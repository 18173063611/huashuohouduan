package com.huashuo.petvideo.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huashuo.asset.vo.AssetItem;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.petvideo.config.PetVideoProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

@Service
public class PetLongVideoGenerationOrchestrator {

    private static final int MIN_TOTAL_DURATION = 20;
    private static final int MAX_TOTAL_DURATION = 45;
    private static final int MIN_SEGMENT_SECONDS = 4;
    private static final int MAX_SEGMENT_SECONDS = 15;
    private static final Set<String> FORBIDDEN_POSITIVE_CAR_TERMS = Set.of(
            "car sales", "vehicle model", "dealership", "test-drive",
            "汽车销售", "车型", "试驾", "销售顾问", "汽车展厅"
    );
    private static final Set<String> FORBIDDEN_ASSET_MARKERS = Set.of(
            "pet-cat-front-white-brown-face.jpg"
    );
    private static final String SINGLE_SUBMIT_AUTHORIZATION_CODE = "PET_LONG_VIDEO_2787_20260710_ONCE";

    private final ObjectMapper objectMapper;
    private final PetVideoProperties petVideoProperties;
    private final PetLongVideoManifestService manifestService;
    private final PetLongVideoSegmentTaskService segmentTaskService;
    private final PetLongVideoStitchService stitchService;
    private final PetLongVideoResultAssetService resultAssetService;

    public PetLongVideoGenerationOrchestrator(ObjectMapper objectMapper,
                                              PetVideoProperties petVideoProperties,
                                              PetLongVideoManifestService manifestService,
                                              PetLongVideoSegmentTaskService segmentTaskService,
                                              PetLongVideoStitchService stitchService,
                                              PetLongVideoResultAssetService resultAssetService) {
        this.objectMapper = objectMapper;
        this.petVideoProperties = petVideoProperties;
        this.manifestService = manifestService;
        this.segmentTaskService = segmentTaskService;
        this.stitchService = stitchService;
        this.resultAssetService = resultAssetService;
    }

    public ObjectNode dryRun(ObjectNode manifest, Long ownerUserId) {
        validateManifest(manifest);
        markDryRunDefaults(manifest);
        manifest.set("stitchManifest", stitchService.buildDryRunStitchManifest(manifest));
        manifest.set("resultAssetPlan", resultAssetService.buildResultAssetPlan(manifest));
        manifest.set("billingSafety", billingSafety(manifest, false));
        manifest.put("executionStatus", "dry_run_ready");
        manifest.put("dryRunPassed", true);
        manifest.put("providerSubmitted", false);
        manifest.put("taskCreated", false);
        manifest.put("dryRunCheckedAt", LocalDateTime.now().toString());
        ObjectNode constraintCheck = constraintCheck(manifest);
        manifest.set("constraintCheckResult", constraintCheck);
        manifestService.ensurePersisted(manifest, ownerUserId);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("dryRun", true);
        response.put("providerSubmitEnabled", petVideoProperties.isProviderSubmitEnabled());
        response.put("providerSubmitted", false);
        response.put("taskCreated", false);
        response.put("canRequestRealSubmitAuthorization", !petVideoProperties.isProviderSubmitEnabled());
        response.put("message", "PET_LONG_VIDEO_EXECUTION_DRY_RUN_READY");
        response.put("compositionId", text(manifest, "compositionId"));
        response.put("longVideoManifestId", longValue(manifest, "longVideoManifestId", 0L));
        response.put("totalDurationSeconds", intValue(manifest, "totalDurationSeconds", 0));
        response.put("segmentCount", manifest.withArray("segments").size());
        response.put("estimatedCredits", longValue(manifest, "estimatedCredits", 0L));
        response.set("segmentSubmitPlan", segmentSubmitPlan(manifest));
        response.set("pollPlan", pollPlan(manifest));
        response.set("stitchManifest", manifest.path("stitchManifest").deepCopy());
        response.set("resultAssetPlan", manifest.path("resultAssetPlan").deepCopy());
        response.set("billingSafety", manifest.path("billingSafety").deepCopy());
        response.set("constraintCheckResult", constraintCheck);
        response.set("manifest", manifest.deepCopy());
        return response;
    }

