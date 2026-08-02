# siyuan 思渊菜单管理

独立部署的 siyuan 菜单控制面。它不运行在 Paper 进程内，也不要求 Minecraft 服务器开放入站端口：管理员通过浏览器编辑并发布菜单，游戏服使用各自的服务器级令牌定时拉取已发布版本，也可把游戏内编辑写回同一版本链。

## 为什么使用 PostgreSQL

Web 控制面使用 PostgreSQL，游戏玩法数据仍可继续使用现有 MySQL。菜单文档适合 PostgreSQL `JSONB`，不可变版本、唯一约束、事务发布和审计日志也比把 YAML 文件放到共享目录更可靠。这里没有要求 Minecraft 插件直连 PostgreSQL；两个系统通过版本化 HTTPS API 解耦，后续可以分别扩容和迁移。

数据层级为：

```text
服务器 web_servers
  -> 菜单 web_menus
     -> 不可变版本 web_menu_versions
     -> published_version 指向游戏服可见版本
  -> 审计 web_audit_log
```

每台服务器有独立同步令牌。数据库只保存令牌的 SHA-256，创建或轮换时明文只返回一次。令牌只能读写该 `server-id` 的菜单，不能访问管理 API；管理员 API Key 与游戏服同步令牌不通用。

## 功能

- 1 至 6 行、每行 9 格的可视化编辑器，格子点击编辑和拖动换位。
- 材质、数量、名称、Lore、左/右/任意点击动作、发光、头颅所有者与打开权限编辑。
- DeluxeMenus 标准 `items:` YAML、siyuan JSON 导入导出；兼容旧版顶层 slot 配置。
- 保存生成不可变版本，发布与保存分离，可选择历史版本重新发布。
- 多服务器隔离，每台服务器只读写自己名下的菜单版本。
- 乐观锁防止并发覆盖，所有创建、保存、发布和令牌轮换写审计日志。
- 健康检查、严格输入大小、CORS 白名单、安全响应头和 PostgreSQL 参数化查询。

## 本地或单机部署

要求 Docker Compose。此 Compose 是独立项目，只创建自己的 PostgreSQL、网络、卷和 Web 容器；PostgreSQL 不映射到宿主机。复制环境变量示例并生成随机凭据：

```bash
cd web
cp .env.example .env
openssl rand -hex 32
openssl rand -hex 32
docker compose up -d --build
```

将第一个随机值设为 `POSTGRES_PASSWORD`，第二个设为 `SIYUAN_WEB_API_KEY`。随机十六进制密码可以直接安全放入 Compose 生成的 PostgreSQL URL。默认仅监听 `127.0.0.1:8080`，适合由同机 Nginx、Caddy 或 Traefik 提供 HTTPS 反向代理；需要直接绑定内网地址时设置：

```text
WEB_BIND_ADDRESS=10.0.0.20
WEB_PORT=8080
CORS_ORIGINS=https://menu.example.com
```

打开 Web 页面后输入 `SIYUAN_WEB_API_KEY`。密钥只保存在浏览器 `sessionStorage`，关闭会话即清除。创建服务器时页面会显示一次该服务器的同步令牌，随后将它配置到对应游戏服。

这个默认 Compose 会创建一个独立 PostgreSQL 容器、专用 Docker 网络和命名卷；它不会把 PostgreSQL 映射到宿主机，也不会复用或修改其他 Compose 项目。

## 使用已有 PostgreSQL

已有托管 PostgreSQL、跨机器 PostgreSQL 或统一数据库集群时，不应再启动内置数据库容器。使用独立 Compose 文件即可只部署 Web：

```bash
cd web
cp .env.external-db.example .env
# 填写 DATABASE_URL 与 SIYUAN_WEB_API_KEY
docker compose -f docker-compose.external-db.yml up -d --build
```

生产数据库应使用 TLS，并保持 `DB_SSL=true`、`DB_SSL_VERIFY=true`。`DATABASE_URL` 中用户名或密码含有 `@`、`:`、`/` 等保留字符时必须做 URL 编码。此模式只创建 Web 容器，不会创建、删除或迁移以外的 Docker 数据库容器；服务启动时只会在目标 PostgreSQL 中幂等创建 `web_*` 表和索引。

## 环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `WEB_BIND_ADDRESS` | `127.0.0.1` | 宿主机绑定地址；公网直连才设为 `0.0.0.0`，通常应保持回环地址并由 HTTPS 反代。 |
| `WEB_PORT` | `8080` | 宿主机 Web 端口；容器内部始终为 `8080`。 |
| `SIYUAN_WEB_API_KEY` | 无 | 管理 API Key，至少 32 字符，浏览器登录与管理接口使用。 |
| `CORS_ORIGINS` | `http://localhost:8080` | 允许访问 API 的浏览器来源，多个来源以英文逗号分隔。 |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | `siyuan_web` / `siyuan_web` / 无 | 仅默认内置 PostgreSQL Compose 使用。 |
| `DATABASE_URL` | 无 | 仅外置 PostgreSQL Compose 或直接 Node 启动使用。 |
| `DB_SSL` / `DB_SSL_VERIFY` | `false` / `true` | PostgreSQL TLS 开关与证书校验；外置 Compose 默认启用。 |
| `TRUST_PROXY` | `1` | 前面有一个 Nginx、Caddy 或 Traefik 时保持 `1`；没有反代时设为 `0`。 |

## 异地部署

