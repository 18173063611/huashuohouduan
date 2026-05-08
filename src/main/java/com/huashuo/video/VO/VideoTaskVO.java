package com.huashuo.video.VO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 视频生成任务查询结果。
 * 与火山方舟「查询视频生成任务」接口的字段对齐：
 * status 取值：queued / running / cancelled / succeeded / failed / expired。
 * 任务成功后，前端取 videoUrl 即可获得最终视频地址。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoTaskVO {

    /** 任务 ID（火山方舟原始返回为 id 字段）。 */
    private String taskId;

    /** 任务实际使用的模型 {模型名称}-{版本}。 */
    private String model;

    /** 任务状态：queued / running / cancelled / succeeded / failed / expired。 */
    private String status;

    /** 任务创建时间，Unix 时间戳（秒）。 */
    private Long createdAt;

    /** 任务最近更新时间，Unix 时间戳（秒）。 */
    private Long updatedAt;

    /** 视频 URL：仅 status=succeeded 时返回。 */
    private String videoUrl;
    private Long resultAssetId;

    /** 视频尾帧 URL：仅在创建任务时设置 return_last_frame=true 且任务成功时返回。 */
    private String lastFrameUrl;

    /** 计费的 completion tokens，便于前端展示消耗。 */
    private Integer completionTokens;

    /** 任务失败时的错误码（仅 status=failed 时有值）。 */
    private String errorCode;

    /** 任务失败时的错误描述（仅 status=failed 时有值）。 */
    private String errorMessage;
}
