package com.huashuo.admin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminTaskManualRetryRequest(
        @NotNull
        Boolean approved,

        Boolean confirmProviderResolved,

        @Size(max = 120)
        String supplierTicketId,

        @Size(max = 1000)
        String remark
) {
}
