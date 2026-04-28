package com.huashuo;

import com.huashuo.upload.UploadProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(UploadProperties.class)
public class HuashuoBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(HuashuoBackendApplication.class, args);
    }
}
