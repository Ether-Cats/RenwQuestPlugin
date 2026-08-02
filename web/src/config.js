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

export function loadConfig() {
  const apiKey = required("SIYUAN_WEB_API_KEY");
  if (apiKey.length < 32) throw new Error("SIYUAN_WEB_API_KEY must contain at least 32 characters");

  return {
    port: Number.parseInt(process.env.PORT || "8080", 10),
    databaseUrl: required("DATABASE_URL"),
    apiKey,
    corsOrigins: (process.env.CORS_ORIGINS || "http://localhost:8080")
      .split(",")
      .map((origin) => origin.trim())
      .filter(Boolean),
    trustProxy: Number.parseInt(process.env.TRUST_PROXY || "1", 10),
    databaseSsl: booleanEnv("DB_SSL") ? { rejectUnauthorized: booleanEnv("DB_SSL_VERIFY", true) } : false
  };
}
