import assert from "node:assert/strict";
import crypto from "node:crypto";
import test from "node:test";
import { createApp } from "../src/app.js";

const syncToken = "sync-token-0123456789abcdef0123456789";
const tokenHash = crypto.createHash("sha256").update(syncToken).digest("hex");

function fakeDatabase() {
  let menu = null;
  const client = {
    async query(sql, values = []) {
      if (sql === "BEGIN" || sql === "COMMIT" || sql === "ROLLBACK") return { rows: [], rowCount: 0 };
      if (sql.includes("pg_advisory_xact_lock")) return { rows: [{}], rowCount: 1 };
      if (sql.includes("SELECT * FROM web_menus")) return menu ? { rows: [menu], rowCount: 1 } : { rows: [], rowCount: 0 };
      if (sql.includes("INSERT INTO web_menus")) {
        menu = { id: values[0], current_version: 1, published_version: values[4] ? 1 : null };
        return { rows: [], rowCount: 1 };
      }
      if (sql.includes("UPDATE web_menus SET display_name")) {
        menu = { ...menu, current_version: values[1], published_version: values[3] ? values[1] : menu.published_version };
        return { rows: [], rowCount: 1 };
      }
      return { rows: [], rowCount: 1 };
    },
    release() {}
  };
  return {
    health: async () => new Date(),
    pool: {
      async query(sql) {
        if (sql.includes("FROM web_servers")) {
          return {
            rows: [{ id: "11111111-1111-1111-1111-111111111111", slug: "lobby-1", display_name: "Lobby", sync_token_hash: tokenHash }],
            rowCount: 1
          };
        }
        if (sql.includes("FROM web_menus")) return { rows: [], rowCount: 0 };
        return { rows: [], rowCount: 0 };
      },
      async connect() { return client; }
    }
  };
}

test("server token pulls and publishes menus without the admin key", async (context) => {
  const app = createApp({
    apiKey: "a".repeat(32),
    corsOrigins: ["http://localhost"],
    trustProxy: 1
  }, fakeDatabase());
  const server = app.listen(0, "127.0.0.1");
  await new Promise((resolve) => server.once("listening", resolve));
  context.after(() => new Promise((resolve) => server.close(resolve)));
  const base = `http://127.0.0.1:${server.address().port}`;

  const rejected = await fetch(`${base}/api/sync/lobby-1`);
  assert.equal(rejected.status, 401);

  const pulled = await fetch(`${base}/api/sync/lobby-1`, {
    headers: { "X-siyuan-Sync-Token": syncToken }
  });
  assert.equal(pulled.status, 200);
  assert.deepEqual((await pulled.json()).menus, []);

  const uploaded = await fetch(`${base}/api/sync/lobby-1/menus/main`, {
    method: "PUT",
    headers: { "Content-Type": "application/json", "X-siyuan-Sync-Token": syncToken },
    body: JSON.stringify({ yaml: "menu_title: Main\nsize: 9\nitems: {}\n", publish: true })
  });
  assert.equal(uploaded.status, 200);
  const result = await uploaded.json();
  assert.equal(result.key, "main");
  assert.equal(result.version, 1);
  assert.equal(result.publishedVersion, 1);

  const newer = await fetch(`${base}/api/sync/lobby-1/menus/main`, {
    method: "PUT",
    headers: { "Content-Type": "application/json", "X-siyuan-Sync-Token": syncToken },
    body: JSON.stringify({
      baseVersion: 1,
      yaml: "menu_title: '&6Current'\nsize: 9\nitems: {}\n"
    })
  });
  assert.equal(newer.status, 200);

  const conflicted = await fetch(`${base}/api/sync/lobby-1/menus/main`, {
    method: "PUT",
    headers: { "Content-Type": "application/json", "X-siyuan-Sync-Token": syncToken },
    body: JSON.stringify({
      baseVersion: 1,
      yaml: "menu_title: '&6Outdated'\nsize: 9\nitems: {}\n"
    })
  });
  assert.equal(conflicted.status, 409);
});
