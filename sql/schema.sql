-- H2（MySQL 模式）与 MySQL 8 共用。索引写在建表语句中，避免各版本对 DROP/CREATE INDEX IF EXISTS 支持不一致。
-- 手动在 MySQL 中建库: CREATE DATABASE IF NOT EXISTS huashuo DEFAULT CHARACTER SET utf8mb4;
-- 然后 USE huashuo; 再执行本文件。
-- 本仓库唯一维护的库表+种子脚本；Maven 构建时复制到 classpath:schema.sql，由 spring.sql.init 执行（见 pom.xml、application*.yml）。

create table if not exists project (
    project_id bigint primary key auto_increment,
    project_name varchar(80) not null,
    description varchar(500),
    status varchar(30) not null default 'DRAFT',
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp,
    deleted tinyint(1) not null default 0,
    key idx_project_status (status),
    key idx_project_deleted (deleted)
);

create table if not exists task (
    task_id bigint primary key auto_increment,
    project_id bigint,
    owner_user_id bigint,
    task_type varchar(50) not null,
    model_code varchar(80),
    credit_cost bigint not null default 0,
    credit_log_id bigint,
    queue_name varchar(80),
    message_id varchar(120),
    idempotency_key varchar(120),
    priority int not null default 0,
    status varchar(30) not null default 'QUEUED',
    progress int not null default 0,
    input_json text,
    output_json text,
    result_asset_id bigint,
    error_code varchar(50),
    retry_count int not null default 0,
    error_message text,
    trace_id varchar(100),
    result_viewed tinyint(1) not null default 0,
    started_at datetime,
    finished_at datetime,
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp,
    deleted tinyint(1) not null default 0,
    key idx_task_project_id (project_id),
    key idx_task_owner_user_id (owner_user_id),
    key idx_task_status (status),
    key idx_task_task_type (task_type),
    key idx_task_model_code (model_code),
    key idx_task_owner_status (owner_user_id, status),
    key idx_task_created_at (created_at),
    unique key uk_task_idempotency_key (idempotency_key),
    key idx_task_deleted (deleted)
);

create table if not exists asset (
    asset_id bigint primary key auto_increment,
    owner_user_id bigint,
    created_by_user_id bigint,
    project_id bigint,
    task_id bigint,
    asset_type varchar(50) not null,
    kind varchar(30) not null default 'MATERIAL',
    visibility varchar(20) not null default 'PRIVATE',
    status varchar(20) not null default 'ACTIVE',
    published_at datetime,
    file_name varchar(255) not null,
    file_path varchar(1000),
    file_url varchar(1000) not null,
    thumbnail_url varchar(1000),
    mime_type varchar(120),
    file_size bigint not null default 0,
    source_type varchar(50) not null default 'UPLOAD',
    metadata_json text,
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp,
    deleted tinyint(1) not null default 0,
    key idx_asset_owner_user_id (owner_user_id),
    key idx_asset_created_by_user_id (created_by_user_id),
    key idx_asset_project_id (project_id),
    key idx_asset_task_id (task_id),
    key idx_asset_asset_type (asset_type),
    key idx_asset_visibility (visibility),
    key idx_asset_kind (kind),
    key idx_asset_status (status),
    key idx_asset_deleted (deleted)
);

create table if not exists template (
    template_id bigint primary key auto_increment,
    owner_user_id bigint,
    created_by_user_id bigint,
    visibility varchar(20) not null default 'PRIVATE',
    status varchar(20) not null default 'ACTIVE',
    published_at datetime,
    version_no int not null default 1,
    title varchar(120) not null,
    description varchar(1000),
    cover_asset_id bigint,
    tags varchar(500),
    metadata_json text,
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp,
    deleted tinyint(1) not null default 0,
    key idx_template_owner_user_id (owner_user_id),
    key idx_template_created_by_user_id (created_by_user_id),
    key idx_template_visibility (visibility),
    key idx_template_status (status),
    key idx_template_deleted (deleted)
);

create table if not exists template_asset_rel (
    rel_id bigint primary key auto_increment,
    template_id bigint not null,
    asset_id bigint not null,
    asset_role varchar(50) not null default 'MATERIAL',
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp,
    deleted tinyint(1) not null default 0,
    key idx_template_asset_rel_template_id (template_id),
    key idx_template_asset_rel_asset_id (asset_id),
    key idx_template_asset_rel_deleted (deleted)
);

