package com.huashuo.task.mq;

import com.huashuo.task.vo.TaskItem;
import com.huashuo.task.enums.TaskTypeCode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.connection.CorrelationData.Confirm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class AiTaskPublisher {

    private static final Logger log = LoggerFactory.getLogger(AiTaskPublisher.class);

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
            log.debug("AI task publisher disabled, skip publish taskId={}", task == null ? null : task.taskId());
            return;
        }
        if (task == null || task.taskId() == null) {
            log.debug("AI task publish skipped because task is missing");
            return;
        }
        String taskType = task.taskType() == null ? null : task.taskType().trim();
        if (!supports(taskType)) {
            log.warn("AI task publish skipped unsupported taskType={} taskId={}", taskType, task.taskId());
            return;
        }
        AiTaskMessage message = new AiTaskMessage(
                task.taskId(),
                taskType,
                task.ownerUserId(),
                task.traceId()
        );
        String routingKey = routingKey(taskType);
        CorrelationData correlationData = new CorrelationData("ai-task-" + task.taskId());
        try {
            rabbitTemplate.convertAndSend(AiTaskQueueNames.EXCHANGE, routingKey, message,
                    persistentMessage(task), correlationData);
            verifyPublishConfirmed(task, taskType, routingKey, correlationData);
            log.info("AI task published taskId={} taskType={} ownerUserId={} exchange={} routingKey={} traceId={}",
                    task.taskId(), taskType, task.ownerUserId(), AiTaskQueueNames.EXCHANGE, routingKey, task.traceId());
        } catch (RuntimeException ex) {
            log.warn("AI task publish failed taskId={} taskType={} routingKey={} reason={}",
                    task.taskId(), taskType, routingKey, ex.getMessage());
            throw ex;
        }
    }

    private void verifyPublishConfirmed(TaskItem task, String taskType, String routingKey,
                                        CorrelationData correlationData) {
        try {
            Confirm confirm = correlationData.getFuture().get(5, TimeUnit.SECONDS);
            ReturnedMessage returned = correlationData.getReturned();
            if (returned != null) {
                throw new IllegalStateException("RabbitMQ returned unroutable task message: replyCode="
                        + returned.getReplyCode() + ", replyText=" + returned.getReplyText()
                        + ", exchange=" + returned.getExchange() + ", routingKey=" + returned.getRoutingKey());
            }
            if (confirm != null && !confirm.isAck()) {
                throw new IllegalStateException("RabbitMQ did not confirm task publish: "
                        + (confirm.getReason() == null ? "no reason" : confirm.getReason()));
            }
        } catch (TimeoutException ex) {
            throw new IllegalStateException("RabbitMQ publish confirm timeout for taskId=" + task.taskId()
                    + ", taskType=" + taskType + ", routingKey=" + routingKey, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("RabbitMQ publish confirm interrupted for taskId=" + task.taskId(), ex);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("RabbitMQ publish confirm failed for taskId=" + task.taskId(), ex);
        }
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

    private MessagePostProcessor persistentMessage(TaskItem task) {
        return message -> {
            message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            message.getMessageProperties().setMessageId("ai-task-" + task.taskId());
            if (task.traceId() != null && !task.traceId().isBlank()) {
                message.getMessageProperties().setCorrelationId(task.traceId().trim());
            }
            return message;
        };
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
                || TaskTypeCode.QUICK_RENDER.equals(taskType)
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
        if (TaskTypeCode.QUICK_RENDER.equals(taskType)) {
            return AiTaskQueueNames.QUICK_RENDER_ROUTING_KEY;
        }
        if (TaskTypeCode.DOUYIN_PARSE_TRANSCRIPT.equals(taskType)) {
            return AiTaskQueueNames.DOUYIN_PARSE_TRANSCRIPT_ROUTING_KEY;
        }
        return AiTaskQueueNames.ROUTING_KEY;
    }
}
