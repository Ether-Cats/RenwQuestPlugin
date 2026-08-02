import crypto from "node:crypto";
import path from "node:path";
import { fileURLToPath } from "node:url";
import cors from "cors";
import express from "express";
import helmet from "helmet";
import { createAiService } from "./ai.js";
import {
  clearSessionCookie,
  hashToken,
  randomToken,
  readCookie,
  setSessionCookie,
  validateUsername,
  verifyPassword
} from "./auth.js";
import { exportMenu, normalizeMenu, parseMenu, validateKey } from "./menu-codec.js";

const rootDirectory = path.dirname(path.dirname(fileURLToPath(import.meta.url)));

class HttpError extends Error {
  constructor(status, message, details) {
    super(message);
    this.status = status;
    this.details = details;
  }
}

function sameSecret(actual, expected) {
  const left = Buffer.from(String(actual || ""));
  const right = Buffer.from(expected);
  return left.length === right.length && crypto.timingSafeEqual(left, right);
}

function actor(request) {
  if (request.auth?.username) return request.auth.username;
  return String(request.get("x-siyuan-actor") || "web-admin").replace(/[^\p{L}\p{N}_.@-]/gu, "").slice(0, 128) || "web-admin";
}

function requiredText(value, field, maxLength = 128) {
  const text = String(value || "").trim();
  if (!text) throw new HttpError(400, `${field} 不能为空`);
  if (text.length > maxLength) throw new HttpError(400, `${field} 不能超过 ${maxLength} 字符`);
  return text;
}

function stableKey(value, label) {
  try {
    return validateKey(value, label);
  } catch (error) {
    throw new HttpError(400, error.message);
  }
}

function menuDocument(value) {
  try {
    return normalizeMenu(value);
  } catch (error) {
    throw new HttpError(400, `菜单内容无效: ${error.message}`);
  }
}

async function transaction(pool, callback) {
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const result = await callback(client);
    await client.query("COMMIT");
    return result;
  } catch (error) {
    await client.query("ROLLBACK");
    throw error;
  } finally {
    client.release();
  }
}

async function audit(client, request, action, serverId, menuId, details = {}) {
  await client.query(
    "INSERT INTO web_audit_log(server_id, menu_id, action, actor, details) VALUES ($1, $2, $3, $4, $5)",
    [serverId, menuId, action, actor(request), details]
  );
}

function asyncRoute(handler) {
  return (request, response, next) => Promise.resolve(handler(request, response, next)).catch(next);
}

