import crypto from "node:crypto";
import pg from "pg";
import mysql from "mysql2/promise";

const { Pool: PgPool } = pg;

const POSTGRES_SCHEMA = `
CREATE TABLE IF NOT EXISTS web_servers (
  id UUID PRIMARY KEY,
  slug VARCHAR(64) NOT NULL UNIQUE,
  display_name VARCHAR(128) NOT NULL,
  description TEXT NOT NULL DEFAULT '',
  sync_token_hash CHAR(64) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS web_menus (
  id UUID PRIMARY KEY,
  server_id UUID NOT NULL REFERENCES web_servers(id) ON DELETE CASCADE,
  menu_key VARCHAR(64) NOT NULL,
  display_name VARCHAR(128) NOT NULL,
  current_version INTEGER NOT NULL DEFAULT 0,
  published_version INTEGER,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (server_id, menu_key),
  CHECK (current_version >= 0),
  CHECK (published_version IS NULL OR published_version BETWEEN 1 AND current_version)
);

CREATE TABLE IF NOT EXISTS web_menu_versions (
  id UUID PRIMARY KEY,
  menu_id UUID NOT NULL REFERENCES web_menus(id) ON DELETE CASCADE,
  version INTEGER NOT NULL,
  document JSONB NOT NULL,
  created_by VARCHAR(128) NOT NULL DEFAULT 'web',
  change_note VARCHAR(512) NOT NULL DEFAULT '',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (menu_id, version)
);

CREATE TABLE IF NOT EXISTS web_audit_log (
  id BIGSERIAL PRIMARY KEY,
  server_id UUID REFERENCES web_servers(id) ON DELETE SET NULL,
  menu_id UUID REFERENCES web_menus(id) ON DELETE SET NULL,
  action VARCHAR(64) NOT NULL,
  actor VARCHAR(128) NOT NULL,
  details JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_web_menus_server ON web_menus(server_id);
CREATE INDEX IF NOT EXISTS idx_web_versions_menu ON web_menu_versions(menu_id, version DESC);
CREATE INDEX IF NOT EXISTS idx_web_audit_created ON web_audit_log(created_at DESC);

CREATE TABLE IF NOT EXISTS web_users (
  id UUID PRIMARY KEY,
  username VARCHAR(64) NOT NULL UNIQUE,
  password_hash VARCHAR(256) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS web_sessions (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES web_users(id) ON DELETE CASCADE,
  token_hash CHAR(64) NOT NULL UNIQUE,
  csrf_token_hash CHAR(64) NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_web_sessions_expiry ON web_sessions(expires_at);
ALTER TABLE web_servers ADD COLUMN IF NOT EXISTS sync_token_hash CHAR(64);
`;

const MYSQL_SCHEMA = [
  `CREATE TABLE IF NOT EXISTS web_servers (
    id CHAR(36) PRIMARY KEY,
    slug VARCHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(128) NOT NULL,
    description TEXT NOT NULL,
    sync_token_hash CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
  ) ENGINE=InnoDB`,
  `CREATE TABLE IF NOT EXISTS web_menus (
    id CHAR(36) PRIMARY KEY,
    server_id CHAR(36) NOT NULL,
    menu_key VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    current_version INT NOT NULL DEFAULT 0,
    published_version INT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_web_menus_server_key (server_id, menu_key),
    CONSTRAINT fk_web_menus_server FOREIGN KEY (server_id) REFERENCES web_servers(id) ON DELETE CASCADE
  ) ENGINE=InnoDB`,
  `CREATE TABLE IF NOT EXISTS web_menu_versions (
    id CHAR(36) PRIMARY KEY,
    menu_id CHAR(36) NOT NULL,
    version INT NOT NULL,
    document JSON NOT NULL,
    created_by VARCHAR(128) NOT NULL DEFAULT 'web',
    change_note VARCHAR(512) NOT NULL DEFAULT '',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_web_versions_menu_version (menu_id, version),
    CONSTRAINT fk_web_versions_menu FOREIGN KEY (menu_id) REFERENCES web_menus(id) ON DELETE CASCADE
  ) ENGINE=InnoDB`,
  `CREATE TABLE IF NOT EXISTS web_audit_log (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    server_id CHAR(36) NULL,
    menu_id CHAR(36) NULL,
    action VARCHAR(64) NOT NULL,
    actor VARCHAR(128) NOT NULL,
    details JSON NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_web_audit_server FOREIGN KEY (server_id) REFERENCES web_servers(id) ON DELETE SET NULL,
    CONSTRAINT fk_web_audit_menu FOREIGN KEY (menu_id) REFERENCES web_menus(id) ON DELETE SET NULL
  ) ENGINE=InnoDB`,
  `CREATE TABLE IF NOT EXISTS web_users (
    id CHAR(36) PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(256) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
  ) ENGINE=InnoDB`,
  `CREATE TABLE IF NOT EXISTS web_sessions (
    id CHAR(36) PRIMARY KEY,
    user_id CHAR(36) NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    csrf_token_hash CHAR(64) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_seen_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_web_sessions_user FOREIGN KEY (user_id) REFERENCES web_users(id) ON DELETE CASCADE
  ) ENGINE=InnoDB`
];

