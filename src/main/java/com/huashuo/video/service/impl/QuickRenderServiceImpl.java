package com.huashuo.video.service.impl;

import com.huashuo.asset.service.AssetService;
import com.huashuo.asset.vo.AssetContent;
import com.huashuo.asset.vo.AssetItem;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.storage.StorageService;
import com.huashuo.storage.UploadResult;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.mq.AiTaskPublisher;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import com.huashuo.video.DTO.CarSalesVideoDTO;
import com.huashuo.video.DTO.DigitalHumanDTO;
import com.huashuo.video.DTO.DigitalHumanGenerateResponse;
import com.huashuo.video.DTO.ImageReferenceDTO;
import com.huashuo.video.DTO.QuickRenderRequest;
import com.huashuo.video.DTO.QuickRenderResponse;
import com.huashuo.video.service.QuickRenderService;
import com.huashuo.video.service.VideoAsyncTaskService;
import com.huashuo.video.service.ViduDigitalHumanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 一键成片编排服务：只做素材识别、链路判断和标准 DTO 转换，实际生成仍复用既有视频生成服务。
 */
@Service
public class QuickRenderServiceImpl implements QuickRenderService {

    private static final Logger log = LoggerFactory.getLogger(QuickRenderServiceImpl.class);

    private static final String ROUTE_CAR_SALES = "car_sales";
    private static final String ROUTE_DIGITAL_HUMAN = "digital_human";
    private static final String ROUTE_GENERAL_VIDEO = "general_video";
    private static final String ROUTE_MATERIAL_MIX = "material_mix";
    private static final int QUICK_SEGMENT_DURATION_SECONDS = 15;
    private static final int DEFAULT_SEGMENT_COUNT = 1;
    private static final int MAX_SEGMENT_COUNT = 6;
    private static final int MAX_QUICK_CAR_REFERENCE_IMAGES = 6;
    private static final int MATERIAL_MIX_CLIP_SECONDS = 8;

    private final AssetService assetService;
    private final VideoAsyncTaskService videoAsyncTaskService;
    private final ViduDigitalHumanService viduDigitalHumanService;
    private final TaskService taskService;
    private final AiTaskPublisher aiTaskPublisher;
    private final ObjectMapper objectMapper;
    private final StorageService storageService;
    private final String ffmpegBin;
    private final HttpClient mediaHttpClient;

    public QuickRenderServiceImpl(AssetService assetService,
                                  VideoAsyncTaskService videoAsyncTaskService,
                                  ViduDigitalHumanService viduDigitalHumanService,
                                  TaskService taskService,
                                  AiTaskPublisher aiTaskPublisher,
                                  ObjectMapper objectMapper,
                                  StorageService storageService,
                                  @Value("${huashuo.ffmpeg.bin:ffmpeg}") String ffmpegBin) {
        this.assetService = assetService;
        this.videoAsyncTaskService = videoAsyncTaskService;
        this.viduDigitalHumanService = viduDigitalHumanService;
        this.taskService = taskService;
        this.aiTaskPublisher = aiTaskPublisher;
        this.objectMapper = objectMapper;
        this.storageService = storageService;
        this.ffmpegBin = StringUtils.hasText(ffmpegBin) ? ffmpegBin.trim() : "ffmpeg";
        this.mediaHttpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public QuickRenderResponse quickRender(QuickRenderRequest request, String traceId, Long ownerUserId,
                                           String idempotencyKey) {
        if (request == null || request.getAssetIds() == null || request.getAssetIds().isEmpty()) {
            throw new BusinessException(40000, "assetIds 至少需要 1 个素材资产");
        }
        OptionalLong viewer = ownerUserId == null ? OptionalLong.empty() : OptionalLong.of(ownerUserId);
        List<Material> materials = loadMaterials(request, viewer);
        String route = resolveRoute(request, materials);

        TaskItem quickTask = taskService.createTask(request.getProjectId(), TaskTypeCode.QUICK_RENDER,
                toJson(request), traceId, ownerUserId, null, 0L, idempotencyKey);
        aiTaskPublisher.publishAfterCommit(quickTask);

        QuickRenderResponse response = new QuickRenderResponse();
        response.setRoute(route);
        response.setTask(quickTask);
        response.setAssets(materials.stream().map(this::toRecognizedAsset).toList());
        response.setSummary("一键成片任务已提交，等待后台识别素材并创建生成任务。" + buildSummary(route, materials, null, null));
        response.setNormalizedRequest(request);
        return response;
    }

    @Override
    public QuickRenderResponse executeForExistingTask(long taskId) {
        TaskItem task = taskService.getTask(taskId);
        if (task == null || !TaskTypeCode.QUICK_RENDER.equals(task.taskType())) {
            throw new BusinessException(40000, "不是有效的一键成片任务 taskId=" + taskId);
        }
        QuickRenderRequest request;
        try {
            request = objectMapper.readValue(task.inputJson(), QuickRenderRequest.class);
        } catch (Exception e) {
            throw new BusinessException(50000, "一键成片任务 inputJson 解析失败：" + e.getMessage());
        }
        OptionalLong viewer = task.ownerUserId() == null ? OptionalLong.empty() : OptionalLong.of(task.ownerUserId());
        List<Material> materials = loadMaterials(request, viewer);
        String route = resolveRoute(request, materials);

        QuickRenderResponse response = new QuickRenderResponse();
        response.setRoute(route);
        response.setAssets(materials.stream().map(this::toRecognizedAsset).toList());

        if (ROUTE_CAR_SALES.equals(route)) {
            CarSalesVideoDTO dto = buildCarSalesRequest(request, materials, viewer);
            TaskItem childTask = videoAsyncTaskService.createCarSalesVideoTask(
                    dto, task.traceId(), task.ownerUserId(), dto.getProjectId(), null);
            response.setTask(childTask);
            response.setNormalizedRequest(dto);
            response.setSummary(buildSummary(route, materials, dto.getSubtitle(), dto.getBgmUrl()));
            return response;
        }

        if (ROUTE_DIGITAL_HUMAN.equals(route)) {
            DigitalHumanDTO dto = buildDigitalHumanRequest(request, materials);
            DigitalHumanGenerateResponse childTask = viduDigitalHumanService.generate(dto, task.traceId(), task.ownerUserId(), null);
            response.setDigitalHumanTask(childTask);
            response.setNormalizedRequest(dto);
            response.setSummary(buildSummary(route, materials, null, null));
            return response;
        }

        if (ROUTE_GENERAL_VIDEO.equals(route)) {
            ImageReferenceDTO dto = buildReferenceVideoRequest(request, materials);
            TaskItem childTask = videoAsyncTaskService.createReferenceVideoTask(
                    dto, task.traceId(), task.ownerUserId(), dto.getProjectId(), null);
            response.setTask(childTask);
            response.setNormalizedRequest(dto);
            response.setSummary(buildSummary(route, materials, null, null));
            return response;
        }

        if (ROUTE_MATERIAL_MIX.equals(route)) {
            MaterialMixResult mix = buildMaterialMix(task, request, materials);
            response.setOutputAsset(mix.outputAsset());
            response.setNormalizedRequest(mix);
            response.setSummary(buildSummary(route, materials, null, null)
                    + (Boolean.TRUE.equals(mix.subtitleApplied()) ? "；字幕已按混剪时间轴烧录" : "")
                    + (Boolean.TRUE.equals(mix.bgmApplied()) ? "；BGM 已后期混入" : "")
                    + "；已完成基础混剪并保存到资产中心，未调用视频生成模型。");
            return response;
        }

        throw new BusinessException(40000, "当前素材组合暂不支持自动成片");
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(50000, "一键成片任务序列化失败");
        }
    }

    private List<Material> loadMaterials(QuickRenderRequest request, OptionalLong viewer) {
        Map<Long, Material> unique = new LinkedHashMap<>();
        for (Long assetId : request.getAssetIds()) {
            if (assetId == null || unique.containsKey(assetId)) {
                continue;
            }
            AssetItem asset = assetService.getAssetForViewer(assetId, viewer);
            if (asset == null) {
                throw new BusinessException(40400, "素材资产不存在 assetId=" + assetId);
            }
            String role = firstText(roleFromRequest(request, assetId), inferRole(asset));
            String text = firstText(textFromRequest(request, assetId), textFromAsset(asset, role, viewer));
            if ("car_model_bundle".equals(role)) {
                text = firstText(text, carBundleTextFromMetadata(asset, viewer));
            }
            unique.put(assetId, new Material(asset, role, text));
        }
        return new ArrayList<>(unique.values());
    }

    private String roleFromRequest(QuickRenderRequest request, Long assetId) {
        if (request.getAssetRoles() == null || assetId == null) {
            return null;
        }
        return normalizeRole(request.getAssetRoles().get(String.valueOf(assetId)));
    }

    private String textFromRequest(QuickRenderRequest request, Long assetId) {
        if (request.getAssetTextContents() == null || assetId == null) {
            return null;
        }
        return trimToNull(request.getAssetTextContents().get(String.valueOf(assetId)));
    }

    private String textFromAsset(AssetItem asset, String role, OptionalLong viewer) {
        if (asset == null || asset.assetId() == null || !shouldLoadTextContent(asset, role)) {
            return null;
        }
        try {
            AssetContent content = assetService.getGeneratedAssetContent(asset.assetId(), viewer);
            return content == null ? null : trimToNull(content.content());
        } catch (BusinessException ex) {
            log.debug("Skip loading quick-render text asset content. assetId={}, role={}, code={}",
                    asset.assetId(), role, ex.getCode());
            return null;
        } catch (Exception ex) {
            log.debug("Skip loading quick-render text asset content. assetId={}, role={}, reason={}",
                    asset.assetId(), role, ex.getMessage());
            return null;
        }
    }

    private boolean shouldLoadTextContent(AssetItem asset, String role) {
        String normalizedRole = normalizeRole(role);
        if ("car_model_bundle".equals(normalizedRole)
                || "voice_script".equals(normalizedRole)
                || "benchmark_json".equals(normalizedRole)
                || "storyboard_json".equals(normalizedRole)
                || "subtitle".equals(normalizedRole)) {
            return true;
        }
        String type = lower(asset.assetType());
        String mime = lower(asset.mimeType());
        String name = lower(asset.fileName());
        return "json".equals(type)
                || "text".equals(type)
                || mime.contains("json")
                || mime.startsWith("text/")
                || name.endsWith(".json")
                || name.endsWith(".txt")
                || name.endsWith(".md");
    }

