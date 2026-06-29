package com.huashuo.video.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.task.aop.AiTaskSubmit;
import com.huashuo.task.enums.TaskStatusCode;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import com.huashuo.video.DTO.CarSalesDigitalHumanReplacementRequest;
import com.huashuo.video.DTO.CarSalesVideoDTO;
import com.huashuo.video.DTO.ImageDTO;
import com.huashuo.video.DTO.ImageFirstLastFrameDTO;
import com.huashuo.video.DTO.ImageReferenceDTO;
import com.huashuo.video.DTO.TextDTO;
import com.huashuo.video.service.VideoAsyncTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

@Service
public class VideoAsyncTaskServiceImpl implements VideoAsyncTaskService {

    private static final Logger log = LoggerFactory.getLogger(VideoAsyncTaskServiceImpl.class);

    private static final String AUDIO_MODE_NONE = "none";
    private static final String AUDIO_MODE_POST_MIX = "post_mix";
    private static final String AUDIO_MODE_REFERENCE = "reference";
    private static final String AUDIO_MODE_AUTO_TTS = "auto_tts";
    private static final String AUDIO_MODE_MODEL_NATIVE = "model_native";
    private static final String SUBTITLE_MODE_NONE = "无";
    private static final String SUBTITLE_MODE_AUTO = "自动生成";
    private static final String SUBTITLE_TIMING_AUDIO_RECOGNITION = "audio_recognition";

    private final TaskService taskService;
    private final ObjectMapper objectMapper;
    private final SeedanceResourceUrlValidator seedanceResourceUrlValidator;

    public VideoAsyncTaskServiceImpl(TaskService taskService,
                                     ObjectMapper objectMapper,
                                     SeedanceResourceUrlValidator seedanceResourceUrlValidator) {
        this.taskService = taskService;
        this.objectMapper = objectMapper;
        this.seedanceResourceUrlValidator = seedanceResourceUrlValidator;
    }

    @Override
    @AiTaskSubmit
    public TaskItem createTextVideoTask(TextDTO request, String traceId, Long ownerUserId,
                                        Long projectId, String idempotencyKey) {
        return taskService.createTask(projectId, TaskTypeCode.SEEDANCE_TEXT_VIDEO, toJson(request),
                traceId, ownerUserId, null, 200L, idempotencyKey);
    }

    @Override
    @AiTaskSubmit
    public TaskItem createFirstFrameVideoTask(ImageDTO request, String traceId, Long ownerUserId,
                                              Long projectId, String idempotencyKey) {
        request.setImageUrl(seedanceResourceUrlValidator.resolveImageUrl(request.getImageUrl()));
        return taskService.createTask(projectId, TaskTypeCode.SEEDANCE_FIRST_FRAME_VIDEO, toJson(request),
                traceId, ownerUserId, null, 200L, idempotencyKey);
    }

    @Override
    @AiTaskSubmit
    public TaskItem createFirstLastFrameVideoTask(ImageFirstLastFrameDTO request, String traceId, Long ownerUserId,
                                                  Long projectId, String idempotencyKey) {
        request.setFirstFrameUrl(seedanceResourceUrlValidator.resolveImageUrl(request.getFirstFrameUrl()));
        request.setLastFrameUrl(seedanceResourceUrlValidator.resolveImageUrl(request.getLastFrameUrl()));
        return taskService.createTask(projectId, TaskTypeCode.SEEDANCE_FIRST_LAST_FRAME_VIDEO, toJson(request),
                traceId, ownerUserId, null, 200L, idempotencyKey);
    }

    @Override
    @AiTaskSubmit
    public TaskItem createReferenceVideoTask(ImageReferenceDTO request, String traceId, Long ownerUserId,
                                             Long projectId, String idempotencyKey) {
        request.setImageUrls(resolveImageUrls(request.getImageUrls()));
        request.setAudioUrls(resolveAudioUrls(request.getAudioUrls()));
        return taskService.createTask(projectId, TaskTypeCode.SEEDANCE_REFERENCE_VIDEO, toJson(request),
                traceId, ownerUserId, null, 220L, idempotencyKey);
    }