    public ObjectNode submit(JsonNode request, Long ownerUserId, String traceId) {
        if (!petVideoProperties.isProviderSubmitEnabled()) {
            throw new BusinessException(50300, "PROVIDER_SUBMIT_DISABLED: run dry-run first and request explicit authorization before real submit");
        }
        if (!booleanValue(request, "confirmRealSubmit", false)) {
            throw new BusinessException(40300, "PET_LONG_VIDEO_REAL_SUBMIT_CONFIRMATION_REQUIRED");
        }
        ObjectNode manifest = loadRequiredManifest(request, ownerUserId);
        validateManifest(manifest);
        if (!booleanValue(manifest, "dryRunPassed", false)) {
            throw new BusinessException(40900, "PET_LONG_VIDEO_DRY_RUN_REQUIRED");
        }
        manifest.put("executionStatus", "submitting");
        manifest.put("providerSubmitted", true);
        manifest.put("submitStartedAt", LocalDateTime.now().toString());
        manifest.set("billingSafety", billingSafety(manifest, true));
        manifestService.updateManifestAsset(longValue(manifest, "longVideoManifestId", 0L), manifest, ownerUserId);

        try {
            ArrayNode segments = manifest.withArray("segments");
            for (JsonNode item : segments) {
                ObjectNode segment = (ObjectNode) item;
                if (hasTerminalFailureBefore(segments, intValue(segment, "segmentIndex", 0))) {
                    break;
                }
                if (hasTask(segment)) {
                    continue;
                }
                segmentTaskService.submitSegment(segment, ownerUserId, traceId);
                manifest.put("taskCreated", true);
                manifest.put("lastSubmittedSegmentIndex", intValue(segment, "segmentIndex", 0));
                manifestService.updateManifestAsset(longValue(manifest, "longVideoManifestId", 0L), manifest, ownerUserId);
            }
            manifest.put("executionStatus", "submitted");
            manifest.put("submitFinishedAt", LocalDateTime.now().toString());
            manifestService.updateManifestAsset(longValue(manifest, "longVideoManifestId", 0L), manifest, ownerUserId);
            petVideoProperties.setProviderSubmitEnabled(false);
            return submitResponse(manifest);
        } catch (RuntimeException ex) {
            manifest.put("executionStatus", "submit_failed");
            manifest.put("errorCode", "PET_LONG_VIDEO_SUBMIT_FAILED");
            manifest.put("errorMessage", ex.getMessage());
            manifestService.updateManifestAsset(longValue(manifest, "longVideoManifestId", 0L), manifest, ownerUserId);
            throw ex;
        } finally {
            petVideoProperties.setProviderSubmitEnabled(false);
        }
    }

    public ObjectNode authorizedSubmitOnce(JsonNode request, Long ownerUserId, String traceId) {
        if (!booleanValue(request, "confirmRealSubmit", false)) {
            throw new BusinessException(40300, "PET_LONG_VIDEO_REAL_SUBMIT_CONFIRMATION_REQUIRED");
        }
        if (!SINGLE_SUBMIT_AUTHORIZATION_CODE.equals(text(request, "authorizationCode"))) {
            throw new BusinessException(40300, "PET_LONG_VIDEO_AUTHORIZATION_CODE_INVALID");
        }
        ObjectNode manifest = loadRequiredManifest(request, ownerUserId);
        validateManifest(manifest);
        if (!"2787".equals(text(manifest, "compositionId"))) {
            throw new BusinessException(40300, "PET_LONG_VIDEO_AUTHORIZATION_SCOPE_MISMATCH");
        }
        if (!booleanValue(manifest, "dryRunPassed", false)) {
            throw new BusinessException(40900, "PET_LONG_VIDEO_DRY_RUN_REQUIRED");
        }
        petVideoProperties.setProviderSubmitEnabled(true);
        try {
            ObjectNode submitRequest = request != null && request.isObject()
                    ? (ObjectNode) request.deepCopy()
                    : objectMapper.createObjectNode();
            submitRequest.put("longVideoManifestId", longValue(manifest, "longVideoManifestId", 0L));
            submitRequest.put("confirmRealSubmit", true);
            return submit(submitRequest, ownerUserId, traceId);
        } finally {
            petVideoProperties.setProviderSubmitEnabled(false);
        }
    }

