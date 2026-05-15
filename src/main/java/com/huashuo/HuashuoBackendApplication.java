package com.huashuo;

import com.huashuo.avatar.config.VolcengineImageProperties;
import com.huashuo.upload.config.UploadProperties;
import com.huashuo.upload.tos.VolcengineTosProperties;
import com.huashuo.video.config.ViduDigitalHumanProperties;
import com.huashuo.voice.config.VolcengineTtsProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableRabbit
@MapperScan({
        "com.huashuo.upload.mapper",
        "com.huashuo.asset.mapper",
        "com.huashuo.template.mapper",
        "com.huashuo.avatar.mapper",
        "com.huashuo.task.mapper",
        "com.huashuo.script.mapper",
        "com.huashuo.voice.mapper",
        "com.huashuo.user.mapper",
        "com.huashuo.admin.mapper",
        "com.huashuo.billing.mapper"
})
@EnableConfigurationProperties({
        UploadProperties.class,
        VolcengineTtsProperties.class,
        VolcengineImageProperties.class,
        VolcengineTosProperties.class,
        ViduDigitalHumanProperties.class
})
/**
 * 后端应用启动入口：负责启动 Spring Boot、扫描 Mapper，并加载上传配置等全局能力。
 */
public class HuashuoBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(HuashuoBackendApplication.class, args);
    }
}
