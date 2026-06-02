package com.huashuo.task.mq;

import com.huashuo.task.enums.TaskStatusCode;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiTaskConsumerTest {

    @Test
    void redeliveredRunningTaskIsReopenedAndDispatched() throws Exception {
        TaskService taskService = mock(TaskService.class);
        AiTaskExecutionDispatcher dispatcher = mock(AiTaskExecutionDispatcher.class);
        TaskExecutionGuard executionGuard = mock(TaskExecutionGuard.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        Channel channel = mock(Channel.class);
        AiTaskConsumer consumer = new AiTaskConsumer(taskService, dispatcher, executionGuard, rabbitTemplate);

        when(taskService.getTask(262L))
                .thenReturn(task(TaskStatusCode.RUNNING))
                .thenReturn(task(TaskStatusCode.RETRYABLE));
        doAnswer(invocation -> {
            TaskExecutionGuard.GuardedAction action = invocation.getArgument(2);
            action.execute();
            return null;
        }).when(executionGuard).run(anyString(), eq(262L), any(TaskExecutionGuard.GuardedAction.class));

        AiTaskMessage message = new AiTaskMessage(262L, TaskTypeCode.SEEDANCE_CAR_SALES_VIDEO, 1L, "trace-262");

        consumer.consumeMessage(message, channel, 9L, true);

        verify(taskService).failTask(eq(262L), anyString(), eq(true), eq(false));
        verify(dispatcher).dispatch(message);
        verify(channel).basicAck(9L, false);
    }

    @Test
    void freshRunningTaskIsAckedWithoutDispatch() throws Exception {
        TaskService taskService = mock(TaskService.class);
        AiTaskExecutionDispatcher dispatcher = mock(AiTaskExecutionDispatcher.class);
        TaskExecutionGuard executionGuard = mock(TaskExecutionGuard.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        Channel channel = mock(Channel.class);
        AiTaskConsumer consumer = new AiTaskConsumer(taskService, dispatcher, executionGuard, rabbitTemplate);

        when(taskService.getTask(262L)).thenReturn(task(TaskStatusCode.RUNNING));

        AiTaskMessage message = new AiTaskMessage(262L, TaskTypeCode.SEEDANCE_CAR_SALES_VIDEO, 1L, "trace-262");

        consumer.consumeMessage(message, channel, 9L, false);

        verify(taskService, never()).failTask(eq(262L), anyString(), eq(true), eq(false));
        verify(executionGuard, never()).run(anyString(), eq(262L), any(TaskExecutionGuard.GuardedAction.class));
        verify(dispatcher, never()).dispatch(any());
        verify(channel).basicAck(9L, false);
    }

    private TaskItem task(String status) {
        return new TaskItem(
                262L,
                null,
                1L,
                TaskTypeCode.SEEDANCE_CAR_SALES_VIDEO,
                "ep-test",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                status,
                22,
                null,
                null,
                null,
                0,
                false,
                "{}",
                "{}",
                "trace-262",
                null,
                null,
                null,
                null
        );
    }
}
