-- H2（MySQL 模式）与 MySQL 8 共用。索引写在建表语句中，避免各版本对 DROP/CREATE INDEX IF EXISTS 支持不一致。
-- 手动在 MySQL 中建库: CREATE DATABASE IF NOT EXISTS huashuo DEFAULT CHARACTER SET utf8mb4;
-- 然后 USE huashuo; 再执行本文件。
-- 本仓库唯一维护的库表+种子脚本；Maven 构建时复制到 classpath:schema.sql，由 spring.sql.init 执行（见 pom.xml、application*.yml）。

create table if not exists activate_code(
  id int primary key auto_increment,
  `key` varchar(30),
  `status` int not null default 1 comment '1-未使用，0-已使用',
  key idx_activate_code_key_status (`key`, `status`)
);

create table if not exists task (
    task_id bigint primary key auto_increment comment '任务主键ID',
    project_id bigint comment '所属项目ID（可空，独立任务可无项目归属）',
    owner_user_id bigint comment '任务发起人/归属用户ID（null=系统或历史任务）',
    task_type varchar(50) not null comment '任务类型：VIDEO_PARSE/SCRIPT_REWRITE/STORYBOARD_GENERATE/TTS_GENERATE/AVATAR_GENERATE/SEEDANCE_TEXT_VIDEO/DIGITAL_HUMAN_GENERATE/VOICE_SAMPLE 等',
    model_code varchar(80) comment '使用的AI模型编码，关联 ai_model_config.model_code',
    credit_cost bigint not null default 0 comment '本次任务消耗的积分数',
    credit_log_id bigint comment '关联的积分流水ID（user_credit_log.credit_log_id）',
    queue_name varchar(80) comment 'RabbitMQ 队列名称（如 task.tts、task.avatar）',
    message_id varchar(120) comment 'MQ 消息ID，用于消费端幂等校验',
    idempotency_key varchar(120) comment '业务幂等键，防止任务重复创建',
    priority int not null default 0 comment '任务优先级，数值越大越优先（默认0）',
    status varchar(30) not null default 'QUEUED' comment '任务状态：QUEUED=排队/RUNNING=运行中/SUCCESS=成功/FAILED=失败/RETRYABLE=可重试/CANCELED=已取消',
    progress int not null default 0 comment '任务进度 0-100，用于任务中心展示',
    input_json longtext comment '任务输入参数JSON（结构按 task_type 不同）',
    output_json longtext comment '任务结果JSON（结构按 task_type 不同）',
    result_asset_id bigint comment '主产物资产ID，关联 asset.asset_id（如 TTS 音频、生成的视频等）',
    error_code varchar(50) comment '错误码（如 PROVIDER_ERROR、INSUFFICIENT_CREDITS 等）',
    retry_count int not null default 0 comment '当前重试次数',
    error_message text comment '错误详情/异常信息',
    trace_id varchar(100) comment '分布式链路追踪ID',
    result_viewed tinyint(1) not null default 0 comment '用户是否已查看结果：0=未查看，1=已查看',
    started_at datetime comment '任务开始执行时间',
    finished_at datetime comment '任务结束时间（成功或失败）',
    created_at datetime not null default current_timestamp comment '创建时间',
    updated_at datetime not null default current_timestamp comment '更新时间',
    deleted tinyint(1) not null default 0 comment '软删除标记：0=未删除，1=已删除',
    key idx_task_project_id (project_id),
    key idx_task_owner_user_id (owner_user_id),
    key idx_task_status (status),
    key idx_task_task_type (task_type),
    key idx_task_model_code (model_code),
    key idx_task_owner_status (owner_user_id, status),
    key idx_task_owner_deleted_created (owner_user_id, deleted, created_at),
    key idx_task_project_deleted_created (project_id, deleted, created_at),
    key idx_task_created_at (created_at),
    unique key uk_task_idempotency_key (idempotency_key),
    key idx_task_deleted (deleted)
);

create table if not exists asset (
    asset_id bigint primary key auto_increment comment '资产主键ID',
    owner_user_id bigint comment '资产归属用户ID（null=公共资产，决定可见性边界）',
    created_by_user_id bigint comment '资产创建者用户ID（追溯生产者，与 owner 可不同，如管理员发布公共资产）',
    project_id bigint comment '所属项目ID（可空）',
    task_id bigint comment '产出该资产的任务ID（AI生成资产指向对应 task）',
    asset_type varchar(50) not null comment '资产类型（按MIME自动判定）：TEXT/IMAGE/VIDEO/AUDIO/JSON/COVER',
    kind varchar(30) not null default 'MATERIAL' comment '资产分类：MATERIAL=用户素材 / GENERATED=AI生成产物',
    visibility varchar(20) not null default 'PRIVATE' comment '可见性：PRIVATE=仅本人可见 / PUBLIC=公共素材池',
    status varchar(20) not null default 'ACTIVE' comment '资产状态：ACTIVE=可用 / REMOVED=软下架（公共资产保留以便审计）',
    published_at datetime comment '发布为公共资产的时间（visibility=PUBLIC 时记录）',
    file_name varchar(255) not null comment '原始文件名',
    file_path varchar(1000) comment '本地/对象存储相对路径',
    file_url varchar(1000) not null comment '访问URL（前端使用）',
    thumbnail_url varchar(1000) comment '缩略图URL',
    mime_type varchar(120) comment 'MIME 类型（如 image/png、video/mp4）',
    file_size bigint not null default 0 comment '文件大小（字节）',
    source_type varchar(50) not null default 'UPLOAD' comment '来源类型：UPLOAD=用户上传 / AI_GENERATED=AI生成 / DEMO=演示数据',
    metadata_json text comment '附加元数据JSON（尺寸、时长、风格等）',
    created_at datetime not null default current_timestamp comment '创建时间',
    updated_at datetime not null default current_timestamp comment '更新时间',
    deleted tinyint(1) not null default 0 comment '软删除标记：0=未删除，1=已删除',
    asset_group varchar(60) comment '资产分组，例如汽车素材包',
    key idx_asset_owner_user_id (owner_user_id),
    key idx_asset_created_by_user_id (created_by_user_id),
    key idx_asset_project_id (project_id),
    key idx_asset_task_id (task_id),
    key idx_asset_asset_type (asset_type),
    key idx_asset_visibility (visibility),
    key idx_asset_kind (kind),
    key idx_asset_status (status),
    key idx_asset_group (asset_group),
    key idx_asset_deleted (deleted)
);

create table if not exists template (
    template_id bigint primary key auto_increment comment '模板主键ID',
    owner_user_id bigint comment '模板归属用户ID（null=公共模板）',
    created_by_user_id bigint comment '模板创建者用户ID',
    visibility varchar(20) not null default 'PRIVATE' comment '可见性：PRIVATE=私有 / PUBLIC=公共',
    status varchar(20) not null default 'ACTIVE' comment '状态：ACTIVE=启用 / REMOVED=下架',
    published_at datetime comment '发布为公共模板的时间',
    version_no int not null default 1 comment '模板版本号，从 1 开始递增',
    title varchar(120) not null comment '模板标题',
    description varchar(1000) comment '模板描述',
    cover_asset_id bigint comment '封面图资产ID，关联 asset.asset_id',
    tags varchar(500) comment '标签，逗号分隔（用于检索/筛选）',
    metadata_json text comment '模板元数据JSON（脚本结构、风格配置等）',
    created_at datetime not null default current_timestamp comment '创建时间',
    updated_at datetime not null default current_timestamp comment '更新时间',
    deleted tinyint(1) not null default 0 comment '软删除标记：0=未删除，1=已删除',
    key idx_template_owner_user_id (owner_user_id),
    key idx_template_created_by_user_id (created_by_user_id),
    key idx_template_visibility (visibility),
    key idx_template_status (status),
    key idx_template_deleted (deleted)
);

