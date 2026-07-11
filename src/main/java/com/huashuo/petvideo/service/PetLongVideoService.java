package com.huashuo.petvideo.service;

import com.fasterxml.jackson.databind.JsonNode;

public interface PetLongVideoService {

    JsonNode previewLongVideo(JsonNode composition, Long ownerUserId);

    JsonNode dryRunLongVideoExecution(JsonNode request, Long ownerUserId);

    JsonNode submitLongVideoExecution(JsonNode request, Long ownerUserId, String traceId);

    JsonNode authorizedSubmitLongVideoExecution(JsonNode request, Long ownerUserId, String traceId);

    JsonNode pollLongVideoExecution(JsonNode request, Long ownerUserId);
}
