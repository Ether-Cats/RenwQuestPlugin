import assert from "node:assert/strict";
import test from "node:test";
import { hashPassword } from "../src/auth.js";
import { createApp } from "../src/app.js";

const password = "correct-horse-battery-staple";

function testConfig() {
  return {
    apiKey: "a".repeat(32),
    corsOrigins: ["http://localhost"],
    trustProxy: 1,
    sessionSecure: false,
    sessionTtlHours: 12,
    ai: { enabled: false }
  };
}

function fakeDatabase() {
  const user = {
    id: "11111111-1111-1111-1111-111111111111",
    username: "admin",
    password_hash: hashPassword(password),
    active: true
  };
  const sessions = new Map();
  return {
    dialect: "postgres",
    health: async () => new Date("2026-01-01T00:00:00Z"),
    pool: { query: async () => ({ rows: [], rowCount: 0 }) },
    async findUser(username) {
      return username === user.username ? user : null;
    },
    async createSession(session) {
      sessions.set(session.tokenHash, {
        csrf_token_hash: session.csrfTokenHash,
        username: user.username,
        active: true
      });
    },
    async findSession(tokenHash) {
      return sessions.get(tokenHash) || null;
    },
    async deleteSession(tokenHash) {
      sessions.delete(tokenHash);
    }
  };
}

test("health is public, legacy API access remains available, and browser login uses a session", async (context) => {
  const app = createApp(testConfig(), fakeDatabase());
  const server = app.listen(0, "127.0.0.1");
  await new Promise((resolve) => server.once("listening", resolve));
  context.after(() => new Promise((resolve) => server.close(resolve)));
  const address = server.address();
  const base = `http://127.0.0.1:${address.port}`;

  const health = await fetch(`${base}/health`);
  assert.equal(health.status, 200);
  assert.equal((await health.json()).database, "ok");

  assert.equal((await fetch(`${base}/api/session`)).status, 401);
  const legacy = await fetch(`${base}/api/session`, { headers: { "X-API-Key": "a".repeat(32) } });
  assert.deepEqual(await legacy.json(), { authenticated: true, authMethod: "api", user: null });

  const rejected = await fetch(`${base}/api/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username: "admin", password: "wrong-password" })
  });
  assert.equal(rejected.status, 401);

  const login = await fetch(`${base}/api/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username: "admin", password })
  });
  assert.equal(login.status, 200);
  const session = await login.json();
  assert.equal(session.user.username, "admin");
  assert.match(session.csrfToken, /^[A-Za-z0-9_-]{32,}$/);
  const cookie = login.headers.get("set-cookie");
  assert.match(cookie, /^siyuan_session=/);
  assert.match(cookie, /HttpOnly/);
  assert.match(cookie, /SameSite=Lax/);

  const browserSession = await fetch(`${base}/api/session`, { headers: { Cookie: cookie } });
  assert.deepEqual(await browserSession.json(), {
    authenticated: true,
    authMethod: "session",
    user: { username: "admin" }
  });

  const csrfRejected = await fetch(`${base}/api/auth/logout`, {
    method: "POST",
    headers: { Cookie: cookie, "X-siyuan-CSRF": "incorrect" }
  });
  assert.equal(csrfRejected.status, 403);

  const logout = await fetch(`${base}/api/auth/logout`, {
    method: "POST",
    headers: { Cookie: cookie, "X-siyuan-CSRF": session.csrfToken }
  });
  assert.equal(logout.status, 204);
  assert.match(logout.headers.get("set-cookie"), /Max-Age=0/);
  assert.equal((await fetch(`${base}/api/session`, { headers: { Cookie: cookie } })).status, 401);
});