    @Override
    @AiTaskSubmit
    public TaskItem createCarSalesVideoTask(CarSalesVideoDTO request, String traceId, Long ownerUserId,
                                            Long projectId, String idempotencyKey) {
        boolean snapshotDrained = false;
        beginResourceSnapshot();
        try {
            prepareCarSalesVoicePolicy(request);
            normalizeCarSalesResourceUrls(request);
            request.setResourceSnapshots(drainResourceSnapshots());
            snapshotDrained = true;
            validateMultiCarCompareRequest(request);
            int segmentCount = normalizeSegmentCount(request == null ? null : request.getSegmentCount());
            long creditCost = Math.max(1, segmentCount) * 220L;
            return taskService.createTask(projectId, TaskTypeCode.SEEDANCE_CAR_SALES_VIDEO, toJson(request),
                    traceId, ownerUserId, null, creditCost, idempotencyKey);
        } finally {
            if (!snapshotDrained) {
                drainResourceSnapshots();
            }
        }
    }

    @Override
    @AiTaskSubmit
    public TaskItem createCarSalesSegmentRegenerationTask(long sourceTaskId, int segmentIndex, String traceId,
                                                          Long ownerUserId, String idempotencyKey) {
        if (segmentIndex < 1) {
            throw new BusinessException(40000, "segmentIndex must start from 1");
        }
        TaskItem sourceTask = taskService.getTask(sourceTaskId);
        assertOwnerCanUse(sourceTask, ownerUserId);
        if (!TaskTypeCode.SEEDANCE_CAR_SALES_VIDEO.equals(sourceTask.taskType())) {
            throw new BusinessException(40000, "Only car sales video tasks support segment regeneration");
        }
        if (!TaskStatusCode.SUCCESS.equals(sourceTask.status())) {
            throw new BusinessException(40900, "Please regenerate a segment after the original video succeeds");
        }
        if (!hasText(sourceTask.inputJson())) {
            throw new BusinessException(40000, "Original task input is missing");
        }

        CarSalesVideoDTO original = parseCarSalesRequest(sourceTask.inputJson());
        List<CarSalesVideoDTO.Scene> scenes = original.getScenes();
        if (scenes == null || scenes.size() < segmentIndex || scenes.get(segmentIndex - 1) == null) {
            throw new BusinessException(40000, "Original task does not contain the requested scene");
        }

        CarSalesVideoDTO.Scene scene = copyScene(scenes.get(segmentIndex - 1));
        if (scene.getDuration() == null) {
            scene.setDuration(original.getSegmentDuration());
        }

        CarSalesVideoDTO request = copyCarSalesRequest(original);
        request.setScenes(List.of(scene));
        request.setSegmentCount(1);
        request.setSegmentDuration(scene.getDuration());
        request.setProjectId(sourceTask.projectId());
        request.setBgmUrl(null);
        request.setGeneratedVoiceAssetId(null);
        request.setGeneratedVoiceUrl(null);
        request.setRenderMode("segment_regeneration");
        request.setPrompt(appendPrompt(request.getPrompt(),
                "Regenerate only original segment " + segmentIndex
                        + ". Keep the same car, host, scene references, visual style, and continuity."));

        String segmentVoice = trimToNull(scene.getVoiceText());
        boolean modelNative = AUDIO_MODE_MODEL_NATIVE.equalsIgnoreCase(trimToDefault(original.getAudioMode(), AUDIO_MODE_NONE))
                || AUDIO_MODE_MODEL_NATIVE.equalsIgnoreCase(trimToDefault(original.getVoicePolicy(), AUDIO_MODE_NONE));
        if (modelNative) {
            if (!hasText(segmentVoice)) {
                throw new BusinessException(40000, "This segment has no voiceText for model-native audio");
            }
            request.setAudioMode(AUDIO_MODE_MODEL_NATIVE);
            request.setVoicePolicy("model_native");
            request.setAudioUrl(null);
            request.setFinalVoiceText(segmentVoice);
            request.setSubtitleMode("auto");
            request.setSubtitle(SUBTITLE_MODE_AUTO);
            request.setSubtitleTimingMode(SUBTITLE_TIMING_AUDIO_RECOGNITION);
        } else {
            request.setAudioMode(AUDIO_MODE_NONE);
            request.setVoicePolicy("none");
            request.setAudioUrl(null);
            request.setFinalVoiceText(segmentVoice);
            request.setSubtitleMode("off");
            request.setSubtitle(SUBTITLE_MODE_NONE);
            request.setSubtitleTimingMode(null);
        }

        boolean snapshotDrained = false;
        beginResourceSnapshot();
        try {
            normalizeCarSalesResourceUrls(request);
            request.setResourceSnapshots(drainResourceSnapshots());
            snapshotDrained = true;
            return taskService.createTask(sourceTask.projectId(), TaskTypeCode.SEEDANCE_CAR_SALES_VIDEO, toJson(request),
                    traceId, ownerUserId, null, 220L, idempotencyKey);
        } finally {
            if (!snapshotDrained) {
                drainResourceSnapshots();
            }
        }
    }

