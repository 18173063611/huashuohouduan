package com.huashuo.video.DTO;

import lombok.Data;

import java.util.List;

@Data
public class CarSalesAiPlanResponse {
    private String script;
    private List<Shot> storyboard;
    private String model;

    @Data
    public static class Shot {
        private Integer index;
        private String visual;
        private String narration;
        private Integer duration;
    }
}
