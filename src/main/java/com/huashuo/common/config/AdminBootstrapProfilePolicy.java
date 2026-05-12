package com.huashuo.common.config;

import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 内置管理员口令策略：与 Spring {@code spring.profiles.active} 联动，供启动期初始化与单元测试复用。
 */
public final class AdminBootstrapProfilePolicy {

    private static final Set<String> WEAK_PLAINTEXT_PASSWORDS = Set.of(
            "admin1234", "123456", "password", "admin", "root", "qwerty"
    );

    private AdminBootstrapProfilePolicy() {
    }

    public enum AdminPasswordPolicy {
        STRICT,
        LENIENT
    }

    /**
     * 任一 profile 为 prod/test → 严格；否则若存在 dev/local → 宽松；其余未知 profile 按严格处理。
     */
    public static AdminPasswordPolicy resolve(String... activeProfiles) {
        Set<String> profiles = Arrays.stream(activeProfiles == null ? new String[0] : activeProfiles)
                .map(p -> p.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        for (String p : profiles) {
            if ("prod".equals(p) || "test".equals(p)) {
                return AdminPasswordPolicy.STRICT;
            }
        }
        for (String p : profiles) {
            if ("dev".equals(p) || "local".equals(p)) {
                return AdminPasswordPolicy.LENIENT;
            }
        }
        return AdminPasswordPolicy.STRICT;
    }

    public static boolean isWeakPlaintextPassword(String plaintext) {
        if (!StringUtils.hasText(plaintext)) {
            return true;
        }
        return WEAK_PLAINTEXT_PASSWORDS.contains(plaintext.trim().toLowerCase(Locale.ROOT));
    }
}