    private String inferRole(AssetItem asset) {
        String type = lower(asset.assetType());
        String mime = lower(asset.mimeType());
        String name = lower(asset.fileName());
        if ("audio".equals(type) || mime.startsWith("audio/")) {
            if (name.contains("bgm") || name.contains("music") || name.contains("背景")) {
                return "bgm";
            }
            if (name.contains("ref") || name.contains("reference")) {
                return "reference_audio";
            }
            return "voiceover";
        }
        if ("video".equals(type) || mime.startsWith("video/")) {
            if (name.contains("host") || name.contains("avatar") || name.contains("口播") || name.contains("主播")) {
                return "host_video";
            }
            if (name.contains("ref") || name.contains("reference") || name.contains("对标")) {
                return "reference_video";
            }
            return "material_video";
        }
        if ("json".equals(type) || mime.contains("json") || name.endsWith(".json")) {
            if (name.contains("car_model_bundle") || name.contains("车型素材包") || metadataContains(asset, "car_model_bundle")) {
                return "car_model_bundle";
            }
            if (name.contains("storyboard") || name.contains("分镜")) {
                return "storyboard_json";
            }
            if (name.contains("benchmark") || name.contains("对标")) {
                return "benchmark_json";
            }
            return "storyboard_json";
        }
        if ("text".equals(type) || mime.startsWith("text/") || name.endsWith(".srt") || name.endsWith(".txt")) {
            if (name.contains("subtitle") || name.contains("srt") || name.contains("字幕")) {
                return "subtitle";
            }
            return "voice_script";
        }
        if ("image".equals(type) || mime.startsWith("image/")) {
            if (name.contains("host") || name.contains("avatar") || name.contains("主播") || name.contains("数字人")) {
                return "host_image";
            }
            if (name.contains("side") || name.contains("侧")) {
                return "car_exterior_side";
            }
            if (name.contains("rear") || name.contains("back") || name.contains("尾")) {
                return "car_exterior_rear";
            }
            if (name.contains("interior") || name.contains("内饰") || name.contains("dashboard") || name.contains("座椅")) {
                return "car_interior_dashboard";
            }
            if (name.contains("sunroof") || name.contains("天窗") || name.contains("全景天幕")) {
                return "car_detail_sunroof";
            }
            if (name.contains("wheel") || name.contains("轮")) {
                return "car_detail_wheel";
            }
            if (name.contains("logo") || name.contains("标")) {
                return "car_detail_logo";
            }
            if (name.contains("light") || name.contains("灯")) {
                return "car_detail_light";
            }
            if (name.contains("showroom") || name.contains("展厅") || name.contains("门店") || name.contains("店内")) {
                return "scene_showroom";
            }
            if (name.contains("road") || name.contains("highway") || name.contains("道路") || name.contains("公路") || name.contains("山路")) {
                return "scene_road";
            }
            if (name.contains("night") || name.contains("夜景") || name.contains("夜间")) {
                return "scene_night";
            }
            if (name.contains("outdoor") || name.contains("city") || name.contains("户外") || name.contains("城市") || name.contains("场景")) {
                return "scene_outdoor";
            }
            if (name.contains("car") || name.contains("front") || name.contains("车")) {
                return "car_exterior_front";
            }
            return "scene_outdoor";
        }
        return "material";
    }

    private String resolveRoute(QuickRenderRequest request, List<Material> materials) {
        String intent = lower(trimToNull(request.getIntent()));
        if ("car_sales".equals(intent)) {
            return ROUTE_CAR_SALES;
        }
        if ("digital_human".equals(intent)) {
            return ROUTE_DIGITAL_HUMAN;
        }
        if ("general_video".equals(intent)) {
            return ROUTE_GENERAL_VIDEO;
        }
        if ("material_mix".equals(intent)) {
            return ROUTE_MATERIAL_MIX;
        }
        if (materials.stream().anyMatch(m -> "car_model_bundle".equals(m.role())
                || m.role().startsWith("car_exterior") || m.role().startsWith("scene_"))) {
            return ROUTE_CAR_SALES;
        }
        if (hasRole(materials, "host_image") && (hasRole(materials, "voiceover") || hasRole(materials, "voice_script"))) {
            return ROUTE_DIGITAL_HUMAN;
        }
        long videoCount = materials.stream().filter(Material::isVideo).count();
        long imageCount = materials.stream().filter(Material::isImage).count();
        if (videoCount > 0 && videoCount >= imageCount) {
            return ROUTE_MATERIAL_MIX;
        }
        if (imageCount > 0) {
            return ROUTE_GENERAL_VIDEO;
        }
        throw new BusinessException(40000, "未识别到可用于成片的图片、视频或数字人素材");
    }

    private CarSalesVideoDTO buildCarSalesRequest(QuickRenderRequest request, List<Material> materials,
                                                  OptionalLong viewer) {
        List<CarBundleImage> bundleImages = extractCarBundleImages(materials, viewer);
        List<String> carImages = selectQuickCarReferenceUrls(request, materials, bundleImages);
        if (carImages.isEmpty()) {
            throw new BusinessException(40000, "汽车销售成片至少需要 1 张车辆图片");
        }
        String carSalesTemplate = classifyCarSalesTemplate(request);

        CarSalesVideoDTO dto = new CarSalesVideoDTO();
        int segmentCount = normalizeQuickSegmentCount(request.getSegmentCount());
        dto.setProjectId(request.getProjectId());
        dto.setCarImageUrls(carImages);
        dto.setSourceAssetIds(materials.stream().map(m -> m.asset().assetId()).toList());
        dto.setAssetRoleBindings(buildCarSalesAssetRoleBindings(request, materials, bundleImages));
        dto.setModel(normalizeAuto(request.getModel()));
        dto.setSegmentCount(segmentCount);
        dto.setSegmentDuration(normalizeQuickSegmentDuration(request.getSegmentDuration()));
        dto.setAspectRatio(normalizeAspectRatio(request.getAspectRatio()));
        dto.setCoverAssetId(request.getCoverAssetId());
        dto.setCoverUrl(resolveQuickRenderCoverUrl(request, materials, bundleImages, carImages));
        dto.setPrompt(buildCarPrompt(request, materials, request.getSubtitleMode()));
        dto.setScriptContext(firstRoleText(materials, "storyboard_json", "benchmark_json"));
        dto.setIgnoredStoryboardFields(List.of("content", "backgroundMusic"));
        dto.setSalesTemplate(carSalesTemplate);
        dto.setBrandModel(extractQuickGoalValue(request.getGoalText(), "车型"));
        dto.setSellingPoints(extractQuickGoalValue(request.getGoalText(), "核心卖点"));
        dto.setCallToAction(extractQuickGoalValue(request.getGoalText(), "行动号召"));
        dto.setTestBatch(trimToNull(request.getTestBatch()));
        dto.setSampleId(trimToNull(request.getSampleId()));
        dto.setOutputPurpose(trimToDefault(request.getOutputPurpose(), "car_sales_golden_path"));
        dto.setReviewer(trimToNull(request.getReviewer()));
        String finalVoiceText = trimToNull(request.getFinalVoiceText());
        if (StringUtils.hasText(finalVoiceText)) {
            dto.setFinalVoiceText(finalVoiceText);
            dto.setStrictVoiceText(Boolean.TRUE);
        }
        String subtitle = resolveSubtitle(request, materials);
        dto.setSubtitle(subtitle);
        dto.setSubtitleMode(carSubtitleModeForRequest(request, subtitle));
        dto.setSubtitleOverlay(request.getSubtitleOverlay());
        dto.setHeadlineOverlay(request.getHeadlineOverlay());
        if (shouldUseScriptTimelineSubtitle(subtitle)) {
            dto.setSubtitleTimingMode("script_timeline");
        }
        dto.setSubtitleLanguage(normalizeSubtitleLanguage(request.getSubtitleLanguage()));
        dto.setNativeVoiceLanguage(normalizeNativeVoiceLanguage(request.getNativeVoiceLanguage()));
        dto.setNativeVoiceStyle(trimToNull(request.getNativeVoiceStyle()));
        dto.setNativeSpeechStyle(trimToNull(request.getNativeSpeechStyle()));

        Material hostImage = firstRole(materials, "host_image");
        if (hostImage != null) {
            dto.setHostImageUrl(hostImage.url());
            dto.setHostAppearanceEnabled(!Boolean.FALSE.equals(request.getHostAppearanceEnabled()));
        } else {
            dto.setHostAppearanceEnabled(false);
        }
        Material hostVideo = firstRole(materials, "host_video");
        if (hostVideo != null) {
            dto.setHostVideoUrl(hostVideo.url());
        }
        Material bgm = firstRole(materials, "bgm");
        if (bgm != null && !"none".equalsIgnoreCase(trimToDefault(request.getAudioPolicy(), "auto"))) {
            dto.setBgmUrl(bgm.url());
        }
        Material voice = firstRole(materials, "voiceover");
        Material referenceAudio = firstRole(materials, "reference_audio");
        if (voice != null && !"none".equalsIgnoreCase(trimToDefault(request.getAudioPolicy(), "auto"))) {
            dto.setAudioUrl(voice.url());
            dto.setAudioMode("post_mix");
        } else if (referenceAudio != null && !"none".equalsIgnoreCase(trimToDefault(request.getAudioPolicy(), "auto"))) {
            dto.setAudioUrl(referenceAudio.url());
            dto.setAudioMode(segmentCount == 1 ? "reference" : "post_mix");
        } else {
            dto.setAudioMode("model_native");
            dto.setVoicePolicy("model_native");
            dto.setScenes(buildLightScenes(carImages, request, materials, segmentCount));
        }
        if ("upload".equals(effectiveSubtitleMode(request.getSubtitleMode(), request.getBurnInSubtitle()))) {
            dto.setScenes(buildLightScenes(carImages, request, materials, segmentCount));
        }
        return dto;
    }

    private List<String> selectQuickCarReferenceUrls(QuickRenderRequest request, List<Material> materials,
                                                     List<CarBundleImage> bundleImages) {
        List<String> selected = new ArrayList<>();
        addExplicitUrls(selected, request == null ? null : request.getImageUrls(), MAX_QUICK_CAR_REFERENCE_IMAGES);
        addBindingImageUrls(selected, request == null ? null : request.getAssetRoleBindings(),
                MAX_QUICK_CAR_REFERENCE_IMAGES, false);
        addMaterialUrlsByRoles(selected, materials, MAX_QUICK_CAR_REFERENCE_IMAGES,
                "car_exterior_front", "car_exterior_side", "car_exterior_rear", "car_exterior_45");
        addBundleUrlsByRoles(selected, bundleImages, MAX_QUICK_CAR_REFERENCE_IMAGES,
                "car_exterior_front", "car_exterior_side", "car_exterior_rear", "car_exterior_45");
        addMaterialUrlsByRoles(selected, materials, MAX_QUICK_CAR_REFERENCE_IMAGES,
                "car_interior_dashboard", "car_interior_front_seat", "car_interior_back_seat");
        addBundleUrlsByRoles(selected, bundleImages, MAX_QUICK_CAR_REFERENCE_IMAGES,
                "car_interior_dashboard", "car_interior_front_seat", "car_interior_back_seat");
        addMaterialUrlsByRoles(selected, materials, MAX_QUICK_CAR_REFERENCE_IMAGES,
                "car_detail_light", "car_detail_wheel", "car_detail_logo", "car_detail_sunroof");
        addBundleUrlsByRoles(selected, bundleImages, MAX_QUICK_CAR_REFERENCE_IMAGES,
                "car_detail_light", "car_detail_wheel", "car_detail_logo", "car_detail_sunroof");
        addMaterialUrlsByRolePrefix(selected, materials, MAX_QUICK_CAR_REFERENCE_IMAGES, "car_");
        addBundleUrlsByRolePrefix(selected, bundleImages, MAX_QUICK_CAR_REFERENCE_IMAGES, "car_");
        if (selected.isEmpty()) {
            addMaterialImageUrls(selected, materials, MAX_QUICK_CAR_REFERENCE_IMAGES, false);
            addBundleImageUrls(selected, bundleImages, MAX_QUICK_CAR_REFERENCE_IMAGES, false);
        }
        if (selected.isEmpty()) {
            addMaterialImageUrls(selected, materials, MAX_QUICK_CAR_REFERENCE_IMAGES, true);
            addBundleImageUrls(selected, bundleImages, MAX_QUICK_CAR_REFERENCE_IMAGES, true);
            addExplicitUrls(selected, request == null ? null : request.getSceneImageUrls(), MAX_QUICK_CAR_REFERENCE_IMAGES);
            addBindingImageUrls(selected, request == null ? null : request.getAssetRoleBindings(),
                    MAX_QUICK_CAR_REFERENCE_IMAGES, true);
        }
        return selected;
    }