    @Override
    @AiTaskSubmit
    public TaskItem createCarSalesDigitalHumanReplacementTask(long sourceTaskId,
                                                              CarSalesDigitalHumanReplacementRequest replacement,
                                                              String traceId,
                                                              Long ownerUserId,
                                                              String idempotencyKey) {
        TaskItem sourceTask = taskService.getTask(sourceTaskId);
        assertOwnerCanUse(sourceTask, ownerUserId);
        if (!TaskTypeCode.SEEDANCE_CAR_SALES_VIDEO.equals(sourceTask.taskType())) {
            throw new BusinessException(40000, "Only car sales video tasks support digital human replacement");
        }
        if (!TaskStatusCode.FAILED.equals(sourceTask.status())
                && !TaskStatusCode.RETRYABLE.equals(sourceTask.status())
                && !TaskStatusCode.CANCELED.equals(sourceTask.status())) {
            throw new BusinessException(40900, "Please replace digital human after the render task fails or is canceled");
        }
        if (!hasText(sourceTask.inputJson())) {
            throw new BusinessException(40000, "Original task input is missing");
        }

        CarSalesVideoDTO request = copyCarSalesRequest(parseCarSalesRequest(sourceTask.inputJson()));
        applyDigitalHumanReplacement(request, replacement);
        request.setProjectId(sourceTask.projectId());

        boolean snapshotDrained = false;
        beginResourceSnapshot();
        try {
            prepareCarSalesVoicePolicy(request);
            normalizeCarSalesResourceUrls(request);
            request.setResourceSnapshots(drainResourceSnapshots());
            snapshotDrained = true;
            validateMultiCarCompareRequest(request);
            int segmentCount = normalizeSegmentCount(request.getSegmentCount());
            long creditCost = Math.max(1, segmentCount) * 220L;
            return taskService.createTask(sourceTask.projectId(), TaskTypeCode.SEEDANCE_CAR_SALES_VIDEO, toJson(request),
                    traceId, ownerUserId, null, creditCost, idempotencyKey);
        } finally {
            if (!snapshotDrained) {
                drainResourceSnapshots();
            }
        }
    }

