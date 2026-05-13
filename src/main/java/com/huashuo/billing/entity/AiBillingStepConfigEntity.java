package com.huashuo.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 功能计费步骤配置：按 task_type + 步骤 维护单步建议积分，供任务创建时汇总扣费。
 * <p>对应《AI 功能成本与积分统计表》：每行=一个功能模块的一个步骤。task_type 必须与 {@link com.huashuo.task.enums.TaskTypeCode}
 * 中已定义的任务类型一一对应，function_module / step_name / cost_text 仅用于后台展示。</p>
 */
@Data
@TableName("ai_billing_step_config")
public class AiBillingStepConfigEntity {

    @TableId(value = "step_id", type = IdType.AUTO)
    private Long stepId;

    /** 与 {@link com.huashuo.task.enums.TaskTypeCode} 中的常量一致，例如 TTS_GENERATE。 */
    private String taskType;

    /** 后台分组展示用：例如 “抖音解析”、“爆款对标”、“文案改写”。 */
    private String functionModule;

    /** 步骤名称：例如 “文本转语音”、“ASR 音频转写”。 */
    private String stepName;

    /** 第三方服务/厂商，例如 VOLCENGINE、TIKHUB、VIDU、LOCAL_FFMPEG。 */
    private String provider;

    /** 模型/API 编码：例如 doubao-seed-2-0-mini-260215、tikhub-api。 */
    private String modelCode;

    /** 计量单位：TOKEN / CHAR / IMAGE / SECOND / PROVIDER_CREDIT / TASK，参见 {@link com.huashuo.billing.model.UsageUnit}。 */
    private String usageUnit;

    /** 调用次数文案：例如 “1 次”、“1 次 + 多次轮询”。仅描述，不参与汇总计算。 */
    private String callCount;

    /** 单次实际成本描述：例如 “平均 1 元/百万 Token”“0.22 元/张”。 */
    private String costText;

    /** 第一版直接采用《AI 功能成本与积分统计表》中“建议积分”，汇总后作为任务扣费总积分。 */
    private Long creditCost;

    /** 1=启用并参与汇总；0=暂停（后台可关）。仅启用项参与 task 创建时积分汇总。 */
    private Integer enabled;

    /** 后台展示与同一 task_type 内排序用。 */
    private Integer sortOrder;

    /** 备注，可写文档/统计表中的“备注”列。 */
    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
