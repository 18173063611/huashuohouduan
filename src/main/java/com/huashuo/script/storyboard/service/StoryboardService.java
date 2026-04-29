package com.huashuo.script.storyboard.service;

import com.huashuo.script.storyboard.dto.StoryboardGenerateRequest;
import com.huashuo.script.storyboard.dto.StoryboardGenerateResponse;

public interface StoryboardService {

    StoryboardGenerateResponse generate(StoryboardGenerateRequest request, String traceId);
}
