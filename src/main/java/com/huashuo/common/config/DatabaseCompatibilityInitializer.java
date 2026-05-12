package com.huashuo.common.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;

@Component
public class DatabaseCompatibilityInitializer implements ApplicationRunner {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public DatabaseCompatibilityInitializer(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
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

    private void ensureDefaultAdmin() throws SQLException {
        if (!tableExists("user_account")) {
            return;
        }
        Integer count = jdbcTemplate.queryForObject(
                "select count(1) from user_account where username = 'admin' and deleted = 0",
                Integer.class
        );
        if (count != null && count > 0) {
            jdbcTemplate.update("update user_account set role = 'ADMIN', status = 'ENABLED' where username = 'admin'");
            return;
        }
        jdbcTemplate.update("""
                insert into user_account(username, password_hash, display_name, role, status)
                values ('admin', '$2a$10$Gq2eqLyRndHwjf8gXD9Pc.sPRr2KfmMqmeUVOVmZ1LwwXuiF99mKC',
                        '系统管理员', 'ADMIN', 'ENABLED')
                """);
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
            return hasTable(metaData, table) || hasTable(metaData, table.toUpperCase());
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
                    || hasColumn(metaData, table.toUpperCase(), column.toUpperCase());
        }
    }

    private boolean hasColumn(DatabaseMetaData metaData, String table, String column) throws SQLException {
        try (ResultSet rs = metaData.getColumns(null, null, table, column)) {
            return rs.next();
        }
    }
}
