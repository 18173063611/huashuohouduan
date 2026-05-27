package com.huashuo.task.mq;

import com.huashuo.avatar.job.AvatarGenerateTaskExecutor;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.video.job.DigitalHumanTaskExecutor;
import com.huashuo.video.job.QuickRenderTaskExecutor;
import com.huashuo.video.job.SeedanceVideoTaskExecutor;
import com.huashuo.voice.job.TtsTaskExecutor;
import com.huashuo.voice.job.VoiceSampleTaskExecutor;
import com.huashuo.writer.job.VideoScriptTaskExecutor;
import com.huashuo.writer.job.WriterTaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class AiTaskExecutionDispatcher {

    private final TtsTaskExecutor ttsTaskExecutor;
    private final AvatarGenerateTaskExecutor avatarGenerateTaskExecutor;
    private final VideoScriptTaskExecutor videoScriptTaskExecutor;
    private final WriterTaskExecutor writerTaskExecutor;
    private final SeedanceVideoTaskExecutor seedanceVideoTaskExecutor;
    private final QuickRenderTaskExecutor quickRenderTaskExecutor;
    private final DigitalHumanTaskExecutor digitalHumanTaskExecutor;
    private final VoiceSampleTaskExecutor voiceSampleTaskExecutor;

    public AiTaskExecutionDispatcher(TtsTaskExecutor ttsTaskExecutor,
                                     AvatarGenerateTaskExecutor avatarGenerateTaskExecutor,
                                     VideoScriptTaskExecutor videoScriptTaskExecutor,
                                     WriterTaskExecutor writerTaskExecutor,
                                     SeedanceVideoTaskExecutor seedanceVideoTaskExecutor,
                                     QuickRenderTaskExecutor quickRenderTaskExecutor,
                                     DigitalHumanTaskExecutor digitalHumanTaskExecutor,
                                     VoiceSampleTaskExecutor voiceSampleTaskExecutor) {
        this.ttsTaskExecutor = ttsTaskExecutor;
        this.avatarGenerateTaskExecutor = avatarGenerateTaskExecutor;
        this.videoScriptTaskExecutor = videoScriptTaskExecutor;
        this.writerTaskExecutor = writerTaskExecutor;
        this.seedanceVideoTaskExecutor = seedanceVideoTaskExecutor;
        this.quickRenderTaskExecutor = quickRenderTaskExecutor;
        this.digitalHumanTaskExecutor = digitalHumanTaskExecutor;
        this.voiceSampleTaskExecutor = voiceSampleTaskExecutor;
    }

    public void dispatch(AiTaskMessage message) {
        if (message == null || message.taskId() == null || message.taskType() == null) {
            throw new BusinessException(40000, "AI task message is invalid");
        }
        String taskType = message.taskType().trim();
        if (taskType.isEmpty()) {
            throw new BusinessException(40000, "AI task message is invalid");
        }
        if (TaskTypeCode.TTS_GENERATE.equals(taskType)) {
            ttsTaskExecutor.run(message.taskId());
            return;
        }
        if (TaskTypeCode.AVATAR_GENERATE.equals(taskType)) {
            avatarGenerateTaskExecutor.run(message.taskId());
            return;
        }
        if (TaskTypeCode.VIDEO_SCRIPT_ANALYZE.equals(taskType)
                || TaskTypeCode.VIDEO_SCRIPT_URL_ANALYZE.equals(taskType)) {
            videoScriptTaskExecutor.run(message.taskId());
            return;
        }
        if (TaskTypeCode.DOUYIN_PARSE_TRANSCRIPT.equals(taskType)
                || TaskTypeCode.DOUYIN_REWRITE.equals(taskType)
                || TaskTypeCode.DOUYIN_TRANSCRIPT.equals(taskType)) {
            writerTaskExecutor.run(message.taskId());
            return;
        }
        if (TaskTypeCode.SEEDANCE_TEXT_VIDEO.equals(taskType)
                || TaskTypeCode.SEEDANCE_FIRST_FRAME_VIDEO.equals(taskType)
                || TaskTypeCode.SEEDANCE_FIRST_LAST_FRAME_VIDEO.equals(taskType)
                || TaskTypeCode.SEEDANCE_REFERENCE_VIDEO.equals(taskType)
                || TaskTypeCode.SEEDANCE_CAR_SALES_VIDEO.equals(taskType)) {
            seedanceVideoTaskExecutor.run(message.taskId());
            return;
        }
        if (TaskTypeCode.DIGITAL_HUMAN_GENERATE.equals(taskType)) {
            digitalHumanTaskExecutor.run(message.taskId());
            return;
        }
        if (TaskTypeCode.QUICK_RENDER.equals(taskType)) {
            quickRenderTaskExecutor.run(message.taskId());
            return;
        }
        if (TaskTypeCode.VOICE_SAMPLE.equals(taskType)) {
            voiceSampleTaskExecutor.run(message.taskId());
            return;
        }
        throw new BusinessException(40000, "Unsupported AI task type: " + taskType);
    }
}
