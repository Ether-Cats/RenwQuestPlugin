# siyuan 思渊 Web 菜单管理

独立部署的 siyuan 菜单控制面。管理员在浏览器编辑、保存和发布菜单；每台 Paper 游戏服凭自己的同步令牌通过出站 HTTPS 拉取已发布版本，也可以把游戏内编辑写回同一版本链。Minecraft 主机不需要开放 Web 入站端口，也不需要直连 Web 数据库。

## 数据边界

- Paper 插件的玩家、通行证、任务进度、商店、传送点和经济审计继续使用 **MySQL 8**，不能改用 PostgreSQL。
- Web 菜单控制面可选 **PostgreSQL** 或 **MySQL 8**。它只创建并使用自己的 `web_*` 表，用于服务器、菜单版本、审计、Web 用户和会话。
- Web 与游戏服通过版本化 HTTPS API 解耦，因此 Web、数据库和任意数量的游戏服可以部署在不同机器。

菜单数据关系：

```text
web_servers
  -> web_menus
     -> web_menu_versions
     -> published_version
  -> web_audit_log
```

每台服务器都有独立同步令牌。数据库只保存令牌哈希；明文只会在创建或轮换时返回一次。同步令牌、Web 管理 API Key、浏览器账号密码三者互不通用。

## 获取部署包

```bash
# 源码部署
git clone --branch siyuan https://github.com/siyuanmc/RenwQuestPlugin.git
cd RenwQuestPlugin/web

# 或下载 Release 中的 Web 部署包
tar -xzf siyuan-web-v1.1.0.tar.gz
cd siyuan-web-v1.1.0
```

Release 同时包含 Paper 用的 `siyuan-1.1.0.jar` 和 Web 部署包。Web 不在插件 JAR 内，也不需要从 JAR 提取文件。

## 默认部署

默认 `docker-compose.yml` **仅启动一个 Web 容器**。它不会创建数据库容器或数据库卷，也不会修改其他 Docker 项目；Compose 只会建立该 Web 服务自己的标准网络。数据库由服主自行提供；首次运行会在指定数据库中幂等创建 `web_*` 表和索引。

```bash
cp .env.example .env
# 编辑 .env，按下面的 PostgreSQL 或 MySQL 示例填写
docker compose up -d --build
docker compose logs --tail=100 web
```

升级 Web 也使用同一条命令：`docker compose up -d --build`。停止此 Compose 项目使用 `docker compose down`，它只停止 Web 容器，不会删除服主提供的数据库内容。

### PostgreSQL 示例

```dotenv
DATABASE_TYPE=postgres
DATABASE_URL=postgresql://siyuan_web:replace-me@postgres.example.com:5432/siyuan_web
DB_SSL=true
DB_SSL_VERIFY=true

SIYUAN_WEB_API_KEY=replace-with-at-least-32-random-characters
SIYUAN_WEB_ADMIN_USER=admin
SIYUAN_WEB_ADMIN_PASSWORD=replace-with-a-password-of-at-least-12-characters
SIYUAN_WEB_SESSION_SECURE=true
SIYUAN_WEB_SESSION_TTL_HOURS=12

WEB_BIND_ADDRESS=127.0.0.1
WEB_PORT=8080
CORS_ORIGINS=https://menu.example.com
TRUST_PROXY=1
```

### MySQL 示例

```dotenv
DATABASE_TYPE=mysql
DATABASE_URL=mysql://siyuan_web:replace-me@mysql.example.com:3306/siyuan_web
DB_SSL=true
DB_SSL_VERIFY=true

SIYUAN_WEB_API_KEY=replace-with-at-least-32-random-characters
SIYUAN_WEB_ADMIN_USER=admin
SIYUAN_WEB_ADMIN_PASSWORD=replace-with-a-password-of-at-least-12-characters
SIYUAN_WEB_SESSION_SECURE=true
SIYUAN_WEB_SESSION_TTL_HOURS=12

WEB_BIND_ADDRESS=127.0.0.1
WEB_PORT=8080
CORS_ORIGINS=https://menu.example.com
TRUST_PROXY=1
```

