package com.huashuo.petvideo.dto;

import java.util.List;

public record PetVideoEstimateResponse(
        String taskType,
        String generationMode,
        Long estimatedCreditCost,
        Long balance,
        Boolean enoughBalance,
        String pricingSource,
        Integer materialCount,
        Integer shotCount,
        List<String> warnings
) {
}