create table if not exists script_version (
    script_version_id bigint primary key auto_increment,
    project_id bigint,
    owner_user_id bigint,
    parse_id bigint,
    version_no int not null,
    source_script text,
    content text not null,
    source_type varchar(50) not null default 'DEMO',
    rewrite_style varchar(80),
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp,
    deleted tinyint(1) not null default 0,
    key idx_script_version_project_id (project_id),
    key idx_script_version_owner_user_id (owner_user_id),
    key idx_script_version_deleted (deleted)
);

create table if not exists voice_profile (
    voice_id bigint primary key auto_increment,
    provider varchar(50) not null,
    provider_voice_id varchar(120) not null,
    voice_name varchar(80) not null,
    gender varchar(20) not null,
    scene varchar(80),
    sample_url varchar(1000),
    enabled tinyint(1) not null default 1,
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp,
    deleted tinyint(1) not null default 0,
    key idx_voice_profile_deleted (deleted),
    key idx_voice_profile_provider (provider)
);

create table if not exists avatar_profile (
    avatar_id bigint primary key auto_increment,
    project_id bigint,
    task_id bigint,
    asset_id bigint,
    avatar_name varchar(80) not null,
    source_type varchar(50) not null,
    prompt text,
    reference_asset_ids varchar(500),
    preview_url varchar(1000),
    metadata_json text,
    default_avatar tinyint(1) not null default 0,
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp,
    deleted tinyint(1) not null default 0,
    key idx_avatar_profile_project_id (project_id),
    key idx_avatar_profile_task_id (task_id),
    key idx_avatar_profile_asset_id (asset_id),
    key idx_avatar_profile_deleted (deleted)
);

create table if not exists uploaded_file (
    file_id bigint primary key auto_increment,
    project_id bigint,
    owner_user_id bigint,
    original_file_name varchar(255) not null,
    stored_file_name varchar(255) not null,
    file_path varchar(1000) not null,
    preview_url varchar(1000) not null,
    mime_type varchar(120),
    file_size bigint not null,
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp,
    deleted tinyint(1) not null default 0,
    key idx_uploaded_file_project_id (project_id),
    key idx_uploaded_file_owner_user_id (owner_user_id),
    key idx_uploaded_file_deleted (deleted)
);

-- ---- 用户与登录（MVP：轻量 token session，不引入 Spring Security 过滤链） ----

create table if not exists user_account (
    user_id bigint primary key auto_increment,
    username varchar(60) not null,
    password_hash varchar(120) not null,
    display_name varchar(80),
    role varchar(20) not null default 'USER',
    status varchar(20) not null default 'ENABLED',
    phone varchar(30),
    email varchar(120),
    remark varchar(500),
    last_login_at datetime,
    last_login_ip varchar(60),
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp,
    deleted tinyint(1) not null default 0,
    unique key uk_user_account_username (username),
    key idx_user_account_role (role),
    key idx_user_account_status (status),
    key idx_user_account_created_at (created_at),
    key idx_user_account_deleted (deleted)
);

create table if not exists user_credit_account (
    credit_account_id bigint primary key auto_increment,
    user_id bigint not null,
    balance bigint not null default 0,
    frozen_balance bigint not null default 0,
    total_recharged bigint not null default 0,
    total_consumed bigint not null default 0,
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp,
    deleted tinyint(1) not null default 0,
    unique key uk_user_credit_account_user_id (user_id),
    key idx_user_credit_account_deleted (deleted)
);

create table if not exists user_credit_log (
    credit_log_id bigint primary key auto_increment,
    user_id bigint not null,
    change_type varchar(30) not null,
    change_amount bigint not null,
    before_balance bigint not null,
    after_balance bigint not null,
    related_task_id bigint,
    model_code varchar(80),
    operator_admin_id bigint,
    idempotency_key varchar(120),
    remark varchar(500),
    created_at datetime not null default current_timestamp,
    deleted tinyint(1) not null default 0,
    key idx_user_credit_log_user_id (user_id),
    key idx_user_credit_log_related_task_id (related_task_id),
    unique key uk_user_credit_log_idempotency_key (idempotency_key),
    key idx_user_credit_log_created_at (created_at)
);

