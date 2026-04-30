create table if not exists project (
    project_id bigint primary key auto_increment,
    project_name varchar(80) not null,
    description varchar(500),
    status varchar(30) not null default 'DRAFT',
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp,
    deleted tinyint not null default 0
);

create index if not exists idx_project_status on project(status);
create index if not exists idx_project_deleted on project(deleted);

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
    deleted tinyint not null default 0
);

create index if not exists idx_task_project_id on task(project_id);
create index if not exists idx_task_status on task(status);
create index if not exists idx_task_task_type on task(task_type);
create index if not exists idx_task_deleted on task(deleted);

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
    deleted tinyint not null default 0
);

create index if not exists idx_asset_project_id on asset(project_id);
create index if not exists idx_asset_task_id on asset(task_id);
create index if not exists idx_asset_asset_type on asset(asset_type);
create index if not exists idx_asset_deleted on asset(deleted);

create table if not exists script_version (
    script_version_id bigint primary key auto_increment,
    project_id bigint not null,
    version_no int not null,
    content text not null,
    source_type varchar(50) not null default 'DEMO',
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp,
    deleted tinyint not null default 0
);

create index if not exists idx_script_version_project_id on script_version(project_id);
create index if not exists idx_script_version_deleted on script_version(deleted);

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
    deleted tinyint not null default 0
);

create index if not exists idx_uploaded_file_project_id on uploaded_file(project_id);
create index if not exists idx_uploaded_file_deleted on uploaded_file(deleted);
