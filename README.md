# SiYuan 思源

SiYuan 是面向 Paper 1.21.4 的聚合插件，把通行证、任务、玩家市场和个人传送点统一到一个运行时中。项目在既有功能边界上做了优化整合，并统一使用 MySQL 数据、Vault 经济和同一套 GUI/权限边界，减少重复配置和跨插件状态不一致。

## 整合来源

SiYuan 以 [RenwQuestPlugin](https://github.com/Ether-Cats/RenwQuestPlugin)、[CSD](https://github.com/Ether-Cats/CSD)、[SHOP](https://github.com/Ether-Cats/SHOP) 与 [GFMenu](https://github.com/levindurant303/GFMenu) 的功能边界为参考进行优化整合，而不是在服务器内同时加载四个独立插件。任务、通行证、市场、传送点和菜单统一为 `/gc` 命令、同一权限模型与可审计数据层；菜单兼容常用 GFMenu/DeluxeMenus/TrMenu 配置写法，并为后续赛季玩法、跨服运营与管理工具扩展预留边界。

## 当前能力

- 赛季、免费/高级/至尊档、经验曲线、逐等级奖励和防重复领取。
- 每日、每周、赛季、剧情、挑战任务配置加载，监听破坏方块、击杀、合成、消费、跳跃和伤害事件。
- 分页全球市场：物品序列化上架、手续费销毁、购买数量选择、库存条件扣减、下架退回和交易审计。
- 个人传送点：创建、传送、删除退款，世界未加载或传送失败自动退款。
- Vault 是必需的经济前置；PlaceholderAPI、LuckPerms 可选集成；Redis 用于奖励额度和经济快照（不可用时奖励额度降级为本地计数）。
- 自定义菜单采用 DeluxeMenus 的槽位模型，兼容导入常用 TrMenu/旧版顶层 slot 配置，并提供 Web 拖放编辑和游戏内箱子编辑；任意点击、关闭动作、发光和头颅所有者会保留在版本链中。
- 所有玩家数据、领取记录、交易、经济事件和传送点均使用 MySQL 持久化。

## 统一命令

插件只注册一个根命令 `/gc`，不再注册 `/pass`、`/quest`、`/shop`、`/wp`、`/gfmenu` 等独立前缀：

```text
/gc                         打开主界面
/gc pass                    通行证
/gc quest <类型>            任务
/gc shop                    全球商店
/gc wp [add 名称 图标]      传送点
/gc menu open <名称>        打开菜单
/gc menu edit <名称> [行数] [标题]
/gc menu action <槽位> <left|right|all> <动作|clear>
/gc menu title <标题>
/gc menu permission <权限|none>
/gc menu save|cancel|reload|sync
/gc season ...              管理员赛季命令
/gc reload                  重载插件
```

游戏内编辑器允许管理员直接放置、移动和移除物品；关闭界面自动保存。原菜单物品使用会话 PDC 标记，不能作为实体物品带出，作为模板放入的真实物品会在保存后返还。

## 构建

宿主机安装 JDK 21 和 Maven 3.9 后执行：

```text
mvn -DskipTests package
```

产物为 `target/SiYuan-1.0.0.jar`。项目使用 Shade 隔离 HikariCP、Jedis 和 Gson；Paper、Vault、PlaceholderAPI、LuckPerms 由服务器提供。

发布前的最小验证命令如下；Maven 的 `package` 会执行插件单元测试，Web 端测试会覆盖认证、菜单编解码、版本冲突和服务器同步接口：

```text
mvn clean package
cd web && npm ci && npm test
```

部署前请阅读 [docs/CONFIGURATION.md](docs/CONFIGURATION.md)；其中说明了 Vault、MySQL、Redis、异地 Web、远程菜单安全策略以及单机/外置 PostgreSQL 两种部署方式。

## 本地依赖

`docker/docker-compose.yml` 只创建 `siyuan-net` 网络、`siyuan-mysql` 和 `siyuan-redis` 两个容器，默认仅绑定宿主机回环地址的 `3307` 和 `6380`，不会占用 MySQL/Redis 默认端口。Redis 使用 `noeviction`，避免奖励额度键被淘汰。密码支持环境变量覆盖：

```text
SIYUAN_MYSQL_ROOT_PASSWORD=...
SIYUAN_MYSQL_PASSWORD=...
SIYUAN_REDIS_PASSWORD=...
docker compose -f docker/docker-compose.yml up -d
```

服务器插件配置默认连接 `127.0.0.1:3307` 和 `127.0.0.1:6380`。生产环境应修改 `plugins/SiYuan/config.yml` 的凭据，并限制数据库只监听游戏服内网。

任务完成后在任务 GUI 点击领取；每日/每周从任务池按玩家 UUID 和周期稳定抽取，数量可由 `quest.assignment-limits` 调整。`/gc wp add <名称> [图标]` 可创建带自定义名称和图标的传送点。

完整的 MySQL、Redis、Vault、经济限制、任务、菜单动作和异地 Web 配置见 [docs/CONFIGURATION.md](docs/CONFIGURATION.md)。

## 经济约束

玩家交易使用 `transfer`，不会改变货币总量；上架手续费、通行证档位和传送费用使用 `sink` 销毁；通行证货币奖励使用 `mintReward`，按玩家每日上限和全局倍率铸造。每次铸币、销毁和退款写入 `sy_economy_events`，每日 Redis 统计会落入 `sy_economy_snapshot`，方便监控异常发放。商店价格、交易金额和经济审计使用 `DECIMAL(19,4)` 保存，避免 DOUBLE 的累计舍入误差。

## 异地 Web 菜单管理

[`web/`](web/) 已提供独立的 Node.js + PostgreSQL 控制面：浏览器中可拖放格子，编辑材质、数量、名称、Lore、左右键动作和权限；保存形成不可变版本，明确发布后才会下发。每台游戏服使用独立令牌通过出站 HTTPS 拉取，游戏服无需开放 HTTP 端口，也无需在 Minecraft 主机部署反向代理。

```yaml
menu-sync:
  enabled: true
  base-url: "https://menu.example.com"
  server-id: "lobby-1"
  sync-token: ""          # 推荐改用 SIYUAN_WEB_SYNC_TOKEN
  poll-seconds: 30
  push-game-edits: true   # 游戏内保存后生成并发布 Web 版本
  allow-remote-console-actions: false
```

Web 与游戏内修改会进入同一版本链，同一 `server-id` 的其他服务器可自动收到发布版本。网络、鉴权或文档校验失败时保留本地最后可用版本，文件写入使用临时文件和原子替换。完整部署及 API 说明见 [web/README.md](web/README.md)。

PostgreSQL 只负责 Web 菜单的 `JSONB` 文档、版本和审计；现有玩家、经济和商店数据继续使用 MySQL，Redis 继续负责共享额度与指标。这样不需要冒险迁移已经稳定的玩法数据，也不会把数据库直接暴露给异地游戏服。AI 对话仍只保留架构边界，不拥有发奖、扣款或发布菜单权限。
