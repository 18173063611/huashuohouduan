package com.huashuo.petvideo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "huashuo.pet-video")
public class PetVideoProperties {

    private boolean providerSubmitEnabled = false;
    private boolean dryRunEnabled = true;

    public boolean isProviderSubmitEnabled() {
        return providerSubmitEnabled;
    }

    public void setProviderSubmitEnabled(boolean providerSubmitEnabled) {
        this.providerSubmitEnabled = providerSubmitEnabled;
    }

    public boolean isDryRunEnabled() {
        return dryRunEnabled;
    }

    public void setDryRunEnabled(boolean dryRunEnabled) {
        this.dryRunEnabled = dryRunEnabled;
    }
}
