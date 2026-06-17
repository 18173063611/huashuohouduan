package com.huashuo.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public record AdminTaskProviderOpsUpdateRequest(
        @NotBlank
        @Size(max = 40)
        String opsStatus,

        @Size(max = 20)
        String priority,

        Long assigneeAdminId,

        LocalDateTime slaDeadlineAt,

        @Size(max = 120)
        String supplierTicketId,

        @Size(max = 1000)
        String remark,

        String supplierResponse,

        String attachmentJson
) {
}
