package com.huashuo.video.DTO;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CarSalesSegmentAdoptRequest {
    @NotNull(message = "regeneratedTaskId is required")
    private Long regeneratedTaskId;
}
