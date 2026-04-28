package com.huashuo.project;

import com.huashuo.project.dto.FwxCreateProjectRequest;
import com.huashuo.project.vo.FwxProjectItem;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;

@Repository
public class FwxProjectRepository {

    private final JdbcClient jdbcClient;
    private final SimpleJdbcInsert projectInsert;

    public FwxProjectRepository(JdbcClient jdbcClient, org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.jdbcClient = jdbcClient;
        this.projectInsert = new SimpleJdbcInsert(jdbcTemplate)
                .withTableName("project")
                .usingGeneratedKeyColumns("project_id");
    }

    public FwxProjectItem create(FwxCreateProjectRequest request) {
        HashMap<String, Object> values = new HashMap<>();
        values.put("project_name", request.projectName());
        values.put("description", request.description());
        values.put("status", "DRAFT");
        Number generatedId = projectInsert.executeAndReturnKey(new MapSqlParameterSource(values));
        Long projectId = generatedId.longValue();
        return findById(projectId).orElseThrow();
    }

    public List<FwxProjectItem> findPage(int limit, int offset) {
        return jdbcClient.sql("""
                        select project_id, project_name, description, status, created_at, updated_at
                        from project
                        where deleted = false
                        order by updated_at desc, project_id desc
                        limit :limit offset :offset
                        """)
                .param("limit", limit)
                .param("offset", offset)
                .query(FwxProjectItem.class)
                .list();
    }

    public long count() {
        return jdbcClient.sql("select count(1) from project where deleted = false")
                .query(Long.class)
                .single();
    }

    public Optional<FwxProjectItem> findById(Long projectId) {
        return jdbcClient.sql("""
                        select project_id, project_name, description, status, created_at, updated_at
                        from project
                        where project_id = :projectId and deleted = false
                        """)
                .param("projectId", projectId)
                .query(FwxProjectItem.class)
                .optional();
    }
}
