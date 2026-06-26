-- 存量库安全补丁：对齐当前最终 schema.sql 的关键表结构。
--
-- 适用场景：
-- 1. 服务器已有 huashuo_ai_video 数据库和历史数据；
-- 2. 不能导入完整 sql/schema.sql，避免重复种子数据或误操作；
-- 3. 只需要补齐新版本所需的表、字段和普通查询索引。
--
-- 安全原则：
-- - 不 DROP / TRUNCATE / DELETE 任何业务表数据；
-- - CREATE TABLE IF NOT EXISTS 只补缺表；
-- - ADD COLUMN / CREATE INDEX 均先查 information_schema，已存在则跳过；
-- - 唯一约束不在本补丁里强制新增，避免历史脏数据导致发布中断。
--
-- 执行前必须先备份：
-- mysqldump -uroot -p huashuo_ai_video > huashuo_ai_video_backup_$(date +%Y%m%d_%H%M%S).sql

delimiter $$

drop procedure if exists hs_add_column_if_missing $$
create procedure hs_add_column_if_missing(
    in p_table_name varchar(128),
    in p_column_name varchar(128),
    in p_column_ddl text
)
begin
    if exists (
        select 1
        from information_schema.tables
        where table_schema = database()
          and table_name = p_table_name
    ) and not exists (
        select 1
        from information_schema.columns
        where table_schema = database()
          and table_name = p_table_name
          and column_name = p_column_name
    ) then
        set @hs_sql = concat('alter table `', p_table_name, '` add column ', p_column_ddl);
        prepare hs_stmt from @hs_sql;
        execute hs_stmt;
        deallocate prepare hs_stmt;
    end if;
end $$

drop procedure if exists hs_add_index_if_missing $$
create procedure hs_add_index_if_missing(
    in p_table_name varchar(128),
    in p_index_name varchar(128),
    in p_index_ddl text
)
begin
    if exists (
        select 1
        from information_schema.tables
        where table_schema = database()
          and table_name = p_table_name
    ) and not exists (
        select 1
        from information_schema.statistics
        where table_schema = database()
          and table_name = p_table_name
          and index_name = p_index_name
    ) then
        set @hs_sql = p_index_ddl;
        prepare hs_stmt from @hs_sql;
        execute hs_stmt;
        deallocate prepare hs_stmt;
    end if;
end $$

delimiter ;

-- 1. 新版本运维/反馈表。
create table if not exists provider_ops_ticket (
    ticket_id bigint primary key auto_increment,
    task_id bigint not null,
    owner_user_id bigint,
    task_type varchar(50),
    provider varchar(50),
    provider_task_id varchar(120),
    provider_status varchar(40),
    status varchar(40) not null default 'OPEN',
    priority varchar(20) not null default 'NORMAL',
    assignee_admin_id bigint,
    supplier_ticket_id varchar(120),
    can_delete_provider_task tinyint(1) not null default 0,
    next_action varchar(80),
    alert_level varchar(30),
    alert_reason varchar(80),
    alert_elapsed_seconds bigint,
    alert_timeout_seconds bigint,
    sla_deadline_at datetime,
    supplier_response text,
    attachment_json text,
    retry_approval_status varchar(30) not null default 'NONE',
    retry_requested_by_admin_id bigint,
    retry_requested_at datetime,
    retry_approved_by_admin_id bigint,
    retry_approved_at datetime,
    retry_approval_remark varchar(1000),
    last_remark varchar(1000),
    closed_at datetime,
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp,
    deleted tinyint(1) not null default 0,
    key idx_provider_ops_ticket_task_id (task_id),
    key idx_provider_ops_ticket_provider_task_id (provider_task_id),
    key idx_provider_ops_ticket_status (status),
    key idx_provider_ops_ticket_assignee (assignee_admin_id),
    key idx_provider_ops_ticket_sla (sla_deadline_at),
    key idx_provider_ops_ticket_deleted_updated (deleted, updated_at)
);