    private void applyDigitalHumanReplacement(CarSalesVideoDTO request,
                                              CarSalesDigitalHumanReplacementRequest replacement) {
        if (request == null || replacement == null) {
            throw new BusinessException(40000, "Replacement request is missing");
        }
        String hostImageUrl = trimToNull(replacement.getHostImageUrl());
        if (!hasText(hostImageUrl)) {
            throw new BusinessException(40000, "请选择新的数字人形象");
        }
        String digitalHumanId = trimToNull(replacement.getDigitalHumanId());
        if (!hasText(digitalHumanId) && replacement.getAssetId() != null) {
            digitalHumanId = String.valueOf(replacement.getAssetId());
        }
        if (!hasText(digitalHumanId) && replacement.getAvatarId() != null) {
            digitalHumanId = String.valueOf(replacement.getAvatarId());
        }
        if (!hasText(digitalHumanId)) {
            digitalHumanId = "digital-human-replacement";
        }
        String label = trimToDefault(replacement.getAvatarName(), "更换数字人");

        List<Long> replacedHostAssetIds = new ArrayList<>();
        request.setAssetRoleBindings(replaceHostImageBinding(request.getAssetRoleBindings(),
                replacement.getAssetId(), hostImageUrl, label, replacedHostAssetIds));
        if (request.getCarPackages() != null) {
            for (CarSalesVideoDTO.CarPackage carPackage : request.getCarPackages()) {
                if (carPackage == null) {
                    continue;
                }
                if (hasHostImageBinding(carPackage.getAssetRoleBindings())) {
                    carPackage.setAssetRoleBindings(replaceHostImageBinding(carPackage.getAssetRoleBindings(),
                            replacement.getAssetId(), hostImageUrl, label, replacedHostAssetIds));
                }
            }
        }

        request.setHostImageUrl(hostImageUrl);
        request.setHostAppearanceEnabled(true);
        request.setHasDigitalHuman(true);
        request.setDigitalHumanId(digitalHumanId);
        request.setVideoType("digital_human");
        request.setResourceSnapshots(null);
        request.setSourceAssetIds(replaceAssetIdReferences(request.getSourceAssetIds(), replacedHostAssetIds,
                replacement.getAssetId()));
        request.setQuickAssetIds(replaceAssetIdReferences(request.getQuickAssetIds(), replacedHostAssetIds,
                replacement.getAssetId()));

        if (request.getScenes() != null) {
            for (CarSalesVideoDTO.Scene scene : request.getScenes()) {
                if (scene == null) {
                    continue;
                }
                scene.setDigitalHumanId(digitalHumanId);
                scene.setAvatarUrl(hostImageUrl);
            }
        }
    }

    private List<CarSalesVideoDTO.AssetRoleBinding> replaceHostImageBinding(
            List<CarSalesVideoDTO.AssetRoleBinding> bindings,
            Long replacementAssetId,
            String replacementUrl,
            String label,
            List<Long> replacedHostAssetIds) {
        List<CarSalesVideoDTO.AssetRoleBinding> next = new ArrayList<>();
        if (bindings != null) {
            for (CarSalesVideoDTO.AssetRoleBinding binding : bindings) {
                if (binding == null) {
                    continue;
                }
                if ("host_image".equalsIgnoreCase(trimToNull(binding.getAssetRole()))) {
                    if (binding.getAssetId() != null) {
                        replacedHostAssetIds.add(binding.getAssetId());
                    }
                    continue;
                }
                next.add(binding);
            }
        }
        CarSalesVideoDTO.AssetRoleBinding replacement = new CarSalesVideoDTO.AssetRoleBinding();
        replacement.setAssetId(replacementAssetId);
        replacement.setUrl(replacementUrl);
        replacement.setAssetType("IMAGE");
        replacement.setAssetRole("host_image");
        replacement.setLabel(label);
        next.add(replacement);
        return next;
    }

    private boolean hasHostImageBinding(List<CarSalesVideoDTO.AssetRoleBinding> bindings) {
        if (bindings == null) {
            return false;
        }
        for (CarSalesVideoDTO.AssetRoleBinding binding : bindings) {
            if (binding != null && "host_image".equalsIgnoreCase(trimToNull(binding.getAssetRole()))) {
                return true;
            }
        }
        return false;
    }

    private List<Long> replaceAssetIdReferences(List<Long> sourceIds, List<Long> removedIds, Long replacementAssetId) {
        if ((sourceIds == null || sourceIds.isEmpty()) && replacementAssetId == null) {
            return sourceIds;
        }
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        if (sourceIds != null) {
            for (Long id : sourceIds) {
                if (id != null && (removedIds == null || !removedIds.contains(id))) {
                    result.add(id);
                }
            }
        }
        if (replacementAssetId != null) {
            result.add(replacementAssetId);
        }
        return new ArrayList<>(result);
    }

