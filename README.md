# RenwQuestPlugin

一个基于 Paper 1.21 的 Minecraft 每日/周常任务插件，帮助服务器轻松建立任务系统，提升玩家活跃度。

---

## 📸 插件展示

![任务菜单示例](https://your-image-link.com/menu.png)
![完成提示示例](https://your-image-link.com/complete.png)

---

## ✨ 主要功能

- **每日任务**：每天随机分配 10 个任务，次日 0 点自动重置。
- **周常任务**：每周随机分配 30 个任务，7 天后自动重置。
- **实时进度追踪**：挖矿、击杀、合成、进食等操作自动更新进度。
- **GUI 可视化菜单**：使用 `/renw` 打开，绿色羊毛表示可领取，红色表示已领取，清晰直观。
- **任务完成提示**：任务达成时在聊天框发送醒目通知。
- **奖励领取**：点击已完成任务直接领取奖励（默认发放经验值，可自定义）。

---

## 📋 命令与权限

| 命令 | 说明 | 权限 |
| :--- | :--- | :--- |
| `/renw` | 打开每日/周常任务菜单 | 默认所有玩家可用 |

> 权限节点：`renw.use`（可自行配置，默认 OP 或所有玩家）

---

## ⚙️ 配置文件

插件首次运行会自动生成 `items.json`，你可以在其中自由增删改任务：

```json
{
  "id": "d1",
  "name": "[日常] ★ 矿工的试炼 - 挖掘沙子",
  "condition_type": "BLOCK_BREAK",
  "target": "SAND",
  "amount": 70,
  "reward": 30
}
```

> `condition_type` 支持：`BLOCK_BREAK`、`ENTITY_KILL`、`ITEM_CRAFT`、`ITEM_CONSUME`

---

## 🔧 依赖与兼容

- **服务端**：Paper 1.21.4（或兼容 1.21 分支）
- **Java**：21
- **可选依赖**：Vault（如需金币奖励，可自行扩展）

---

## 📬 反馈与贡献

如有问题或建议，欢迎提交 [Issue](https://github.com/Ether-Cats/RenwQuestPlugin/issues) 或 Pull Request。