create table if not exists template_asset_rel (
    rel_id bigint primary key auto_increment comment '关联表主键ID',
    template_id bigint not null comment '模板ID，关联 template.template_id',
    asset_id bigint not null comment '资产ID，关联 asset.asset_id',
    asset_role varchar(50) not null default 'MATERIAL' comment '资产在模板中的角色：MATERIAL=素材（默认），其他角色按业务扩展',
    created_at datetime not null default current_timestamp comment '创建时间',
    updated_at datetime not null default current_timestamp comment '更新时间',
    deleted tinyint(1) not null default 0 comment '软删除标记：0=未删除，1=已删除',
    key idx_template_asset_rel_template_id (template_id),
    key idx_template_asset_rel_asset_id (asset_id),
    key idx_template_asset_rel_deleted (deleted)
);

create table if not exists script_version (
    script_version_id bigint primary key auto_increment comment '脚本版本主键ID',
    project_id bigint comment '所属项目ID',
    owner_user_id bigint comment '归属用户ID（null=公共/演示脚本）',
    parse_id bigint comment '来源解析任务ID（关联 VIDEO_PARSE 任务），可空',
    version_no int not null comment '脚本版本号，从 1 开始递增',
    source_script text comment '原始/输入脚本文本',
    content text not null comment '当前版本脚本正文（改写后的最终内容）',
    source_type varchar(50) not null default 'DEMO' comment '来源类型：DEMO=演示 / USER_INPUT=用户录入 / AI_GENERATED=AI改写 等',
    rewrite_style varchar(80) comment '改写风格/调性（如 营销、知识口播 等）',
    created_at datetime not null default current_timestamp comment '创建时间',
    updated_at datetime not null default current_timestamp comment '更新时间',
    deleted tinyint(1) not null default 0 comment '软删除标记：0=未删除，1=已删除',
    key idx_script_version_project_id (project_id),
    key idx_script_version_owner_user_id (owner_user_id),
    key idx_script_version_deleted (deleted)
);

create table if not exists voice_profile (
    voice_id bigint primary key auto_increment comment '音色主键ID',
    provider varchar(50) not null comment '音色提供方：DOUBAO（火山引擎）等',
    provider_voice_id varchar(120) not null comment '提供方的音色/发音人ID（须与火山 TTS speaker 严格一致）',
    voice_name varchar(80) not null comment '音色展示名称',
    gender varchar(20) not null comment '性别：MALE/FEMALE 或 男声/女声（与提供方约定保持一致）',
    scene varchar(80) comment '适用场景描述（如 知识口播、品牌讲解 等）',
    sample_url varchar(1000) comment '试听样本URL',
    enabled tinyint(1) not null default 1 comment '是否启用：0=禁用，1=启用',
    created_at datetime not null default current_timestamp comment '创建时间',
    updated_at datetime not null default current_timestamp comment '更新时间',
    deleted tinyint(1) not null default 0 comment '软删除标记：0=未删除，1=已删除',
    key idx_voice_profile_deleted (deleted),
    key idx_voice_profile_provider (provider)
);

create table if not exists user_voice_library (
    library_id bigint primary key auto_increment comment '用户音色收藏主键ID',
    user_id bigint not null comment '收藏者用户ID',
    voice_id bigint not null comment '音色ID，关联 voice_profile.voice_id',
    created_at datetime not null default current_timestamp comment '创建时间',
    updated_at datetime not null default current_timestamp comment '更新时间',
    deleted tinyint(1) not null default 0 comment '软删除标记：0=未删除，1=已删除',
    key idx_uvl_user_id (user_id),
    key idx_uvl_voice_id (voice_id),
    key idx_uvl_deleted (deleted)
    );

create table if not exists avatar_profile (
    avatar_id bigint primary key auto_increment comment '数字人形象主键ID',
    project_id bigint comment '所属项目ID',
    task_id bigint comment '生成该形象的任务ID（AI生成场景）',
    asset_id bigint comment '形象主图资产ID，关联 asset.asset_id',
    avatar_name varchar(80) not null comment '形象名称',
    source_type varchar(50) not null comment '来源类型：UPLOAD=用户上传 / AI_GENERATED=AI生成',
    prompt text comment 'AI生成时使用的提示词',
    reference_asset_ids varchar(500) comment '参考资产ID列表，逗号分隔（如风格参考图）',
    preview_url varchar(1000) comment '预览图URL',
    metadata_json text comment '附加元数据JSON',
    default_avatar tinyint(1) not null default 0 comment '是否为默认形象：0=否，1=是',
    created_at datetime not null default current_timestamp comment '创建时间',
    updated_at datetime not null default current_timestamp comment '更新时间',
    deleted tinyint(1) not null default 0 comment '软删除标记：0=未删除，1=已删除',
    key idx_avatar_profile_project_id (project_id),
    key idx_avatar_profile_task_id (task_id),
    key idx_avatar_profile_asset_id (asset_id),
    key idx_avatar_profile_deleted (deleted)
);

create table if not exists uploaded_file (
    file_id bigint primary key auto_increment comment '上传文件主键ID',
    project_id bigint comment '所属项目ID（可空）',
    owner_user_id bigint comment '上传者用户ID（null=历史/公共）',
    original_file_name varchar(255) not null comment '原始文件名',
    stored_file_name varchar(255) not null comment '存储文件名（重命名后落盘的文件名）',
    file_path varchar(1000) not null comment '存储路径（本地/对象存储）',
    preview_url varchar(1000) not null comment '预览/访问URL',
    mime_type varchar(120) comment 'MIME 类型',
    file_size bigint not null comment '文件大小（字节）',
    created_at datetime not null default current_timestamp comment '创建时间',
    updated_at datetime not null default current_timestamp comment '更新时间',
    deleted tinyint(1) not null default 0 comment '软删除标记：0=未删除，1=已删除',
    key idx_uploaded_file_project_id (project_id),
    key idx_uploaded_file_owner_user_id (owner_user_id),
    key idx_uploaded_file_deleted (deleted)
);

-- ---- 用户与登录（MVP：轻量 token session，不引入 Spring Security 过滤链） ----

create table if not exists user_account (
    user_id bigint primary key auto_increment comment '用户主键ID',
    username varchar(60) not null comment '登录用户名（唯一）',
    password_hash varchar(120) not null comment '密码哈希（BCrypt）',
    display_name varchar(80) comment '展示昵称',
    role varchar(20) not null default 'USER' comment '角色：USER=普通用户 / ADMIN=管理员',
    status varchar(20) not null default 'ENABLED' comment '账号状态：ENABLED=启用 / DISABLED=禁用',
    phone varchar(30) comment '手机号',
    email varchar(120) comment '邮箱',
    remark varchar(500) comment '运营备注',
    last_login_at datetime comment '最近一次登录时间',
    last_login_ip varchar(60) comment '最近一次登录IP',
    created_at datetime not null default current_timestamp comment '创建时间',
    updated_at datetime not null default current_timestamp comment '更新时间',
    deleted tinyint(1) not null default 0 comment '软删除标记：0=未删除，1=已删除',
    unique key uk_user_account_username (username),
    key idx_user_account_role (role),
    key idx_user_account_status (status),
    key idx_user_account_created_at (created_at),
    key idx_user_account_deleted (deleted)
);