const MYSQL_INDEXES = [
  ["web_menus", "idx_web_menus_server", "CREATE INDEX idx_web_menus_server ON web_menus(server_id)"],
  ["web_menu_versions", "idx_web_versions_menu", "CREATE INDEX idx_web_versions_menu ON web_menu_versions(menu_id, version)"],
  ["web_audit_log", "idx_web_audit_created", "CREATE INDEX idx_web_audit_created ON web_audit_log(created_at)"],
  ["web_sessions", "idx_web_sessions_expiry", "CREATE INDEX idx_web_sessions_expiry ON web_sessions(expires_at)"]
];

function isPlainObject(value) {
  return value && typeof value === "object" && !Array.isArray(value) && !(value instanceof Date) && !Buffer.isBuffer(value);
}

function mysqlValue(value) {
  return isPlainObject(value) ? JSON.stringify(value) : value == null ? null : value;
}

export function adaptMysqlQuery(sql, values = []) {
  const ordered = [];
  const converted = String(sql).replace(/\$(\d+)/g, (_match, number) => {
    ordered.push(values[Number(number) - 1]);
    return "?";
  });
  return { sql: converted, values: ordered.map(mysqlValue) };
}

function wrapClient(raw, dialect) {
  return {
    async query(sql, values = []) {
      if (dialect === "postgres") return raw.query(sql, values);
      const adapted = adaptMysqlQuery(sql, values);
      const [rows] = await raw.query(adapted.sql, adapted.values);
      const list = Array.isArray(rows) ? rows : [];
      return { rows: list, rowCount: typeof rows?.affectedRows === "number" ? rows.affectedRows : list.length, result: rows };
    },
    release() {
      if (typeof raw.release === "function") raw.release();
      else if (typeof raw.destroy === "function") raw.destroy();
    }
  };
}

