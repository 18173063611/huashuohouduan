package com.huashuo.task.limit;

import com.huashuo.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Component
public class AiTaskUserRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(AiTaskUserRateLimiter.class);

    private static final String KEY_PREFIX = "running:user:";
    private static final Duration ACTIVE_TTL = Duration.ofHours(24);
    private static final DefaultRedisScript<Long> RESERVE_SCRIPT = new DefaultRedisScript<>("""
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
            local current = redis.call('ZCARD', KEYS[1])
            if current >= tonumber(ARGV[2]) then
                return current
            end
            redis.call('ZADD', KEYS[1], ARGV[3], ARGV[4])
            redis.call('EXPIRE', KEYS[1], ARGV[5])
            return -1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public AiTaskUserRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Reservation reserve(Long userId, int maxActive) {
        if (userId == null) {
            return Reservation.noop();
        }
        String key = key(userId);
        String pendingMember = "pending:" + UUID.randomUUID();
        long now = System.currentTimeMillis();
        long staleBefore = now - ACTIVE_TTL.toMillis();
        try {
            Long result = redisTemplate.execute(
                    RESERVE_SCRIPT,
                    List.of(key),
                    String.valueOf(staleBefore),
                    String.valueOf(Math.max(1, maxActive)),
                    String.valueOf(now),
                    pendingMember,
                    String.valueOf(ACTIVE_TTL.toSeconds())
            );
            if (result != null && result >= 0) {
                throw new BusinessException(42900, "当前账号 AI 任务排队较多，请等待部分任务完成后再提交");
            }
            return new Reservation(userId, pendingMember, true);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("Redis AI task user limit unavailable, userId={}: {}", userId, ex.getMessage());
            return Reservation.noop();
        }
    }

    public void confirm(Reservation reservation, Long taskId) {
        if (reservation == null || !reservation.active() || taskId == null) {
            return;
        }
        String key = key(reservation.userId());
        try {
            redisTemplate.opsForZSet().add(key, taskMember(taskId), System.currentTimeMillis());
            redisTemplate.opsForZSet().remove(key, reservation.member());
            redisTemplate.expire(key, ACTIVE_TTL);
        } catch (Exception ex) {
            log.warn("Failed to confirm Redis AI task user limit, userId={}, taskId={}: {}",
                    reservation.userId(), taskId, ex.getMessage());
        }
    }

    public void release(Reservation reservation) {
        if (reservation == null || !reservation.active()) {
            return;
        }
        try {
            redisTemplate.opsForZSet().remove(key(reservation.userId()), reservation.member());
        } catch (Exception ex) {
            log.warn("Failed to release Redis AI task user reservation, userId={}: {}",
                    reservation.userId(), ex.getMessage());
        }
    }

    public void release(Long userId, Long taskId) {
        if (userId == null || taskId == null) {
            return;
        }
        try {
            redisTemplate.opsForZSet().remove(key(userId), taskMember(taskId));
        } catch (Exception ex) {
            log.warn("Failed to release Redis AI task user limit, userId={}, taskId={}: {}",
                    userId, taskId, ex.getMessage());
        }
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }

    private String taskMember(Long taskId) {
        return "task:" + taskId;
    }

    public record Reservation(Long userId, String member, boolean active) {
        public static Reservation noop() {
            return new Reservation(null, null, false);
        }
    }
}
