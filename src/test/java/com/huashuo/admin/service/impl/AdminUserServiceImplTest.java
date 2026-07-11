package com.huashuo.admin.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.huashuo.admin.config.AdminAccessProperties;
import com.huashuo.admin.dto.AdminUserCreateRequest;
import com.huashuo.admin.service.AdminAccessService;
import com.huashuo.admin.service.AdminOperationAuditService;
import com.huashuo.admin.service.AdminOperationContext;
import com.huashuo.admin.vo.AdminUserItem;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.user.entity.UserAccountEntity;
import com.huashuo.user.entity.UserCreditAccountEntity;
import com.huashuo.user.entity.UserCreditLogEntity;
import com.huashuo.user.entity.UserFeaturePermissionEntity;
import com.huashuo.user.entity.UserSessionEntity;
import com.huashuo.user.mapper.UserAccountMapper;
import com.huashuo.user.mapper.UserCreditAccountMapper;
import com.huashuo.user.mapper.UserCreditLogMapper;
import com.huashuo.user.mapper.UserFeaturePermissionMapper;
import com.huashuo.user.mapper.UserSessionMapper;
import com.huashuo.user.security.AuthSessionService;
import com.huashuo.user.service.UserFeaturePermissionService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class AdminUserServiceImplTest {

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        initTableInfo(UserAccountEntity.class);
        initTableInfo(UserCreditAccountEntity.class);
        initTableInfo(UserFeaturePermissionEntity.class);
        initTableInfo(UserSessionEntity.class);
    }

    private final UserAccountMapper userAccountMapper = mock(UserAccountMapper.class);
    private final AdminAccessService adminAccessService = mock(AdminAccessService.class);
    private final AdminOperationAuditService auditService = mock(AdminOperationAuditService.class);
    private final UserCreditAccountMapper userCreditAccountMapper = mock(UserCreditAccountMapper.class);
    private final UserCreditLogMapper userCreditLogMapper = mock(UserCreditLogMapper.class);
    private final UserFeaturePermissionMapper userFeaturePermissionMapper = mock(UserFeaturePermissionMapper.class);
    private final UserSessionMapper userSessionMapper = mock(UserSessionMapper.class);
    private final AuthSessionService authSessionService = mock(AuthSessionService.class);
    private final AdminAccessProperties adminAccessProperties = new AdminAccessProperties();
    private final AdminUserServiceImpl service = new AdminUserServiceImpl(
            userAccountMapper,
            adminAccessService,
            auditService,
            userCreditAccountMapper,
            userCreditLogMapper,
            userFeaturePermissionMapper,
            userSessionMapper,
            authSessionService,
            adminAccessProperties
    );

    @BeforeEach
    void setUpDefaults() {
        when(userFeaturePermissionMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void deleteUserReleasesUsernameThenUsesLogicDelete() {
        UserAccountEntity user = user("ztj");
        UserCreditAccountEntity account = creditAccount();
        when(userAccountMapper.selectById(42L)).thenReturn(user);
        when(userCreditAccountMapper.selectOne(any())).thenReturn(account);
        when(adminAccessService.roleOf(42L, "ztj")).thenReturn("USER");
        when(userAccountMapper.updateById(any(UserAccountEntity.class))).thenReturn(1);
        when(userAccountMapper.deleteById(42L)).thenReturn(1);

        service.deleteUser(42L, new AdminOperationContext(1L, "127.0.0.1", "trace-delete-user"));

        ArgumentCaptor<UserAccountEntity> userCaptor = ArgumentCaptor.forClass(UserAccountEntity.class);
        verify(userAccountMapper).updateById(userCaptor.capture());
        UserAccountEntity updated = userCaptor.getValue();
        assertEquals(42L, updated.getUserId());
        assertTrue(updated.getUsername().startsWith("__deleted_42_"));
        assertEquals(0, updated.getDeleted());
        assertNotNull(updated.getUpdatedAt());
        verify(userAccountMapper).deleteById(42L);
        verify(auditService).record(any(), eq("USER_DELETE"), eq("USER"), eq(42L), any(), any());
    }

    @Test
    void deleteUserFailsWhenUsernameReleaseDoesNotUpdateRow() {
        UserAccountEntity user = user("ztj");
        when(userAccountMapper.selectById(42L)).thenReturn(user);
        when(userCreditAccountMapper.selectOne(any())).thenReturn(creditAccount());
        when(adminAccessService.roleOf(42L, "ztj")).thenReturn("USER");
        when(userAccountMapper.updateById(any(UserAccountEntity.class))).thenReturn(0);

        assertThrows(BusinessException.class,
                () -> service.deleteUser(42L, new AdminOperationContext(1L, "127.0.0.1", "trace-delete-user")));

        verify(userAccountMapper, never()).deleteById(42L);
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void createUserAppliesFeaturePermissionsAndInitialCredits() {
        AtomicReference<UserAccountEntity> savedUser = new AtomicReference<>();
        AtomicReference<UserCreditAccountEntity> savedCreditAccount = new AtomicReference<>();
        List<UserFeaturePermissionEntity> savedPermissions = new ArrayList<>();

        when(userAccountMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            UserAccountEntity user = invocation.getArgument(0);
            user.setUserId(88L);
            savedUser.set(user);
            return 1;
        }).when(userAccountMapper).insert(any(UserAccountEntity.class));
        when(userAccountMapper.selectById(88L)).thenAnswer(invocation -> savedUser.get());
        when(adminAccessService.roleOf(88L, "pet001")).thenReturn("USER");

        when(userCreditAccountMapper.selectOne(any())).thenAnswer(invocation -> savedCreditAccount.get());
        doAnswer(invocation -> {
            UserCreditAccountEntity account = invocation.getArgument(0);
            account.setCreditAccountId(188L);
            savedCreditAccount.set(account);
            return 1;
        }).when(userCreditAccountMapper).insert(any(UserCreditAccountEntity.class));

        when(userFeaturePermissionMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            UserFeaturePermissionEntity permission = invocation.getArgument(0);
            permission.setPermissionId((long) savedPermissions.size() + 1);
            savedPermissions.add(permission);
            return 1;
        }).when(userFeaturePermissionMapper).insert(any(UserFeaturePermissionEntity.class));
        when(userFeaturePermissionMapper.selectList(any()))
                .thenAnswer(invocation -> savedPermissions.stream()
                        .filter(permission -> permission.getEnabled() != null && permission.getEnabled() == 1)
                        .toList());

        AdminUserCreateRequest request = new AdminUserCreateRequest(
                "pet001",
                "peta1001",
                "宠物测试账号 001",
                "USER",
                "ENABLED",
                null,
                null,
                "pet-only",
                List.of(UserFeaturePermissionService.PET_CREATION_ACCESS),
                15000L
        );

        AdminUserItem created = service.createUser(
                request,
                new AdminOperationContext(1L, "127.0.0.1", "trace-create-pet-user")
        );

        assertEquals(88L, created.userId());
        assertEquals(15000L, created.creditBalance());
        assertEquals(List.of(UserFeaturePermissionService.PET_CREATION_ACCESS), created.permissions());
        assertEquals(15000L, savedCreditAccount.get().getBalance());
        assertEquals(15000L, savedCreditAccount.get().getTotalRecharged());

        ArgumentCaptor<UserCreditLogEntity> creditLogCaptor = ArgumentCaptor.forClass(UserCreditLogEntity.class);
        verify(userCreditLogMapper).insert(creditLogCaptor.capture());
        assertEquals("ADMIN_SET", creditLogCaptor.getValue().getChangeType());
        assertEquals(15000L, creditLogCaptor.getValue().getChangeAmount());
        assertEquals(15000L, creditLogCaptor.getValue().getAfterBalance());
        verify(userFeaturePermissionMapper, times(1)).insert(any(UserFeaturePermissionEntity.class));
    }

    private UserAccountEntity user(String username) {
        UserAccountEntity user = new UserAccountEntity();
        user.setUserId(42L);
        user.setUsername(username);
        user.setPasswordHash("$2a$10$123456789012345678901u123456789012345678901234567890123456");
        user.setDisplayName("钟恬洁");
        user.setRole("USER");
        user.setStatus("ENABLED");
        user.setDeleted(0);
        user.setCreatedAt(LocalDateTime.now().minusDays(1));
        user.setUpdatedAt(LocalDateTime.now().minusDays(1));
        return user;
    }

    private UserCreditAccountEntity creditAccount() {
        UserCreditAccountEntity account = new UserCreditAccountEntity();
        account.setCreditAccountId(100L);
        account.setUserId(42L);
        account.setBalance(0L);
        account.setFrozenBalance(0L);
        account.setTotalRecharged(0L);
        account.setTotalConsumed(0L);
        account.setDeleted(0);
        return account;
    }

    private static void initTableInfo(Class<?> entityClass) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace(entityClass.getName());
        TableInfoHelper.initTableInfo(assistant, entityClass);
    }
}