create table if not exists provider_ops_ticket_action (
    action_id bigint primary key auto_increment,
    ticket_id bigint not null,
    task_id bigint not null,
    action_type varchar(50) not null,
    from_status varchar(40),
    to_status varchar(40),
    operator_admin_id bigint,
    supplier_ticket_id varchar(120),
    remark varchar(1000),
    supplier_response text,
    attachment_json text,
    retry_approval_status varchar(30),
    created_at datetime not null default current_timestamp,
    deleted tinyint(1) not null default 0,
    key idx_provider_ops_action_ticket_id (ticket_id),
    key idx_provider_ops_action_task_id (task_id),
    key idx_provider_ops_action_created_at (created_at)
);

create table if not exists customer_feedback (
    feedback_id bigint primary key auto_increment,
    owner_user_id bigint not null,
    category varchar(40) not null default 'OTHER',
    priority varchar(20) not null default 'NORMAL',
    status varchar(30) not null default 'OPEN',
    title varchar(120) not null,
    content text not null,
    contact varchar(120),
    related_task_id bigint,
    project_id bigint,
    page_url varchar(1000),
    source_path varchar(255),
    user_agent varchar(500),
    attachment_file_ids varchar(1000),
    admin_reply text,
    admin_note text,
    assignee_admin_id bigint,
    first_response_at datetime,
    resolved_at datetime,
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp,
    deleted tinyint(1) not null default 0
);

call hs_add_index_if_missing('customer_feedback', 'idx_customer_feedback_owner_user_id', 'create index idx_customer_feedback_owner_user_id on customer_feedback(owner_user_id)');
call hs_add_index_if_missing('customer_feedback', 'idx_customer_feedback_status', 'create index idx_customer_feedback_status on customer_feedback(status)');
call hs_add_index_if_missing('customer_feedback', 'idx_customer_feedback_category', 'create index idx_customer_feedback_category on customer_feedback(category)');
call hs_add_index_if_missing('customer_feedback', 'idx_customer_feedback_priority', 'create index idx_customer_feedback_priority on customer_feedback(priority)');
call hs_add_index_if_missing('customer_feedback', 'idx_customer_feedback_related_task_id', 'create index idx_customer_feedback_related_task_id on customer_feedback(related_task_id)');
call hs_add_index_if_missing('customer_feedback', 'idx_customer_feedback_created_at', 'create index idx_customer_feedback_created_at on customer_feedback(created_at)');
call hs_add_index_if_missing('customer_feedback', 'idx_customer_feedback_deleted', 'create index idx_customer_feedback_deleted on customer_feedback(deleted)');