create table if not exists ai_model_config (
    model_id bigint primary key auto_increment,
    model_code varchar(80) not null,
    model_name varchar(120) not null,
    model_type varchar(30) not null,
    provider varchar(50) not null,
    provider_model varchar(120),
    credit_cost bigint not null default 0,
    enabled tinyint(1) not null default 1,
    default_model tinyint(1) not null default 0,
    capability_json text,
    default_params_json text,
    rate_limit_per_minute int,
    concurrency_limit int,
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp,
    deleted tinyint(1) not null default 0,
    unique key uk_ai_model_config_model_code (model_code),
    key idx_ai_model_config_model_type (model_type),
    key idx_ai_model_config_enabled (enabled),
    key idx_ai_model_config_deleted (deleted)
);

create table if not exists task_outbox (
    outbox_id bigint primary key auto_increment,
    event_type varchar(50) not null,
    aggregate_id bigint not null,
    routing_key varchar(100) not null,
    payload_json text not null,
    status varchar(30) not null default 'PENDING',
    retry_count int not null default 0,
    last_error text,
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp,
    deleted tinyint(1) not null default 0,
    key idx_task_outbox_status (status),
    key idx_task_outbox_aggregate_id (aggregate_id),
    key idx_task_outbox_created_at (created_at),
    key idx_task_outbox_deleted (deleted)
);

create table if not exists admin_operation_log (
    operation_id bigint primary key auto_increment,
    admin_user_id bigint not null,
    operation_type varchar(50) not null,
    target_type varchar(50) not null,
    target_id bigint,
    before_json text,
    after_json text,
    ip varchar(60),
    trace_id varchar(100),
    created_at datetime not null default current_timestamp,
    key idx_admin_operation_log_admin_user_id (admin_user_id),
    key idx_admin_operation_log_target (target_type, target_id),
    key idx_admin_operation_log_created_at (created_at)
);

create table if not exists user_session (
    session_id bigint primary key auto_increment,
    user_id bigint not null,
    token varchar(120) not null,
    expires_at datetime not null,
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp,
    deleted tinyint(1) not null default 0,
    unique key uk_user_session_token (token),
    key idx_user_session_user_id (user_id),
    key idx_user_session_expires_at (expires_at),
    key idx_user_session_deleted (deleted)
);

-- ---- seed data（幂等；voice_profile 的 provider_voice_id 须与火山 TTS speaker 一致，勿改） ----

insert into project(project_name, description, status)
select 'AI 数字人口播 MVP 演示项目', '用于演示项目管理、任务中心和资产中心基础能力', 'DRAFT'
where not exists (
    select 1 from project where project_name = 'AI 数字人口播 MVP 演示项目' and deleted = 0
);

insert into task(project_id, task_type, status, input_json, output_json, retry_count, trace_id)
select p.project_id, 'SCRIPT_REWRITE', 'SUCCESS',
       '{"source":"demo script","goal":"生成可联调用例"}',
       '{"summary":"已生成演示文案版本"}',
       0,
       'demo-trace-001'
from project p
where p.project_name = 'AI 数字人口播 MVP 演示项目'
  and p.deleted = 0
  and not exists (select 1 from task t where t.project_id = p.project_id and t.task_type = 'SCRIPT_REWRITE' and t.deleted = 0);

insert into asset(project_id, task_id, asset_type, file_name, file_url, thumbnail_url, mime_type, file_size, source_type, metadata_json)
select p.project_id, t.task_id, 'TEXT', 'demo-script.txt', '/uploads/demo-script.txt', null, 'text/plain', 128, 'DEMO',
       '{"description":"资产中心演示文案"}'
from project p
left join task t on t.project_id = p.project_id and t.task_type = 'SCRIPT_REWRITE' and t.deleted = 0
where p.project_name = 'AI 数字人口播 MVP 演示项目'
  and p.deleted = 0
  and not exists (select 1 from asset a where a.project_id = p.project_id and a.file_name = 'demo-script.txt' and a.deleted = 0);

insert into script_version(project_id, version_no, content, source_type)
select p.project_id, 1, '大家好，今天演示 AI 数字人视频制作的基础工作台。', 'DEMO'
from project p
where p.project_name = 'AI 数字人口播 MVP 演示项目'
  and p.deleted = 0
  and not exists (select 1 from script_version s where s.project_id = p.project_id and s.version_no = 1 and s.deleted = 0);

