function booleanEnv(name, defaultValue = false) {
  const value = process.env[name];
  if (value == null) return defaultValue;
  return ["1", "true", "yes", "on"].includes(value.toLowerCase());
}

function required(name) {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`${name} is required`);
  return value;
}

function positiveInteger(name, fallback, min, max) {
  const parsed = Number.parseInt(process.env[name] || String(fallback), 10);
  if (!Number.isInteger(parsed) || parsed < min || parsed > max) {
    throw new Error(`${name} must be an integer between ${min} and ${max}`);
  }
  return parsed;
}

function aiConfig() {
  const enabled = booleanEnv("SIYUAN_AI_ENABLED", false);
  if (!enabled) return { enabled: false };
  const baseUrl = (process.env.SIYUAN_AI_BASE_URL || "https://api.openai.com/v1").trim().replace(/\/+$/, "");
  try {
    new URL(baseUrl);
  } catch {
    throw new Error("SIYUAN_AI_BASE_URL must be an absolute URL");
  }
  return {
    enabled: true,
    baseUrl,
    apiKey: required("SIYUAN_AI_API_KEY"),
    model: required("SIYUAN_AI_MODEL"),
    timeoutMs: positiveInteger("SIYUAN_AI_TIMEOUT_MS", 20_000, 1_000, 120_000),
    maxPromptChars: positiveInteger("SIYUAN_AI_MAX_PROMPT_CHARS", 2_000, 100, 20_000),
    maxTokens: positiveInteger("SIYUAN_AI_MAX_TOKENS", 1_200, 128, 8_000),
    rateLimitPerMinute: positiveInteger("SIYUAN_AI_RATE_LIMIT_PER_MINUTE", 6, 1, 120)
  };
}

function databaseType() {
  const requested = (process.env.DATABASE_TYPE || "postgres").trim().toLowerCase();
  if (requested === "postgresql") return "postgres";
  if (!new Set(["postgres", "mysql"]).has(requested)) {
    throw new Error("DATABASE_TYPE must be postgres or mysql");
  }
  return requested;
}

function databaseUrl(type) {
  const value = required("DATABASE_URL");
  let protocol;
  try {
    protocol = new URL(value).protocol.replace(/:$/, "").toLowerCase();
  } catch {
    throw new Error("DATABASE_URL must be an absolute connection URL");
  }
  const validProtocols = type === "mysql" ? ["mysql"] : ["postgres", "postgresql"];
  if (!validProtocols.includes(protocol)) {
    throw new Error(`DATABASE_URL must use a ${type} connection URL`);
  }
  return value;
}

export function loadConfig() {
  const apiKey = required("SIYUAN_WEB_API_KEY");
  if (apiKey.length < 32) throw new Error("SIYUAN_WEB_API_KEY must contain at least 32 characters");
  const type = databaseType();

  return {
    port: Number.parseInt(process.env.PORT || "8080", 10),
    databaseUrl: databaseUrl(type),
    databaseType: type,
    apiKey,
    corsOrigins: (process.env.CORS_ORIGINS || "http://localhost:8080")
      .split(",")
      .map((origin) => origin.trim())
      .filter(Boolean),
    trustProxy: Number.parseInt(process.env.TRUST_PROXY || "1", 10),
    databaseSsl: booleanEnv("DB_SSL") ? { rejectUnauthorized: booleanEnv("DB_SSL_VERIFY", true) } : false,
    adminUsername: required("SIYUAN_WEB_ADMIN_USER"),
    adminPassword: required("SIYUAN_WEB_ADMIN_PASSWORD"),
    sessionSecure: booleanEnv("SIYUAN_WEB_SESSION_SECURE", false),
    sessionTtlHours: positiveInteger("SIYUAN_WEB_SESSION_TTL_HOURS", 12, 1, 720),
    ai: aiConfig()
  };
}
