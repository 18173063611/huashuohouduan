# 华烁 AI 视频平台后端

华烁后端是 AI 爆款视频改造平台的服务端工程，负责账号登录、管理员后台、用户积分、任务中心、资产管理、语音合成、数字人形象、视频生成、第三方 AI 能力接入等业务能力。

当前项目以 Spring Boot 为主体，默认使用远程共享 MySQL / Redis / RabbitMQ 进行本地联调，也可以通过环境变量切换到本地中间件。

## 技术栈

- JDK 17
- Spring Boot 3.4.3
- Maven
- MyBatis-Plus 3.5.9
- H2 / MySQL 8.0
- Spring Validation
- Spring AMQP
- BCrypt 密码加密
- 火山引擎 TOS / Ark / TTS / ASR SDK
- Vidu 数字人接口

## 目录结构

```text
huashuohouduan
├─ src/main/java/com/huashuo
│  ├─ admin          管理员后台、用户管理、模型配置、操作日志
│  ├─ asset          资产中心
│  ├─ avatar         数字人形象生成
│  ├─ common         通用响应、异常、配置、启动兼容初始化
│  ├─ project        项目管理
│  ├─ script         文案改写、分镜
│  ├─ storage        文件/对象存储抽象
│  ├─ task           任务中心、任务状态流转、重试/取消
│  ├─ template       模板管理
│  ├─ upload         文件上传、TOS 上传
│  ├─ user           登录注册、session、积分账户
│  ├─ video          视频生成、数字人口播
│  ├─ voice          TTS、音色库、试听任务
│  └─ writer         对标视频解析、文案服务
├─ src/main/resources
│  ├─ application.yml
│  ├─ application-dev.yml
│  └─ application-secrets.example.yml
├─ sql/schema.sql    数据库结构与演示种子数据
├─ docker-compose.yml
└─ pom.xml
```

## 环境要求

- JDK 17+
- Maven 3.8+
- 可选：Docker Desktop，用于启动 MySQL、Redis、RabbitMQ

## 快速启动

默认 profile 为 `dev`，会直连远程 MySQL、Redis、RabbitMQ；本地默认保持与线上一致的任务发布、消息消费、对象存储和启动初始化逻辑，同时降低本机消费者并发并缩短连接等待，方便调试。启动前可先检查连通性和延迟：

```powershell
cd huashuohouduan
.\tools\check-remote-services.ps1 -Mode direct
```

连通后启动后端：

```bash
cd huashuohouduan
mvn spring-boot:run
```

启动成功后：

- 后端地址：`http://127.0.0.1:8080`
- API 前缀：`http://127.0.0.1:8080/api/v1`

默认本地管理员账号：

- 用户名：`admin`
- 密码：`admin1234`

该默认密码只允许在 `local` / `dev` 等开发 profile 下使用。生产或测试 profile 必须配置强密码。

## 远程服务配置

默认远程服务地址：

- MySQL：`101.47.67.115:3306`
- Redis：`101.47.67.115:6379`
- RabbitMQ：`101.47.67.115:5672`

本地私密配置可以复制示例文件：

```bash
copy src\main\resources\application-secrets.example.yml application-secrets.yml
```

后端根目录的 `application-secrets.yml` 不应提交到 Git。数据库、Redis、RabbitMQ 密码可以写在该文件中，也可以用环境变量覆盖。远程 MySQL 默认使用 `huashuo_admin`，对应密码建议写入 `host.devSqlPassword`。

如需使用 SSH 隧道，先启动隧道：

```powershell
.\tools\start-remote-tunnel.ps1
```

保持隧道窗口打开，并在后端启动环境变量中指向隧道端口：

```powershell
$env:HUASHUO_REMOTE_HOST="127.0.0.1"
$env:HUASHUO_REMOTE_DB_PORT="13306"
$env:HUASHUO_REMOTE_REDIS_PORT="16379"
$env:HUASHUO_REMOTE_RABBITMQ_PORT="5673"
mvn spring-boot:run
```

## 使用本地中间件联调

启动本仓库提供的中间件：

```bash
cd huashuohouduan
docker compose up -d mysql redis rabbitmq
```

MySQL 默认连接信息：

- 地址：`localhost:3306`
- 数据库：`huashuo_ai_video`
- 用户名：`root`
- 密码：`123456`

