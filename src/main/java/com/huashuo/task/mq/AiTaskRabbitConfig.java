package com.huashuo.task.mq;

import com.huashuo.task.config.AiTaskProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
public class AiTaskRabbitConfig {

    private final AiTaskProperties aiTaskProperties;

    public AiTaskRabbitConfig(AiTaskProperties aiTaskProperties) {
        this.aiTaskProperties = aiTaskProperties;
    }

    @Bean
    public DirectExchange aiTaskExchange() {
        return new DirectExchange(AiTaskQueueNames.EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange aiTaskDlxExchange() {
        return new DirectExchange(AiTaskQueueNames.DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue aiTaskQueue() {
        return QueueBuilder.durable(AiTaskQueueNames.QUEUE)
                .withArgument("x-dead-letter-exchange", AiTaskQueueNames.DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", AiTaskQueueNames.RETRY_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue ttsGenerateQueue() {
        return retryableQueue(AiTaskQueueNames.TTS_GENERATE_QUEUE, AiTaskQueueNames.TTS_RETRY_ROUTING_KEY);
    }

    @Bean
    public Queue writerQueue() {
        return retryableQueue(AiTaskQueueNames.WRITER_QUEUE, AiTaskQueueNames.WRITER_RETRY_ROUTING_KEY);
    }

    @Bean
    public Queue videoGenerateQueue() {
        return retryableQueue(AiTaskQueueNames.VIDEO_GENERATE_QUEUE, AiTaskQueueNames.VIDEO_RETRY_ROUTING_KEY);
    }

    @Bean
    public Queue avatarGenerateQueue() {
        return retryableQueue(AiTaskQueueNames.AVATAR_GENERATE_QUEUE, AiTaskQueueNames.AVATAR_RETRY_ROUTING_KEY);
    }

    @Bean
    public Queue douyinParseTranscriptQueue() {
        return QueueBuilder.durable(AiTaskQueueNames.DOUYIN_PARSE_TRANSCRIPT_QUEUE)
                .withArgument("x-dead-letter-exchange", AiTaskQueueNames.DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", AiTaskQueueNames.DEAD_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue aiTaskRetryQueue() {
        return retryQueue(AiTaskQueueNames.RETRY_QUEUE, AiTaskQueueNames.ROUTING_KEY);
    }

    @Bean
    public Queue ttsRetryQueue() {
        return retryQueue(AiTaskQueueNames.TTS_RETRY_QUEUE, AiTaskQueueNames.TTS_GENERATE_ROUTING_KEY);
    }

    @Bean
    public Queue writerRetryQueue() {
        return retryQueue(AiTaskQueueNames.WRITER_RETRY_QUEUE, AiTaskQueueNames.WRITER_ROUTING_KEY);
    }

    @Bean
    public Queue videoRetryQueue() {
        return retryQueue(AiTaskQueueNames.VIDEO_RETRY_QUEUE, AiTaskQueueNames.VIDEO_GENERATE_ROUTING_KEY);
    }

    @Bean
    public Queue avatarRetryQueue() {
        return retryQueue(AiTaskQueueNames.AVATAR_RETRY_QUEUE, AiTaskQueueNames.AVATAR_GENERATE_ROUTING_KEY);
    }

    @Bean
    public Queue aiTaskDeadQueue() {
        return QueueBuilder.durable(AiTaskQueueNames.DEAD_QUEUE).build();
    }

    @Bean
    public Binding aiTaskBinding(Queue aiTaskQueue, DirectExchange aiTaskExchange) {
        return BindingBuilder.bind(aiTaskQueue)
                .to(aiTaskExchange)
                .with(AiTaskQueueNames.ROUTING_KEY);
    }

    @Bean
    public Binding ttsGenerateBinding(Queue ttsGenerateQueue, DirectExchange aiTaskExchange) {
        return BindingBuilder.bind(ttsGenerateQueue)
                .to(aiTaskExchange)
                .with(AiTaskQueueNames.TTS_GENERATE_ROUTING_KEY);
    }

    @Bean
    public Binding writerBinding(Queue writerQueue, DirectExchange aiTaskExchange) {
        return BindingBuilder.bind(writerQueue)
                .to(aiTaskExchange)
                .with(AiTaskQueueNames.WRITER_ROUTING_KEY);
    }

    @Bean
    public Binding videoGenerateBinding(Queue videoGenerateQueue, DirectExchange aiTaskExchange) {
        return BindingBuilder.bind(videoGenerateQueue)
                .to(aiTaskExchange)
                .with(AiTaskQueueNames.VIDEO_GENERATE_ROUTING_KEY);
    }

    @Bean
    public Binding avatarGenerateBinding(Queue avatarGenerateQueue, DirectExchange aiTaskExchange) {
        return BindingBuilder.bind(avatarGenerateQueue)
                .to(aiTaskExchange)
                .with(AiTaskQueueNames.AVATAR_GENERATE_ROUTING_KEY);
    }

    @Bean
    public Binding douyinParseTranscriptBinding(Queue douyinParseTranscriptQueue,
                                                DirectExchange aiTaskExchange) {
        return BindingBuilder.bind(douyinParseTranscriptQueue)
                .to(aiTaskExchange)
                .with(AiTaskQueueNames.DOUYIN_PARSE_TRANSCRIPT_ROUTING_KEY);
    }

    @Bean
    public Binding aiTaskRetryBinding(Queue aiTaskRetryQueue, DirectExchange aiTaskDlxExchange) {
        return BindingBuilder.bind(aiTaskRetryQueue)
                .to(aiTaskDlxExchange)
                .with(AiTaskQueueNames.RETRY_ROUTING_KEY);
    }

    @Bean
    public Binding ttsRetryBinding(Queue ttsRetryQueue, DirectExchange aiTaskDlxExchange) {
        return BindingBuilder.bind(ttsRetryQueue)
                .to(aiTaskDlxExchange)
                .with(AiTaskQueueNames.TTS_RETRY_ROUTING_KEY);
    }

    @Bean
    public Binding writerRetryBinding(Queue writerRetryQueue, DirectExchange aiTaskDlxExchange) {
        return BindingBuilder.bind(writerRetryQueue)
                .to(aiTaskDlxExchange)
                .with(AiTaskQueueNames.WRITER_RETRY_ROUTING_KEY);
    }

    @Bean
    public Binding videoRetryBinding(Queue videoRetryQueue, DirectExchange aiTaskDlxExchange) {
        return BindingBuilder.bind(videoRetryQueue)
                .to(aiTaskDlxExchange)
                .with(AiTaskQueueNames.VIDEO_RETRY_ROUTING_KEY);
    }

    @Bean
    public Binding avatarRetryBinding(Queue avatarRetryQueue, DirectExchange aiTaskDlxExchange) {
        return BindingBuilder.bind(avatarRetryQueue)
                .to(aiTaskDlxExchange)
                .with(AiTaskQueueNames.AVATAR_RETRY_ROUTING_KEY);
    }

    @Bean
    public Binding aiTaskDeadBinding(Queue aiTaskDeadQueue, DirectExchange aiTaskDlxExchange) {
        return BindingBuilder.bind(aiTaskDeadQueue)
                .to(aiTaskDlxExchange)
                .with(AiTaskQueueNames.DEAD_ROUTING_KEY);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter
    ) {
        return listenerContainerFactory(connectionFactory, jsonMessageConverter,
                aiTaskProperties.getListener().getRegular());
    }

    @Bean
    public SimpleRabbitListenerContainerFactory aiTaskRabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter
    ) {
        return listenerContainerFactory(connectionFactory, jsonMessageConverter,
                aiTaskProperties.getListener().getRegular());
    }

    @Bean
    public SimpleRabbitListenerContainerFactory ttsRabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter
    ) {
        return listenerContainerFactory(connectionFactory, jsonMessageConverter,
                aiTaskProperties.getListener().getTts());
    }

    @Bean
    public SimpleRabbitListenerContainerFactory writerRabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter
    ) {
        return listenerContainerFactory(connectionFactory, jsonMessageConverter,
                aiTaskProperties.getListener().getWriter());
    }

    @Bean
    public SimpleRabbitListenerContainerFactory videoRabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter
    ) {
        return listenerContainerFactory(connectionFactory, jsonMessageConverter,
                aiTaskProperties.getListener().getVideo());
    }

    @Bean
    public SimpleRabbitListenerContainerFactory avatarRabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter
    ) {
        return listenerContainerFactory(connectionFactory, jsonMessageConverter,
                aiTaskProperties.getListener().getAvatar());
    }

    @Bean
    public SimpleRabbitListenerContainerFactory douyinParseTranscriptRabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter
    ) {
        return listenerContainerFactory(connectionFactory, jsonMessageConverter,
                aiTaskProperties.getListener().getDouyinParseTranscript());
    }