insert into voice_profile(provider, provider_voice_id, voice_name, gender, scene, sample_url, enabled)
select 'DOUBAO', 'zh_female_shuangkuaisisi_moon_bigtts', '清爽女声', 'FEMALE', '知识口播', null, 1
where not exists (select 1 from voice_profile v where v.provider_voice_id = 'zh_female_shuangkuaisisi_moon_bigtts' and v.deleted = 0);

insert into voice_profile(provider, provider_voice_id, voice_name, gender, scene, sample_url, enabled)
select 'DOUBAO', 'zh_male_liufei_uranus_bigtts', '沉稳男声', 'MALE', '品牌讲解', null, 1
where not exists (select 1 from voice_profile v where v.provider_voice_id = 'zh_male_liufei_uranus_bigtts' and v.deleted = 0);

insert into voice_profile(provider, provider_voice_id, voice_name, gender, scene, sample_url, enabled)
select 'DOUBAO', 'zh_female_wanwanxiaohe_moon_bigtts', '活力女声', 'FEMALE', '带货促销', null, 1
where not exists (select 1 from voice_profile v where v.provider_voice_id = 'zh_female_wanwanxiaohe_moon_bigtts' and v.deleted = 0);

-- demo 用户：用户名 demo / 密码 demo1234（BCrypt）
insert into user_account(username, password_hash, display_name)
select 'demo', '$2a$10$FpjChVSxXshPiX0E62qP5eWjtXi7AfhCgQLJyF9zkwAhvG1zakTIG', '演示用户'
where not exists (select 1 from user_account u where u.username = 'demo' and u.deleted = 0);

insert into user_credit_account(user_id, balance, frozen_balance, total_recharged, total_consumed)
select u.user_id, 0, 0, 0, 0
from user_account u
where u.deleted = 0
  and not exists (select 1 from user_credit_account c where c.user_id = u.user_id and c.deleted = 0);

-- ---- 用户与资产 seed（幂等，用于资产中心联调演示）----
-- 说明：owner_user_id 为 null 表示公共/演示资产，未登录用户仅可列出与查看此类资产；非空则仅该用户与公共资产一并可见。
-- 账号密码统一为 demo1234（BCrypt 同上），便于测试。

insert into user_account(username, password_hash, display_name)
select 'alice', '$2a$10$FpjChVSxXshPiX0E62qP5eWjtXi7AfhCgQLJyF9zkwAhvG1zakTIG', 'Alice 运营'
where not exists (select 1 from user_account u where u.username = 'alice' and u.deleted = 0);

insert into user_account(username, password_hash, display_name)
select 'bob', '$2a$10$FpjChVSxXshPiX0E62qP5eWjtXi7AfhCgQLJyF9zkwAhvG1zakTIG', 'Bob 设计'
where not exists (select 1 from user_account u where u.username = 'bob' and u.deleted = 0);

-- ---- admin MVP seed: users, models, tasks, credit logs and operation logs ----
-- Accounts:
--   admin / admin1234
--   demo, alice, bob / demo1234
insert into user_account(username, password_hash, display_name, role, status)
select 'admin', '$2a$10$Gq2eqLyRndHwjf8gXD9Pc.sPRr2KfmMqmeUVOVmZ1LwwXuiF99mKC', 'System Admin', 'ADMIN', 'ENABLED'
where not exists (select 1 from user_account u where u.username = 'admin' and u.deleted = 0);

update user_account
set role = 'ADMIN', status = 'ENABLED'
where username = 'admin' and deleted = 0;

insert into user_credit_account(user_id, balance, frozen_balance, total_recharged, total_consumed)
select u.user_id, 0, 0, 0, 0
from user_account u
where u.deleted = 0
  and not exists (select 1 from user_credit_account c where c.user_id = u.user_id and c.deleted = 0);

insert into ai_model_config(model_code, model_name, model_type, provider, provider_model, credit_cost, enabled, default_model, capability_json, default_params_json, rate_limit_per_minute, concurrency_limit)
select 'tts-doubao-default', 'Doubao TTS Default', 'TTS', 'VOLCENGINE', 'bigtts', 1, 1, 1,
       '{"taskTypes":["TTS_GENERATE"]}', '{"voice":"zh_female_shuangkuaisisi_moon_bigtts"}', 60, 5
where not exists (select 1 from ai_model_config m where m.model_code = 'tts-doubao-default' and m.deleted = 0);

