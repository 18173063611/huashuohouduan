package com.huashuo.script.service;

import com.huashuo.script.vo.ScriptVersionItem;

import java.util.List;

public interface ScriptVersionService {

    List<ScriptVersionItem> listByProject(Long projectId);

    ScriptVersionItem requireForProject(Long projectId, Long scriptVersionId);

    ScriptVersionItem createVersion(Long projectId, String content, String sourceType);
}
