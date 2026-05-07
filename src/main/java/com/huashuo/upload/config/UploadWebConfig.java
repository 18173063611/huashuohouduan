package com.huashuo.upload.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

@Configuration
@ConditionalOnProperty(prefix = "huashuo.upload", name = "serve-local-preview", havingValue = "true")
public class UploadWebConfig implements WebMvcConfigurer {

    private final UploadProperties uploadProperties;

    public UploadWebConfig(UploadProperties uploadProperties) {
        this.uploadProperties = uploadProperties;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (!StringUtils.hasText(uploadProperties.localRoot())) {
            return;
        }
        String location = Path.of(uploadProperties.localRoot()).toAbsolutePath().toUri().toString();
        registry.addResourceHandler(uploadProperties.effectivePreviewPrefix() + "/**")
                .addResourceLocations(location);
    }
}
