package com.huashuo.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.project.ProjectService;
import com.huashuo.task.dto.CreateTaskRequest;
import com.huashuo.task.entity.TaskEntity;
import com.huashuo.task.mapper.TaskMapper;
import com.huashuo.task.vo.TaskItem;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TaskService {

    private final TaskMapper taskMapper;
    private final ProjectService projectService;

    public TaskService(TaskMapper taskMapper, ProjectService projectService) {
        this.taskMapper = taskMapper;
        this.projectService = projectService;
    }

    public TaskItem createTask(CreateTaskRequest request, String traceId) {
        projectService.getProject(request.projectId());

        TaskEntity entity = new TaskEntity();
        entity.setProjectId(request.projectId());
        entity.setTaskType(request.taskType());
        entity.setStatus("QUEUED");
        entity.setInputJson(normalizeJson(request.inputJson()));
        entity.setRetryCount(0);
        entity.setTraceId(traceId);
        taskMapper.insert(entity);

        TaskEntity loaded = taskMapper.selectById(entity.getTaskId());
        if (loaded == null) {
            throw new BusinessException(50000, "Failed to load task after insert");
        }
        return toItem(loaded);
    }

    public List<TaskItem> listProjectTasks(Long projectId) {
        projectService.getProject(projectId);
        LambdaQueryWrapper<TaskEntity> w = new LambdaQueryWrapper<>();
        w.eq(TaskEntity::getProjectId, projectId)
                .orderByDesc(TaskEntity::getCreatedAt, TaskEntity::getTaskId);
        return taskMapper.selectList(w).stream().map(this::toItem).toList();
    }

    public TaskItem getTask(Long taskId) {
        TaskEntity entity = taskMapper.selectById(taskId);
        if (entity == null) {
            throw new BusinessException(40400, "Task does not exist");
        }
        return toItem(entity);
    }

    private TaskItem toItem(TaskEntity entity) {
        return new TaskItem(
                entity.getTaskId(),
                entity.getProjectId(),
                entity.getTaskType(),
                entity.getStatus(),
                entity.getInputJson(),
                entity.getOutputJson(),
                entity.getRetryCount(),
                entity.getErrorMessage(),
                entity.getTraceId(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private String normalizeJson(String json) {
        if (json == null || json.isBlank()) {
            return "{}";
        }
        return json;
    }
}