create table if not exists user_credit_account (
    credit_account_id bigint primary key auto_increment comment '积分账户主键ID',
    user_id bigint not null comment '用户ID（与 user_account 1:1）',
    balance bigint not null default 0 comment '当前可用积分余额',
    frozen_balance bigint not null default 0 comment '冻结积分（任务进行中预扣）',
    total_recharged bigint not null default 0 comment '累计充值/发放积分',
    total_consumed bigint not null default 0 comment '累计消耗积分',
    created_at datetime not null default current_timestamp comment '创建时间',
    updated_at datetime not null default current_timestamp comment '更新时间',
    deleted tinyint(1) not null default 0 comment '软删除标记：0=未删除，1=已删除',
    unique key uk_user_credit_account_user_id (user_id),
    key idx_user_credit_account_deleted (deleted)
);

create table if not exists user_credit_log (
    credit_log_id bigint primary key auto_increment comment '积分流水主键ID',
    user_id bigint not null comment '用户ID',
    change_type varchar(30) not null comment '变动类型：ADMIN_ADD=管理员发放 / AI_CONSUME=任务消耗 / AI_REFUND=任务失败退款 等',
    change_amount bigint not null comment '变动数量（正数=增加，负数=扣减）',
    before_balance bigint not null comment '变动前余额',
    after_balance bigint not null comment '变动后余额',
    related_task_id bigint comment '关联任务ID（AI消耗/退款时填写）',
    model_code varchar(80) comment '关联的模型编码（用于成本归属）',
    operator_admin_id bigint comment '操作管理员ID（人工调整时填写，AI 自动消耗为空）',
    idempotency_key varchar(120) comment '幂等键，防止同一变动重复入账',
    remark varchar(500) comment '备注/原因',
    created_at datetime not null default current_timestamp comment '创建时间',
    deleted tinyint(1) not null default 0 comment '软删除标记：0=未删除，1=已删除',
    key idx_user_credit_log_user_id (user_id),
    key idx_user_credit_log_related_task_id (related_task_id),
    unique key uk_user_credit_log_idempotency_key (idempotency_key),
    key idx_user_credit_log_created_at (created_at)
);

-- credit_debt_log：settle 时实际成本 > 预估且余额不足时，差额作为欠费记录于此表，
-- 用户余额永不被扣成负数；后续充值后由对账作业按 user_id 自动补扣。
create table if not exists credit_debt_log (
    debt_id bigint primary key auto_increment comment '欠费记录主键ID',
    user_id bigint not null comment '欠费用户ID',
    task_id bigint comment '关联任务ID（settle 补扣失败时写入）',
    debt_type varchar(30) not null default 'SETTLEMENT_EXTRA' comment '欠费类型：SETTLEMENT_EXTRA=settle 补扣余额不足',
    debt_credits bigint not null default 0 comment '欠费总额（应补扣金额）',
    paid_credits bigint not null default 0 comment '已补扣金额（充值后逐步抵扣）',
    status varchar(20) not null default 'UNPAID' comment '欠费状态：UNPAID / PARTIAL_PAID / PAID / CANCELLED',
    reason varchar(500) comment '欠费产生原因/上下文摘要',
    created_at datetime not null default current_timestamp comment '创建时间',
    updated_at datetime not null default current_timestamp comment '更新时间',
    deleted tinyint(1) not null default 0 comment '软删除标记：0=未删除，1=已删除',
    key idx_credit_debt_log_user_id (user_id),
    key idx_credit_debt_log_task_id (task_id),
    key idx_credit_debt_log_status (status),
    key idx_credit_debt_log_created_at (created_at),
    key idx_credit_debt_log_deleted (deleted)
);

create table if not exists ai_model_config (
    model_id bigint primary key auto_increment comment '模型配置主键ID',
    model_code varchar(80) not null comment '模型业务编码（唯一，用于 task.model_code 引用）',
    model_name varchar(120) not null comment '模型展示名称',
    model_type varchar(30) not null comment '模型类型：TTS=语音合成 / IMAGE=图像 / VIDEO=视频',
    provider varchar(50) not null comment '提供方：VOLCENGINE=火山引擎 / VIDU 等',
    provider_model varchar(120) comment '提供方对应的模型标识/版本',
    credit_cost bigint not null default 0 comment '单次调用消耗积分',
    enabled tinyint(1) not null default 1 comment '是否启用：0=禁用，1=启用',
    default_model tinyint(1) not null default 0 comment '是否为该 model_type 的默认模型：0=否，1=是',
    capability_json text comment '能力描述JSON（如 {"taskTypes":["TTS_GENERATE"]}）',
    default_params_json text comment '默认参数JSON',
    rate_limit_per_minute int comment '每分钟调用速率上限',
    concurrency_limit int comment '并发上限',
    created_at datetime not null default current_timestamp comment '创建时间',
    updated_at datetime not null default current_timestamp comment '更新时间',
    deleted tinyint(1) not null default 0 comment '软删除标记：0=未删除，1=已删除',
    unique key uk_ai_model_config_model_code (model_code),
    key idx_ai_model_config_model_type (model_type),
    key idx_ai_model_config_enabled (enabled),
    key idx_ai_model_config_deleted (deleted)
);

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
    deleted tinyint(1) not null default 0,
    unique key uk_ai_model_price_model_task (provider, model_code, task_type),
    key idx_ai_model_price_task_type (task_type),
    key idx_ai_model_price_model_code (model_code),
    key idx_ai_model_price_enabled (enabled),
    key idx_ai_model_price_deleted (deleted)
);

create table if not exists ai_usage_log (
    usage_id bigint primary key auto_increment,
    task_id bigint not null,
    user_id bigint,
    task_type varchar(50) not null,
    provider varchar(50),
    model_code varchar(100),
    usage_unit varchar(30) not null,
    -- ESTIMATE: 任务创建时按计费配置写入的估算占位行（actual_credit_cost=0）
    -- ACTUAL:   任务结束后真实用量/实际成本结算行（无真实 usage 时也写一行做占位）
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
    deleted tinyint(1) not null default 0,
    key idx_ai_usage_log_task_id (task_id),
    key idx_ai_usage_log_user_id (user_id),
    key idx_ai_usage_log_model_code (model_code),
    key idx_ai_usage_log_usage_phase (usage_phase),
    key idx_ai_usage_log_created_at (created_at),
    key idx_ai_usage_log_deleted (deleted)
);

-- ai_billing_step_config：按 task_type 维护「功能步骤 + 模型/API + usage 单位 + 建议积分」清单。
-- 任务创建时通过 BillingStepConfigService 汇总 enabled=1 步骤的 credit_cost，作为总积分预扣，
-- 没有配置时回退 TaskCreditProperties 固定积分，不破坏旧扣费流水。
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
    unique key uk_ai_billing_step_config_unique (task_type, step_name),
    key idx_ai_billing_step_config_task_type (task_type),
    key idx_ai_billing_step_config_enabled (enabled),
    key idx_ai_billing_step_config_deleted (deleted)
);

create table if not exists task_outbox (
    outbox_id bigint primary key auto_increment comment 'Outbox 记录主键ID',
    event_type varchar(50) not null comment '事件类型（如 TaskCompleted 等）',
    aggregate_id bigint not null comment '聚合根ID（通常为 task_id）',
    routing_key varchar(100) not null comment 'RabbitMQ 路由键',
    payload_json text not null comment '事件载荷JSON',
    status varchar(30) not null default 'PENDING' comment '投递状态：PENDING=待发布 / PUBLISHED=已发布 / FAILED=失败',
    retry_count int not null default 0 comment '重试次数',
    last_error text comment '最近一次失败的错误信息',
    created_at datetime not null default current_timestamp comment '创建时间',
    updated_at datetime not null default current_timestamp comment '更新时间',
    deleted tinyint(1) not null default 0 comment '软删除标记：0=未删除，1=已删除',
    key idx_task_outbox_status (status),
    key idx_task_outbox_aggregate_id (aggregate_id),
    key idx_task_outbox_created_at (created_at),
    key idx_task_outbox_deleted (deleted)
);

