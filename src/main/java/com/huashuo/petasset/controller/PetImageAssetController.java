package com.huashuo.petasset.controller;

import com.huashuo.common.config.TraceIdFilter;
import com.huashuo.common.response.ApiResponse;
import com.huashuo.petasset.dto.PetImageGenerateRequest;
import com.huashuo.petasset.dto.PetImageGenerateResponse;
import com.huashuo.petasset.service.PetImageAssetService;
import com.huashuo.user.service.UserAuthService;
import com.huashuo.user.service.UserFeaturePermissionService;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/pet-assets")
public class PetImageAssetController {

    private final PetImageAssetService petImageAssetService;
    private final UserAuthService userAuthService;
    private final UserFeaturePermissionService featurePermissionService;

    public PetImageAssetController(
            PetImageAssetService petImageAssetService,
            UserAuthService userAuthService,
            UserFeaturePermissionService featurePermissionService
    ) {
        this.petImageAssetService = petImageAssetService;
        this.userAuthService = userAuthService;
        this.featurePermissionService = featurePermissionService;
    }

    @PostMapping("/images/generate")
    public ApiResponse<PetImageGenerateResponse> generate(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Token", required = false) String xAuthToken,
            @Valid @RequestBody PetImageGenerateRequest request
    ) {
        long userId = userAuthService.requireUserId(authorization, xAuthToken);
        featurePermissionService.assertPetCreationAccess(userId);
        return ApiResponse.success(petImageAssetService.generate(request, userId), traceId());
    }

    private String traceId() {
        return MDC.get(TraceIdFilter.TRACE_ID);
    }
}
