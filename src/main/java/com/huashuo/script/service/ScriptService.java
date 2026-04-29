package com.huashuo.script.service;

import com.huashuo.script.dto.RewriteScriptRequest;
import com.huashuo.script.dto.RewriteScriptResponse;
import com.huashuo.script.vo.ScriptVersionItem;

import java.util.List;

/**
 * 文案服务接口：定义脚本改写和项目脚本版本查询能力。
 */
public interface ScriptService {

    List<ScriptVersionItem> listProjectScripts(Long projectId);

    RewriteScriptResponse rewrite(RewriteScriptRequest request, String traceId);
}
