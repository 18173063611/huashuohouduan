package com.huashuo.writer.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DouyinVideoParseWithTranscriptEvent{
        String stage;
        Long taskId;
        DouyinVideoParseResponse parseResult;
        WriterVO transcriptResult;
}