如果要完全使用本地 Docker 服务，启动时覆盖连接地址：

```powershell
$env:SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:3306/huashuo_ai_video?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
$env:SPRING_DATASOURCE_USERNAME="root"
$env:SPRING_DATASOURCE_PASSWORD="123456"
$env:SPRING_DATA_REDIS_HOST="127.0.0.1"
$env:SPRING_DATA_REDIS_PORT="6379"
$env:SPRING_DATA_REDIS_PASSWORD="147352"
$env:SPRING_RABBITMQ_HOST="127.0.0.1"
$env:SPRING_RABBITMQ_PORT="5672"
$env:SPRING_RABBITMQ_USERNAME="guest"
$env:SPRING_RABBITMQ_PASSWORD="guest"
mvn spring-boot:run
```

## 配置说明

主要配置位于 [application.yml](src/main/resources/application.yml)。

常用环境变量：

| 变量 | 说明 |
| --- | --- |
| `PORT` | 服务端口，默认 `8080` |
| `SPRING_PROFILES_ACTIVE` | 启动环境，默认 `local` |
| `SPRING_DATASOURCE_URL` | 数据库 JDBC 地址 |
| `SPRING_DATASOURCE_USERNAME` | 数据库用户名 |
| `SPRING_DATASOURCE_PASSWORD` | 数据库密码 |
| `SPRING_SQL_INIT_MODE` | SQL 初始化模式，本地可用 `always` |
| `HUASHUO_REMOTE_HOST` | 远程中间件统一 Host，默认 `101.47.67.115` |
| `HUASHUO_REMOTE_DB_HOST` | 远程 MySQL Host，可覆盖统一 Host |
| `HUASHUO_REMOTE_DB_PORT` | 远程 MySQL 端口，默认 `3306` |
| `HUASHUO_REMOTE_REDIS_HOST` | 远程 Redis Host，可覆盖统一 Host |
| `HUASHUO_REMOTE_REDIS_PORT` | 远程 Redis 端口，默认 `6379` |
| `HUASHUO_REMOTE_RABBITMQ_HOST` | 远程 RabbitMQ Host，可覆盖统一 Host |
| `HUASHUO_REMOTE_RABBITMQ_PORT` | 远程 RabbitMQ 端口，默认 `5672` |
| `HUASHUO_ADMIN_USERNAME` | 内置管理员用户名，默认 `admin` |
| `HUASHUO_ADMIN_PASSWORD` | 内置管理员启动密码，生产/测试必填强密码 |
| `HUASHUO_ADMIN_FORCE_RESET` | 是否启动时强制重置内置管理员密码 |
| `HUASHUO_UPLOAD_LOCAL_ROOT` | 本地上传根目录 |
| `HUASHUO_UPLOAD_PUBLIC_BASE_URL` | 上传资源公网访问基址 |
| `UPLOAD_LOCAL_CLEANUP_ENABLED` | 是否启用本地解析临时视频清理，默认 `true` |
| `UPLOAD_LOCAL_CLEANUP_RETENTION_DAYS` | 本地解析临时视频保留天数，默认 `3` |
| `UPLOAD_LOCAL_CLEANUP_CRON` | 清理 cron，默认 `0 0 3 * * ?` |
| `UPLOAD_LOCAL_CLEANUP_WARN_SIZE_MB` | 本地上传目录大小告警阈值，默认 `10240` |
| `UPLOAD_LOCAL_CLEANUP_MIN_FILE_AGE_MINUTES` | 最近修改保护窗口，默认 `30` |

第三方服务密钥：

| 变量 | 说明 |
| --- | --- |
| `TIKHUB_API_KEY` | TikHub 对标视频解析密钥 |
| `VIDU_API_KEY` | Vidu 数字人口播密钥 |
| `VOLCENGINE_TTS_ACCESS_KEY` | 火山 TTS 密钥 |
| `VOLCENGINE_ASR_ACCESS_KEY` | 火山 ASR 密钥 |
| `VOLCENGINE_ARK_API_KEY` | 火山 Ark 通用密钥 |
| `VOLCENGINE_ARKS_API_KEY` | 火山 Ark 视频密钥，可回落到 `VOLCENGINE_ARK_API_KEY` |
| `VOLCENGINE_SEEDANCE_API_KEY` | Seedance 视频生成密钥 |
| `VOLCENGINE_IMAGE_API_KEY` | 图片生成密钥 |
| `VOLCENGINE_TOS_ACCESS_KEY_ID` | TOS Access Key ID |
| `VOLCENGINE_TOS_SECRET_ACCESS_KEY` | TOS Secret Access Key |

