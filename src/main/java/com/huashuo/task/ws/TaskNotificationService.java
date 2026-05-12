package com.huashuo.task.ws;

import com.huashuo.task.vo.TaskItem;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
public class TaskNotificationService {

    private final SimpMessagingTemplate messagingTemplate;

    public TaskNotificationService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void notifyTaskChanged(TaskItem task) {
        if (task == null || task.taskId() == null) {
            return;
        }
        TaskStatusMessage message = new TaskStatusMessage(
                task.taskId(),
                task.ownerUserId(),
                task.taskType(),
                task.status(),
                task.progress(),
                task.errorMessage()
        );
        messagingTemplate.convertAndSend("/topic/tasks/" + task.taskId(), message);
        if (task.ownerUserId() != null) {
            messagingTemplate.convertAndSend("/topic/users/" + task.ownerUserId() + "/tasks", message);
        }
    }
}
