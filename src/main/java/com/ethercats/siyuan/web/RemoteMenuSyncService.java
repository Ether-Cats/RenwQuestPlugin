package com.ethercats.siyuan.web;

import com.ethercats.siyuan.SiYuanPlugin;
import com.ethercats.siyuan.gui.MenuActionCodec;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Pulls published menu versions from the standalone Web control plane. The
 * Minecraft server only makes outbound requests and never exposes an HTTP port.
 */
public final class RemoteMenuSyncService {
    private static final Pattern SAFE_KEY = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_MENU_BYTES = 1024 * 1024;

    private final SiYuanPlugin plugin;
    private final AtomicBoolean syncing = new AtomicBoolean();
    private final Map<String, Integer> managedVersions = new ConcurrentHashMap<>();
    private volatile String etag;
    private volatile boolean enabled;
    private volatile URI endpoint;
    private volatile String syncToken;
    private volatile String serverId;
    private volatile HttpClient client;
    private volatile boolean pushGameEdits;
    private volatile boolean allowRemoteConsoleActions;
    private ExecutorService operations;
    private BukkitTask task;

    public RemoteMenuSyncService(SiYuanPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        ConfigurationSection config = plugin.getConfig().getConfigurationSection("menu-sync");
        if (config == null || !config.getBoolean("enabled", false)) return;

        serverId = config.getString("server-id", "").trim().toLowerCase();
        String baseUrl = config.getString("base-url", "").trim().replaceAll("/+$", "");
        syncToken = System.getenv().getOrDefault("SIYUAN_WEB_SYNC_TOKEN",
            config.getString("sync-token", "")).trim();
        if (!SAFE_KEY.matcher(serverId).matches() || baseUrl.isBlank() || syncToken.length() < 32) {
            plugin.getLogger().severe("[MenuSync] server-id、base-url 或至少 32 字符的 sync-token 配置无效，远程同步未启动");
            return;
        }

        try {
            endpoint = URI.create(baseUrl + "/api/sync/" + serverId);
            validateEndpoint(endpoint, config.getBoolean("allow-insecure-http", false));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().severe("[MenuSync] 地址无效: " + ex.getMessage());
            return;
        }

        int timeoutSeconds = Math.max(2, Math.min(30, config.getInt("timeout-seconds", 8)));
        client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeoutSeconds))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        pushGameEdits = config.getBoolean("push-game-edits", true);
        allowRemoteConsoleActions = config.getBoolean("allow-remote-console-actions", false);
        ManagedMenus previous = loadManagedMenus(manifestPath());
        managedVersions.clear();
        managedVersions.putAll(previous.versions());
        operations = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "siyuan-menu-sync");
            thread.setDaemon(true);
            return thread;
        });
        enabled = true;
        long periodTicks = Math.max(20, config.getLong("poll-seconds", 30)) * 20L;
        task = plugin.getServer().getScheduler().runTaskTimer(
            plugin, () -> submit(() -> synchronize(null)), 40L, periodTicks);
        plugin.getLogger().info("[MenuSync] 已启用服务器 " + serverId + " 的远程菜单拉取");
    }

    public void stop() {
        enabled = false;
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (operations != null) {
            operations.shutdownNow();
            operations = null;
        }
        managedVersions.clear();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void requestSync(CommandSender sender) {
        if (!enabled) {
            sender.sendMessage("§c远程菜单同步未启用");
            return;
        }
        sender.sendMessage("§7正在拉取已发布菜单...");
        submit(() -> synchronize(sender));
    }

    public void pushLocalMenu(String name, String yaml, String actor) {
        if (!enabled || !pushGameEdits || !SAFE_KEY.matcher(name).matches()) return;
        if (yaml == null || yaml.isBlank() || yaml.getBytes(StandardCharsets.UTF_8).length > MAX_MENU_BYTES) {
            plugin.getLogger().warning("[MenuSync] 拒绝上传无效菜单: " + name);
            return;
        }
        try {
            validateMenuYaml(name, yaml);
        } catch (IOException ex) {
            plugin.getLogger().warning("[MenuSync] 拒绝上传菜单 " + name + ": " + ex.getMessage());
            return;
        }
        String capturedYaml = yaml;
        Integer knownVersion = managedVersions.get(name);
        final Integer baseVersion = knownVersion != null && knownVersion >= 1 ? knownVersion : null;
        submit(() -> pushLocalMenuNow(name, capturedYaml, actor, baseVersion));
    }

    private void pushLocalMenuNow(String name, String yaml, String actor, Integer baseVersion) {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("yaml", yaml);
            payload.addProperty("displayName", name);
            payload.addProperty("publish", true);
            if (baseVersion != null) payload.addProperty("baseVersion", baseVersion);
            String safeActor = actor == null ? "game" : actor.replaceAll("[^A-Za-z0-9_.-]", "");
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "/menus/" + name))
                .timeout(Duration.ofSeconds(Math.max(3, plugin.getConfig().getInt("menu-sync.timeout-seconds", 8))))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-siyuan-Sync-Token", syncToken)
                .header("X-siyuan-Actor", safeActor.isBlank() ? "game" : "game-" + safeActor)
                .PUT(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            String responseBody;
            try (InputStream stream = response.body()) {
                responseBody = new String(readLimited(stream, 1024 * 1024), StandardCharsets.UTF_8);
            }
            if (response.statusCode() == 409) {
                plugin.getLogger().warning("[MenuSync] Web 拒绝上传 " + name
                    + "，远端版本已变化；请先执行 /gc menu sync 后再编辑");
                return;
            }
            if (response.statusCode() != 200) throw new IOException("Web 服务返回 HTTP " + response.statusCode());
            JsonObject result = JsonParser.parseString(responseBody).getAsJsonObject();
            if (result.has("version") && result.get("version").getAsInt() > 0) {
                managedVersions.put(name, result.get("version").getAsInt());
                writeManagedMenus(manifestPath(), managedVersions);
            }
            etag = null;
            plugin.getLogger().info("[MenuSync] 游戏内菜单已发布到 Web: " + name);
            synchronize(null);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "[MenuSync] 上传菜单失败 " + name + ": " + ex.getMessage());
        }
    }

    private void submit(Runnable operation) {
        ExecutorService executor = operations;
        if (!enabled || executor == null || executor.isShutdown()) return;
        try {
            executor.execute(operation);
        } catch (RejectedExecutionException ignored) {
            // A reload or shutdown raced with this submission.
        }
    }

    private void synchronize(CommandSender sender) {
        if (!enabled || !syncing.compareAndSet(false, true)) {
            if (sender != null) send(sender, "§e已有同步任务正在执行");
            return;
        }
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(Math.max(3,
                    plugin.getConfig().getInt("menu-sync.timeout-seconds", 8))))
                .header("Accept", "application/json")
                .header("X-siyuan-Sync-Token", syncToken)
                .GET();
            if (etag != null) request.header("If-None-Match", etag);
            HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            String body;
            try (InputStream stream = response.body()) {
                if (response.statusCode() == 304) {
                    if (sender != null) send(sender, "§a菜单已经是最新版本");
                    return;
                }
                if (response.statusCode() != 200) {
                    throw new IOException("Web 服务返回 HTTP " + response.statusCode());
                }
                body = new String(readLimited(stream, MAX_RESPONSE_BYTES), StandardCharsets.UTF_8);
            }
            int updated = applyResponse(body);
            etag = response.headers().firstValue("ETag").orElse(null);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                plugin.getDynamicMenuManager().reload();
                if (sender != null) sender.sendMessage("§a远程菜单同步完成，共 " + updated + " 个菜单");
            });
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "[MenuSync] 同步失败: " + ex.getMessage());
            if (sender != null) send(sender, "§c远程菜单同步失败，请检查控制台");
        } finally {
            syncing.set(false);
        }
    }

    private int applyResponse(String body) throws IOException {
        JsonObject root;
        try {
            root = JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException ex) {
            throw new IOException("响应 JSON 无效", ex);
        }
        JsonArray menus = root.getAsJsonArray("menus");
        if (menus == null) throw new IOException("响应缺少 menus");

        Map<String, ManagedMenu> files = new HashMap<>();
        for (JsonElement element : menus) {
            JsonObject menu = element.getAsJsonObject();
            String key = menu.has("key") ? menu.get("key").getAsString().toLowerCase() : "";
            String yaml = menu.has("yaml") ? menu.get("yaml").getAsString() : "";
            int version = menu.has("version") ? menu.get("version").getAsInt() : -1;
            if (!SAFE_KEY.matcher(key).matches()) throw new IOException("远程菜单标识无效");
            if (version < 1) throw new IOException("菜单 " + key + " 缺少有效版本号");
            if (yaml.isBlank() || yaml.getBytes(StandardCharsets.UTF_8).length > MAX_MENU_BYTES) {
                throw new IOException("菜单 " + key + " 内容为空或超过 1MB");
            }
            validateMenuYaml(key, yaml);
            String managedYaml = "siyuan_menu_key: " + key + "\n" + yaml;
            if (files.put(key, new ManagedMenu(managedYaml, version)) != null) {
                throw new IOException("远程菜单标识重复: " + key);
            }
        }

        Path menuDirectory = plugin.getDataFolder().toPath().resolve("menus").toAbsolutePath().normalize();
        Path remoteDirectory = menuDirectory.resolve(".remote").resolve(serverId).normalize();
        if (!remoteDirectory.startsWith(menuDirectory)) throw new IOException("远程菜单目录无效");
        Files.createDirectories(menuDirectory);
        Path manifest = manifestPath();
        ManagedMenus previous = loadManagedMenus(manifest);
        rejectSourceConflicts(menuDirectory, remoteDirectory, files.keySet(), previous.versions().keySet());
        Files.createDirectories(remoteDirectory);

        for (Map.Entry<String, ManagedMenu> entry : files.entrySet()) {
            Path destination = remoteDirectory.resolve(entry.getKey() + ".yml");
            writeAtomically(destination, entry.getValue().yaml());
        }
        for (String oldKey : previous.versions().keySet()) {
            if (!files.containsKey(oldKey)) Files.deleteIfExists(remoteDirectory.resolve(oldKey + ".yml"));
        }
        // Move only files that a prior siyuan sync explicitly marked as owned.
        for (String oldKey : previous.versions().keySet()) {
            deleteLegacyManagedFile(menuDirectory.resolve(oldKey + ".yml"), oldKey);
            deleteLegacyManagedFile(menuDirectory.resolve(oldKey + ".yaml"), oldKey);
        }
        Map<String, Integer> versions = new HashMap<>();
        files.forEach((key, value) -> versions.put(key, value.version()));
        writeManagedMenus(manifest, versions);
        managedVersions.clear();
        managedVersions.putAll(versions);
        return files.size();
    }

    private void validateMenuYaml(String key, String yaml) throws IOException {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(yaml);
        } catch (Exception ex) {
            throw new IOException("菜单 " + key + " 的 YAML 无效", ex);
        }
        int size = normalizeSize(config.getInt("size", config.getInt("menu_size", 54)));
        String title = config.getString("menu_title", config.getString("title", ""));
        if (title != null && title.length() > 128) throw new IOException("菜单 " + key + " 的标题过长");
        Set<Integer> slots = new HashSet<>();
        if (config.contains("items") && config.getConfigurationSection("items") == null) {
            throw new IOException("菜单 " + key + " 的 items 不是对象");
        }
        validateItemSections(key, config, size, slots);
        ConfigurationSection nested = config.getConfigurationSection("items");
        if (nested != null) validateItemSections(key, nested, size, slots);
        if (!allowRemoteConsoleActions && (containsConsoleAction(config.getStringList("open_commands"))
            || containsConsoleAction(config.getStringList("close_commands")))) {
            throw new IOException("菜单 " + key + " 包含远程控制台动作，需显式开启 allow-remote-console-actions");
        }
    }

    private void validateItemSections(String key, ConfigurationSection source, int size, Set<Integer> slots) throws IOException {
        for (String itemKey : source.getKeys(false)) {
            ConfigurationSection item = source.getConfigurationSection(itemKey);
            if (item == null || !item.contains("slot")) continue;
            int slot = item.getInt("slot", -1);
            if (slot < 0 || slot >= size || !slots.add(slot)) {
                throw new IOException("菜单 " + key + " 存在越界或重复槽位");
            }
            String material = item.getString("material", "STONE");
            if (material == null || Material.matchMaterial(material) == null) {
                throw new IOException("菜单 " + key + " 的材质无效: " + material);
            }
            if (!allowRemoteConsoleActions && (containsConsoleAction(item.getStringList("left_click_commands"))
                || containsConsoleAction(item.getStringList("right_click_commands"))
                || containsConsoleAction(item.getStringList("click_commands")))) {
                throw new IOException("菜单 " + key + " 包含远程控制台动作，需显式开启 allow-remote-console-actions");
            }
        }
    }

    private boolean containsConsoleAction(Iterable<String> actions) {
        for (String action : actions) {
            String normalized = MenuActionCodec.normalize(action);
            if (normalized.startsWith("console:") || normalized.startsWith("op:")) return true;
        }
        return false;
    }

    private ManagedMenus loadManagedMenus(Path manifest) {
        Map<String, Integer> versions = new HashMap<>();
        if (!Files.isRegularFile(manifest)) return new ManagedMenus(versions);
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(manifest));
            if (parsed.isJsonArray()) {
                for (JsonElement element : parsed.getAsJsonArray()) {
                    String key = element.getAsString();
                    if (SAFE_KEY.matcher(key).matches()) versions.put(key, -1);
                }
            } else if (parsed.isJsonObject()) {
                JsonObject root = parsed.getAsJsonObject();
                JsonObject menus = root.has("menus") && root.get("menus").isJsonObject()
                    ? root.getAsJsonObject("menus") : root;
                for (Map.Entry<String, JsonElement> entry : menus.entrySet()) {
                    if (SAFE_KEY.matcher(entry.getKey()).matches() && entry.getValue().isJsonPrimitive()) {
                        versions.put(entry.getKey(), entry.getValue().getAsInt());
                    }
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("[MenuSync] 无法读取旧同步清单，将保留现有本地菜单");
            versions.clear();
        }
        return new ManagedMenus(versions);
    }

    private void writeManagedMenus(Path manifest, Map<String, Integer> versions) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("format", 2);
        JsonObject menus = new JsonObject();
        versions.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .forEach(entry -> menus.addProperty(entry.getKey(), entry.getValue()));
        root.add("menus", menus);
        writeAtomically(manifest, root.toString());
    }

    private void rejectSourceConflicts(Path menuDirectory, Path remoteDirectory, Set<String> incomingKeys,
                                       Set<String> previouslyManagedKeys) throws IOException {
        try (var paths = Files.walk(menuDirectory)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String fileName = path.getFileName().toString().toLowerCase();
                if (!fileName.endsWith(".yml") && !fileName.endsWith(".yaml")) continue;
                if (path.startsWith(remoteDirectory)) continue;
                Path relative = menuDirectory.relativize(path);
                if (relative.getNameCount() > 0 && relative.getName(0).toString().equals(".backups")) continue;
                String key = readMenuKey(path);
                if (!incomingKeys.contains(key)) continue;
                if (previouslyManagedKeys.contains(key) && isLegacyManagedFile(path, key)) continue;
                throw new IOException("远程菜单 " + key + " 与本地文件 " + relative
                    + " 冲突，已拒绝覆盖；请改名或迁移本地菜单");
            }
        }
    }

    private String readMenuKey(Path path) {
        try {
            if (Files.size(path) > MAX_MENU_BYTES) return "";
            YamlConfiguration config = new YamlConfiguration();
            config.loadFromString(Files.readString(path));
            String fileName = path.getFileName().toString().replaceFirst("(?i)\\.(yml|yaml)$", "");
            String key = config.getString("siyuan_menu_key", fileName).trim().toLowerCase();
            return SAFE_KEY.matcher(key).matches() ? key : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean isLegacyManagedFile(Path path, String key) {
        if (!Files.isRegularFile(path)) return false;
        try {
            if (Files.size(path) > MAX_MENU_BYTES) return false;
            YamlConfiguration config = new YamlConfiguration();
            config.loadFromString(Files.readString(path));
            return key.equals(config.getString("siyuan_menu_key", "").trim().toLowerCase());
        } catch (Exception ignored) {
            return false;
        }
    }

    private void deleteLegacyManagedFile(Path path, String key) throws IOException {
        if (isLegacyManagedFile(path, key)) Files.deleteIfExists(path);
    }

    private Path manifestPath() {
        return plugin.getDataFolder().toPath().resolve(".menu-sync-" + serverId + ".json");
    }

    private int normalizeSize(int requestedSize) {
        int bounded = Math.max(9, Math.min(54, requestedSize));
        return ((bounded + 8) / 9) * 9;
    }

    private void writeAtomically(Path destination, String content) throws IOException {
        Path temporary = Files.createTempFile(destination.getParent(), ".siyuan-menu-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void validateEndpoint(URI uri, boolean allowInsecure) {
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null) {
            throw new IllegalArgumentException("必须是无用户信息的绝对 HTTP(S) 地址");
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) return;
        if (!"http".equalsIgnoreCase(uri.getScheme())) throw new IllegalArgumentException("仅支持 HTTP(S)");
        if (!allowInsecure && !isLoopback(uri.getHost())) {
            throw new IllegalArgumentException("远程服务必须使用 HTTPS，或显式启用 allow-insecure-http");
        }
    }

    private boolean isLoopback(String host) {
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (Exception ignored) {
            return false;
        }
    }

    private byte[] readLimited(InputStream stream, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = stream.read(buffer)) >= 0) {
            total += read;
            if (total > maximum) throw new IOException("Web 响应超过 " + maximum + " 字节");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private void send(CommandSender sender, String message) {
        plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(message));
    }

    private record ManagedMenu(String yaml, int version) {
    }

    private record ManagedMenus(Map<String, Integer> versions) {
        private ManagedMenus {
            versions = Map.copyOf(versions);
        }
    }
}
