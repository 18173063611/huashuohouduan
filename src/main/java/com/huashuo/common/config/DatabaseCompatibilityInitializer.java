package com.huashuo.common.config;

import com.huashuo.admin.config.AdminAccessProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Locale;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(prefix = "huashuo.bootstrap.database-compatibility", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class DatabaseCompatibilityInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseCompatibilityInitializer.class);

    private static final String DEV_FALLBACK_PLAINTEXT_PASSWORD = "admin1234";

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final Environment environment;
    private final AdminAccessProperties adminAccessProperties;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public DatabaseCompatibilityInitializer(
            DataSource dataSource,
            JdbcTemplate jdbcTemplate,
            Environment environment,
            AdminAccessProperties adminAccessProperties
    ) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
        this.environment = environment;
        this.adminAccessProperties = adminAccessProperties;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        ensureUserAccountColumns();
        ensureAssetColumns();
        ensureScriptAndUploadOwnershipColumns();
        ensureTaskColumns();
        ensureProviderOpsTables();
        ensureCustomerFeedbackTable();
        ensureUsageBillingTables();
        ensureDefaultAdmin();
        ensureCreditAccounts();
    }

    private void ensureUserAccountColumns() throws SQLException {
        if (!tableExists("user_account")) {
            return;
        }
        // schema.sql 的 create table if not exists 不会改旧表，这里兜住本地 H2 和已有 MySQL 的平滑升级。
        addColumnIfMissing("user_account", "role", "varchar(20) not null default 'USER'");
        addColumnIfMissing("user_account", "status", "varchar(20) not null default 'ENABLED'");
        addColumnIfMissing("user_account", "phone", "varchar(30)");
        addColumnIfMissing("user_account", "email", "varchar(120)");
        addColumnIfMissing("user_account", "remark", "varchar(500)");
        addColumnIfMissing("user_account", "last_login_at", "datetime");
        addColumnIfMissing("user_account", "last_login_ip", "varchar(60)");
        addIndexIfMissing("user_account", "idx_user_account_role",
                "create index idx_user_account_role on user_account(role)");
        addIndexIfMissing("user_account", "idx_user_account_status",
                "create index idx_user_account_status on user_account(status)");
        addIndexIfMissing("user_account", "idx_user_account_created_at",
                "create index idx_user_account_created_at on user_account(created_at)");
    }

    private void ensureAssetColumns() throws SQLException {
        if (!tableExists("asset")) {
            return;
        }
        addColumnIfMissing("asset", "owner_user_id", "bigint");
        addColumnIfMissing("asset", "created_by_user_id", "bigint");
        addColumnIfMissing("asset", "kind", "varchar(30) not null default 'MATERIAL'");
        addColumnIfMissing("asset", "visibility", "varchar(20) not null default 'PRIVATE'");
        addColumnIfMissing("asset", "status", "varchar(20) not null default 'ACTIVE'");
        addColumnIfMissing("asset", "published_at", "datetime");
        addColumnIfMissing("asset", "asset_group", "varchar(60)");
        addIndexIfMissing("asset", "idx_asset_owner_user_id",
                "create index idx_asset_owner_user_id on asset(owner_user_id)");
        addIndexIfMissing("asset", "idx_asset_created_by_user_id",
                "create index idx_asset_created_by_user_id on asset(created_by_user_id)");
        addIndexIfMissing("asset", "idx_asset_visibility",
                "create index idx_asset_visibility on asset(visibility)");
        addIndexIfMissing("asset", "idx_asset_kind",
                "create index idx_asset_kind on asset(kind)");
        addIndexIfMissing("asset", "idx_asset_status",
                "create index idx_asset_status on asset(status)");
        addIndexIfMissing("asset", "idx_asset_group",
                "create index idx_asset_group on asset(asset_group)");
    }

    private void ensureScriptAndUploadOwnershipColumns() throws SQLException {
        if (tableExists("script_version")) {
            addColumnIfMissing("script_version", "owner_user_id", "bigint");
            addIndexIfMissing("script_version", "idx_script_version_owner_user_id",
                    "create index idx_script_version_owner_user_id on script_version(owner_user_id)");
        }
        if (tableExists("uploaded_file")) {
            addColumnIfMissing("uploaded_file", "owner_user_id", "bigint");
            addIndexIfMissing("uploaded_file", "idx_uploaded_file_owner_user_id",
                    "create index idx_uploaded_file_owner_user_id on uploaded_file(owner_user_id)");
        }
    }

    private void addColumnIfMissing(String table, String column, String ddl) throws SQLException {
        if (columnExists(table, column)) {
            return;
        }
        jdbcTemplate.execute("alter table " + table + " add column " + column + " " + ddl);
    }

    /**
     * 内置管理员：按 spring profile 与 {@code huashuo.admin.*} / {@code HUASHUO_ADMIN_*} 策略创建或补齐。
     * 不在此记录明文密码。
     */
    private void ensureDefaultAdmin() throws SQLException {
        if (!tableExists("user_account")) {
            return;
        }
        AdminBootstrapProfilePolicy.AdminPasswordPolicy policy = resolveAdminPasswordPolicy();
        assertStrictProfilePasswordConfigured(policy);

        String adminUsername = resolveConfiguredAdminUsername();
        Integer count = jdbcTemplate.queryForObject(
                "select count(1) from user_account where lower(username) = ? and deleted = 0",
                Integer.class,
                adminUsername
        );
        boolean exists = count != null && count > 0;
        if (exists) {
            jdbcTemplate.update(
                    "update user_account set role = 'ADMIN', status = 'ENABLED', updated_at = ? where lower(username) = ? and deleted = 0",
                    LocalDateTime.now(), adminUsername
            );
            if (adminAccessProperties.isForceReset()) {
                applyForcedPasswordReset(adminUsername, policy);
            }
            return;
        }

        String plaintextForInsert = resolvePlaintextForNewAdmin(policy);
        warnDevDefaultPasswordIfNeeded(policy, plaintextForInsert);
        String hash = passwordEncoder.encode(plaintextForInsert);
        jdbcTemplate.update(
                "insert into user_account(username, password_hash, display_name, role, status) values (?, ?, ?, ?, ?)",
                adminUsername, hash, "系统管理员", "ADMIN", "ENABLED"
        );
    }

    private void assertStrictProfilePasswordConfigured(AdminBootstrapProfilePolicy.AdminPasswordPolicy policy) {
        if (policy != AdminBootstrapProfilePolicy.AdminPasswordPolicy.STRICT) {
            return;
        }
        String pwd = adminAccessProperties.getPassword();
        if (!StringUtils.hasText(pwd)) {
            throw new IllegalStateException(
                    "Active Spring profile is prod or test: set a strong HUASHUO_ADMIN_PASSWORD (huashuo.admin.password). "
                            + "Default weak passwords are not allowed."
            );
        }
        String trimmed = pwd.trim();
        if (AdminBootstrapProfilePolicy.isWeakPlaintextPassword(trimmed)) {
            throw new IllegalStateException(
                    "Active Spring profile is prod or test: HUASHUO_ADMIN_PASSWORD must not be a well-known weak password."
            );
        }
    }

    private void applyForcedPasswordReset(String adminUsername, AdminBootstrapProfilePolicy.AdminPasswordPolicy policy) {
        if (!adminAccessProperties.isForceReset()) {
            return;
        }
        String pwd = adminAccessProperties.getPassword();
        if (!StringUtils.hasText(pwd)) {
            throw new IllegalStateException(
                    "HUASHUO_ADMIN_FORCE_RESET=true requires HUASHUO_ADMIN_PASSWORD to be set (huashuo.admin.password)."
            );
        }
        String trimmed = pwd.trim();
        if (policy == AdminBootstrapProfilePolicy.AdminPasswordPolicy.STRICT
                && AdminBootstrapProfilePolicy.isWeakPlaintextPassword(trimmed)) {
            throw new IllegalStateException(
                    "HUASHUO_ADMIN_FORCE_RESET with prod/test profile: password must not be a well-known weak password."
            );
        }
        String hash = passwordEncoder.encode(trimmed);
        jdbcTemplate.update(
                "update user_account set password_hash = ?, updated_at = ? where lower(username) = ? and deleted = 0",
                hash, LocalDateTime.now(), adminUsername
        );
        log.warn("Applied HUASHUO_ADMIN_FORCE_RESET for built-in admin username '{}'. Turn HUASHUO_ADMIN_FORCE_RESET back to false after deployment.",
                adminUsername);
    }

    private String resolvePlaintextForNewAdmin(AdminBootstrapProfilePolicy.AdminPasswordPolicy policy) {
        String configured = adminAccessProperties.getPassword();
        if (policy == AdminBootstrapProfilePolicy.AdminPasswordPolicy.STRICT) {
            // 已在 assertStrictProfilePasswordConfigured 校验非空且非弱口令
            return configured.trim();
        }
        if (StringUtils.hasText(configured)) {
            return configured.trim();
        }
        return DEV_FALLBACK_PLAINTEXT_PASSWORD;
    }

    private void warnDevDefaultPasswordIfNeeded(AdminBootstrapProfilePolicy.AdminPasswordPolicy policy, String plaintextForInsert) {
        if (policy != AdminBootstrapProfilePolicy.AdminPasswordPolicy.LENIENT) {
            return;
        }
        if (DEV_FALLBACK_PLAINTEXT_PASSWORD.equals(plaintextForInsert)) {
            log.warn("Using development default administrator password for username '{}'. "
                            + "Do not use this in production; switch to prod/test profiles with HUASHUO_ADMIN_PASSWORD.",
                    resolveConfiguredAdminUsername());
        }
    }

    private String resolveConfiguredAdminUsername() {
        if (!StringUtils.hasText(adminAccessProperties.getUsername())) {
            return "admin";
        }
        return adminAccessProperties.getUsername().trim().toLowerCase(Locale.ROOT);
    }

    private AdminBootstrapProfilePolicy.AdminPasswordPolicy resolveAdminPasswordPolicy() {
        return AdminBootstrapProfilePolicy.resolve(environment.getActiveProfiles());
    }

    private void ensureTaskColumns() throws SQLException {
        if (!tableExists("task")) {
            return;
        }
        addColumnIfMissing("task", "owner_user_id", "bigint");
        addColumnIfMissing("task", "model_code", "varchar(80)");
        addColumnIfMissing("task", "provider", "varchar(50)");
        addColumnIfMissing("task", "usage_unit", "varchar(30)");
        addColumnIfMissing("task", "estimated_usage", "decimal(18,4)");
        addColumnIfMissing("task", "actual_usage", "decimal(18,4)");
        addColumnIfMissing("task", "estimated_credit_cost", "bigint not null default 0");
        addColumnIfMissing("task", "actual_credit_cost", "bigint not null default 0");
        addColumnIfMissing("task", "settlement_status", "varchar(30) not null default 'NONE'");
        addColumnIfMissing("task", "credit_cost", "bigint not null default 0");
        addColumnIfMissing("task", "credit_log_id", "bigint");
        addColumnIfMissing("task", "queue_name", "varchar(80)");
        addColumnIfMissing("task", "message_id", "varchar(120)");
        addColumnIfMissing("task", "idempotency_key", "varchar(120)");
        addColumnIfMissing("task", "priority", "int not null default 0");
        addColumnIfMissing("task", "progress", "int not null default 0");
        addColumnIfMissing("task", "result_asset_id", "bigint");
        addColumnIfMissing("task", "result_viewed", "tinyint(1) not null default 0");
        addColumnIfMissing("task", "started_at", "datetime");
        addColumnIfMissing("task", "finished_at", "datetime");
        addIndexIfMissing("task", "idx_task_owner_user_id",
                "create index idx_task_owner_user_id on task(owner_user_id)");
        addIndexIfMissing("task", "idx_task_model_code",
                "create index idx_task_model_code on task(model_code)");
        addIndexIfMissing("task", "idx_task_provider",
                "create index idx_task_provider on task(provider)");
        addIndexIfMissing("task", "idx_task_settlement_status",
                "create index idx_task_settlement_status on task(settlement_status)");
        addIndexIfMissing("task", "idx_task_owner_status",
                "create index idx_task_owner_status on task(owner_user_id, status)");
        addIndexIfMissing("task", "idx_task_created_at",
                "create index idx_task_created_at on task(created_at)");
    }

    private void ensureProviderOpsTables() throws SQLException {
        if (!tableExists("provider_ops_ticket")) {
            jdbcTemplate.execute("""
                    create table provider_ops_ticket (
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
                    )
                    """);
        }
        if (!tableExists("provider_ops_ticket_action")) {
            jdbcTemplate.execute("""
                    create table provider_ops_ticket_action (
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
                    )
                    """);
        }
    }

    private void ensureUsageBillingTables() throws SQLException {
        if (!tableExists("ai_model_price")) {
            jdbcTemplate.execute("""
                    create table ai_model_price (
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
                    )
                    """);
        }
        addIndexIfMissing("ai_model_price", "idx_ai_model_price_task_type",
                "create index idx_ai_model_price_task_type on ai_model_price(task_type)");
        addIndexIfMissing("ai_model_price", "idx_ai_model_price_model_code",
                "create index idx_ai_model_price_model_code on ai_model_price(model_code)");
        addIndexIfMissing("ai_model_price", "idx_ai_model_price_enabled",
                "create index idx_ai_model_price_enabled on ai_model_price(enabled)");
        addIndexIfMissing("ai_model_price", "idx_ai_model_price_deleted",
                "create index idx_ai_model_price_deleted on ai_model_price(deleted)");
        if (!tableExists("ai_usage_log")) {
            jdbcTemplate.execute("""
                    create table ai_usage_log (
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
                    )
                    """);
        }
        addColumnIfMissing("ai_usage_log", "usage_phase", "varchar(20) not null default 'ACTUAL'");
        addIndexIfMissing("ai_usage_log", "idx_ai_usage_log_task_id",
                "create index idx_ai_usage_log_task_id on ai_usage_log(task_id)");
        addIndexIfMissing("ai_usage_log", "idx_ai_usage_log_user_id",
                "create index idx_ai_usage_log_user_id on ai_usage_log(user_id)");
        addIndexIfMissing("ai_usage_log", "idx_ai_usage_log_model_code",
                "create index idx_ai_usage_log_model_code on ai_usage_log(model_code)");
        addIndexIfMissing("ai_usage_log", "idx_ai_usage_log_usage_phase",
                "create index idx_ai_usage_log_usage_phase on ai_usage_log(usage_phase)");
        addIndexIfMissing("ai_usage_log", "idx_ai_usage_log_created_at",
                "create index idx_ai_usage_log_created_at on ai_usage_log(created_at)");
        addIndexIfMissing("ai_usage_log", "idx_ai_usage_log_deleted",
                "create index idx_ai_usage_log_deleted on ai_usage_log(deleted)");
        seedTokenModelPrice("VOLCENGINE", "text-doubao-default", "Doubao Text Default", "SCRIPT_REWRITE", 0.0, 0.0, 1.2);
        seedTokenModelPrice("VOLCENGINE", "text-doubao-default", "Doubao Text Default", "STORYBOARD_GENERATE", 0.0, 0.0, 1.5);
        seedModelPrice("VOLCENGINE", "tts-doubao-default", "Doubao TTS Default", "TTS_GENERATE", "CHAR", 1.0);
        seedModelPrice("VOLCENGINE", "avatar-seedream-default", "Seedream Avatar Default", "AVATAR_GENERATE", "IMAGE", 5.0);
        seedModelPrice("VIDU", "digital-human-vidu-default", "Vidu Digital Human Default", "DIGITAL_HUMAN_GENERATE", "PROVIDER_CREDIT", 1.0);
    }

    private void ensureCustomerFeedbackTable() throws SQLException {
        if (!tableExists("customer_feedback")) {
            jdbcTemplate.execute("""
                    create table customer_feedback (
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
                    )
                    """);
        }
        addIndexIfMissing("customer_feedback", "idx_customer_feedback_owner_user_id",
                "create index idx_customer_feedback_owner_user_id on customer_feedback(owner_user_id)");
        addIndexIfMissing("customer_feedback", "idx_customer_feedback_status",
                "create index idx_customer_feedback_status on customer_feedback(status)");
        addIndexIfMissing("customer_feedback", "idx_customer_feedback_category",
                "create index idx_customer_feedback_category on customer_feedback(category)");
        addIndexIfMissing("customer_feedback", "idx_customer_feedback_priority",
                "create index idx_customer_feedback_priority on customer_feedback(priority)");
        addIndexIfMissing("customer_feedback", "idx_customer_feedback_related_task_id",
                "create index idx_customer_feedback_related_task_id on customer_feedback(related_task_id)");
        addIndexIfMissing("customer_feedback", "idx_customer_feedback_created_at",
                "create index idx_customer_feedback_created_at on customer_feedback(created_at)");
        addIndexIfMissing("customer_feedback", "idx_customer_feedback_deleted",
                "create index idx_customer_feedback_deleted on customer_feedback(deleted)");
    }

    private void seedModelPrice(String provider, String modelCode, String modelName, String taskType,
                                String usageUnit, double unitCreditPrice) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(1) from ai_model_price where provider = ? and model_code = ? and task_type = ? and deleted = 0",
                Integer.class,
                provider, modelCode, taskType
        );
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.update("""
                insert into ai_model_price(provider, model_code, model_name, task_type, usage_unit, unit_credit_price,
                    estimate_output_ratio, estimate_buffer_ratio, enabled)
                values (?, ?, ?, ?, ?, ?, 1.0000, 1.0000, 1)
                """, provider, modelCode, modelName, taskType, usageUnit, unitCreditPrice);
    }

    private void seedTokenModelPrice(String provider, String modelCode, String modelName, String taskType,
                                     double inputCreditPer1k, double outputCreditPer1k, double outputRatio) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(1) from ai_model_price where provider = ? and model_code = ? and task_type = ? and deleted = 0",
                Integer.class,
                provider, modelCode, taskType
        );
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.update("""
                insert into ai_model_price(provider, model_code, model_name, task_type, usage_unit,
                    input_credit_per_1k, output_credit_per_1k, estimate_output_ratio, estimate_buffer_ratio, enabled)
                values (?, ?, ?, ?, 'TOKEN', ?, ?, ?, 1.2000, 1)
                """, provider, modelCode, modelName, taskType, inputCreditPer1k, outputCreditPer1k, outputRatio);
    }

    private void ensureCreditAccounts() throws SQLException {
        if (!tableExists("user_account") || !tableExists("user_credit_account")) {
            return;
        }
        jdbcTemplate.update("""
                insert into user_credit_account(user_id, balance, frozen_balance, total_recharged, total_consumed)
                select u.user_id, 0, 0, 0, 0
                from user_account u
                where u.deleted = 0
                  and not exists (
                      select 1 from user_credit_account c
                      where c.user_id = u.user_id and c.deleted = 0
                  )
                """);
    }

    private boolean tableExists(String table) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            return hasTable(metaData, table) || hasTable(metaData, table.toUpperCase(Locale.ROOT));
        }
    }

    private boolean hasTable(DatabaseMetaData metaData, String table) throws SQLException {
        try (ResultSet rs = metaData.getTables(null, null, table, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    private boolean columnExists(String table, String column) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            return hasColumn(metaData, table, column)
                    || hasColumn(metaData, table.toUpperCase(Locale.ROOT), column.toUpperCase(Locale.ROOT));
        }
    }

    private boolean hasColumn(DatabaseMetaData metaData, String table, String column) throws SQLException {
        try (ResultSet rs = metaData.getColumns(null, null, table, column)) {
            return rs.next();
        }
    }

    private void addIndexIfMissing(String table, String indexName, String ddl) throws SQLException {
        if (indexExists(table, indexName)) {
            return;
        }
        jdbcTemplate.execute(ddl);
    }

    private boolean indexExists(String table, String indexName) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            return hasIndex(metaData, table, indexName)
                    || hasIndex(metaData, table.toUpperCase(Locale.ROOT), indexName.toUpperCase(Locale.ROOT));
        }
    }

    private boolean hasIndex(DatabaseMetaData metaData, String table, String indexName) throws SQLException {
        try (ResultSet rs = metaData.getIndexInfo(null, null, table, false, false)) {
            while (rs.next()) {
                String existingName = rs.getString("INDEX_NAME");
                if (existingName != null && existingName.equalsIgnoreCase(indexName)) {
                    return true;
                }
            }
        }
        return false;
    }
}
