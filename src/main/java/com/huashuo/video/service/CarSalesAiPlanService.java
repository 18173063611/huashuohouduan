package com.huashuo.video.service;

import com.huashuo.video.DTO.CarSalesAiPlanRequest;
import com.huashuo.video.DTO.CarSalesAiPlanResponse;

public interface CarSalesAiPlanService {
    CarSalesAiPlanResponse generate(CarSalesAiPlanRequest request);
}
