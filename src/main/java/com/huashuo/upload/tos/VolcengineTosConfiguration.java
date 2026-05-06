package com.huashuo.upload.tos;

import com.volcengine.tos.TOSV2;
import com.volcengine.tos.TOSV2ClientBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
@ConditionalOnProperty(prefix = "volcengine.tos", name = "enabled", havingValue = "true")
public class VolcengineTosConfiguration {

    @Bean
    public TOSV2 tosV2Client(VolcengineTosProperties properties) {
        if (!StringUtils.hasText(properties.accessKeyId()) || !StringUtils.hasText(properties.secretAccessKey())) {
            throw new IllegalStateException(
                    "volcengine.tos.enabled=true 但未配置密钥：请设置环境变量 VOLCENGINE_TOS_ACCESS_KEY_ID、"
                            + "VOLCENGINE_TOS_SECRET_ACCESS_KEY，或使用未被 Git 跟踪的本地配置。"
            );
        }
        return new TOSV2ClientBuilder().build(
                properties.region(),
                properties.endpoint(),
                properties.accessKeyId(),
                properties.secretAccessKey()
        );
    }
}
