package com.huashuo.task.mq;

import com.huashuo.avatar.job.AvatarGenerateTaskExecutor;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.video.job.DigitalHumanTaskExecutor;
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
    private final DigitalHumanTaskExecutor digitalHumanTaskExecutor;
    private final VoiceSampleTaskExecutor voiceSampleTaskExecutor;

    public AiTaskExecutionDispatcher(TtsTaskExecutor ttsTaskExecutor,
                                     AvatarGenerateTaskExecutor avatarGenerateTaskExecutor,
                                     VideoScriptTaskExecutor videoScriptTaskExecutor,
                                     WriterTaskExecutor writerTaskExecutor,
                                     SeedanceVideoTaskExecutor seedanceVideoTaskExecutor,
                                     DigitalHumanTaskExecutor digitalHumanTaskExecutor,
                                     VoiceSampleTaskExecutor voiceSampleTaskExecutor) {
        this.ttsTaskExecutor = ttsTaskExecutor;
        this.avatarGenerateTaskExecutor = avatarGenerateTaskExecutor;
        this.videoScriptTaskExecutor = videoScriptTaskExecutor;
        this.writerTaskExecutor = writerTaskExecutor;
        this.seedanceVideoTaskExecutor = seedanceVideoTaskExecutor;
        this.digitalHumanTaskExecutor = digitalHumanTaskExecutor;
        this.voiceSampleTaskExecutor = voiceSampleTaskExecutor;
    }

    public void dispatch(AiTaskMessage message) {
        if (message == null || message.taskId() == null || message.taskType() == null) {
            throw new BusinessException(40000, "AI task message is invalid");
        }
        if (TaskTypeCode.TTS_GENERATE.equals(message.taskType())) {
            ttsTaskExecutor.run(message.taskId());
            return;
        }
        if (TaskTypeCode.AVATAR_GENERATE.equals(message.taskType())) {
            avatarGenerateTaskExecutor.run(message.taskId());
            return;
        }
        if (TaskTypeCode.VIDEO_SCRIPT_ANALYZE.equals(message.taskType())
                || TaskTypeCode.VIDEO_SCRIPT_URL_ANALYZE.equals(message.taskType())) {
            videoScriptTaskExecutor.run(message.taskId());
            return;
        }
        if (TaskTypeCode.DOUYIN_PARSE_TRANSCRIPT.equals(message.taskType())
                || TaskTypeCode.DOUYIN_REWRITE.equals(message.taskType())
                || TaskTypeCode.DOUYIN_TRANSCRIPT.equals(message.taskType())) {
            writerTaskExecutor.run(message.taskId());
            return;
        }
        if (TaskTypeCode.SEEDANCE_TEXT_VIDEO.equals(message.taskType())
                || TaskTypeCode.SEEDANCE_FIRST_FRAME_VIDEO.equals(message.taskType())
                || TaskTypeCode.SEEDANCE_FIRST_LAST_FRAME_VIDEO.equals(message.taskType())
                || TaskTypeCode.SEEDANCE_REFERENCE_VIDEO.equals(message.taskType())) {
            seedanceVideoTaskExecutor.run(message.taskId());
            return;
        }
        if (TaskTypeCode.DIGITAL_HUMAN_GENERATE.equals(message.taskType())) {
            digitalHumanTaskExecutor.run(message.taskId());
            return;
        }
        if (TaskTypeCode.VOICE_SAMPLE.equals(message.taskType())) {
            voiceSampleTaskExecutor.run(message.taskId());
            return;
        }
        throw new BusinessException(40000, "Unsupported AI task type: " + message.taskType());
    }
}
