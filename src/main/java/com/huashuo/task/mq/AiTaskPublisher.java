package com.huashuo.task.mq;

import com.huashuo.task.vo.TaskItem;
import com.huashuo.task.enums.TaskTypeCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class AiTaskPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final boolean enabled;

    public AiTaskPublisher(
            RabbitTemplate rabbitTemplate,
            @Value("${huashuo.ai-task.publisher.enabled:true}") boolean enabled
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.enabled = enabled;
    }

    public void publish(TaskItem task) {
        if (!enabled) {
            return;
        }
        if (task == null || task.taskId() == null) {
            return;
        }
        String taskType = task.taskType() == null ? null : task.taskType().trim();
        if (!supports(taskType)) {
            return;
        }
        AiTaskMessage message = new AiTaskMessage(
                task.taskId(),
                taskType,
                task.ownerUserId(),
                task.traceId()
        );
        rabbitTemplate.convertAndSend(AiTaskQueueNames.EXCHANGE, routingKey(taskType), message);
    }

    public void publishAfterCommit(TaskItem task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(task);
                }
            });
            return;
        }
        publish(task);
    }

    private boolean supports(String taskType) {
        return TaskTypeCode.TTS_GENERATE.equals(taskType)
                || TaskTypeCode.AVATAR_GENERATE.equals(taskType)
                || TaskTypeCode.VIDEO_SCRIPT_ANALYZE.equals(taskType)
                || TaskTypeCode.VIDEO_SCRIPT_URL_ANALYZE.equals(taskType)
                || TaskTypeCode.DOUYIN_PARSE_TRANSCRIPT.equals(taskType)
                || TaskTypeCode.DOUYIN_REWRITE.equals(taskType)
                || TaskTypeCode.DOUYIN_TRANSCRIPT.equals(taskType)
                || TaskTypeCode.SEEDANCE_TEXT_VIDEO.equals(taskType)
                || TaskTypeCode.SEEDANCE_FIRST_FRAME_VIDEO.equals(taskType)
                || TaskTypeCode.SEEDANCE_FIRST_LAST_FRAME_VIDEO.equals(taskType)
                || TaskTypeCode.SEEDANCE_REFERENCE_VIDEO.equals(taskType)
                || TaskTypeCode.SEEDANCE_CAR_SALES_VIDEO.equals(taskType)
                || TaskTypeCode.DIGITAL_HUMAN_GENERATE.equals(taskType)
                || TaskTypeCode.VOICE_SAMPLE.equals(taskType);
    }

    private String routingKey(String taskType) {
        if (TaskTypeCode.TTS_GENERATE.equals(taskType)
                || TaskTypeCode.VOICE_SAMPLE.equals(taskType)) {
            return AiTaskQueueNames.TTS_GENERATE_ROUTING_KEY;
        }
        if (TaskTypeCode.AVATAR_GENERATE.equals(taskType)) {
            return AiTaskQueueNames.AVATAR_GENERATE_ROUTING_KEY;
        }
        if (TaskTypeCode.VIDEO_SCRIPT_ANALYZE.equals(taskType)
                || TaskTypeCode.VIDEO_SCRIPT_URL_ANALYZE.equals(taskType)
                || TaskTypeCode.DOUYIN_REWRITE.equals(taskType)
                || TaskTypeCode.DOUYIN_TRANSCRIPT.equals(taskType)) {
            return AiTaskQueueNames.WRITER_ROUTING_KEY;
        }
        if (TaskTypeCode.SEEDANCE_TEXT_VIDEO.equals(taskType)
                || TaskTypeCode.SEEDANCE_FIRST_FRAME_VIDEO.equals(taskType)
                || TaskTypeCode.SEEDANCE_FIRST_LAST_FRAME_VIDEO.equals(taskType)
                || TaskTypeCode.SEEDANCE_REFERENCE_VIDEO.equals(taskType)
                || TaskTypeCode.SEEDANCE_CAR_SALES_VIDEO.equals(taskType)
                || TaskTypeCode.DIGITAL_HUMAN_GENERATE.equals(taskType)) {
            return AiTaskQueueNames.VIDEO_GENERATE_ROUTING_KEY;
        }
        if (TaskTypeCode.DOUYIN_PARSE_TRANSCRIPT.equals(taskType)) {
            return AiTaskQueueNames.DOUYIN_PARSE_TRANSCRIPT_ROUTING_KEY;
        }
        return AiTaskQueueNames.ROUTING_KEY;
    }
}
