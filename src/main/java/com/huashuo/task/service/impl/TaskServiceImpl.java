package com.huashuo.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.project.service.ProjectService;
import com.huashuo.task.entity.TaskEntity;
import com.huashuo.task.enums.TaskStatusCode;
import com.huashuo.task.mapper.TaskMapper;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
/**
 * 任务服务实现：集中维护任务状态流转、input_json/output_json 持久化和异常状态处理。
 */
public class TaskServiceImpl implements TaskService {

    private final TaskMapper taskMapper;
    private final ProjectService projectService;

    public TaskServiceImpl(TaskMapper taskMapper, ProjectService projectService) {
        this.taskMapper = taskMapper;
        this.projectService = projectService;
    }

    @Override
    @Transactional
    public TaskItem createTask(Long projectId, String taskType, String inputJson, String traceId) {
        projectService.getProject(projectId);
        TaskEntity entity = new TaskEntity();
        entity.setProjectId(projectId);
        entity.setTaskType(taskType);
        entity.setStatus(TaskStatusCode.QUEUED);
        entity.setInputJson(normalizeJson(inputJson));
        entity.setRetryCount(0);
        entity.setTraceId(traceId);
        taskMapper.insert(entity);

        TaskEntity loaded = taskMapper.selectById(entity.getTaskId());
        if (loaded == null) {
            throw new BusinessException(50000, "Failed to load task after insert");
        }
        return toItem(loaded);
    }

    @Override
    @Transactional
    public TaskItem startTask(Long taskId) {
        TaskEntity existing = taskMapper.selectById(taskId);
        if (existing == null) {
            throw new BusinessException(40400, "Task does not exist");
        }
        if (!TaskStatusCode.QUEUED.equals(existing.getStatus())
                && !TaskStatusCode.RETRYABLE.equals(existing.getStatus())) {
            throw new BusinessException(40900, "Task can only start from QUEUED or RETRYABLE");
        }
        LambdaUpdateWrapper<TaskEntity> uw = new LambdaUpdateWrapper<>();
        uw.eq(TaskEntity::getTaskId, taskId)
                .set(TaskEntity::getStatus, TaskStatusCode.RUNNING)
                .set(TaskEntity::getUpdatedAt, LocalDateTime.now());
        taskMapper.update(null, uw);
        return getTask(taskId);
    }

    @Override
    @Transactional
    public TaskItem completeTask(Long taskId, String outputJson) {
        TaskEntity existing = taskMapper.selectById(taskId);
        if (existing == null) {
            throw new BusinessException(40400, "Task does not exist");
        }
        if (!TaskStatusCode.RUNNING.equals(existing.getStatus())) {
            throw new BusinessException(40900, "Task can only complete from RUNNING");
        }
        LambdaUpdateWrapper<TaskEntity> uw = new LambdaUpdateWrapper<>();
        uw.eq(TaskEntity::getTaskId, taskId)
                .set(TaskEntity::getStatus, TaskStatusCode.SUCCESS)
                .set(TaskEntity::getOutputJson, outputJson == null ? "{}" : outputJson)
                .set(TaskEntity::getUpdatedAt, LocalDateTime.now());
        taskMapper.update(null, uw);
        return getTask(taskId);
    }

    @Override
    @Transactional
    public TaskItem failTask(Long taskId, String errorMessage, boolean retryable) {
        TaskEntity existing = taskMapper.selectById(taskId);
        if (existing == null) {
            throw new BusinessException(40400, "Task does not exist");
        }
        if (!TaskStatusCode.RUNNING.equals(existing.getStatus())) {
            throw new BusinessException(40900, "Task can only fail from RUNNING");
        }
        LambdaUpdateWrapper<TaskEntity> uw = new LambdaUpdateWrapper<>();
        uw.eq(TaskEntity::getTaskId, taskId)
                .set(TaskEntity::getStatus, retryable ? TaskStatusCode.RETRYABLE : TaskStatusCode.FAILED)
                .set(TaskEntity::getErrorMessage, errorMessage == null ? "unknown error" : errorMessage)
                .set(TaskEntity::getUpdatedAt, LocalDateTime.now());
        taskMapper.update(null, uw);
        return getTask(taskId);
    }

    @Override
    public List<TaskItem> listProjectTasks(Long projectId) {
        projectService.getProject(projectId);
        LambdaQueryWrapper<TaskEntity> w = new LambdaQueryWrapper<>();
        w.eq(TaskEntity::getProjectId, projectId)
                .orderByDesc(TaskEntity::getCreatedAt, TaskEntity::getTaskId);
        return taskMapper.selectList(w).stream().map(this::toItem).toList();
    }

    @Override
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
