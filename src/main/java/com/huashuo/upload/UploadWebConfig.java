package com.huashuo.upload;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

@Configuration
public class UploadWebConfig implements WebMvcConfigurer {

    private final UploadProperties uploadProperties;

    public UploadWebConfig(UploadProperties uploadProperties) {
        this.uploadProperties = uploadProperties;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = Path.of(uploadProperties.localRoot()).toAbsolutePath().toUri().toString();
        registry.addResourceHandler(uploadProperties.previewPrefix() + "/**")
                .addResourceLocations(location);
    }
}