    public ObjectNode poll(JsonNode request, Long ownerUserId) {
        ObjectNode manifest = loadRequiredManifest(request, ownerUserId);
        validateManifest(manifest);
        boolean anyFailure = false;
        int succeeded = 0;
        for (JsonNode item : manifest.withArray("segments")) {
            ObjectNode segment = (ObjectNode) item;
            if (hasTask(segment)) {
                segmentTaskService.pollSegment(segment, ownerUserId);
                manifestService.updateManifestAsset(longValue(manifest, "longVideoManifestId", 0L), manifest, ownerUserId);
            }
            String status = text(segment, "taskStatus", "pending");
            if ("succeeded".equals(status)) {
                succeeded++;
            }
            if (segmentTaskService.isTerminalFailure(status)) {
                anyFailure = true;
                manifest.put("executionStatus", "failed");
                manifest.put("failedSegmentIndex", intValue(segment, "segmentIndex", 0));
                manifest.put("errorCode", text(segment, "errorCode", "SEGMENT_FAILED"));
                manifest.put("errorMessage", text(segment, "errorMessage", "segment failed"));
                break;
            }
        }
        manifest.set("stitchManifest", stitchService.buildDryRunStitchManifest(manifest));
        if (!anyFailure && succeeded == manifest.withArray("segments").size()) {
            completeByStitching(manifest, ownerUserId);
        } else if (!anyFailure) {
            manifest.put("executionStatus", succeeded > 0 ? "processing" : "submitted");
        }
        manifest.put("lastPolledAt", LocalDateTime.now().toString());
        manifestService.updateManifestAsset(longValue(manifest, "longVideoManifestId", 0L), manifest, ownerUserId);
        return pollResponse(manifest);
    }

    public ObjectNode loadRequiredManifest(JsonNode request, Long ownerUserId) {
        Long manifestId = manifestId(request);
        if (manifestId == null) {
            throw new BusinessException(40000, "longVideoManifestId is required");
        }
        return manifestService.loadManifest(manifestId, ownerUserId);
    }

