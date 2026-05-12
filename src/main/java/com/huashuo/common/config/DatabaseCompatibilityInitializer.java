package com.huashuo.common.config;

import com.huashuo.admin.config.AdminAccessProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
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
        ensureTaskColumns();
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
        addColumnIfMissing("task", "model_code", "varchar(80)");
        addColumnIfMissing("task", "credit_cost", "bigint not null default 0");
        addColumnIfMissing("task", "credit_log_id", "bigint");
        addColumnIfMissing("task", "queue_name", "varchar(80)");
        addColumnIfMissing("task", "message_id", "varchar(120)");
        addColumnIfMissing("task", "idempotency_key", "varchar(120)");
        addColumnIfMissing("task", "priority", "int not null default 0");
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
}
