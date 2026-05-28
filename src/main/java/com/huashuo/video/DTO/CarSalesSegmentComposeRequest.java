package com.huashuo.video.DTO;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class CarSalesSegmentComposeRequest {

    @Valid
    @NotEmpty(message = "segments is required")
    @Size(max = 12, message = "segments cannot exceed 12")
    private List<Segment> segments;

    @Data
    public static class Segment {
        private Long assetId;
        private String videoUrl;
        private String title;
    }
}
