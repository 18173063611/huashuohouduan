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
public class HuashuoBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(HuashuoBackendApplication.class, args);
    }
}
