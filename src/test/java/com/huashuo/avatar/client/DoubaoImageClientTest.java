package com.huashuo.avatar.client;

import com.huashuo.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DoubaoImageClientTest {

    @Test
    void normalizeSizeForProviderAcceptsFrontendKValues() {
        assertThat(DoubaoImageClient.normalizeSizeForProvider("1K", "2K")).isEqualTo("1024x1024");
        assertThat(DoubaoImageClient.normalizeSizeForProvider("2K", "2K")).isEqualTo("2k");
        assertThat(DoubaoImageClient.normalizeSizeForProvider("3k", "2K")).isEqualTo("3k");
        assertThat(DoubaoImageClient.normalizeSizeForProvider(null, "2K")).isEqualTo("2k");
    }

    @Test
    void normalizeSizeForProviderPreservesExplicitDimensions() {
        assertThat(DoubaoImageClient.normalizeSizeForProvider("2048X2048", "2K")).isEqualTo("2048x2048");
    }

    @Test
    void normalizeSizeForProviderRejectsUnsupportedValues() {
        assertThatThrownBy(() -> DoubaoImageClient.normalizeSizeForProvider("small", "2K"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Avatar image size");
    }
}
