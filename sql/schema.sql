-- H2（MySQL 模式）与 MySQL 8 共用。索引写在建表语句中，避免各版本对 DROP/CREATE INDEX IF EXISTS 支持不一致。
-- 手动在 MySQL 中建库: CREATE DATABASE IF NOT EXISTS huashuo DEFAULT CHARACTER SET utf8mb4;
-- 然后 USE huashuo; 再执行本文件（或通过 Spring dev 配置自动初始化）。

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
    input_json json,
    output_json json,
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
    metadata_json json,
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
    version_no int not null,
    content text not null,
    source_type varchar(50) not null default 'DEMO',
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp,
    deleted tinyint(1) not null default 0,
    key idx_script_version_project_id (project_id),
    key idx_script_version_deleted (deleted)
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

insert into project(project_name, description, status)
select 'AI 数字人口播 MVP 演示项目', '用于演示项目管理、任务中心和资产中心基础能力', 'DRAFT'
where not exists (
    select 1 from project where project_name = 'AI 数字人口播 MVP 演示项目' and deleted = false
);

insert into task(project_id, task_type, status, input_json, output_json, retry_count, trace_id)
select p.project_id, 'SCRIPT_REWRITE', 'SUCCESS',
       '{"source":"demo script","goal":"生成可联调用例"}',
       '{"summary":"已生成演示文案版本"}',
       0,
       'demo-trace-001'
from project p
where p.project_name = 'AI 数字人口播 MVP 演示项目'
  and not exists (select 1 from task t where t.project_id = p.project_id and t.task_type = 'SCRIPT_REWRITE');

insert into asset(project_id, task_id, asset_type, file_name, file_url, thumbnail_url, mime_type, file_size, source_type, metadata_json)
select p.project_id, t.task_id, 'TEXT', 'demo-script.txt', '/uploads/demo-script.txt', null, 'text/plain', 128, 'DEMO',
       '{"description":"资产中心演示文案"}'
from project p
left join task t on t.project_id = p.project_id and t.task_type = 'SCRIPT_REWRITE'
where p.project_name = 'AI 数字人口播 MVP 演示项目'
  and not exists (select 1 from asset a where a.project_id = p.project_id and a.file_name = 'demo-script.txt');

insert into script_version(project_id, version_no, content, source_type)
select p.project_id, 1, '大家好，今天演示 AI 数字人视频制作的基础工作台。', 'DEMO'
from project p
where p.project_name = 'AI 数字人口播 MVP 演示项目'
  and not exists (select 1 from script_version s where s.project_id = p.project_id and s.version_no = 1);