export function createApp(config, database, dependencies = {}) {
  const aiService = dependencies.aiService || createAiService(config.ai);
  const app = express();
  app.set("trust proxy", config.trustProxy);
  app.disable("x-powered-by");
  app.use(helmet({
    contentSecurityPolicy: {
      directives: {
        defaultSrc: ["'self'"],
        scriptSrc: ["'self'"],
        styleSrc: ["'self'"],
        imgSrc: ["'self'", "data:"],
        connectSrc: ["'self'"],
        objectSrc: ["'none'"],
        frameAncestors: ["'none'"]
      }
    },
    crossOriginResourcePolicy: { policy: "same-origin" }
  }));
  app.use(cors({
    origin(origin, callback) {
      if (!origin || config.corsOrigins.includes(origin)) return callback(null, true);
      return callback(new HttpError(403, "该来源不允许访问 API"));
    },
    allowedHeaders: ["Content-Type", "X-API-Key", "X-siyuan-Actor", "X-siyuan-CSRF", "X-siyuan-Sync-Token", "If-None-Match"],
    methods: ["GET", "POST", "PUT", "DELETE", "OPTIONS"],
    credentials: true
  }));
  app.use(express.json({ limit: "1mb" }));
  app.use((request, _response, next) => {
    if (["POST", "PUT"].includes(request.method) && (!request.body || typeof request.body !== "object" || Array.isArray(request.body))) {
      request.body = {};
    }
    next();
  });

  app.get("/health", asyncRoute(async (_request, response) => {
    const checkedAt = await database.health();
    response.json({ status: "ok", database: "ok", checkedAt });
  }));

  async function authenticateSyncServer(request) {
    const slug = stableKey(request.params.serverSlug, "服务器标识");
    const serverResult = await database.pool.query(
      "SELECT id, slug, display_name, sync_token_hash FROM web_servers WHERE slug = $1",
      [slug]
    );
    if (!serverResult.rowCount) throw new HttpError(404, "服务器不存在");
    const suppliedHash = crypto.createHash("sha256").update(String(request.get("x-siyuan-sync-token") || "")).digest("hex");
    if (!serverResult.rows[0].sync_token_hash || !sameSecret(suppliedHash, serverResult.rows[0].sync_token_hash)) {
      throw new HttpError(401, "同步令牌无效");
    }
    return serverResult.rows[0];
  }

  app.get("/api/sync/:serverSlug", asyncRoute(async (request, response) => {
    const syncServer = await authenticateSyncServer(request);
    const result = await database.pool.query(`
      SELECT m.id AS menu_id, m.menu_key, m.display_name, m.published_version AS version, v.document
      FROM web_menus m
      JOIN web_menu_versions v ON v.menu_id = m.id AND v.version = m.published_version
      WHERE m.server_id = $1 AND m.published_version IS NOT NULL
      ORDER BY m.menu_key
    `, [syncServer.id]);
    const menus = result.rows.map((row) => ({
      id: row.menu_id,
      key: row.menu_key,
      displayName: row.display_name,
      version: row.version,
      document: row.document,
      yaml: exportMenu(row.document, "yaml"),
      checksum: crypto.createHash("sha256").update(JSON.stringify(row.document)).digest("hex")
    }));
    const checksum = crypto.createHash("sha256").update(JSON.stringify(menus.map(({ key, version, checksum: hash }) => [key, version, hash]))).digest("hex");
    const etag = `"${checksum}"`;
    response.set({ "ETag": etag, "Cache-Control": "private, no-cache" });
    if (request.get("if-none-match") === etag) return response.status(304).end();
    const { sync_token_hash: _secret, ...server } = syncServer;
    response.json({ server, checksum, generatedAt: new Date().toISOString(), menus });
  }));

  app.put("/api/sync/:serverSlug/menus/:menuKey", asyncRoute(async (request, response) => {
    const syncServer = await authenticateSyncServer(request);
    const menuKey = stableKey(request.params.menuKey, "菜单标识");
    const requestedBaseVersion = request.body?.baseVersion == null
      ? null : Number.parseInt(request.body.baseVersion, 10);
    if (request.body?.baseVersion != null && (!Number.isInteger(requestedBaseVersion) || requestedBaseVersion < 1)) {
      throw new HttpError(400, "baseVersion 无效");
    }
    let document;
    try {
      document = parseMenu(request.body?.yaml, "yaml");
    } catch (error) {
      throw new HttpError(400, `菜单内容无效: ${error.message}`);
    }
    const publish = request.body?.publish !== false;
    const displayName = String(request.body?.displayName || menuKey).trim().slice(0, 128) || menuKey;
    let result;
    try {
      result = await transaction(database.pool, async (client) => {
        // PostgreSQL benefits from an advisory lock here. MySQL uses the indexed
        // row lock below; a concurrent first insert is translated to a conflict.
        if (database.dialect !== "mysql") {
          await client.query("SELECT pg_advisory_xact_lock(hashtext($1), 0)", [`${syncServer.id}:${menuKey}`]);
        }
        const existing = await client.query(
          "SELECT * FROM web_menus WHERE server_id = $1 AND menu_key = $2 FOR UPDATE",
          [syncServer.id, menuKey]
        );
        if (existing.rowCount && requestedBaseVersion !== existing.rows[0].current_version) {
          throw new HttpError(409, "菜单已在 Web 端变化，请先同步后再保存", {
            currentVersion: existing.rows[0].current_version,
            publishedVersion: existing.rows[0].published_version
          });
        }
        const menuId = existing.rowCount ? existing.rows[0].id : crypto.randomUUID();
        const version = existing.rowCount ? existing.rows[0].current_version + 1 : 1;
        if (existing.rowCount) {
          await client.query(`
            UPDATE web_menus SET display_name = $3, current_version = $2,
              published_version = CASE WHEN $4 THEN $2 ELSE published_version END, updated_at = CURRENT_TIMESTAMP
            WHERE id = $1
          `, [menuId, version, displayName, publish]);
        } else {
          await client.query(`
            INSERT INTO web_menus(id, server_id, menu_key, display_name, current_version, published_version)
            VALUES ($1, $2, $3, $4, 1, CASE WHEN $5 THEN 1 ELSE NULL END)
          `, [menuId, syncServer.id, menuKey, displayName, publish]);
        }
        await client.query(`
          INSERT INTO web_menu_versions(id, menu_id, version, document, created_by, change_note)
          VALUES ($1, $2, $3, $4, $5, $6)
        `, [crypto.randomUUID(), menuId, version, document, actor(request), "游戏内编辑"]);
        await audit(client, request, "MENU_GAME_SAVE", syncServer.id, menuId, { version, published: publish });
        return { menuId, key: menuKey, version, publishedVersion: publish ? version : existing.rows[0]?.published_version || null };
      });
    } catch (error) {
      if (["23505", "ER_DUP_ENTRY"].includes(error.code)) {
        throw new HttpError(409, "菜单已在 Web 端变化，请先同步后再保存");
      }
      throw error;
    }
    response.json(result);
  }));

  const loginAttempts = new Map();
  function checkLoginRateLimit(request) {
    const key = request.ip || "unknown";
    const cutoff = Date.now() - 60_000;
    const attempts = (loginAttempts.get(key) || []).filter((at) => at > cutoff);
    if (loginAttempts.size > 10_000) {
      for (const [candidate, timestamps] of loginAttempts) {
        if (!timestamps.some((at) => at > cutoff)) loginAttempts.delete(candidate);
      }
    }
    if (attempts.length >= 10) throw new HttpError(429, "登录尝试过于频繁，请稍后再试");
    attempts.push(Date.now());
    loginAttempts.set(key, attempts);
  }

  app.post("/api/auth/login", asyncRoute(async (request, response) => {
    checkLoginRateLimit(request);
    let username;
    try { username = validateUsername(request.body?.username); }
    catch { throw new HttpError(401, "账号或密码错误"); }
    const password = String(request.body?.password || "");
    if (password.length < 12 || password.length > 256) throw new HttpError(401, "账号或密码错误");
    const user = await database.findUser(username);
    if (!user || !user.active || !verifyPassword(password, user.password_hash)) {
      throw new HttpError(401, "账号或密码错误");
    }
    const sessionToken = randomToken();
    const csrfToken = randomToken();
    const expiresAt = new Date(Date.now() + config.sessionTtlHours * 60 * 60 * 1000);
    await database.createSession({
      userId: user.id,
      tokenHash: hashToken(sessionToken),
      csrfTokenHash: hashToken(csrfToken),
      expiresAt
    });
    setSessionCookie(response, sessionToken, config.sessionTtlHours * 60 * 60, config.sessionSecure);
    response.json({ authenticated: true, user: { id: user.id, username: user.username }, csrfToken });
  }));

  app.use("/api", (request, response, next) => {
    Promise.resolve().then(async () => {
      if (sameSecret(request.get("x-api-key"), config.apiKey)) {
        request.auth = { kind: "api", username: null };
      } else {
        const sessionToken = readCookie(request, "siyuan_session");
        const session = sessionToken ? await database.findSession(hashToken(sessionToken)) : null;
        if (!session || !session.active) return response.status(401).json({ error: "请先登录" });
        request.auth = {
          kind: "session",
          username: session.username,
          tokenHash: sessionToken && hashToken(sessionToken),
          csrfTokenHash: session.csrf_token_hash
        };
        if (request.method !== "GET" && request.method !== "HEAD" && request.method !== "OPTIONS") {
          const csrfToken = request.get("x-siyuan-csrf");
          if (!csrfToken || !sameSecret(hashToken(csrfToken), session.csrf_token_hash)) {
            return response.status(403).json({ error: "CSRF 令牌无效，请重新登录" });
          }
        }
      }
      response.set("Cache-Control", "no-store");
      next();
    }).catch(next);
  });

  app.get("/api/session", (request, response) => response.json({
    authenticated: true,
    authMethod: request.auth.kind,
    user: request.auth.username ? { username: request.auth.username } : null
  }));

  app.post("/api/auth/logout", asyncRoute(async (request, response) => {
    if (request.auth.kind === "session" && request.auth.tokenHash) await database.deleteSession(request.auth.tokenHash);
    clearSessionCookie(response, config.sessionSecure);
    response.status(204).end();
  }));

  async function auditAi(request, action, details) {
    await database.pool.query(
      "INSERT INTO web_audit_log(server_id, menu_id, action, actor, details) VALUES (NULL, NULL, $1, $2, $3)",
      [action, actor(request), details]
    );
  }

  app.get("/api/ai/status", (_request, response) => response.json(aiService.status()));

  app.post("/api/ai/task-draft", asyncRoute(async (request, response) => {
    const result = await aiService.generateTask({
      prompt: request.body?.prompt,
      taskType: request.body?.taskType,
      rateKey: `${actor(request)}:${request.ip}`
    });
    await auditAi(request, "AI_TASK_DRAFT", {
      type: result.draft.type,
      objectiveCount: result.draft.objectives.length,
      rewardCount: result.draft.rewards.length
    });
    response.json(result);
  }));

  app.post("/api/ai/menu-draft", asyncRoute(async (request, response) => {
    const result = await aiService.generateMenu({
      prompt: request.body?.prompt,
      rateKey: `${actor(request)}:${request.ip}`
    });
    await auditAi(request, "AI_MENU_DRAFT", {
      size: result.document.size,
      itemCount: result.document.items.length
    });
    response.json(result);
  }));

  app.get("/api/servers", asyncRoute(async (_request, response) => {
    const menuCount = database.dialect === "mysql" ? "CAST(COUNT(m.id) AS SIGNED)" : "count(m.id)::int";
    const result = await database.pool.query(`
      SELECT s.id, s.slug, s.display_name, s.description, s.created_at, s.updated_at,
             ${menuCount} AS menu_count
      FROM web_servers s LEFT JOIN web_menus m ON m.server_id = s.id
      GROUP BY s.id ORDER BY s.display_name
    `);
    response.json(result.rows);
  }));

  app.post("/api/servers/:serverId/rotate-sync-token", asyncRoute(async (request, response) => {
    const syncToken = crypto.randomBytes(32).toString("base64url");
    const syncTokenHash = crypto.createHash("sha256").update(syncToken).digest("hex");
    const result = await transaction(database.pool, async (client) => {
      const updated = await client.query(
        "UPDATE web_servers SET sync_token_hash = $2, updated_at = CURRENT_TIMESTAMP WHERE id = $1",
        [request.params.serverId, syncTokenHash]
      );
      if (!updated.rowCount) throw new HttpError(404, "服务器不存在");
      const server = await client.query("SELECT id, slug FROM web_servers WHERE id = $1", [request.params.serverId]);
      await audit(client, request, "SYNC_TOKEN_ROTATE", request.params.serverId, null);
      return server.rows[0];
    });
    response.json({ ...result, syncToken });
  }));

  app.post("/api/servers", asyncRoute(async (request, response) => {
    const id = crypto.randomUUID();
    const syncToken = crypto.randomBytes(32).toString("base64url");
    const syncTokenHash = crypto.createHash("sha256").update(syncToken).digest("hex");
    const slug = stableKey(request.body.slug, "服务器标识");
    const displayName = requiredText(request.body.displayName, "服务器名称");
    const description = String(request.body.description || "").slice(0, 1000);
    try {
      const server = await transaction(database.pool, async (client) => {
        await client.query(
          "INSERT INTO web_servers(id, slug, display_name, description, sync_token_hash) VALUES ($1, $2, $3, $4, $5)",
          [id, slug, displayName, description, syncTokenHash]
        );
        const result = await client.query(
          "SELECT id, slug, display_name, description, created_at, updated_at FROM web_servers WHERE id = $1",
          [id]
        );
        await audit(client, request, "SERVER_CREATE", id, null, { slug });
        return { ...result.rows[0], syncToken };
      });
      response.status(201).json(server);
    } catch (error) {
      if (["23505", "ER_DUP_ENTRY"].includes(error.code)) throw new HttpError(409, "服务器标识已存在");
      throw error;
    }
  }));

  app.delete("/api/servers/:serverId", asyncRoute(async (request, response) => {
    await transaction(database.pool, async (client) => {
      const exists = await client.query("SELECT id, slug FROM web_servers WHERE id = $1 FOR UPDATE", [request.params.serverId]);
      if (!exists.rowCount) throw new HttpError(404, "服务器不存在");
      await audit(client, request, "SERVER_DELETE", request.params.serverId, null, { slug: exists.rows[0].slug });
      await client.query("DELETE FROM web_servers WHERE id = $1", [request.params.serverId]);
    });
    response.status(204).end();
  }));

  app.get("/api/servers/:serverId/menus", asyncRoute(async (request, response) => {
    const result = await database.pool.query(`
      SELECT id, server_id, menu_key, display_name, current_version, published_version, created_at, updated_at
      FROM web_menus WHERE server_id = $1 ORDER BY display_name
    `, [request.params.serverId]);
    response.json(result.rows);
  }));

  app.post("/api/servers/:serverId/menus", asyncRoute(async (request, response) => {
    const menuId = crypto.randomUUID();
    const versionId = crypto.randomUUID();
    const menuKey = stableKey(request.body.menuKey, "菜单标识");
    const displayName = requiredText(request.body.displayName, "菜单名称");
    const document = menuDocument(request.body.document || { title: `&6${displayName}`, size: 54, items: [] });
    try {
      const result = await transaction(database.pool, async (client) => {
        await client.query(`
          INSERT INTO web_menus(id, server_id, menu_key, display_name, current_version)
          VALUES ($1, $2, $3, $4, 1)
        `, [menuId, request.params.serverId, menuKey, displayName]);
        const inserted = await client.query("SELECT * FROM web_menus WHERE id = $1", [menuId]);
        await client.query(`
          INSERT INTO web_menu_versions(id, menu_id, version, document, created_by, change_note)
          VALUES ($1, $2, 1, $3, $4, $5)
        `, [versionId, menuId, document, actor(request), "创建菜单"]);
        await audit(client, request, "MENU_CREATE", request.params.serverId, menuId, { menuKey, version: 1 });
        return { ...inserted.rows[0], document };
      });
      response.status(201).json(result);
    } catch (error) {
      if (["23503", "ER_NO_REFERENCED_ROW_2"].includes(error.code)) throw new HttpError(404, "服务器不存在");
      if (["23505", "ER_DUP_ENTRY"].includes(error.code)) throw new HttpError(409, "该服务器已存在同名菜单标识");
      throw error;
    }
  }));

  app.get("/api/menus/:menuId", asyncRoute(async (request, response) => {
    const requestedVersion = request.query.version ? Number.parseInt(request.query.version, 10) : null;
    const result = await database.pool.query(`
      SELECT m.*, s.slug AS server_slug, s.display_name AS server_name,
             v.document, v.created_by AS version_created_by, v.change_note, v.created_at AS version_created_at
      FROM web_menus m
      JOIN web_servers s ON s.id = m.server_id
      JOIN web_menu_versions v ON v.menu_id = m.id AND v.version = COALESCE($2, m.current_version)
      WHERE m.id = $1
    `, [request.params.menuId, requestedVersion]);
    if (!result.rowCount) throw new HttpError(404, "菜单或版本不存在");
    const versions = await database.pool.query(`
      SELECT version, created_by, change_note, created_at
      FROM web_menu_versions WHERE menu_id = $1 ORDER BY version DESC LIMIT 100
    `, [request.params.menuId]);
    response.json({ ...result.rows[0], selectedVersion: requestedVersion || result.rows[0].current_version, versions: versions.rows });
  }));

  app.put("/api/menus/:menuId", asyncRoute(async (request, response) => {
    const baseVersion = Number.parseInt(request.body.baseVersion, 10);
    if (!Number.isInteger(baseVersion) || baseVersion < 1) throw new HttpError(400, "baseVersion 无效");
    const document = menuDocument(request.body.document);
    const note = String(request.body.changeNote || "Web 编辑").slice(0, 512);
    const saved = await transaction(database.pool, async (client) => {
      const locked = await client.query("SELECT * FROM web_menus WHERE id = $1 FOR UPDATE", [request.params.menuId]);
      if (!locked.rowCount) throw new HttpError(404, "菜单不存在");
      const menu = locked.rows[0];
      if (menu.current_version !== baseVersion) {
        throw new HttpError(409, "菜单已被其他管理员修改，请重新载入", { currentVersion: menu.current_version });
      }
      const nextVersion = baseVersion + 1;
      await client.query(`
        INSERT INTO web_menu_versions(id, menu_id, version, document, created_by, change_note)
        VALUES ($1, $2, $3, $4, $5, $6)
      `, [crypto.randomUUID(), menu.id, nextVersion, document, actor(request), note]);
      await client.query("UPDATE web_menus SET current_version = $2, updated_at = CURRENT_TIMESTAMP WHERE id = $1", [menu.id, nextVersion]);
      await audit(client, request, "MENU_SAVE", menu.server_id, menu.id, { baseVersion, version: nextVersion, note });
      return { version: nextVersion, document };
    });
    response.json(saved);
  }));

  app.post("/api/menus/:menuId/publish", asyncRoute(async (request, response) => {
    const published = await transaction(database.pool, async (client) => {
      const locked = await client.query("SELECT * FROM web_menus WHERE id = $1 FOR UPDATE", [request.params.menuId]);
      if (!locked.rowCount) throw new HttpError(404, "菜单不存在");
      const menu = locked.rows[0];
      const version = request.body.version == null ? menu.current_version : Number.parseInt(request.body.version, 10);
      if (!Number.isInteger(version) || version < 1 || version > menu.current_version) throw new HttpError(400, "发布版本无效");
      const exists = await client.query("SELECT 1 FROM web_menu_versions WHERE menu_id = $1 AND version = $2", [menu.id, version]);
      if (!exists.rowCount) throw new HttpError(404, "菜单版本不存在");
      await client.query("UPDATE web_menus SET published_version = $2, updated_at = CURRENT_TIMESTAMP WHERE id = $1", [menu.id, version]);
      await audit(client, request, "MENU_PUBLISH", menu.server_id, menu.id, { version });
      return { menuId: menu.id, publishedVersion: version };
    });
    response.json(published);
  }));

  app.delete("/api/menus/:menuId", asyncRoute(async (request, response) => {
    await transaction(database.pool, async (client) => {
      const exists = await client.query("SELECT id, server_id, menu_key FROM web_menus WHERE id = $1 FOR UPDATE", [request.params.menuId]);
      if (!exists.rowCount) throw new HttpError(404, "菜单不存在");
      const menu = exists.rows[0];
      await audit(client, request, "MENU_DELETE", menu.server_id, menu.id, { menuKey: menu.menu_key });
      await client.query("DELETE FROM web_menus WHERE id = $1", [menu.id]);
    });
    response.status(204).end();
  }));

  app.post("/api/import", asyncRoute(async (request, response) => {
    try {
      response.json({ document: parseMenu(request.body.source, request.body.format || "yaml") });
    } catch (error) {
      throw new HttpError(400, `导入失败: ${error.message}`);
    }
  }));

  app.get("/api/menus/:menuId/export", asyncRoute(async (request, response) => {
    const format = request.query.format === "json" ? "json" : "yaml";
    const version = request.query.version ? Number.parseInt(request.query.version, 10) : null;
    const result = await database.pool.query(`
      SELECT m.menu_key, v.document, v.version
      FROM web_menus m JOIN web_menu_versions v ON v.menu_id = m.id
      WHERE m.id = $1 AND v.version = COALESCE($2, m.current_version)
    `, [request.params.menuId, version]);
    if (!result.rowCount) throw new HttpError(404, "菜单或版本不存在");
    const row = result.rows[0];
    response.type(format === "json" ? "application/json" : "application/yaml");
    response.set("Content-Disposition", `attachment; filename="${row.menu_key}-v${row.version}.${format === "json" ? "json" : "yml"}"`);
    response.send(exportMenu(row.document, format));
  }));

  app.get("/api/audit", asyncRoute(async (request, response) => {
    const limit = Math.max(1, Math.min(200, Number.parseInt(request.query.limit || "50", 10)));
    const result = await database.pool.query(`
      SELECT id, server_id, menu_id, action, actor, details, created_at
      FROM web_audit_log ORDER BY created_at DESC LIMIT $1
    `, [limit]);
    response.json(result.rows);
  }));

  app.use("/api", (_request, response) => response.status(404).json({ error: "API 路径不存在" }));

  app.use(express.static(path.join(rootDirectory, "public"), { extensions: ["html"], maxAge: "1h" }));
  app.get("*", (_request, response) => response.sendFile(path.join(rootDirectory, "public", "index.html")));

  app.use((error, _request, response, _next) => {
    const status = error.status || (error.type === "entity.too.large" ? 413 : error.code === "22P02" ? 400 : 500);
    if (status >= 500) console.error(error);
    response.status(status).json({ error: status >= 500 ? "服务器内部错误" : error.message, details: error.details });
  });

  return app;
}
