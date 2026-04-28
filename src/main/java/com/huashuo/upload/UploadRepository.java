package com.huashuo.upload;

import com.huashuo.upload.vo.UploadedFileItem;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class UploadRepository {

    private final JdbcClient jdbcClient;

    public UploadRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void save(Long projectId, String originalFileName, String storedFileName, String filePath,
                     String previewUrl, String mimeType, long fileSize) {
        jdbcClient.sql("""
                        insert into uploaded_file(
                            project_id, original_file_name, stored_file_name, file_path,
                            preview_url, mime_type, file_size
                        )
                        values (
                            :projectId, :originalFileName, :storedFileName, :filePath,
                            :previewUrl, :mimeType, :fileSize
                        )
                        """)
                .param("projectId", projectId)
                .param("originalFileName", originalFileName)
                .param("storedFileName", storedFileName)
                .param("filePath", filePath)
                .param("previewUrl", previewUrl)
                .param("mimeType", mimeType)
                .param("fileSize", fileSize)
                .update();
    }

    public List<UploadedFileItem> findByProjectId(Long projectId) {
        return jdbcClient.sql("""
                        select file_id, project_id, original_file_name, stored_file_name, file_path,
                               preview_url, mime_type, file_size, created_at
                        from uploaded_file
                        where project_id = :projectId and deleted = false
                        order by file_id desc
                        """)
                .param("projectId", projectId)
                .query(UploadedFileItem.class)
                .list();
    }
}