create table if not exists admin_operation_log (
    operation_id bigint primary key auto_increment comment '操作日志主键ID',
    admin_user_id bigint not null comment '操作管理员用户ID',
    operation_type varchar(50) not null comment '操作类型：CREDIT_ADJUST=积分调整 / MODEL_SAVE=模型保存 等',
    target_type varchar(50) not null comment '操作对象类型：USER/MODEL/TASK 等',
    target_id bigint comment '操作对象ID',
    before_json text comment '操作前快照JSON',
    after_json text comment '操作后快照JSON',
    ip varchar(60) comment '操作来源IP',
    trace_id varchar(100) comment '链路追踪ID',
    created_at datetime not null default current_timestamp comment '创建时间',
    key idx_admin_operation_log_admin_user_id (admin_user_id),
    key idx_admin_operation_log_target (target_type, target_id),
    key idx_admin_operation_log_created_at (created_at)
);

create table if not exists user_session (
    session_id bigint primary key auto_increment comment '会话主键ID',
    user_id bigint not null comment '用户ID',
    token varchar(120) not null comment '会话令牌（Bearer Token，唯一）',
    expires_at datetime not null comment '令牌过期时间',
    created_at datetime not null default current_timestamp comment '创建时间',
    updated_at datetime not null default current_timestamp comment '更新时间',
    deleted tinyint(1) not null default 0 comment '软删除标记：0=未删除，1=已删除',
    unique key uk_user_session_token (token),
    key idx_user_session_user_id (user_id),
    key idx_user_session_expires_at (expires_at),
    key idx_user_session_deleted (deleted)
);

-- ---- seed data（幂等；voice_profile 的 provider_voice_id 须与火山 TTS speaker 一致，勿改） ----

insert into task(project_id, task_type, status, input_json, output_json, retry_count, trace_id)
select null, 'SCRIPT_REWRITE', 'SUCCESS',
       '{"source":"demo script","goal":"生成可联调用例"}',
       '{"summary":"已生成演示文案版本"}',
       0,
       'demo-trace-001'
where not exists (select 1 from task t where t.project_id is null and t.task_type = 'SCRIPT_REWRITE' and t.deleted = 0);

insert into asset(project_id, task_id, asset_type, file_name, file_url, thumbnail_url, mime_type, file_size, source_type, metadata_json)
select null, t.task_id, 'TEXT', 'demo-script.txt', '/uploads/demo-script.txt', null, 'text/plain', 128, 'DEMO',
       '{"description":"资产中心演示文案"}'
from task t
where t.project_id is null
  and t.task_type = 'SCRIPT_REWRITE'
  and t.deleted = 0
  and not exists (select 1 from asset a where a.project_id is null and a.file_name = 'demo-script.txt' and a.deleted = 0);

insert into script_version(project_id, version_no, content, source_type)
select null, 1, '大家好，今天演示 AI 数字人视频制作的基础工作台。', 'DEMO'
where not exists (select 1 from script_version s where s.project_id is null and s.version_no = 1 and s.deleted = 0);

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

-- ---- 内置管理员（不在 SQL 中写死密码，避免生产弱口令）----
-- 账号由应用启动时 DatabaseCompatibilityInitializer 按 spring profiles 与 HUASHUO_ADMIN_USERNAME / HUASHUO_ADMIN_PASSWORD 创建或补齐。
-- 以下演示种子中若 join `user_account admin where admin.username = 'admin'`，在首包尚未有管理员行时不会插入数据，属预期；需完整演示数据时请使用 dev/local profile 并保证应用已创建管理员后再导入补充脚本（与数据库章节负责人协作）。

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
select null, u.user_id, 'TTS_GENERATE', 'tts-doubao-default', 1, 'task.tts', 'seed-msg-tts-001',
       'SEED:TASK:TTS:demo:001', 0, 'SUCCESS', 100,
       '{"text":"hello huashuo"}', '{"audioUrl":"/uploads/demo-tts.mp3"}',
       null, 0, null, 'seed-trace-tts-001', current_timestamp, current_timestamp
from user_account u
where u.username = 'demo' and u.deleted = 0
  and not exists (select 1 from task t where t.idempotency_key = 'SEED:TASK:TTS:demo:001' and t.deleted = 0);

insert into task(project_id, owner_user_id, task_type, model_code, credit_cost, queue_name, message_id, idempotency_key, priority, status, progress, input_json, output_json, error_code, retry_count, error_message, trace_id, started_at, finished_at)
select null, u.user_id, 'AVATAR_GENERATE', 'avatar-seedream-default', 5, 'task.avatar', 'seed-msg-avatar-001',
       'SEED:TASK:AVATAR:alice:001', 0, 'FAILED', 0,
       '{"prompt":"business presenter"}', null,
       'PROVIDER_ERROR', 0, 'seed provider failure, refunded', 'seed-trace-avatar-001', current_timestamp, current_timestamp
from user_account u
where u.username = 'alice' and u.deleted = 0
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


insert into voice_profile(provider, provider_voice_id, voice_name, gender, scene, sample_url, enabled)
select 'DOUBAO', 'zh_female_cancan_mars_bigtts', '灿灿', '女声', '通用口播', null, 1
    where not exists (select 1 from voice_profile v where v.provider_voice_id = 'zh_female_cancan_mars_bigtts' and v.deleted = 0);

insert into voice_profile(provider, provider_voice_id, voice_name, gender, scene, sample_url, enabled)
select 'DOUBAO', 'zh_female_qingxinnvsheng_mars_bigtts', '清新女声', '女声', '通用口播', null, 1
    where not exists (select 1 from voice_profile v where v.provider_voice_id = 'zh_female_qingxinnvsheng_mars_bigtts' and v.deleted = 0);

insert into voice_profile(provider, provider_voice_id, voice_name, gender, scene, sample_url, enabled)
select 'DOUBAO', 'zh_female_zhixingnvsheng_mars_bigtts', '知性女声', '女声', '知识讲解', null, 1
    where not exists (select 1 from voice_profile v where v.provider_voice_id = 'zh_female_zhixingnvsheng_mars_bigtts' and v.deleted = 0);

insert into voice_profile(provider, provider_voice_id, voice_name, gender, scene, sample_url, enabled)
select 'DOUBAO', 'zh_male_wennuanahu_moon_bigtts', '温暖阿虎', '男声', '温暖口播', null, 1
    where not exists (select 1 from voice_profile v where v.provider_voice_id = 'zh_male_wennuanahu_moon_bigtts' and v.deleted = 0);

insert into voice_profile(provider, provider_voice_id, voice_name, gender, scene, sample_url, enabled)
select 'DOUBAO', 'zh_male_shaonianzixin_moon_bigtts', '少年梓辛', '男声', '年轻口播', null, 1
    where not exists (select 1 from voice_profile v where v.provider_voice_id = 'zh_male_shaonianzixin_moon_bigtts' and v.deleted = 0);

insert into voice_profile(provider, provider_voice_id, voice_name, gender, scene, sample_url, enabled)
select 'DOUBAO', 'zh_male_qingshuangnanda_mars_bigtts', '清爽男大', '男声', '通用口播', null, 1
    where not exists (select 1 from voice_profile v where v.provider_voice_id = 'zh_male_qingshuangnanda_mars_bigtts' and v.deleted = 0);