    @Bean
    public SimpleMessageListenerContainer aiTaskManualListenerContainer(
            ConnectionFactory connectionFactory,
            ObjectMapper objectMapper,
            AiTaskConsumer aiTaskConsumer
    ) {
        return manualListenerContainer(connectionFactory, objectMapper, aiTaskConsumer,
                AiTaskQueueNames.QUEUE, aiTaskProperties.getListener().getRegular());
    }

    @Bean
    public SimpleMessageListenerContainer ttsManualListenerContainer(
            ConnectionFactory connectionFactory,
            ObjectMapper objectMapper,
            AiTaskConsumer aiTaskConsumer
    ) {
        return manualListenerContainer(connectionFactory, objectMapper, aiTaskConsumer,
                AiTaskQueueNames.TTS_GENERATE_QUEUE, aiTaskProperties.getListener().getTts());
    }

    @Bean
    public SimpleMessageListenerContainer writerManualListenerContainer(
            ConnectionFactory connectionFactory,
            ObjectMapper objectMapper,
            AiTaskConsumer aiTaskConsumer
    ) {
        return manualListenerContainer(connectionFactory, objectMapper, aiTaskConsumer,
                AiTaskQueueNames.WRITER_QUEUE, aiTaskProperties.getListener().getWriter());
    }

