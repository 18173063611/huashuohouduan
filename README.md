# Huashuo Backend

## Stack

- JDK: 17
- Spring Boot: 3.4.3
- Maven: 3.9.x
- MySQL: 8.0
- Runtime dependencies: MySQL, RabbitMQ, Volcengine SDKs

## Local MySQL

Start the bundled database:

```bash
docker compose up -d mysql
```

Default local connection:

- JDBC URL: `jdbc:mysql://localhost:3306/huashuo_ai_video?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai`
- Username: `root`
- Password: `123456`

`sql/schema.sql` is mounted into the MySQL init directory and runs on the first container initialization.

## Deployment Secrets

`src/main/resources/application-secrets.yml` is intentionally ignored by Git and Docker. Use deployment environment variables for secrets:

- `TIKHUB_API_KEY`
- `VOLCENGINE_TTS_ACCESS_KEY`
- `VOLCENGINE_ASR_ACCESS_KEY`
- `VOLCENGINE_ARK_API_KEY`
- `VOLCENGINE_ARKS_API_KEY` (optional; falls back to `VOLCENGINE_ARK_API_KEY`)
- `VOLCENGINE_SEEDANCE_API_KEY` (optional; falls back to `VOLCENGINE_ARK_API_KEY`)
- `VOLCENGINE_IMAGE_API_KEY` (optional; falls back to `VOLCENGINE_ARK_API_KEY`)
- `VOLCENGINE_TOS_ACCESS_KEY_ID`
- `VOLCENGINE_TOS_SECRET_ACCESS_KEY`
- `VIDU_API_KEY` (digital human: image/audio lip-sync video)

Database deployment variables:

- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`
- `SPRING_DATASOURCE_DRIVER_CLASS_NAME` (for MySQL: `com.mysql.cj.jdbc.Driver`)
- `SPRING_SQL_INIT_MODE` (usually `never` after schema has been initialized)

For local development, copy `src/main/resources/application-secrets.example.yml` to `src/main/resources/application-secrets.yml` and fill in local values only.
