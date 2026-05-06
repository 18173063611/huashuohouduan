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
    project_id bigint not null,
    task_type varchar(50) not null,
    status varchar(30) not null default 'QUEUED',
    input_json text,
    output_json text,
    retry_count int not null default 0,
    error_message text,
    trace_id varchar(100),
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp,
    deleted tinyint(1) not null default 0,
    key idx_task_project_id (project_id),
    key idx_task_status (status),
    key idx_task_task_type (task_type),
    key idx_task_deleted (deleted)
);

create table if not exists asset (
    asset_id bigint primary key auto_increment,
    project_id bigint not null,
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
    key idx_asset_project_id (project_id),
    key idx_asset_task_id (task_id),
    key idx_asset_asset_type (asset_type),
    key idx_asset_deleted (deleted)
);

create table if not exists script_version (
    script_version_id bigint primary key auto_increment,
    project_id bigint not null,
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
    project_id bigint not null,
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
    project_id bigint not null,
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
    key idx_uploaded_file_deleted (deleted)
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
