# SiYuan 配置参考

SiYuan 把游戏功能和菜单控制面拆开：游戏服使用 MySQL 和可选 Redis，Web 菜单管理使用 PostgreSQL。两套数据库不需要互相直连。

## 前置条件

- Paper `1.21.4` 与 Java `21`。
- Vault 和一个 Vault 经济实现是必需项；插件在找不到经济服务时会主动禁用，避免商店、通行证价格或传送费用出现半初始化状态。
- PlaceholderAPI、LuckPerms 是可选项。
- `siyuan.admin` 是所有管理员命令和游戏内菜单编辑权限。

将 `target/SiYuan-1.0.0.jar` 放入服务器 `plugins/` 目录，首次启动会生成 `plugins/SiYuan/config.yml`、通行证、任务和菜单示例文件。不要用开发默认密码直接暴露到公网。

## MySQL 与 Redis

游戏服配置文件中的 `database` 是玩法数据的唯一存储位置：玩家通行证、任务进度、商店、传送点和经济审计都在这里。当前玩法库是 MySQL 8，不可直接用 PostgreSQL 替换。

```yaml
database:
  host: "mysql.internal.example"
  port: 3306
  database: "siyuan"
  username: "siyuan"
  password: "replace-with-a-long-random-password"
  ssl: true
  allow-public-key-retrieval: false

redis:
  enabled: true
  host: "redis.internal.example"
  port: 6379
  password: "replace-with-a-long-random-password"
```

MySQL 账户只应被授权访问 SiYuan 自己的数据库。远程数据库启用 `ssl: true`，并在防火墙只允许游戏服内网地址访问。Redis 不可用时，通行证奖励额度会退化为本机计数；多游戏服共享限额时必须启用同一 Redis，且建议保持 `noeviction`。

本项目提供仅用于本机开发的 MySQL + Redis Compose：

```bash
cd /root/codexproject/SiYuan
docker compose -f docker/docker-compose.yml up -d
```

它只绑定 `127.0.0.1:3307`、`127.0.0.1:6380`，不会占用系统默认的 MySQL 或 Redis 端口。生产环境优先使用已有受管数据库或私网容器网络。

## 经济与通胀控制

```yaml
pass:
  tier-prices:
    premium: 500.0
    vip: 1500.0
  reward-money-multiplier: 0.85

economy:
  max-daily-reward-per-player: 5000.0

shop:
  listing-fee: 0.05
  max-price-per-unit: 100000.0
  max-listing-amount: 128
  max-listings-per-player: 16
```

玩家间商店成交是余额转移，不增加总货币量。上架手续费、通行证升级和传送费用是货币销毁；通行证发钱奖励才会铸币，并同时受 `reward-money-multiplier` 和玩家每日 `max-daily-reward-per-player` 限制。所有铸币、销毁、退款都会进入 `sy_economy_events`，可结合每日快照检查异常。

## 任务、商店与传送点

```yaml
quest:
  daily-reset-hour: 0
  daily-reset-minute: 0
  weekly-reset-day: "MONDAY"
  exp-multiplier: 1.0
  assignment-limits:
    daily: 10
    weekly: 30
    seasonal: 0
    story: 0
    challenge: 0

waypoint:
  max-waypoints: 18
  prices:
    add: 10.0
    teleport: 2.0
    refund: 8.0
```

`assignment-limits` 的 `0` 表示该任务类型全部显示；其他正数表示按玩家 UUID 和周期稳定抽取的数量。传送失败或世界未加载时，传送费用会自动退回。

## 菜单格式与动作

菜单位于 `plugins/SiYuan/menus/`。SiYuan 可加载 DeluxeMenus 样式 YAML、常见 TrMenu `layout`/`Icons` YAML 以及旧版顶层槽位格式；游戏内保存统一输出 DeluxeMenus 样式 YAML，便于 Web 版本控制。

游戏内编辑每次写入前都会把原文件复制到 `plugins/SiYuan/menus/.backups/`，用于回退 TrMenu 转换或未建模的第三方字段。编辑后的规范格式是 DeluxeMenus YAML；复杂 TrMenu 的独立绑定和布局扩展应先通过 Web 导入检查，再在生产服编辑。

```yaml
menu_title: "&6主菜单"
size: 27
open_requires_permission: false
items:
  spawn:
    slot: 13
    material: NETHER_STAR
    display_name: "&e出生点"
    lore:
      - "&7左键传送"
    left_click_commands:
      - "[player] spawn"
      - "[sound] ENTITY_PLAYER_LEVELUP-1-1"
    right_click_commands:
      - "[message] &a欢迎回来"
```

动作支持 `command:`、`console:`/`op:`、`tell:`/`message:`、`chat:`、`menu:`、`sound:` 和 `close`。`[player]`、`[console]`、`[message]`、`[sound]`、`[open]`、`[close]` 会被规范化；GFMenu 1.10 的中文别名，例如 `控制台命令:`、`打开菜单:`、`声音:` 也可导入。`%player%`、`{player}`、`%uuid%`、`{uuid}` 会在游戏内动作执行时替换。

管理员使用以下命令管理菜单：

```text
/gc menu open <名称>
/gc menu edit <名称> [行数] [标题]
/gc menu action <槽位> <left|right|all> <动作|clear>
/gc menu title <标题>
/gc menu permission <权限节点|none>
/gc menu save
/gc menu cancel
/gc menu sync
```

编辑器内现有菜单物品是临时虚拟副本，结束编辑后会清理；从背包放入的真实物品会作为菜单模板保存，并归还给编辑者。不要让非管理员获得 `siyuan.admin`。

## 异地 Web 菜单管理

在 Web 管理端创建游戏服务器后，会得到一次性的 `server-id` 和同步令牌。将其填入对应游戏服：

```yaml
menu-sync:
  enabled: true
  base-url: "https://menu.example.com"
  server-id: "lobby-1"
  sync-token: ""
  poll-seconds: 30
  timeout-seconds: 8
  push-game-edits: true
  allow-remote-console-actions: false
  allow-insecure-http: false
```

生产环境推荐不要把 `sync-token` 写入 YAML，而是在启动 Paper 的服务管理器中设置 `SIYUAN_WEB_SYNC_TOKEN`。该变量优先于配置文件。每台游戏服必须使用自己的令牌；它只能访问自己的菜单，不等同于 Web 管理 API Key。

`allow-remote-console-actions` 默认关闭。此时带 `console:` 或 `op:` 的菜单动作不会被上传或从 Web 下发；只有在 Web 管理端与每台游戏服都受同一管理员严格控制时才应显式开启。普通 `command:` 动作由点击玩家执行，不受此限制。

Web 可以部署在另一台机器。游戏服只通过出站 HTTPS 拉取已发布菜单并上传游戏内保存，不需要开放任何入站 HTTP 端口，也不需要让 PostgreSQL 暴露给 Minecraft 主机。Web 部署、PostgreSQL 选项和反向代理要求见 [`web/README.md`](../web/README.md)。

远程文件会隔离在 `plugins/SiYuan/menus/.remote/<server-id>/`。同名本地菜单不会被远程更新覆盖，插件会拒绝同步并在控制台报告冲突；先改名、迁移或删除本地菜单后再执行 `/gc menu sync`。游戏内保存若发现 Web 版本已改变也会被 Web 拒绝，先同步再重新编辑即可避免覆盖另一位管理员的改动。
