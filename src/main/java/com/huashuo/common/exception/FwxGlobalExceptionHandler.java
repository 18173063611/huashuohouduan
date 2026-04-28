package com.huashuo.common.exception;

import com.huashuo.common.config.FwxTraceIdFilter;
import com.huashuo.common.response.FwxApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class FwxGlobalExceptionHandler {

    @ExceptionHandler(FwxBusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public FwxApiResponse<Void> handleBusinessException(FwxBusinessException exception) {
        return FwxApiResponse.failure(exception.getCode(), exception.getMessage(), traceId());
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            BindException.class,
            ConstraintViolationException.class
    })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public FwxApiResponse<Void> handleValidationException(Exception exception) {
        return FwxApiResponse.failure(40000, "请求参数错误：" + exception.getMessage(), traceId());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public FwxApiResponse<Void> handleException(Exception exception) {
        return FwxApiResponse.failure(50000, "系统异常：" + exception.getMessage(), traceId());
    }

    private String traceId() {
        return MDC.get(FwxTraceIdFilter.TRACE_ID);
    }
}
