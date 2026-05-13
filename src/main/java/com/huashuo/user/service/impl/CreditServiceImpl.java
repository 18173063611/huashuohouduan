package com.huashuo.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.user.entity.UserCreditAccountEntity;
import com.huashuo.user.entity.UserCreditLogEntity;
import com.huashuo.user.mapper.UserCreditAccountMapper;
import com.huashuo.user.mapper.UserCreditLogMapper;
import com.huashuo.user.service.CreditChangeResult;
import com.huashuo.user.service.CreditService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Service
public class CreditServiceImpl implements CreditService {

    private static final int MAX_BALANCE_UPDATE_RETRY = 5;

    private final UserCreditAccountMapper userCreditAccountMapper;
    private final UserCreditLogMapper userCreditLogMapper;

    public CreditServiceImpl(UserCreditAccountMapper userCreditAccountMapper,
                             UserCreditLogMapper userCreditLogMapper) {
        this.userCreditAccountMapper = userCreditAccountMapper;
        this.userCreditLogMapper = userCreditLogMapper;
    }

    @Override
    public void assertBalanceAtLeast(Long userId, long minimumAmount) {
        if (minimumAmount <= 0) {
            return;
        }
        if (userId == null) {
            throw new BusinessException(40100, "请先登录后再提交消耗积分的任务");
        }
        // 只读查余额，不调用 ensureAccount，避免同类内部调用导致 @Transactional 不生效
        UserCreditAccountEntity account = findAccount(userId);
        long balance = account == null ? 0L : safe(account.getBalance());
        if (balance < minimumAmount) {
            throw new BusinessException(40900, "积分余额不足，无法提交当前任务");
        }
    }

    @Override
    @Transactional
    public UserCreditAccountEntity ensureAccount(Long userId) {
        if (userId == null) {
            throw new BusinessException(40000, "用户 ID 不能为空");
        }
        UserCreditAccountEntity existing = findAccount(userId);
        if (existing != null) {
            return existing;
        }

        LocalDateTime now = LocalDateTime.now();
        UserCreditAccountEntity created = new UserCreditAccountEntity();
        created.setUserId(userId);
        created.setBalance(0L);
        created.setFrozenBalance(0L);
        created.setTotalRecharged(0L);
        created.setTotalConsumed(0L);
        created.setCreatedAt(now);
        created.setUpdatedAt(now);
        try {
            userCreditAccountMapper.insert(created);
            return created;
        } catch (DuplicateKeyException ignored) {
            UserCreditAccountEntity reloaded = findAccount(userId);
            if (reloaded != null) {
                return reloaded;
            }
            throw ignored;
        }
    }

