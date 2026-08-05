# siyuan 配置参考

siyuan 把游戏功能和菜单控制面拆开：游戏服使用 MySQL 和可选 Redis，Web 菜单管理可使用服主提供的 PostgreSQL 或 MySQL。两套数据不需要互相直连，游戏玩法库仍必须使用 MySQL。

## 前置条件

- Paper `1.21.4` 与 Java `21`。
- Vault 和一个 Vault 经济实现是必需项；插件在找不到经济服务时会主动禁用，避免商店、通行证价格或传送费用出现半初始化状态。
- PlaceholderAPI、LuckPerms 是可选项。
- `siyuan.admin` 是所有管理员命令和游戏内菜单编辑权限。

将 `target/siyuan-1.1.0.jar` 放入服务器 `plugins/` 目录，首次启动会生成 `plugins/siyuan/config.yml`、通行证、任务和菜单示例文件。不要用开发默认密码直接暴露到公网。

从旧实验构建升级时，先将 `plugins/SiYuan/` 重命名为 `plugins/siyuan/`，再启动新版本；插件名改为小写后，Paper 会以新目录作为数据目录。

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

MySQL 账户只应被授权访问 siyuan 自己的数据库。远程数据库启用 `ssl: true`，并在防火墙只允许游戏服内网地址访问。Redis 不可用时，通行证奖励额度会退化为本机计数；多游戏服共享限额时必须启用同一 Redis，且建议保持 `noeviction`。

本项目提供仅用于本机开发的 MySQL + Redis Compose：

```bash
cd /root/codexproject/siyuan
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

### 任务类型、命令与完整示例

`/gc quest` 只负责打开任务界面，不会创建任务。`[]` 代表可选参数，`<>` 代表必填参数：

| 玩家输入 | 说明 | 进度周期 |
| --- | --- | --- |
| `/gc quest` 或 `/gc quest daily` | 打开每日任务。 | 在 `daily-reset-hour` / `daily-reset-minute` 指定的服务器时间重置。 |
| `/gc quest weekly` | 打开每周任务。 | 在 `weekly-reset-day` 指定的星期重置。 |
| `/gc quest seasonal` | 打开赛季任务。 | 绑定当前赛季；建议先执行 `/gc season start <赛季名称>`。 |
| `/gc quest story` | 打开剧情任务。 | 持续保留，不自动重置。 |
| `/gc quest challenge` | 打开挑战任务。 | 持续保留，不自动重置。 |

`/gc quest reset` 仅限 `siyuan.admin`，并且只会立即重置每日任务。它适合测试，不应作为日常运营的重置方式。完成任务后，玩家要在对应任务界面点击已完成的任务领取奖励。

任务文件放在 `plugins/siyuan/quests/<任务类型>/`。目录和 `type` 请保持一致：`daily` 对应 `DAILY`、`weekly` 对应 `WEEKLY`、`seasonal` 对应 `SEASONAL`、`story` 对应 `STORY`、`challenge` 对应 `CHALLENGE`。新增或修改 YAML 后执行 `/gc reload`。

下面是可直接使用的每日任务：

```yaml
# plugins/siyuan/quests/daily/mine_iron.yml
id: "daily_mine_iron"       # 全服唯一 ID；已有玩家进度时不要改动
name: "&f铁匠学徒"          # 支持 Minecraft 颜色代码
description: "挖掘 32 个铁矿石"
type: "DAILY"
experience: 60               # 领取时给予的通行证经验，受 quest.exp-multiplier 影响
priority: 10                 # 数字越小，GUI 中排序越靠前
objectives:                  # 一项或多项；所有目标完成后才能领取
  - type: "BLOCK_BREAK"
    target: "IRON_ORE"
    amount: 32
rewards:                     # 可省略；完成后与经验一起发放
  - "money:30"
  - "item:IRON_INGOT:4"
