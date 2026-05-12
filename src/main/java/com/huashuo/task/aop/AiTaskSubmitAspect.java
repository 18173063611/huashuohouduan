package com.huashuo.task.aop;

import com.huashuo.task.mq.AiTaskPublisher;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Aspect
@Component
public class AiTaskSubmitAspect {

    private final TaskService taskService;
    private final AiTaskPublisher aiTaskPublisher;

    public AiTaskSubmitAspect(TaskService taskService, AiTaskPublisher aiTaskPublisher) {
        this.taskService = taskService;
        this.aiTaskPublisher = aiTaskPublisher;
    }

    @AfterReturning(pointcut = "@annotation(com.huashuo.task.aop.AiTaskSubmit)", returning = "result")
    public void publishTask(Object result) {
        Long taskId = extractTaskId(result);
        if (taskId == null) {
            return;
        }
        TaskItem task = result instanceof TaskItem item ? item : taskService.getTask(taskId);
        aiTaskPublisher.publishAfterCommit(task);
    }

    private Long extractTaskId(Object result) {
        if (result == null) {
            return null;
        }
        if (result instanceof TaskItem item) {
            return item.taskId();
        }
        Object data = extractData(result);
        if (data != null && data != result) {
            return extractTaskId(data);
        }
        try {
            Method method = result.getClass().getMethod("taskId");
            Object value = method.invoke(result);
            return value instanceof Number number ? number.longValue() : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private Object extractData(Object result) {
        try {
            Method method = result.getClass().getMethod("data");
            return method.invoke(result);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