insert into ai_model_config(model_code, model_name, model_type, provider, provider_model, credit_cost, enabled, default_model, capability_json, default_params_json, rate_limit_per_minute, concurrency_limit)
select 'avatar-seedream-default', 'Seedream Avatar Default', 'IMAGE', 'VOLCENGINE', 'seedream', 5, 1, 1,
       '{"taskTypes":["AVATAR_GENERATE"]}', '{"size":"1024x1024"}', 30, 3
where not exists (select 1 from ai_model_config m where m.model_code = 'avatar-seedream-default' and m.deleted = 0);

insert into ai_model_config(model_code, model_name, model_type, provider, provider_model, credit_cost, enabled, default_model, capability_json, default_params_json, rate_limit_per_minute, concurrency_limit)
select 'digital-human-vidu-default', 'Vidu Digital Human Default', 'VIDEO', 'VIDU', 'vidu-digital-human', 10, 1, 1,
       '{"taskTypes":["DIGITAL_HUMAN_GENERATE"]}', '{"duration":5}', 10, 1
where not exists (select 1 from ai_model_config m where m.model_code = 'digital-human-vidu-default' and m.deleted = 0);

insert into task(project_id, owner_user_id, task_type, model_code, credit_cost, queue_name, message_id, idempotency_key, priority, status, progress, input_json, output_json, error_code, retry_count, error_message, trace_id, started_at, finished_at)
select p.project_id, u.user_id, 'TTS_GENERATE', 'tts-doubao-default', 1, 'task.tts', 'seed-msg-tts-001',
       'SEED:TASK:TTS:demo:001', 0, 'SUCCESS', 100,
       '{"text":"hello huashuo"}', '{"audioUrl":"/uploads/demo-tts.mp3"}',
       null, 0, null, 'seed-trace-tts-001', current_timestamp, current_timestamp
from project p, user_account u
where p.deleted = 0 and u.username = 'demo' and u.deleted = 0
  and not exists (select 1 from task t where t.idempotency_key = 'SEED:TASK:TTS:demo:001' and t.deleted = 0);

insert into task(project_id, owner_user_id, task_type, model_code, credit_cost, queue_name, message_id, idempotency_key, priority, status, progress, input_json, output_json, error_code, retry_count, error_message, trace_id, started_at, finished_at)
select p.project_id, u.user_id, 'AVATAR_GENERATE', 'avatar-seedream-default', 5, 'task.avatar', 'seed-msg-avatar-001',
       'SEED:TASK:AVATAR:alice:001', 0, 'FAILED', 0,
       '{"prompt":"business presenter"}', null,
       'PROVIDER_ERROR', 0, 'seed provider failure, refunded', 'seed-trace-avatar-001', current_timestamp, current_timestamp
from project p, user_account u
where p.deleted = 0 and u.username = 'alice' and u.deleted = 0
  and not exists (select 1 from task t where t.idempotency_key = 'SEED:TASK:AVATAR:alice:001' and t.deleted = 0);

insert into user_credit_log(user_id, change_type, change_amount, before_balance, after_balance, related_task_id, model_code, operator_admin_id, idempotency_key, remark)
select u.user_id, 'ADMIN_ADD', 100, 0, 100, null, null, admin.user_id, 'SEED:CREDIT:ADMIN_ADD:demo', 'seed admin add credits'
from user_account u, user_account admin
where u.username = 'demo' and u.deleted = 0 and admin.username = 'admin' and admin.deleted = 0
  and not exists (select 1 from user_credit_log l where l.idempotency_key = 'SEED:CREDIT:ADMIN_ADD:demo' and l.deleted = 0);

update user_credit_account
set balance = 99, total_recharged = 100, total_consumed = 1, updated_at = current_timestamp
where user_id = (select user_id from user_account where username = 'demo' and deleted = 0)
  and not exists (select 1 from user_credit_log l where l.idempotency_key = 'SEED:CREDIT:AI_CONSUME:demo:tts:001' and l.deleted = 0);

insert into user_credit_log(user_id, change_type, change_amount, before_balance, after_balance, related_task_id, model_code, operator_admin_id, idempotency_key, remark)
select u.user_id, 'AI_CONSUME', -1, 100, 99, t.task_id, 'tts-doubao-default', null,
       'SEED:CREDIT:AI_CONSUME:demo:tts:001', 'seed TTS task cost'
from user_account u, task t
where u.username = 'demo' and u.deleted = 0 and t.idempotency_key = 'SEED:TASK:TTS:demo:001' and t.deleted = 0
  and not exists (select 1 from user_credit_log l where l.idempotency_key = 'SEED:CREDIT:AI_CONSUME:demo:tts:001' and l.deleted = 0);

