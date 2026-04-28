package com.huashuo.common.response;

import java.util.List;

public record FwxPageResult<T>(
        List<T> records,
        int pageNo,
        int pageSize,
        long total
) {
}
