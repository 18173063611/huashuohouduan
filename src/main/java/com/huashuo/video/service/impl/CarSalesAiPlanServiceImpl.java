package com.huashuo.video.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.common.ai.ArkChatResult;
import com.huashuo.common.ai.ArkTextClient;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.video.DTO.CarSalesAiPlanRequest;
import com.huashuo.video.DTO.CarSalesAiPlanResponse;
import com.huashuo.video.service.CarSalesAiPlanService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class CarSalesAiPlanServiceImpl implements CarSalesAiPlanService {

    private final ArkTextClient arkTextClient;
    private final ObjectMapper objectMapper;
    private final String planModel;

    public CarSalesAiPlanServiceImpl(
            ArkTextClient arkTextClient,
            ObjectMapper objectMapper,
            @Value("${volcengine.ark.plan-model:${VOLCENGINE_ARK_PLAN_MODEL:doubao-seed-2-0-pro-260215}}") String planModel
    ) {
        this.arkTextClient = arkTextClient;
        this.objectMapper = objectMapper;
        this.planModel = StringUtils.hasText(planModel) ? planModel.trim() : "doubao-seed-2-0-pro-260215";
    }

    @Override
    public CarSalesAiPlanResponse generate(CarSalesAiPlanRequest request) {
        if (!arkTextClient.available()) {
            throw new BusinessException(50214, "Volcengine Ark is not configured");
        }
        ArkChatResult result = arkTextClient.chat(buildPrompt(request), planModel, Duration.ofSeconds(100));
        CarSalesAiPlanResponse response = parseResponse(result.content(), request);
        response.setModel(result.model());
        return response;
    }

    private String buildPrompt(CarSalesAiPlanRequest request) {
        int segmentCount = clamp(request.getSegmentCount(), 4, 1, 6);
        int totalDuration = clamp(request.getTotalDuration(), segmentCount * 5, 8, 60);
        boolean english = isEnglishLanguage(request.getVoiceLanguage());
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一名汽车销售短视频策划和分镜导演，需要根据用户提示词和车型素材包信息，直接生成可编辑的视频方案。\n");
        prompt.append("这份方案后续会进入老版手动制作同一套 CarSalesVideo Scene 协议，所以分镜必须写成可被视频生成模型执行的导演计划，不要只写泛泛描述。\n");
        prompt.append("只输出严格 JSON，不要 Markdown，不要解释。JSON 格式：\n");
        if (english) {
            prompt.append("{\"script\":\"complete English voiceover copy\",\"storyboard\":[{\"index\":1,\"visual\":\"Shot intent=...; Shot size=...; Camera movement=...; Composition=...; Subject/scene=...; Visual focus=...; Transition=...; Subtitle/big-text post-production suggestion=...; Execution notes=...\",\"narration\":\"English voiceover for this shot\",\"duration\":5}]}\n");
        } else {
            prompt.append("{\"script\":\"完整口播文案\",\"storyboard\":[{\"index\":1,\"visual\":\"镜头意图=...；景别=...；运镜=...；构图=...；主体/场景=...；视觉重点=...；转场=...；字幕/大字报后期建议=...；执行说明=...\",\"narration\":\"本镜头口播\",\"duration\":5}]}\n");
        }
        prompt.append("要求：\n");
        prompt.append("1. ").append(english
                ? "script、storyboard.visual、storyboard.narration 全部输出自然英文，适合海外短视频口播；不要夹杂中文，也不要输出中文字段标签。"
                : "script、storyboard.visual、storyboard.narration 全部输出中文，用于前端展示和用户编辑。").append("\n");
        prompt.append("2. 文案必须围绕用户提示词、车型素材包名称、素材摘要和已选卖点生成；用户提示词只作为创作目标，不要原样复述，不要把系统提示词、模板提示词、JSON 字段或调试信息展示给用户。\n");
        prompt.append("3. 只能使用车型素材包和已选卖点里能确认的信息；不要生成不存在的车型名，不要把 A 车型卖点写给 B 车型。\n");
        prompt.append("4. 不编造价格、优惠、库存、政策、保修、金融方案等未提供事实；如用户只说促销，只能用“到店咨询/了解权益”等安全表达。\n");
        prompt.append("5. 文案开头 3 秒有钩子，中段围绕 2-4 个卖点递进，结尾引导到店咨询或预约试驾；输出必须像真实汽车销售短视频口播，不要像任务说明。\n");
        prompt.append("6. 分镜数量为 ").append(segmentCount).append(" 段，总时长约 ").append(totalDuration).append(" 秒；每段 visual 必须包含镜头意图、景别、运镜、构图、主体/场景、视觉重点、转场、字幕/大字报后期建议和执行说明，narration 要和 script 对齐。\n");
        prompt.append("7. 字幕/大字报是后期叠加建议，不要要求视频模型直接在画面里生成可读文字；画面主体必须优先使用车型素材包图片、参数和卖点。\n");
        prompt.append("8. 适配竖屏/横屏比例：").append(textOrDefault(request.getAspectRatio(), "9:16")).append("。\n");
        prompt.append("\n用户提示词：").append(textOrDefault(request.getPrompt(), "根据车型素材包生成汽车销售视频")).append("\n");
        prompt.append("车型素材包名称：").append(textOrDefault(request.getCarModelName(), "未命名车型素材包")).append("\n");
        if (StringUtils.hasText(request.getCarModelSummary())) {
            prompt.append("车型素材包摘要：").append(limit(request.getCarModelSummary(), 1400)).append("\n");
        }
        if (request.getSellingPoints() != null && !request.getSellingPoints().isEmpty()) {
            prompt.append("已选卖点：").append(String.join("、", request.getSellingPoints())).append("\n");
        }
        if (StringUtils.hasText(request.getSourceText())) {
            prompt.append("页面上下文：").append(limit(request.getSourceText(), 2200)).append("\n");
        }
        return prompt.toString();
    }

    private CarSalesAiPlanResponse parseResponse(String content, CarSalesAiPlanRequest request) {
        try {
            JsonNode root = objectMapper.readTree(extractJsonPayload(content));
            String script = text(root, "script");
            JsonNode shotsNode = root.get("storyboard");
            if (!StringUtils.hasText(script) || shotsNode == null || !shotsNode.isArray() || shotsNode.isEmpty()) {
                throw new BusinessException(50214, "Doubao plan response missing script or storyboard");
            }
            int expectedCount = clamp(request.getSegmentCount(), 4, 1, 6);
            List<CarSalesAiPlanResponse.Shot> shots = new ArrayList<>();
            int max = Math.min(8, shotsNode.size());
            for (int i = 0; i < max; i++) {
                JsonNode item = shotsNode.get(i);
                String visual = firstNonBlank(text(item, "visual"), text(item, "scene"), text(item, "description"));
                String narration = firstNonBlank(text(item, "narration"), text(item, "voiceover"), text(item, "copy"));
                if (!StringUtils.hasText(visual) || !StringUtils.hasText(narration)) {
                    continue;
                }
                CarSalesAiPlanResponse.Shot shot = new CarSalesAiPlanResponse.Shot();
                shot.setIndex(shots.size() + 1);
                shot.setVisual(limit(visual, 900));
                shot.setNarration(limit(narration, 360));
                shot.setDuration(clamp(number(item, "duration", number(item, "estDurationSec", request.getSegmentDuration())), 5, 2, 12));
                shots.add(shot);
            }
            if (shots.isEmpty()) {
                throw new BusinessException(50214, "Doubao plan response has no valid storyboard shots");
            }
            while (shots.size() > expectedCount) {
                shots.remove(shots.size() - 1);
            }
            CarSalesAiPlanResponse response = new CarSalesAiPlanResponse();
            response.setScript(cleanText(script));
            response.setStoryboard(shots);
            return response;
        } catch (JsonProcessingException exception) {
            throw new BusinessException(50214, "Doubao plan response is not valid JSON: " + exception.getMessage());
        }
    }

    private String extractJsonPayload(String content) {
        String value = content == null ? "" : content.trim();
        value = value.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "").trim();
        int start = value.indexOf('{');
        if (start > 0) {
            value = value.substring(start);
        }
        int end = value.lastIndexOf('}');
        if (end >= 0 && end + 1 < value.length()) {
            value = value.substring(0, end + 1);
        }
        return value;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        return value.isTextual() ? value.asText() : value.toString();
    }

    private Integer number(JsonNode node, String field, Integer fallback) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (value.isNumber()) {
            return value.asInt();
        }
        try {
            return Integer.parseInt(value.asText());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private String textOrDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String cleanText(String value) {
        return value == null ? "" : value.trim()
                .replaceAll("^```[a-zA-Z]*\\s*", "")
                .replaceAll("\\s*```$", "")
                .trim();
    }

    private String limit(String value, int maxLength) {
        String text = value == null ? "" : value.trim();
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength).trim();
    }

    private boolean isEnglishLanguage(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String normalized = value.trim().toLowerCase();
        return normalized.startsWith("en") || normalized.contains("english") || normalized.contains("英文");
    }

    private int clamp(Integer value, int fallback, int min, int max) {
        int number = value == null ? fallback : value;
        return Math.max(min, Math.min(max, number));
    }
}