-- 2. 任务表补齐：任务中心、幂等、队列、计费、结算字段。
call hs_add_column_if_missing('task', 'owner_user_id', '`owner_user_id` bigint comment ''任务发起人/归属用户ID''');
call hs_add_column_if_missing('task', 'model_code', '`model_code` varchar(80) comment ''使用的AI模型编码''');
call hs_add_column_if_missing('task', 'provider', '`provider` varchar(50) comment ''AI服务提供方''');
call hs_add_column_if_missing('task', 'usage_unit', '`usage_unit` varchar(30) comment ''用量单位''');
call hs_add_column_if_missing('task', 'estimated_usage', '`estimated_usage` decimal(18,4) comment ''创建任务时预估用量''');
call hs_add_column_if_missing('task', 'actual_usage', '`actual_usage` decimal(18,4) comment ''任务完成后实际用量''');
call hs_add_column_if_missing('task', 'estimated_credit_cost', '`estimated_credit_cost` bigint not null default 0 comment ''创建任务时预估积分成本''');
call hs_add_column_if_missing('task', 'actual_credit_cost', '`actual_credit_cost` bigint not null default 0 comment ''任务完成后实际积分成本''');
call hs_add_column_if_missing('task', 'settlement_status', '`settlement_status` varchar(30) not null default ''NONE'' comment ''结算状态''');
call hs_add_column_if_missing('task', 'credit_cost', '`credit_cost` bigint not null default 0 comment ''本次任务消耗的积分数''');
call hs_add_column_if_missing('task', 'credit_log_id', '`credit_log_id` bigint comment ''关联积分流水ID''');
call hs_add_column_if_missing('task', 'queue_name', '`queue_name` varchar(80) comment ''RabbitMQ队列名称''');
call hs_add_column_if_missing('task', 'message_id', '`message_id` varchar(120) comment ''MQ消息ID''');
call hs_add_column_if_missing('task', 'idempotency_key', '`idempotency_key` varchar(120) comment ''业务幂等键''');
call hs_add_column_if_missing('task', 'priority', '`priority` int not null default 0 comment ''任务优先级''');
call hs_add_column_if_missing('task', 'progress', '`progress` int not null default 0 comment ''任务进度0-100''');
call hs_add_column_if_missing('task', 'result_asset_id', '`result_asset_id` bigint comment ''主产物资产ID''');
call hs_add_column_if_missing('task', 'result_viewed', '`result_viewed` tinyint(1) not null default 0 comment ''用户是否已查看结果''');
call hs_add_column_if_missing('task', 'started_at', '`started_at` datetime comment ''任务开始执行时间''');
call hs_add_column_if_missing('task', 'finished_at', '`finished_at` datetime comment ''任务结束时间''');

call hs_add_index_if_missing('task', 'idx_task_owner_user_id', 'create index idx_task_owner_user_id on task(owner_user_id)');
call hs_add_index_if_missing('task', 'idx_task_model_code', 'create index idx_task_model_code on task(model_code)');
call hs_add_index_if_missing('task', 'idx_task_provider', 'create index idx_task_provider on task(provider)');
call hs_add_index_if_missing('task', 'idx_task_settlement_status', 'create index idx_task_settlement_status on task(settlement_status)');
call hs_add_index_if_missing('task', 'idx_task_owner_status', 'create index idx_task_owner_status on task(owner_user_id, status)');
call hs_add_index_if_missing('task', 'idx_task_owner_deleted_created', 'create index idx_task_owner_deleted_created on task(owner_user_id, deleted, created_at)');
call hs_add_index_if_missing('task', 'idx_task_created_at', 'create index idx_task_created_at on task(created_at)');

-- 3. 资产、脚本、上传文件归属字段。
call hs_add_column_if_missing('asset', 'owner_user_id', '`owner_user_id` bigint comment ''资产归属用户ID，null=公共资产''');
call hs_add_column_if_missing('asset', 'created_by_user_id', '`created_by_user_id` bigint comment ''资产创建者用户ID''');
call hs_add_column_if_missing('asset', 'kind', '`kind` varchar(30) not null default ''MATERIAL'' comment ''资产分类''');
call hs_add_column_if_missing('asset', 'visibility', '`visibility` varchar(20) not null default ''PRIVATE'' comment ''可见性''');
call hs_add_column_if_missing('asset', 'status', '`status` varchar(20) not null default ''ACTIVE'' comment ''资产状态''');
call hs_add_column_if_missing('asset', 'published_at', '`published_at` datetime comment ''发布为公共资产的时间''');
call hs_add_column_if_missing('asset', 'asset_group', '`asset_group` varchar(60) comment ''资产分组''');

call hs_add_index_if_missing('asset', 'idx_asset_owner_user_id', 'create index idx_asset_owner_user_id on asset(owner_user_id)');
call hs_add_index_if_missing('asset', 'idx_asset_created_by_user_id', 'create index idx_asset_created_by_user_id on asset(created_by_user_id)');
call hs_add_index_if_missing('asset', 'idx_asset_visibility', 'create index idx_asset_visibility on asset(visibility)');
call hs_add_index_if_missing('asset', 'idx_asset_kind', 'create index idx_asset_kind on asset(kind)');
call hs_add_index_if_missing('asset', 'idx_asset_status', 'create index idx_asset_status on asset(status)');
call hs_add_index_if_missing('asset', 'idx_asset_group', 'create index idx_asset_group on asset(asset_group)');

