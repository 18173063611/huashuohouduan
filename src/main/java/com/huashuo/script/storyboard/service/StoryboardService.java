package com.huashuo.script.storyboard.service;

import com.huashuo.script.storyboard.dto.StoryboardGenerateRequest;
import com.huashuo.script.storyboard.dto.StoryboardGenerateResponse;

/**
 * 分镜服务接口：定义根据脚本版本生成分镜任务的能力。
 */
public interface StoryboardService {

    StoryboardGenerateResponse generate(StoryboardGenerateRequest request, String traceId);
}
