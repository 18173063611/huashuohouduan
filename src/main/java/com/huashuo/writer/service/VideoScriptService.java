package com.huashuo.writer.service;

import com.huashuo.writer.VO.ScriptVO;

import java.util.List;

public interface VideoScriptService {
    List<ScriptVO> scriptAnalyze(String url);
}
