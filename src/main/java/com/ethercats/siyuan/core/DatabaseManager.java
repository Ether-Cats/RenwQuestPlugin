package com.ethercats.siyuan.core;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.*;
import java.util.List;
import java.util.logging.Level;

public class DatabaseManager {

    @FunctionalInterface
    public interface ResultSetHandler<T> {
        T handle(ResultSet rs) throws SQLException;
    }

    private HikariDataSource dataSource;
    private final JavaPlugin plugin;

    public DatabaseManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean init() {
        FileConfiguration cfg = plugin.getConfig();
        String host = cfg.getString("database.host", "127.0.0.1");
        int    port = cfg.getInt("database.port", 3307);
        String db   = cfg.getString("database.database", "siyuan");
        String user = cfg.getString("database.username", "siyuan");
        String pass = System.getenv().getOrDefault("SIYUAN_MYSQL_PASSWORD",
            cfg.getString("database.password", "siyuan_pass_2024"));
        boolean ssl = cfg.getBoolean("database.ssl", false);
        boolean allowPublicKey = cfg.getBoolean("database.allow-public-key-retrieval", !ssl);
        if (!ssl && !host.equals("127.0.0.1") && !host.equalsIgnoreCase("localhost")) {
            plugin.getLogger().warning("[DB] 远程 MySQL 未启用 TLS，生产环境请设置 database.ssl=true");
        }

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + db
                + "?useSSL=" + ssl + "&allowPublicKeyRetrieval=" + allowPublicKey
                + "&serverTimezone=UTC&useUnicode=true&characterEncoding=UTF-8");
        hc.setUsername(user);
        hc.setPassword(pass);
        hc.setDriverClassName("com.mysql.cj.jdbc.Driver");
        hc.setMaximumPoolSize(10);
        hc.setMinimumIdle(2);
        hc.setConnectionTimeout(30000);
        hc.setIdleTimeout(600000);
        hc.setMaxLifetime(1800000);
        hc.setPoolName("siyuan-pool");
        hc.addDataSourceProperty("cachePrepStmts", "true");
        hc.addDataSourceProperty("prepStmtCacheSize", "250");
        hc.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        try {
            dataSource = new HikariDataSource(hc);
            plugin.getLogger().info("[DB] MySQL 连接池初始化成功");
            if (!initSchema()) {
                plugin.getLogger().severe("[DB] 数据库表初始化失败，拒绝启用插件");
                close();
                return false;
            }
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "[DB] MySQL 连接失败: " + e.getMessage(), e);
            return false;
        }
    }

    private boolean initSchema() {
        String[] tables = {
            """
            CREATE TABLE IF NOT EXISTS sy_seasons (
              id VARCHAR(64) NOT NULL, name VARCHAR(128) NOT NULL,
              start_time BIGINT NOT NULL, end_time BIGINT DEFAULT NULL,
              active TINYINT(1) NOT NULL DEFAULT 0, created_at BIGINT NOT NULL,
              PRIMARY KEY (id), INDEX idx_active (active)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS sy_player_pass (
              uuid CHAR(36) NOT NULL, season_id VARCHAR(64) NOT NULL DEFAULT 'none',
              pass_id VARCHAR(64) NOT NULL DEFAULT 'default',
              tier VARCHAR(32) NOT NULL DEFAULT 'free',
              level INT NOT NULL DEFAULT 1, experience BIGINT NOT NULL DEFAULT 0,
              total_exp_earned BIGINT NOT NULL DEFAULT 0, last_update BIGINT NOT NULL,
              PRIMARY KEY (uuid, season_id), INDEX idx_level (level DESC)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS sy_claimed_rewards (
              id BIGINT NOT NULL AUTO_INCREMENT, uuid CHAR(36) NOT NULL,
              season_id VARCHAR(64) NOT NULL, pass_id VARCHAR(64) NOT NULL,
              level INT NOT NULL, tier VARCHAR(32) NOT NULL, claimed_at BIGINT NOT NULL,
              PRIMARY KEY (id),
              UNIQUE KEY uk_claim (uuid, season_id, pass_id, level, tier),
              INDEX idx_uuid_season (season_id, uuid)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS sy_quest_progress (
              id BIGINT NOT NULL AUTO_INCREMENT, uuid CHAR(36) NOT NULL,
              quest_id VARCHAR(128) NOT NULL, quest_type VARCHAR(16) NOT NULL,
              season_id VARCHAR(64) NOT NULL DEFAULT 'none',
              progress_json TEXT NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'IN_PROGRESS',
              started_at BIGINT NOT NULL, completed_at BIGINT DEFAULT NULL,
              reset_date VARCHAR(64) NOT NULL DEFAULT '',
              PRIMARY KEY (id),
              UNIQUE KEY uk_quest (uuid, quest_id, season_id, reset_date),
              INDEX idx_uuid_type (uuid, quest_type)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS sy_shop_items (
              id VARCHAR(64) NOT NULL, seller_uuid CHAR(36) NOT NULL,
              seller_name VARCHAR(64) NOT NULL, item_base64 LONGTEXT NOT NULL,
              item_name VARCHAR(128) NOT NULL DEFAULT '',
              amount INT NOT NULL DEFAULT 1, price_per_unit DECIMAL(19,4) NOT NULL,
              listed_at BIGINT NOT NULL,
              PRIMARY KEY (id), INDEX idx_seller (seller_uuid), INDEX idx_listed (listed_at DESC)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS sy_transactions (
              id BIGINT NOT NULL AUTO_INCREMENT,
              buyer_uuid CHAR(36) NOT NULL, buyer_name VARCHAR(64) NOT NULL,
              seller_uuid CHAR(36) NOT NULL, seller_name VARCHAR(64) NOT NULL,
              item_name VARCHAR(128) NOT NULL, amount INT NOT NULL,
              unit_price DECIMAL(19,4) NOT NULL, total_price DECIMAL(19,4) NOT NULL,
              tx_type VARCHAR(16) NOT NULL DEFAULT 'SHOP', tx_at BIGINT NOT NULL,
              PRIMARY KEY (id), INDEX idx_tx_at (tx_at DESC)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS sy_economy_snapshot (
              snapshot_date DATE NOT NULL, total_money_in DECIMAL(19,4) NOT NULL DEFAULT 0,
              total_money_out DECIMAL(19,4) NOT NULL DEFAULT 0,
              total_trades INT NOT NULL DEFAULT 0, avg_item_price DECIMAL(19,4) NOT NULL DEFAULT 0,
              active_players INT NOT NULL DEFAULT 0,
              PRIMARY KEY (snapshot_date)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS sy_economy_events (
              id BIGINT NOT NULL AUTO_INCREMENT, direction VARCHAR(16) NOT NULL,
              amount DECIMAL(19,4) NOT NULL, reason VARCHAR(64) NOT NULL, occurred_at BIGINT NOT NULL,
              PRIMARY KEY (id), INDEX idx_economy_time (occurred_at),
              INDEX idx_economy_direction (direction, occurred_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS sy_waypoints (
              id BIGINT NOT NULL AUTO_INCREMENT, uuid CHAR(36) NOT NULL,
              slot INT NOT NULL, world VARCHAR(64) NOT NULL,
              x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL,
              yaw FLOAT NOT NULL DEFAULT 0, pitch FLOAT NOT NULL DEFAULT 0,
              icon VARCHAR(64) NOT NULL DEFAULT 'RED_BED', name VARCHAR(64) NOT NULL DEFAULT '',
              PRIMARY KEY (id), UNIQUE KEY uk_wp (uuid, slot), INDEX idx_uuid (uuid)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS sy_players (
              uuid CHAR(36) NOT NULL, name VARCHAR(64) NOT NULL,
              first_join BIGINT NOT NULL, last_seen BIGINT NOT NULL,
              PRIMARY KEY (uuid), INDEX idx_name (name)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            "ALTER TABLE sy_quest_progress MODIFY reset_date VARCHAR(64) NOT NULL DEFAULT ''",
            "ALTER TABLE sy_shop_items MODIFY price_per_unit DECIMAL(19,4) NOT NULL",
            "ALTER TABLE sy_transactions MODIFY unit_price DECIMAL(19,4) NOT NULL, MODIFY total_price DECIMAL(19,4) NOT NULL",
            "ALTER TABLE sy_economy_snapshot MODIFY total_money_in DECIMAL(19,4) NOT NULL DEFAULT 0, MODIFY total_money_out DECIMAL(19,4) NOT NULL DEFAULT 0, MODIFY avg_item_price DECIMAL(19,4) NOT NULL DEFAULT 0",
            "ALTER TABLE sy_economy_events MODIFY amount DECIMAL(19,4) NOT NULL"
        };
        for (String ddl : tables) {
            try {
                if (execute(ddl.trim()) < 0) {
                    plugin.getLogger().severe("[DB] DDL 执行失败，SQL: " + ddl.trim());
                    return false;
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "[DB] DDL 执行失败", e);
                return false;
            }
        }
        plugin.getLogger().info("[DB] 数据库表初始化完成");
        return true;
    }

    public int execute(String sql, Object... params) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            return ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "[DB] execute 失败: " + sql, e);
            return -1;
        }
    }

    public <T> T query(String sql, ResultSetHandler<T> handler, Object... params) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                return handler.handle(rs);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "[DB] query 失败: " + sql, e);
            return null;
        }
    }

    public int batchExecute(String sql, List<Object[]> paramsList) {
        if (paramsList == null || paramsList.isEmpty()) return 0;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            conn.setAutoCommit(false);
            for (Object[] params : paramsList) {
                for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
                ps.addBatch();
            }
            int[] results = ps.executeBatch();
            conn.commit();
            conn.setAutoCommit(true);
            int total = 0;
            for (int r : results) if (r > 0) total += r;
            return total;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "[DB] batchExecute 失败", e);
            return -1;
        }
    }

    public boolean isConnected() {
        return dataSource != null && !dataSource.isClosed();
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().info("[DB] 数据库连接池已关闭");
        }
    }
}
