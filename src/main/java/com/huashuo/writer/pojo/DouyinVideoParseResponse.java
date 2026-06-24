package com.huashuo.writer.pojo;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class DouyinVideoParseResponse{
    String videoId;
    String playUrl;
    String title;
    DouyinAuthorInfo author;
    String coverUrl;
    Long durationSeconds;
    String sourceEndpoint;
    String requestId;
    JsonNode rawData;
    List<ReferenceStructureShot> referenceStructure;

    public DouyinVideoParseResponse(String videoId, String playUrl, String title, DouyinAuthorInfo author,
                                    String coverUrl, Long durationSeconds, String sourceEndpoint,
                                    String requestId, JsonNode rawData) {
        this(videoId, playUrl, title, author, coverUrl, durationSeconds, sourceEndpoint, requestId, rawData, null);
    }

    public DouyinVideoParseResponse(String videoId, String playUrl, String title, DouyinAuthorInfo author,
                                    String coverUrl, Long durationSeconds, String sourceEndpoint,
                                    String requestId, JsonNode rawData,
                                    List<ReferenceStructureShot> referenceStructure) {
        this.videoId = videoId;
        this.playUrl = playUrl;
        this.title = title;
        this.author = author;
        this.coverUrl = coverUrl;
        this.durationSeconds = durationSeconds;
        this.sourceEndpoint = sourceEndpoint;
        this.requestId = requestId;
        this.rawData = rawData;
        this.referenceStructure = referenceStructure;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReferenceStructureShot {
        Integer index;
        Double startSec;
        Double endSec;
        Double durationSec;
        String visualSummary;
        String cameraMotion;
        String sceneType;
        String keyframeUrl;
        String asrTextSpan;
        String ocrText;
        Boolean hasSpeech;
        Double audioEnergy;
        String source;
        Double confidence;
    }
}
