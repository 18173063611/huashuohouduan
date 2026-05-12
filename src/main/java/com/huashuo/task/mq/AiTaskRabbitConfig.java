package com.huashuo.task.mq;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiTaskRabbitConfig {

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
    public Queue aiTaskRetryQueue() {
        return QueueBuilder.durable(AiTaskQueueNames.RETRY_QUEUE)
                .withArgument("x-message-ttl", AiTaskQueueNames.RETRY_TTL_MILLIS)
                .withArgument("x-dead-letter-exchange", AiTaskQueueNames.EXCHANGE)
                .withArgument("x-dead-letter-routing-key", AiTaskQueueNames.ROUTING_KEY)
                .build();
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
    public Binding aiTaskRetryBinding(Queue aiTaskRetryQueue, DirectExchange aiTaskDlxExchange) {
        return BindingBuilder.bind(aiTaskRetryQueue)
                .to(aiTaskDlxExchange)
                .with(AiTaskQueueNames.RETRY_ROUTING_KEY);
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
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        return factory;
    }
}