```

一个任务可以有多个 `objectives`，例如先击杀僵尸再吃面包；每项都达到 `amount` 后才算完成。`target` 不区分大小写，写成 `ANY` 可匹配该事件的所有目标。

| `objectives[].type` | `target` 写法 | 示例 | 何时累计 |
| --- | --- | --- | --- |
| `BLOCK_BREAK` | 方块 Material 名或 `ANY` | `IRON_ORE`、`STONE` | 玩家破坏方块。 |
| `ENTITY_KILL` | 实体类型或 `ANY` | `ZOMBIE` | 玩家击杀实体。 |
| `ITEM_CRAFT` | 物品 Material 名或 `ANY` | `IRON_PICKAXE` | 玩家合成物品。 |
| `ITEM_CONSUME` | 物品 Material 名或 `ANY` | `BREAD` | 玩家食用或使用可消耗物品。 |
| `PLAYER_JUMP` | 固定写 `ANY` | `ANY` | 玩家正常跳跃。 |
| `DAMAGE_DEALT` | 被攻击实体类型或 `ANY` | `ZOMBIE` | 玩家造成伤害；按伤害整数累计。 |
| `DAMAGE_TAKEN` | 攻击者实体类型或 `ANY` | `ZOMBIE` | 玩家受到伤害；按伤害整数累计。 |

支持的额外奖励与通行证奖励相同：`money:30`（Vault 金币，受全局奖励倍率和每日上限约束）、`exp:50`（额外通行证经验）、`item:DIAMOND:2`、`command:give {player} emerald 1`、`permission:siyuan.vip.fly:30`（需要 LuckPerms）和 `title:vip_title`（需要配置 `integrations.title-command`）。`{player}` 与 `{uuid}` 可用于 `command:`。

创建后的检查流程为：保存 YAML -> 管理员执行 `/gc reload` -> 玩家输入相应的 `/gc quest <任务类型>` -> 完成事件目标 -> 在任务 GUI 点击领取。若任务没有出现，先检查 `type` 与目录是否一致，再检查该类型的 `assignment-limits` 是否小于任务池数量。

## 菜单格式与动作

菜单位于 `plugins/siyuan/menus/`。siyuan 可加载 DeluxeMenus 样式 YAML、常见 TrMenu `layout`/`Icons` YAML 以及旧版顶层槽位格式；游戏内保存统一输出 DeluxeMenus 样式 YAML，便于 Web 版本控制。

游戏内编辑每次写入前都会把原文件复制到 `plugins/siyuan/menus/.backups/`，用于回退 TrMenu 转换或未建模的第三方字段。编辑后的规范格式是 DeluxeMenus YAML；复杂 TrMenu 的独立绑定和布局扩展应先通过 Web 导入检查，再在生产服编辑。

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

动作支持 `command:`、`console:`/`op:`、`tell:`/`message:`、`chat:`、`menu:`、`sound:`、`catcher:`、`book:` 和 `close`。`[player]`、`[console]`、`[message]`、`[sound]`、`[open]`、`[close]` 会被规范化；GFMenu 1.10 的中文别名，例如 `控制台命令:`、`打开菜单:`、`声音:`、`聊天输入:`、`书本输入:` 也可导入。插件功能命令仍全部从 `/gc` 进入，因此菜单中调用插件功能应写为 `command:gc ...`。`%player%`、`{player}`、`%uuid%`、`{uuid}` 会在游戏内动作执行时替换。

`catcher:<标识>|start=<动作>|end=<动作>|cancel=<动作>` 会在聊天栏接收一次输入；`book:<标识>|prompt=<提示>|start=<动作>|end=<动作>|cancel=<动作>` 会打开可写书本。完成动作可使用 `%book_input%`、`{book_input}`、`%book_input_<标识>%`、`{input}`、`%input%` 或 `%player_input%` 取得输入内容。聊天输入 30 秒、书本输入 120 秒后自动过期；玩家输入 `cancel` 或 `取消` 会执行 `cancel` 动作。

管理员使用以下命令管理菜单：

```text
/gc menu open <名称>
/gc menu edit <名称> [行数] [标题]
/gc menu action list <槽位> <left|right|all>
/gc menu action <槽位> <left|right|all> <set|add|remove|clear> [动作|序号]
/gc menu title <标题>
/gc menu permission <权限节点|none>
/gc menu save
/gc menu cancel
/gc menu sync
```

`list` 显示从 `0` 开始的动作序号；`set` 覆盖该点击类型的动作列表，`add` 追加一个动作，`remove` 按该序号删除一个动作，`clear` 清空列表。为兼容旧用法，省略操作词时仍等同于 `set`。

编辑器内现有菜单物品是临时虚拟副本，结束编辑后会清理；从背包放入的真实物品会作为菜单模板保存，并归还给编辑者。不要让非管理员获得 `siyuan.admin`。

### GFMenu 命令别名与局部热更新

TrMenu/GFMenu 菜单可保留 `Bindings.Commands`，让玩家直接通过一个命令打开菜单。SiYuan 仅接受小写字母、数字、`_`、`-`、`:` 组成的标签，拒绝覆盖已存在的服务器命令；菜单保存时只卸载并重新注册该菜单自己的别名，不会全量重载其他菜单。

```yaml
Title: "&6每日任务"
layout:
  - "    A    "
