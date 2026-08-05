import assert from "node:assert/strict";
import test from "node:test";
import { loadConfig } from "../src/config.js";

const baseEnvironment = {
  SIYUAN_WEB_API_KEY: "a".repeat(32),
  SIYUAN_WEB_ADMIN_USER: "admin",
  SIYUAN_WEB_ADMIN_PASSWORD: "correct-horse-battery-staple",
  SIYUAN_AI_ENABLED: "false"
};

function withEnvironment(values, callback) {
  const original = { ...process.env };
  Object.assign(process.env, baseEnvironment, values);
  try {
    return callback();
  } finally {
    for (const key of Object.keys(process.env)) {
      if (!(key in original)) delete process.env[key];
    }
    Object.assign(process.env, original);
  }
}

test("configuration accepts PostgreSQL and MySQL connection choices", () => {
  withEnvironment({
    DATABASE_TYPE: "postgresql",
    DATABASE_URL: "postgresql://siyuan:test@postgres.example.com:5432/siyuan"
  }, () => {
    const config = loadConfig();
    assert.equal(config.databaseType, "postgres");
  });

  withEnvironment({
    DATABASE_TYPE: "mysql",
    DATABASE_URL: "mysql://siyuan:test@mysql.example.com:3306/siyuan"
  }, () => {
    const config = loadConfig();
    assert.equal(config.databaseType, "mysql");
  });
});

test("configuration rejects a database URL for the wrong engine", () => {
  withEnvironment({
    DATABASE_TYPE: "mysql",
    DATABASE_URL: "postgresql://siyuan:test@postgres.example.com:5432/siyuan"
  }, () => {
    assert.throws(() => loadConfig(), /DATABASE_URL must use a mysql connection URL/);
  });
});
