package com.huashuo.task.mq;

import com.huashuo.common.exception.BusinessException;
import com.huashuo.task.enums.TaskStatusCode;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class AiTaskConsumer {

    private static final Logger log = LoggerFactory.getLogger(AiTaskConsumer.class);

    private final TaskService taskService;
    private final AiTaskExecutionDispatcher dispatcher;
    private final TaskExecutionGuard executionGuard;
    private final RabbitTemplate rabbitTemplate;

    public AiTaskConsumer(TaskService taskService,
                          AiTaskExecutionDispatcher dispatcher,
                          TaskExecutionGuard executionGuard,
                          RabbitTemplate rabbitTemplate) {
        this.taskService = taskService;
        this.dispatcher = dispatcher;
        this.executionGuard = executionGuard;
        this.rabbitTemplate = rabbitTemplate;
    }

    @RabbitListener(
            id = "aiTaskRegularListener",
            queues = AiTaskQueueNames.QUEUE,
            containerFactory = "aiTaskRabbitListenerContainerFactory",
            autoStartup = "${huashuo.ai-task.listener.enabled:true}"
    )
    public void consume(AiTaskMessage message, Channel channel,
                        @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        consumeMessage(message, channel, deliveryTag);
    }

    @RabbitListener(
            id = "aiTaskTtsListener",
            queues = AiTaskQueueNames.TTS_GENERATE_QUEUE,
            containerFactory = "ttsRabbitListenerContainerFactory",
            autoStartup = "${huashuo.ai-task.listener.enabled:true}"
    )
    public void consumeTts(AiTaskMessage message, Channel channel,
                           @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        consumeMessage(message, channel, deliveryTag);
    }

    @RabbitListener(
            id = "aiTaskWriterListener",
            queues = AiTaskQueueNames.WRITER_QUEUE,
            containerFactory = "writerRabbitListenerContainerFactory",
            autoStartup = "${huashuo.ai-task.listener.enabled:true}"
    )
    public void consumeWriter(AiTaskMessage message, Channel channel,
                              @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        consumeMessage(message, channel, deliveryTag);
    }

    @RabbitListener(
            id = "aiTaskVideoListener",
            queues = AiTaskQueueNames.VIDEO_GENERATE_QUEUE,
            containerFactory = "videoRabbitListenerContainerFactory",
            autoStartup = "${huashuo.ai-task.listener.enabled:true}"
    )
    public void consumeVideo(AiTaskMessage message, Channel channel,
                             @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        consumeMessage(message, channel, deliveryTag);
    }

    @RabbitListener(
            id = "aiTaskQuickRenderListener",
            queues = AiTaskQueueNames.QUICK_RENDER_QUEUE,
            containerFactory = "quickRenderRabbitListenerContainerFactory",
            autoStartup = "${huashuo.ai-task.listener.enabled:true}"
    )
    public void consumeQuickRender(AiTaskMessage message, Channel channel,
                                   @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        consumeMessage(message, channel, deliveryTag);
    }

    @RabbitListener(
            id = "aiTaskAvatarListener",
            queues = AiTaskQueueNames.AVATAR_GENERATE_QUEUE,
            containerFactory = "avatarRabbitListenerContainerFactory",
            autoStartup = "${huashuo.ai-task.listener.enabled:true}"
    )
    public void consumeAvatar(AiTaskMessage message, Channel channel,
                              @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        consumeMessage(message, channel, deliveryTag);
    }

    @RabbitListener(
            id = "aiTaskDouyinParseTranscriptListener",
            queues = AiTaskQueueNames.DOUYIN_PARSE_TRANSCRIPT_QUEUE,
            containerFactory = "douyinParseTranscriptRabbitListenerContainerFactory",
            autoStartup = "${huashuo.ai-task.listener.enabled:true}"
    )
    public void consumeDouyinParseTranscript(AiTaskMessage message, Channel channel,
                                             @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        consumeMessage(message, channel, deliveryTag);
    }

    void consumeMessage(AiTaskMessage message, Channel channel, long deliveryTag) throws IOException {
        if (message == null || message.taskId() == null) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        TaskItem task;
        try {
            task = taskService.getTask(message.taskId());
        } catch (Exception ex) {
            log.warn("AI task {} not found, drop message: {}", message.taskId(), ex.getMessage());
            channel.basicAck(deliveryTag, false);
            return;
        }

        log.info("AI task message received taskId={} messageType={} dbType={} dbStatus={} retryCount={}",
                message.taskId(), message.taskType(), task.taskType(), task.status(), task.retryCount());
        if (isTerminalOrRunning(task.status())) {
            log.info("AI task message skipped taskId={} status={} reason=already-terminal-or-running",
                    task.taskId(), task.status());
            channel.basicAck(deliveryTag, false);
            return;
        }

        AiTaskMessage effectiveMessage = effectiveMessage(message, task);
        long started = System.currentTimeMillis();
        try {
            log.info("AI task dispatch start taskId={} taskType={} ownerUserId={}",
                    effectiveMessage.taskId(), effectiveMessage.taskType(), effectiveMessage.ownerUserId());
            executionGuard.run(effectiveMessage.taskType(), effectiveMessage.taskId(),
                    () -> dispatcher.dispatch(effectiveMessage));
            log.info("AI task dispatch completed taskId={} taskType={} costMs={}",
                    effectiveMessage.taskId(), effectiveMessage.taskType(), System.currentTimeMillis() - started);
            channel.basicAck(deliveryTag, false);
        } catch (BusinessException ex) {
            log.warn("AI task dispatch business failure taskId={} taskType={} costMs={} reason={}",
                    effectiveMessage.taskId(), effectiveMessage.taskType(), System.currentTimeMillis() - started,
                    ex.getMessage());
            markPermanentFailure(effectiveMessage, ex.getMessage());
            channel.basicAck(deliveryTag, false);
        } catch (RuntimeException ex) {
            log.warn("AI task dispatch retryable failure taskId={} taskType={} costMs={} reason={}",
                    effectiveMessage.taskId(), effectiveMessage.taskType(), System.currentTimeMillis() - started,
                    ex.getMessage());
            // RetryableException 以及其他未知 RuntimeException 都视为可重试
            handleRetryable(effectiveMessage, ex.getMessage(), channel, deliveryTag);
        } finally {
            TaskFailureRefundHint.clear();
        }
    }

    private AiTaskMessage effectiveMessage(AiTaskMessage message, TaskItem task) {
        String taskType = hasText(task.taskType()) ? task.taskType().trim() : trimToNull(message.taskType());
        Long ownerUserId = task.ownerUserId() != null ? task.ownerUserId() : message.ownerUserId();
        String traceId = hasText(task.traceId()) ? task.traceId().trim() : message.traceId();
        return new AiTaskMessage(task.taskId(), taskType, ownerUserId, traceId);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private boolean isTerminalOrRunning(String status) {
        return TaskStatusCode.SUCCESS.equals(status)
                || TaskStatusCode.FAILED.equals(status)
                || TaskStatusCode.CANCELED.equals(status)
                || TaskStatusCode.RUNNING.equals(status);
    }

    private void handleRetryable(AiTaskMessage msg, String errorMsg, Channel channel, long deliveryTag) throws IOException {
        // SSE 任务（抖音解析+转写）的客户端连接在首次失败时已断开，重试用户也看不到，直接终态失败。
        if (TaskTypeCode.DOUYIN_PARSE_TRANSCRIPT.equals(msg.taskType())) {
            markPermanentFailure(msg, errorMsg);
            channel.basicAck(deliveryTag, false);
            return;
        }

        TaskItem task;
        try {
            task = taskService.getTask(msg.taskId());
        } catch (Exception ex) {
            log.warn("AI task {} disappeared while classifying failure: {}", msg.taskId(), ex.getMessage());
            channel.basicAck(deliveryTag, false);
            return;
        }

        int current = task.retryCount() == null ? 0 : task.retryCount();
        if (current >= AiTaskQueueNames.MAX_AUTO_RETRY) {
            log.warn("AI task {} reached max auto-retry ({}), routing to dead queue: {}",
                    msg.taskId(), current, errorMsg);
            markPermanentFailure(msg, errorMsg);
            sendToDeadQueue(msg);
            channel.basicAck(deliveryTag, false);
            return;
        }

        markRetryableFailure(msg, errorMsg);
        try {
            taskService.incrementRetryCount(msg.taskId());
        } catch (Exception ex) {
            log.warn("Failed to increment retry_count for task {}: {}", msg.taskId(), ex.getMessage());
        }
        // requeue=false 让消息走 DLX → retry queue → TTL 到期回主队列
        channel.basicNack(deliveryTag, false, false);
    }

    private void markPermanentFailure(AiTaskMessage msg, String errorMsg) {
        boolean refund = TaskFailureRefundHint.getOrDefault(true);
        try {
            taskService.failTask(msg.taskId(), safeMessage(errorMsg), false, refund);
        } catch (Exception ex) {
            log.warn("Failed to mark task {} as FAILED: {}", msg.taskId(), ex.getMessage());
        }
    }

    private void markRetryableFailure(AiTaskMessage msg, String errorMsg) {
        // 中间态：本次失败将自动重试，retry queue 30s 后回主队列重新消费，先不退款。
        try {
            taskService.failTask(msg.taskId(), safeMessage(errorMsg), true, false);
        } catch (Exception ex) {
            log.warn("Failed to mark task {} as RETRYABLE: {}", msg.taskId(), ex.getMessage());
        }
    }

    private void sendToDeadQueue(AiTaskMessage msg) {
        try {
            MessagePostProcessor persistent = m -> {
                m.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                return m;
            };
            rabbitTemplate.convertAndSend(
                    AiTaskQueueNames.DLX_EXCHANGE,
                    AiTaskQueueNames.DEAD_ROUTING_KEY,
                    msg,
                    persistent
            );
        } catch (Exception ex) {
            log.error("Failed to send task {} to dead queue", msg.taskId(), ex);
        }
    }

    private String safeMessage(String message) {
        return (message == null || message.isBlank()) ? "AI 任务执行失败" : message;
    }
}
