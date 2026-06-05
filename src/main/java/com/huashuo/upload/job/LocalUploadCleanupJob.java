package com.huashuo.upload.job;

import com.huashuo.upload.config.LocalUploadCleanupProperties;
import com.huashuo.upload.config.UploadProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

@Component
public class LocalUploadCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(LocalUploadCleanupJob.class);
    private static final String LOCAL_UPLOAD_SUBDIR = "upload";
    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "mov", "avi", "mkv", "webm");

    private final UploadProperties uploadProperties;
    private final LocalUploadCleanupProperties cleanupProperties;

    public LocalUploadCleanupJob(UploadProperties uploadProperties,
                                 LocalUploadCleanupProperties cleanupProperties) {
        this.uploadProperties = uploadProperties;
        this.cleanupProperties = cleanupProperties;
    }

    @Scheduled(cron = "${upload.local.cleanup.cron:0 0 3 * * ?}")
    public void cleanupLocalUploads() {
        if (!cleanupProperties.isEnabled()) {
            log.debug("Local upload cleanup skipped because it is disabled.");
            return;
        }

        Optional<Path> rootOpt = localUploadRoot();
        if (rootOpt.isEmpty()) {
            return;
        }

        Path cleanupRoot = rootOpt.get();
        int retentionDays = cleanupProperties.effectiveRetentionDays();
        int minFileAgeMinutes = cleanupProperties.effectiveMinFileAgeMinutes();
        Instant now = Instant.now();
        Instant retentionCutoff = now.minus(Duration.ofDays(retentionDays));
        Instant recentCutoff = now.minus(Duration.ofMinutes(minFileAgeMinutes));
        long started = System.currentTimeMillis();
        CleanupStats stats = new CleanupStats();

        log.info("Local upload cleanup started. dir={} retentionDays={} minFileAgeMinutes={}",
                cleanupRoot, retentionDays, minFileAgeMinutes);

        if (!Files.exists(cleanupRoot, LinkOption.NOFOLLOW_LINKS)) {
            log.info("Local upload cleanup completed. dir={} deletedCount=0 failedCount=0 freedBytes=0 costMs={}",
                    cleanupRoot, System.currentTimeMillis() - started);
            return;
        }

        try (Stream<Path> stream = Files.walk(cleanupRoot)) {
            stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .forEach(path -> cleanFile(path, cleanupRoot, retentionCutoff, recentCutoff, stats));
        } catch (IOException e) {
            log.warn("Local upload cleanup scan failed. dir={} reason={}", cleanupRoot, e.getMessage(), e);
        }

        cleanupEmptyDirectories(cleanupRoot);
        warnIfUploadRootTooLarge(cleanupRoot.getParent());

        log.info("Local upload cleanup completed. dir={} deletedCount={} failedCount={} freedBytes={} costMs={}",
                cleanupRoot, stats.deletedCount, stats.failedCount, stats.freedBytes,
                System.currentTimeMillis() - started);
    }

    private Optional<Path> localUploadRoot() {
        if (!StringUtils.hasText(uploadProperties.localRoot())) {
            log.warn("Local upload cleanup skipped because huashuo.upload.local-root is empty.");
            return Optional.empty();
        }
        Path uploadRoot = Path.of(uploadProperties.localRoot()).toAbsolutePath().normalize();
        Path cleanupRoot = uploadRoot.resolve(LOCAL_UPLOAD_SUBDIR).normalize();
        if (!cleanupRoot.startsWith(uploadRoot) || cleanupRoot.equals(uploadRoot)) {
            log.error("Local upload cleanup skipped because cleanup dir is outside upload root. root={} dir={}",
                    uploadRoot, cleanupRoot);
            return Optional.empty();
        }
        return Optional.of(cleanupRoot);
    }

    private void cleanFile(Path path, Path cleanupRoot, Instant retentionCutoff, Instant recentCutoff,
                           CleanupStats stats) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(cleanupRoot)) {
            log.warn("Local upload cleanup ignored file outside cleanup dir. file={} dir={}", normalized, cleanupRoot);
            return;
        }
        if (!isVideoFile(normalized)) {
            return;
        }

        try {
            FileTime lastModified = Files.getLastModifiedTime(normalized, LinkOption.NOFOLLOW_LINKS);
            Instant modifiedAt = lastModified.toInstant();
            if (modifiedAt.isAfter(retentionCutoff) || modifiedAt.isAfter(recentCutoff)) {
                return;
            }

            long size = Files.size(normalized);
            if (Files.deleteIfExists(normalized)) {
                stats.deletedCount++;
                stats.freedBytes += size;
                log.debug("Local upload cleanup deleted file={} size={}", normalized, size);
            }
        } catch (IOException | RuntimeException e) {
            stats.failedCount++;
            log.warn("Local upload cleanup failed to delete file={} reason={}", normalized, e.getMessage());
        }
    }

    private void cleanupEmptyDirectories(Path cleanupRoot) {
        try (Stream<Path> stream = Files.walk(cleanupRoot)) {
            stream.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !path.equals(cleanupRoot))
                    .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                    .forEach(this::deleteIfEmptyDirectory);
        } catch (IOException e) {
            log.debug("Local upload cleanup empty directory scan failed. dir={} reason={}",
                    cleanupRoot, e.getMessage());
        }
    }

    private void deleteIfEmptyDirectory(Path dir) {
        try (DirectoryStream<Path> children = Files.newDirectoryStream(dir)) {
            if (children.iterator().hasNext()) {
                return;
            }
            Files.deleteIfExists(dir);
            log.debug("Local upload cleanup deleted empty directory={}", dir);
        } catch (IOException ignored) {
        }
    }

    private void warnIfUploadRootTooLarge(Path uploadRoot) {
        if (uploadRoot == null || cleanupProperties.warnSizeBytes() == Long.MAX_VALUE) {
            return;
        }
        try {
            long totalBytes = directorySize(uploadRoot);
            if (totalBytes > cleanupProperties.warnSizeBytes()) {
                log.warn("Local upload directory size exceeds warning threshold. dir={} sizeBytes={} warnSizeMb={}",
                        uploadRoot, totalBytes, cleanupProperties.getWarnSizeMb());
            }
        } catch (IOException e) {
            log.debug("Local upload directory size calculation failed. dir={} reason={}", uploadRoot, e.getMessage());
        }
    }

    private long directorySize(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return 0L;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .mapToLong(this::safeSize)
                    .sum();
        }
    }

    private long safeSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private boolean isVideoFile(Path path) {
        String filename = path.getFileName() == null ? "" : path.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return false;
        }
        String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return VIDEO_EXTENSIONS.contains(ext);
    }

    /*
     * Current storage=local uploads share one local temp path for benchmark and storyboard analysis.
     * The first safe cleanup version relies on lastModifiedTime. A later iteration can join uploaded_file.file_path
     * with task.input_json/output_json and task.status, then skip QUEUED/RUNNING/PROCESSING task inputs explicitly.
     */
    private static class CleanupStats {
        private long deletedCount;
        private long failedCount;
        private long freedBytes;
    }
}