update task
set credit_log_id = (select credit_log_id from user_credit_log where idempotency_key = 'SEED:CREDIT:AI_CONSUME:demo:tts:001' and deleted = 0)
where idempotency_key = 'SEED:TASK:TTS:demo:001' and deleted = 0 and credit_log_id is null;

insert into user_credit_log(user_id, change_type, change_amount, before_balance, after_balance, related_task_id, model_code, operator_admin_id, idempotency_key, remark)
select u.user_id, 'ADMIN_ADD', 50, 0, 50, null, null, admin.user_id, 'SEED:CREDIT:ADMIN_ADD:alice', 'seed admin add credits'
from user_account u, user_account admin
where u.username = 'alice' and u.deleted = 0 and admin.username = 'admin' and admin.deleted = 0
  and not exists (select 1 from user_credit_log l where l.idempotency_key = 'SEED:CREDIT:ADMIN_ADD:alice' and l.deleted = 0);

update user_credit_account
set balance = 50, total_recharged = 50, total_consumed = 5, updated_at = current_timestamp
where user_id = (select user_id from user_account where username = 'alice' and deleted = 0)
  and not exists (select 1 from user_credit_log l where l.idempotency_key = 'SEED:CREDIT:AI_REFUND:alice:avatar:001' and l.deleted = 0);

insert into user_credit_log(user_id, change_type, change_amount, before_balance, after_balance, related_task_id, model_code, operator_admin_id, idempotency_key, remark)
select u.user_id, 'AI_CONSUME', -5, 50, 45, t.task_id, 'avatar-seedream-default', null,
       'SEED:CREDIT:AI_CONSUME:alice:avatar:001', 'seed avatar task cost'
from user_account u, task t
where u.username = 'alice' and u.deleted = 0 and t.idempotency_key = 'SEED:TASK:AVATAR:alice:001' and t.deleted = 0
  and not exists (select 1 from user_credit_log l where l.idempotency_key = 'SEED:CREDIT:AI_CONSUME:alice:avatar:001' and l.deleted = 0);

update task
set credit_log_id = (select credit_log_id from user_credit_log where idempotency_key = 'SEED:CREDIT:AI_CONSUME:alice:avatar:001' and deleted = 0)
where idempotency_key = 'SEED:TASK:AVATAR:alice:001' and deleted = 0 and credit_log_id is null;

insert into user_credit_log(user_id, change_type, change_amount, before_balance, after_balance, related_task_id, model_code, operator_admin_id, idempotency_key, remark)
select u.user_id, 'AI_REFUND', 5, 45, 50, t.task_id, 'avatar-seedream-default', null,
       'SEED:CREDIT:AI_REFUND:alice:avatar:001', 'seed avatar task refund'
from user_account u, task t
where u.username = 'alice' and u.deleted = 0 and t.idempotency_key = 'SEED:TASK:AVATAR:alice:001' and t.deleted = 0
  and not exists (select 1 from user_credit_log l where l.idempotency_key = 'SEED:CREDIT:AI_REFUND:alice:avatar:001' and l.deleted = 0);

insert into admin_operation_log(admin_user_id, operation_type, target_type, target_id, before_json, after_json, ip, trace_id)
select admin.user_id, 'CREDIT_ADJUST', 'USER', u.user_id, null,
       '{"changeType":"ADMIN_ADD","amount":100}', '127.0.0.1', 'seed-op-credit-001'
from user_account admin, user_account u
where admin.username = 'admin' and admin.deleted = 0 and u.username = 'demo' and u.deleted = 0
  and not exists (select 1 from admin_operation_log l where l.trace_id = 'seed-op-credit-001');

insert into admin_operation_log(admin_user_id, operation_type, target_type, target_id, before_json, after_json, ip, trace_id)
select admin.user_id, 'MODEL_SAVE', 'MODEL', m.model_id, null,
       '{"modelCode":"tts-doubao-default","enabled":true}', '127.0.0.1', 'seed-op-model-001'
from user_account admin, ai_model_config m
where admin.username = 'admin' and admin.deleted = 0 and m.model_code = 'tts-doubao-default' and m.deleted = 0
  and not exists (select 1 from admin_operation_log l where l.trace_id = 'seed-op-model-001');

