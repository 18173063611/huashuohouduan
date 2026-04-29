package com.huashuo.script.service;

import com.huashuo.script.vo.ScriptVersionItem;

import java.util.List;

/**
 * 脚本版本服务接口：定义脚本版本保存、项目内查询和详情校验能力。
 */
public interface ScriptVersionService {

    List<ScriptVersionItem> listByProject(Long projectId);

    ScriptVersionItem requireForProject(Long projectId, Long scriptVersionId);

    ScriptVersionItem createVersion(Long projectId, String content, String sourceType);
}
