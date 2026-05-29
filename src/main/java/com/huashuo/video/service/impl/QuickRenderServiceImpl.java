package com.huashuo.video.service.impl;

import com.huashuo.asset.service.AssetService;
import com.huashuo.asset.vo.AssetItem;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.common.exception.BusinessException;
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
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.stream.Collectors;

/**
 * 一键成片编排服务：只做素材识别、链路判断和标准 DTO 转换，实际生成仍复用既有视频生成服务。
 */
@Service
public class QuickRenderServiceImpl implements QuickRenderService {

    private static final String ROUTE_CAR_SALES = "car_sales";
    private static final String ROUTE_DIGITAL_HUMAN = "digital_human";
    private static final String ROUTE_GENERAL_VIDEO = "general_video";
    private static final String ROUTE_MATERIAL_MIX = "material_mix";
    private static final int QUICK_SEGMENT_DURATION_SECONDS = 8;
    private static final int DEFAULT_SEGMENT_COUNT = 4;
    private static final int MAX_SEGMENT_COUNT = 6;

    private final AssetService assetService;
    private final VideoAsyncTaskService videoAsyncTaskService;
    private final ViduDigitalHumanService viduDigitalHumanService;
    private final TaskService taskService;
    private final AiTaskPublisher aiTaskPublisher;
    private final ObjectMapper objectMapper;

    public QuickRenderServiceImpl(AssetService assetService,
                                  VideoAsyncTaskService videoAsyncTaskService,
                                  ViduDigitalHumanService viduDigitalHumanService,
                                  TaskService taskService,
                                  AiTaskPublisher aiTaskPublisher,
                                  ObjectMapper objectMapper) {
        this.assetService = assetService;
        this.videoAsyncTaskService = videoAsyncTaskService;
        this.viduDigitalHumanService = viduDigitalHumanService;
        this.taskService = taskService;
        this.aiTaskPublisher = aiTaskPublisher;
        this.objectMapper = objectMapper;
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
            CarSalesVideoDTO dto = buildCarSalesRequest(request, materials);
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

        throw new BusinessException(40000, "当前版本暂不支持多视频素材混剪，请先使用图片、口播或汽车素材生成成片");
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
            String text = textFromRequest(request, assetId);
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
            if (name.contains("wheel") || name.contains("轮")) {
                return "car_detail_wheel";
            }
            if (name.contains("logo") || name.contains("标")) {
                return "car_detail_logo";
            }
            if (name.contains("light") || name.contains("灯")) {
                return "car_detail_light";
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

    private CarSalesVideoDTO buildCarSalesRequest(QuickRenderRequest request, List<Material> materials) {
        List<CarBundleImage> bundleImages = extractCarBundleImages(materials);
        List<String> carImages = materials.stream()
                .filter(m -> m.isImage() && (m.role().startsWith("car_") || m.role().startsWith("scene_")))
                .map(Material::url)
                .filter(StringUtils::hasText)
                .limit(9)
                .toList();
        if (carImages.isEmpty() && !bundleImages.isEmpty()) {
            carImages = bundleImages.stream()
                    .map(CarBundleImage::url)
                    .filter(StringUtils::hasText)
                    .limit(9)
                    .toList();
        }
        if (carImages.isEmpty()) {
            carImages = materials.stream()
                    .filter(Material::isImage)
                    .map(Material::url)
                    .filter(StringUtils::hasText)
                    .limit(9)
                    .toList();
        }
        if (carImages.isEmpty()) {
            throw new BusinessException(40000, "汽车销售成片至少需要 1 张车辆图片");
        }

        CarSalesVideoDTO dto = new CarSalesVideoDTO();
        int segmentCount = normalizeQuickSegmentCount(request.getSegmentCount());
        dto.setProjectId(request.getProjectId());
        dto.setCarImageUrls(carImages);
        dto.setSourceAssetIds(materials.stream().map(m -> m.asset().assetId()).toList());
        dto.setAssetRoleBindings(buildCarSalesAssetRoleBindings(materials, bundleImages));
        dto.setModel(normalizeAuto(request.getModel()));
        dto.setSegmentCount(segmentCount);
        dto.setSegmentDuration(QUICK_SEGMENT_DURATION_SECONDS);
        dto.setAspectRatio(normalizeAspectRatio(request.getAspectRatio()));
        dto.setPrompt(buildCarPrompt(request, materials, request.getSubtitleMode()));
        dto.setScriptContext(firstRoleText(materials, "storyboard_json", "benchmark_json"));
        dto.setIgnoredStoryboardFields(List.of("content", "backgroundMusic"));
        dto.setSubtitleMode(carSubtitleModeForRequest(request));
        dto.setSubtitleLanguage(normalizeSubtitleLanguage(request.getSubtitleLanguage()));
        dto.setNativeVoiceLanguage(normalizeNativeVoiceLanguage(request.getNativeVoiceLanguage()));

        Material hostImage = firstRole(materials, "host_image");
        if (hostImage != null) {
            dto.setHostImageUrl(hostImage.url());
            dto.setHostAppearanceEnabled(true);
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
        dto.setSubtitle(resolveSubtitle(request, materials));
        if ("upload".equals(effectiveSubtitleMode(request.getSubtitleMode(), request.getBurnInSubtitle()))) {
            dto.setScenes(buildLightScenes(carImages, request, materials, segmentCount));
        }
        return dto;
    }

    private List<CarSalesVideoDTO.AssetRoleBinding> buildCarSalesAssetRoleBindings(List<Material> materials,
                                                                                   List<CarBundleImage> bundleImages) {
        if ((materials == null || materials.isEmpty()) && (bundleImages == null || bundleImages.isEmpty())) {
            return List.of();
        }
        List<CarSalesVideoDTO.AssetRoleBinding> bindings = new ArrayList<>();
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
            bindings.add(binding);
        }
        if (bundleImages != null) {
            for (CarBundleImage image : bundleImages) {
                CarSalesVideoDTO.AssetRoleBinding binding = new CarSalesVideoDTO.AssetRoleBinding();
                binding.setAssetId(image.assetId());
                binding.setUrl(image.url());
                binding.setAssetType("IMAGE");
                binding.setAssetRole(image.role());
                binding.setLabel(image.label());
                bindings.add(binding);
            }
        }
        return bindings;
    }

    private List<CarBundleImage> extractCarBundleImages(List<Material> materials) {
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
                JsonNode rows = root.path("images");
                if (!rows.isArray()) {
                    continue;
                }
                for (JsonNode row : rows) {
                    String url = firstTextJson(row, "url", "fileUrl", "previewUrl", "imageUrl");
                    if (!StringUtils.hasText(url)) {
                        continue;
                    }
                    String role = normalizeRole(firstTextJson(row, "role", "assetRole", "type"));
                    String label = firstTextJson(row, "label", "name", "fileName");
                    Long assetId = row.path("assetId").canConvertToLong() ? row.path("assetId").asLong() : null;
                    images.add(new CarBundleImage(url.trim(), StringUtils.hasText(role) ? role : "car_exterior_front",
                            StringUtils.hasText(label) ? label.trim() : "车型素材", assetId));
                    if (images.size() >= 9) {
                        return images;
                    }
                }
            } catch (Exception ignored) {
                // 非标准车型包不阻断一键成片，后续会按普通素材继续判断。
            }
        }
        return images;
    }

