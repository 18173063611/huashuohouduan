package com.huashuo.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "huashuo.datasource.warmup", name = "enabled", havingValue = "true")
public class DataSourceWarmupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSourceWarmupRunner.class);

    private final DataSource dataSource;
    private final boolean required;
    private final String validationQuery;

    public DataSourceWarmupRunner(
            DataSource dataSource,
            @Value("${huashuo.datasource.warmup.required:true}") boolean required,
            @Value("${huashuo.datasource.warmup.validation-query:select 1}") String validationQuery
    ) {
        this.dataSource = dataSource;
        this.required = required;
        this.validationQuery = validationQuery;
    }

    @Override
    public void run(ApplicationArguments args) {
        long startedAt = System.nanoTime();
        try (Connection connection = dataSource.getConnection()) {
            validate(connection);
            log.info("DataSource warmup completed in {} ms.", elapsedMillis(startedAt));
        } catch (SQLException e) {
            if (required) {
                throw new IllegalStateException("DataSource warmup failed. Check remote MySQL connectivity and credentials.", e);
            }
            log.warn("DataSource warmup failed after {} ms: {}", elapsedMillis(startedAt), e.getMessage());
        }
    }

    private void validate(Connection connection) throws SQLException {
        if (!StringUtils.hasText(validationQuery)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute(validationQuery);
        }
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