    private CarSalesVideoDTO parseCarSalesRequest(String inputJson) {
        try {
            return objectMapper.readValue(inputJson, CarSalesVideoDTO.class);
        } catch (Exception e) {
            throw new BusinessException(40000, "Original car sales input is invalid");
        }
    }

    private CarSalesVideoDTO copyCarSalesRequest(CarSalesVideoDTO source) {
        return objectMapper.convertValue(source, CarSalesVideoDTO.class);
    }

    private CarSalesVideoDTO.Scene copyScene(CarSalesVideoDTO.Scene source) {
        return objectMapper.convertValue(source, CarSalesVideoDTO.Scene.class);
    }

    private void beginResourceSnapshot() {
        if (seedanceResourceUrlValidator != null) {
            seedanceResourceUrlValidator.beginResourceSnapshot();
        }
    }

    private List<CarSalesVideoDTO.ResourceSnapshot> drainResourceSnapshots() {
        return seedanceResourceUrlValidator == null ? null : seedanceResourceUrlValidator.drainResourceSnapshots();
    }

    private void assertOwnerCanUse(TaskItem task, Long ownerUserId) {
        if (task == null) {
            throw new BusinessException(40400, "Task not found");
        }
        Long owner = task.ownerUserId();
        if (owner == null) {
            return;
        }
        if (ownerUserId == null) {
            throw new BusinessException(40100, "Please login first");
        }
        if (!owner.equals(ownerUserId)) {
            throw new BusinessException(40300, "No permission to operate this task");
        }
    }

    private String appendPrompt(String prompt, String addition) {
        String base = trimToNull(prompt);
        return base == null ? addition : base + "\n" + addition;
    }

    private void normalizeCarSalesResourceUrls(CarSalesVideoDTO request) {
        if (request == null) {
            throw new BusinessException(40000, "请求体不能为空");
        }
        request.setCarImageUrls(resolveImageUrls(request.getCarImageUrls()));
        if (request.getCarPackages() != null) {
            for (CarSalesVideoDTO.CarPackage carPackage : request.getCarPackages()) {
                if (carPackage == null) {
                    continue;
                }
                carPackage.setImageUrls(resolveImageUrls(carPackage.getImageUrls()));
                carPackage.setSceneImageUrls(resolveImageUrls(carPackage.getSceneImageUrls()));
                if (carPackage.getAssetRoleBindings() != null) {
                    for (CarSalesVideoDTO.AssetRoleBinding binding : carPackage.getAssetRoleBindings()) {
                        normalizeCarSalesAssetBindingUrl(binding);
                    }
                }
            }
        }
        if (hasText(request.getHostImageUrl())) {
            request.setHostImageUrl(seedanceResourceUrlValidator.resolveImageUrl(request.getHostImageUrl()));
        }
        if (request.getScenes() != null) {
            for (CarSalesVideoDTO.Scene scene : request.getScenes()) {
                if (scene != null && scene.getImageUrls() != null) {
                    scene.setImageUrls(resolveImageUrls(scene.getImageUrls()));
                }
            }
        }
        if (request.getAssetRoleBindings() != null) {
            for (CarSalesVideoDTO.AssetRoleBinding binding : request.getAssetRoleBindings()) {
                normalizeCarSalesAssetBindingUrl(binding);
            }
        }
        if (hasText(request.getAudioUrl())) {
            request.setAudioUrl(seedanceResourceUrlValidator.resolveAudioUrl(request.getAudioUrl()));
        }
        if (hasText(request.getGeneratedVoiceUrl())) {
            request.setGeneratedVoiceUrl(seedanceResourceUrlValidator.resolveAudioUrl(request.getGeneratedVoiceUrl()));
        }
        if (hasText(request.getBgmUrl())) {
            request.setBgmUrl(seedanceResourceUrlValidator.resolveAudioUrl(request.getBgmUrl()));
        }
    }