PostgreSQL 建议使用专用数据库和专用角色。MySQL 需为 8.0+、InnoDB、`utf8mb4`，同样使用专用数据库和账号。账号需要在自己的数据库内建表、建索引和读写 `web_*` 表的权限。连接串中的 `@`、`:`、`/`、`#` 等保留字符必须进行 URL 编码。

生产环境应由 Nginx、Caddy 或 Traefik 提供 HTTPS，并保持 `WEB_BIND_ADDRESS=127.0.0.1`、`SIYUAN_WEB_SESSION_SECURE=true` 与正确的 `CORS_ORIGINS`。无反向代理的本地 HTTP 调试可暂时设 `SIYUAN_WEB_SESSION_SECURE=false` 和 `TRUST_PROXY=0`，不要把该设置直接用于公网。

## 账号密码登录

首次启动会用 `SIYUAN_WEB_ADMIN_USER` 和 `SIYUAN_WEB_ADMIN_PASSWORD` 创建管理员账号。浏览器只提交账号密码；成功后服务端签发 `HttpOnly`、`SameSite=Lax` 会话 Cookie，写操作额外要求 CSRF 令牌。浏览器不会保存管理 API Key 或会话明文。

`SIYUAN_WEB_ADMIN_PASSWORD` 是该管理员账号的期望密码。之后修改 `.env` 中的密码并重新执行 `docker compose up -d --build` 会更新密码并撤销该账号已有的浏览器会话。密码长度必须为 12 到 256 字符，数据库只保存 scrypt 哈希。

打开 `https://menu.example.com` 后直接用该账号密码登录。创建游戏服务器时，页面会显示一次同步令牌；立即将它填入该游戏服的 `menu-sync.sync-token` 或启动环境变量 `SIYUAN_WEB_SYNC_TOKEN`。

## 环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `DATABASE_TYPE` | `postgres` | `postgres`（也接受 `postgresql`）或 `mysql`。 |
| `DATABASE_URL` | 无 | 服主提供的数据库连接串。 |
| `DB_SSL` / `DB_SSL_VERIFY` | `true` / `true` | 两种数据库的 TLS 开关与证书校验。 |
| `SIYUAN_WEB_ADMIN_USER` | 无 | 初始/受管管理员账号，3 到 64 位字母、数字或 `._-@`。 |
| `SIYUAN_WEB_ADMIN_PASSWORD` | 无 | 管理员密码，12 到 256 字符；修改后会在启动时轮换。 |
| `SIYUAN_WEB_SESSION_SECURE` | `true` | HTTPS 环境必须为 `true`；本地 HTTP 调试才设为 `false`。 |
| `SIYUAN_WEB_SESSION_TTL_HOURS` | `12` | Web 会话时长，范围 1 到 720 小时。 |
| `SIYUAN_WEB_API_KEY` | 无 | 至少 32 字符；保留给自动化管理 API，不用于浏览器登录。 |
| `WEB_BIND_ADDRESS` | `127.0.0.1` | 宿主机绑定地址。通常保持回环地址，由反向代理提供 HTTPS。 |
| `WEB_PORT` | `8080` | 宿主机 Web 端口；容器内恒为 `8080`。 |
| `CORS_ORIGINS` | `http://localhost:8080` | 允许浏览器访问的精确来源，多个来源用英文逗号分隔。 |
| `TRUST_PROXY` | `1` | 前方有一层反向代理时保持 `1`；没有反代时设 `0`。 |
| `SIYUAN_AI_ENABLED` | `false` | 是否启用 Web 端 AI 草稿。 |
| `SIYUAN_AI_BASE_URL` | `https://api.openai.com/v1` | OpenAI 兼容 Chat Completions API 根地址。 |
| `SIYUAN_AI_API_KEY` / `SIYUAN_AI_MODEL` | 无 | 仅保存在 Web 容器内的 AI 提供商凭据与模型名。 |
| `SIYUAN_AI_TIMEOUT_MS` | `20000` | 单次 AI 请求超时，范围 1000 到 120000 毫秒。 |
| `SIYUAN_AI_MAX_PROMPT_CHARS` / `SIYUAN_AI_MAX_TOKENS` | `2000` / `1200` | 每次需求长度与模型输出上限。 |
| `SIYUAN_AI_RATE_LIMIT_PER_MINUTE` | `6` | 每个操作人和来源的每分钟 AI 请求上限。 |

