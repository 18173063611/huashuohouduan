create table if not exists project (
    project_id bigint primary key auto_increment,
    project_name varchar(80) not null,
    description varchar(500),
    status varchar(30) not null default 'DRAFT',
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp,
    deleted tinyint(1) not null default 0
);

create index idx_project_status on project(status);
create index idx_project_deleted on project(deleted);

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
    deleted tinyint(1) not null default 0
);

create index idx_uploaded_file_project_id on uploaded_file(project_id);
create index idx_uploaded_file_deleted on uploaded_file(deleted);

insert into project(project_name, description, status)
values ('AI 数字人口播 MVP 演示项目', '用于演示项目管理基础接口和文件上传能力', 'DRAFT');