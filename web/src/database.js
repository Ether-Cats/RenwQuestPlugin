import pg from "pg";

const { Pool } = pg;

const SCHEMA = `
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

ALTER TABLE web_servers ADD COLUMN IF NOT EXISTS sync_token_hash CHAR(64);
`;

export function createDatabase(config) {
  const pool = new Pool({
    connectionString: config.databaseUrl,
    ssl: config.databaseSsl,
    max: Number.parseInt(process.env.DB_POOL_SIZE || "10", 10),
    connectionTimeoutMillis: 5_000,
    idleTimeoutMillis: 30_000
  });

  return {
    pool,
    async migrate() {
      await pool.query(SCHEMA);
    },
    async health() {
      const result = await pool.query("SELECT now() AS now");
      return result.rows[0].now;
    },
    async close() {
      await pool.end();
    }
  };
}
