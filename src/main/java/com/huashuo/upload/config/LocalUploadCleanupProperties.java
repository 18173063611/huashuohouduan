package com.huashuo.upload.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "upload.local.cleanup")
public class LocalUploadCleanupProperties {

    private boolean enabled = true;
    private int retentionDays = 3;
    private String cron = "0 0 3 * * ?";
    private long warnSizeMb = 10240;
    private int minFileAgeMinutes = 30;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public long getWarnSizeMb() {
        return warnSizeMb;
    }

    public void setWarnSizeMb(long warnSizeMb) {
        this.warnSizeMb = warnSizeMb;
    }

    public int getMinFileAgeMinutes() {
        return minFileAgeMinutes;
    }

    public void setMinFileAgeMinutes(int minFileAgeMinutes) {
        this.minFileAgeMinutes = minFileAgeMinutes;
    }

    public int effectiveRetentionDays() {
        return Math.max(0, retentionDays);
    }

    public int effectiveMinFileAgeMinutes() {
        return Math.max(1, minFileAgeMinutes);
    }

    public long warnSizeBytes() {
        if (warnSizeMb <= 0) {
            return Long.MAX_VALUE;
        }
        return warnSizeMb * 1024L * 1024L;
    }
}
