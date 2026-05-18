package com.huashuo.writer.limit;

import com.huashuo.common.exception.BusinessException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class WriterRequestRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(WriterRequestRateLimiter.class);
    private static final String DOUYIN_PARSE_KEY_PREFIX = "writer:douyin-parse:";
    private static final Duration WINDOW_TTL = Duration.ofMinutes(1);
    private static final DefaultRedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final int douyinParseMaxRequestsPerMinute;
    private final ConcurrentHashMap<String, LocalWindow> localWindows = new ConcurrentHashMap<>();

    public WriterRequestRateLimiter(
            StringRedisTemplate redisTemplate,
            @Value("${writer.douyin-parse.max-requests-per-minute:20}") int douyinParseMaxRequestsPerMinute
    ) {
        this.redisTemplate = redisTemplate;
        this.douyinParseMaxRequestsPerMinute = douyinParseMaxRequestsPerMinute;
    }

    public void assertDouyinParseAllowed(Long userId) {
        if (douyinParseMaxRequestsPerMinute <= 0) {
            return;
        }
        String key = DOUYIN_PARSE_KEY_PREFIX + (userId == null ? "anonymous" : userId);
        try {
            Long count = redisTemplate.execute(
                    INCREMENT_SCRIPT,
                    List.of(key),
                    String.valueOf(WINDOW_TTL.toSeconds())
            );
            if (count != null && count > douyinParseMaxRequestsPerMinute) {
                throwTooManyRequests();
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("Redis writer rate limit unavailable, falling back to local window: {}", exception.getMessage());
            assertLocalWindowAllowed(key);
        }
    }

    private void assertLocalWindowAllowed(String key) {
        long currentWindow = System.currentTimeMillis() / WINDOW_TTL.toMillis();
        LocalWindow window = localWindows.compute(key, (ignored, existing) -> {
            if (existing == null || existing.window() != currentWindow) {
                return new LocalWindow(currentWindow, new AtomicInteger(0));
            }
            return existing;
        });
        if (window.counter().incrementAndGet() > douyinParseMaxRequestsPerMinute) {
            throwTooManyRequests();
        }
    }

    private void throwTooManyRequests() {
        throw new BusinessException(42900, "抖音解析请求过于频繁，请稍后再试");
    }

    private record LocalWindow(long window, AtomicInteger counter) {
    }
}