## 异地部署

Web、数据库与游戏服可以分别放在不同服务器：

```text
Minecraft server A -- outbound HTTPS --> menu.example.com --> PostgreSQL or MySQL
Minecraft server B -- outbound HTTPS --> menu.example.com --> PostgreSQL or MySQL
```

不要向游戏服或公网开放 Web 数据库。游戏服只需要能通过 HTTPS 访问 `menu-sync.base-url`。生产环境必须使用 HTTPS；管理 API Key 或同步令牌经明文 HTTP 传输会被中间节点获取。反向代理还应限制管理端来源 IP 或 VPN、设置访问日志与请求速率限制。

Web 可以横向运行多个实例，只要它们使用同一个 Web 数据库、相同的 `SIYUAN_WEB_API_KEY` 和管理员环境变量。保存与发布使用事务、菜单行锁和 `baseVersion` 乐观锁，避免静默覆盖。

游戏服配置示例：

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

生产环境推荐通过服务管理器设置 `SIYUAN_WEB_SYNC_TOKEN`，它优先于 YAML 中的 `sync-token`。每台游戏服必须有独立令牌；它只能读写所属 `server-id` 的菜单。

## 同步与管理 API

同步接口不使用浏览器账号或管理 API Key：

```http
GET /api/sync/lobby-1 HTTP/1.1
X-siyuan-Sync-Token: <该服务器令牌>
If-None-Match: "<上次 checksum>"
```

响应只包含已发布菜单，并提供 `ETag`。内容未变时返回 `304`。游戏内编辑使用 `PUT /api/sync/:serverSlug/menus/:menuKey`，携带 `baseVersion`；版本已变化时服务返回 `409`，插件会先保留本地最后可用版本并提示重新同步。同步接口默认拒绝远程 `console:` 和 `op:` 菜单动作。

浏览器使用 `/api/auth/login`、Cookie 和 CSRF 令牌。自动化管理程序可使用：

```http
X-API-Key: <SIYUAN_WEB_API_KEY>
X-siyuan-Actor: automation-name
```

主要管理接口为 `GET|POST /api/servers`、`POST /api/servers/:id/rotate-sync-token`、`GET|POST /api/servers/:id/menus`、`GET|PUT|DELETE /api/menus/:id`、`POST /api/menus/:id/publish`、`POST /api/import`、`GET /api/menus/:id/export` 和 `GET /api/audit`。创建和轮换同步令牌的响应只显示一次明文令牌，不能记录到日志、工单或仓库。

## AI 草稿

AI 默认关闭。启用后，Web 顶栏提供“AI 草稿”：可生成任务 YAML，或生成并载入当前菜单的**未保存草稿**。任务 YAML 只显示建议文件路径，仍需管理员审阅并放入游戏服；菜单草稿仍需手动保存版本和发布。

```dotenv
SIYUAN_AI_ENABLED=true
SIYUAN_AI_BASE_URL=https://your-ai-gateway.example/v1
SIYUAN_AI_API_KEY=replace-with-provider-key
SIYUAN_AI_MODEL=replace-with-provider-model
```

AI Key 不发送到浏览器或 Minecraft 插件。服务按账号和来源限速，审计日志只保存草稿种类与数量，不保存提示词或模型回复。任务草稿只接受 `money`、`exp`、`item` 奖励；菜单草稿拒绝 `console:` 和 `op:` 动作。AI 没有数据库写入、菜单发布、Vault 经济或游戏服控制台权限。

## 验证与排障

```bash
npm ci
npm test
node --check src/app.js
node --check src/database.js
node --check public/app.js
docker compose config --quiet
curl -fsS http://127.0.0.1:8080/health
```

健康检查应返回 `{"status":"ok","database":"ok"}`。未登录访问 `GET /api/session` 必须返回 `401`；登录后会返回当前账号和 `authMethod: "session"`。如果容器无法启动，先检查 `docker compose logs web`、数据库防火墙/白名单、连接串 URL 编码、数据库用户建表权限，以及 `DATABASE_TYPE` 是否与连接串一致。
