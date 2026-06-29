package com.huashuo.admin.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.huashuo.admin.config.AdminAccessProperties;
import com.huashuo.admin.service.AdminAccessService;
import com.huashuo.admin.service.AdminOperationAuditService;
import com.huashuo.admin.service.AdminOperationContext;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.user.entity.UserAccountEntity;
import com.huashuo.user.entity.UserCreditAccountEntity;
import com.huashuo.user.entity.UserSessionEntity;
import com.huashuo.user.mapper.UserAccountMapper;
import com.huashuo.user.mapper.UserCreditAccountMapper;
import com.huashuo.user.mapper.UserCreditLogMapper;
import com.huashuo.user.mapper.UserSessionMapper;
import com.huashuo.user.security.AuthSessionService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminUserServiceImplTest {

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        initTableInfo(UserAccountEntity.class);
        initTableInfo(UserCreditAccountEntity.class);
        initTableInfo(UserSessionEntity.class);
    }

    private final UserAccountMapper userAccountMapper = mock(UserAccountMapper.class);
    private final AdminAccessService adminAccessService = mock(AdminAccessService.class);
    private final AdminOperationAuditService auditService = mock(AdminOperationAuditService.class);
    private final UserCreditAccountMapper userCreditAccountMapper = mock(UserCreditAccountMapper.class);
    private final UserCreditLogMapper userCreditLogMapper = mock(UserCreditLogMapper.class);
    private final UserSessionMapper userSessionMapper = mock(UserSessionMapper.class);
    private final AuthSessionService authSessionService = mock(AuthSessionService.class);
    private final AdminAccessProperties adminAccessProperties = new AdminAccessProperties();
    private final AdminUserServiceImpl service = new AdminUserServiceImpl(
            userAccountMapper,
            adminAccessService,
            auditService,
            userCreditAccountMapper,
            userCreditLogMapper,
            userSessionMapper,
            authSessionService,
            adminAccessProperties
    );

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
