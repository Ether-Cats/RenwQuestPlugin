import crypto from "node:crypto";
import path from "node:path";
import { fileURLToPath } from "node:url";
import cors from "cors";
import express from "express";
import helmet from "helmet";
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

export function createApp(config, database) {
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
    allowedHeaders: ["Content-Type", "X-API-Key", "X-SiYuan-Actor", "X-SiYuan-Sync-Token", "If-None-Match"],
    methods: ["GET", "POST", "PUT", "DELETE", "OPTIONS"]
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
    const result = await transaction(database.pool, async (client) => {
      await client.query("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))", [`${syncServer.id}:${menuKey}`]);
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
            published_version = CASE WHEN $4 THEN $2 ELSE published_version END, updated_at = now()
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
    response.json(result);
  }));

  app.use("/api", (request, response, next) => {
    if (!sameSecret(request.get("x-api-key"), config.apiKey)) {
      return response.status(401).json({ error: "API Key 无效" });
    }
    response.set("Cache-Control", "no-store");
    next();
  });

  app.get("/api/session", (_request, response) => response.json({ authenticated: true }));

  app.get("/api/servers", asyncRoute(async (_request, response) => {
    const result = await database.pool.query(`
      SELECT s.id, s.slug, s.display_name, s.description, s.created_at, s.updated_at,
             count(m.id)::int AS menu_count
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
        "UPDATE web_servers SET sync_token_hash = $2, updated_at = now() WHERE id = $1 RETURNING id, slug",
        [request.params.serverId, syncTokenHash]
      );
      if (!updated.rowCount) throw new HttpError(404, "服务器不存在");
      await audit(client, request, "SYNC_TOKEN_ROTATE", request.params.serverId, null);
      return updated.rows[0];
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
        const result = await client.query(
          "INSERT INTO web_servers(id, slug, display_name, description, sync_token_hash) VALUES ($1, $2, $3, $4, $5) RETURNING id, slug, display_name, description, created_at, updated_at",
          [id, slug, displayName, description, syncTokenHash]
        );
        await audit(client, request, "SERVER_CREATE", id, null, { slug });
        return { ...result.rows[0], syncToken };
      });
      response.status(201).json(server);
    } catch (error) {
      if (error.code === "23505") throw new HttpError(409, "服务器标识已存在");
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
        const inserted = await client.query(`
          INSERT INTO web_menus(id, server_id, menu_key, display_name, current_version)
          VALUES ($1, $2, $3, $4, 1) RETURNING *
        `, [menuId, request.params.serverId, menuKey, displayName]);
        await client.query(`
          INSERT INTO web_menu_versions(id, menu_id, version, document, created_by, change_note)
          VALUES ($1, $2, 1, $3, $4, $5)
        `, [versionId, menuId, document, actor(request), "创建菜单"]);
        await audit(client, request, "MENU_CREATE", request.params.serverId, menuId, { menuKey, version: 1 });
        return { ...inserted.rows[0], document };
      });
      response.status(201).json(result);
    } catch (error) {
      if (error.code === "23503") throw new HttpError(404, "服务器不存在");
      if (error.code === "23505") throw new HttpError(409, "该服务器已存在同名菜单标识");
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
      await client.query("UPDATE web_menus SET current_version = $2, updated_at = now() WHERE id = $1", [menu.id, nextVersion]);
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
      await client.query("UPDATE web_menus SET published_version = $2, updated_at = now() WHERE id = $1", [menu.id, version]);
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
