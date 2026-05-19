package com.huashuo.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.asset.entity.AssetEntity;
import com.huashuo.asset.mapper.AssetMapper;
import com.huashuo.asset.service.AssetService;
import com.huashuo.asset.vo.AssetItem;
import com.huashuo.task.entity.TaskEntity;
import com.huashuo.task.enums.TaskStatusCode;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.mapper.TaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskResultAssetService {

    private static final String ASSET_TYPE_JSON = "JSON";
    private static final String KIND_GENERATED = "GENERATED";
    private static final String STATUS_ACTIVE = "ACTIVE";

    private final TaskMapper taskMapper;
    private final AssetMapper assetMapper;
    private final AssetService assetService;
    private final ObjectMapper objectMapper;

    @Transactional
    public ResultAsset ensureJsonResultAsset(TaskEntity task, String outputJson) {
        if (!needsJsonResultAsset(task, outputJson)) {
            return ResultAsset.unchanged(outputJson, null);
        }

        Long existingId = parseResultAssetId(outputJson);
        if (existingId != null) {
            return ResultAsset.unchanged(outputJson, existingId);
        }

        AssetEntity existing = findExistingJsonAsset(task);
        if (existing != null) {
            String updatedOutput = appendAssetReference(outputJson, existing.getAssetId(), existing.getFileUrl());
            return new ResultAsset(updatedOutput, existing.getAssetId(), false);
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("taskType", task.getTaskType());
        meta.put("taskId", task.getTaskId());
        meta.put("projectId", task.getProjectId());
        meta.put("generatedResult", true);

        AssetItem asset = assetService.createGeneratedJsonAsset(
                task.getOwnerUserId(),
                task.getProjectId(),
                task.getTaskId(),
                resultFileName(task),
                outputJson,
                storageCategory(task.getTaskType()),
                task.getTaskType(),
                writeJson(meta)
        );
        String updatedOutput = appendAssetReference(outputJson, asset.assetId(), asset.fileUrl());
        return new ResultAsset(updatedOutput, asset.assetId(), true);
    }

    @Transactional
    public int backfillRecentSuccessTasks(int maxItems, int lookbackHours) {
        int limit = Math.max(1, maxItems);
        int hours = Math.max(1, lookbackHours);
        LocalDateTime since = LocalDateTime.now().minusHours(hours);
        LambdaQueryWrapper<TaskEntity> wrapper = new LambdaQueryWrapper<TaskEntity>()
                .eq(TaskEntity::getStatus, TaskStatusCode.SUCCESS)
                .isNull(TaskEntity::getResultAssetId)
                .isNotNull(TaskEntity::getOutputJson)
                .ge(TaskEntity::getCreatedAt, since)
                .orderByDesc(TaskEntity::getCreatedAt)
                .last("limit " + limit);
        List<TaskEntity> tasks = taskMapper.selectList(wrapper);
        int updated = 0;
        for (TaskEntity task : tasks) {
            if (!needsJsonResultAsset(task, task.getOutputJson())) {
                continue;
            }
            try {
                ResultAsset result = ensureJsonResultAsset(task, task.getOutputJson());
                if (result.assetId() == null) {
                    continue;
                }
                LambdaUpdateWrapper<TaskEntity> uw = new LambdaUpdateWrapper<>();
                uw.eq(TaskEntity::getTaskId, task.getTaskId())
                        .set(TaskEntity::getResultAssetId, result.assetId())
                        .set(TaskEntity::getOutputJson, result.outputJson())
                        .set(TaskEntity::getUpdatedAt, LocalDateTime.now());
                taskMapper.update(null, uw);
                updated++;
            } catch (RuntimeException ex) {
                log.warn("Backfill generated result asset failed. taskId={}, taskType={}, reason={}",
                        task.getTaskId(), task.getTaskType(), ex.getMessage());
            }
        }
        return updated;
    }

    private boolean needsJsonResultAsset(TaskEntity task, String outputJson) {
        if (task == null || task.getTaskId() == null || task.getOwnerUserId() == null) {
            return false;
        }
        if (!StringUtils.hasText(outputJson)) {
            return false;
        }
        return isJsonResultTask(task.getTaskType());
    }

    private boolean isJsonResultTask(String taskType) {
        return TaskTypeCode.VIDEO_PARSE.equals(taskType)
                || TaskTypeCode.SCRIPT_REWRITE.equals(taskType)
                || TaskTypeCode.STORYBOARD_GENERATE.equals(taskType)
                || TaskTypeCode.VIDEO_SCRIPT_ANALYZE.equals(taskType)
                || TaskTypeCode.VIDEO_SCRIPT_URL_ANALYZE.equals(taskType)
                || TaskTypeCode.DOUYIN_REWRITE.equals(taskType)
                || TaskTypeCode.DOUYIN_TRANSCRIPT.equals(taskType)
                || TaskTypeCode.DOUYIN_PARSE_TRANSCRIPT.equals(taskType);
    }

    private AssetEntity findExistingJsonAsset(TaskEntity task) {
        LambdaQueryWrapper<AssetEntity> wrapper = new LambdaQueryWrapper<AssetEntity>()
                .eq(AssetEntity::getTaskId, task.getTaskId())
                .eq(AssetEntity::getOwnerUserId, task.getOwnerUserId())
                .eq(AssetEntity::getAssetType, ASSET_TYPE_JSON)
                .eq(AssetEntity::getKind, KIND_GENERATED)
                .eq(AssetEntity::getStatus, STATUS_ACTIVE)
                .orderByDesc(AssetEntity::getCreatedAt)
                .last("limit 1");
        return assetMapper.selectOne(wrapper);
    }

    private String resultFileName(TaskEntity task) {
        String type = StringUtils.hasText(task.getTaskType()) ? task.getTaskType().toLowerCase() : "task";
        return type.replaceAll("[^a-z0-9_-]", "-") + "-result-task-" + task.getTaskId() + ".json";
    }

    private String storageCategory(String taskType) {
        if (TaskTypeCode.SCRIPT_REWRITE.equals(taskType)) {
            return "script";
        }
        if (TaskTypeCode.DOUYIN_REWRITE.equals(taskType)
                || TaskTypeCode.DOUYIN_TRANSCRIPT.equals(taskType)
                || TaskTypeCode.DOUYIN_PARSE_TRANSCRIPT.equals(taskType)) {
            return "writer";
        }
        return "storyboard";
    }

    private Long parseResultAssetId(String outputJson) {
        if (!StringUtils.hasText(outputJson)) {
            return null;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(outputJson, new TypeReference<>() {
            });
            Object direct = map.get("resultAssetId");
            if (direct instanceof Number n) {
                return n.longValue();
            }
            if (direct instanceof String s && StringUtils.hasText(s)) {
                return Long.parseLong(s.trim());
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String appendAssetReference(String outputJson, Long assetId, String fileUrl) {
        Map<String, Object> map = readOutputAsMap(outputJson);
        map.put("resultAssetId", assetId);
        if (StringUtils.hasText(fileUrl)) {
            map.put("previewUrl", fileUrl);
        }
        return writeJson(map);
    }

    private Map<String, Object> readOutputAsMap(String outputJson) {
        if (StringUtils.hasText(outputJson)) {
            try {
                Map<String, Object> parsed = objectMapper.readValue(outputJson, new TypeReference<>() {
                });
                return new LinkedHashMap<>(parsed);
            } catch (Exception ignored) {
            }
        }
        Map<String, Object> fallback = new LinkedHashMap<>();
        if (StringUtils.hasText(outputJson)) {
            fallback.put("rawResult", outputJson);
        }
        return fallback;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    public record ResultAsset(String outputJson, Long assetId, boolean created) {
        static ResultAsset unchanged(String outputJson, Long assetId) {
            return new ResultAsset(outputJson, assetId, false);
        }
    }
}