insert into voice_profile(provider, provider_voice_id, voice_name, gender, scene, sample_url, enabled)
select 'DOUBAO', 'zh_female_linjianvhai_moon_bigtts', '邻家女孩', '女声', '生活分享', null, 1
    where not exists (select 1 from voice_profile v where v.provider_voice_id = 'zh_female_linjianvhai_moon_bigtts' and v.deleted = 0);

insert into voice_profile(provider, provider_voice_id, voice_name, gender, scene, sample_url, enabled)
select 'DOUBAO', 'zh_male_yuanboxiaoshu_moon_bigtts', '渊博小叔', '男声', '知识讲解', null, 1
    where not exists (select 1 from voice_profile v where v.provider_voice_id = 'zh_male_yuanboxiaoshu_moon_bigtts' and v.deleted = 0);

insert into voice_profile(provider, provider_voice_id, voice_name, gender, scene, sample_url, enabled)
select 'DOUBAO', 'zh_male_yangguangqingnian_moon_bigtts', '阳光青年', '男声', '通用口播', null, 1
    where not exists (select 1 from voice_profile v where v.provider_voice_id = 'zh_male_yangguangqingnian_moon_bigtts' and v.deleted = 0);

insert into voice_profile(provider, provider_voice_id, voice_name, gender, scene, sample_url, enabled)
select 'DOUBAO', 'zh_female_tianmeixiaoyuan_moon_bigtts', '甜美小源', '女声', '生活分享', null, 1
    where not exists (select 1 from voice_profile v where v.provider_voice_id = 'zh_female_tianmeixiaoyuan_moon_bigtts' and v.deleted = 0);

insert into voice_profile(provider, provider_voice_id, voice_name, gender, scene, sample_url, enabled)
select 'DOUBAO', 'zh_female_qingchezizi_moon_bigtts', '清澈梓梓', '女声', '清亮口播', null, 1
    where not exists (select 1 from voice_profile v where v.provider_voice_id = 'zh_female_qingchezizi_moon_bigtts' and v.deleted = 0);

insert into voice_profile(provider, provider_voice_id, voice_name, gender, scene, sample_url, enabled)
select 'DOUBAO', 'zh_female_sajiaonvyou_moon_bigtts', '撒娇女友', '女声', '情感娱乐', null, 1
    where not exists (select 1 from voice_profile v where v.provider_voice_id = 'zh_female_sajiaonvyou_moon_bigtts' and v.deleted = 0);

insert into voice_profile(provider, provider_voice_id, voice_name, gender, scene, sample_url, enabled)
select 'DOUBAO', 'zh_female_gaolengyujie_moon_bigtts', '高冷御姐', '女声', '情感娱乐', null, 1
    where not exists (select 1 from voice_profile v where v.provider_voice_id = 'zh_female_gaolengyujie_moon_bigtts' and v.deleted = 0);

insert into voice_profile(provider, provider_voice_id, voice_name, gender, scene, sample_url, enabled)
select 'DOUBAO', 'zh_female_yuanqinvyou_moon_bigtts', '元气女友', '女声', '活力口播', null, 1
    where not exists (select 1 from voice_profile v where v.provider_voice_id = 'zh_female_yuanqinvyou_moon_bigtts' and v.deleted = 0);

insert into voice_profile(provider, provider_voice_id, voice_name, gender, scene, sample_url, enabled)
select 'DOUBAO', 'zh_male_aojiaobazong_moon_bigtts', '傲娇霸总', '男声', '剧情娱乐', null, 1
    where not exists (select 1 from voice_profile v where v.provider_voice_id = 'zh_male_aojiaobazong_moon_bigtts' and v.deleted = 0);

insert into voice_profile(provider, provider_voice_id, voice_name, gender, scene, sample_url, enabled)
select 'DOUBAO', 'zh_male_jingqiangkanye_moon_bigtts', '京腔侃爷', '男声', '方言娱乐', null, 1
    where not exists (select 1 from voice_profile v where v.provider_voice_id = 'zh_male_jingqiangkanye_moon_bigtts' and v.deleted = 0);

insert into voice_profile(provider, provider_voice_id, voice_name, gender, scene, sample_url, enabled)
select 'DOUBAO', 'zh_female_shaoergushi_mars_bigtts', '少儿故事', '女声', '故事配音', null, 1
    where not exists (select 1 from voice_profile v where v.provider_voice_id = 'zh_female_shaoergushi_mars_bigtts' and v.deleted = 0);

insert into voice_profile(provider, provider_voice_id, voice_name, gender, scene, sample_url, enabled)
select 'DOUBAO', 'zh_male_silang_mars_bigtts', '四郎', '男声', '低沉配音', null, 1
    where not exists (select 1 from voice_profile v where v.provider_voice_id = 'zh_male_silang_mars_bigtts' and v.deleted = 0);

insert into voice_profile(provider, provider_voice_id, voice_name, gender, scene, sample_url, enabled)
select 'DOUBAO', 'zh_male_jieshuonansheng_mars_bigtts', '解说男声', '男声', '视频解说', null, 1
    where not exists (select 1 from voice_profile v where v.provider_voice_id = 'zh_male_jieshuonansheng_mars_bigtts' and v.deleted = 0);

insert into voice_profile(provider, provider_voice_id, voice_name, gender, scene, sample_url, enabled)
select 'DOUBAO', 'zh_female_jitangmeimei_mars_bigtts', '鸡汤妹妹', '女声', '情感旁白', null, 1
    where not exists (select 1 from voice_profile v where v.provider_voice_id = 'zh_female_jitangmeimei_mars_bigtts' and v.deleted = 0);

insert into voice_profile(provider, provider_voice_id, voice_name, gender, scene, sample_url, enabled)
select 'DOUBAO', 'zh_female_tiexinnvsheng_mars_bigtts', '贴心女声', '女声', '温暖旁白', null, 1
    where not exists (select 1 from voice_profile v where v.provider_voice_id = 'zh_female_tiexinnvsheng_mars_bigtts' and v.deleted = 0);

insert into voice_profile(provider, provider_voice_id, voice_name, gender, scene, sample_url, enabled)
select 'DOUBAO', 'zh_female_qiaopinvsheng_mars_bigtts', '俏皮女声', '女声', '活力配音', null, 1
    where not exists (select 1 from voice_profile v where v.provider_voice_id = 'zh_female_qiaopinvsheng_mars_bigtts' and v.deleted = 0);

insert into voice_profile(provider, provider_voice_id, voice_name, gender, scene, sample_url, enabled)
select 'DOUBAO', 'zh_female_mengyatou_mars_bigtts', '萌丫头', '女声', '萌系配音', null, 1
    where not exists (select 1 from voice_profile v where v.provider_voice_id = 'zh_female_mengyatou_mars_bigtts' and v.deleted = 0);

insert into voice_profile(provider, provider_voice_id, voice_name, gender, scene, sample_url, enabled)
select 'DOUBAO', 'zh_male_guozhoudege_moon_bigtts', '广州德哥', '男声', '粤语口播', null, 1
    where not exists (select 1 from voice_profile v where v.provider_voice_id = 'zh_male_guozhoudege_moon_bigtts' and v.deleted = 0);

insert into activate_code(`key`) values('huashuo');

