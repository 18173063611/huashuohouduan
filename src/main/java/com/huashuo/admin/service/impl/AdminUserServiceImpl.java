package com.huashuo.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.huashuo.admin.dto.AdminCreditAdjustRequest;
import com.huashuo.admin.dto.AdminPasswordResetRequest;
import com.huashuo.admin.dto.AdminUserCreateRequest;
import com.huashuo.admin.dto.AdminUserUpdateRequest;
import com.huashuo.admin.service.AdminAccessService;
import com.huashuo.admin.service.AdminOperationAuditService;
import com.huashuo.admin.service.AdminOperationContext;
import com.huashuo.admin.service.AdminUserService;
import com.huashuo.admin.vo.AdminCreditAccountResponse;
import com.huashuo.admin.vo.AdminCreditLogItem;
import com.huashuo.admin.vo.AdminUserItem;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.common.response.PageResult;
import com.huashuo.user.entity.UserAccountEntity;
import com.huashuo.user.entity.UserCreditAccountEntity;
import com.huashuo.user.entity.UserCreditLogEntity;
import com.huashuo.user.entity.UserSessionEntity;
import com.huashuo.user.mapper.UserAccountMapper;
import com.huashuo.user.mapper.UserCreditAccountMapper;
import com.huashuo.user.mapper.UserCreditLogMapper;
import com.huashuo.user.mapper.UserSessionMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminUserServiceImpl implements AdminUserService {

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String ROLE_USER = "USER";
    private static final String ROLE_ADMIN = "ADMIN";
    private static final String STATUS_ENABLED = "ENABLED";
    private static final String STATUS_DISABLED = "DISABLED";
    private static final String STATUS_LOCKED = "LOCKED";
    private static final String BUILTIN_ADMIN_USERNAME = "admin";

    private final UserAccountMapper userAccountMapper;
    private final AdminAccessService adminAccessService;
    private final AdminOperationAuditService auditService;
    private final UserCreditAccountMapper userCreditAccountMapper;
    private final UserCreditLogMapper userCreditLogMapper;
    private final UserSessionMapper userSessionMapper;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AdminUserServiceImpl(UserAccountMapper userAccountMapper, AdminAccessService adminAccessService,
                                AdminOperationAuditService auditService,
                                UserCreditAccountMapper userCreditAccountMapper,
                                UserCreditLogMapper userCreditLogMapper,
                                UserSessionMapper userSessionMapper) {
        this.userAccountMapper = userAccountMapper;
        this.adminAccessService = adminAccessService;
        this.auditService = auditService;
        this.userCreditAccountMapper = userCreditAccountMapper;
        this.userCreditLogMapper = userCreditLogMapper;
        this.userSessionMapper = userSessionMapper;
    }

    @Override
    public PageResult<AdminUserItem> listUsers(String keyword, String role, String status, Integer pageNo,
                                               Integer pageSize) {
        LambdaQueryWrapper<UserAccountEntity> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            String k = keyword.trim();
            wrapper.and(w -> w.like(UserAccountEntity::getUsername, k)
                    .or()
                    .like(UserAccountEntity::getDisplayName, k));
        }
        if (StringUtils.hasText(role)) {
            wrapper.eq(UserAccountEntity::getRole, normalizeRole(role));
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(UserAccountEntity::getStatus, normalizeStatus(status));
        }
        int page = pageNo == null || pageNo < 1 ? 1 : pageNo;
        int size = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        long total = userAccountMapper.selectCount(wrapper);
        wrapper.orderByDesc(UserAccountEntity::getCreatedAt)
                .last("LIMIT " + ((long) (page - 1) * size) + "," + size);
        List<AdminUserItem> records = userAccountMapper.selectList(wrapper).stream()
                .map(this::toItem)
                .toList();
        return new PageResult<>(records, page, size, total);
    }

    @Override
    public AdminUserItem getUser(Long userId) {
        return toItem(requireUser(userId));
    }

    @Override
    @Transactional
    public AdminUserItem createUser(AdminUserCreateRequest request, AdminOperationContext context) {
        String username = normalizeUsername(request.username());
        if (existsUsername(username)) {
            throw new BusinessException(40900, "用户名已存在");
        }
        UserAccountEntity entity = new UserAccountEntity();
        entity.setUsername(username);
        entity.setPasswordHash(passwordEncoder.encode(request.password().trim()));
        entity.setDisplayName(normalizeDisplayName(request.displayName(), username));
        entity.setRole(normalizeRoleOrDefault(request.role()));
        entity.setStatus(normalizeStatusOrDefault(request.status()));
        entity.setPhone(trimToNull(request.phone()));
        entity.setEmail(trimToNull(request.email()));
        entity.setRemark(trimToNull(request.remark()));
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        userAccountMapper.insert(entity);
        ensureCreditAccount(entity.getUserId());
        AdminUserItem after = toItem(userAccountMapper.selectById(entity.getUserId()));
        auditService.record(context, "USER_CREATE", "USER", entity.getUserId(), null, after);
        return after;
    }

    @Override
    @Transactional
    public AdminUserItem updateUser(Long userId, AdminUserUpdateRequest request, AdminOperationContext context) {
        UserAccountEntity entity = requireUser(userId);
        AdminUserItem before = toItem(entity);
        assertBuiltinAdminMutableFields(entity, request.role(), request.status());
        entity.setDisplayName(normalizeDisplayName(request.displayName(), entity.getUsername()));
        entity.setRole(normalizeRoleOrDefault(request.role()));
        entity.setStatus(normalizeStatusOrDefault(request.status()));
        entity.setPhone(trimToNull(request.phone()));
        entity.setEmail(trimToNull(request.email()));
        entity.setRemark(trimToNull(request.remark()));
        entity.setUpdatedAt(LocalDateTime.now());
        userAccountMapper.updateById(entity);
        if (!STATUS_ENABLED.equals(entity.getStatus())) {
            invalidateUserSessions(userId);
        }
        AdminUserItem after = toItem(userAccountMapper.selectById(userId));
        auditService.record(context, "USER_UPDATE", "USER", userId, before, after);
        return after;
    }

    @Override
    @Transactional
    public void deleteUser(Long userId, AdminOperationContext context) {
        if (context != null && userId != null && userId.equals(context.adminUserId())) {
            throw new BusinessException(40900, "不能删除当前登录的管理员账号");
        }
        UserAccountEntity entity = requireUser(userId);
        assertNotBuiltinAdmin(entity, "内置最高管理员账号不能删除");
        AdminUserItem before = toItem(entity);
        entity.setDeleted(1);
        entity.setUpdatedAt(LocalDateTime.now());
        userAccountMapper.updateById(entity);
        invalidateUserSessions(userId);
        auditService.record(context, "USER_DELETE", "USER", userId, before, Map.of("deleted", true));
    }

    @Override
    public AdminUserItem enableUser(Long userId, AdminOperationContext context) {
        return updateStatus(userId, STATUS_ENABLED, "USER_ENABLE", context);
    }

    @Override
    public AdminUserItem disableUser(Long userId, AdminOperationContext context) {
        assertNotBuiltinAdmin(requireUser(userId), "内置最高管理员账号不能禁用");
        return updateStatus(userId, STATUS_DISABLED, "USER_DISABLE", context);
    }

    @Override
    @Transactional
    public void resetPassword(Long userId, AdminPasswordResetRequest request, AdminOperationContext context) {
        UserAccountEntity entity = requireUser(userId);
        AdminUserItem before = toItem(entity);
        entity.setPasswordHash(passwordEncoder.encode(request.password().trim()));
        entity.setUpdatedAt(LocalDateTime.now());
        userAccountMapper.updateById(entity);
        invalidateUserSessions(userId);
        auditService.record(context, "USER_RESET_PASSWORD", "USER", userId, before,
                Map.of("passwordReset", true, "username", entity.getUsername()));
    }

    @Override
    public AdminCreditAccountResponse getCreditAccount(Long userId) {
        requireUser(userId);
        return toCreditAccountResponse(ensureCreditAccount(userId));
    }

    @Override
    @Transactional
    public AdminCreditAccountResponse adjustCredits(Long userId, AdminCreditAdjustRequest request,
                                                    AdminOperationContext context) {
        requireUser(userId);
        UserCreditAccountEntity account = ensureCreditAccount(userId);
        AdminCreditAccountResponse beforeAccount = toCreditAccountResponse(account);
        long before = safe(account.getBalance());
        long amount = request.amount();
        long after;
        long delta;
        String changeType = normalizeCreditChangeType(request.changeType());
        if ("ADMIN_ADD".equals(changeType)) {
            delta = amount;
            after = before + amount;
            account.setTotalRecharged(safe(account.getTotalRecharged()) + amount);
        } else if ("ADMIN_DEDUCT".equals(changeType)) {
            if (before < amount) {
                throw new BusinessException(40900, "积分余额不足，不能扣成负数");
            }
            delta = -amount;
            after = before - amount;
            account.setTotalConsumed(safe(account.getTotalConsumed()) + amount);
        } else {
            after = amount;
            delta = after - before;
            if (delta > 0) {
                account.setTotalRecharged(safe(account.getTotalRecharged()) + delta);
            } else if (delta < 0) {
                account.setTotalConsumed(safe(account.getTotalConsumed()) + Math.abs(delta));
            }
        }

        account.setBalance(after);
        account.setUpdatedAt(LocalDateTime.now());
        userCreditAccountMapper.updateById(account);

        UserCreditLogEntity log = new UserCreditLogEntity();
        log.setUserId(userId);
        log.setChangeType(changeType);
        log.setChangeAmount(delta);
        log.setBeforeBalance(before);
        log.setAfterBalance(after);
        log.setOperatorAdminId(context == null ? null : context.adminUserId());
        log.setRemark(trimToNull(request.remark()));
        log.setCreatedAt(LocalDateTime.now());
        userCreditLogMapper.insert(log);
        AdminCreditAccountResponse afterAccount = toCreditAccountResponse(account);
        Map<String, Object> afterSnapshot = new LinkedHashMap<>();
        afterSnapshot.put("account", afterAccount);
        afterSnapshot.put("changeType", changeType);
        afterSnapshot.put("changeAmount", delta);
        afterSnapshot.put("creditLogId", log.getCreditLogId());
        afterSnapshot.put("remark", trimToNull(request.remark()));
        auditService.record(context, "CREDIT_ADJUST", "CREDIT", userId, beforeAccount, afterSnapshot);
        return afterAccount;
    }

    @Override
    public PageResult<AdminCreditLogItem> listCreditLogs(Long userId, Integer pageNo, Integer pageSize) {
        requireUser(userId);
        int page = pageNo == null || pageNo < 1 ? 1 : pageNo;
        int size = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        LambdaQueryWrapper<UserCreditLogEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserCreditLogEntity::getUserId, userId);
        long total = userCreditLogMapper.selectCount(wrapper);
        wrapper.orderByDesc(UserCreditLogEntity::getCreatedAt)
                .last("LIMIT " + ((long) (page - 1) * size) + "," + size);
        List<AdminCreditLogItem> records = userCreditLogMapper.selectList(wrapper).stream()
                .map(this::toCreditLogItem)
                .toList();
        return new PageResult<>(records, page, size, total);
    }

    private UserAccountEntity requireUser(Long userId) {
        if (userId == null) {
            throw new BusinessException(40000, "用户 ID 不能为空");
        }
        UserAccountEntity entity = userAccountMapper.selectById(userId);
        if (entity == null || entity.getDeleted() != null && entity.getDeleted() == 1) {
            throw new BusinessException(40400, "用户不存在");
        }
        return entity;
    }

    private AdminUserItem toItem(UserAccountEntity entity) {
        UserCreditAccountEntity creditAccount = ensureCreditAccount(entity.getUserId());
        return new AdminUserItem(
                entity.getUserId(),
                entity.getUsername(),
                entity.getDisplayName(),
                adminAccessService.roleOf(entity.getUserId(), entity.getUsername()),
                entity.getStatus(),
                entity.getPhone(),
                entity.getEmail(),
                entity.getRemark(),
                creditAccount.getBalance(),
                entity.getLastLoginAt(),
                entity.getCreatedAt()
        );
    }

    private boolean existsUsername(String username) {
        LambdaQueryWrapper<UserAccountEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserAccountEntity::getUsername, username)
                .eq(UserAccountEntity::getDeleted, 0)
                .last("limit 1");
        return userAccountMapper.selectOne(wrapper) != null;
    }

    private String normalizeUsername(String username) {
        if (!StringUtils.hasText(username)) {
            throw new BusinessException(40000, "用户名不能为空");
        }
        String value = username.trim();
        if (value.length() > 60) {
            throw new BusinessException(40000, "用户名最长 60 字符");
        }
        return value;
    }

    private String normalizeDisplayName(String displayName, String fallback) {
        if (!StringUtils.hasText(displayName)) {
            return fallback;
        }
        String value = displayName.trim();
        if (value.length() > 80) {
            throw new BusinessException(40000, "展示名最长 80 字符");
        }
        return value;
    }

    private AdminCreditAccountResponse toCreditAccountResponse(UserCreditAccountEntity account) {
        return new AdminCreditAccountResponse(
                account.getUserId(),
                safe(account.getBalance()),
                safe(account.getFrozenBalance()),
                safe(account.getTotalRecharged()),
                safe(account.getTotalConsumed())
        );
    }

    private AdminCreditLogItem toCreditLogItem(UserCreditLogEntity entity) {
        return new AdminCreditLogItem(
                entity.getCreditLogId(),
                entity.getUserId(),
                entity.getChangeType(),
                entity.getChangeAmount(),
                entity.getBeforeBalance(),
                entity.getAfterBalance(),
                entity.getRelatedTaskId(),
                entity.getModelCode(),
                entity.getOperatorAdminId(),
                entity.getRemark(),
                entity.getCreatedAt()
        );
    }

    private AdminUserItem updateStatus(Long userId, String status, String operationType, AdminOperationContext context) {
        UserAccountEntity entity = requireUser(userId);
        if (!STATUS_ENABLED.equals(status)) {
            assertNotBuiltinAdmin(entity, "内置最高管理员账号必须保持启用");
        }
        AdminUserItem before = toItem(entity);
        entity.setStatus(status);
        entity.setUpdatedAt(LocalDateTime.now());
        userAccountMapper.updateById(entity);
        if (!STATUS_ENABLED.equals(status)) {
            invalidateUserSessions(userId);
        }
        AdminUserItem after = toItem(userAccountMapper.selectById(userId));
        auditService.record(context, operationType, "USER", userId, before, after);
        return after;
    }

    private void invalidateUserSessions(Long userId) {
        if (userId == null) {
            return;
        }
        LambdaUpdateWrapper<UserSessionEntity> update = new LambdaUpdateWrapper<>();
        update.eq(UserSessionEntity::getUserId, userId)
                .eq(UserSessionEntity::getDeleted, 0)
                .set(UserSessionEntity::getDeleted, 1)
                .set(UserSessionEntity::getUpdatedAt, LocalDateTime.now());
        userSessionMapper.update(null, update);
    }

    private void assertBuiltinAdminMutableFields(UserAccountEntity entity, String role, String status) {
        if (!isBuiltinAdmin(entity)) {
            return;
        }
        if (StringUtils.hasText(role) && !ROLE_ADMIN.equals(normalizeRole(role))) {
            throw new BusinessException(40900, "内置最高管理员账号不能降级为普通用户");
        }
        if (StringUtils.hasText(status) && !STATUS_ENABLED.equals(normalizeStatus(status))) {
            throw new BusinessException(40900, "内置最高管理员账号不能禁用或锁定");
        }
    }

    private void assertNotBuiltinAdmin(UserAccountEntity entity, String message) {
        if (isBuiltinAdmin(entity)) {
            throw new BusinessException(40900, message);
        }
    }

    private boolean isBuiltinAdmin(UserAccountEntity entity) {
        return entity != null && BUILTIN_ADMIN_USERNAME.equalsIgnoreCase(entity.getUsername());
    }

    private UserCreditAccountEntity ensureCreditAccount(Long userId) {
        LambdaQueryWrapper<UserCreditAccountEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserCreditAccountEntity::getUserId, userId)
                .eq(UserCreditAccountEntity::getDeleted, 0)
                .last("limit 1");
        UserCreditAccountEntity existing = userCreditAccountMapper.selectOne(wrapper);
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
        userCreditAccountMapper.insert(created);
        return created;
    }

    private String normalizeRoleOrDefault(String role) {
        if (!StringUtils.hasText(role)) {
            return ROLE_USER;
        }
        return normalizeRole(role);
    }

    private String normalizeRole(String role) {
        String value = role.trim().toUpperCase();
        if (!ROLE_USER.equals(value) && !ROLE_ADMIN.equals(value)) {
            throw new BusinessException(40000, "角色只支持 USER 或 ADMIN");
        }
        return value;
    }

    private String normalizeStatusOrDefault(String status) {
        if (!StringUtils.hasText(status)) {
            return STATUS_ENABLED;
        }
        return normalizeStatus(status);
    }

    private String normalizeStatus(String status) {
        String value = status.trim().toUpperCase();
        if (!STATUS_ENABLED.equals(value) && !STATUS_DISABLED.equals(value) && !STATUS_LOCKED.equals(value)) {
            throw new BusinessException(40000, "账号状态只支持 ENABLED、DISABLED 或 LOCKED");
        }
        return value;
    }

    private String normalizeCreditChangeType(String changeType) {
        if (!StringUtils.hasText(changeType)) {
            throw new BusinessException(40000, "积分调整类型不能为空");
        }
        String value = changeType.trim().toUpperCase();
        if (!"ADMIN_ADD".equals(value) && !"ADMIN_DEDUCT".equals(value) && !"ADMIN_SET".equals(value)) {
            throw new BusinessException(40000, "积分调整类型不支持");
        }
        return value;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private long safe(Long value) {
        return value == null ? 0L : value;
    }
}
