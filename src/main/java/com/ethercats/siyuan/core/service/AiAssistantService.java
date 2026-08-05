package com.ethercats.siyuan.core.service;

import com.ethercats.siyuan.SiYuanPlugin;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional OpenAI-compatible in-game assistant. It has no access to commands,
 * Vault, menus, or any player data beyond the question the sender submits.
 */
public final class AiAssistantService {
    private static final int MAX_HTTP_RESPONSE_BYTES = 1_024 * 1_024;
    private static final int MIN_TIMEOUT_SECONDS = 3;
    private static final int MAX_TIMEOUT_SECONDS = 60;
    private static final int MIN_PROMPT_CHARS = 64;
    private static final int MAX_PROMPT_CHARS = 4_000;
    private static final int MIN_RESPONSE_CHARS = 128;
    private static final int MAX_RESPONSE_CHARS = 4_000;
    private static final int MIN_RATE_LIMIT = 1;
    private static final int MAX_RATE_LIMIT = 60;
    private static final int MIN_MAX_TOKENS = 32;
    private static final int MAX_MAX_TOKENS = 2_048;

    private final SiYuanPlugin plugin;
    private final Map<String, RateWindow> rateWindows = new ConcurrentHashMap<>();
    private volatile boolean enabled;
    private volatile String disabledReason = "未启用";
    private volatile URI endpoint;
    private volatile HttpClient client;
    private volatile String apiKey = "";
    private volatile String model = "";
    private volatile String systemPrompt = "";
    private volatile int timeoutSeconds = 20;
    private volatile int maxPromptChars = 1_000;
    private volatile int maxResponseChars = 1_600;
    private volatile int rateLimitPerMinute = 6;
    private volatile int maxTokens = 512;