insert into activate_code(`key`) values
('HSAI-7K3M9Q2A'),
('HSAI-P4X8N6R1'),
('HSAI-2W9C5T7L'),
('HSAI-M6Q1Z8V3'),
('HSAI-9R2H4K6D'),
('HSAI-C7N5P1X8'),
('HSAI-5T8V2M9Q'),
('HSAI-X1L6R3C7'),
('HSAI-8D4K9N2P'),
('HSAI-Q3V7W5M1'),
('HSAI-6P2X8T4H'),
('HSAI-N9C1Q7R5'),
('HSAI-4M8L2V6K'),
('HSAI-R7T3D9X1'),
('HSAI-1Q6P4N8C'),
('HSAI-V5K9M2R7'),
('HSAI-3X8H1T6P'),
('HSAI-L2D7Q5N9'),
('HSAI-9V4C8M1K'),
('HSAI-P6R2X7T3'),
('HSAI-5N9Q1H4D'),
('HSAI-C8M3V6L2'),
('HSAI-2T7K9P5X'),
('HSAI-Q1D6R8N4'),
('HSAI-7H2V5C9M'),
('HSAI-X4P8L1Q6'),
('HSAI-8R3N7T2D'),
('HSAI-M9K5C1V8'),
('HSAI-6Q2X4P7L'),
('HSAI-D5T9R1N3'),
('HSAI-1V8M6K2C'),
('HSAI-P7Q3H9X5'),
('HSAI-4L1D8N6R'),
('HSAI-T9C2V7M4'),
('HSAI-2K6P1Q8X'),
('HSAI-N5R9D3T7'),
('HSAI-8M4H2C6V'),
('HSAI-Q7X1L5P9'),
('HSAI-3D8T6R2N'),
('HSAI-V1K9M4C7'),
('HSAI-6P5Q2X8L'),
('HSAI-C9N3D7T1'),
('HSAI-4R8V6M2K'),
('HSAI-X5L1P9Q3'),
('HSAI-7T2C8N4D'),
('HSAI-M6Q9R1V5'),
('HSAI-1H4K7X2P'),
('HSAI-D8M3T6N9'),
('HSAI-5V1C9Q7L'),
('HSAI-R2P6X4K8');

insert into admin_operation_log(admin_user_id, operation_type, target_type, target_id, before_json, after_json, ip, trace_id)
select admin.user_id, 'MODEL_SAVE', 'MODEL', m.model_id, null,
       '{"modelCode":"tts-doubao-default","enabled":true}', '127.0.0.1', 'seed-op-model-001'
from user_account admin, ai_model_config m
where admin.username = 'admin' and admin.deleted = 0 and m.model_code = 'tts-doubao-default' and m.deleted = 0
  and not exists (select 1 from admin_operation_log l where l.trace_id = 'seed-op-model-001');

-- 演示资产（图片/文本/JSON）不在此插入：历史上此处未写 file_path，导致 H2 每次初始化后出现「无本地路径」的参考图；
-- 统一由 SeedUserAssetInitializer 在启动时写入绝对路径并复制 seed 文件，MySQL 存量脏数据也会在同类逻辑中修补。

-- ---- ai_billing_step_config 种子（依据《AI 功能成本与积分统计表.txt》）----
-- 规则：第一版完全采用表中“建议积分”作为 credit_cost；同一 task_type 下所有 enabled=1 步骤的 credit_cost 由
-- BillingStepConfigService.aggregateCreditCost 累加，作为该任务创建时的总扣费积分。
-- 管理员若希望降低单任务积分，可在后台将不需要默认参与的步骤改为 enabled=0，留作真实用量上报参考。

-- 一、抖音解析 / 爆款对标 -> TaskTypeCode.DOUYIN_PARSE_TRANSCRIPT
insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'DOUYIN_PARSE_TRANSCRIPT', '抖音解析', '分享链接解析', 'TIKHUB', 'tikhub-api', 'TASK', '1-2 次', '$ 0.001', 10, 1, 10, '获取视频基础信息'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'DOUYIN_PARSE_TRANSCRIPT' and c.step_name = '分享链接解析' and c.deleted = 0);

insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'DOUYIN_PARSE_TRANSCRIPT', '抖音解析', '视频字幕获取', 'TIKHUB', 'tikhub-api', 'TASK', '1 次', '$ 0.001', 10, 1, 20, '获取视频字幕'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'DOUYIN_PARSE_TRANSCRIPT' and c.step_name = '视频字幕获取' and c.deleted = 0);

insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'DOUYIN_PARSE_TRANSCRIPT', '抖音解析', '视频下载', 'TIKHUB', 'tikhub-api', 'TASK', '1 次', '$ 0.001', 10, 1, 30, '下载源视频'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'DOUYIN_PARSE_TRANSCRIPT' and c.step_name = '视频下载' and c.deleted = 0);

insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'DOUYIN_PARSE_TRANSCRIPT', '抖音解析', 'FFmpeg 抽音频', 'LOCAL_FFMPEG', 'ffmpeg-local', 'SECOND', '1 次', '0', 30, 1, 40, '本地服务器资源消耗'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'DOUYIN_PARSE_TRANSCRIPT' and c.step_name = 'FFmpeg 抽音频' and c.deleted = 0);

insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'DOUYIN_PARSE_TRANSCRIPT', '爆款对标', 'ASR 音频转写', 'VOLCENGINE', 'volcengine-asr', 'SECOND', '1 次 + 多次轮询', '0.8 元/小时，平均 0.013 元/分钟', 20, 1, 50, '按音频时长计费'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'DOUYIN_PARSE_TRANSCRIPT' and c.step_name = 'ASR 音频转写' and c.deleted = 0);

insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'DOUYIN_PARSE_TRANSCRIPT', '爆款对标', '文案分析', 'VOLCENGINE', 'doubao-seed-2-0-mini-260215', 'TOKEN', '1 次', '平均 1 元/百万 Token', 20, 1, 60, '文本模型'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'DOUYIN_PARSE_TRANSCRIPT' and c.step_name = '文案分析' and c.deleted = 0);

insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'DOUYIN_PARSE_TRANSCRIPT', '爆款对标', '爆点分析', 'VOLCENGINE', 'doubao-seed-2-0-mini-260215', 'TOKEN', '1 次', '平均 1 元/百万 Token', 20, 1, 70, '文本模型'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'DOUYIN_PARSE_TRANSCRIPT' and c.step_name = '爆点分析' and c.deleted = 0);

insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'DOUYIN_PARSE_TRANSCRIPT', '爆款对标', '文案结构拆解', 'VOLCENGINE', 'doubao-seed-2-0-mini-260215', 'TOKEN', '1 次', '平均 1 元/百万 Token', 20, 1, 80, '文本模型'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'DOUYIN_PARSE_TRANSCRIPT' and c.step_name = '文案结构拆解' and c.deleted = 0);

insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'DOUYIN_PARSE_TRANSCRIPT', '爆款对标', '标签分析', 'VOLCENGINE', 'doubao-seed-2-0-mini-260215', 'TOKEN', '1 次', '平均 1 元/百万 Token', 20, 1, 90, '文本模型'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'DOUYIN_PARSE_TRANSCRIPT' and c.step_name = '标签分析' and c.deleted = 0);

-- 二、文案改写 -> TaskTypeCode.SCRIPT_REWRITE
-- 默认仅启用「爆款风格改写」一个步骤参与扣费（避免一次任务被汇总成 5 步=100 积分），其余先入库 enabled=0 留待管理员按风格开启。
insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'SCRIPT_REWRITE', '文案改写', '爆款风格改写', 'VOLCENGINE', 'doubao-seed-2-0-mini-260215', 'TOKEN', '1 次', '平均 1 元/百万 Token', 20, 1, 10, '按 token 计费；默认作为该任务计费步骤'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'SCRIPT_REWRITE' and c.step_name = '爆款风格改写' and c.deleted = 0);

insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'SCRIPT_REWRITE', '文案改写', '文案润色', 'VOLCENGINE', 'doubao-seed-2-0-mini-260215', 'TOKEN', '1 次', '平均 1 元/百万 Token', 20, 0, 20, '按 token 计费'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'SCRIPT_REWRITE' and c.step_name = '文案润色' and c.deleted = 0);

insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'SCRIPT_REWRITE', '文案改写', '缩写/扩写', 'VOLCENGINE', 'doubao-seed-2-0-mini-260215', 'TOKEN', '1 次', '平均 1 元/百万 Token', 20, 0, 30, '按 token 计费'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'SCRIPT_REWRITE' and c.step_name = '缩写/扩写' and c.deleted = 0);

insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'SCRIPT_REWRITE', '文案改写', '情绪增强', 'VOLCENGINE', 'doubao-seed-2-0-mini-260215', 'TOKEN', '1 次', '平均 1 元/百万 Token', 20, 0, 40, '按 token 计费'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'SCRIPT_REWRITE' and c.step_name = '情绪增强' and c.deleted = 0);

insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'SCRIPT_REWRITE', '文案改写', '违禁词优化', 'VOLCENGINE', 'doubao-seed-2-0-mini-260215', 'TOKEN', '1 次', '平均 1 元/百万 Token', 20, 0, 50, '按 token 计费'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'SCRIPT_REWRITE' and c.step_name = '违禁词优化' and c.deleted = 0);

-- 三、分镜生成 -> TaskTypeCode.STORYBOARD_GENERATE
insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'STORYBOARD_GENERATE', '分镜生成', '分镜脚本生成', 'VOLCENGINE', 'doubao-seed-2-0-mini-260215', 'TOKEN', '1 次', '平均 1 元/百万 Token', 20, 1, 10, '按 token 计费'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'STORYBOARD_GENERATE' and c.step_name = '分镜脚本生成' and c.deleted = 0);

insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'STORYBOARD_GENERATE', '分镜生成', '镜头拆分', 'VOLCENGINE', 'doubao-seed-2-0-mini-260215', 'TOKEN', '1 次', '平均 1 元/百万 Token', 20, 1, 20, '按 token 计费'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'STORYBOARD_GENERATE' and c.step_name = '镜头拆分' and c.deleted = 0);

-- 三bis、视频分镜解析（上传/直链）-> TaskTypeCode.VIDEO_SCRIPT_ANALYZE / VIDEO_SCRIPT_URL_ANALYZE
-- 与 STORYBOARD_GENERATE 口径一致：两步各 20 积分；createTask 预扣为 enabled 步骤之和。
insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'VIDEO_SCRIPT_ANALYZE', '分镜解析', '分镜脚本生成', 'VOLCENGINE', 'doubao-seed-2-0-mini-260215', 'TOKEN', '1 次', '平均 1 元/百万 Token', 20, 1, 10, '上传视频后走视觉模型解析'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'VIDEO_SCRIPT_ANALYZE' and c.step_name = '分镜脚本生成' and c.deleted = 0);

insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'VIDEO_SCRIPT_ANALYZE', '分镜解析', '镜头拆分', 'VOLCENGINE', 'doubao-seed-2-0-mini-260215', 'TOKEN', '1 次', '平均 1 元/百万 Token', 20, 1, 20, '按 token 计费'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'VIDEO_SCRIPT_ANALYZE' and c.step_name = '镜头拆分' and c.deleted = 0);

insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'VIDEO_SCRIPT_URL_ANALYZE', '分镜解析', '分镜脚本生成', 'VOLCENGINE', 'doubao-seed-2-0-mini-260215', 'TOKEN', '1 次', '平均 1 元/百万 Token', 20, 1, 10, '分享链接解析后再走视觉模型'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'VIDEO_SCRIPT_URL_ANALYZE' and c.step_name = '分镜脚本生成' and c.deleted = 0);

insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'VIDEO_SCRIPT_URL_ANALYZE', '分镜解析', '镜头拆分', 'VOLCENGINE', 'doubao-seed-2-0-mini-260215', 'TOKEN', '1 次', '平均 1 元/百万 Token', 20, 1, 20, '按 token 计费'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'VIDEO_SCRIPT_URL_ANALYZE' and c.step_name = '镜头拆分' and c.deleted = 0);

-- 视频理解 -> TaskTypeCode.VIDEO_PARSE（当前未走 createTask，预留配置便于后续接入）
insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'VIDEO_PARSE', '视频理解', '视频 URL 分析', 'VOLCENGINE', 'doubao-seed-2-0-lite-260215', 'TOKEN', '1 次', '平均 5 元/百万 Token', 100, 1, 10, '多模态分析'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'VIDEO_PARSE' and c.step_name = '视频 URL 分析' and c.deleted = 0);

insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'VIDEO_PARSE', '视频理解', '抖音 URL 分析', 'VOLCENGINE', 'tikhub+tos+ark', 'TOKEN', '多次', '组合调用', 50, 1, 20, '多服务组合调用'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'VIDEO_PARSE' and c.step_name = '抖音 URL 分析' and c.deleted = 0);

-- 四、TTS -> TaskTypeCode.TTS_GENERATE / VOICE_SAMPLE
-- 任务创建走 TTS_GENERATE 时主流程仅启用「文本转语音」一步（5 积分）；情绪语音与轮询查询留库 disabled，避免与预扣口径冲突。
insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'TTS_GENERATE', 'TTS', '文本转语音', 'VOLCENGINE', 'tts-doubao-default', 'CHAR', '1 次', '按字符数计费', 5, 1, 10, '主合成步骤'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'TTS_GENERATE' and c.step_name = '文本转语音' and c.deleted = 0);

insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'TTS_GENERATE', 'TTS', '情绪语音生成', 'VOLCENGINE', 'tts-doubao-default', 'CHAR', '1 次', '按字符数计费', 10, 0, 20, '默认关闭；需要情绪能力时启用'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'TTS_GENERATE' and c.step_name = '情绪语音生成' and c.deleted = 0);

insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'TTS_GENERATE', 'TTS', '音频查询轮询', 'VOLCENGINE', 'tts-doubao-query', 'TASK', '多次', '查询状态', 0, 0, 30, '预扣仅计主合成；轮询不占预扣，可按需启用'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'TTS_GENERATE' and c.step_name = '音频查询轮询' and c.deleted = 0);

insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'VOICE_SAMPLE', 'TTS', '音色试听', 'VOLCENGINE', 'tts-doubao-default', 'CHAR', '1 次', '按字符数计费', 10, 1, 10, '首次生成试听音频并持久化缓存'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'VOICE_SAMPLE' and c.step_name = '音色试听' and c.deleted = 0);

-- 五、AI 图片生成 -> TaskTypeCode.AVATAR_GENERATE
-- 数字人形象、封面图、场景背景图共享同一 task_type；默认仅启用「数字人形象生成」一个步骤参与扣费，其余留作管理员按入口开启。
insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'AVATAR_GENERATE', 'AI 图片生成', '数字人形象生成', 'VOLCENGINE', 'doubao-seedream-5-0-260128', 'IMAGE', '1 次', '0.22 元/张', 20, 1, 10, '一次可返回多张'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'AVATAR_GENERATE' and c.step_name = '数字人形象生成' and c.deleted = 0);

insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'AVATAR_GENERATE', 'AI 图片生成', '封面图生成', 'VOLCENGINE', 'doubao-seedream-5-0-260128', 'IMAGE', '1 次', '0.22 元/张', 20, 0, 20, '默认关闭；按张数计费'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'AVATAR_GENERATE' and c.step_name = '封面图生成' and c.deleted = 0);

insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'AVATAR_GENERATE', 'AI 图片生成', '场景背景图生成', 'VOLCENGINE', 'doubao-seedream-5-0-260128', 'IMAGE', '1 次', '0.22 元/张', 20, 0, 30, '默认关闭；按张数计费'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'AVATAR_GENERATE' and c.step_name = '场景背景图生成' and c.deleted = 0);

-- 六、视频生成 / 图生视频 / Seedance 2.0
-- 当前没有对应 TaskTypeCode，先写入 SEEDANCE_* 与 IMAGE_TO_VIDEO 占位 task_type，待后续接入 createTask 时即可生效。
-- 暂未对外暴露 createTask 入口，不会立即扣费；管理员可在后台调整 credit_cost 与 enabled。
insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'TEXT_TO_VIDEO_SEEDANCE_1_5', '文生视频', 'Seedance 1.5 视频生成', 'VOLCENGINE', 'doubao-seedance-1-5-pro-251215', 'SECOND', '1 次 + 多次轮询', '平均 10 元/个', 200, 1, 10, '按视频时长计费；待 createTask 入口接入'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'TEXT_TO_VIDEO_SEEDANCE_1_5' and c.step_name = 'Seedance 1.5 视频生成' and c.deleted = 0);

insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'TEXT_TO_VIDEO_SEEDANCE_2_0', '文生视频', 'Seedance 2.0 视频生成', 'VOLCENGINE', 'doubao-seedance-2-0-pro', 'SECOND', '1 次 + 多次轮询', '平均 10 元/个', 200, 1, 10, '新版模型；待 createTask 入口接入'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'TEXT_TO_VIDEO_SEEDANCE_2_0' and c.step_name = 'Seedance 2.0 视频生成' and c.deleted = 0);

insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'IMAGE_TO_VIDEO_SEEDANCE_1_5', '图生视频', '首帧视频生成', 'VOLCENGINE', 'doubao-seedance-1-5-pro-251215', 'SECOND', '1 次 + 多次轮询', '平均 10 元/个', 200, 1, 10, '按视频时长计费'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'IMAGE_TO_VIDEO_SEEDANCE_1_5' and c.step_name = '首帧视频生成' and c.deleted = 0);

insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'IMAGE_TO_VIDEO_SEEDANCE_1_5', '图生视频', '首尾帧视频生成', 'VOLCENGINE', 'doubao-seedance-1-5-pro-251215', 'SECOND', '1 次 + 多次轮询', '平均 10 元/个', 200, 0, 20, '按视频时长计费；默认关闭'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'IMAGE_TO_VIDEO_SEEDANCE_1_5' and c.step_name = '首尾帧视频生成' and c.deleted = 0);

insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'IMAGE_TO_VIDEO_SEEDANCE_2_0_FAST', '图生视频', '参考图视频生成', 'VOLCENGINE', 'doubao-seedance-2-0-fast', 'SECOND', '1 次 + 多次轮询', '平均 15 元/个', 230, 1, 10, '按视频时长计费'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'IMAGE_TO_VIDEO_SEEDANCE_2_0_FAST' and c.step_name = '参考图视频生成' and c.deleted = 0);

insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'IMAGE_TO_VIDEO_SEEDANCE_2_0', '图生视频', 'Seedance 2.0 图生视频', 'VOLCENGINE', 'doubao-seedance-2-0', 'SECOND', '1 次 + 多次轮询', '平均 30 元/个', 300, 1, 10, '新版模型'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'IMAGE_TO_VIDEO_SEEDANCE_2_0' and c.step_name = 'Seedance 2.0 图生视频' and c.deleted = 0);

-- 七、数字人口播 -> TaskTypeCode.DIGITAL_HUMAN_GENERATE
-- 表中各步骤未给具体积分（Vidu 返回 credits 后再结算），仅创建数字人任务给 10 积分占位（与 TaskCreditProperties 当前默认一致），
-- 后续走 CreditBillingService.settle 用 actual provider_credits 结算时再补扣或退差。
insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'DIGITAL_HUMAN_GENERATE', '数字人口播', '创建数字人任务', 'VIDU', 'viduq2-turbo', 'PROVIDER_CREDIT', '1 次', 'Vidu 返回 credits', 10, 1, 10, '保留与 TaskCreditProperties 默认一致的占位扣费'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'DIGITAL_HUMAN_GENERATE' and c.step_name = '创建数字人任务' and c.deleted = 0);

insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'DIGITAL_HUMAN_GENERATE', '数字人口播', '音频驱动口型', 'VIDU', 'viduq2-turbo', 'PROVIDER_CREDIT', '1 次', 'Vidu credits', 0, 1, 20, '由 provider credits 累计，settle 时结算实际积分'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'DIGITAL_HUMAN_GENERATE' and c.step_name = '音频驱动口型' and c.deleted = 0);

insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'DIGITAL_HUMAN_GENERATE', '数字人口播', '视频渲染', 'VIDU', 'viduq2-turbo', 'PROVIDER_CREDIT', '1 次', 'Vidu credits', 0, 1, 30, '由 provider credits 累计，settle 时结算实际积分'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'DIGITAL_HUMAN_GENERATE' and c.step_name = '视频渲染' and c.deleted = 0);

insert into ai_billing_step_config(task_type, function_module, step_name, provider, model_code, usage_unit, call_count, cost_text, credit_cost, enabled, sort_order, remark)
select 'DIGITAL_HUMAN_GENERATE', '数字人口播', '结果轮询', 'VIDU', 'vidu-query', 'TASK', '多次', '查询状态', 0, 1, 40, '查询状态'
where not exists (select 1 from ai_billing_step_config c where c.task_type = 'DIGITAL_HUMAN_GENERATE' and c.step_name = '结果轮询' and c.deleted = 0);


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

-- 已存在的 MySQL 8.0.29 以下版本不支持 ALTER TABLE ... ADD COLUMN IF NOT EXISTS：
-- 若 ai_usage_log 缺少 usage_phase 列，请手动执行一次（仅一次、列已存在则跳过）：
-- alter table ai_usage_log add column usage_phase varchar(20) not null default 'ACTUAL' after usage_unit;
-- create index idx_ai_usage_log_usage_phase on ai_usage_log(usage_phase);

-- 已存在的 MySQL 库若缺少 ai_billing_step_config 表（按功能步骤维度的计费配置），请执行一次：
-- create table ai_billing_step_config (
--     step_id bigint primary key auto_increment,
--     task_type varchar(50) not null,
--     function_module varchar(80) not null default '',
--     step_name varchar(120) not null,
--     provider varchar(50) null,
--     model_code varchar(120) null,
--     usage_unit varchar(30) not null default 'TASK',
--     call_count varchar(60) null,
--     cost_text varchar(200) null,
--     credit_cost bigint not null default 0,
--     enabled tinyint(1) not null default 1,
--     sort_order int not null default 0,
--     remark varchar(500) null,
--     created_at datetime not null default current_timestamp,
--     updated_at datetime not null default current_timestamp,
--     deleted tinyint(1) not null default 0,
--     unique key uk_ai_billing_step_config_unique (task_type, step_name),
--     key idx_ai_billing_step_config_task_type (task_type),
--     key idx_ai_billing_step_config_enabled (enabled),
--     key idx_ai_billing_step_config_deleted (deleted)
-- );