call hs_add_column_if_missing('script_version', 'owner_user_id', '`owner_user_id` bigint comment ''归属用户ID，null=公共/演示脚本''');
call hs_add_index_if_missing('script_version', 'idx_script_version_owner_user_id', 'create index idx_script_version_owner_user_id on script_version(owner_user_id)');

call hs_add_column_if_missing('uploaded_file', 'owner_user_id', '`owner_user_id` bigint comment ''上传者用户ID，null=历史/公共''');
call hs_add_index_if_missing('uploaded_file', 'idx_uploaded_file_owner_user_id', 'create index idx_uploaded_file_owner_user_id on uploaded_file(owner_user_id)');

-- 4. 用户后台字段。
call hs_add_column_if_missing('user_account', 'role', '`role` varchar(20) not null default ''USER'' comment ''角色''');
call hs_add_column_if_missing('user_account', 'status', '`status` varchar(20) not null default ''ENABLED'' comment ''账号状态''');
call hs_add_column_if_missing('user_account', 'phone', '`phone` varchar(30) comment ''手机号''');
call hs_add_column_if_missing('user_account', 'email', '`email` varchar(120) comment ''邮箱''');
call hs_add_column_if_missing('user_account', 'remark', '`remark` varchar(500) comment ''运营备注''');
call hs_add_column_if_missing('user_account', 'last_login_at', '`last_login_at` datetime comment ''最近一次登录时间''');
call hs_add_column_if_missing('user_account', 'last_login_ip', '`last_login_ip` varchar(60) comment ''最近一次登录IP''');

call hs_add_index_if_missing('user_account', 'idx_user_account_role', 'create index idx_user_account_role on user_account(role)');
call hs_add_index_if_missing('user_account', 'idx_user_account_status', 'create index idx_user_account_status on user_account(status)');
call hs_add_index_if_missing('user_account', 'idx_user_account_created_at', 'create index idx_user_account_created_at on user_account(created_at)');

-- 5. 计费、用量和任务事件表。
create table if not exists ai_model_price (
    price_id bigint primary key auto_increment,
    provider varchar(50) not null,
    model_code varchar(100) not null,
    model_name varchar(100),
    task_type varchar(50),
    usage_unit varchar(30) not null,
    input_credit_per_1k decimal(18,6) not null default 0,
    output_credit_per_1k decimal(18,6) not null default 0,
    unit_credit_price decimal(18,6) not null default 0,
    estimate_output_ratio decimal(10,4) not null default 1.0000,
    estimate_buffer_ratio decimal(10,4) not null default 1.2000,
    enabled tinyint(1) not null default 1,
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp,
    deleted tinyint(1) not null default 0
);

call hs_add_index_if_missing('ai_model_price', 'idx_ai_model_price_task_type', 'create index idx_ai_model_price_task_type on ai_model_price(task_type)');
call hs_add_index_if_missing('ai_model_price', 'idx_ai_model_price_model_code', 'create index idx_ai_model_price_model_code on ai_model_price(model_code)');
call hs_add_index_if_missing('ai_model_price', 'idx_ai_model_price_enabled', 'create index idx_ai_model_price_enabled on ai_model_price(enabled)');
call hs_add_index_if_missing('ai_model_price', 'idx_ai_model_price_deleted', 'create index idx_ai_model_price_deleted on ai_model_price(deleted)');