推荐把 `web + PostgreSQL` 部署在管理机，将 `https://menu.example.com` 反代到 Web 容器。Minecraft 服务器只需允许出站 HTTPS，并轮询同步 API：

```text
Minecraft server A --HTTPS--> menu.example.com --internal--> PostgreSQL
Minecraft server B --HTTPS--> menu.example.com --internal--> PostgreSQL
```

不要把 PostgreSQL 暴露给游戏服或公网。生产环境必须使用 HTTPS；API Key 或同步令牌通过明文 HTTP 传输会被中间节点获取。反向代理还应设置请求速率限制、访问日志和管理端 IP/VPN 限制。

Web 服务是无状态的，可以在负载均衡器后运行多个实例，只要它们使用同一个 PostgreSQL 和相同的管理 API Key。菜单并发安全由数据库事务和 `baseVersion` 乐观锁保证。

## 同步 API 合约

请求：

```http
GET /api/sync/lobby-1 HTTP/1.1
Host: menu.example.com
X-siyuan-Sync-Token: <该服务器创建时返回的令牌>
If-None-Match: "<上次响应的 checksum>"
```

成功返回 `200 application/json`：

```json
{
  "server": { "id": "uuid", "slug": "lobby-1", "display_name": "大厅一服" },
  "checksum": "sha256",
  "generatedAt": "2026-08-01T12:00:00.000Z",
  "menus": [
    {
      "id": "uuid",
      "key": "main",
      "displayName": "主菜单",
      "version": 7,
      "document": { "title": "&6主菜单", "size": 54, "items": [] },
      "yaml": "menu_title: '&6主菜单'\n...",
      "checksum": "sha256"
    }
  ]
}
```

服务同时返回 `ETag: "<checksum>"`。内容未变时，携带 `If-None-Match` 的请求返回 `304` 且无响应体。只有 `published_version` 非空的菜单会出现；草稿永远不会下发。插件应先完整校验响应，再以临时文件加原子重命名替换本地菜单；网络错误、`401`、`5xx` 或无效文档时保留最后一个可用版本。

游戏内编辑写回使用同一个服务器令牌：

```http
PUT /api/sync/lobby-1/menus/main HTTP/1.1
X-siyuan-Sync-Token: <服务器令牌>
Content-Type: application/json

{"baseVersion":7,"yaml":"menu_title: Main\nsize: 9\nitems: {}\n","publish":true}
```

服务会在 PostgreSQL 事务和菜单级 advisory lock 中创建不可变版本；`publish:true` 同时推进发布指针，其他同 `server-id` 实例随后即可拉取。已有菜单必须携带最近一次同步得到的 `baseVersion`；Web 已有新版本时返回 `409`，游戏服应先拉取再重新编辑，避免静默覆盖。状态码：`401` 表示同步令牌无效，`404` 表示服务器标识不存在，`409` 表示版本冲突，`200/304` 表示正常。同步接口不接受管理 API Key。

## 管理 API

除 `/health` 和服务器同步接口外，所有管理 `/api` 请求都必须携带：

```http
X-API-Key: <SIYUAN_WEB_API_KEY>
X-siyuan-Actor: admin-name
```

主要接口：

- `GET /health`：服务和 PostgreSQL 健康检查。
- `GET|POST /api/servers`：列出或创建服务器；创建响应额外包含一次性 `syncToken`。
- `POST /api/servers/:id/rotate-sync-token`：立即废止旧令牌并返回一次新令牌。
- `DELETE /api/servers/:id`：删除服务器及其菜单版本。
- `GET|POST /api/servers/:id/menus`：列出或创建菜单。
- `GET /api/menus/:id?version=N`：读取当前或指定版本及版本历史。
- `PUT /api/menus/:id`：以 `{baseVersion, document, changeNote}` 保存新版本。
- `POST /api/menus/:id/publish`：以 `{version}` 发布当前或历史版本。
- `DELETE /api/menus/:id`：删除菜单及版本。
- `POST /api/import`：以 `{format: "yaml|json", source}` 解析并规范化导入内容。
- `GET /api/menus/:id/export?format=yaml|json&version=N`：导出版本。
- `GET /api/audit?limit=50`：读取最近审计记录。

插件配置中的 `menu-sync.push-game-edits` 控制游戏内保存是否写回 Web；启用时 `/gc menu edit` 保存会自动提交并发布，禁用时仅保留本地菜单。`/gc menu sync` 可立即拉取一次。

管理 API Key 轮换方式是更新所有 Web 实例的 `SIYUAN_WEB_API_KEY` 并滚动重启。服务器同步令牌通过轮换接口逐服更换，旧令牌会立即失效。不要在日志、工单或代码库中记录任何明文密钥。

## 开发与验证

Node.js 18 或更高版本：

```bash
npm ci
npm test
node --check src/app.js
node --check public/app.js
POSTGRES_PASSWORD=test SIYUAN_WEB_API_KEY=0123456789abcdef0123456789abcdef docker compose config --quiet
```

服务启动时自动执行幂等建表。生产升级前仍应备份 PostgreSQL 卷，并定期保留 `web_menu_versions` 与 `web_audit_log`。

部署完成后的最小验收为：访问 `GET /health` 应返回 `{"status":"ok","database":"ok"}`，未携带 `X-API-Key` 的 `GET /api/session` 必须返回 `401`。这两个检查分别确认 Web 与 PostgreSQL 连通，以及管理接口没有被意外公开。
