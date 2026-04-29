package com.huashuo;

import com.huashuo.upload.config.UploadProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@MapperScan({
        "com.huashuo.project.mapper",
        "com.huashuo.upload.mapper",
        "com.huashuo.asset.mapper",
        "com.huashuo.task.mapper",
        "com.huashuo.script.mapper"
})
@EnableConfigurationProperties(UploadProperties.class)
/**
 * 后端应用启动入口：负责启动 Spring Boot、扫描 Mapper，并加载上传配置等全局能力。
 */
public class HuashuoBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(HuashuoBackendApplication.class, args);
    }
}
