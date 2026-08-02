-- SiYuan Database Schema
-- UTF8MB4, InnoDB

SET NAMES utf8mb4;
SET time_zone = '+00:00';

-- 赛季表
CREATE TABLE IF NOT EXISTS sy_seasons (
  id          VARCHAR(64)  NOT NULL,
  name        VARCHAR(128) NOT NULL,
  start_time  BIGINT       NOT NULL,
  end_time    BIGINT       DEFAULT NULL,
  active      TINYINT(1)   NOT NULL DEFAULT 0,
  created_at  BIGINT       NOT NULL,
  PRIMARY KEY (id),
  INDEX idx_active (active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 玩家通行证数据
CREATE TABLE IF NOT EXISTS sy_player_pass (
  uuid         CHAR(36)     NOT NULL,
  season_id    VARCHAR(64)  NOT NULL DEFAULT 'none',
  pass_id      VARCHAR(64)  NOT NULL DEFAULT 'default',
  tier         VARCHAR(32)  NOT NULL DEFAULT 'free',
  level        INT          NOT NULL DEFAULT 1,
  experience   BIGINT       NOT NULL DEFAULT 0,
  total_exp_earned BIGINT   NOT NULL DEFAULT 0,
  last_update  BIGINT       NOT NULL,
  PRIMARY KEY (uuid, season_id),
  INDEX idx_season (season_id),
  INDEX idx_level (level DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 玩家领取的奖励记录（防重复领取）
CREATE TABLE IF NOT EXISTS sy_claimed_rewards (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  uuid         CHAR(36)     NOT NULL,
  season_id    VARCHAR(64)  NOT NULL,
  pass_id      VARCHAR(64)  NOT NULL,
  level        INT          NOT NULL,
  tier         VARCHAR(32)  NOT NULL,
  claimed_at   BIGINT       NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_claim (uuid, season_id, pass_id, level, tier),
  INDEX idx_uuid (uuid),
  INDEX idx_season_uuid (season_id, uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 玩家任务进度
CREATE TABLE IF NOT EXISTS sy_quest_progress (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  uuid            CHAR(36)     NOT NULL,
  quest_id        VARCHAR(128) NOT NULL,
  quest_type      VARCHAR(16)  NOT NULL,
  season_id       VARCHAR(64)  NOT NULL DEFAULT 'none',
  progress_json   TEXT         NOT NULL,
  status          VARCHAR(16)  NOT NULL DEFAULT 'IN_PROGRESS',
  started_at      BIGINT       NOT NULL,
  completed_at    BIGINT       DEFAULT NULL,
  reset_date      VARCHAR(64)  NOT NULL DEFAULT '',
  PRIMARY KEY (id),
  UNIQUE KEY uk_quest (uuid, quest_id, season_id, reset_date),
  INDEX idx_uuid_type (uuid, quest_type),
  INDEX idx_uuid_season (uuid, season_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 商店物品
CREATE TABLE IF NOT EXISTS sy_shop_items (
  id              VARCHAR(64)  NOT NULL,
  seller_uuid     CHAR(36)     NOT NULL,
  seller_name     VARCHAR(64)  NOT NULL,
  item_base64     LONGTEXT     NOT NULL,
  item_name       VARCHAR(128) NOT NULL DEFAULT '',
  amount          INT          NOT NULL DEFAULT 1,
  price_per_unit  DECIMAL(19,4) NOT NULL,
  listed_at       BIGINT       NOT NULL,
  PRIMARY KEY (id),
  INDEX idx_seller (seller_uuid),
  INDEX idx_listed (listed_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 交易记录（通胀监控核心）
CREATE TABLE IF NOT EXISTS sy_transactions (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  buyer_uuid      CHAR(36)     NOT NULL,
  buyer_name      VARCHAR(64)  NOT NULL,
  seller_uuid     CHAR(36)     NOT NULL,
  seller_name     VARCHAR(64)  NOT NULL,
  item_name       VARCHAR(128) NOT NULL,
  amount          INT          NOT NULL,
  unit_price      DECIMAL(19,4) NOT NULL,
  total_price     DECIMAL(19,4) NOT NULL,
  tx_type         VARCHAR(16)  NOT NULL DEFAULT 'SHOP',
  tx_at           BIGINT       NOT NULL,
  PRIMARY KEY (id),
  INDEX idx_buyer (buyer_uuid),
  INDEX idx_seller (seller_uuid),
  INDEX idx_tx_at (tx_at DESC),
  INDEX idx_type_at (tx_type, tx_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 经济快照（每日，用于通胀监控）
CREATE TABLE IF NOT EXISTS sy_economy_snapshot (
  snapshot_date   DATE         NOT NULL,
  total_money_in  DECIMAL(19,4) NOT NULL DEFAULT 0,
  total_money_out DECIMAL(19,4) NOT NULL DEFAULT 0,
  total_trades    INT          NOT NULL DEFAULT 0,
  avg_item_price  DECIMAL(19,4) NOT NULL DEFAULT 0,
  active_players  INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (snapshot_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 经济事件审计（奖励铸币、费用销毁、退款和交易量）
CREATE TABLE IF NOT EXISTS sy_economy_events (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  direction     VARCHAR(16)  NOT NULL,
  amount        DECIMAL(19,4) NOT NULL,
  reason        VARCHAR(64)  NOT NULL,
  occurred_at   BIGINT       NOT NULL,
  PRIMARY KEY (id),
  INDEX idx_economy_time (occurred_at),
  INDEX idx_economy_direction (direction, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 传送点
CREATE TABLE IF NOT EXISTS sy_waypoints (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  uuid            CHAR(36)     NOT NULL,
  slot            INT          NOT NULL,
  world           VARCHAR(64)  NOT NULL,
  x               DOUBLE       NOT NULL,
  y               DOUBLE       NOT NULL,
  z               DOUBLE       NOT NULL,
  yaw             FLOAT        NOT NULL DEFAULT 0,
  pitch           FLOAT        NOT NULL DEFAULT 0,
  icon            VARCHAR(64)  NOT NULL DEFAULT 'RED_BED',
  name            VARCHAR(64)  NOT NULL DEFAULT '',
  PRIMARY KEY (id),
  UNIQUE KEY uk_wp (uuid, slot),
  INDEX idx_uuid (uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 玩家基础信息缓存
CREATE TABLE IF NOT EXISTS sy_players (
  uuid            CHAR(36)     NOT NULL,
  name            VARCHAR(64)  NOT NULL,
  first_join      BIGINT       NOT NULL,
  last_seen       BIGINT       NOT NULL,
  PRIMARY KEY (uuid),
  INDEX idx_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Existing installations may have used VARCHAR(16), but seasonal IDs are longer.
ALTER TABLE sy_quest_progress MODIFY reset_date VARCHAR(64) NOT NULL DEFAULT '';
ALTER TABLE sy_shop_items MODIFY price_per_unit DECIMAL(19,4) NOT NULL;
ALTER TABLE sy_transactions MODIFY unit_price DECIMAL(19,4) NOT NULL, MODIFY total_price DECIMAL(19,4) NOT NULL;
ALTER TABLE sy_economy_snapshot MODIFY total_money_in DECIMAL(19,4) NOT NULL DEFAULT 0, MODIFY total_money_out DECIMAL(19,4) NOT NULL DEFAULT 0, MODIFY avg_item_price DECIMAL(19,4) NOT NULL DEFAULT 0;
ALTER TABLE sy_economy_events MODIFY amount DECIMAL(19,4) NOT NULL;