    private void normalizeCarSalesAssetBindingUrl(CarSalesVideoDTO.AssetRoleBinding binding) {
        if (binding == null || !hasText(binding.getUrl())) {
            return;
        }
        String assetType = trimToNull(binding.getAssetType());
        if ("AUDIO".equalsIgnoreCase(assetType)) {
            binding.setUrl(seedanceResourceUrlValidator.resolveAudioUrl(binding.getUrl()));
        } else if (isImageBinding(assetType, binding.getUrl())) {
            binding.setUrl(seedanceResourceUrlValidator.resolveImageUrl(binding.getUrl()));
        } else {
            String inferredAssetType = inferAssetTypeFromUrl(binding.getUrl());
            if (hasText(inferredAssetType) && isImageAssetType(assetType) && !isImageReferenceUrl(binding.getUrl())) {
                binding.setAssetType(inferredAssetType);
            }
        }
    }

    private void validateMultiCarCompareRequest(CarSalesVideoDTO request) {
        if (!isMultiCarCompareRequest(request)) {
            return;
        }
        List<CarSalesVideoDTO.CarPackage> packages = request.getCarPackages() == null
                ? List.of()
                : request.getCarPackages().stream().filter(item -> item != null).toList();
        if (packages.size() < 2) {
            throw new BusinessException(40000, "多车型对比至少需要 2 个车型素材包");
        }
        if (packages.size() > 5) {
            throw new BusinessException(40000, "多车型对比首期最多支持 5 个车型素材包");
        }
        for (CarSalesVideoDTO.CarPackage carPackage : packages) {
            if (resolvePackageImageUrls(carPackage).isEmpty()) {
                throw new BusinessException(40000, "每个对比车型素材包至少需要 1 张车辆图片");
            }
        }
    }

    private boolean isMultiCarCompareRequest(CarSalesVideoDTO request) {
        if (request == null) {
            return false;
        }
        return "multi_car_compare".equalsIgnoreCase(trimToNull(request.getTaskMode()))
                || "multi_car_compare".equalsIgnoreCase(trimToNull(request.getRenderMode()))
                || (request.getCarPackages() != null && request.getCarPackages().size() >= 2);
    }

    private List<String> resolvePackageImageUrls(CarSalesVideoDTO.CarPackage carPackage) {
        List<String> urls = new ArrayList<>();
        if (carPackage == null) {
            return urls;
        }
        if (carPackage.getImageUrls() != null) {
            for (String url : carPackage.getImageUrls()) {
                if (hasText(url) && isImageReferenceUrl(url) && !urls.contains(url.trim())) {
                    urls.add(url.trim());
                }
            }
        }
        if (carPackage.getAssetRoleBindings() != null) {
            for (CarSalesVideoDTO.AssetRoleBinding binding : carPackage.getAssetRoleBindings()) {
                if (binding != null && hasText(binding.getUrl())
                        && isImageBinding(binding.getAssetType(), binding.getUrl())
                        && !urls.contains(binding.getUrl().trim())) {
                    urls.add(binding.getUrl().trim());
                }
            }
        }
        return urls;
    }

    private List<String> resolveImageUrls(List<String> urls) {
        if (urls == null) {
            return null;
        }
        List<String> resolved = new ArrayList<>();
        for (String url : urls) {
            if (hasText(url)) {
                if (!isImageReferenceUrl(url)) {
                    log.warn("Skip non-image URL in image reference list: {}", url);
                    continue;
                }
                resolved.add(seedanceResourceUrlValidator.resolveImageUrl(url));
            }
        }
        return resolved;
    }

    private boolean isImageBinding(String assetType, String url) {
        String type = lower(assetType);
        if (isNonImageAssetType(type)) {
            return false;
        }
        if (hasText(type) && !isImageAssetType(type)) {
            return false;
        }
        return isImageReferenceUrl(url);
    }

    private boolean isImageReferenceUrl(String url) {
        String path = urlPath(url);
        return path.endsWith(".jpg")
                || path.endsWith(".jpeg")
                || path.endsWith(".png")
                || path.endsWith(".webp")
                || lower(url).startsWith("data:image/");
    }

    private boolean isImageAssetType(String assetType) {
        String type = lower(assetType);
        return "image".equals(type) || "cover".equals(type);
    }

