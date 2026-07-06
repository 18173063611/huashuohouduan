-- 宠物创作中心正式后端接入补丁：权限点 + 宠物作品/任务映射。

create table if not exists user_feature_permission (
    permission_id bigint primary key auto_increment comment '用户功能权限主键ID',
    user_id bigint not null comment '授权用户ID，关联 user_account.user_id',
    permission_code varchar(80) not null comment '权限点编码，如 PET_CREATION_ACCESS',
    enabled tinyint(1) not null default 1 comment '是否启用：1=启用，0=禁用',
    remark varchar(500) comment '授权备注',
    created_at datetime not null default current_timestamp comment '创建时间',
    updated_at datetime not null default current_timestamp comment '更新时间',
    deleted tinyint(1) not null default 0 comment '软删除标记：0=未删除，1=已删除',
    unique key uk_user_feature_permission_user_code (user_id, permission_code),
    key idx_user_feature_permission_code (permission_code),
    key idx_user_feature_permission_enabled (enabled),
    key idx_user_feature_permission_deleted (deleted)
);

create table if not exists pet_video_work (
    work_id bigint primary key auto_increment comment '宠物创作作品/草稿主键ID',
    owner_user_id bigint not null comment '作品归属用户ID',
    task_id bigint comment '关联真实视频生成 task.task_id；草稿复制时为空',
    source_work_id bigint comment '复制/二创来源作品ID',
    title varchar(160) not null comment '作品标题',
    status varchar(30) not null default 'DRAFT' comment '宠物作品状态：DRAFT/RUNNING/COMPLETED/FAILED',
    pet_type varchar(30) not null default 'other' comment '主宠类型：cat/dog/other',
    aspect_ratio varchar(20) not null default '9:16' comment '视频比例：9:16/16:9/1:1',
    duration_seconds int not null default 15 comment '目标时长秒',
    draft_json longtext not null comment '宠物创作 draft JSON',
    video_url varchar(1000) comment '生成完成后的视频 URL 快照',
    cover_url varchar(1000) comment '生成完成后的封面 URL 快照',
    created_at datetime not null default current_timestamp comment '创建时间',
    updated_at datetime not null default current_timestamp comment '更新时间',
    deleted tinyint(1) not null default 0 comment '软删除标记：0=未删除，1=已删除',
    key idx_pet_video_work_owner_created (owner_user_id, created_at),
    key idx_pet_video_work_task_id (task_id),
    key idx_pet_video_work_status (status),
    key idx_pet_video_work_pet_type (pet_type),
    key idx_pet_video_work_deleted (deleted)
);

-- 授权示例：把 123 改为真实 user_id 后执行。
-- insert into user_feature_permission(user_id, permission_code, enabled, remark)
-- values (123, 'PET_CREATION_ACCESS', 1, '宠物创作中心灰度授权')
-- on duplicate key update enabled = values(enabled), remark = values(remark), updated_at = now(), deleted = 0;
