package com.huashuo;

import com.huashuo.upload.FwxUploadProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(FwxUploadProperties.class)
public class HuashuoBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(HuashuoBackendApplication.class, args);
    }
}