    public void validateManifest(ObjectNode manifest) {
        if (manifest == null) {
            throw new BusinessException(40000, "PET_LONG_VIDEO_MANIFEST_REQUIRED");
        }
        if (!"pet".equalsIgnoreCase(text(manifest, "businessDomain", "pet"))) {
            throw new BusinessException(40000, "PET_LONG_VIDEO_MANIFEST_INVALID: businessDomain must be pet");
        }
        if (!"PET_STORY_LONG_VIDEO".equals(text(manifest, "templateType"))) {
            throw new BusinessException(40000, "PET_LONG_VIDEO_MANIFEST_INVALID: templateType mismatch");
        }
        String rawManifest = manifest.toString();
        for (String marker : FORBIDDEN_ASSET_MARKERS) {
            if (rawManifest.contains(marker)) {
                throw new BusinessException(40000, "PET_LONG_VIDEO_FORBIDDEN_ASSET: " + marker);
            }
        }
        int total = intValue(manifest, "totalDurationSeconds", 0);
        if (total < MIN_TOTAL_DURATION || total > MAX_TOTAL_DURATION) {
            throw new BusinessException(40000, "PET_LONG_VIDEO_MANIFEST_INVALID: totalDurationSeconds must be 20-45");
        }
        ArrayNode segments = manifest.withArray("segments");
        if (segments.isEmpty()) {
            throw new BusinessException(40000, "PET_LONG_VIDEO_MANIFEST_INVALID: segments are required");
        }
        int timelineTotal = 0;
        Set<String> idempotencyKeys = new LinkedHashSet<>();
        for (JsonNode item : segments) {
            ObjectNode segment = (ObjectNode) item;
            int duration = intValue(segment, "durationSeconds", 0);
            if (duration < MIN_SEGMENT_SECONDS || duration > MAX_SEGMENT_SECONDS) {
                throw new BusinessException(40000, "PET_LONG_VIDEO_MANIFEST_INVALID: segment duration must be 4-15");
            }
            timelineTotal += duration;
            if (!segment.path("providerPayload").isObject()) {
                throw new BusinessException(40000, "PET_LONG_VIDEO_MANIFEST_INVALID: providerPayload is required");
            }
            String idempotencyKey = text(segment, "idempotencyKey");
            if (!StringUtils.hasText(idempotencyKey) || !idempotencyKeys.add(idempotencyKey)) {
                throw new BusinessException(40000, "PET_LONG_VIDEO_MANIFEST_INVALID: unique idempotencyKey is required");
            }
            if (segment.withArray("referenceAssetIds").isEmpty()) {
                throw new BusinessException(40000, "PET_LONG_VIDEO_MANIFEST_INVALID: referenceAssetIds are required");
            }
            if (segment.withArray("localDialogues").isEmpty() || segment.withArray("localSubtitles").isEmpty()
                    || !segment.path("voiceMapping").isObject()) {
                throw new BusinessException(40000, "PET_LONG_VIDEO_MANIFEST_INVALID: dialogues subtitles and voiceMapping are required");
            }
            String prompt = text(segment.path("providerPayload"), "prompt", text(segment, "segmentPrompt"));
            for (String keyword : FORBIDDEN_POSITIVE_CAR_TERMS) {
                if (prompt.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))) {
                    throw new BusinessException(40000, "PET_LONG_VIDEO_CAR_POLLUTION: " + keyword);
                }
            }
            if (!"pet".equalsIgnoreCase(text(segment.path("providerPayload"), "businessDomain", "pet"))) {
                throw new BusinessException(40000, "PET_LONG_VIDEO_MANIFEST_INVALID: segment businessDomain must be pet");
            }
        }
        if (timelineTotal != total) {
            throw new BusinessException(40000, "PET_LONG_VIDEO_MANIFEST_INVALID: segment durations do not match total");
        }
        JsonNode audit = manifest.path("audit");
        if (audit.isObject()) {
            if (audit.has("bannedAssetCheck") && !audit.get("bannedAssetCheck").asBoolean(false)) {
                throw new BusinessException(40000, "PET_LONG_VIDEO_MANIFEST_INVALID: banned asset check failed");
            }
            if (audit.has("carPollutionCheck") && !audit.get("carPollutionCheck").asBoolean(false)) {
                throw new BusinessException(40000, "PET_LONG_VIDEO_MANIFEST_INVALID: car pollution check failed");
            }
        }
    }

    private void completeByStitching(ObjectNode manifest, Long ownerUserId) {
        if (longValue(manifest, "finalAssetId", 0L) > 0) {
            manifest.put("executionStatus", "succeeded");
            return;
        }
        PetLongVideoStitchService.StitchResult result = null;
        try {
            manifest.put("executionStatus", "stitching");
            manifestService.updateManifestAsset(longValue(manifest, "longVideoManifestId", 0L), manifest, ownerUserId);
            result = stitchService.stitch(manifest);
            AssetItem finalAsset = resultAssetService.saveFinalAsset(manifest, result.outputFile(), ownerUserId, null, result);
            manifest.put("executionStatus", "succeeded");
            manifest.put("finalAssetId", finalAsset.assetId());
            manifest.put("finalResultUrl", finalAsset.fileUrl());
            manifest.put("actualChargedCredits", actualChargedCredits(manifest));
            manifest.put("completedAt", LocalDateTime.now().toString());
        } catch (RuntimeException ex) {
            manifest.put("executionStatus", "stitch_failed");
            manifest.put("errorCode", "PET_LONG_VIDEO_STITCH_FAILED");
            manifest.put("errorMessage", ex.getMessage());
            throw ex;
        } finally {
            stitchService.cleanup(result);
        }
    }

    private void markDryRunDefaults(ObjectNode manifest) {
        for (JsonNode item : manifest.withArray("segments")) {
            ObjectNode segment = (ObjectNode) item;
            if (!StringUtils.hasText(text(segment, "taskStatus"))) {
                segment.put("taskStatus", "pending");
            }
            if (!segment.has("reservedCredits")) {
                segment.put("reservedCredits", 0L);
            }
            if (!segment.has("actualChargedCredits")) {
                segment.put("actualChargedCredits", 0L);
            }
        }
    }

    private ObjectNode submitResponse(ObjectNode manifest) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("dryRun", false);
        response.put("providerSubmitEnabledAfterFinally", petVideoProperties.isProviderSubmitEnabled());
        response.put("providerSubmitted", true);
        response.put("taskCreated", booleanValue(manifest, "taskCreated", false));
        response.put("longVideoManifestId", longValue(manifest, "longVideoManifestId", 0L));
        response.put("executionStatus", text(manifest, "executionStatus"));
        response.put("estimatedCredits", longValue(manifest, "estimatedCredits", 0L));
        response.set("segments", segmentState(manifest));
        return response;
    }

    private ObjectNode pollResponse(ObjectNode manifest) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("longVideoManifestId", longValue(manifest, "longVideoManifestId", 0L));
        response.put("executionStatus", text(manifest, "executionStatus"));
        response.put("segmentCount", manifest.withArray("segments").size());
        response.put("succeededSegments", succeededSegments(manifest));
        response.put("estimatedCredits", longValue(manifest, "estimatedCredits", 0L));
        response.put("actualChargedCredits", actualChargedCredits(manifest));
        response.put("finalAssetId", longValue(manifest, "finalAssetId", 0L));
        response.put("finalResultUrl", text(manifest, "finalResultUrl"));
        response.set("segments", segmentState(manifest));
        response.set("stitchManifest", manifest.path("stitchManifest").deepCopy());
        return response;
    }

    private ArrayNode segmentSubmitPlan(ObjectNode manifest) {
        ArrayNode plan = objectMapper.createArrayNode();
        for (JsonNode item : manifest.withArray("segments")) {
            ObjectNode segment = (ObjectNode) item;
            ObjectNode row = objectMapper.createObjectNode();
            row.put("segmentIndex", intValue(segment, "segmentIndex", plan.size() + 1));
            row.put("idempotencyKey", text(segment, "idempotencyKey"));
            row.put("durationSeconds", intValue(segment, "durationSeconds", 0));
            row.put("estimatedCredits", longValue(segment, "estimatedCredits", 0L));
            row.put("willCreateProviderTaskInDryRun", false);
            row.put("realSubmitRule", "create once, persist immediately, never retry automatically");
            plan.add(row);
        }
        return plan;
    }

    private ObjectNode pollPlan(ObjectNode manifest) {
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("pollExistingTasksOnly", true);
        plan.put("retrySubmitOnPollFailure", false);
        plan.put("stitchWhenAllSegmentsSucceeded", true);
        plan.put("stopOnFirstSegmentFailure", true);
        plan.put("segmentCount", manifest.withArray("segments").size());
        return plan;
    }

    private ObjectNode billingSafety(ObjectNode manifest, boolean realSubmit) {
        ObjectNode billing = objectMapper.createObjectNode();
        billing.put("previewChargesCredits", false);
        billing.put("estimateBeforeSubmit", true);
        billing.put("reserveMode", "per_segment_existing_task_precharge");
        billing.put("realSubmit", realSubmit);
        billing.put("estimatedCredits", longValue(manifest, "estimatedCredits", 0L));
        billing.put("reservedCredits", reservedCredits(manifest));
        billing.put("actualChargedCredits", actualChargedCredits(manifest));
        billing.put("maxRealSubmitsPerSegment", 1);
        billing.put("autoRetrySegmentSubmit", false);
        billing.put("autoRegenerateOnStitchFailure", false);
        return billing;
    }

    private ObjectNode constraintCheck(ObjectNode manifest) {
        ObjectNode check = objectMapper.createObjectNode();
        check.put("businessDomainPet", "pet".equalsIgnoreCase(text(manifest, "businessDomain")));
        check.put("templateTypeValid", "PET_STORY_LONG_VIDEO".equals(text(manifest, "templateType")));
        check.put("segmentDurationValid", true);
        check.put("providerPayloadPresent", true);
        check.put("idempotencyKeysUnique", true);
        check.put("referenceAssetsPresent", true);
        check.put("dialogueSubtitleVoicePresent", true);
        check.put("carPollutionCheck", true);
        return check;
    }

    private ArrayNode segmentState(ObjectNode manifest) {
        ArrayNode state = objectMapper.createArrayNode();
        for (JsonNode item : manifest.withArray("segments")) {
            ObjectNode segment = (ObjectNode) item;
            ObjectNode row = objectMapper.createObjectNode();
            row.put("segmentIndex", intValue(segment, "segmentIndex", state.size() + 1));
            row.put("globalStart", intValue(segment, "globalStart", 0));
            row.put("globalEnd", intValue(segment, "globalEnd", 0));
            row.put("durationSeconds", intValue(segment, "durationSeconds", 0));
            row.put("taskStatus", text(segment, "taskStatus", "pending"));
            row.put("generationTaskId", longValue(segment, "generationTaskId", 0L));
            row.put("providerTaskId", text(segment, "providerTaskId"));
            row.put("resultUrl", text(segment, "resultUrl"));
            row.put("estimatedCredits", longValue(segment, "estimatedCredits", 0L));
            row.put("reservedCredits", longValue(segment, "reservedCredits", 0L));
            row.put("actualChargedCredits", longValue(segment, "actualChargedCredits", 0L));
            state.add(row);
        }
        return state;
    }

    private boolean hasTerminalFailureBefore(ArrayNode segments, int currentIndex) {
        for (JsonNode item : segments) {
            int index = intValue(item, "segmentIndex", 0);
            if (index >= currentIndex) {
                continue;
            }
            String status = text(item, "taskStatus");
            if (segmentTaskService.isTerminalFailure(status)) {
                return true;
            }
        }
        return false;
    }

    private int succeededSegments(ObjectNode manifest) {
        int count = 0;
        for (JsonNode segment : manifest.withArray("segments")) {
            if ("succeeded".equals(text(segment, "taskStatus"))) {
                count++;
            }
        }
        return count;
    }

    private long reservedCredits(ObjectNode manifest) {
        long total = 0L;
        for (JsonNode segment : manifest.withArray("segments")) {
            total += longValue(segment, "reservedCredits", 0L);
        }
        return total;
    }

    private long actualChargedCredits(ObjectNode manifest) {
        long total = 0L;
        for (JsonNode segment : manifest.withArray("segments")) {
            total += longValue(segment, "actualChargedCredits", 0L);
        }
        return total;
    }

    private boolean hasTask(JsonNode segment) {
        return longValue(segment, "generationTaskId", 0L) > 0 || StringUtils.hasText(text(segment, "providerTaskId"));
    }

    private Long manifestId(JsonNode request) {
        Long id = longValueOrNull(request, "longVideoManifestId");
        if (id != null) {
            return id;
        }
        id = longValueOrNull(request, "manifestAssetId");
        if (id != null) {
            return id;
        }
        if (request != null && request.has("manifest") && request.get("manifest").isObject()) {
            return longValueOrNull(request.get("manifest"), "longVideoManifestId");
        }
        return null;
    }

    private static Long longValueOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field)) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value.canConvertToLong()) {
            return value.asLong();
        }
        if (value.isTextual() && value.asText("").matches("\\d+")) {
            return Long.parseLong(value.asText());
        }
        return null;
    }

    private static long longValue(JsonNode node, String field, long fallback) {
        Long value = longValueOrNull(node, field);
        return value == null ? fallback : value;
    }

    private static int intValue(JsonNode node, String field, int fallback) {
        return node != null && node.has(field) && node.get(field).canConvertToInt() ? node.get(field).asInt() : fallback;
    }

    private static boolean booleanValue(JsonNode node, String field, boolean fallback) {
        return node != null && node.has(field) ? node.get(field).asBoolean(fallback) : fallback;
    }

    private static String text(JsonNode node, String field) {
        return text(node, field, "");
    }

    private static String text(JsonNode node, String field, String fallback) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return fallback;
        }
        String value = node.get(field).asText("");
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}