    private String resolveQuickRenderCoverUrl(QuickRenderRequest request, List<Material> materials,
                                              List<CarBundleImage> bundleImages, List<String> carImages) {
        if (request == null) {
            return firstTextFromList(carImages);
        }
        Material coverAsset = materialByAssetId(materials, request.getCoverAssetId());
        if (coverAsset != null) {
            return firstText(coverAsset.asset().thumbnailUrl(), coverAsset.url());
        }
        String explicit = trimToNull(request.getCoverUrl());
        if (StringUtils.hasText(explicit)) {
            return explicit;
        }
        for (Material material : materials == null ? List.<Material>of() : materials) {
            if (material != null && material.isImage()) {
                String url = firstText(material.asset().thumbnailUrl(), material.url());
                if (StringUtils.hasText(url)) {
                    return url;
                }
            }
        }
        if (bundleImages != null) {
            for (CarBundleImage image : bundleImages) {
                if (image != null && StringUtils.hasText(image.url())) {
                    return image.url();
                }
            }
        }
        return firstTextFromList(carImages);
    }

    private Material materialByAssetId(List<Material> materials, Long assetId) {
        if (materials == null || assetId == null) {
            return null;
        }
        for (Material material : materials) {
            if (material != null && material.asset() != null && assetId.equals(material.asset().assetId())) {
                return material;
            }
        }
        return null;
    }

    private String firstTextFromList(List<String> values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private void addMaterialUrlsByRoles(List<String> selected, List<Material> materials, int max, String... roles) {
        if (materials == null || roles == null) {
            return;
        }
        for (String role : roles) {
            for (Material material : materials) {
                if (selected.size() >= max) {
                    return;
                }
                if (material != null && material.isImage() && lower(material.role()).equals(role)) {
                    addUniqueUrl(selected, material.url(), max);
                }
            }
        }
    }

    private void addExplicitUrls(List<String> selected, List<String> urls, int max) {
        if (urls == null) {
            return;
        }
        for (String url : urls) {
            if (selected.size() >= max) {
                return;
            }
            addUniqueUrl(selected, url, max);
        }
    }

    private void addBindingImageUrls(List<String> selected, List<CarSalesVideoDTO.AssetRoleBinding> bindings,
                                     int max, boolean includeScene) {
        if (bindings == null) {
            return;
        }
        for (CarSalesVideoDTO.AssetRoleBinding binding : bindings) {
            if (selected.size() >= max) {
                return;
            }
            if (binding == null || !StringUtils.hasText(binding.getUrl())) {
                continue;
            }
            String role = lower(binding.getAssetRole());
            if (!includeScene && role.startsWith("scene_")) {
                continue;
            }
            addUniqueUrl(selected, binding.getUrl(), max);
        }
    }

    private void addBundleUrlsByRoles(List<String> selected, List<CarBundleImage> bundleImages, int max, String... roles) {
        if (bundleImages == null || roles == null) {
            return;
        }
        for (String role : roles) {
            for (CarBundleImage image : bundleImages) {
                if (selected.size() >= max) {
                    return;
                }
                if (image != null && lower(image.role()).equals(role)) {
                    addUniqueUrl(selected, image.url(), max);
                }
            }
        }
    }

    private void addMaterialUrlsByRolePrefix(List<String> selected, List<Material> materials, int max, String prefix) {
        if (materials == null || !StringUtils.hasText(prefix)) {
            return;
        }
        for (Material material : materials) {
            if (selected.size() >= max) {
                return;
            }
            if (material != null && material.isImage() && lower(material.role()).startsWith(prefix)) {
                addUniqueUrl(selected, material.url(), max);
            }
        }
    }

    private void addBundleUrlsByRolePrefix(List<String> selected, List<CarBundleImage> bundleImages, int max, String prefix) {
        if (bundleImages == null || !StringUtils.hasText(prefix)) {
            return;
        }
        for (CarBundleImage image : bundleImages) {
            if (selected.size() >= max) {
                return;
            }
            if (image != null && lower(image.role()).startsWith(prefix)) {
                addUniqueUrl(selected, image.url(), max);
            }
        }
    }

    private void addMaterialImageUrls(List<String> selected, List<Material> materials, int max, boolean includeScene) {
        if (materials == null) {
            return;
        }
        for (Material material : materials) {
            if (selected.size() >= max) {
                return;
            }
            if (material == null || !material.isImage()) {
                continue;
            }
            String role = lower(material.role());
            if (!includeScene && (role.startsWith("scene_") || "host_image".equals(role))) {
                continue;
            }
            addUniqueUrl(selected, material.url(), max);
        }
    }

    private void addBundleImageUrls(List<String> selected, List<CarBundleImage> bundleImages, int max, boolean includeScene) {
        if (bundleImages == null) {
            return;
        }
        for (CarBundleImage image : bundleImages) {
            if (selected.size() >= max) {
                return;
            }
            if (image == null) {
                continue;
            }
            String role = lower(image.role());
            if (!includeScene && role.startsWith("scene_")) {
                continue;
            }
            addUniqueUrl(selected, image.url(), max);
        }
    }

    private void addUniqueUrl(List<String> selected, String url, int max) {
        if (selected.size() >= max || !StringUtils.hasText(url)) {
            return;
        }
        String normalizedUrl = url.trim();
        if (!selected.contains(normalizedUrl)) {
            selected.add(normalizedUrl);
        }
    }

    private List<CarSalesVideoDTO.AssetRoleBinding> buildCarSalesAssetRoleBindings(QuickRenderRequest request,
                                                                                   List<Material> materials,
                                                                                   List<CarBundleImage> bundleImages) {
        List<CarSalesVideoDTO.AssetRoleBinding> bindings = new ArrayList<>();
        if (request != null && request.getAssetRoleBindings() != null) {
            for (CarSalesVideoDTO.AssetRoleBinding source : request.getAssetRoleBindings()) {
                addAssetRoleBinding(bindings, copyAssetRoleBinding(source));
            }
        }
        for (Material material : materials) {
            if (material == null || !StringUtils.hasText(material.url())) {
                continue;
            }
            CarSalesVideoDTO.AssetRoleBinding binding = new CarSalesVideoDTO.AssetRoleBinding();
            binding.setAssetId(material.asset().assetId());
            binding.setUrl(material.url());
            binding.setAssetType(material.asset().assetType());
            binding.setAssetRole(material.role());
            binding.setLabel(material.asset().fileName());
            addAssetRoleBinding(bindings, binding);
        }
        if (bundleImages != null) {
            for (CarBundleImage image : bundleImages) {
                CarSalesVideoDTO.AssetRoleBinding binding = new CarSalesVideoDTO.AssetRoleBinding();
                binding.setAssetId(image.assetId());
                binding.setUrl(image.url());
                binding.setAssetType("IMAGE");
                binding.setAssetRole(image.role());
                binding.setLabel(image.label());
                addAssetRoleBinding(bindings, binding);
            }
        }
        return bindings;
    }

    private CarSalesVideoDTO.AssetRoleBinding copyAssetRoleBinding(CarSalesVideoDTO.AssetRoleBinding source) {
        if (source == null || !StringUtils.hasText(source.getUrl())) {
            return null;
        }
        CarSalesVideoDTO.AssetRoleBinding binding = new CarSalesVideoDTO.AssetRoleBinding();
        binding.setAssetId(source.getAssetId());
        binding.setUrl(source.getUrl());
        binding.setAssetType(firstText(source.getAssetType(), "IMAGE"));
        binding.setAssetRole(normalizeRole(firstText(source.getAssetRole(), "car_exterior_front")));
        binding.setLabel(source.getLabel());
        binding.setCarPackageId(source.getCarPackageId());
        binding.setCarIndex(source.getCarIndex());
        return binding;
    }

    private void addAssetRoleBinding(List<CarSalesVideoDTO.AssetRoleBinding> bindings,
                                     CarSalesVideoDTO.AssetRoleBinding binding) {
        if (binding == null || !StringUtils.hasText(binding.getUrl())) {
            return;
        }
        String url = binding.getUrl().trim();
        for (CarSalesVideoDTO.AssetRoleBinding existing : bindings) {
            if (existing != null && url.equals(existing.getUrl())) {
                return;
            }
        }
        binding.setUrl(url);
        bindings.add(binding);
    }

    private List<CarBundleImage> extractCarBundleImages(List<Material> materials, OptionalLong viewer) {
        if (materials == null || materials.isEmpty()) {
            return List.of();
        }
        List<CarBundleImage> images = new ArrayList<>();
        for (Material material : materials) {
            if (!"car_model_bundle".equals(material.role()) || !StringUtils.hasText(material.text())) {
                continue;
            }
            try {
                JsonNode root = objectMapper.readTree(material.text());
                JsonNode previewRows = root.path("previewImages");
                if (previewRows.isArray()) {
                    for (JsonNode row : previewRows) {
                        if (row.isTextual()) {
                            addCarBundleImage(images, row.asText(), "car_exterior_front", "车型素材", null);
                        } else {
                            addCarBundleImageFromJson(images, row);
                        }
                        if (images.size() >= 9) {
                            return images;
                        }
                    }
                }
                JsonNode rows = firstArrayJson(root, "images", "vehicleImages", "carImages", "materials", "items", "assets");
                if (!rows.isArray()) {
                    continue;
                }
                for (JsonNode row : rows) {
                    addCarBundleImageFromJson(images, row);
                    if (images.size() >= 9) {
                        return images;
                    }
                }
                addCarBundleComponentAssets(images, root, viewer);
                if (images.size() >= 9) {
                    return images;
                }
                JsonNode metadataRoot = parseJsonNode(material.asset().metadataJson());
                addCarBundleComponentAssets(images, metadataRoot, viewer);
                if (images.size() >= 9) {
                    return images;
                }
            } catch (Exception ignored) {
                // 非标准车型包不阻断一键成片，后续会按普通素材继续判断。
            }
        }
        return images;
    }

    private void addCarBundleComponentAssets(List<CarBundleImage> images, JsonNode root, OptionalLong viewer) {
        for (Long assetId : componentAssetIdsFromJson(root)) {
            if (images.size() >= 9) {
                return;
            }
            try {
                AssetItem asset = assetService.getAssetForViewer(assetId, viewer);
                if (asset == null || !"image".equals(lower(asset.assetType()))) {
                    continue;
                }
                JsonNode metadata = parseJsonNode(asset.metadataJson());
                String role = normalizeRole(firstTextJson(metadata, "assetRole", "role", "type", "category"));
                String label = firstText(asset.fileName(), firstTextJson(metadata, "label", "name", "title"));
                addCarBundleImage(images, firstText(asset.fileUrl(), asset.thumbnailUrl()), role, label, asset.assetId());
            } catch (Exception ex) {
                log.debug("Skip car bundle component asset. assetId={}, reason={}", assetId, ex.getMessage());
            }
        }
    }

    private void addCarBundleImageFromJson(List<CarBundleImage> images, JsonNode row) {
        String url = firstImageUrlJson(row);
        if (!StringUtils.hasText(url)) {
            return;
        }
        String role = normalizeRole(firstTextJson(row, "role", "assetRole", "type", "category", "position"));
        String label = firstTextJson(row, "label", "name", "fileName", "title");
        Long assetId = firstLongJson(row, "assetId", "id");
        addCarBundleImage(images, url, role, label, assetId);
    }

    private void addCarBundleImage(List<CarBundleImage> images, String url, String role, String label, Long assetId) {
        if (!StringUtils.hasText(url)) {
            return;
        }
        String normalizedUrl = url.trim();
        for (CarBundleImage existing : images) {
            if (existing != null && normalizedUrl.equals(existing.url())) {
                return;
            }
        }
        String normalizedRole = StringUtils.hasText(role) ? role : "car_exterior_front";
        images.add(new CarBundleImage(normalizedUrl, normalizedRole,
                StringUtils.hasText(label) ? label.trim() : "车型素材", assetId));
    }

    private List<CarSalesVideoDTO.Scene> buildLightScenes(List<String> carImages, QuickRenderRequest request,
                                                          List<Material> materials, int segmentCount) {
        int count = normalizeQuickSegmentCount(segmentCount);
        List<String> titles = quickSceneTitles(count);
        List<String> prompts = quickScenePrompts(count);
        List<QuickRenderRequest.GeneratedStoryboardShot> generatedStoryboard =
                request.getGeneratedStoryboard() == null ? List.of() : request.getGeneratedStoryboard();
        String voiceScript = firstRoleText(materials, "voice_script");
        if (StringUtils.hasText(request.getFinalVoiceText())) {
            voiceScript = request.getFinalVoiceText().trim();
        }
        if ("upload".equals(effectiveSubtitleMode(request.getSubtitleMode(), request.getBurnInSubtitle()))) {
            voiceScript = firstText(request.getFinalVoiceText(), request.getCustomSubtitle(), firstRoleText(materials, "subtitle"));
        }
        List<String> voiceParts = splitTextForSceneCount(voiceScript, count);
        List<CarSalesVideoDTO.Scene> scenes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            QuickRenderRequest.GeneratedStoryboardShot generatedShot =
                    i < generatedStoryboard.size() ? generatedStoryboard.get(i) : null;
            String visualPrompt = generatedShot != null && StringUtils.hasText(generatedShot.getVisual())
                    ? generatedShot.getVisual().trim()
                    : prompts.get(i);
            CarSalesVideoDTO.Scene scene = new CarSalesVideoDTO.Scene();
            scene.setSegmentIndex(i + 1);
            scene.setTitle(titles.get(i));
            scene.setVisualPrompt(visualPrompt);
            scene.setPrompt(visualPrompt);
            scene.setImageUrls(carImages);
            scene.setDuration(generatedShot != null && generatedShot.getDuration() != null
                    ? normalizeQuickSegmentDuration(generatedShot.getDuration())
                    : normalizeQuickSegmentDuration(request.getSegmentDuration()));
            scene.setVoiceText(i < voiceParts.size() && StringUtils.hasText(voiceParts.get(i))
                    ? voiceParts.get(i)
                    : defaultVoiceText(i, request.getGoalText()));
            scenes.add(scene);
        }
        return scenes;
    }

