package com.huashuo.admin.dto;

import jakarta.validation.constraints.Size;

public record AdminTaskManualRetryApplyRequest(
        @Size(max = 120)
        String supplierTicketId,

        @Size(max = 1000)
        String remark
) {
}