    private boolean isNonImageAssetType(String assetType) {
        String type = lower(assetType);
        return "audio".equals(type)
                || "bgm".equals(type)
                || "json".equals(type)
                || "script".equals(type)
                || "script_asset".equals(type)
                || "storyboard".equals(type)
                || "storyboard_asset".equals(type)
                || "text".equals(type)
                || "video".equals(type);
    }

    private String inferAssetTypeFromUrl(String url) {
        String path = urlPath(url);
        if (isImageReferenceUrl(url)) {
            return "IMAGE";
        }
        if (path.endsWith(".mp3") || path.endsWith(".wav") || path.endsWith(".m4a")
                || path.endsWith(".aac")) {
            return "AUDIO";
        }
        if (path.endsWith(".mp4") || path.endsWith(".mov") || path.endsWith(".webm")) {
            return "VIDEO";
        }
        if (path.endsWith(".json")) {
            return "JSON";
        }
        if (path.endsWith(".txt") || path.endsWith(".md") || path.endsWith(".srt")
                || path.endsWith(".vtt")) {
            return "TEXT";
        }
        return null;
    }

    private String urlPath(String url) {
        if (!hasText(url)) {
            return "";
        }
        String value = url.trim();
        try {
            URI uri = URI.create(value);
            if (hasText(uri.getPath())) {
                return uri.getPath().toLowerCase(Locale.ROOT);
            }
        } catch (Exception ignored) {
        }
        int queryIndex = value.indexOf('?');
        if (queryIndex >= 0) {
            value = value.substring(0, queryIndex);
        }
        int hashIndex = value.indexOf('#');
        if (hashIndex >= 0) {
            value = value.substring(0, hashIndex);
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private List<String> resolveAudioUrls(List<String> urls) {
        if (urls == null) {
            return null;
        }
        List<String> resolved = new ArrayList<>();
        for (String url : urls) {
            if (hasText(url)) {
                resolved.add(seedanceResourceUrlValidator.resolveAudioUrl(url));
            }
        }
        return resolved;
    }

    private void prepareCarSalesVoicePolicy(CarSalesVideoDTO request) {
        if (request == null) {
            throw new BusinessException(40000, "请求体不能为空");
        }
        String rawAudioMode = trimToNull(request.getAudioMode());
        String rawVoicePolicy = trimToNull(request.getVoicePolicy());
        String audioUrl = trimToNull(request.getAudioUrl());
        String generatedVoiceUrl = trimToNull(request.getGeneratedVoiceUrl());
        if (isImplicitDefaultVoiceoverRequest(request, rawAudioMode, rawVoicePolicy, audioUrl, generatedVoiceUrl)) {
            clearImplicitDefaultVoiceover(request);
            rawAudioMode = AUDIO_MODE_NONE;
            rawVoicePolicy = AUDIO_MODE_NONE;
            audioUrl = null;
            generatedVoiceUrl = null;
        }
        String audioMode = rawAudioMode == null
                ? (hasText(audioUrl) || hasText(generatedVoiceUrl)
                ? AUDIO_MODE_POST_MIX
                : "auto_tts".equalsIgnoreCase(rawVoicePolicy)
                ? AUDIO_MODE_AUTO_TTS
                : AUDIO_MODE_MODEL_NATIVE.equalsIgnoreCase(rawVoicePolicy)
                ? AUDIO_MODE_MODEL_NATIVE
                : AUDIO_MODE_NONE)
                : trimToDefault(rawAudioMode, AUDIO_MODE_NONE);
        if (AUDIO_MODE_NONE.equalsIgnoreCase(audioMode) && "auto_tts".equalsIgnoreCase(rawVoicePolicy)) {
            audioMode = AUDIO_MODE_AUTO_TTS;
        }

        if (AUDIO_MODE_AUTO_TTS.equalsIgnoreCase(audioMode)) {
            if (hasText(generatedVoiceUrl)) {
                request.setAudioUrl(generatedVoiceUrl);
                request.setAudioMode(AUDIO_MODE_POST_MIX);
            } else if (hasText(audioUrl)) {
                request.setAudioMode(AUDIO_MODE_POST_MIX);
            } else {
                request.setAudioUrl(null);
                request.setAudioMode(AUDIO_MODE_AUTO_TTS);
            }
            request.setVoicePolicy("auto_tts");
            return;
        }
        if (AUDIO_MODE_MODEL_NATIVE.equalsIgnoreCase(audioMode)) {
            request.setAudioUrl(null);
            request.setAudioMode(AUDIO_MODE_MODEL_NATIVE);
            request.setVoicePolicy("model_native");
            return;
        }
        if (AUDIO_MODE_NONE.equalsIgnoreCase(audioMode)) {
            request.setAudioUrl(null);
            request.setAudioMode(AUDIO_MODE_NONE);
            if (!hasText(request.getVoicePolicy())) {
                request.setVoicePolicy("none");
            }
            return;
        }
        if (!AUDIO_MODE_NONE.equalsIgnoreCase(audioMode)
                && !AUDIO_MODE_POST_MIX.equalsIgnoreCase(audioMode)
                && !AUDIO_MODE_REFERENCE.equalsIgnoreCase(audioMode)) {
            throw new BusinessException(40000, "不支持的 audioMode: " + audioMode);
        }
        if (!AUDIO_MODE_NONE.equalsIgnoreCase(audioMode) && !hasText(audioUrl)) {
            throw new BusinessException(40000, "选择口播音频模式时必须提供 audioUrl");
        }
        if (hasText(audioUrl) && hasText(request.getBgmUrl())
                && audioUrl.equals(trimToNull(request.getBgmUrl()))) {
            throw new BusinessException(40000, "BGM 不能作为口播音频来源");
        }
        request.setAudioMode(hasText(audioUrl) ? audioMode : AUDIO_MODE_NONE);
        if (!hasText(request.getVoicePolicy())) {
            request.setVoicePolicy(hasText(audioUrl) ? "user_audio" : "none");
        }
    }

    private boolean isImplicitDefaultVoiceoverRequest(CarSalesVideoDTO request, String rawAudioMode,
                                                      String rawVoicePolicy, String audioUrl,
                                                      String generatedVoiceUrl) {
        if (request == null
                || hasText(audioUrl)
                || hasText(generatedVoiceUrl)
                || request.getGeneratedVoiceAssetId() != null
                || Boolean.TRUE.equals(request.getStrictVoiceText())) {
            return false;
        }
        if (!"auto".equalsIgnoreCase(trimToDefault(request.getVoiceTextSource(), ""))) {
            return false;
        }
        return AUDIO_MODE_AUTO_TTS.equalsIgnoreCase(rawAudioMode)
                || AUDIO_MODE_MODEL_NATIVE.equalsIgnoreCase(rawAudioMode)
                || AUDIO_MODE_AUTO_TTS.equalsIgnoreCase(rawVoicePolicy)
                || AUDIO_MODE_MODEL_NATIVE.equalsIgnoreCase(rawVoicePolicy);
    }

    private void clearImplicitDefaultVoiceover(CarSalesVideoDTO request) {
        request.setAudioUrl(null);
        request.setGeneratedVoiceUrl(null);
        request.setGeneratedVoiceAssetId(null);
        request.setAudioMode(AUDIO_MODE_NONE);
        request.setVoicePolicy("none");
        request.setFinalVoiceText(null);
        request.setStrictVoiceText(null);
        if (request.getScenes() == null) {
            return;
        }
        for (CarSalesVideoDTO.Scene scene : request.getScenes()) {
            if (scene != null) {
                scene.setVoiceText(null);
            }
        }
    }

    private int normalizeSegmentCount(Integer value) {
        if (value == null) {
            return 4;
        }
        return Math.max(1, Math.min(12, value));
    }

    private String trimToDefault(String value, String fallback) {
        String trimmed = trimToNull(value);
        return trimmed == null ? fallback : trimmed;
    }

    private String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize video task input", e);
            throw new BusinessException(50000, "Failed to serialize task input");
        }
    }
}
