package com.huashuo.writer.service;

import com.huashuo.writer.dto.RewriteDTO;
import com.huashuo.writer.pojo.DouyinVideoParseRequest;
import com.huashuo.writer.pojo.DouyinVideoParseResponse;
import com.huashuo.writer.pojo.DouyinVideoTranscriptRequest;
import com.huashuo.writer.pojo.VideoDownloadResource;
import com.huashuo.writer.pojo.WriterVO;

public interface WriterService {

    DouyinVideoParseResponse parseDouyinVideo(DouyinVideoParseRequest request);

    VideoDownloadResource openShareVideoDownload(DouyinVideoParseRequest request);

    VideoDownloadResource openRemoteCoverImage(String imageUrl);

    WriterVO extractDouyinVideoTranscript(DouyinVideoTranscriptRequest request);

    WriterVO extractDouyinVideoTranscript(DouyinVideoParseResponse parseResult);

    WriterVO rewriteDouyinVideo(RewriteDTO request);

}
