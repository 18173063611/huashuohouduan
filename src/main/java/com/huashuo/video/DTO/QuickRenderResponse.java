package com.huashuo.video.DTO;

import com.huashuo.task.vo.TaskItem;
import lombok.Data;

import java.util.List;

/**
 * 一键成片响应：返回后端最终选择的链路、提交到现有生成服务的标准请求和任务信息。
 */
@Data
public class QuickRenderResponse {

    /**
     * 后端最终选择的生成链路。
     * 取值包括 car_sales、digital_human、general_video、material_mix。
     */
    private String route;

    /**
     * Seedance 类任务的标准任务记录。
     * 汽车销售和通用图生视频会返回该字段，前端可继续按 taskId 跟踪结果。
     */
    private TaskItem task;

    /**
     * 数字人口播任务的提交结果。
     * route 为 digital_human 时返回，前端使用数字人轮询接口查询详情。
     */
    private DigitalHumanGenerateResponse digitalHumanTask;

    /**
     * 后端识别后的素材清单。
     * 前端可用于展示提交后的诊断信息或排查素材角色问题。
     */
    private List<RecognizedAsset> assets;

    /**
     * 后端生成前摘要。
     * 用自然语言说明识别到的素材、选择的链路和关键策略。
     */
    private String summary;

    /**
     * 实际提交给现有生成链路的标准 DTO。
     * 可能是 CarSalesVideoDTO、ImageReferenceDTO 或 DigitalHumanDTO。
     */
    private Object normalizedRequest;

    /**
     * 单个素材的后端识别结果。
     */
    @Data
    public static class RecognizedAsset {

        /**
         * 资产 ID。
         */
        private Long assetId;

        /**
         * 资产文件名。
         */
        private String fileName;

        /**
         * 资产类型，如 IMAGE、AUDIO、VIDEO、JSON、TEXT。
         */
        private String assetType;

        /**
         * MIME 类型。
         */
        private String mimeType;

        /**
         * 最终用于编排的素材角色。
         */
        private String role;

        /**
         * 资产可访问 URL。
         */
        private String url;
    }
}