    public AiAssistantService(SiYuanPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        enabled = false;
        rateWindows.clear();
        apiKey = "";
        client = null;
        endpoint = null;
        model = "";
        systemPrompt = "";
        ConfigurationSection config = plugin.getConfig().getConfigurationSection("ai-assistant");
        if (config == null || !config.getBoolean("enabled", false)) {
            disabledReason = "配置未启用";
            return;
        }

        String configuredModel = cleanSingleLine(config.getString("model", ""));
        String environmentKey = System.getenv("SIYUAN_AI_API_KEY");
        String configuredKey = environmentKey == null ? "" : environmentKey.trim();
        if (configuredKey.isBlank()) {
            disabledReason = "缺少 SIYUAN_AI_API_KEY 环境变量";
            plugin.getLogger().warning("[AI] ai-assistant 已启用，但未设置 SIYUAN_AI_API_KEY；服务未启动");
            return;
        }
        if (configuredModel.isBlank() || configuredModel.length() > 128) {
            disabledReason = "模型名称无效";
            plugin.getLogger().warning("[AI] ai-assistant.model 未配置或长度无效；服务未启动");
            return;
        }

        try {
            endpoint = buildEndpoint(config.getString("base-url", ""),
                config.getBoolean("allow-insecure-http", false));
        } catch (IllegalArgumentException ex) {
            disabledReason = "接口地址无效";
            plugin.getLogger().warning("[AI] OpenAI 兼容地址无效：" + ex.getMessage());
            return;
        }

        timeoutSeconds = clamp(config.getInt("timeout-seconds", 20), MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS);
        maxPromptChars = clamp(config.getInt("max-prompt-chars", 1_000), MIN_PROMPT_CHARS, MAX_PROMPT_CHARS);
        maxResponseChars = clamp(config.getInt("max-response-chars", 1_600), MIN_RESPONSE_CHARS, MAX_RESPONSE_CHARS);
        rateLimitPerMinute = clamp(config.getInt("rate-limit-per-minute", 6), MIN_RATE_LIMIT, MAX_RATE_LIMIT);
        maxTokens = clamp(config.getInt("max-tokens", 512), MIN_MAX_TOKENS, MAX_MAX_TOKENS);
        model = configuredModel;
        apiKey = configuredKey;
        systemPrompt = truncate(cleanText(config.getString("system-prompt", "")), 2_000);
        client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeoutSeconds))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        disabledReason = "";
        enabled = true;
        plugin.getLogger().info("[AI] 已启用 OpenAI 兼容游戏内助手，模型 " + model + "，目标 " + endpointDescription());
    }

    public void shutdown() {
        enabled = false;
        rateWindows.clear();
        apiKey = "";
        client = null;
        endpoint = null;
        disabledReason = "插件已停止";
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getDisabledReason() {
        return disabledReason;
    }

    public String getModel() {
        return model;
    }

    public String endpointDescription() {
        URI currentEndpoint = endpoint;
        if (currentEndpoint == null || currentEndpoint.getHost() == null) return "未配置";
        String port = currentEndpoint.getPort() > 0 ? ":" + currentEndpoint.getPort() : "";
        return currentEndpoint.getScheme() + "://" + currentEndpoint.getHost() + port;
    }

    public AskResult ask(CommandSender sender, String rawQuestion) {
        if (!enabled || client == null || endpoint == null) {
            return AskResult.failure("AI 未启用：" + disabledReason);
        }
        String question = cleanText(rawQuestion);
        if (question.isBlank()) return AskResult.failure("请提供问题内容");
        if (question.length() > maxPromptChars) {
            return AskResult.failure("问题不能超过 " + maxPromptChars + " 个字符");
        }

        String rateKey = sender instanceof Player player
            ? "player:" + player.getUniqueId()
            : "sender:" + sender.getName().toLowerCase(Locale.ROOT);
        if (!consumeRateLimit(rateKey)) {
            return AskResult.failure("请求过于频繁，请稍后再试");
        }

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(createRequestBody(question), StandardCharsets.UTF_8))
                .build();
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("[AI] 无法构建请求：" + ex.getMessage());
            return AskResult.failure("AI 请求配置无效");
        }

        try {
            client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenApplyAsync(this::readResponse)
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new CompletionException(new IOException("HTTP " + response.statusCode()));
                    }
                    return truncate(cleanText(extractContent(response.bodyText())), maxResponseChars);
                })
                .whenComplete((answer, error) -> deliverResponse(sender, answer, error));
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("[AI] 请求未能发出：" + ex.getMessage());
            return AskResult.failure("AI 请求未能发出");
        }
        return AskResult.success();
    }

    static URI buildEndpoint(String configuredBaseUrl, boolean allowInsecureHttp) {
        if (configuredBaseUrl == null || configuredBaseUrl.isBlank()) {
            throw new IllegalArgumentException("base-url 不能为空");
        }
        URI base = URI.create(configuredBaseUrl.trim());
        String scheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(allowInsecureHttp && scheme.equals("http"))) {
            throw new IllegalArgumentException("仅允许 HTTPS；本地调试需显式开启 allow-insecure-http");
        }
        if (base.getHost() == null || base.getRawAuthority() == null || base.getRawUserInfo() != null
            || base.getRawQuery() != null || base.getRawFragment() != null) {
            throw new IllegalArgumentException("地址必须是不含账号、查询参数或片段的完整 HTTP 地址");
        }
        String path = base.getRawPath() == null ? "" : base.getRawPath().replaceAll("/+$", "");
        if (!path.endsWith("/chat/completions")) path += "/chat/completions";
        if (!path.startsWith("/")) path = "/" + path;
        return URI.create(scheme + "://" + base.getRawAuthority() + path);
    }

    static String extractContent(String responseBody) {
        JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty() || !choices.get(0).isJsonObject()) {
            throw new IllegalArgumentException("响应没有 choices[0]");
        }
        JsonObject choice = choices.get(0).getAsJsonObject();
        JsonObject message = choice.has("message") && choice.get("message").isJsonObject()
            ? choice.getAsJsonObject("message") : null;
        JsonElement content = message == null ? null : message.get("content");
        if (content == null || content.isJsonNull() || !content.isJsonPrimitive()) {
            throw new IllegalArgumentException("响应没有文本内容");
        }
        String value = content.getAsString();
        if (value == null || value.isBlank()) throw new IllegalArgumentException("响应文本为空");
        return value;
    }

    static List<String> splitForChat(String value, int lineLength) {
        String text = cleanText(value);
        int limit = Math.max(40, Math.min(240, lineLength));
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\\n", -1)) {
            String remaining = paragraph.trim();
            if (remaining.isEmpty()) continue;
            while (remaining.length() > limit) {
                int splitAt = remaining.lastIndexOf(' ', limit);
                if (splitAt < limit / 2) splitAt = limit;
                lines.add(remaining.substring(0, splitAt).trim());
                remaining = remaining.substring(splitAt).trim();
            }
            if (!remaining.isEmpty()) lines.add(remaining);
        }
        return lines.isEmpty() ? List.of("（空回复）") : List.copyOf(lines);
    }

    private String createRequestBody(String question) {
        JsonObject request = new JsonObject();
        request.addProperty("model", model);
        request.addProperty("max_tokens", maxTokens);
        JsonArray messages = new JsonArray();
        if (!systemPrompt.isBlank()) messages.add(message("system", systemPrompt));
        messages.add(message("user", question));
        request.add("messages", messages);
        return request.toString();
    }

    private JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private HttpResponseText readResponse(HttpResponse<InputStream> response) {
        try (InputStream body = response.body()) {
            return new HttpResponseText(response.statusCode(), readLimited(body, MAX_HTTP_RESPONSE_BYTES));
        } catch (IOException ex) {
            throw new CompletionException(ex);
        }
    }

    private void deliverResponse(CommandSender sender, String answer, Throwable error) {
        if (!plugin.isEnabled()) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (sender instanceof Player player && !player.isOnline()) return;
            if (error != null) {
                plugin.getLogger().warning("[AI] 请求失败：" + conciseError(error));
                sender.sendMessage("§cAI 请求失败，请检查配置或稍后重试");
                return;
            }
            sender.sendMessage("§aAI 回复：");
            for (String line : splitForChat(answer, 220)) sender.sendMessage("§f" + line);
        });
    }

    private boolean consumeRateLimit(String key) {
        long now = System.currentTimeMillis();
        AtomicBoolean accepted = new AtomicBoolean();
        rateWindows.compute(key, (ignored, previous) -> {
            if (previous == null || now - previous.startedAt() >= 60_000L) {
                accepted.set(true);
                return new RateWindow(now, 1);
            }
            if (previous.count() >= rateLimitPerMinute) return previous;
            accepted.set(true);
            return new RateWindow(previous.startedAt(), previous.count() + 1);
        });
        if (rateWindows.size() > 2_048) {
            rateWindows.entrySet().removeIf(entry -> now - entry.getValue().startedAt() >= 120_000L);
        }
        return accepted.get();
    }

    private static byte[] readLimited(InputStream stream, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8_192];
        int read;
        while ((read = stream.read(buffer)) >= 0) {
            if (output.size() + read > maxBytes) throw new IOException("AI 响应超过大小限制");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String cleanText(String value) {
        if (value == null) return "";
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '§') {
                result.append('?');
            } else if (Character.isISOControl(character) && character != '\n' && character != '\t') {
                result.append(' ');
            } else if (character == '\t') {
                result.append(' ');
            } else {
                result.append(character);
            }
        }
        return result.toString().trim();
    }

    private static String cleanSingleLine(String value) {
        return cleanText(value).replace('\n', ' ').trim();
    }

    private static String truncate(String value, int maximumLength) {
        if (value.length() <= maximumLength) return value;
        return value.substring(0, Math.max(0, maximumLength - 3)).trim() + "...";
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String conciseError(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && (cause instanceof CompletionException || cause.getClass().getName().contains("ExecutionException"))) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + cleanSingleLine(message));
    }

    public record AskResult(boolean accepted, String message) {
        static AskResult success() {
            return new AskResult(true, "");
        }

        static AskResult failure(String message) {
            return new AskResult(false, message);
        }
    }

    private record RateWindow(long startedAt, int count) {
    }

    private record HttpResponseText(int statusCode, byte[] payload) {
        private String bodyText() {
            return new String(payload, StandardCharsets.UTF_8);
        }
    }
}