-- 演示资产（图片/文本/JSON）不在此插入：历史上此处未写 file_path，导致 H2 每次初始化后出现「无本地路径」的参考图；
-- 统一由 SeedUserAssetInitializer 在启动时写入绝对路径并复制 seed 文件，MySQL 存量脏数据也会在同类逻辑中修补。

-- 已存在的 MySQL 库若 user_account 表缺少运营/权限字段，请手动执行一次（仅一次、列已存在则跳过）：
-- alter table user_account add column role varchar(20) not null default 'USER' after display_name;
-- alter table user_account add column status varchar(20) not null default 'ENABLED' after role;
-- alter table user_account add column phone varchar(30) null after status;
-- alter table user_account add column email varchar(120) null after phone;
-- alter table user_account add column remark varchar(500) null after email;
-- alter table user_account add column last_login_at datetime null after remark;
-- alter table user_account add column last_login_ip varchar(60) null after last_login_at;
-- create index idx_user_account_role on user_account(role);
-- create index idx_user_account_status on user_account(status);
-- create index idx_user_account_created_at on user_account(created_at);

-- 已存在的 MySQL 库若缺少积分账户/积分流水/模型配置/outbox/管理员操作日志表，请参考上方 DDL 手动创建：
-- user_credit_account、user_credit_log、ai_model_config、task_outbox、admin_operation_log。

-- 已存在的 MySQL 库若 asset 表缺少 owner_user_id，请手动执行一次（仅一次）：
-- alter table asset add column owner_user_id bigint null comment 'null=公共可见' after asset_id;
-- create index idx_asset_owner_user_id on asset(owner_user_id);

-- 已存在的 MySQL 库若 asset 表缺少新字段（公共资产/模板库/素材中心收敛），请手动执行一次（仅一次、列已存在则跳过）：
-- alter table asset add column created_by_user_id bigint null after owner_user_id;
-- alter table asset add column kind varchar(30) not null default 'MATERIAL' after asset_type;
-- alter table asset add column visibility varchar(20) not null default 'PRIVATE' after kind;
-- alter table asset add column status varchar(20) not null default 'ACTIVE' after visibility;
-- alter table asset add column published_at datetime null after status;
-- create index idx_asset_created_by_user_id on asset(created_by_user_id);
-- create index idx_asset_visibility on asset(visibility);
-- create index idx_asset_kind on asset(kind);
-- create index idx_asset_status on asset(status);

-- 已存在的 MySQL 库若缺少模板库表，请执行一次：
-- create table template (...); create table template_asset_rel (...);

-- 已存在的 MySQL 库若 task 表缺少任务中心字段，请手动执行一次（仅一次、列已存在则跳过）：
-- alter table task add column owner_user_id bigint null after project_id;
-- create index idx_task_owner_user_id on task(owner_user_id);
-- alter table task add column progress int not null default 0 after status;
-- alter table task add column result_asset_id bigint null after output_json;
-- alter table task add column result_viewed tinyint(1) not null default 0 after trace_id;
-- alter table task add column started_at datetime null after result_viewed;
-- alter table task add column finished_at datetime null after started_at;
-- alter table task add column model_code varchar(80) null after task_type;
-- alter table task add column credit_cost bigint not null default 0 after model_code;
-- alter table task add column credit_log_id bigint null after credit_cost;
-- alter table task add column queue_name varchar(80) null after credit_log_id;
-- alter table task add column message_id varchar(120) null after queue_name;
-- alter table task add column idempotency_key varchar(120) null after message_id;
-- alter table task add column priority int not null default 0 after idempotency_key;
-- create index idx_task_model_code on task(model_code);
-- create index idx_task_owner_status on task(owner_user_id, status);
-- create index idx_task_created_at on task(created_at);
-- create unique index uk_task_idempotency_key on task(idempotency_key);

-- 已存在的 MySQL 库若 script_version 缺少归属字段（projectless 可见性），请手动执行一次：
-- alter table script_version add column owner_user_id bigint null comment 'null=公共/演示脚本' after project_id;
-- create index idx_script_version_owner_user_id on script_version(owner_user_id);

-- 已存在的 MySQL 库若 uploaded_file 缺少归属字段（上传列表按用户收敛），请手动执行一次：
-- alter table uploaded_file add column owner_user_id bigint null comment 'null=历史/公共' after project_id;
-- create index idx_uploaded_file_owner_user_id on uploaded_file(owner_user_id);
