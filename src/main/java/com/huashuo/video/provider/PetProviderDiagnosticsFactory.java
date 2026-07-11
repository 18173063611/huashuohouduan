package com.huashuo.video.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huashuo.task.model.ProviderFailureDiagnostics;
import com.volcengine.ark.runtime.exception.ArkHttpException;
import okhttp3.Headers;
import okhttp3.ResponseBody;
import retrofit2.HttpException;
import retrofit2.Response;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Extracts and redacts the diagnostic information retained by the Ark SDK.
 */
public final class PetProviderDiagnosticsFactory {

    private static final int MAX_MESSAGE_LENGTH = 2000;
    private static final int MAX_RESPONSE_LENGTH = 16000;
    private static final int MAX_STACK_LENGTH = 6000;
    private static final int MAX_STACK_FRAMES = 16;
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "authorization", "api_key", "apikey", "access_key", "accesskey", "secret",
            "secret_key", "secretkey", "token", "cookie", "set-cookie", "signature", "credential"
    );
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)bearer\\s+[a-z0-9._~+\\-/=]{8,}");
    private static final Pattern SECRET_ASSIGNMENT_PATTERN = Pattern.compile(
            "(?i)(api[_-]?key|access[_-]?key|secret[_-]?key|token|signature)(\\s*[:=]\\s*)[^\\s,;}&\\\"]+"
    );

    private PetProviderDiagnosticsFactory() {
    }

    public static ProviderFailureDiagnostics fromThrowable(
            ObjectMapper objectMapper,
            Throwable error,
            PetProviderCallContext context,
            String stage,
            long durationMs,
            String providerTaskId
    ) {
        ArkHttpException arkError = findCause(error, ArkHttpException.class);
        HttpException httpError = findCause(error, HttpException.class);
        Integer httpStatus = arkError != null
                ? Integer.valueOf(arkError.statusCode)
                : (httpError == null ? null : httpError.code());
        boolean timeout = isTimeout(error);
        String rawBody = readErrorBody(httpError);
        boolean bodyEmpty = !hasText(rawBody);
        String providerCode = firstText(arkError == null ? null : arkError.code,
                timeout ? "PROVIDER_TIMEOUT" : null,
                httpStatus == null ? null : "HTTP_" + httpStatus,
                "PROVIDER_CALL_FAILED");
        if (timeout) {
            providerCode = "PROVIDER_TIMEOUT";
        }
        String message = firstText(
                arkError == null ? null : arkError.getMessage(),
                deepestMessage(error),
                timeout ? "Provider request timed out" : "Provider request failed"
        );
        String providerTraceId = firstText(
                arkError == null ? null : arkError.requestId,
                context == null ? null : context.requestTraceId()
        );
        String providerRequestId = responseRequestId(httpError);
        String sanitizedRaw = hasText(rawBody)
                ? sanitizeResponse(objectMapper, rawBody)
                : synthesizedProviderBody(objectMapper, arkError, httpStatus, message);
        return new ProviderFailureDiagnostics(
                httpStatus,
                truncate(providerCode, 120),
                truncate(sanitizeText(message), MAX_MESSAGE_LENGTH),
                truncate(sanitizedRaw, MAX_RESPONSE_LENGTH),
                truncate(providerTraceId, 120),
                truncate(providerRequestId, 120),
                truncate(providerTaskId, 120),
                Math.max(0L, durationMs),
                error == null ? "UnknownProviderException" : error.getClass().getName(),
                truncate(stackTraceSummary(error), MAX_STACK_LENGTH),
                truncate(stage, 80),
                bodyEmpty,
                LocalDateTime.now()
        );
    }

    public static ProviderFailureDiagnostics emptyResponse(
            PetProviderCallContext context,
            String stage,
            long durationMs
    ) {
        return new ProviderFailureDiagnostics(
                null,
                "PROVIDER_EMPTY_RESPONSE",
                "Provider returned an empty response body or task id",
                "",
                context == null ? null : truncate(context.requestTraceId(), 120),
                null,
                null,
                Math.max(0L, durationMs),
                "EmptyProviderResponse",
                "EmptyProviderResponse at " + firstText(stage, "unknown"),
                truncate(stage, 80),
                true,
                LocalDateTime.now()
        );
    }

    public static ProviderFailureDiagnostics providerTaskFailure(
            ObjectMapper objectMapper,
            PetProviderCallContext context,
            String stage,
            long durationMs,
            String providerTaskId,
            String providerErrorCode,
            String providerErrorMessage,
            String providerStatus
    ) {
        ObjectNode raw = objectMapper.createObjectNode();
        raw.put("status", firstText(providerStatus, "failed"));
        raw.put("providerTaskId", providerTaskId);
        ObjectNode error = raw.putObject("error");
        error.put("code", firstText(providerErrorCode, "PROVIDER_GENERATION_FAILED"));
        error.put("message", firstText(providerErrorMessage, "Provider generation task failed"));
        return new ProviderFailureDiagnostics(
                null,
                truncate(firstText(providerErrorCode, "PROVIDER_GENERATION_FAILED"), 120),
                truncate(sanitizeText(firstText(providerErrorMessage, "Provider generation task failed")),
                        MAX_MESSAGE_LENGTH),
                truncate(sanitizeResponse(objectMapper, raw.toString()), MAX_RESPONSE_LENGTH),
                context == null ? null : truncate(context.requestTraceId(), 120),
                null,
                truncate(providerTaskId, 120),
                Math.max(0L, durationMs),
                "ProviderTaskFailure",
                "ProviderTaskFailure: status=" + firstText(providerStatus, "failed"),
                truncate(stage, 80),
                false,
                LocalDateTime.now()
        );
    }

    private static String synthesizedProviderBody(ObjectMapper objectMapper, ArkHttpException arkError,
                                                   Integer httpStatus, String message) {
        if (arkError == null && httpStatus == null) {
            return null;
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("captureSource", "ark_exception_fields");
        if (httpStatus != null) {
            root.put("httpStatus", httpStatus);
        }
        ObjectNode error = root.putObject("error");
        error.put("code", arkError == null ? null : arkError.code);
        error.put("type", arkError == null ? null : arkError.type);
        error.put("param", arkError == null ? null : arkError.param);
        error.put("message", message);
        return sanitizeResponse(objectMapper, root.toString());
    }

    private static String readErrorBody(HttpException exception) {
        if (exception == null) {
            return null;
        }
        try {
            Response<?> response = exception.response();
            ResponseBody body = response == null ? null : response.errorBody();
            return body == null ? null : body.string();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String responseRequestId(HttpException exception) {
        if (exception == null || exception.response() == null) {
            return null;
        }
        Headers headers = exception.response().headers();
        return firstText(
                headers.get("x-request-id"),
                headers.get("x-tt-logid"),
                headers.get("request-id"),
                headers.get("trace-id")
        );
    }

    private static String sanitizeResponse(ObjectMapper objectMapper, String raw) {
        if (!hasText(raw)) {
            return raw == null ? null : "";
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            redactNode(node);
            return sanitizeText(objectMapper.writeValueAsString(node));
        } catch (Exception ignored) {
            return sanitizeText(raw);
        }
    }

    private static void redactNode(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (isSensitiveKey(field.getKey())) {
                    ((ObjectNode) node).put(field.getKey(), "[REDACTED]");
                } else {
                    redactNode(field.getValue());
                }
            }
        } else if (node.isArray()) {
            node.forEach(PetProviderDiagnosticsFactory::redactNode);
        }
    }

    private static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT).replace('-', '_');
        return SENSITIVE_KEYS.contains(normalized)
                || normalized.endsWith("_token")
                || normalized.endsWith("_secret")
                || normalized.endsWith("_signature");
    }

    private static String sanitizeText(String value) {
        if (value == null) {
            return null;
        }
        String sanitized = BEARER_PATTERN.matcher(value).replaceAll("Bearer [REDACTED]");
        return SECRET_ASSIGNMENT_PATTERN.matcher(sanitized).replaceAll("$1$2[REDACTED]");
    }

    private static String stackTraceSummary(Throwable error) {
        if (error == null) {
            return "UnknownProviderException";
        }
        StringBuilder summary = new StringBuilder();
        Throwable current = error;
        int frameCount = 0;
        int causeCount = 0;
        while (current != null && causeCount < 8 && summary.length() < MAX_STACK_LENGTH) {
            if (causeCount > 0) {
                summary.append("\nCaused by: ");
            }
            summary.append(current.getClass().getName())
                    .append(": ")
                    .append(firstText(sanitizeText(current.getMessage()), "<no message>"));
            for (StackTraceElement frame : current.getStackTrace()) {
                if (frameCount >= MAX_STACK_FRAMES || summary.length() >= MAX_STACK_LENGTH) {
                    break;
                }
                summary.append("\n  at ").append(frame);
                frameCount++;
            }
            current = current.getCause();
            causeCount++;
        }
        return sanitizeText(summary.toString());
    }

    private static boolean isTimeout(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 12) {
            if (current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof InterruptedIOException
                    || current.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String deepestMessage(Throwable error) {
        String message = null;
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 12) {
            if (hasText(current.getMessage())) {
                message = current.getMessage();
            }
            current = current.getCause();
        }
        return message;
    }

    private static <T extends Throwable> T findCause(Throwable error, Class<T> type) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 12) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
