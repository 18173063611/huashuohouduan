package com.huashuo.script.service;

import com.huashuo.script.dto.RewriteScriptRequest;
import com.huashuo.script.dto.RewriteScriptResponse;
import com.huashuo.script.vo.ScriptVersionItem;

import java.util.List;

public interface ScriptService {

    List<ScriptVersionItem> listProjectScripts(Long projectId);

    RewriteScriptResponse rewrite(RewriteScriptRequest request, String traceId);
}