    @Bean
    public SimpleMessageListenerContainer videoManualListenerContainer(
            ConnectionFactory connectionFactory,
            ObjectMapper objectMapper,
            AiTaskConsumer aiTaskConsumer
    ) {
        return manualListenerContainer(connectionFactory, objectMapper, aiTaskConsumer,
                AiTaskQueueNames.VIDEO_GENERATE_QUEUE, aiTaskProperties.getListener().getVideo());
    }

    @Bean
    public SimpleMessageListenerContainer avatarManualListenerContainer(
            ConnectionFactory connectionFactory,
            ObjectMapper objectMapper,
            AiTaskConsumer aiTaskConsumer
    ) {
        return manualListenerContainer(connectionFactory, objectMapper, aiTaskConsumer,
                AiTaskQueueNames.AVATAR_GENERATE_QUEUE, aiTaskProperties.getListener().getAvatar());
    }

    @Bean
    public SimpleMessageListenerContainer douyinParseTranscriptManualListenerContainer(
            ConnectionFactory connectionFactory,
            ObjectMapper objectMapper,
            AiTaskConsumer aiTaskConsumer
    ) {
        return manualListenerContainer(connectionFactory, objectMapper, aiTaskConsumer,
                AiTaskQueueNames.DOUYIN_PARSE_TRANSCRIPT_QUEUE,
                aiTaskProperties.getListener().getDouyinParseTranscript());
    }

    private SimpleRabbitListenerContainerFactory listenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter,
            AiTaskProperties.ListenerContainer config
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setConcurrentConsumers(config.getConcurrentConsumers());
        factory.setMaxConcurrentConsumers(config.getMaxConcurrentConsumers());
        factory.setPrefetchCount(config.getPrefetchCount());
        return factory;
    }

    private SimpleMessageListenerContainer manualListenerContainer(
            ConnectionFactory connectionFactory,
            ObjectMapper objectMapper,
            AiTaskConsumer aiTaskConsumer,
            String queueName,
            AiTaskProperties.ListenerContainer config
    ) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(queueName);
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setConcurrentConsumers(config.getConcurrentConsumers());
        container.setMaxConcurrentConsumers(config.getMaxConcurrentConsumers());
        container.setPrefetchCount(config.getPrefetchCount());
        container.setMessageListener((ChannelAwareMessageListener) (message, channel) ->
                consumeManualMessage(objectMapper, aiTaskConsumer, message, channel));
        return container;
    }

    private void consumeManualMessage(
            ObjectMapper objectMapper,
            AiTaskConsumer aiTaskConsumer,
            Message message,
            Channel channel
    ) throws IOException {
        AiTaskMessage taskMessage = objectMapper.readValue(message.getBody(), AiTaskMessage.class);
        aiTaskConsumer.consumeMessage(taskMessage, channel, message.getMessageProperties().getDeliveryTag());
    }

    private Queue retryableQueue(String queueName, String retryRoutingKey) {
        return QueueBuilder.durable(queueName)
                .withArgument("x-dead-letter-exchange", AiTaskQueueNames.DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", retryRoutingKey)
                .build();
    }

    private Queue retryQueue(String queueName, String targetRoutingKey) {
        return QueueBuilder.durable(queueName)
                .withArgument("x-message-ttl", AiTaskQueueNames.RETRY_TTL_MILLIS)
                .withArgument("x-dead-letter-exchange", AiTaskQueueNames.EXCHANGE)
                .withArgument("x-dead-letter-routing-key", targetRoutingKey)
                .build();
    }
}
