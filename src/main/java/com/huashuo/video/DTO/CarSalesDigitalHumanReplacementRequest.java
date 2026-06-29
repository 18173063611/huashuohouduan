package com.huashuo.video.DTO;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Replace only the digital-human reference for a failed car-sales render and resubmit with the original parameters.
 */
@Data
public class CarSalesDigitalHumanReplacementRequest {

    private Long assetId;

    private Long avatarId;

    private String digitalHumanId;

    private String avatarName;

    @NotBlank(message = "hostImageUrl is required")
    private String hostImageUrl;
}
