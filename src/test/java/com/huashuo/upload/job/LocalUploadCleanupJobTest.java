package com.huashuo.upload.job;

import com.huashuo.upload.config.LocalUploadCleanupProperties;
import com.huashuo.upload.config.UploadProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalUploadCleanupJobTest {

    @TempDir
    Path uploadRoot;

    @Test
    void cleansOnlyOldLocalVideoFilesUnderUploadSubdir() throws Exception {
        Path localUploadDay = uploadRoot.resolve("upload/2026/06/01");
        Files.createDirectories(localUploadDay);

        Path oldMp4 = localUploadDay.resolve("old.mp4");
        Path oldTxt = localUploadDay.resolve("old.txt");
        Path recentMp4 = localUploadDay.resolve("recent.mp4");
        Files.writeString(oldMp4, "old video");
        Files.writeString(oldTxt, "not video");
        Files.writeString(recentMp4, "recent video");
        Files.setLastModifiedTime(oldMp4, FileTime.from(Instant.now().minus(2, ChronoUnit.HOURS)));
        Files.setLastModifiedTime(oldTxt, FileTime.from(Instant.now().minus(2, ChronoUnit.HOURS)));

        Path otherBusinessVideo = uploadRoot.resolve("writer/asr/old.mp4");
        Files.createDirectories(otherBusinessVideo.getParent());
        Files.writeString(otherBusinessVideo, "other business video");
        Files.setLastModifiedTime(otherBusinessVideo, FileTime.from(Instant.now().minus(2, ChronoUnit.HOURS)));

        LocalUploadCleanupJob job = new LocalUploadCleanupJob(
                new UploadProperties(uploadRoot.toString(), "/uploads", "", true),
                cleanupProperties()
        );

        job.cleanupLocalUploads();

        assertFalse(Files.exists(oldMp4));
        assertTrue(Files.exists(oldTxt));
        assertTrue(Files.exists(recentMp4));
        assertTrue(Files.exists(otherBusinessVideo));
        assertTrue(Files.exists(uploadRoot));
    }

    private LocalUploadCleanupProperties cleanupProperties() {
        LocalUploadCleanupProperties properties = new LocalUploadCleanupProperties();
        properties.setEnabled(true);
        properties.setRetentionDays(0);
        properties.setMinFileAgeMinutes(30);
        properties.setWarnSizeMb(10240);
        return properties;
    }
}
