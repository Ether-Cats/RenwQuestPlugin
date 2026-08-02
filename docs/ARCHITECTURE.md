# SiYuan 架构说明

## 组件边界

`SiYuanPlugin` 只负责生命周期和依赖装配。业务模块通过服务接口访问数据库、Redis、Vault 和消息系统：

```text
Paper events/commands
        |
        +-- GuiManager ---- PassManager ---- RewardExecutor
        |                +-- QuestManager --- QuestListener
        |                +-- ShopManager ---- EconomyService
        |                +-- WaypointManager
        |
        +-- SeasonManager
             |
             +-- MySQL (source of truth)
             +-- Redis (quota/cache/metrics)
             +-- Vault (external economy)

Browser --HTTPS--> Menu Web --PostgreSQL/JSONB
                         ^
                         |
               outbound pull/push
                         |
                 RemoteMenuSyncService
```

原插件职责映射：RenwQuestPlugin 的事件型任务进入 `QuestManager`，CSD 的个人坐标进入 `WaypointManager`，SHOP 的物品市场进入 `ShopManager`，GFMenu 的静态菜单由 `DynamicMenuManager` 兼容加载，业务 GUI 由 `GuiManager` 统一承载。外部命令全部收敛到 `/gc`，不再注册原插件的独立命令前缀。

## 一致性规则

1. MySQL 是赛季、通行证、领取记录、任务、商店库存和交易的最终来源。
2. 商店购买先使用 `UPDATE ... WHERE amount >= ?` 预留库存，再执行 Vault 转账；失败会恢复库存和物品。
3. 货币只允许三类入口：`mintReward`（铸币）、`sink`（销毁）、`transfer`（账户间转移）。普通 `deposit/withdraw` 不写入净供应统计，用于退款和内部回滚。
4. GUI 只信任私有 `InventoryHolder` 的动作标识，不信任玩家放入的物品名称或 Lore。
5. 赛季数据按 `season_id` 保留为历史；结束/切换成功后才清理对应内存缓存，避免旧缓存回写新赛季。

## 通行证与任务优化整合

当前实现将赛季、三档通行证、经验曲线、逐等级奖励、重复领取保护、任务类型和可配置 GUI 统一到同一数据与权限模型。任务按周期稳定分配，完成后由 GUI 领取经验/奖励。菜单内部采用 DeluxeMenus 槽位模型，支持标准 `items:`、旧版顶层 slot 和常用 TrMenu 布局导入；游戏内编辑器使用会话 PDC 防止虚拟图标泄漏。后续可在这一基础上增加前置任务链、跨服排行榜、运营活动模板和跨服交易补偿账本，而不改变现有经济审计边界。

## Web 与跨服菜单

Web 控制面是独立 Node.js 服务，PostgreSQL 保存规范化 `JSONB` 菜单、不可变版本、发布指针和审计日志。管理 API 使用全局管理密钥，每个 `server-id` 使用只保存 SHA-256 的独立同步令牌。游戏服只发起出站 HTTPS，不监听管理端口，也不直连 PostgreSQL。

`RemoteMenuSyncService` 使用 ETag 轮询已发布版本，完整校验后原子替换本地 YAML；同步清单确保只删除此前由 Web 管理的文件。游戏内保存可通过同一服务器令牌写回新版本并自动发布，单线程操作队列避免同一实例上传乱序，PostgreSQL advisory lock 串行化同菜单的跨实例写入。

玩法数据继续使用 MySQL，避免无收益的大规模迁移；Web 版本数据使用 PostgreSQL，是因为 `JSONB`、事务发布和版本查询更贴合该负载。Redis 仍作为奖励额度和经济指标共享层。AI 服务不应直接操作 Vault、发奖、菜单发布或管理密钥。