    @Override
    @Transactional
    public CreditChangeResult consumeForTask(Long userId, Long taskId, String modelCode, long amount,
                                             String idempotencyKey, String remark) {
        validateTaskChange(userId, taskId, amount, idempotencyKey);
        UserCreditLogEntity existing = findLogByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            return toResult(existing);
        }
        return applyTaskChange(userId, taskId, modelCode, -amount, "AI_CONSUME",
                idempotencyKey, remark, true);
    }

    @Override
    @Transactional
    public CreditChangeResult refundForTask(Long userId, Long taskId, String modelCode, long amount,
                                            String idempotencyKey, String remark) {
        validateTaskChange(userId, taskId, amount, idempotencyKey);
        UserCreditLogEntity existing = findLogByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            return toResult(existing);
        }
        return applyTaskChange(userId, taskId, modelCode, amount, "AI_REFUND",
                idempotencyKey, remark, false);
    }

    @Override
    public long getBalance(Long userId) {
        if (userId == null) {
            return 0L;
        }
        UserCreditAccountEntity account = findAccount(userId);
        return account == null ? 0L : safe(account.getBalance());
    }

    @Override
    @Transactional
    public long consumeUpTo(Long userId, Long taskId, String modelCode, long maxAmount,
                            String idempotencyKey, String remark) {
        if (maxAmount <= 0) {
            return 0L;
        }
        validateTaskChange(userId, taskId, maxAmount, idempotencyKey);
        UserCreditLogEntity existing = findLogByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            // 历史流水保留原扣减金额（负数），返回正数表示已扣金额，保证幂等。
            return existing.getChangeAmount() == null ? 0L : Math.abs(existing.getChangeAmount());
        }
        long available = getBalance(userId);
        if (available <= 0) {
            return 0L;
        }
        long actualConsume = Math.min(maxAmount, available);
        try {
            applyTaskChange(userId, taskId, modelCode, -actualConsume, "AI_CONSUME",
                    idempotencyKey, remark, true);
        } catch (BusinessException ex) {
            // CAS 抢占失败或并发把余额扣到 < actualConsume：重新查询当前可用余额，再次尝试。
            available = getBalance(userId);
            if (available <= 0) {
                return 0L;
            }
            actualConsume = Math.min(maxAmount, available);
            applyTaskChange(userId, taskId, modelCode, -actualConsume, "AI_CONSUME",
                    idempotencyKey, remark, true);
        }
        return actualConsume;
    }

    private CreditChangeResult applyTaskChange(Long userId, Long taskId, String modelCode, long delta,
                                               String changeType, String idempotencyKey, String remark,
                                               boolean consumeTotal) {
        long amount = Math.abs(delta);
        for (int i = 0; i < MAX_BALANCE_UPDATE_RETRY; i++) {
            UserCreditAccountEntity account = ensureAccount(userId);
            long before = safe(account.getBalance());
            long after = before + delta;
            if (after < 0) {
                throw new BusinessException(40900, "积分余额不足");
            }

            LocalDateTime now = LocalDateTime.now();
            LambdaUpdateWrapper<UserCreditAccountEntity> update = new LambdaUpdateWrapper<>();
            update.eq(UserCreditAccountEntity::getCreditAccountId, account.getCreditAccountId())
                    .eq(UserCreditAccountEntity::getBalance, before)
                    .eq(UserCreditAccountEntity::getDeleted, 0)
                    .set(UserCreditAccountEntity::getBalance, after)
                    .set(UserCreditAccountEntity::getUpdatedAt, now);
            if (consumeTotal) {
                update.setSql("total_consumed = total_consumed + " + amount);
            }

            int updated = userCreditAccountMapper.update(null, update);
            if (updated == 0) {
                continue;
            }

            UserCreditLogEntity log = new UserCreditLogEntity();
            log.setUserId(userId);
            log.setChangeType(changeType);
            log.setChangeAmount(delta);
            log.setBeforeBalance(before);
            log.setAfterBalance(after);
            log.setRelatedTaskId(taskId);
            log.setModelCode(trimToNull(modelCode));
            log.setIdempotencyKey(idempotencyKey.trim());
            log.setRemark(trimToNull(remark));
            log.setCreatedAt(now);
            userCreditLogMapper.insert(log);
            return toResult(log);
        }
        throw new BusinessException(40900, "积分账户正在更新，请稍后重试");
    }

    private void validateTaskChange(Long userId, Long taskId, long amount, String idempotencyKey) {
        if (userId == null) {
            throw new BusinessException(40100, "请先登录后再提交消耗积分的任务");
        }
        if (taskId == null) {
            throw new BusinessException(40000, "任务 ID 不能为空");
        }
        if (amount <= 0) {
            throw new BusinessException(40000, "积分变动数量必须大于 0");
        }
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new BusinessException(40000, "积分流水幂等键不能为空");
        }
        if (idempotencyKey.trim().length() > 120) {
            throw new BusinessException(40000, "积分流水幂等键不能超过 120 字符");
        }
    }

    private UserCreditAccountEntity findAccount(Long userId) {
        LambdaQueryWrapper<UserCreditAccountEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserCreditAccountEntity::getUserId, userId)
                .eq(UserCreditAccountEntity::getDeleted, 0)
                .last("limit 1");
        return userCreditAccountMapper.selectOne(wrapper);
    }

    private UserCreditLogEntity findLogByIdempotencyKey(String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return null;
        }
        LambdaQueryWrapper<UserCreditLogEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserCreditLogEntity::getIdempotencyKey, idempotencyKey.trim())
                .eq(UserCreditLogEntity::getDeleted, 0)
                .last("limit 1");
        return userCreditLogMapper.selectOne(wrapper);
    }

    private CreditChangeResult toResult(UserCreditLogEntity log) {
        return new CreditChangeResult(
                log.getCreditLogId(),
                log.getUserId(),
                log.getChangeAmount(),
                log.getBeforeBalance(),
                log.getAfterBalance()
        );
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private long safe(Long value) {
        return value == null ? 0L : value;
    }
}