create table if not exists ai_usage_log (
    usage_id bigint primary key auto_increment,
    task_id bigint not null,
    user_id bigint,
    task_type varchar(50) not null,
    provider varchar(50),
    model_code varchar(100),
    usage_unit varchar(30) not null,
    usage_phase varchar(20) not null default 'ACTUAL',
    prompt_tokens int not null default 0,
    completion_tokens int not null default 0,
    total_tokens int not null default 0,
    character_count int not null default 0,
    image_count int not null default 0,
    duration_seconds decimal(10,2) not null default 0,
    provider_credits decimal(18,4) not null default 0,
    estimated_credit_cost bigint not null default 0,
    actual_credit_cost bigint not null default 0,
    raw_usage_json text,
    created_at datetime not null default current_timestamp,
    deleted tinyint(1) not null default 0
);

call hs_add_column_if_missing('ai_usage_log', 'usage_phase', '`usage_phase` varchar(20) not null default ''ACTUAL''');
call hs_add_index_if_missing('ai_usage_log', 'idx_ai_usage_log_task_id', 'create index idx_ai_usage_log_task_id on ai_usage_log(task_id)');
call hs_add_index_if_missing('ai_usage_log', 'idx_ai_usage_log_user_id', 'create index idx_ai_usage_log_user_id on ai_usage_log(user_id)');
call hs_add_index_if_missing('ai_usage_log', 'idx_ai_usage_log_model_code', 'create index idx_ai_usage_log_model_code on ai_usage_log(model_code)');
call hs_add_index_if_missing('ai_usage_log', 'idx_ai_usage_log_usage_phase', 'create index idx_ai_usage_log_usage_phase on ai_usage_log(usage_phase)');
call hs_add_index_if_missing('ai_usage_log', 'idx_ai_usage_log_created_at', 'create index idx_ai_usage_log_created_at on ai_usage_log(created_at)');
call hs_add_index_if_missing('ai_usage_log', 'idx_ai_usage_log_deleted', 'create index idx_ai_usage_log_deleted on ai_usage_log(deleted)');

create table if not exists ai_billing_step_config (
    step_id bigint primary key auto_increment,
    task_type varchar(50) not null,
    function_module varchar(80) not null default '',
    step_name varchar(120) not null,
    provider varchar(50),
    model_code varchar(120),
    usage_unit varchar(30) not null default 'TASK',
    call_count varchar(60),
    cost_text varchar(200),
    credit_cost bigint not null default 0,
    enabled tinyint(1) not null default 1,
    sort_order int not null default 0,
    remark varchar(500),
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp,
    deleted tinyint(1) not null default 0,
    key idx_ai_billing_step_config_task_type (task_type),
    key idx_ai_billing_step_config_enabled (enabled),
    key idx_ai_billing_step_config_deleted (deleted)
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
    key idx_user_credit_log_created_at (created_at)
);

create table if not exists credit_debt_log (
    debt_id bigint primary key auto_increment,
    user_id bigint not null,
    task_id bigint,
    debt_type varchar(30) not null default 'SETTLEMENT_EXTRA',
    debt_credits bigint not null default 0,
    paid_credits bigint not null default 0,
    status varchar(20) not null default 'UNPAID',
    reason varchar(500),
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp,
    deleted tinyint(1) not null default 0,
    key idx_credit_debt_log_user_id (user_id),
    key idx_credit_debt_log_task_id (task_id),
    key idx_credit_debt_log_status (status),
    key idx_credit_debt_log_created_at (created_at),
    key idx_credit_debt_log_deleted (deleted)
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

-- 6. 模板与会话表（若线上库较老则补齐）。
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

create table if not exists user_session (
    session_id bigint primary key auto_increment,
    user_id bigint not null,
    token varchar(120) not null,
    expires_at datetime not null,
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp,
    deleted tinyint(1) not null default 0,
    key idx_user_session_user_id (user_id),
    key idx_user_session_expires_at (expires_at),
    key idx_user_session_deleted (deleted)
);

-- 7. 最后清理本补丁临时过程。
drop procedure if exists hs_add_column_if_missing;
drop procedure if exists hs_add_index_if_missing;
