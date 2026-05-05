package com.huashuo.writer.service;

import com.huashuo.writer.dto.ApplyWriterScriptRequest;
import com.huashuo.writer.dto.UpdateWriterScriptRequest;
import com.huashuo.writer.vo.WriterScriptItem;

/**
 * 文案改写页面脚本服务：承接解析接口返回结果，并保存用户确认后的正式文案。
 */
public interface WriterScriptService {

    WriterScriptItem apply(ApplyWriterScriptRequest request);

    WriterScriptItem getCurrent(Long projectId);

    WriterScriptItem update(Long scriptId, UpdateWriterScriptRequest request);
}