Bindings:
  Commands:
    - "daily"
    - "/daily-quests"
Settings:
  permission: "siyuan.quest.daily"
```

默认 `menu-command-bindings.enabled: true`，所以上例会注册 `/daily` 与 `/daily-quests`。管理员可用 `/gc menu commands` 查看当前真正注册成功的别名。游戏内编辑器不会提供别名编辑 UI，但会保留已导入菜单的 `Bindings.Commands`；需要增加或删除别名时修改 YAML 或 Web 菜单源后执行 `/gc menu reload`。

远程 Web 菜单默认不允许注册命令别名，即使其 YAML 含有 `Bindings.Commands`。只有完全信任控制面并在每台游戏服明确设置 `menu-command-bindings.allow-remote: true` 时才会启用；同步时仍会校验格式和每菜单最多 16 个别名。

## 运行管理、公告、审计与 AI

`/gc admin status` 汇总 Paper、TPS/MSPT、JVM、磁盘、MySQL、Redis、菜单别名、远程同步、公告、审计和 AI 状态。`/gc admin players` 仅限管理员，显示在线玩家的游戏模式、生命值和区块坐标以便排障；它不显示 IP、UUID、背包或 OP 状态。

```yaml
announcements:
  enabled: true
  interval-seconds: 300
  messages:
    - "&6[思渊] &f当前在线 &e{online}&f/&e{max_players}"

activity-audit:
  enabled: true
  include-joins: true
  include-blocks: true
  flush-seconds: 5
  queue-size: 2000
```

公告间隔会限制在 10 秒到一天之间，并按消息顺序循环。活动审计采用有界内存队列并异步写入 `plugins/siyuan/audit/activity-YYYY-MM-DD.log`，只记录玩家名、加入/退出、方块放置和破坏的世界/坐标/类型。它明确不采集 IP、聊天内容、背包、UUID 或逐移动轨迹；队列满时丢弃事件并在控制台限频告警，避免影响主线程。

游戏内 AI 使用 OpenAI Chat Completions 兼容接口，默认关闭且需要 `siyuan.ai.use`（默认 OP）：

```yaml
ai-assistant:
  enabled: true
  base-url: "https://api.openai.com/v1"
  model: "your-model"
  timeout-seconds: 20
  max-prompt-chars: 1000
  max-response-chars: 1600
  max-tokens: 512
  rate-limit-per-minute: 6
  allow-insecure-http: false
```

在启动 Paper 的服务管理器环境中设置 `SIYUAN_AI_API_KEY`；该变量优先且是唯一读取位置，插件没有 `/setkey` 命令，也不会把密钥写回 YAML。使用本机 HTTP 提供商时需明确设置 `allow-insecure-http: true`。玩家执行 `/gc ai ask <问题>` 后，网络请求和 JSON 解析都在异步线程完成，回复回到主线程作为普通文本发送；模型输出永远不会被当作服务器命令、菜单动作、经济操作或管理指令执行。`/gc ai status` 仅显示模型与主机，不显示密钥。

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

Web 可以部署在另一台机器。游戏服只通过出站 HTTPS 拉取已发布菜单并上传游戏内保存，不需要开放任何入站 HTTP 端口，也不需要让 Web 使用的 PostgreSQL 或 MySQL 暴露给 Minecraft 主机。Web 部署、账号密码登录、MySQL/PostgreSQL 选择和反向代理要求见 [`web/README.md`](../web/README.md)。

远程文件会隔离在 `plugins/siyuan/menus/.remote/<server-id>/`。同名本地菜单不会被远程更新覆盖，插件会拒绝同步并在控制台报告冲突；先改名、迁移或删除本地菜单后再执行 `/gc menu sync`。游戏内保存若发现 Web 版本已改变也会被 Web 拒绝，先同步再重新编辑即可避免覆盖另一位管理员的改动。
