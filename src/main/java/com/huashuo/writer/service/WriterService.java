package com.huashuo.writer.service;

import com.huashuo.writer.pojo.DouyinVideoParseRequest;
import com.huashuo.writer.pojo.DouyinVideoParseResponse;
import com.huashuo.writer.pojo.DouyinVideoTranscriptRequest;
import com.huashuo.writer.pojo.WriterVO;

public interface WriterService {

    DouyinVideoParseResponse parseDouyinVideo(DouyinVideoParseRequest request);

    WriterVO extractDouyinVideoTranscript(DouyinVideoTranscriptRequest request);
}
