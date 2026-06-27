package com.huashuo.video.DTO;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class CarSalesAiPlanRequest {
    @NotBlank(message = "prompt is required")
    private String prompt;
    private String carModelName;
    private String carModelSummary;
    private List<String> sellingPoints;
    private String aspectRatio;
    private String voiceLanguage;
    private Integer totalDuration;
    private Integer segmentCount;
    private Integer segmentDuration;
    private String sourceText;
    private Boolean hostAppearanceEnabled;
    private Boolean hasDigitalHuman;
    private String digitalHumanId;
    private String digitalHumanName;
    private String avatarUrl;
    private String hostImageUrl;
    private String audioPolicy;
    private String videoType;
}
