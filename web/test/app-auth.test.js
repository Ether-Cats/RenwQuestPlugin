import assert from "node:assert/strict";
import test from "node:test";
import { createApp } from "../src/app.js";

test("health is public while management API requires its key", async (context) => {
  const database = {
    health: async () => new Date("2026-01-01T00:00:00Z"),
    pool: { query: async () => ({ rows: [], rowCount: 0 }) }
  };
  const app = createApp({
    apiKey: "a".repeat(32),
    corsOrigins: ["http://localhost"],
    trustProxy: 1
  }, database);
  const server = app.listen(0, "127.0.0.1");
  await new Promise((resolve) => server.once("listening", resolve));
  context.after(() => new Promise((resolve) => server.close(resolve)));
  const address = server.address();
  const base = `http://127.0.0.1:${address.port}`;

  const health = await fetch(`${base}/health`);
  assert.equal(health.status, 200);
  assert.equal((await health.json()).database, "ok");

  assert.equal((await fetch(`${base}/api/session`)).status, 401);
  const authenticated = await fetch(`${base}/api/session`, { headers: { "X-API-Key": "a".repeat(32) } });
  assert.equal(authenticated.status, 200);
  assert.equal((await authenticated.json()).authenticated, true);
});
