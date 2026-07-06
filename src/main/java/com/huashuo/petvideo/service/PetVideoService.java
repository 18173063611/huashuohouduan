package com.huashuo.petvideo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.huashuo.petvideo.dto.PetWorkDownloadResponse;
import com.huashuo.petvideo.dto.PetWorkForkRequest;
import com.huashuo.petvideo.dto.PetVideoEstimateResponse;
import com.huashuo.petvideo.dto.PetVideoPreviewResponse;
import com.huashuo.petvideo.dto.PetVideoTaskResponse;
import com.huashuo.petvideo.dto.PetWorkResponse;

import java.util.List;

public interface PetVideoService {

    JsonNode generateScript(JsonNode draft, Long ownerUserId);

    JsonNode generateStoryboard(JsonNode draft, Long ownerUserId);

    PetVideoEstimateResponse estimate(JsonNode draft, Long ownerUserId);

    PetVideoPreviewResponse previewTask(JsonNode draft, Long ownerUserId);

    PetVideoTaskResponse createTask(JsonNode draft, Long ownerUserId, String traceId, String idempotencyKey);

    PetVideoTaskResponse getTask(Long taskId, Long ownerUserId);

    List<PetWorkResponse> listWorks(Long ownerUserId, String status, String keyword, String petType);

    PetWorkResponse forkWork(Long workId, PetWorkForkRequest request, Long ownerUserId);

    PetVideoTaskResponse regenerateWork(Long workId, Long ownerUserId, String traceId, String idempotencyKey);

    void deleteWork(Long workId, Long ownerUserId);

    PetWorkDownloadResponse downloadWork(Long workId, Long ownerUserId);
}