    private List<String> quickSceneTitles(int count) {
        if (count <= 1) {
            return List.of("一键销售短片");
        }
        if (count == 2) {
            return List.of("外观开场", "卖点与行动号召");
        }
        if (count == 3) {
            return List.of("外观开场", "核心卖点", "行动收口");
        }
        return List.of("外观开场", "核心卖点", "内饰/细节补强", "行动收口", "用车场景", "优惠收口");
    }

    private List<String> quickScenePrompts(int count) {
        if (count <= 1) {
            return List.of("生成一条完整汽车销售短视频，内部包含 2-4 个自然镜头：外观开场、核心卖点展示、内饰或细节补强、咨询或到店试驾收口。镜头稳定，节奏干净，禁止画面原生文字和无关人物。");
        }
        if (count == 2) {
            return List.of(
                    "展示车辆外观、车头和车身线条，镜头稳定推进，突出第一眼吸引力。",
                    "结合素材展示核心卖点，并自然收束到咨询或到店试驾行动号召。"
            );
        }
        if (count == 3) {
            return List.of(
                    "展示车辆外观、车头和车身线条，镜头稳定推进，突出第一眼吸引力。",
                    "结合上传素材展示核心卖点，优先使用外观、内饰或细节中最匹配的参考图。",
                    "展示门店、试驾、道路或车辆高光细节，强化咨询和预约试驾转化。"
            );
        }
        return List.of(
                "展示车辆外观、车头和车身线条，镜头稳定推进，突出第一眼吸引力。",
                "结合上传素材展示核心卖点，优先使用外观、内饰或细节中最匹配的参考图。",
                "展示内饰、座椅、空间或配置细节，强调舒适与质感。",
                "展示门店、试驾或道路场景，强化咨询和预约试驾转化。",
                "展示城市通勤、家庭出行或周末短途场景，让车辆与真实生活需求结合。",
                "用车身高光细节、权益氛围和咨询引导收口，强化立即行动。"
        );
    }

    private int normalizeQuickSegmentCount(Integer value) {
        if (value == null) {
            return DEFAULT_SEGMENT_COUNT;
        }
        return Math.max(1, Math.min(MAX_SEGMENT_COUNT, value));
    }

    private int normalizeQuickSegmentDuration(Integer value) {
        if (value == null) {
            return QUICK_SEGMENT_DURATION_SECONDS;
        }
        return Math.max(4, Math.min(15, value));
    }

