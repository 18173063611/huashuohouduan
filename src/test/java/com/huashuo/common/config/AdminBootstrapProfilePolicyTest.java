package com.huashuo.common.config;

import org.junit.jupiter.api.Test;

import static com.huashuo.common.config.AdminBootstrapProfilePolicy.AdminPasswordPolicy.LENIENT;
import static com.huashuo.common.config.AdminBootstrapProfilePolicy.AdminPasswordPolicy.STRICT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminBootstrapProfilePolicyTest {

    @Test
    void prodWinsOverDev() {
        assertEquals(STRICT, AdminBootstrapProfilePolicy.resolve("dev", "prod"));
    }

    @Test
    void testProfileIsStrict() {
        assertEquals(STRICT, AdminBootstrapProfilePolicy.resolve("test"));
    }

    @Test
    void devProfileIsLenient() {
        assertEquals(LENIENT, AdminBootstrapProfilePolicy.resolve("dev"));
    }

    @Test
    void localProfileIsLenient() {
        assertEquals(LENIENT, AdminBootstrapProfilePolicy.resolve("local"));
    }

    @Test
    void unknownProfileDefaultsToStrict() {
        assertEquals(STRICT, AdminBootstrapProfilePolicy.resolve("staging"));
    }

    @Test
    void emptyProfilesDefaultsToStrict() {
        assertEquals(STRICT, AdminBootstrapProfilePolicy.resolve());
    }

    @Test
    void weakPasswordDetection() {
        assertTrue(AdminBootstrapProfilePolicy.isWeakPlaintextPassword("admin1234"));
        assertTrue(AdminBootstrapProfilePolicy.isWeakPlaintextPassword("password"));
        assertFalse(AdminBootstrapProfilePolicy.isWeakPlaintextPassword("xK9#mP2$vL8qR4!nW7"));
    }
}
