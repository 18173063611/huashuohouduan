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
    key idx_task_deleted (deleted)
);

create table if not exists asset (
    asset_id bigint primary key auto_increment,
    owner_user_id bigint,
    project_id bigint,
    task_id bigint,
    asset_type varchar(50) not null,
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
    key idx_asset_project_id (project_id),
    key idx_asset_task_id (task_id),
    key idx_asset_asset_type (asset_type),
    key idx_asset_deleted (deleted)
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
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp,
    deleted tinyint(1) not null default 0,
    unique key uk_user_account_username (username),
    key idx_user_account_deleted (deleted)
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

-- ---- 用户与资产 seed（幂等，用于资产中心联调演示）----
-- 说明：owner_user_id 为 null 表示公共/演示资产，未登录用户仅可列出与查看此类资产；非空则仅该用户与公共资产一并可见。
-- 账号密码统一为 demo1234（BCrypt 同上），便于测试。

insert into user_account(username, password_hash, display_name)
select 'alice', '$2a$10$FpjChVSxXshPiX0E62qP5eWjtXi7AfhCgQLJyF9zkwAhvG1zakTIG', 'Alice 运营'
where not exists (select 1 from user_account u where u.username = 'alice' and u.deleted = 0);

insert into user_account(username, password_hash, display_name)
select 'bob', '$2a$10$FpjChVSxXshPiX0E62qP5eWjtXi7AfhCgQLJyF9zkwAhvG1zakTIG', 'Bob 设计'
where not exists (select 1 from user_account u where u.username = 'bob' and u.deleted = 0);

-- 演示资产（图片/文本/JSON）不在此插入：历史上此处未写 file_path，导致 H2 每次初始化后出现「无本地路径」的参考图；
-- 统一由 SeedUserAssetInitializer 在启动时写入绝对路径并复制 seed 文件，MySQL 存量脏数据也会在同类逻辑中修补。

-- 已存在的 MySQL 库若 asset 表缺少 owner_user_id，请手动执行一次（仅一次）：
-- alter table asset add column owner_user_id bigint null comment 'null=公共可见' after asset_id;
-- create index idx_asset_owner_user_id on asset(owner_user_id);

-- 已存在的 MySQL 库若 task 表缺少任务中心字段，请手动执行一次（仅一次、列已存在则跳过）：
-- alter table task add column owner_user_id bigint null after project_id;
-- create index idx_task_owner_user_id on task(owner_user_id);
-- alter table task add column progress int not null default 0 after status;
-- alter table task add column result_asset_id bigint null after output_json;
-- alter table task add column result_viewed tinyint(1) not null default 0 after trace_id;
-- alter table task add column started_at datetime null after result_viewed;
-- alter table task add column finished_at datetime null after started_at;

-- 已存在的 MySQL 库若 script_version 缺少归属字段（projectless 可见性），请手动执行一次：
-- alter table script_version add column owner_user_id bigint null comment 'null=公共/演示脚本' after project_id;
-- create index idx_script_version_owner_user_id on script_version(owner_user_id);

-- 已存在的 MySQL 库若 uploaded_file 缺少归属字段（上传列表按用户收敛），请手动执行一次：
-- alter table uploaded_file add column owner_user_id bigint null comment 'null=历史/公共' after project_id;
-- create index idx_uploaded_file_owner_user_id on uploaded_file(owner_user_id);