    private List<String> splitTextForSceneCount(String text, int count) {
        if (!StringUtils.hasText(text) || count <= 0) {
            return List.of();
        }
        String normalized = text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[ \\t]+", " ")
                .trim();
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            current.append(ch);
            if ("\n。！？!?；;.".indexOf(ch) >= 0) {
                String sentence = current.toString().trim();
                if (StringUtils.hasText(sentence)) {
                    sentences.add(sentence);
                }
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            String sentence = current.toString().trim();
            if (StringUtils.hasText(sentence)) {
                sentences.add(sentence);
            }
        }
        if (sentences.isEmpty()) {
            sentences.add(normalized);
        }
        List<StringBuilder> buckets = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            buckets.add(new StringBuilder());
        }
        for (int i = 0; i < sentences.size(); i++) {
            StringBuilder bucket = buckets.get(Math.min(count - 1, i * count / Math.max(1, sentences.size())));
            if (!bucket.isEmpty()) {
                bucket.append('\n');
            }
            bucket.append(sentences.get(i));
        }
        return buckets.stream()
                .map(StringBuilder::toString)
                .map(String::trim)
                .toList();
    }

    private ImageReferenceDTO buildReferenceVideoRequest(QuickRenderRequest request, List<Material> materials) {
        List<String> imageUrls = materials.stream()
                .filter(Material::isImage)
                .map(Material::url)
                .filter(StringUtils::hasText)
                .limit(9)
                .toList();
        if (imageUrls.isEmpty()) {
            throw new BusinessException(40000, "通用图生视频至少需要 1 张图片");
        }
        ImageReferenceDTO dto = new ImageReferenceDTO();
        dto.setProjectId(request.getProjectId());
        dto.setImageUrls(imageUrls);
        dto.setPrompt(buildGeneralPrompt(request, materials));
        dto.setRatio(normalizeAspectRatio(request.getAspectRatio()));
        dto.setDuration(5);
        dto.setGenerateAudio(false);
        dto.setModel(normalizeAuto(request.getModel()));
        return dto;
    }

    private DigitalHumanDTO buildDigitalHumanRequest(QuickRenderRequest request, List<Material> materials) {
        Material hostImage = firstRole(materials, "host_image");
        if (hostImage == null) {
            hostImage = materials.stream().filter(Material::isImage).findFirst().orElse(null);
        }
        if (hostImage == null) {
            throw new BusinessException(40000, "数字人口播需要 host_image 主播图片");
        }
        Material voice = firstRole(materials, "voiceover");
        String text = firstText(request.getFinalVoiceText(), firstRoleText(materials, "voice_script", "subtitle"));
        if (voice == null && !StringUtils.hasText(text)) {
            throw new BusinessException(40000, "数字人口播需要口播音频或口播文案");
        }
        DigitalHumanDTO dto = new DigitalHumanDTO();
        dto.setProjectId(request.getProjectId());
        dto.setImageUrl(hostImage.url());
        dto.setAudioUrl(voice == null ? null : voice.url());
        dto.setText(text);
        dto.setPrompt(trimToNull(request.getGoalText()));
        dto.setResolution("720p");
        dto.setModel(normalizeAuto(request.getModel()));
        return dto;
    }

    private String resolveSubtitle(QuickRenderRequest request, List<Material> materials) {
        String mode = lower(trimToDefault(request.getSubtitleMode(), "auto"));
        if ("off".equals(mode) || Boolean.FALSE.equals(request.getBurnInSubtitle())) {
            return "无";
        }
        if ("upload".equals(mode)) {
            String subtitle = firstText(request.getFinalVoiceText(), request.getCustomSubtitle(), firstRoleText(materials, "subtitle"));
            if (!StringUtils.hasText(subtitle)) {
                throw new BusinessException(40000, "字幕模式为上传时，需要输入自定义字幕或提供 subtitle 文本素材");
            }
            return subtitle;
        }
        if (StringUtils.hasText(trimToNull(request.getFinalVoiceText()))
                || hasRole(materials, "voiceover") || hasRole(materials, "reference_audio")
                || hasRole(materials, "voice_script") || hasRole(materials, "car_model_bundle")) {
            return "自动生成";
        }
        return "无";
    }

    private String buildCarPrompt(QuickRenderRequest request, List<Material> materials, String subtitleMode) {
        List<String> parts = new ArrayList<>();
        if (StringUtils.hasText(request.getGoalText())) {
            parts.add(request.getGoalText().trim());
        }
        if (hasRole(materials, "scene_showroom")) {
            parts.add("适合汽车展厅销售场景");
        }
        if (hasRole(materials, "scene_outdoor")) {
            parts.add("使用户外或城市生活场景作为背景参考");
        }
        if (hasRole(materials, "scene_road")) {
            parts.add("包含道路试驾氛围");
        }
        if (hasRole(materials, "scene_night")) {
            parts.add("包含夜景门店或夜间灯光氛围");
        }
        String mode = effectiveSubtitleMode(subtitleMode, request.getBurnInSubtitle());
        if ("upload".equals(mode)) {
            parts.add("画面中禁止生成字幕、标题、价格贴纸、水印或任何文字，字幕只由后期烧录添加");
        } else if ("off".equals(mode)) {
            parts.add("画面中禁止生成字幕、标题、价格贴纸、水印或任何文字，后期也不添加字幕");
        } else if ("auto".equals(mode)) {
            parts.add("画面中禁止生成字幕、标题、价格贴纸、水印或任何文字，成片后由后端识别最终音轨生成字幕并烧录");
        }
        return parts.isEmpty() ? "自动根据素材生成汽车销售短视频，节奏干净，突出车型质感和到店转化。" : String.join("；", parts);
    }

    private String classifyCarSalesTemplate(QuickRenderRequest request) {
        String text = lower(String.join(" ",
                trimToDefault(request == null ? null : request.getGoalText(), ""),
                trimToDefault(request == null ? null : request.getFinalVoiceText(), ""),
                trimToDefault(request == null ? null : request.getCustomSubtitle(), "")
        ));
        if (containsAny(text, "优惠", "促销", "限时", "到店", "试驾", "置换", "订金", "补贴", "私信", "咨询")) {
            return "store_promotion";
        }
        if (containsAny(text, "油耗", "省油", "续航", "电耗", "充电", "混动", "增程", "纯电", "里程", "用车成本")) {
            return "efficiency_range";
        }
        if (containsAny(text, "智能", "座舱", "屏", "车机", "辅助驾驶", "智驾", "导航", "语音", "科技", "配置")) {
            return "smart_cabin";
        }
        if (containsAny(text, "颜值", "外观", "设计", "运动", "年轻", "线条", "大气", "豪华", "质感")) {
            return "exterior_style";
        }
        if (containsAny(text, "空间", "家庭", "一家", "后排", "座椅", "舒适", "亲子", "周末", "出行", "露营")) {
            return "family_space";
        }
        return "general_sales";
    }

    private boolean containsAny(String text, String... keywords) {
        if (!StringUtils.hasText(text) || keywords == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (StringUtils.hasText(keyword) && text.contains(lower(keyword))) {
                return true;
            }
        }
        return false;
    }

    private String buildGeneralPrompt(QuickRenderRequest request, List<Material> materials) {
        List<String> parts = new ArrayList<>();
        String goal = trimToNull(request.getGoalText());
        if (StringUtils.hasText(goal)) {
            parts.add(goal);
        } else {
            parts.add("根据参考图片生成自然流畅的短视频，镜头运动稳定，画面清晰，适合短视频发布。");
        }
        String mode = effectiveSubtitleMode(request.getSubtitleMode(), request.getBurnInSubtitle());
        if ("off".equals(mode)) {
            parts.add("画面中禁止生成字幕、标题、贴纸、水印或任何文字，后期也不添加字幕");
        } else if ("auto".equals(mode)) {
            parts.add("由 AI 在画面中自动生成简洁中文字幕，不走后期字幕烧录");
        }
        return String.join("；", parts);
    }

    private String effectiveSubtitleMode(String subtitleMode, Boolean burnInSubtitle) {
        if (Boolean.FALSE.equals(burnInSubtitle)) {
            return "off";
        }
        return lower(trimToDefault(subtitleMode, "auto"));
    }

    private String carSubtitleModeForRequest(QuickRenderRequest request, String subtitle) {
        String mode = effectiveSubtitleMode(request.getSubtitleMode(), request.getBurnInSubtitle());
        if ("upload".equals(mode) || shouldUseScriptTimelineSubtitle(subtitle)) {
            return "custom";
        }
        return mode;
    }

    private boolean shouldUseScriptTimelineSubtitle(String subtitle) {
        return StringUtils.hasText(subtitle)
                && !"无".equals(subtitle)
                && !"自动生成".equals(subtitle)
                && !"auto".equalsIgnoreCase(subtitle);
    }

    private MaterialMixResult buildMaterialMix(TaskItem task, QuickRenderRequest request, List<Material> materials) {
        if (task.ownerUserId() == null) {
            throw new BusinessException(40100, "素材混剪保存到私有资产失败：缺少登录用户信息");
        }
        List<Material> videos = materials.stream()
                .filter(Material::isVideo)
                .filter(material -> StringUtils.hasText(material.url()) || StringUtils.hasText(material.asset().filePath()))
                .limit(MAX_SEGMENT_COUNT)
                .toList();
        if (videos.isEmpty()) {
            throw new BusinessException(40000, "素材混剪至少需要 1 个视频素材");
        }

        TargetSize target = targetSize(request.getAspectRatio());
        String subtitleText = materialMixSubtitleText(request, materials, videos);
        List<String> clipTexts = materialMixClipTexts(videos, subtitleText);
        List<MaterialMixClip> timeline = buildMaterialMixTimeline(videos, clipTexts);
        List<MaterialMixSubtitleCue> subtitleCues = buildMaterialMixSubtitleCues(subtitleText, timeline);
        Material bgm = shouldApplyMaterialMixBgm(request) ? firstRole(materials, "bgm") : null;
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("huashuo-material-mix-" + task.taskId() + "-");
            List<Path> localVideos = new ArrayList<>();
            for (int i = 0; i < videos.size(); i++) {
                Path targetFile = tempDir.resolve("source-" + (i + 1) + guessMediaExtension(videos.get(i).url(), ".mp4"));
                copyMediaToFile(videos.get(i), targetFile, "视频素材");
                localVideos.add(targetFile);
            }
            Path baseFile = tempDir.resolve("material-mix-" + task.taskId() + "-base.mp4");
            runMaterialMixFfmpeg(localVideos, baseFile, target);
            Path processedFile = baseFile;
            if (!subtitleCues.isEmpty()) {
                Path srtFile = tempDir.resolve("material-mix-" + task.taskId() + ".srt");
                Path subtitledFile = tempDir.resolve("material-mix-" + task.taskId() + "-subtitle.mp4");
                writeMaterialMixSrt(srtFile, subtitleCues);
                burnMaterialMixSubtitles(processedFile, srtFile, subtitledFile);
                processedFile = subtitledFile;
            }
            if (bgm != null) {
                Path bgmFile = tempDir.resolve("material-mix-bgm-" + task.taskId() + guessMediaExtension(bgm.url(), ".mp3"));
                Path bgmMixedFile = tempDir.resolve("material-mix-" + task.taskId() + "-bgm.mp4");
                copyMediaToFile(bgm, bgmFile, "BGM");
                mixMaterialMixBgm(processedFile, bgmFile, bgmMixedFile);
                processedFile = bgmMixedFile;
            }
            AssetItem asset = saveMaterialMixAsset(task, request, processedFile, target, timeline, subtitleCues, bgm);
            return new MaterialMixResult(timeline, subtitleCues, asset, asset.fileUrl(), target.width(), target.height(),
                    normalizeAspectRatio(request.getAspectRatio()), Boolean.TRUE, !subtitleCues.isEmpty(), bgm != null);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(50100, "素材混剪失败：" + e.getMessage());
        } finally {
            deleteQuietly(tempDir);
        }
    }

    private List<MaterialMixClip> buildMaterialMixTimeline(List<Material> videos, List<String> clipTexts) {
        List<MaterialMixClip> clips = new ArrayList<>();
        int cursorMs = 0;
        for (int i = 0; i < videos.size(); i++) {
            Material material = videos.get(i);
            int startMs = cursorMs;
            int endMs = startMs + MATERIAL_MIX_CLIP_SECONDS * 1000;
            String clipText = i < clipTexts.size() ? trimToNull(clipTexts.get(i)) : null;
            clips.add(new MaterialMixClip(
                    material.asset().assetId(),
                    material.url(),
                    startMs,
                    endMs,
                    trimToDefault(material.asset().fileName(), "视频素材 " + (i + 1)),
                    clipText,
                    null,
                    i == 0 ? "clean_start" : "cut"
            ));
            cursorMs = endMs;
        }
        return clips;
    }

    private String materialMixSubtitleText(QuickRenderRequest request, List<Material> materials, List<Material> videos) {
        String mode = effectiveSubtitleMode(request.getSubtitleMode(), request.getBurnInSubtitle());
        if ("off".equals(mode)) {
            return null;
        }
        String text = firstText(
                request.getCustomSubtitle(),
                firstRoleText(materials, "subtitle"),
                request.getFinalVoiceText(),
                firstRoleText(materials, "voice_script")
        );
        if (!StringUtils.hasText(text)) {
            text = videos.stream()
                    .map(Material::text)
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .collect(Collectors.joining("\n"));
        }
        if ("upload".equals(mode) && !StringUtils.hasText(text)) {
            throw new BusinessException(40000, "素材混剪字幕模式为上传时，需要输入自定义字幕或提供 subtitle 文本素材");
        }
        return trimToNull(text);
    }

    private List<String> materialMixClipTexts(List<Material> videos, String subtitleText) {
        List<String> direct = videos.stream()
                .map(Material::text)
                .map(this::trimToNull)
                .toList();
        if (direct.stream().anyMatch(StringUtils::hasText)) {
            return direct;
        }
        if (!StringUtils.hasText(subtitleText) || isSrtText(subtitleText)) {
            return List.of();
        }
        return splitTextForSceneCount(subtitleText, videos.size());
    }

    private List<MaterialMixSubtitleCue> buildMaterialMixSubtitleCues(String subtitleText, List<MaterialMixClip> timeline) {
        if (!StringUtils.hasText(subtitleText) || timeline == null || timeline.isEmpty()) {
            return List.of();
        }
        if (isSrtText(subtitleText)) {
            int totalMs = timeline.get(timeline.size() - 1).endMs();
            return parseSrtSubtitleCues(subtitleText, totalMs);
        }
        List<MaterialMixSubtitleCue> cues = new ArrayList<>();
        for (MaterialMixClip clip : timeline) {
            String text = trimToNull(clip.voiceText());
            if (!StringUtils.hasText(text)) {
                continue;
            }
            cues.addAll(splitTextIntoCueWindow(text, clip.startMs(), clip.endMs()));
        }
        return cues;
    }

    private List<MaterialMixSubtitleCue> splitTextIntoCueWindow(String text, int startMs, int endMs) {
        List<String> chunks = splitSubtitleChunks(text);
        if (chunks.isEmpty() || endMs <= startMs) {
            return List.of();
        }
        int duration = endMs - startMs;
        int totalWeight = chunks.stream().mapToInt(this::subtitleDisplayWeight).sum();
        List<MaterialMixSubtitleCue> cues = new ArrayList<>();
        int cursor = startMs;
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            int cueEnd;
            if (i == chunks.size() - 1) {
                cueEnd = endMs;
            } else {
                int weight = Math.max(1, subtitleDisplayWeight(chunk));
                int cueDuration = Math.max(900, duration * weight / Math.max(1, totalWeight));
                cueEnd = Math.min(endMs - (chunks.size() - i - 1) * 600, cursor + cueDuration);
                if (cueEnd <= cursor) {
                    cueEnd = Math.min(endMs, cursor + 600);
                }
            }
            cues.add(new MaterialMixSubtitleCue(cursor, Math.max(cursor + 300, cueEnd), chunk));
            cursor = cueEnd;
        }
        return cues;
    }

    private List<String> splitSubtitleChunks(String text) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[ \\t]+", " ")
                .trim();
        if (!StringUtils.hasText(normalized)) {
            return List.of();
        }
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            current.append(ch);
            if ("\n。！？!?；;".indexOf(ch) >= 0) {
                String sentence = current.toString().trim();
                if (StringUtils.hasText(sentence)) {
                    sentences.add(sentence);
                }
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            String sentence = current.toString().trim();
            if (StringUtils.hasText(sentence)) {
                sentences.add(sentence);
            }
        }
        if (sentences.isEmpty()) {
            sentences.add(normalized);
        }
        List<String> chunks = new ArrayList<>();
        String active = "";
        for (String sentence : sentences) {
            for (String piece : splitLongSubtitleLine(sentence, 38)) {
                String candidate = active.isEmpty() ? piece : active + "\n" + piece;
                if (!active.isEmpty() && subtitleDisplayWeight(candidate) > 42) {
                    chunks.add(active);
                    active = piece;
                } else {
                    active = candidate;
                }
            }
        }
        if (StringUtils.hasText(active)) {
            chunks.add(active);
        }
        return chunks;
    }

    private List<String> splitLongSubtitleLine(String text, int maxWeight) {
        if (!StringUtils.hasText(text) || subtitleDisplayWeight(text) <= maxWeight) {
            return List.of(text == null ? "" : text.trim());
        }
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentWeight = 0;
        for (String token : subtitleTokens(text)) {
            int tokenWeight = subtitleDisplayWeight(token);
            if (!current.isEmpty() && currentWeight + tokenWeight > maxWeight) {
                result.add(current.toString().trim());
                current.setLength(0);
                currentWeight = 0;
            }
            current.append(token);
            currentWeight += tokenWeight;
        }
        if (!current.isEmpty()) {
            result.add(current.toString().trim());
        }
        return result;
    }

    private List<String> subtitleTokens(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        StringBuilder ascii = new StringBuilder();
        for (char ch : text.toCharArray()) {
            if (ch < 128 && !Character.isWhitespace(ch)) {
                ascii.append(ch);
            } else {
                if (!ascii.isEmpty()) {
                    tokens.add(ascii.toString());
                    ascii.setLength(0);
                }
                if (!Character.isWhitespace(ch)) {
                    tokens.add(String.valueOf(ch));
                } else if (!tokens.isEmpty()) {
                    tokens.add(" ");
                }
            }
        }
        if (!ascii.isEmpty()) {
            tokens.add(ascii.toString());
        }
        return tokens;
    }

    private int subtitleDisplayWeight(String text) {
        if (!StringUtils.hasText(text)) {
            return 1;
        }
        int weight = 0;
        for (char ch : text.toCharArray()) {
            if (ch == '\n' || ch == '\r') {
                continue;
            }
            weight += ch < 128 ? 1 : 2;
        }
        return Math.max(1, weight);
    }

    private boolean isSrtText(String text) {
        return StringUtils.hasText(text) && text.contains("-->");
    }

    private List<MaterialMixSubtitleCue> parseSrtSubtitleCues(String text, int totalMs) {
        List<MaterialMixSubtitleCue> cues = new ArrayList<>();
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] blocks = normalized.split("\\n\\s*\\n");
        for (String block : blocks) {
            String[] lines = block.lines().map(String::trim).filter(StringUtils::hasText).toArray(String[]::new);
            int timingIndex = -1;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains("-->")) {
                    timingIndex = i;
                    break;
                }
            }
            if (timingIndex < 0 || timingIndex >= lines.length - 1) {
                continue;
            }
            String[] timing = lines[timingIndex].split("-->");
            if (timing.length < 2) {
                continue;
            }
            int startMs = parseSrtTimeMs(timing[0]);
            int endMs = parseSrtTimeMs(timing[1]);
            if (endMs <= startMs || startMs >= totalMs) {
                continue;
            }
            String body = String.join("\n", java.util.Arrays.copyOfRange(lines, timingIndex + 1, lines.length)).trim();
            if (StringUtils.hasText(body)) {
                cues.add(new MaterialMixSubtitleCue(Math.max(0, startMs), Math.min(totalMs, endMs), body));
            }
        }
        return cues;
    }

    private int parseSrtTimeMs(String value) {
        String text = value == null ? "" : value.trim().replace(',', '.');
        String[] parts = text.split(":");
        if (parts.length != 3) {
            return 0;
        }
        try {
            int hours = Integer.parseInt(parts[0].trim());
            int minutes = Integer.parseInt(parts[1].trim());
            double seconds = Double.parseDouble(parts[2].trim());
            return (int) Math.round((hours * 3600 + minutes * 60 + seconds) * 1000);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void copyMediaToFile(Material material, Path targetFile, String label) throws Exception {
        if (material == null) {
            throw new BusinessException(40000, label + "为空");
        }
        String url = trimToNull(material.url());
        if (StringUtils.hasText(url) && (url.startsWith("http://") || url.startsWith("https://"))) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMinutes(5))
                    .GET()
                    .build();
            HttpResponse<InputStream> response = mediaHttpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(50100, label + "下载失败，HTTP " + response.statusCode());
            }
            try (InputStream in = response.body()) {
                Files.copy(in, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }
            return;
        }

        String filePath = trimToNull(material.asset().filePath());
        if (StringUtils.hasText(filePath)) {
            Path source = Path.of(filePath);
            if (Files.isRegularFile(source)) {
                Files.copy(source, targetFile, StandardCopyOption.REPLACE_EXISTING);
                return;
            }
        }
        throw new BusinessException(40000, label + "缺少可下载 URL，请使用资产中心/TOS 中可公网访问的素材");
    }

    private void runMaterialMixFfmpeg(List<Path> localVideos, Path outputFile, TargetSize target) throws Exception {
        if (localVideos == null || localVideos.isEmpty()) {
            throw new BusinessException(40000, "素材混剪缺少本地视频文件");
        }
        List<String> command = new ArrayList<>();
        command.add(ffmpegBin);
        command.add("-y");
        for (Path video : localVideos) {
            command.add("-t");
            command.add(String.valueOf(MATERIAL_MIX_CLIP_SECONDS));
            command.add("-i");
            command.add(video.toAbsolutePath().toString());
        }
        for (int i = 0; i < localVideos.size(); i++) {
            command.add("-f");
            command.add("lavfi");
            command.add("-t");
            command.add(String.valueOf(MATERIAL_MIX_CLIP_SECONDS));
            command.add("-i");
            command.add("anullsrc=r=44100:cl=stereo");
        }
        command.add("-filter_complex");
        command.add(buildMaterialMixFilter(localVideos.size(), target));
        command.add("-map");
        command.add("[outv]");
        command.add("-map");
        command.add("[outa]");
        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("veryfast");
        command.add("-pix_fmt");
        command.add("yuv420p");
        command.add("-c:a");
        command.add("aac");
        command.add("-movflags");
        command.add("+faststart");
        command.add(outputFile.toAbsolutePath().toString());

        Path logFile = outputFile.resolveSibling("ffmpeg-material-mix.log");
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile())
                .start();
        boolean finished = process.waitFor(10, java.util.concurrent.TimeUnit.MINUTES);
        String output = Files.exists(logFile) ? Files.readString(logFile, StandardCharsets.UTF_8) : "";
        if (!finished) {
            process.destroyForcibly();
            throw new BusinessException(50100, "FFmpeg 素材混剪超时");
        }
        if (process.exitValue() != 0) {
            throw new BusinessException(50100, "FFmpeg 素材混剪失败：" + outputTail(output));
        }
        if (!Files.isRegularFile(outputFile) || Files.size(outputFile) <= 0) {
            throw new BusinessException(50100, "FFmpeg 素材混剪没有生成有效视频");
        }
    }

    private void writeMaterialMixSrt(Path srtFile, List<MaterialMixSubtitleCue> cues) throws Exception {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < cues.size(); i++) {
            MaterialMixSubtitleCue cue = cues.get(i);
            builder.append(i + 1).append('\n')
                    .append(formatSrtTime(cue.startMs())).append(" --> ").append(formatSrtTime(cue.endMs())).append('\n')
                    .append(escapeSrtBody(cue.text())).append("\n\n");
        }
        Files.writeString(srtFile, builder.toString(), StandardCharsets.UTF_8);
    }

    private void burnMaterialMixSubtitles(Path videoFile, Path srtFile, Path outputFile) throws Exception {
        Path logFile = outputFile.resolveSibling("ffmpeg-material-mix-subtitle.log");
        String filter = "subtitles=filename='" + escapeSubtitleFilterPath(srtFile)
                + "':charenc=UTF-8:force_style='FontName=Microsoft YaHei,FontSize=16,"
                + "PrimaryColour=&H00FFFFFF,OutlineColour=&H00111111,BorderStyle=1,Outline=2,Shadow=1,Alignment=2,MarginV=68'";
        Process process = new ProcessBuilder(
                ffmpegBin,
                "-y",
                "-i", videoFile.toString(),
                "-vf", filter,
                "-map", "0:v:0",
                "-map", "0:a?",
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-crf", "20",
                "-c:a", "copy",
                "-movflags", "+faststart",
                outputFile.toString()
        ).redirectErrorStream(true).redirectOutput(logFile.toFile()).start();
        boolean finished = process.waitFor(10, java.util.concurrent.TimeUnit.MINUTES);
        String output = Files.exists(logFile) ? Files.readString(logFile, StandardCharsets.UTF_8) : "";
        if (!finished) {
            process.destroyForcibly();
            throw new BusinessException(50100, "FFmpeg 素材混剪字幕烧录超时");
        }
        if (process.exitValue() != 0) {
            throw new BusinessException(50100, "FFmpeg 素材混剪字幕烧录失败：" + outputTail(output));
        }
    }

    private void mixMaterialMixBgm(Path videoFile, Path bgmFile, Path outputFile) throws Exception {
        Path logFile = outputFile.resolveSibling("ffmpeg-material-mix-bgm.log");
        Process process = new ProcessBuilder(
                ffmpegBin,
                "-y",
                "-i", videoFile.toString(),
                "-stream_loop", "-1",
                "-i", bgmFile.toString(),
                "-filter_complex", "[1:a]volume=0.18[bgm];[0:a][bgm]amix=inputs=2:duration=first:dropout_transition=2[a]",
                "-map", "0:v:0",
                "-map", "[a]",
                "-c:v", "copy",
                "-c:a", "aac",
                "-shortest",
                "-movflags", "+faststart",
                outputFile.toString()
        ).redirectErrorStream(true).redirectOutput(logFile.toFile()).start();
        boolean finished = process.waitFor(10, java.util.concurrent.TimeUnit.MINUTES);
        String output = Files.exists(logFile) ? Files.readString(logFile, StandardCharsets.UTF_8) : "";
        if (!finished) {
            process.destroyForcibly();
            throw new BusinessException(50100, "FFmpeg 素材混剪 BGM 混音超时");
        }
        if (process.exitValue() != 0) {
            throw new BusinessException(50100, "FFmpeg 素材混剪 BGM 混音失败：" + outputTail(output));
        }
    }

    private String formatSrtTime(int ms) {
        int safe = Math.max(0, ms);
        int hours = safe / 3_600_000;
        safe %= 3_600_000;
        int minutes = safe / 60_000;
        safe %= 60_000;
        int seconds = safe / 1000;
        int millis = safe % 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis);
    }

    private String escapeSrtBody(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private String escapeSubtitleFilterPath(Path subtitleFile) {
        return subtitleFile.toAbsolutePath().toString()
                .replace("\\", "/")
                .replace(":", "\\:")
                .replace("'", "\\'");
    }

    private boolean shouldApplyMaterialMixBgm(QuickRenderRequest request) {
        return !"none".equalsIgnoreCase(trimToDefault(request.getAudioPolicy(), "auto"));
    }

    private String buildMaterialMixFilter(int count, TargetSize target) {
        StringBuilder filter = new StringBuilder();
        for (int i = 0; i < count; i++) {
            int audioIndex = count + i;
            filter.append('[').append(i).append(":v]")
                    .append("scale=").append(target.width()).append(':').append(target.height())
                    .append(":force_original_aspect_ratio=decrease,")
                    .append("pad=").append(target.width()).append(':').append(target.height())
                    .append(":(ow-iw)/2:(oh-ih)/2,")
                    .append("setsar=1,fps=30,format=yuv420p,setpts=PTS-STARTPTS[v").append(i).append("];");
            filter.append('[').append(audioIndex).append(":a]")
                    .append("atrim=duration=").append(MATERIAL_MIX_CLIP_SECONDS)
                    .append(",asetpts=PTS-STARTPTS,")
                    .append("aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo[a")
                    .append(i).append("];");
        }
        for (int i = 0; i < count; i++) {
            filter.append("[v").append(i).append("][a").append(i).append(']');
        }
        filter.append("concat=n=").append(count).append(":v=1:a=1[outv][outa]");
        return filter.toString();
    }

    private AssetItem saveMaterialMixAsset(TaskItem task, QuickRenderRequest request, Path outputFile,
                                           TargetSize target, List<MaterialMixClip> timeline,
                                           List<MaterialMixSubtitleCue> subtitleCues, Material bgm) throws Exception {
        try (InputStream in = Files.newInputStream(outputFile)) {
            String fileName = "material-mix-" + task.taskId() + ".mp4";
            String thumbnailUrl = firstText(
                    extractAndUploadVideoFirstFrame(outputFile, "material-mix-" + task.taskId() + "-cover.jpg"),
                    trimToNull(request.getCoverUrl())
            );
            UploadResult stored = storageService.upload(in, Files.size(outputFile), fileName, "video/mp4", "video");
            return assetService.createGeneratedVideoAsset(
                    task.ownerUserId(),
                    task.projectId(),
                    task.taskId(),
                    stored.filename(),
                    stored.objectKey(),
                    stored.url(),
                    thumbnailUrl,
                    stored.contentType(),
                    stored.size(),
                    TaskTypeCode.QUICK_RENDER,
                    buildMaterialMixMetadata(task, request, target, timeline, subtitleCues, bgm, thumbnailUrl)
            );
        }
    }

    private String buildMaterialMixMetadata(TaskItem task, QuickRenderRequest request, TargetSize target,
                                            List<MaterialMixClip> timeline,
                                            List<MaterialMixSubtitleCue> subtitleCues,
                                            Material bgm,
                                            String thumbnailUrl) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("source", "MATERIAL_MIX");
        meta.put("taskType", TaskTypeCode.QUICK_RENDER);
        meta.put("localTaskId", task.taskId());
        meta.put("sourceAssetIds", request.getAssetIds());
        meta.put("timeline", timeline);
        meta.put("subtitleCues", subtitleCues);
        meta.put("width", target.width());
        meta.put("height", target.height());
        meta.put("aspectRatio", normalizeAspectRatio(request.getAspectRatio()));
        meta.put("clipSeconds", MATERIAL_MIX_CLIP_SECONDS);
        meta.put("modelInvoked", false);
        meta.put("subtitleApplied", subtitleCues != null && !subtitleCues.isEmpty());
        meta.put("bgmApplied", bgm != null);
        meta.put("bgmAssetId", bgm == null ? null : bgm.asset().assetId());
        meta.put("bgmUrl", bgm == null ? null : bgm.url());
        meta.put("coverAssetId", request.getCoverAssetId());
        meta.put("coverUrl", firstText(thumbnailUrl, request.getCoverUrl()));
        meta.put("thumbnailUrl", firstText(thumbnailUrl, request.getCoverUrl()));
        return toJson(meta);
    }

    private String extractAndUploadVideoFirstFrame(Path videoFile, String fileName) {
        if (videoFile == null || !Files.isRegularFile(videoFile)) {
            return null;
        }
        Path coverFile = videoFile.resolveSibling(fileName);
        Path logFile = videoFile.resolveSibling(fileName + ".log");
        try {
            Process process = new ProcessBuilder(
                    ffmpegBin,
                    "-y",
                    "-ss", "0",
                    "-i", videoFile.toAbsolutePath().toString(),
                    "-frames:v", "1",
                    "-q:v", "2",
                    coverFile.toAbsolutePath().toString()
            ).redirectErrorStream(true).redirectOutput(logFile.toFile()).start();
            boolean finished = process.waitFor(45, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Material mix cover extraction timed out video={}", videoFile);
                return null;
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(coverFile) || Files.size(coverFile) <= 0) {
                String output = Files.exists(logFile) ? Files.readString(logFile, StandardCharsets.UTF_8) : "";
                log.warn("Material mix cover extraction failed video={} output={}", videoFile, outputTail(output));
                return null;
            }
            try (InputStream in = Files.newInputStream(coverFile)) {
                UploadResult stored = storageService.upload(in, Files.size(coverFile), fileName, "image/jpeg", "cover");
                return stored.url();
            }
        } catch (Exception ex) {
            log.warn("Material mix cover extraction skipped video={} reason={}", videoFile, ex.getMessage());
            return null;
        } finally {
            try {
                Files.deleteIfExists(coverFile);
            } catch (Exception ignored) {
            }
            try {
                Files.deleteIfExists(logFile);
            } catch (Exception ignored) {
            }
        }
    }

    private TargetSize targetSize(String aspectRatio) {
        String ratio = trimToDefault(aspectRatio, "9:16");
        if ("16:9".equals(ratio)) {
            return new TargetSize(1920, 1080);
        }
        return new TargetSize(1080, 1920);
    }

    private String outputTail(String output) {
        if (!StringUtils.hasText(output)) {
            return "无日志输出";
        }
        String normalized = output.trim();
        return normalized.length() <= 1200 ? normalized : normalized.substring(normalized.length() - 1200);
    }

    private String guessMediaExtension(String url, String fallback) {
        try {
            String path = URI.create(url == null ? "" : url.trim()).getPath();
            int dot = path == null ? -1 : path.lastIndexOf('.');
            if (dot >= 0 && dot < path.length() - 1) {
                String ext = path.substring(dot).toLowerCase(Locale.ROOT);
                if (ext.matches("\\.[a-z0-9]{2,5}")) {
                    return ext;
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private void deleteQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception ignored) {
        }
    }

    private String buildSummary(String route, List<Material> materials, String subtitle, String bgmUrl) {
        Map<String, Long> counts = materials.stream()
                .collect(Collectors.groupingBy(m -> m.asset().assetType(), LinkedHashMap::new, Collectors.counting()));
        StringBuilder builder = new StringBuilder();
        builder.append("系统将使用").append(routeName(route)).append("链路。");
        builder.append("已识别素材：");
        builder.append(counts.entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue() + " 个")
                .collect(Collectors.joining("、")));
        if (StringUtils.hasText(subtitle) && !"无".equals(subtitle)) {
            builder.append("；字幕已开启");
        }
        if (StringUtils.hasText(bgmUrl)) {
            builder.append("；将混入 BGM");
        }
        return builder.toString();
    }

    private String routeName(String route) {
        return switch (route) {
            case ROUTE_CAR_SALES -> "汽车销售成片";
            case ROUTE_DIGITAL_HUMAN -> "数字人口播";
            case ROUTE_GENERAL_VIDEO -> "通用图生视频";
            case ROUTE_MATERIAL_MIX -> "素材混剪";
            default -> "自动";
        };
    }

    private QuickRenderResponse.RecognizedAsset toRecognizedAsset(Material material) {
        QuickRenderResponse.RecognizedAsset item = new QuickRenderResponse.RecognizedAsset();
        item.setAssetId(material.asset().assetId());
        item.setFileName(material.asset().fileName());
        item.setAssetType(material.asset().assetType());
        item.setMimeType(material.asset().mimeType());
        item.setRole(material.role());
        item.setUrl(material.url());
        return item;
    }

    private boolean hasRole(List<Material> materials, String role) {
        return materials.stream().anyMatch(m -> role.equals(m.role()));
    }

    private Material firstRole(List<Material> materials, String role) {
        return materials.stream().filter(m -> role.equals(m.role())).findFirst().orElse(null);
    }

    private String firstRoleText(List<Material> materials, String... roles) {
        for (String role : roles) {
            for (Material material : materials) {
                if (role.equals(material.role()) && StringUtils.hasText(material.text())) {
                    return material.text().trim();
                }
            }
        }
        return null;
    }

    private String defaultVoiceText(int index, String goalText) {
        String suffix = StringUtils.hasText(goalText) ? "，" + goalText.trim() : "";
        return switch (index) {
            case 0 -> "先看这台车的整体外观，线条利落，第一眼就很有辨识度" + suffix;
            case 1 -> "进入车内，空间、座椅和智能座舱都很适合日常通勤和家庭出行";
            case 2 -> "配置、动力和用车成本是这台车的核心优势，适合正在对比车型的用户";
            default -> "想进一步了解价格和试驾权益，可以直接预约到店体验";
        };
    }

    private String normalizeRole(String role) {
        return lower(trimToNull(role));
    }

    private String normalizeAspectRatio(String aspectRatio) {
        String ratio = trimToNull(aspectRatio);
        if (!StringUtils.hasText(ratio) || "auto".equalsIgnoreCase(ratio)) {
            return null;
        }
        return ratio;
    }

    private String normalizeAuto(String value) {
        String text = trimToNull(value);
        if (!StringUtils.hasText(text) || "auto".equalsIgnoreCase(text)) {
            return null;
        }
        return text;
    }

    private String normalizeSubtitleLanguage(String value) {
        String language = trimToNull(value);
        if (!StringUtils.hasText(language)) {
            return "zh-CN";
        }
        return switch (language.trim()) {
            case "en-US", "zh-CN" -> language.trim();
            default -> "zh-CN";
        };
    }

    private String normalizeNativeVoiceLanguage(String value) {
        String language = trimToNull(value);
        if (!StringUtils.hasText(language)) {
            return "zh-CN";
        }
        return switch (language.trim()) {
            case "en-US", "zh-CN" -> language.trim();
            default -> "zh-CN";
        };
    }

    private boolean metadataContains(AssetItem asset, String needle) {
        return asset != null
                && StringUtils.hasText(asset.metadataJson())
                && asset.metadataJson().toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private String carBundleTextFromMetadata(AssetItem asset, OptionalLong viewer) {
        if (asset == null || !StringUtils.hasText(asset.metadataJson())) {
            return null;
        }
        JsonNode metadata = readJsonOrNull(asset.metadataJson());
        if (metadata == null) {
            return null;
        }
        List<Map<String, Object>> images = new ArrayList<>();
        appendSyntheticCarBundleRows(images, metadata.path("images"), viewer);
        appendSyntheticCarBundleAssetIds(images, metadata.path("componentAssetIds"), viewer);
        appendSyntheticCarBundleAssetIds(images, metadata.path("assetIds"), viewer);
        if (images.isEmpty()) {
            String coverUrl = firstTextJson(metadata, "coverUrl", "thumbnailUrl", "imageUrl", "previewUrl");
            if (StringUtils.hasText(coverUrl)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("url", coverUrl.trim());
                row.put("role", "car_exterior_front");
                row.put("label", firstText(asset.fileName(), "car model bundle cover"));
                images.add(row);
            }
        }
        if (images.isEmpty()) {
            return null;
        }
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("images", images);
        try {
            return objectMapper.writeValueAsString(bundle);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private void appendSyntheticCarBundleRows(List<Map<String, Object>> images, JsonNode rows, OptionalLong viewer) {
        if (rows == null || rows.isMissingNode() || rows.isNull() || images.size() >= 9) {
            return;
        }
        if (rows.isArray()) {
            for (JsonNode row : rows) {
                appendSyntheticCarBundleRow(images, row, viewer);
                if (images.size() >= 9) {
                    return;
                }
            }
            return;
        }
        appendSyntheticCarBundleRow(images, rows, viewer);
    }

    private void appendSyntheticCarBundleRow(List<Map<String, Object>> images, JsonNode row, OptionalLong viewer) {
        if (row == null || row.isMissingNode() || row.isNull() || images.size() >= 9) {
            return;
        }
        if (row.isNumber() || row.isTextual()) {
            appendSyntheticCarBundleAssetId(images, jsonLong(row), viewer);
            return;
        }
        Long assetId = firstLongJson(row, "assetId", "id", "asset_id");
        AssetItem component = assetId == null ? null : loadCarBundleComponentAsset(assetId, viewer);
        String url = firstText(firstTextJson(row, "url", "fileUrl", "previewUrl", "imageUrl", "thumbnailUrl", "coverUrl", "posterUrl", "src"),
                component == null ? null : component.fileUrl(),
                component == null ? null : component.thumbnailUrl());
        if (!StringUtils.hasText(url)) {
            return;
        }
        String role = normalizeRole(firstText(firstTextJson(row, "role", "assetRole", "type"),
                component == null ? null : inferRole(component)));
        String label = firstText(firstTextJson(row, "label", "name", "fileName", "title"),
                component == null ? null : component.fileName(),
                "car model material");
        addSyntheticCarBundleRow(images, assetId, url, role, label);
    }

    private void appendSyntheticCarBundleAssetIds(List<Map<String, Object>> images, JsonNode ids, OptionalLong viewer) {
        if (ids == null || ids.isMissingNode() || ids.isNull() || images.size() >= 9) {
            return;
        }
        if (ids.isArray()) {
            for (JsonNode idNode : ids) {
                appendSyntheticCarBundleAssetId(images, jsonLong(idNode), viewer);
                if (images.size() >= 9) {
                    return;
                }
            }
            return;
        }
        appendSyntheticCarBundleAssetId(images, jsonLong(ids), viewer);
    }

    private void appendSyntheticCarBundleAssetId(List<Map<String, Object>> images, Long assetId, OptionalLong viewer) {
        if (assetId == null || images.size() >= 9) {
            return;
        }
        AssetItem component = loadCarBundleComponentAsset(assetId, viewer);
        if (component == null || !isImageAsset(component)) {
            return;
        }
        JsonNode metadata = readJsonOrNull(component.metadataJson());
        String role = normalizeRole(firstText(firstTextJson(metadata, "role", "assetRole", "type"), inferRole(component)));
        String label = firstText(firstTextJson(metadata, "label", "name", "fileName", "title"), component.fileName());
        addSyntheticCarBundleRow(images, assetId, firstText(component.fileUrl(), component.thumbnailUrl()), role, label);
    }

    private void addSyntheticCarBundleRow(List<Map<String, Object>> images, Long assetId, String url, String role, String label) {
        if (images == null || images.size() >= 9 || !StringUtils.hasText(url)) {
            return;
        }
        String normalizedUrl = url.trim();
        boolean exists = images.stream().anyMatch(row -> normalizedUrl.equals(row.get("url")));
        if (exists) {
            return;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        if (assetId != null) {
            row.put("assetId", assetId);
        }
        row.put("role", StringUtils.hasText(role) ? normalizeRole(role) : "car_exterior_front");
        row.put("label", StringUtils.hasText(label) ? label.trim() : "car model material");
        row.put("url", normalizedUrl);
        row.put("thumbnailUrl", normalizedUrl);
        images.add(row);
    }

    private AssetItem loadCarBundleComponentAsset(Long assetId, OptionalLong viewer) {
        if (assetId == null) {
            return null;
        }
        try {
            return assetService.getAssetForViewer(assetId, viewer);
        } catch (BusinessException ex) {
            log.debug("Skip loading car bundle component asset. assetId={}, code={}", assetId, ex.getCode());
            return null;
        } catch (Exception ex) {
            log.debug("Skip loading car bundle component asset. assetId={}, reason={}", assetId, ex.getMessage());
            return null;
        }
    }

    private boolean isImageAsset(AssetItem asset) {
        if (asset == null) {
            return false;
        }
        String type = lower(asset.assetType());
        String mime = lower(asset.mimeType());
        return "image".equals(type) || mime.startsWith("image/");
    }

    private JsonNode readJsonOrNull(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Long jsonLong(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber() && node.canConvertToLong()) {
            return node.asLong();
        }
        if (node.isTextual() && StringUtils.hasText(node.asText())) {
            try {
                return Long.parseLong(node.asText().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String firstTextJson(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        for (String field : fields) {
            JsonNode child = node.path(field);
            if (child.isTextual() && StringUtils.hasText(child.asText())) {
                return child.asText().trim();
            }
            if (child.isNumber()) {
                return child.asText();
            }
        }
        return null;
    }

    private JsonNode firstArrayJson(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return objectMapper.createArrayNode();
        }
        for (String field : fields) {
            JsonNode child = node.path(field);
            if (child.isArray()) {
                return child;
            }
        }
        return objectMapper.createArrayNode();
    }

    private JsonNode parseJsonNode(String json) {
        if (!StringUtils.hasText(json)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private List<Long> componentAssetIdsFromJson(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (String field : List.of("componentAssetIds", "imageAssetIds", "vehicleAssetIds", "carImageAssetIds")) {
            addAssetIdsFromJson(ids, root.path(field));
        }
        JsonNode components = root.path("components");
        if (components.isArray()) {
            for (JsonNode component : components) {
                addAssetId(ids, firstLongJson(component, "assetId", "id", "componentAssetId"));
            }
        }
        return ids;
    }

    private void addAssetIdsFromJson(List<Long> ids, JsonNode node) {
        if (!node.isArray()) {
            return;
        }
        for (JsonNode item : node) {
            if (item.canConvertToLong()) {
                addAssetId(ids, item.asLong());
            } else if (item.isTextual() && StringUtils.hasText(item.asText())) {
                try {
                    addAssetId(ids, Long.parseLong(item.asText().trim()));
                } catch (NumberFormatException ignored) {
                    // 忽略非数字组件 ID。
                }
            } else {
                addAssetId(ids, firstLongJson(item, "assetId", "id", "componentAssetId"));
            }
        }
    }

    private void addAssetId(List<Long> ids, Long assetId) {
        if (assetId != null && assetId > 0 && !ids.contains(assetId)) {
            ids.add(assetId);
        }
    }

    private String firstImageUrlJson(JsonNode node) {
        String direct = firstTextJson(node, "url", "fileUrl", "previewUrl", "imageUrl",
                "thumbnailUrl", "coverUrl", "coverImageUrl", "firstFrameUrl", "posterUrl", "src");
        if (StringUtils.hasText(direct)) {
            return direct;
        }
        for (String field : List.of("asset", "image", "file", "material", "preview", "source")) {
            JsonNode child = node == null ? null : node.path(field);
            String nested = firstTextJson(child, "url", "fileUrl", "previewUrl", "imageUrl",
                    "thumbnailUrl", "coverUrl", "coverImageUrl", "firstFrameUrl", "posterUrl", "src");
            if (StringUtils.hasText(nested)) {
                return nested;
            }
        }
        return null;
    }

    private Long firstLongJson(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        for (String field : fields) {
            JsonNode child = node.path(field);
            if (child.canConvertToLong()) {
                return child.asLong();
            }
            if (child.isTextual() && StringUtils.hasText(child.asText())) {
                try {
                    return Long.parseLong(child.asText().trim());
                } catch (NumberFormatException ignored) {
                    // 继续查找后续字段。
                }
            }
        }
        return null;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String extractQuickGoalValue(String goalText, String label) {
        if (!StringUtils.hasText(goalText) || !StringUtils.hasText(label)) {
            return null;
        }
        String prefix = label.trim() + "：";
        for (String part : goalText.split("[；;]")) {
            String text = part == null ? "" : part.trim();
            if (text.startsWith(prefix)) {
                return trimToNull(text.substring(prefix.length()));
            }
        }
        return null;
    }

    private String trimToDefault(String value, String fallback) {
        String text = trimToNull(value);
        return StringUtils.hasText(text) ? text : fallback;
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record Material(AssetItem asset, String role, String text) {
        String url() {
            return asset == null ? null : asset.fileUrl();
        }

        boolean isImage() {
            String type = asset == null ? "" : lowerStatic(asset.assetType());
            String mime = asset == null ? "" : lowerStatic(asset.mimeType());
            return "image".equals(type) || mime.startsWith("image/");
        }

        boolean isVideo() {
            String type = asset == null ? "" : lowerStatic(asset.assetType());
            String mime = asset == null ? "" : lowerStatic(asset.mimeType());
            return "video".equals(type) || mime.startsWith("video/");
        }

        private static String lowerStatic(String value) {
            return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        }
    }

    private record CarBundleImage(String url, String role, String label, Long assetId) {
    }

    private record MaterialMixClip(Long sourceAssetId, String sourceUrl, int startMs, int endMs, String caption,
                                   String voiceText, String overlayText, String transition) {
    }

    private record MaterialMixSubtitleCue(int startMs, int endMs, String text) {
    }

    private record MaterialMixResult(List<MaterialMixClip> timeline, List<MaterialMixSubtitleCue> subtitleCues,
                                     AssetItem outputAsset, String outputUrl, int width, int height, String aspectRatio,
                                     Boolean normalizedVideoOnly, Boolean subtitleApplied, Boolean bgmApplied) {
    }

    private record TargetSize(int width, int height) {
    }
}