## 管理员账号策略

内置管理员不在 `schema.sql` 中写死密码，而是在应用启动时由 `DatabaseCompatibilityInitializer` 创建或修正。

| profile | 行为 |
| --- | --- |
| `local` / `dev` | 允许未配置密码时使用开发默认密码 `admin1234` |
| `prod` / `test` | 必须配置强 `HUASHUO_ADMIN_PASSWORD` |
| 其他未知 profile | 按严格模式处理 |

已有管理员账号时，启动只会确保 `role=ADMIN`、`status=ENABLED`，不会自动改密码。若需要重置密码：

```powershell
$env:HUASHUO_ADMIN_PASSWORD="一个足够强的密码"
$env:HUASHUO_ADMIN_FORCE_RESET="true"
mvn spring-boot:run
```

重置成功后请把 `HUASHUO_ADMIN_FORCE_RESET` 改回 `false`。

## 常用命令

```bash
# 编译并运行测试
mvn test

# 本地启动
mvn spring-boot:run

# 打包
mvn clean package

# 启动 MySQL / Redis / RabbitMQ
docker compose up -d mysql redis rabbitmq

# 停止中间件
docker compose down
```

## 主要接口模块

API 统一前缀为 `/api/v1`。

| 模块 | 示例路径 | 说明 |
| --- | --- | --- |
| 认证 | `/auth/login`、`/auth/me` | 登录、注册、退出、当前用户 |
| 管理后台 | `/admin/users`、`/admin/tasks` | 用户、积分、任务、模型、操作日志 |
| 任务中心 | `/tasks`、`/tasks/{id}/retry` | 任务列表、详情、重试、取消、已读 |
| 上传 | `/uploads` | 文件上传与上传记录 |
| 资产 | `/assets` | 资产列表、资产详情 |
| 语音 | `/tts`、`/voices` | TTS、音色、试听 |
| 形象 | `/avatars` | 数字人形象上传与生成 |
| 视频 | `/video` | 视频生成、数字人口播 |
| 文案/分镜 | `/scripts`、`/storyboards` | 文案改写、分镜生成 |

## 数据库说明

`sql/schema.sql` 是当前项目维护的库表脚本，构建时会复制到 classpath 根目录供 Spring SQL Init 加载。

已有数据库不会因为 `create table if not exists` 自动补旧表字段，因此项目中保留了 `DatabaseCompatibilityInitializer` 做部分本地兼容升级，例如用户角色字段、任务字段、积分账户初始化等。

## 部署建议

生产或测试环境建议：

1. 显式设置 `SPRING_PROFILES_ACTIVE=prod` 或 `test`。
2. 设置强 `HUASHUO_ADMIN_PASSWORD`。
3. 使用 MySQL 持久化数据库，不使用 H2。
4. 第三方 API 密钥全部使用环境变量或私密配置，不提交到仓库。
5. 首次初始化后，视情况将 `SPRING_SQL_INIT_MODE` 调整为 `never`，避免重复执行种子脚本。
6. Redis / RabbitMQ 使用独立持久化实例并接入监控。

## 常见问题

### 启动时报 `HUASHUO_ADMIN_PASSWORD` 未配置

说明当前 active profile 是 `prod`、`test` 或其他严格 profile。解决方式：

- 本地开发：设置 `SPRING_PROFILES_ACTIVE=local` 或使用默认配置。
- 生产/测试：配置强 `HUASHUO_ADMIN_PASSWORD`。

### H2 文件被锁定

同一个 `.mv.db` 只能被一个 JVM 打开。结束旧的后端进程后再启动。

### 本地使用 MySQL 时连接失败

先确认 Docker MySQL 已启动：

```bash
docker compose ps
```

再确认 `SPRING_PROFILES_ACTIVE=dev`，或手动配置 `SPRING_DATASOURCE_URL`、用户名和密码。
