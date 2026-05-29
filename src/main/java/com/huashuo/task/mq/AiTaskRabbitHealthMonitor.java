package com.huashuo.task.mq;

import com.huashuo.task.config.AiTaskProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(prefix = "huashuo.ai-task.rabbit-health", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class AiTaskRabbitHealthMonitor implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AiTaskRabbitHealthMonitor.class);

    private static final List<QueueRoute> CONSUMED_ROUTES = List.of(
            new QueueRoute(AiTaskQueueNames.QUEUE, AiTaskQueueNames.ROUTING_KEY, "aiTaskRegularListener"),
            new QueueRoute(AiTaskQueueNames.TTS_GENERATE_QUEUE, AiTaskQueueNames.TTS_GENERATE_ROUTING_KEY,
                    "aiTaskTtsListener"),
            new QueueRoute(AiTaskQueueNames.WRITER_QUEUE, AiTaskQueueNames.WRITER_ROUTING_KEY,
                    "aiTaskWriterListener"),
            new QueueRoute(AiTaskQueueNames.VIDEO_GENERATE_QUEUE, AiTaskQueueNames.VIDEO_GENERATE_ROUTING_KEY,
                    "aiTaskVideoListener"),
            new QueueRoute(AiTaskQueueNames.QUICK_RENDER_QUEUE, AiTaskQueueNames.QUICK_RENDER_ROUTING_KEY,
                    "aiTaskQuickRenderListener"),
            new QueueRoute(AiTaskQueueNames.AVATAR_GENERATE_QUEUE, AiTaskQueueNames.AVATAR_GENERATE_ROUTING_KEY,
                    "aiTaskAvatarListener"),
            new QueueRoute(AiTaskQueueNames.DOUYIN_PARSE_TRANSCRIPT_QUEUE,
                    AiTaskQueueNames.DOUYIN_PARSE_TRANSCRIPT_ROUTING_KEY,
                    "aiTaskDouyinParseTranscriptListener")
    );

    private static final List<String> SUPPORT_QUEUES = List.of(
            AiTaskQueueNames.RETRY_QUEUE,
            AiTaskQueueNames.TTS_RETRY_QUEUE,
            AiTaskQueueNames.WRITER_RETRY_QUEUE,
            AiTaskQueueNames.VIDEO_RETRY_QUEUE,
            AiTaskQueueNames.QUICK_RENDER_RETRY_QUEUE,
            AiTaskQueueNames.AVATAR_RETRY_QUEUE,
            AiTaskQueueNames.DEAD_QUEUE
    );

    private final RabbitAdmin rabbitAdmin;
    private final RabbitListenerEndpointRegistry listenerRegistry;
    private final AiTaskProperties aiTaskProperties;
    private final boolean autoStartStoppedListeners;
    private final AtomicBoolean listenerDisabledLogged = new AtomicBoolean();

    public AiTaskRabbitHealthMonitor(
            RabbitAdmin rabbitAdmin,
            RabbitListenerEndpointRegistry listenerRegistry,
            AiTaskProperties aiTaskProperties,
            @Value("${huashuo.ai-task.rabbit-health.auto-start-stopped-listeners:true}")
            boolean autoStartStoppedListeners
    ) {
        this.rabbitAdmin = rabbitAdmin;
        this.listenerRegistry = listenerRegistry;
        this.aiTaskProperties = aiTaskProperties;
        this.autoStartStoppedListeners = autoStartStoppedListeners;
    }

    @Override
    public void run(ApplicationArguments args) {
        checkRabbitHealth("startup");
    }

    @Scheduled(
            initialDelayString = "${huashuo.ai-task.rabbit-health.initial-delay-ms:30000}",
            fixedDelayString = "${huashuo.ai-task.rabbit-health.interval-ms:60000}"
    )
    public void checkRabbitHealthOnSchedule() {
        checkRabbitHealth("scheduled");
    }

    private void checkRabbitHealth(String trigger) {
        if (!aiTaskProperties.getListener().isEnabled()) {
            if (listenerDisabledLogged.compareAndSet(false, true)) {
                log.info("AI task Rabbit health check skipped because listeners are disabled. trigger={}", trigger);
            } else {
                log.debug("AI task Rabbit health check skipped because listeners are disabled. trigger={}", trigger);
            }
            return;
        }
        listenerDisabledLogged.set(false);

        declareTopology(trigger);
        for (QueueRoute route : CONSUMED_ROUTES) {
            checkListener(route);
            checkConsumedQueue(route);
        }
        for (String queueName : SUPPORT_QUEUES) {
            checkSupportQueue(queueName);
        }
    }

    private void declareTopology(String trigger) {
        try {
            rabbitAdmin.initialize();
            log.debug("AI task RabbitMQ topology declaration verified. trigger={}", trigger);
        } catch (Exception ex) {
            log.error("AI task RabbitMQ topology declaration failed. trigger={} reason={}",
                    trigger, ex.getMessage(), ex);
        }
    }

    private void checkListener(QueueRoute route) {
        MessageListenerContainer container = listenerRegistry.getListenerContainer(route.listenerId());
        if (container == null) {
            log.error("AI task RabbitMQ listener is not registered. listenerId={} queue={} routingKey={}",
                    route.listenerId(), route.queueName(), route.routingKey());
            return;
        }

        if (!container.isRunning()) {
            log.error("AI task RabbitMQ listener is stopped. listenerId={} queue={} routingKey={} autoStart={}",
                    route.listenerId(), route.queueName(), route.routingKey(), autoStartStoppedListeners);
            if (autoStartStoppedListeners) {
                startContainer(route, container);
            }
            return;
        }

        if (container instanceof SimpleMessageListenerContainer simpleContainer
                && simpleContainer.getActiveConsumerCount() <= 0) {
            log.warn("AI task RabbitMQ listener is running but has no active local consumers. "
                            + "listenerId={} queue={} routingKey={}",
                    route.listenerId(), route.queueName(), route.routingKey());
        }
    }

    private void startContainer(QueueRoute route, MessageListenerContainer container) {
        try {
            container.start();
            log.warn("AI task RabbitMQ listener restart requested. listenerId={} queue={} routingKey={}",
                    route.listenerId(), route.queueName(), route.routingKey());
        } catch (Exception ex) {
            log.error("AI task RabbitMQ listener restart failed. listenerId={} queue={} routingKey={} reason={}",
                    route.listenerId(), route.queueName(), route.routingKey(), ex.getMessage(), ex);
        }
    }

    private void checkConsumedQueue(QueueRoute route) {
        QueueInformation info = queueInfo(route.queueName(), route.routingKey(), route.listenerId());
        if (info == null) {
            return;
        }

        if (info.getConsumerCount() <= 0) {
            if (info.getMessageCount() > 0) {
                log.error("AI task RabbitMQ queue has messages but no broker consumers. "
                                + "queue={} routingKey={} listenerId={} messages={}",
                        route.queueName(), route.routingKey(), route.listenerId(), info.getMessageCount());
            } else {
                log.warn("AI task RabbitMQ queue has no broker consumers. queue={} routingKey={} listenerId={}",
                        route.queueName(), route.routingKey(), route.listenerId());
            }
        } else {
            log.debug("AI task RabbitMQ queue healthy. queue={} routingKey={} consumers={} messages={}",
                    route.queueName(), route.routingKey(), info.getConsumerCount(), info.getMessageCount());
        }
    }

    private void checkSupportQueue(String queueName) {
        QueueInformation info = queueInfo(queueName, null, null);
        if (info == null) {
            return;
        }
        if (AiTaskQueueNames.DEAD_QUEUE.equals(queueName) && info.getMessageCount() > 0) {
            log.error("AI task RabbitMQ dead queue has message(s). queue={} messages={}",
                    queueName, info.getMessageCount());
        } else {
            log.debug("AI task RabbitMQ support queue verified. queue={} messages={}",
                    queueName, info.getMessageCount());
        }
    }

    private QueueInformation queueInfo(String queueName, String routingKey, String listenerId) {
        try {
            QueueInformation info = rabbitAdmin.getQueueInfo(queueName);
            if (info == null) {
                log.error("AI task RabbitMQ queue is missing. queue={} routingKey={} listenerId={}",
                        queueName, routingKey, listenerId);
            }
            return info;
        } catch (Exception ex) {
            log.error("AI task RabbitMQ queue inspection failed. queue={} routingKey={} listenerId={} reason={}",
                    queueName, routingKey, listenerId, ex.getMessage(), ex);
            return null;
        }
    }

    private record QueueRoute(String queueName, String routingKey, String listenerId) {
    }
}
