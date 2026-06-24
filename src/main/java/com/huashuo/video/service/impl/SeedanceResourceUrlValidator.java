package com.huashuo.video.service.impl;

import com.huashuo.common.exception.BusinessException;
import com.huashuo.storage.StorageService;
import com.huashuo.storage.UploadResult;
import com.huashuo.storage.resolve.StoredUrlResolver;
import com.huashuo.upload.config.UploadProperties;
import com.huashuo.upload.tos.VolcengineTosProperties;
import com.huashuo.video.DTO.CarSalesVideoDTO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class SeedanceResourceUrlValidator {

    private static final String TYPE_IMAGE = "image";
    private static final String TYPE_AUDIO = "audio";
    private static final long MIN_MEDIA_BYTES = 512L;
    private static final long MAX_IMAGE_BYTES = 16L * 1024L * 1024L;
    private static final long MAX_AUDIO_BYTES = 32L * 1024L * 1024L;
    private static final Set<String> IMAGE_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/webp"
    );
    private static final Set<String> REJECTED_AUDIO_CONTENT_TYPES = Set.of(
            "text/html",
            "text/plain",
            "application/json",
            "application/xml",
            "text/xml"
    );

    private final StoredUrlResolver storedUrlResolver;
    private final StorageService storageService;
    private final UploadProperties uploadProperties;
    private final VolcengineTosProperties tosProperties;
    private final HttpClient httpClient;
    private final ThreadLocal<ResolutionContext> resolutionContext = new ThreadLocal<>();

    public SeedanceResourceUrlValidator(StoredUrlResolver storedUrlResolver,
                                        StorageService storageService,
                                        UploadProperties uploadProperties,
                                        VolcengineTosProperties tosProperties) {
        this.storedUrlResolver = storedUrlResolver;
        this.storageService = storageService;
        this.uploadProperties = uploadProperties;
        this.tosProperties = tosProperties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public void beginResourceSnapshot() {
        resolutionContext.set(new ResolutionContext());
    }

    public List<CarSalesVideoDTO.ResourceSnapshot> drainResourceSnapshots() {
        ResolutionContext context = resolutionContext.get();
        resolutionContext.remove();
        if (context == null || context.snapshots.isEmpty()) {
            return null;
        }
        return List.copyOf(context.snapshots);
    }

    public String resolveImageUrl(String rawUrl) {
        return resolveModelAccessibleUrl(rawUrl, TYPE_IMAGE, MAX_IMAGE_BYTES, "image");
    }

    public String resolveAudioUrl(String rawUrl) {
        return resolveModelAccessibleUrl(rawUrl, TYPE_AUDIO, MAX_AUDIO_BYTES, "upload");
    }

    private String resolveModelAccessibleUrl(String rawUrl, String resourceType, long maxBytes, String storageCategory) {
        if (!StringUtils.hasText(rawUrl)) {
            throw new BusinessException(40000, resourceType + " URL is required");
        }
        String original = rawUrl.trim();
        ResolutionContext context = resolutionContext.get();
        String cacheKey = resourceType + "|" + original;
        if (context != null && context.cache.containsKey(cacheKey)) {
            return context.cache.get(cacheKey).canonicalUrl();
        }

        String resolved = storedUrlResolver.resolveToPublicUrl(original);
        if (!StringUtils.hasText(resolved)) {
            throw new BusinessException(40000, resourceType + " URL is required");
        }
        String value = resolved.trim();
        URI uri = parseModelUrl(value, resourceType, original);
        validatePublicHttpsUrl(uri, resourceType, original);

        ResourceProbe probe = probeUrl(value, resourceType, maxBytes);
        ResolvedResource result = isPlatformUrl(value)
                ? new ResolvedResource(resourceType, original, value, probe.contentType(), probe.contentLength(),
                null, Instant.now().toString(), null)
                : rehostResource(value, original, resourceType, storageCategory, probe);

        if (context != null) {
            context.cache.put(cacheKey, result);
            context.snapshots.add(toSnapshot(result));
        }
        return result.canonicalUrl();
    }

    private ResourceProbe probeUrl(String url, String resourceType, long maxBytes) {
        URI uri = URI.create(url);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(12))
                .header("User-Agent", "Huashuo-Seedance-Preflight/1.0")
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<Void> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            throw new BusinessException(40000,
                    resourceType + " URL is not reachable by the video model: " + abbreviateUrl(url));
        }
        int status = response.statusCode();
        if (status >= 300 && status < 400) {
            throw new BusinessException(40000,
                    resourceType + " URL must not redirect. Please use the final TOS/CDN URL: " + abbreviateUrl(url));
        }
        if (status < 200 || status >= 300) {
            throw new BusinessException(40000,
                    resourceType + " URL HEAD check failed, status=" + status + ": " + abbreviateUrl(url));
        }
        String contentType = normalizeContentType(response.headers().firstValue("content-type").orElse(""));
        long contentLength = parseContentLength(response.headers().firstValue("content-length").orElse(null));
        validateContentType(resourceType, contentType, url);
        validateContentLength(resourceType, contentLength, maxBytes, url);
        return new ResourceProbe(contentType, contentLength);
    }

    private ResolvedResource rehostResource(String url, String original, String resourceType,
                                            String storageCategory, ResourceProbe probe) {
        URI uri = URI.create(url);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Huashuo-Seedance-Rehost/1.0")
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception e) {
            throw new BusinessException(40000,
                    resourceType + " URL download failed before generation: " + abbreviateUrl(url));
        }
        int status = response.statusCode();
        if (status >= 300 && status < 400) {
            closeQuietly(response.body());
            throw new BusinessException(40000,
                    resourceType + " URL must not redirect during download: " + abbreviateUrl(url));
        }
        if (status < 200 || status >= 300) {
            closeQuietly(response.body());
            throw new BusinessException(40000,
                    resourceType + " URL download failed, status=" + status + ": " + abbreviateUrl(url));
        }
        String contentType = normalizeContentType(response.headers().firstValue("content-type").orElse(probe.contentType()));
        long contentLength = parseContentLength(response.headers().firstValue("content-length").orElse(null));
        if (contentLength < 0) {
            contentLength = probe.contentLength();
        }
        validateContentType(resourceType, contentType, url);
        validateContentLength(resourceType, contentLength, resourceType.equals(TYPE_IMAGE) ? MAX_IMAGE_BYTES : MAX_AUDIO_BYTES, url);

        try (InputStream raw = response.body()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream in = new DigestInputStream(raw, digest)) {
                UploadResult uploaded = storageService.upload(
                        in,
                        contentLength,
                        filenameFor(uri, contentType, resourceType),
                        contentType,
                        storageCategory
                );
                return new ResolvedResource(resourceType, original, uploaded.url(), uploaded.contentType(), uploaded.size(),
                        toHex(digest.digest()), Instant.now().toString(), null);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(50000,
                    "Failed to rehost " + resourceType + " resource to object storage: " + e.getMessage());
        }
    }

    private URI parseModelUrl(String value, String resourceType, String original) {
        try {
            return URI.create(value);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(40000,
                    resourceType + " URL format is invalid: " + abbreviateUrl(original));
        }
    }

    private void validatePublicHttpsUrl(URI uri, String resourceType, String original) {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(scheme)) {
            throw new BusinessException(40000,
                    resourceType + " URL must be a public HTTPS URL: " + abbreviateUrl(original));
        }
        if (!StringUtils.hasText(host) || isPrivateOrLocalHost(host) || isIpLiteral(host)) {
            throw new BusinessException(40000,
                    resourceType + " URL host is not publicly usable by the video model: " + abbreviateUrl(original));
        }
    }

    private void validateContentType(String resourceType, String contentType, String url) {
        if (!StringUtils.hasText(contentType)) {
            throw new BusinessException(40000,
                    resourceType + " URL must return a valid Content-Type header: " + abbreviateUrl(url));
        }
        if (TYPE_IMAGE.equals(resourceType) && !IMAGE_CONTENT_TYPES.contains(contentType)) {
            throw new BusinessException(40000,
                    "Image URL Content-Type must be image/jpeg, image/png or image/webp: " + abbreviateUrl(url));
        }
        if (TYPE_AUDIO.equals(resourceType) && REJECTED_AUDIO_CONTENT_TYPES.contains(contentType)) {
            throw new BusinessException(40000,
                    "Audio URL Content-Type is not supported: " + abbreviateUrl(url));
        }
    }

    private void validateContentLength(String resourceType, long contentLength, long maxBytes, String url) {
        if (contentLength < MIN_MEDIA_BYTES) {
            throw new BusinessException(40000,
                    resourceType + " URL must return a reasonable Content-Length: " + abbreviateUrl(url));
        }
        if (contentLength > maxBytes) {
            throw new BusinessException(40000,
                    resourceType + " URL is too large for video generation preflight: " + abbreviateUrl(url));
        }
    }

    private boolean isPlatformUrl(String value) {
        return isUnderBase(value, uploadProperties == null ? null : uploadProperties.effectivePublicBaseUrl())
                || isUnderBase(value, tosProperties == null ? null : tosProperties.publicBaseUrl());
    }

    private boolean isUnderBase(String value, String base) {
        if (!StringUtils.hasText(value) || !StringUtils.hasText(base)) {
            return false;
        }
        try {
            URI url = URI.create(value.trim());
            URI root = URI.create(trimTrailingSlash(base.trim()));
            if (!same(root.getScheme(), url.getScheme()) || !same(root.getHost(), url.getHost())) {
                return false;
            }
            if (normalizePort(root) != normalizePort(url)) {
                return false;
            }
            String rootPath = trimTrailingSlash(StringUtils.hasText(root.getPath()) ? root.getPath() : "");
            String urlPath = StringUtils.hasText(url.getPath()) ? url.getPath() : "";
            return !StringUtils.hasText(rootPath) || urlPath.equals(rootPath) || urlPath.startsWith(rootPath + "/");
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isPrivateOrLocalHost(String host) {
        if (!StringUtils.hasText(host)) {
            return true;
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        if ("localhost".equals(h) || h.endsWith(".localhost") || h.endsWith(".local")
                || "::1".equals(h) || "0:0:0:0:0:0:0:1".equals(h)) {
            return true;
        }
        if (h.startsWith("127.") || h.startsWith("10.") || h.startsWith("0.") || h.startsWith("169.254.")) {
            return true;
        }
        String[] parts = h.split("\\.");
        if (parts.length == 4) {
            try {
                int first = Integer.parseInt(parts[0]);
                int second = Integer.parseInt(parts[1]);
                if (first == 192 && second == 168) {
                    return true;
                }
                return first == 172 && second >= 16 && second <= 31;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }

    private boolean isIpLiteral(String host) {
        if (!StringUtils.hasText(host)) {
            return true;
        }
        String h = host.trim();
        if (h.contains(":")) {
            return true;
        }
        String[] parts = h.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            try {
                int value = Integer.parseInt(part);
                if (value < 0 || value > 255) {
                    return false;
                }
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return true;
    }

    private String filenameFor(URI uri, String contentType, String resourceType) {
        String path = uri == null ? "" : uri.getPath();
        String name = "";
        if (StringUtils.hasText(path)) {
            int slash = path.lastIndexOf('/');
            name = slash >= 0 ? path.substring(slash + 1) : path;
        }
        if (!StringUtils.hasText(name) || !name.contains(".")) {
            name = resourceType + "-" + UUID.randomUUID() + extensionFor(contentType, resourceType);
        }
        return name;
    }

    private String extensionFor(String contentType, String resourceType) {
        String type = normalizeContentType(contentType);
        return switch (type) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "audio/mpeg", "audio/mp3" -> ".mp3";
            case "audio/wav", "audio/x-wav" -> ".wav";
            case "audio/mp4", "video/mp4" -> ".mp4";
            default -> TYPE_IMAGE.equals(resourceType) ? ".jpg" : ".bin";
        };
    }

    private long parseContentLength(String value) {
        if (!StringUtils.hasText(value)) {
            return -1L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private String normalizeContentType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return "";
        }
        return contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private CarSalesVideoDTO.ResourceSnapshot toSnapshot(ResolvedResource resource) {
        CarSalesVideoDTO.ResourceSnapshot snapshot = new CarSalesVideoDTO.ResourceSnapshot();
        snapshot.setResourceType(resource.resourceType());
        snapshot.setSourceUrl(resource.sourceUrl());
        snapshot.setCanonicalUrl(resource.canonicalUrl());
        snapshot.setContentType(resource.contentType());
        snapshot.setSize(resource.size());
        snapshot.setHash(resource.hash());
        snapshot.setCheckedAt(resource.checkedAt());
        snapshot.setExpiresAt(resource.expiresAt());
        return snapshot;
    }

    private String abbreviateUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return "";
        }
        String value = url.trim();
        return value.length() <= 180 ? value : value.substring(0, 177) + "...";
    }

    private void closeQuietly(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (Exception ignored) {
        }
    }

    private boolean same(String a, String b) {
        return String.valueOf(a).equalsIgnoreCase(String.valueOf(b));
    }

    private int normalizePort(URI uri) {
        int port = uri.getPort();
        if (port >= 0) {
            return port;
        }
        return "http".equalsIgnoreCase(uri.getScheme()) ? 80 : 443;
    }

    private String trimTrailingSlash(String value) {
        String v = value == null ? "" : value;
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    private String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static final class ResolutionContext {
        private final List<CarSalesVideoDTO.ResourceSnapshot> snapshots = new ArrayList<>();
        private final Map<String, ResolvedResource> cache = new HashMap<>();
    }

    private record ResourceProbe(String contentType, long contentLength) {
    }

    private record ResolvedResource(String resourceType, String sourceUrl, String canonicalUrl, String contentType,
                                    Long size, String hash, String checkedAt, String expiresAt) {
    }
}