    private List<CarSalesVideoDTO.Scene> buildLightScenes(List<String> carImages, QuickRenderRequest request,
                                                          List<Material> materials, int segmentCount) {
        List<String> titles = List.of("外观开场", "内饰空间", "核心卖点", "转化收口", "用车场景", "优惠收口");
        List<String> prompts = List.of(
                "展示车辆外观、车头和车身线条，镜头稳定推进，突出第一眼吸引力。",
                "展示内饰、座椅、空间和屏幕细节，强调舒适与质感。",
                "结合素材展示动力、智能、安全或用车成本卖点，节奏干净有说服力。",
                "展示门店、试驾或道路场景，强化咨询和预约试驾转化。",
                "展示城市通勤、家庭出行或周末短途场景，让车辆与真实生活需求结合。",
                "用车身高光细节、权益氛围和咨询引导收口，强化立即行动。"
        );
        String voiceScript = firstRoleText(materials, "voice_script");
        if ("upload".equals(effectiveSubtitleMode(request.getSubtitleMode(), request.getBurnInSubtitle()))) {
            voiceScript = firstText(request.getCustomSubtitle(), firstRoleText(materials, "subtitle"));
        }
        int count = normalizeQuickSegmentCount(segmentCount);
        List<String> voiceParts = splitTextForSceneCount(voiceScript, count);
        List<CarSalesVideoDTO.Scene> scenes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            CarSalesVideoDTO.Scene scene = new CarSalesVideoDTO.Scene();
            scene.setSegmentIndex(i + 1);
            scene.setTitle(titles.get(i));
            scene.setVisualPrompt(prompts.get(i));
            scene.setPrompt(prompts.get(i));
            scene.setImageUrls(carImages);
            scene.setDuration(QUICK_SEGMENT_DURATION_SECONDS);
            scene.setVoiceText(i < voiceParts.size() && StringUtils.hasText(voiceParts.get(i))
                    ? voiceParts.get(i)
                    : defaultVoiceText(i, request.getGoalText()));
            scenes.add(scene);
        }
        return scenes;
    }

    private int normalizeQuickSegmentCount(Integer value) {
        if (value == null) {
            return DEFAULT_SEGMENT_COUNT;
        }
        return Math.max(1, Math.min(MAX_SEGMENT_COUNT, value));
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
        String text = firstRoleText(materials, "voice_script", "subtitle");
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
            String subtitle = firstText(request.getCustomSubtitle(), firstRoleText(materials, "subtitle"));
            if (!StringUtils.hasText(subtitle)) {
                throw new BusinessException(40000, "字幕模式为上传时，需要输入自定义字幕或提供 subtitle 文本素材");
            }
            return subtitle;
        }
        if (hasRole(materials, "voiceover") || hasRole(materials, "reference_audio")
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
        if (hasRole(materials, "scene_road")) {
            parts.add("包含道路试驾氛围");
        }
        String mode = effectiveSubtitleMode(subtitleMode, request.getBurnInSubtitle());
        if ("upload".equals(mode)) {
            parts.add("画面中禁止生成字幕、标题、价格贴纸、水印或任何文字，字幕只由后期烧录添加");
        } else if ("off".equals(mode)) {
            parts.add("画面中禁止生成字幕、标题、价格贴纸、水印或任何文字，后期也不添加字幕");
        } else if ("auto".equals(mode)) {
            parts.add("画面中禁止生成字幕、标题、价格贴纸、水印或任何文字，成片后按最终音频自动识别并烧录字幕");
        }
        return parts.isEmpty() ? "自动根据素材生成汽车销售短视频，节奏干净，突出车型质感和到店转化。" : String.join("；", parts);
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

    private String carSubtitleModeForRequest(QuickRenderRequest request) {
        String mode = effectiveSubtitleMode(request.getSubtitleMode(), request.getBurnInSubtitle());
        if ("upload".equals(mode)) {
            return "custom";
        }
        return mode;
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

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
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
}