function mysqlOptions(databaseUrl, ssl) {
  const url = new URL(databaseUrl);
  return {
    host: url.hostname,
    port: Number(url.port || 3306),
    user: decodeURIComponent(url.username),
    password: decodeURIComponent(url.password),
    database: decodeURIComponent(url.pathname.replace(/^\//, "")),
    ssl: ssl || undefined,
    connectionLimit: Number.parseInt(process.env.DB_POOL_SIZE || "10", 10),
    waitForConnections: true,
    connectTimeout: 5_000,
    idleTimeout: 30_000
  };
}

export function createDatabase(config) {
  const requestedDialect = String(config.databaseType || "postgres").toLowerCase();
  const dialect = requestedDialect === "postgresql" ? "postgres" : requestedDialect;
  if (!new Set(["postgres", "mysql"]).has(dialect)) throw new Error("DATABASE_TYPE must be postgres or mysql");
  const rawPool = dialect === "postgres"
    ? new PgPool({
      connectionString: config.databaseUrl,
      ssl: config.databaseSsl,
      max: Number.parseInt(process.env.DB_POOL_SIZE || "10", 10),
      connectionTimeoutMillis: 5_000,
      idleTimeoutMillis: 30_000
    })
    : mysql.createPool(mysqlOptions(config.databaseUrl, config.databaseSsl));

  const pool = {
    async query(sql, values = []) {
      if (dialect === "postgres") return rawPool.query(sql, values);
      const adapted = adaptMysqlQuery(sql, values);
      const [rows] = await rawPool.query(adapted.sql, adapted.values);
      const list = Array.isArray(rows) ? rows : [];
      return { rows: list, rowCount: typeof rows?.affectedRows === "number" ? rows.affectedRows : list.length, result: rows };
    },
    async connect() {
      const raw = dialect === "postgres" ? await rawPool.connect() : await rawPool.getConnection();
      return wrapClient(raw, dialect);
    },
    async end() {
      await rawPool.end();
    }
  };

  return {
    pool,
    dialect,
    async migrate() {
      if (dialect === "postgres") {
        await rawPool.query(POSTGRES_SCHEMA);
      } else {
        for (const statement of MYSQL_SCHEMA) await rawPool.query(statement);
        for (const [table, index, statement] of MYSQL_INDEXES) {
          const [rows] = await rawPool.query(
            "SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ? LIMIT 1",
            [table, index]
          );
          if (!rows.length) await rawPool.query(statement);
        }
      }
    },
    async health() {
      const result = await pool.query("SELECT CURRENT_TIMESTAMP AS now");
      return result.rows[0].now;
    },
    async ensureAdminUser(username, passwordHash) {
      const existing = await pool.query("SELECT id FROM web_users WHERE username = $1 LIMIT 1", [username]);
      if (existing.rowCount) return false;
      try {
        await pool.query(
          "INSERT INTO web_users(id, username, password_hash, active) VALUES ($1, $2, $3, $4)",
          [crypto.randomUUID(), username, passwordHash, true]
        );
        return true;
      } catch (error) {
        if (["23505", "ER_DUP_ENTRY"].includes(error.code)) return false;
        throw error;
      }
    },
    async findUser(username) {
      const result = await pool.query(
        "SELECT id, username, password_hash, active FROM web_users WHERE username = $1 LIMIT 1",
        [username]
      );
      return result.rows[0] || null;
    },
    async updateUserPassword(userId, passwordHash) {
      await pool.query(
        "UPDATE web_users SET password_hash = $2, updated_at = CURRENT_TIMESTAMP WHERE id = $1",
        [userId, passwordHash]
      );
      await pool.query("DELETE FROM web_sessions WHERE user_id = $1", [userId]);
    },
    async createSession({ userId, tokenHash, csrfTokenHash, expiresAt }) {
      await pool.query(
        "INSERT INTO web_sessions(id, user_id, token_hash, csrf_token_hash, expires_at) VALUES ($1, $2, $3, $4, $5)",
        [crypto.randomUUID(), userId, tokenHash, csrfTokenHash, expiresAt]
      );
    },
    async findSession(tokenHash) {
      const result = await pool.query(`
        SELECT s.token_hash, s.csrf_token_hash, s.expires_at,
               u.id AS user_id, u.username, u.active
        FROM web_sessions s JOIN web_users u ON u.id = s.user_id
        WHERE s.token_hash = $1 AND s.expires_at > CURRENT_TIMESTAMP
        LIMIT 1
      `, [tokenHash]);
      if (!result.rowCount) return null;
      await pool.query("UPDATE web_sessions SET last_seen_at = CURRENT_TIMESTAMP WHERE token_hash = $1", [tokenHash]);
      return result.rows[0];
    },
    async deleteSession(tokenHash) {
      await pool.query("DELETE FROM web_sessions WHERE token_hash = $1", [tokenHash]);
    },
    async purgeSessions() {
      await pool.query("DELETE FROM web_sessions WHERE expires_at <= CURRENT_TIMESTAMP");
    },
    async close() {
      await pool.end();
    }
  };
}
