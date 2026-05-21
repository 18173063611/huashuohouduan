package com.huashuo.writer.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.upload.config.UploadProperties;
import com.huashuo.upload.tos.TosUploadService;
import com.huashuo.upload.tos.UploadPublicBaseProvider;
import com.huashuo.writer.dto.RewriteDTO;
import com.huashuo.writer.pojo.DouyinAuthorInfo;
import com.huashuo.writer.pojo.DouyinVideoParseRequest;
import com.huashuo.writer.pojo.DouyinVideoParseResponse;
import com.huashuo.writer.pojo.DouyinVideoTranscriptRequest;
import com.huashuo.writer.pojo.VideoDownloadResource;
import com.huashuo.writer.pojo.WriterVO;
import com.huashuo.writer.service.WriterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class WriterServiceImpl implements WriterService {

    private static final String HYBRID_VIDEO_DATA_PATH = "/api/v1/hybrid/video_data";
    private static final String DOUYIN_SHARE_VIDEO_PATH = "/api/v1/douyin/web/fetch_one_video_by_share_url";
    private static final String TIKTOK_SHARE_VIDEO_PATH = "/api/v1/tiktok/app/v3/fetch_one_video_by_share_url";
    private static final String TIKTOK_SHARE_VIDEO_V2_PATH = "/api/v1/tiktok/app/v3/fetch_one_video_by_share_url_v2";
    private static final String TIKTOK_VIDEO_V2_PATH = "/api/v1/tiktok/app/v3/fetch_one_video_v2";
    private static final String TIKTOK_VIDEO_V3_PATH = "/api/v1/tiktok/app/v3/fetch_one_video_v3";
    private static final String XIAOHONGSHU_VIDEO_NOTE_PATH = "/api/v1/xiaohongshu/app_v2/get_video_note_detail";
    private static final String XIAOHONGSHU_IMAGE_NOTE_PATH = "/api/v1/xiaohongshu/app_v2/get_image_note_detail";
    private static final String XIAOHONGSHU_APP_NOTE_INFO_PATH = "/api/v1/xiaohongshu/app/get_video_note_info";
    private static final String XIAOHONGSHU_WEB_V3_NOTE_DETAIL_PATH = "/api/v1/xiaohongshu/web_v3/fetch_note_detail";
    private static final String XIAOHONGSHU_WEB_V2_FEED_NOTES_PATH = "/api/v1/xiaohongshu/web_v2/fetch_feed_notes_v2";
    private static final String XIAOHONGSHU_WEB_NOTE_INFO_V7_PATH = "/api/v1/xiaohongshu/web/get_note_info_v7";
    private static final String XIAOHONGSHU_NOTE_ID_XSEC_PATH = "/api/v1/xiaohongshu/web/get_note_id_and_xsec_token";
    private static final String INSTAGRAM_POST_BY_URL_PATH = "/api/v1/instagram/v1/fetch_post_by_url";
    private static final String INSTAGRAM_POST_BY_URL_V2_PATH = "/api/v1/instagram/v1/fetch_post_by_url_v2";
    private static final String THREADS_POST_BY_URL_PATH = "/api/v1/threads/web/fetch_post_detail_v2";
    private static final String KUAISHOU_WEB_VIDEO_PATH = "/api/v1/kuaishou/web/fetch_one_video";
    private static final String KUAISHOU_WEB_VIDEO_V2_PATH = "/api/v1/kuaishou/web/fetch_one_video_v2";
    private static final String KUAISHOU_APP_VIDEO_BY_URL_PATH = "/api/v1/kuaishou/app/fetch_one_video_by_url";
    private static final String KUAISHOU_WEB_VIDEO_BY_URL_PATH = "/api/v1/kuaishou/web/fetch_one_video_by_url";
    private static final String TWITTER_TWEET_DETAIL_PATH = "/api/v1/twitter/web/fetch_tweet_detail";
    private static final String YOUTUBE_VIDEO_INFO_PATH = "/api/v1/youtube/web_v2/get_video_info";
    private static final String YOUTUBE_VIDEO_STREAMS_PATH = "/api/v1/youtube/web_v2/get_video_streams_v2";
    private static final String WECHAT_CHANNELS_VIDEO_BY_SHARE_URL_PATH = "/api/v1/wechat_channels/fetch_video_by_share_url";
    private static final String BILIBILI_VIDEO_BY_URL_PATH = "/api/v1/bilibili/web/fetch_one_video_v3";
    private static final String BILIBILI_VIDEO_PLAY_URL_PATH = "/api/v1/bilibili/web/fetch_video_playurl";
    private static final String BILIBILI_VIEW_API_URL = "https://api.bilibili.com/x/web-interface/view";
    private static final String BILIBILI_PLAYURL_API_URL = "https://api.bilibili.com/x/player/playurl";
    private static final String WEIBO_WEB_POST_DETAIL_PATH = "/api/v1/weibo/web_v2/fetch_post_detail";
    private static final String WEIBO_APP_STATUS_DETAIL_PATH = "/api/v1/weibo/app/fetch_status_detail";
    private static final String LEMON8_POST_DETAIL_PATH = "/api/v1/lemon8/app/fetch_post_detail";
    private static final String LINKEDIN_POST_BY_SLUG_PATH = "/api/v1/linkedin/web_v2/get_post_detail_by_slug";
    private static final String LINKEDIN_POST_DETAIL_PATH = "/api/v1/linkedin/web_v2/get_post_detail";
    private static final String REDDIT_POST_DETAIL_PATH = "/api/v1/reddit/app/fetch_post_details";
    private static final String VOLCENGINE_SUBMIT_URL = "https://openspeech.bytedance.com/api/v1/auc/submit";
    private static final String VOLCENGINE_QUERY_URL = "https://openspeech.bytedance.com/api/v1/auc/query";
    private static final int VOLCENGINE_SUCCESS_CODE = 1000;
    private static final String VOLCENGINE_AUDIO_FORMAT = "mp4";
    private static final String PREPROCESSED_AUDIO_FORMAT = "mp3";
    private static final String PREPROCESSED_AUDIO_CONTENT_TYPE = "audio/mpeg";
    private static final long DEFAULT_SOURCE_VIDEO_MAX_BYTES = 500L * 1024L * 1024L;
    private static final long DEFAULT_COVER_IMAGE_MAX_BYTES = 15L * 1024L * 1024L;
    private static final long DEFAULT_DOWNLOAD_TIMEOUT_SECONDS = 300L;
    private static final int MAX_DOWNLOAD_REDIRECTS = 5;
    private static final int EMPTY_TRANSCRIPT_CODE = 50215;
    private static final int XIAOHONGSHU_HTML_MAX_BYTES = 4 * 1024 * 1024;
    private static final String EMPTY_TRANSCRIPT_MESSAGE = "视频里没有识别到可转写的口播文案，可以手动输入原文后继续改写";
    private static final String PROVIDER_PARSE_REJECTED_MESSAGE =
            "平台暂未返回可解析的视频数据，请确认视频是公开可访问的视频，并尽量复制分享内容中的完整 http(s) 链接或完整分享文案后重试";
    private static final String DEFAULT_VIDEO_CONTENT_TYPE = "video/mp4";
    private static final String DEFAULT_COVER_CONTENT_TYPE = "image/jpeg";
    private static final String DOWNLOAD_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final Pattern HTTP_URL_PATTERN = Pattern.compile("https?://[A-Za-z0-9._~:/?#\\[\\]@!$&()*+,;=%-]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIKTOK_VIDEO_ID_PATTERN = Pattern.compile("/(?:video|photo)/(\\d{8,})", Pattern.CASE_INSENSITIVE);
    private static final Pattern TWEET_STATUS_PATTERN = Pattern.compile("(?:twitter\\.com|x\\.com)/[^/]+/status(?:es)?/(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BILIBILI_BV_PATTERN = Pattern.compile("(BV[0-9A-Za-z]+)");
    private static final Pattern LONG_NUMBER_PATTERN = Pattern.compile("(\\d{8,})");
    private static final int FACEBOOK_HTML_MAX_BYTES = 4 * 1024 * 1024;
    private static final int KUAISHOU_HTML_MAX_BYTES = 4 * 1024 * 1024;
    private static final long SHARE_PAGE_FETCH_TIMEOUT_SECONDS = 20L;
    private static final long PARSE_RESULT_CACHE_TTL_MILLIS = Duration.ofMinutes(10).toMillis();
    private static final int PARSE_RESULT_CACHE_MAX_SIZE = 512;
    private static final int TIKHUB_MAX_ATTEMPTS = 2;
    private static final long TIKHUB_RETRY_DELAY_MILLIS = 450L;
    private static final int BILIBILI_API_MAX_ATTEMPTS = 3;
    private static final long BILIBILI_API_TIMEOUT_SECONDS = 45L;
    private static final long BILIBILI_API_RETRY_DELAY_MILLIS = 700L;
    private static final int BILIBILI_DOWNLOAD_MAX_ATTEMPTS = 3;
    private static final long BILIBILI_DOWNLOAD_RETRY_DELAY_MILLIS = 800L;
    private static final long ASR_TOS_SIGNED_URL_EXPIRES_SECONDS = 6L * 60L * 60L;
    private static final int BILIBILI_TRANSCRIPT_CANDIDATE_LIMIT = 8;
    private static final String COPY_REWRITE_PROMPT_BASE = "改写以下短视频口播文案：保留核心信息，去除口水话，不虚构内容，只输出改写后的纯文本。";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final HttpClient downloadHttpClient;
    private final UploadProperties uploadProperties;
    private final TosUploadService tosUploadService;
    private final UploadPublicBaseProvider uploadPublicBaseProvider;
    private final String tikhubBaseUrl;
    private final String tikhubApiKey;
    private final String volcengineAppId;
    private final String volcengineToken;
    private final String volcengineCluster;
    private final String arkBaseUrl;
    private final String arkApiKey;
    private final String arkModel;
    private final boolean audioPreprocessEnabled;
    private final String ffmpegPath;
    private final double audioSpeed;
    private final long audioPreprocessTimeoutSeconds;
    private final long sourceVideoMaxBytes;
    private final long downloadTimeoutSeconds;
    private final Map<String, CachedParseResult> parseResultCache = new ConcurrentHashMap<>();

    public WriterServiceImpl(
            ObjectMapper objectMapper,
            UploadProperties uploadProperties,
            TosUploadService tosUploadService,
            UploadPublicBaseProvider uploadPublicBaseProvider,
            @Value("${tikhub.base-url:https://api.tikhub.io}") String tikhubBaseUrl,
            @Value("${tikhub.api-key:${TIKHUB_API_KEY:}}") String tikhubApiKey,
            @Value("${volcengine.asr.app-key:${VOLCENGINE_ASR_APP_KEY:}}") String volcengineAppId,
            @Value("${volcengine.asr.access-key:${VOLCENGINE_ASR_ACCESS_KEY:}}") String volcengineToken,
            @Value("${volcengine.asr.cluster:${VOLCENGINE_ASR_CLUSTER:volc_auc_common}}") String volcengineCluster,
            @Value("${volcengine.ark.base-url:${VOLCENGINE_ARK_BASE_URL:https://ark.cn-beijing.volces.com/api/v3}}") String arkBaseUrl,
            @Value("${volcengine.ark.api-key:${VOLCENGINE_ARK_API_KEY:}}") String arkApiKey,
            @Value("${volcengine.ark.model:${VOLCENGINE_ARK_MODEL:doubao-seed-2-0-mini-260215}}") String arkModel,
            @Value("${writer.audio-preprocess.enabled:true}") boolean audioPreprocessEnabled,
            @Value("${writer.audio-preprocess.ffmpeg-path:ffmpeg}") String ffmpegPath,
            @Value("${writer.audio-preprocess.speed:1.2}") double audioSpeed,
            @Value("${writer.audio-preprocess.timeout-seconds:900}") long audioPreprocessTimeoutSeconds,
            @Value("${writer.audio-preprocess.source-video-max-bytes:524288000}") long sourceVideoMaxBytes,
            @Value("${writer.audio-preprocess.download-timeout-seconds:300}") long downloadTimeoutSeconds
    ) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(60))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.downloadHttpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.uploadProperties = uploadProperties;
        this.tosUploadService = tosUploadService;
        this.uploadPublicBaseProvider = uploadPublicBaseProvider;
        this.tikhubBaseUrl = trimTrailingSlash(tikhubBaseUrl);
        this.tikhubApiKey = tikhubApiKey;
        this.volcengineAppId = volcengineAppId;
        this.volcengineToken = volcengineToken;
        this.volcengineCluster = StringUtils.hasText(volcengineCluster) ? volcengineCluster.trim() : "volc_auc_common";
        this.arkBaseUrl = trimTrailingSlash(arkBaseUrl);
        this.arkApiKey = arkApiKey;
        this.arkModel = StringUtils.hasText(arkModel) ? arkModel.trim() : "doubao-seed-2-0-mini-260215";
        this.audioPreprocessEnabled = audioPreprocessEnabled;
        this.ffmpegPath = StringUtils.hasText(ffmpegPath) ? ffmpegPath.trim() : "ffmpeg";
        this.audioSpeed = audioSpeed > 0 ? audioSpeed : 1.2D;
        this.audioPreprocessTimeoutSeconds = audioPreprocessTimeoutSeconds > 0 ? audioPreprocessTimeoutSeconds : 900L;
        this.sourceVideoMaxBytes = sourceVideoMaxBytes > 0 ? sourceVideoMaxBytes : DEFAULT_SOURCE_VIDEO_MAX_BYTES;
        this.downloadTimeoutSeconds = downloadTimeoutSeconds > 0
                ? downloadTimeoutSeconds
                : DEFAULT_DOWNLOAD_TIMEOUT_SECONDS;
    }

    /**
     * 解析抖音视频成可下载的视频链接
     * @param request
     * @return
     */
    @Override
    public DouyinVideoParseResponse parseDouyinVideo(DouyinVideoParseRequest request) {
        String shareUrl = firstNonBlank(request == null ? null : request.getUrl());
        if (!StringUtils.hasText(shareUrl)) {
            throw new BusinessException(40000, "shareUrl or url is required");
        }
        VideoPlatform platform = resolveRequestedPlatform(request == null ? null : request.getPlatform(), shareUrl);
        Optional<String> extractedShareUrl = extractFirstHttpUrl(shareUrl);
        String mediaUrl = extractedShareUrl.orElse(shareUrl);
        boolean directUploadRequest = isDirectUploadParseRequest(request, mediaUrl);
        log.info("Benchmark parse classified. platform={}, sourceType={}, hasFilePath={}, directUpload={}, mediaHost={}",
                request == null ? null : request.getPlatform(),
                request == null ? null : request.getSourceType(),
                request != null && StringUtils.hasText(request.getFilePath()),
                directUploadRequest,
                safeHost(mediaUrl));
        if (directUploadRequest) {
            parsePublicHttpUri(mediaUrl);
            return directVideoParseResponse(mediaUrl, platform, request == null ? null : request.getTitle());
        }
        String cacheKey = parseCacheKey(platform, shareUrl, extractedShareUrl.orElse(null));
        DouyinVideoParseResponse cached = getCachedParseResult(cacheKey);
        if (cached != null) {
            log.info("Benchmark parse cache hit. platform={}, cacheKey={}", platform, cacheKey);
            return cached;
        }
        if (!StringUtils.hasText(tikhubApiKey)) {
            throw new BusinessException(50001, "TikHub api key is not configured");
        }
        if (platform == VideoPlatform.UNKNOWN && extractedShareUrl.isEmpty()) {
            throw new BusinessException(40000, "未识别到可解析的视频链接，请粘贴包含 http(s) 链接的社媒分享内容后重试");
        }
        if (platform == VideoPlatform.XIAOHONGSHU) {
            return cacheAndReturn(cacheKey, parseXiaohongshuVideoByDocumentedFlow(shareUrl));
        }
        if (platform == VideoPlatform.BILIBILI) {
            return cacheAndReturn(cacheKey, parseBilibiliVideoByDocumentedFlow(shareUrl));
        }

        List<TikHubEndpoint> candidates = buildParseCandidates(platform, shareUrl);
        if (candidates.isEmpty()) {
            throw new BusinessException(40000, "Unsupported video platform: " + platform.displayName());
        }

        RuntimeException lastError = null;
        DouyinVideoParseResponse firstParsedWithoutDownloadUrl = null;
        for (TikHubEndpoint candidate : candidates) {
            try {
                JsonNode response = callTikHub(candidate.path(), candidate.queryName(), candidate.queryValue());
                if (!hasData(response)) {
                    lastError = new BusinessException(50202, "TikHub returned empty video data");
                    continue;
                }
                DouyinVideoParseResponse parsed = toVideoParseResponse(response, candidate.path());
                if (StringUtils.hasText(parsed.getPlayUrl()) && !isUnsupportedVideoCodecUrl(parsed.getPlayUrl())) {
                    return cacheAndReturn(cacheKey, mergeParseMetadata(parsed, firstParsedWithoutDownloadUrl));
                }
                if (!StringUtils.hasText(parsed.getPlayUrl()) && firstParsedWithoutDownloadUrl == null) {
                    firstParsedWithoutDownloadUrl = parsed;
                }
                lastError = StringUtils.hasText(parsed.getPlayUrl())
                        ? new BusinessException(41500, "TikHub returned video data but only found a bvc2/HEVC/AV1 unsupported codec url")
                        : new BusinessException(50202, "TikHub returned video data without downloadable url");
            } catch (RuntimeException exception) {
                lastError = exception;
            }
        }

        if (platform == VideoPlatform.KUAISHOU) {
            DouyinVideoParseResponse webFallback = resolveKuaishouWebPageParseResponse(shareUrl);
            if (webFallback != null && StringUtils.hasText(webFallback.getPlayUrl())) {
                return cacheAndReturn(cacheKey, mergeParseMetadata(webFallback, firstParsedWithoutDownloadUrl));
            }
        }

        if (firstParsedWithoutDownloadUrl != null) {
            return cacheAndReturn(cacheKey, firstParsedWithoutDownloadUrl);
        }
        String reason = lastError == null ? "unsupported platform or empty video data" : lastError.getMessage();
        if (isTikHubBadRequest(lastError) || isProviderParseRejectedMessage(reason)) {
            throw new BusinessException(50200, PROVIDER_PARSE_REJECTED_MESSAGE);
        }
        throw new BusinessException(50201, "TikHub parse failed: " + reason);
    }

    private boolean isDirectUploadParseRequest(DouyinVideoParseRequest request, String mediaUrl) {
        String platform = lower(request == null ? null : request.getPlatform());
        String sourceType = lower(request == null ? null : request.getSourceType());
        boolean uploadPlatform = "upload".equals(platform)
                || "local".equals(platform)
                || "localupload".equals(platform)
                || "direct".equals(platform);
        boolean uploadSource = "upload".equals(sourceType)
                || "local".equals(sourceType)
                || "localupload".equals(sourceType)
                || "direct".equals(sourceType)
                || StringUtils.hasText(request == null ? null : request.getFilePath());
        return uploadSource || uploadPlatform || isStoredUploadUrl(mediaUrl) || isLikelyDirectVideoUrl(mediaUrl);
    }

    private String safeHost(String value) {
        Optional<URI> uri = firstHttpUri(value);
        return uri.map(URI::getHost).orElse("");
    }

    private boolean isTikHubBadRequest(RuntimeException error) {
        return error instanceof BusinessException businessException
                && businessException.getCode() == 50200
                && lower(businessException.getMessage()).contains("http 400");
    }

    private boolean isProviderParseRejectedMessage(String message) {
        String normalized = lower(message);
        return normalized.contains("tikhub parse failed")
                || normalized.contains("tikhub request failed with http 400")
                || normalized.contains("hybrid error")
                || normalized.contains("http 400");
    }

    private boolean isStoredUploadUrl(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String normalized = value.trim();
        if (normalized.startsWith("/upload/") || normalized.startsWith("/uploads/") || isLikelyTosObjectKey(normalized)) {
            return true;
        }
        String tosPublicBaseUrl = tosUploadService.publicBaseUrl();
        if (StringUtils.hasText(tosPublicBaseUrl)
                && lower(normalized).startsWith(lower(tosPublicBaseUrl.trim()).replaceAll("/+$", "") + "/")) {
            return true;
        }
        String publicBaseUrl = uploadPublicBaseProvider.effectivePublicBaseUrl();
        if (StringUtils.hasText(publicBaseUrl)
                && lower(normalized).startsWith(lower(publicBaseUrl.trim()).replaceAll("/+$", "") + "/")) {
            return true;
        }
        if (StringUtils.hasText(tosPublicBaseUrl)) {
            Optional<URI> source = firstHttpUri(normalized);
            Optional<URI> base = firstHttpUri(tosPublicBaseUrl);
            return source.isPresent()
                    && base.isPresent()
                    && lower(source.get().getHost()).equals(lower(base.get().getHost()))
                    && source.get().getPath() != null
                    && source.get().getPath().contains("/upload/");
        }
        return false;
    }

    private String parseCacheKey(VideoPlatform platform, String shareText, String extractedUrl) {
        String source = firstNonBlank(extractedUrl, shareText);
        if (platform == VideoPlatform.XIAOHONGSHU) {
            Optional<String> noteId = extractXiaohongshuNoteId(shareText);
            if (noteId.isPresent()) {
                return platform.name() + ":note:" + noteId.get().toLowerCase(Locale.ROOT);
            }
        }
        if (platform == VideoPlatform.BILIBILI) {
            Optional<String> bvId = extractBilibiliBvId(shareText);
            if (bvId.isPresent()) {
                return platform.name() + ":bv:" + bvId.get().toUpperCase(Locale.ROOT);
            }
        }
        if (platform == VideoPlatform.TIKTOK) {
            Optional<String> awemeId = extractTikTokAwemeId(shareText);
            if (awemeId.isPresent()) {
                return platform.name() + ":aweme:" + awemeId.get();
            }
        }
        if (platform == VideoPlatform.YOUTUBE) {
            Optional<String> videoId = extractYoutubeVideoId(shareText);
            if (videoId.isPresent()) {
                return platform.name() + ":video:" + videoId.get();
            }
        }
        return platform.name() + ":url:" + canonicalizeParseCacheSource(source);
    }

    private String canonicalizeParseCacheSource(String source) {
        String cleaned = cleanExtractedUrl(source);
        Optional<URI> uri = firstHttpUri(cleaned);
        if (uri.isEmpty()) {
            return lower(cleaned);
        }
        URI value = uri.get();
        String scheme = lower(value.getScheme());
        String host = lower(value.getHost());
        String path = StringUtils.hasText(value.getPath()) ? value.getPath() : "";
        String query = value.getRawQuery();
        return firstNonBlank(scheme, "https") + "://" + host + path + (StringUtils.hasText(query) ? "?" + query : "");
    }

    private DouyinVideoParseResponse getCachedParseResult(String cacheKey) {
        if (!StringUtils.hasText(cacheKey)) {
            return null;
        }
        CachedParseResult cached = parseResultCache.get(cacheKey);
        if (cached == null) {
            return null;
        }
        if (cached.expiresAtMillis() <= System.currentTimeMillis()) {
            parseResultCache.remove(cacheKey);
            return null;
        }
        return cached.response();
    }

    private DouyinVideoParseResponse cacheAndReturn(String cacheKey, DouyinVideoParseResponse response) {
        if (StringUtils.hasText(cacheKey) && isCacheableParseResponse(response)) {
            trimParseResultCache();
            parseResultCache.put(
                    cacheKey,
                    new CachedParseResult(System.currentTimeMillis() + PARSE_RESULT_CACHE_TTL_MILLIS, response)
            );
        }
        return response;
    }

    private boolean isCacheableParseResponse(DouyinVideoParseResponse response) {
        return response != null
                && StringUtils.hasText(response.getPlayUrl())
                && !isUnsupportedVideoCodecUrl(response.getPlayUrl());
    }

    private void trimParseResultCache() {
        if (parseResultCache.size() < PARSE_RESULT_CACHE_MAX_SIZE) {
            return;
        }
        long now = System.currentTimeMillis();
        parseResultCache.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
        if (parseResultCache.size() < PARSE_RESULT_CACHE_MAX_SIZE) {
            return;
        }
        int overflow = parseResultCache.size() - PARSE_RESULT_CACHE_MAX_SIZE + 1;
        int removed = 0;
        for (String key : parseResultCache.keySet()) {
            parseResultCache.remove(key);
            removed++;
            if (removed >= overflow) {
                break;
            }
        }
    }

    @Override
    public VideoDownloadResource openShareVideoDownload(DouyinVideoParseRequest request) {
        String shareUrl = firstNonBlank(request == null ? null : request.getUrl());
        if (!StringUtils.hasText(shareUrl)) {
            throw new BusinessException(40000, "url is required");
        }
        String extractedUrl = extractFirstHttpUrl(shareUrl).orElse(shareUrl);
        if (isLikelyDirectVideoUrl(extractedUrl)) {
            if (isUnsupportedVideoCodecUrl(extractedUrl)) {
                throw new BusinessException(41500, "该视频直链是 bvc2/HEVC/AV1 等高压缩编码，当前暂无法提供通用 MP4 下载");
            }
            return openRemoteVideoStream(
                    extractedUrl,
                    extractedUrl,
                    directVideoParseResponse(extractedUrl, detectPlatform(extractedUrl))
            );
        }
        if (detectPlatform(shareUrl) == VideoPlatform.FACEBOOK) {
            Optional<String> facebookVideoUrl = resolveFacebookVideoUrl(shareUrl);
            if (facebookVideoUrl.isPresent()) {
                if (isUnsupportedVideoCodecUrl(facebookVideoUrl.get())) {
                    throw new BusinessException(41500, "Facebook 返回的视频是高压缩编码，当前暂无法提供通用 MP4 下载");
                }
                return openRemoteVideoStream(
                        facebookVideoUrl.get(),
                        shareUrl,
                        directVideoParseResponse(facebookVideoUrl.get(), VideoPlatform.FACEBOOK)
                );
            }
            throw new BusinessException(50231, "Facebook video url could not be resolved; please use a public video page or direct video URL");
        }
        DouyinVideoParseResponse parseResult = parseDouyinVideo(request);
        String playUrl = parseResult == null ? null : trimToNull(parseResult.getPlayUrl());
        if (!StringUtils.hasText(playUrl)) {
            throw new BusinessException(50202, "已解析到视频信息，但没有拿到可下载的视频地址。请确认链接为公开视频，或更换分享链接后重试");
        }
        if (isUnsupportedVideoCodecUrl(playUrl)) {
            throw new BusinessException(41500, "平台返回的视频是 bvc2/HEVC/AV1 等高压缩编码，当前暂无法提供通用 MP4 下载，请换公开视频链接后重试");
        }
        return openRemoteVideoStream(playUrl, shareUrl, parseResult);
    }

    @Override
    public VideoDownloadResource openRemoteCoverImage(String imageUrl) {
        String url = extractFirstHttpUrl(imageUrl).orElse(imageUrl);
        if (!StringUtils.hasText(url)) {
            throw new BusinessException(40000, "cover image url is required");
        }
        if (!isLikelyImageUrl(url)) {
            throw new BusinessException(40000, "Only public image URLs can be proxied");
        }
        VideoPlatform platform = detectPlatform(url);
        try {
            URI currentUri = parsePublicHttpUri(url);
            HttpResponse<InputStream> response = null;
            for (int redirectCount = 0; redirectCount <= MAX_DOWNLOAD_REDIRECTS; redirectCount++) {
                validatePublicHttpUri(currentUri);
                HttpRequest request = buildImageProxyRequest(currentUri, platform)
                        .GET()
                        .build();
                response = downloadHttpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (!isRedirect(response.statusCode())) {
                    break;
                }
                closeQuietly(response.body());
                currentUri = resolveRedirectUri(currentUri, response);
            }
            if (response == null || isRedirect(response.statusCode())) {
                throw new BusinessException(50230, "封面图片跳转次数过多，请稍后重试");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                closeQuietly(response.body());
                throw new BusinessException(50230, "封面图片加载失败，HTTP " + response.statusCode());
            }
            long contentLength = response.headers().firstValueAsLong("content-length").orElse(-1L);
            if (contentLength > DEFAULT_COVER_IMAGE_MAX_BYTES) {
                closeQuietly(response.body());
                throw new BusinessException(41300, "封面图片过大，暂不支持展示");
            }
            String contentType = response.headers()
                    .firstValue(HttpHeaders.CONTENT_TYPE)
                    .filter(StringUtils::hasText)
                    .map(this::normalizeImageContentType)
                    .orElse(DEFAULT_COVER_CONTENT_TYPE);
            return new VideoDownloadResource(
                    buildCoverFileName(currentUri, contentType),
                    contentType,
                    contentLength,
                    DEFAULT_COVER_IMAGE_MAX_BYTES,
                    response.body()
            );
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new BusinessException(50230, "封面图片加载失败：" + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50230, "封面图片加载被中断");
        }
    }

    private DouyinVideoParseResponse directVideoParseResponse(String videoUrl, VideoPlatform platform) {
        return directVideoParseResponse(videoUrl, platform, null);
    }

    private DouyinVideoParseResponse directVideoParseResponse(String videoUrl, VideoPlatform platform, String title) {
        var rawData = objectMapper.createObjectNode();
        rawData.put("source", "direct-video");
        rawData.put("url", videoUrl);
        if (StringUtils.hasText(title)) {
            rawData.put("title", title.trim());
        }
        return new DouyinVideoParseResponse(
                null,
                videoUrl,
                trimToNull(title),
                null,
                null,
                null,
                "direct-" + defaultVideoFileName(platform),
                null,
                rawData
        );
    }

    private DouyinVideoParseResponse mergeParseMetadata(DouyinVideoParseResponse playable,
                                                        DouyinVideoParseResponse metadata) {
        if (playable == null || metadata == null) {
            return playable;
        }
        if (!StringUtils.hasText(playable.getVideoId())) {
            playable.setVideoId(metadata.getVideoId());
        }
        if (!StringUtils.hasText(playable.getTitle())) {
            playable.setTitle(metadata.getTitle());
        }
        if (playable.getAuthor() == null) {
            playable.setAuthor(metadata.getAuthor());
        }
        if (!StringUtils.hasText(playable.getCoverUrl())) {
            playable.setCoverUrl(metadata.getCoverUrl());
        }
        if (playable.getDurationSeconds() == null) {
            playable.setDurationSeconds(metadata.getDurationSeconds());
        }
        if (!StringUtils.hasText(playable.getRequestId())) {
            playable.setRequestId(metadata.getRequestId());
        }
        return playable;
    }

    private DouyinVideoParseResponse parseXiaohongshuVideoByDocumentedFlow(String shareText) {
        String firstUrl = extractFirstHttpUrl(shareText).orElse(null);
        String originalShareText = trimToNull(shareText);
        String shareUrlForFallback = firstNonBlank(firstUrl, originalShareText);
        String noteId = extractXiaohongshuNoteId(shareText).orElse(null);
        String xsecToken = extractXiaohongshuXsecToken(shareText).orElse(null);
        List<String> shareTextCandidates = buildXiaohongshuShareTextCandidates(originalShareText, firstUrl, noteId, xsecToken);
        RuntimeException lastError = null;
        DouyinVideoParseResponse firstParsedWithoutDownloadUrl = null;

        for (String shareTextCandidate : shareTextCandidates) {
            try {
                JsonNode hybridResponse = callTikHub(HYBRID_VIDEO_DATA_PATH, "url", shareTextCandidate);
                if (!hasData(hybridResponse)) {
                    lastError = new BusinessException(50202, "TikHub hybrid returned empty Xiaohongshu note data");
                    continue;
                }
                DouyinVideoParseResponse parsed = toVideoParseResponse(hybridResponse, HYBRID_VIDEO_DATA_PATH);
                if (StringUtils.hasText(parsed.getPlayUrl()) && !isUnsupportedVideoCodecUrl(parsed.getPlayUrl())) {
                    return parsed;
                }
                if (firstParsedWithoutDownloadUrl == null) {
                    firstParsedWithoutDownloadUrl = parsed;
                }
                lastError = StringUtils.hasText(parsed.getPlayUrl())
                        ? new BusinessException(41500, "TikHub hybrid returned Xiaohongshu data but only found an unsupported codec url")
                        : new BusinessException(50202, "TikHub hybrid returned Xiaohongshu note data without downloadable video url");
            } catch (RuntimeException exception) {
                lastError = exception;
                log.debug("Legacy Xiaohongshu hybrid parse failed: {}", exception.getMessage());
            }
        }

        DouyinVideoParseResponse webPageFallback = resolveXiaohongshuWebPageParseResponse(shareText);
        if (webPageFallback != null && StringUtils.hasText(webPageFallback.getPlayUrl())) {
            return mergeParseMetadata(webPageFallback, firstParsedWithoutDownloadUrl);
        }

        if ((!StringUtils.hasText(noteId) || !StringUtils.hasText(xsecToken))
                && !shareTextCandidates.isEmpty()) {
            for (String shareTextCandidate : shareTextCandidates) {
                try {
                    JsonNode shareInfo = callTikHub(XIAOHONGSHU_NOTE_ID_XSEC_PATH, "share_text", shareTextCandidate);
                    JsonNode data = shareInfo.path("data");
                    noteId = firstNonBlank(
                            noteId,
                            textByPaths(data, "/note_id", "/noteId", "/data/note_id", "/data/noteId", "/id")
                    );
                    xsecToken = firstNonBlank(
                            xsecToken,
                            textByPaths(data, "/xsec_token", "/xsecToken", "/xsec_token_in_share_link", "/data/xsec_token", "/data/xsecToken")
                    );
                    if (StringUtils.hasText(noteId) && StringUtils.hasText(xsecToken)) {
                        break;
                    }
                } catch (RuntimeException exception) {
                    log.debug("Xiaohongshu share info extraction skipped for share_text candidate: {}", exception.getMessage());
                }
            }
        }

        List<TikHubRequest> candidates = new ArrayList<>();
        addXiaohongshuDetailRequests(candidates, XIAOHONGSHU_VIDEO_NOTE_PATH, noteId, shareTextCandidates);
        addXiaohongshuDetailRequests(candidates, XIAOHONGSHU_APP_NOTE_INFO_PATH, noteId, shareTextCandidates);
        addTikHubRequest(candidates, XIAOHONGSHU_WEB_V3_NOTE_DETAIL_PATH, paramsOf(
                "note_id", noteId,
                "xsec_token", xsecToken
        ));
        addTikHubRequest(candidates, XIAOHONGSHU_WEB_V2_FEED_NOTES_PATH, paramsOf("note_id", noteId));
        addXiaohongshuDetailRequests(candidates, XIAOHONGSHU_WEB_NOTE_INFO_V7_PATH, noteId, shareTextCandidates);
        addXiaohongshuDetailRequests(candidates, XIAOHONGSHU_IMAGE_NOTE_PATH, noteId, shareTextCandidates);

        for (TikHubRequest candidate : candidates) {
            try {
                JsonNode response = callTikHub(candidate.path(), candidate.queryParams());
                if (!hasData(response)) {
                    lastError = new BusinessException(50202, "TikHub returned empty Xiaohongshu note data");
                    continue;
                }
                DouyinVideoParseResponse parsed = toVideoParseResponse(response, candidate.path());
                if (StringUtils.hasText(parsed.getPlayUrl()) && !isUnsupportedVideoCodecUrl(parsed.getPlayUrl())) {
                    return mergeParseMetadata(parsed, firstParsedWithoutDownloadUrl);
                }
                if (firstParsedWithoutDownloadUrl == null) {
                    firstParsedWithoutDownloadUrl = parsed;
                }
                lastError = StringUtils.hasText(parsed.getPlayUrl())
                        ? new BusinessException(41500, "TikHub returned Xiaohongshu data but only found an unsupported codec url")
                        : new BusinessException(50202, "TikHub returned Xiaohongshu note data without downloadable video url");
            } catch (RuntimeException exception) {
                lastError = exception;
                log.debug("Xiaohongshu parse candidate failed, path={}, params={}, reason={}",
                        candidate.path(),
                        candidate.queryParams().keySet(),
                        exception.getMessage());
            }
        }

        webPageFallback = resolveXiaohongshuWebPageParseResponse(shareText);
        if (webPageFallback != null && StringUtils.hasText(webPageFallback.getPlayUrl())) {
            return mergeParseMetadata(webPageFallback, firstParsedWithoutDownloadUrl);
        }

        if (firstParsedWithoutDownloadUrl != null) {
            return firstParsedWithoutDownloadUrl;
        }
        DouyinVideoParseResponse shareTextFallback = xiaohongshuShareTextFallback(
                shareText,
                shareUrlForFallback,
                noteId,
                xsecToken
        );
        if (shareTextFallback != null) {
            return shareTextFallback;
        }
        String reason = lastError == null ? "unsupported Xiaohongshu note or empty data" : lastError.getMessage();
        if (isProviderParseRejectedMessage(reason)) {
            throw new BusinessException(50200, PROVIDER_PARSE_REJECTED_MESSAGE);
        }
        throw new BusinessException(50201, "Xiaohongshu parse failed: " + reason);
    }

    private List<String> buildXiaohongshuShareTextCandidates(String originalShareText, String firstUrl,
                                                             String noteId, String xsecToken) {
        List<String> candidates = uniqueNonBlank(originalShareText, firstUrl);
        buildXiaohongshuCanonicalUrl("explore", noteId, xsecToken)
                .ifPresent(url -> addUniqueNonBlank(candidates, url));
        buildXiaohongshuCanonicalUrl("discovery/item", noteId, xsecToken)
                .ifPresent(url -> addUniqueNonBlank(candidates, url));
        return candidates;
    }

    private Optional<String> buildXiaohongshuCanonicalUrl(String pathPrefix, String noteId, String xsecToken) {
        if (!StringUtils.hasText(pathPrefix) || !StringUtils.hasText(noteId)) {
            return Optional.empty();
        }
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString("https://www.xiaohongshu.com/" + pathPrefix + "/" + noteId.trim());
        if (StringUtils.hasText(xsecToken)) {
            builder.queryParam("xsec_token", xsecToken.trim());
            builder.queryParam("xsec_source", "pc_share");
        }
        return Optional.of(builder.build().encode().toUriString());
    }

    private DouyinVideoParseResponse xiaohongshuShareTextFallback(String originalText, String shareUrl,
                                                                  String noteId, String xsecToken) {
        String resolvedNoteId = firstNonBlank(noteId, extractXiaohongshuNoteId(originalText).orElse(null));
        String resolvedTitle = extractXiaohongshuTitle(originalText).orElse(null);
        String cleanedShareUrl = firstNonBlank(shareUrl, extractFirstHttpUrl(originalText).orElse(null));
        if (!StringUtils.hasText(resolvedNoteId)
                && !StringUtils.hasText(resolvedTitle)
                && !StringUtils.hasText(cleanedShareUrl)) {
            return null;
        }

        var rawData = objectMapper.createObjectNode();
        rawData.put("source", "share_text_fallback");
        if (StringUtils.hasText(originalText)) {
            rawData.put("shareText", originalText);
        }
        if (StringUtils.hasText(cleanedShareUrl)) {
            rawData.put("shareUrl", cleanedShareUrl);
        }
        if (StringUtils.hasText(resolvedNoteId)) {
            rawData.put("noteId", resolvedNoteId);
        }
        if (StringUtils.hasText(xsecToken)) {
            rawData.put("xsecToken", xsecToken);
        }

        return new DouyinVideoParseResponse(
                resolvedNoteId,
                null,
                resolvedTitle,
                null,
                null,
                null,
                "xiaohongshu-share-text-fallback",
                null,
                rawData
        );
    }

    private DouyinVideoParseResponse parseBilibiliVideoByDocumentedFlow(String shareUrl) {
        String firstUrl = extractFirstHttpUrl(shareUrl).orElse(shareUrl);
        String bvId = extractBilibiliBvId(firstUrl)
                .or(() -> extractBilibiliBvId(shareUrl))
                .orElseThrow(() -> new BusinessException(40000, "未识别到 B 站 BV 号，请粘贴完整 B 站视频链接后重试"));
        String referer = "https://www.bilibili.com/video/" + bvId + "/";

        JsonNode detailResponse;
        try {
            detailResponse = callBilibiliApi(
                    UriComponentsBuilder.fromUriString(BILIBILI_VIEW_API_URL)
                            .queryParam("bvid", bvId)
                            .build(true)
                            .toUri(),
                    referer
            );
        } catch (RuntimeException exception) {
            log.warn("Bilibili detail request failed, fallback to share metadata. bvid={}, reason={}",
                    bvId, exception.getMessage());
            return bilibiliShareMetadataFallback(bvId, shareUrl, exception.getMessage());
        }
        JsonNode data = detailResponse.path("data");
        String cid = firstNonBlank(
                textByPaths(
                        data,
                        "/cid",
                        "/data/cid",
                        "/View/cid",
                        "/videoData/cid",
                        "/item/cid",
                        "/pages/0/cid",
                        "/data/pages/0/cid",
                        "/View/pages/0/cid",
                        "/videoData/pages/0/cid",
                        "/ugc_season/sections/0/episodes/0/cid"
                )
        );

        if (!StringUtils.hasText(cid)) {
            log.warn("Bilibili detail response has no cid, fallback to metadata-only. bvid={}", bvId);
            return bilibiliMetadataResponse(bvId, null, data, "bilibili-web-interface-metadata");
        }

        JsonNode playResponse;
        try {
            playResponse = callBilibiliApi(
                    UriComponentsBuilder.fromUriString(BILIBILI_PLAYURL_API_URL)
                            .queryParam("bvid", bvId)
                            .queryParam("cid", cid)
                            .queryParam("qn", 32)
                            .queryParam("fnval", 0)
                            .queryParam("fnver", 0)
                            .queryParam("fourk", 0)
                            .build(true)
                            .toUri(),
                    referer
            );
        } catch (RuntimeException exception) {
            log.warn("Bilibili playurl request failed, fallback to metadata-only. bvid={}, cid={}, reason={}",
                    bvId, cid, exception.getMessage());
            return bilibiliMetadataResponse(bvId, null, data, "bilibili-web-interface-metadata");
        }
        JsonNode playData = playResponse.path("data");
        String playUrl = firstNonBlank(
                textByPaths(playData, "/durl/0/url", "/data/durl/0/url"),
                firstUrlFromUrlNode(playData.path("durl").path(0).path("backup_url")),
                findPlayUrl(playData)
        );
        if (!StringUtils.hasText(playUrl)) {
            log.warn("Bilibili playurl response has no MP4 url, fallback to metadata-only. bvid={}, cid={}", bvId, cid);
            return bilibiliMetadataResponse(bvId, null, data, "bilibili-web-interface-metadata");
        }

        var rawData = objectMapper.createObjectNode();
        rawData.put("source", "bilibili-web");
        rawData.set("detail", data);
        rawData.set("playurl", playData);

        return bilibiliMetadataResponse(bvId, playUrl, data, "bilibili-web-interface", rawData);
    }

    private DouyinVideoParseResponse bilibiliShareMetadataFallback(String bvId, String shareText, String reason) {
        var rawData = objectMapper.createObjectNode();
        rawData.put("source", "bilibili-share-metadata-fallback");
        rawData.put("bvid", bvId);
        if (StringUtils.hasText(shareText)) {
            rawData.put("shareText", shareText);
        }
        if (StringUtils.hasText(reason)) {
            rawData.put("reason", reason);
        }
        String title = extractBracketTitle(shareText).orElse(bvId);
        return new DouyinVideoParseResponse(
                bvId,
                null,
                title,
                null,
                null,
                null,
                "bilibili-share-metadata-fallback",
                null,
                rawData
        );
    }

    private DouyinVideoParseResponse bilibiliMetadataResponse(String bvId, String playUrl, JsonNode data,
                                                              String sourceEndpoint) {
        var rawData = objectMapper.createObjectNode();
        rawData.put("source", sourceEndpoint);
        rawData.set("detail", data == null || data.isMissingNode() ? objectMapper.createObjectNode() : data);
        return bilibiliMetadataResponse(bvId, playUrl, data, sourceEndpoint, rawData);
    }

    private DouyinVideoParseResponse bilibiliMetadataResponse(String bvId, String playUrl, JsonNode data,
                                                              String sourceEndpoint, JsonNode rawData) {
        return new DouyinVideoParseResponse(
                bvId,
                playUrl,
                firstNonBlank(textByPaths(data, "/title"), bvId),
                new DouyinAuthorInfo(
                        textByPaths(data, "/owner/mid"),
                        textByPaths(data, "/owner/mid"),
                        textByPaths(data, "/owner/name"),
                        textByPaths(data, "/owner/face")
                ),
                firstNonBlank(
                        textByPaths(data, "/pic"),
                        textByPaths(data, "/pages/0/first_frame")
                ),
                normalizeDurationSeconds(textByPaths(data, "/duration")),
                sourceEndpoint,
                null,
                rawData
        );
    }

    private Optional<String> extractBracketTitle(String text) {
        if (!StringUtils.hasText(text)) {
            return Optional.empty();
        }
        Matcher matcher = Pattern.compile("【([^】]{1,120})】").matcher(text);
        if (matcher.find()) {
            return Optional.ofNullable(trimToNull(matcher.group(1)));
        }
        return Optional.empty();
    }

    private JsonNode callBilibiliApi(URI uri, String referer) {
        BusinessException lastException = null;
        for (int attempt = 1; attempt <= BILIBILI_API_MAX_ATTEMPTS; attempt++) {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(BILIBILI_API_TIMEOUT_SECONDS))
                    .header(HttpHeaders.USER_AGENT, DOWNLOAD_USER_AGENT)
                    .header(HttpHeaders.ACCEPT, "application/json, text/plain, */*")
                    .GET();
            if (StringUtils.hasText(referer)) {
                requestBuilder.header(HttpHeaders.REFERER, referer);
            }
            try {
                HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
                int statusCode = response.statusCode();
                if (statusCode < 200 || statusCode >= 300) {
                    BusinessException exception = new BusinessException(50200, "Bilibili api request failed, HTTP " + statusCode);
                    if (shouldRetryBilibiliRequest(statusCode, exception.getMessage(), attempt, BILIBILI_API_MAX_ATTEMPTS)) {
                        lastException = exception;
                        log.warn("Bilibili api request failed, retrying. attempt={}/{} status={} uri={}",
                                attempt, BILIBILI_API_MAX_ATTEMPTS, statusCode, uri);
                        sleepBeforeBilibiliRetry(BILIBILI_API_RETRY_DELAY_MILLIS);
                        continue;
                    }
                    throw exception;
                }
                JsonNode body = objectMapper.readTree(response.body());
                int code = body.path("code").asInt(0);
                if (code != 0) {
                    String message = firstNonBlank(body.path("message").asText(null), body.path("msg").asText(null), "Bilibili api returned failure");
                    throw new BusinessException(50200, message);
                }
                if (body.path("data").isMissingNode() || body.path("data").isNull()) {
                    throw new BusinessException(50202, "Bilibili api returned empty data");
                }
                return body;
            } catch (BusinessException exception) {
                throw exception;
            } catch (IOException exception) {
                BusinessException wrapped = new BusinessException(50200, "Bilibili api request failed: " + exception.getMessage());
                if (shouldRetryBilibiliRequest(null, exception.getMessage(), attempt, BILIBILI_API_MAX_ATTEMPTS)) {
                    lastException = wrapped;
                    log.warn("Bilibili api request failed, retrying. attempt={}/{} reason={} uri={}",
                            attempt, BILIBILI_API_MAX_ATTEMPTS, exception.getMessage(), uri);
                    sleepBeforeBilibiliRetry(BILIBILI_API_RETRY_DELAY_MILLIS);
                    continue;
                }
                throw wrapped;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new BusinessException(50200, "Bilibili api request was interrupted");
            }
        }
        throw lastException == null ? new BusinessException(50200, "Bilibili api request failed") : lastException;
    }
    private Optional<String> resolveFacebookVideoUrl(String shareUrl) {
        String url = extractFirstHttpUrl(shareUrl).orElse(shareUrl);
        if (!StringUtils.hasText(url)) {
            return Optional.empty();
        }
        HttpResponse<InputStream> response = null;
        try {
            URI currentUri = parsePublicHttpUri(url);
            for (int redirectCount = 0; redirectCount <= MAX_DOWNLOAD_REDIRECTS; redirectCount++) {
                validatePublicHttpUri(currentUri);
                HttpRequest request = buildVideoDownloadRequest(currentUri, VideoPlatform.FACEBOOK)
                        .setHeader(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .GET()
                        .build();
                response = downloadHttpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (!isRedirect(response.statusCode())) {
                    break;
                }
                closeQuietly(response.body());
                currentUri = resolveRedirectUri(currentUri, response);
            }
            if (response == null || response.statusCode() < 200 || response.statusCode() >= 300) {
                closeQuietly(response == null ? null : response.body());
                return Optional.empty();
            }
            String html = readUtf8WithLimit(response.body(), FACEBOOK_HTML_MAX_BYTES);
            return findLikelyVideoUrlInText(html);
        } catch (RuntimeException exception) {
            log.warn("Facebook video url resolve failed: {}", exception.getMessage());
            closeQuietly(response == null ? null : response.body());
            return Optional.empty();
        } catch (IOException exception) {
            log.warn("Facebook video url resolve failed: {}", exception.getMessage());
            return Optional.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private DouyinVideoParseResponse resolveKuaishouWebPageParseResponse(String shareUrl) {
        try {
            FetchedHtmlPage page = fetchPublicHtmlPage(shareUrl, VideoPlatform.KUAISHOU, KUAISHOU_HTML_MAX_BYTES);
            String normalizedHtml = normalizeEscapedText(page.html());
            String playUrl = findLikelyVideoUrlInText(normalizedHtml).orElse(null);
            if (!StringUtils.hasText(playUrl)) {
                return null;
            }

            String photoBlock = textWindowAround(normalizedHtml, "VisionVideoDetailPhoto", 30000);
            String authorBlock = textWindowAround(normalizedHtml, "VisionVideoDetailAuthor", 6000);
            String videoId = firstNonBlank(
                    extractKuaishouPhotoId(page.finalUrl()).orElse(null),
                    extractKuaishouPhotoId(normalizedHtml).orElse(null),
                    extractJsonStringField(photoBlock, "id")
            );
            String title = firstNonBlank(
                    extractJsonStringField(photoBlock, "caption"),
                    extractJsonStringField(normalizedHtml, "caption")
            );
            String coverUrl = firstNonBlank(
                    extractJsonStringField(photoBlock, "coverUrl"),
                    extractJsonStringField(normalizedHtml, "coverUrl"),
                    findLikelyImageUrlInText(normalizedHtml).orElse(null)
            );
            String authorId = firstNonBlank(
                    extractJsonStringField(authorBlock, "id"),
                    extractJsonStringField(normalizedHtml, "authorId")
            );
            String authorName = firstNonBlank(
                    extractJsonStringField(authorBlock, "name"),
                    extractJsonStringField(normalizedHtml, "userName")
            );
            String authorAvatar = firstNonBlank(
                    extractJsonStringField(authorBlock, "headerUrl"),
                    extractJsonStringField(normalizedHtml, "headerUrl")
            );
            Long durationSeconds = normalizeDurationSeconds(firstNonBlank(
                    extractJsonNumberField(photoBlock, "duration"),
                    extractJsonNumberField(normalizedHtml, "duration")
            ));

            var rawData = objectMapper.createObjectNode();
            rawData.put("sourceUrl", shareUrl);
            rawData.put("resolvedUrl", page.finalUrl());
            rawData.put("playUrl", playUrl);
            if (StringUtils.hasText(videoId)) {
                rawData.put("videoId", videoId);
            }
            if (StringUtils.hasText(title)) {
                rawData.put("title", title);
            }
            if (StringUtils.hasText(coverUrl)) {
                rawData.put("coverUrl", coverUrl);
            }

            return new DouyinVideoParseResponse(
                    videoId,
                    playUrl,
                    title,
                    StringUtils.hasText(authorId) || StringUtils.hasText(authorName) || StringUtils.hasText(authorAvatar)
                            ? new DouyinAuthorInfo(authorId, null, authorName, authorAvatar)
                            : null,
                    coverUrl,
                    durationSeconds,
                    "kuaishou-web-page",
                    null,
                    rawData
            );
        } catch (RuntimeException exception) {
            log.warn("Kuaishou web page fallback parse failed: {}", exception.getMessage());
            return null;
        }
    }

    private DouyinVideoParseResponse resolveXiaohongshuWebPageParseResponse(String shareText) {
        try {
            String pageUrl = extractFirstHttpUrl(shareText).orElse(shareText);
            if (!StringUtils.hasText(pageUrl)) {
                return null;
            }
            FetchedHtmlPage page = fetchPublicHtmlPage(pageUrl, VideoPlatform.XIAOHONGSHU, XIAOHONGSHU_HTML_MAX_BYTES);
            String normalizedHtml = normalizeEscapedText(page.html());
            String playUrl = firstNonBlank(
                    htmlMetaContent(normalizedHtml, "og:video"),
                    findLikelyVideoUrlInText(normalizedHtml).orElse(null)
            );
            if (!StringUtils.hasText(playUrl) || isUnsupportedVideoCodecUrl(playUrl)) {
                return null;
            }

            String title = cleanXiaohongshuWebTitle(firstNonBlank(
                    htmlMetaContent(normalizedHtml, "og:title"),
                    htmlTitle(normalizedHtml),
                    extractXiaohongshuTitle(shareText).orElse(null)
            ));
            String description = firstNonBlank(
                    htmlMetaContent(normalizedHtml, "description"),
                    htmlMetaContent(normalizedHtml, "og:description")
            );
            String coverUrl = firstNonBlank(
                    htmlMetaContent(normalizedHtml, "og:image"),
                    findLikelyImageUrlInText(normalizedHtml).orElse(null)
            );
            String videoId = firstNonBlank(
                    extractXiaohongshuNoteId(page.finalUrl()).orElse(null),
                    extractXiaohongshuNoteId(shareText).orElse(null)
            );
            Long durationSeconds = normalizeDurationSeconds(htmlMetaContent(normalizedHtml, "og:videotime"));

            var rawData = objectMapper.createObjectNode();
            rawData.put("source", "xiaohongshu_web_page");
            rawData.put("sourceUrl", pageUrl);
            rawData.put("resolvedUrl", page.finalUrl());
            rawData.put("playUrl", playUrl);
            if (StringUtils.hasText(videoId)) {
                rawData.put("noteId", videoId);
            }
            if (StringUtils.hasText(title)) {
                rawData.put("title", title);
            }
            if (StringUtils.hasText(description)) {
                rawData.put("description", description);
            }
            if (StringUtils.hasText(coverUrl)) {
                rawData.put("coverUrl", coverUrl);
            }

            return new DouyinVideoParseResponse(
                    videoId,
                    playUrl,
                    firstNonBlank(title, description),
                    null,
                    coverUrl,
                    durationSeconds,
                    "xiaohongshu-web-page",
                    null,
                    rawData
            );
        } catch (RuntimeException exception) {
            log.warn("Xiaohongshu web page fallback parse failed: {}", exception.getMessage());
            return null;
        }
    }

    private FetchedHtmlPage fetchPublicHtmlPage(String url, VideoPlatform platform, int maxBytes) {
        String normalizedUrl = extractFirstHttpUrl(url).orElse(url);
        if (!StringUtils.hasText(normalizedUrl)) {
            throw new BusinessException(40000, "url is required");
        }
        HttpResponse<InputStream> response = null;
        try {
            URI currentUri = parsePublicHttpUri(normalizedUrl);
            for (int redirectCount = 0; redirectCount <= MAX_DOWNLOAD_REDIRECTS; redirectCount++) {
                validatePublicHttpUri(currentUri);
                HttpRequest request = HttpRequest.newBuilder(currentUri)
                        .timeout(Duration.ofSeconds(Math.min(downloadTimeoutSeconds, SHARE_PAGE_FETCH_TIMEOUT_SECONDS)))
                        .header(HttpHeaders.USER_AGENT, DOWNLOAD_USER_AGENT)
                        .header(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header(HttpHeaders.ACCEPT_ENCODING, "identity")
                        .header(HttpHeaders.REFERER, firstNonBlank(refererForPlatform(platform, currentUri.getHost()), "https://www.kuaishou.com/"))
                        .GET()
                        .build();
                response = downloadHttpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (!isRedirect(response.statusCode())) {
                    break;
                }
                closeQuietly(response.body());
                currentUri = resolveRedirectUri(currentUri, response);
            }
            if (response == null || isRedirect(response.statusCode())) {
                throw new BusinessException(50231, "Share page redirected too many times");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                closeQuietly(response.body());
                throw new BusinessException(50231, "Share page request failed, HTTP " + response.statusCode());
            }
            return new FetchedHtmlPage(currentUri.toString(), readUtf8WithLimit(response.body(), maxBytes));
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new BusinessException(50231, "Share page request failed: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50231, "Share page request was interrupted");
        } finally {
            closeQuietly(response == null ? null : response.body());
        }
    }

    private String readUtf8WithLimit(InputStream inputStream, int maxBytes) throws IOException {
        try (InputStream in = inputStream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    break;
                }
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private Optional<String> findLikelyVideoUrlInText(String text) {
        if (!StringUtils.hasText(text)) {
            return Optional.empty();
        }
        String normalized = normalizeEscapedText(text);
        Matcher matcher = HTTP_URL_PATTERN.matcher(normalized);
        List<String> candidates = new ArrayList<>();
        while (matcher.find()) {
            String candidate = cleanExtractedUrl(matcher.group());
            if (!StringUtils.hasText(candidate) || !isLikelyVideoUrl(candidate)) {
                continue;
            }
            if (!isUnsupportedVideoCodecUrl(candidate)) {
                candidates.add(candidate);
            }
        }
        return candidates.stream()
                .max((left, right) -> Integer.compare(scoreVideoUrl(left), scoreVideoUrl(right)));
    }

    private Optional<String> findLikelyImageUrlInText(String text) {
        if (!StringUtils.hasText(text)) {
            return Optional.empty();
        }
        Matcher matcher = HTTP_URL_PATTERN.matcher(normalizeEscapedText(text));
        while (matcher.find()) {
            String candidate = cleanExtractedUrl(matcher.group());
            if (StringUtils.hasText(candidate) && isLikelyImageUrl(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private String normalizeEscapedText(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text
                .replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("\\u002f", "/")
                .replace("&amp;", "&")
                .replace("\\u0026", "&")
                .replace("\\u003d", "=")
                .replace("\\u003D", "=")
                .replace("\\u003a", ":")
                .replace("\\u003A", ":")
                .replace("\\u0025", "%");
    }

    private String htmlMetaContent(String html, String name) {
        if (!StringUtils.hasText(html) || !StringUtils.hasText(name)) {
            return null;
        }
        String quotedName = Pattern.quote(name);
        Pattern propertyFirst = Pattern.compile(
                "<meta\\b(?=[^>]*(?:property|name)=[\"']" + quotedName + "[\"'])[^>]*\\bcontent=[\"']([^\"']*)[\"'][^>]*>",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = propertyFirst.matcher(html);
        if (matcher.find()) {
            return htmlUnescape(matcher.group(1));
        }
        Pattern contentFirst = Pattern.compile(
                "<meta\\b(?=[^>]*\\bcontent=[\"']([^\"']*)[\"'])[^>]*(?:property|name)=[\"']" + quotedName + "[\"'][^>]*>",
                Pattern.CASE_INSENSITIVE);
        matcher = contentFirst.matcher(html);
        return matcher.find() ? htmlUnescape(matcher.group(1)) : null;
    }

    private String htmlTitle(String html) {
        if (!StringUtils.hasText(html)) {
            return null;
        }
        Matcher matcher = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                .matcher(html);
        return matcher.find() ? htmlUnescape(matcher.group(1)) : null;
    }

    private String htmlUnescape(String value) {
        return trimToNull(HtmlUtils.htmlUnescape(normalizeEscapedText(value)));
    }

    private String cleanXiaohongshuWebTitle(String title) {
        String cleaned = trimToNull(title);
        if (!StringUtils.hasText(cleaned)) {
            return null;
        }
        cleaned = cleaned.replace(" - 小红书", "").replace("| 小红书", "");
        return trimToNull(cleaned);
    }

    private Optional<String> extractKuaishouPhotoId(String text) {
        Optional<String> fromPath = extractPathSegmentAfter(text, "short-video");
        if (fromPath.isPresent()) {
            return fromPath;
        }
        Matcher matcher = Pattern.compile("(?:photoId|shareObjectId)[\\\\\"'=:\\s]+([0-9A-Za-z_-]{6,})")
                .matcher(text == null ? "" : text);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private String textWindowAround(String text, String marker, int windowSize) {
        if (!StringUtils.hasText(text) || !StringUtils.hasText(marker)) {
            return text;
        }
        int index = text.indexOf(marker);
        if (index < 0) {
            return text;
        }
        int start = Math.max(0, index);
        int end = Math.min(text.length(), index + Math.max(windowSize, marker.length()));
        return text.substring(start, end);
    }

    private String extractJsonStringField(String text, String fieldName) {
        if (!StringUtils.hasText(text) || !StringUtils.hasText(fieldName)) {
            return null;
        }
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? decodeJsonString(matcher.group(1)) : null;
    }

    private String extractJsonNumberField(String text, String fieldName) {
        if (!StringUtils.hasText(text) || !StringUtils.hasText(fieldName)) {
            return null;
        }
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String decodeJsonString(String escaped) {
        if (escaped == null) {
            return null;
        }
        try {
            return trimToNull(objectMapper.readValue("\"" + escaped + "\"", String.class));
        } catch (IOException exception) {
            return trimToNull(normalizeEscapedText(escaped));
        }
    }

    /**
     * 提取抖音视频的文字
     */
    @Override
    public WriterVO extractDouyinVideoTranscript(DouyinVideoTranscriptRequest request) {
        String playUrl = request == null ? null : trimToNull(request.getPlayUrl());
        if (!StringUtils.hasText(playUrl)) {
            throw new BusinessException(40000, "playUrl is required");
        }
        return extractTranscriptFromCandidates(uniqueNonBlank(playUrl), null);
    }

    @Override
    public WriterVO extractDouyinVideoTranscript(DouyinVideoParseResponse parseResult) {
        String playUrl = parseResult == null ? null : trimToNull(parseResult.getPlayUrl());
        if (!StringUtils.hasText(playUrl)) {
            throw new BusinessException(40000, "playUrl is required");
        }
        return extractTranscriptFromCandidates(transcriptCandidateUrls(parseResult), parseResult);
    }

    private WriterVO extractTranscriptFromCandidates(List<String> playUrls, DouyinVideoParseResponse parseResult) {
        if (playUrls == null || playUrls.isEmpty()) {
            throw new BusinessException(40000, "playUrl is required");
        }
        if (!StringUtils.hasText(volcengineAppId) || !StringUtils.hasText(volcengineToken)) {
            throw new BusinessException(50001, "Volcengine ASR app-key or access-key is not configured");
        }
        if (!StringUtils.hasText(arkApiKey)) {
            throw new BusinessException(50001, "Volcengine Ark api key is not configured");
        }

        BusinessException lastException = null;
        for (int index = 0; index < playUrls.size(); index++) {
            String playUrl = playUrls.get(index);
            try {
                log.info("开始下载视频并抽取音轨：{} candidate={}/{} host={}",
                        LocalDateTime.now(), index + 1, playUrls.size(), safeHost(playUrl));
                AsrMedia asrMedia = prepareAudioForAsrWithDirectFallback(playUrl, parseResult);
                log.info("音轨抽取完成，提交火山 ASR：{}", LocalDateTime.now());
                String taskId = submitVolcengineAsrTask(asrMedia.url(), asrMedia.format());
                log.info("提交成功，taskId={} {}", taskId, LocalDateTime.now());
                String originalText = queryVolcengineTranscript(taskId);
                log.info("轮询查看识别结果结束 {}", LocalDateTime.now());
                return new WriterVO(originalText, null);
            } catch (BusinessException exception) {
                lastException = exception;
                if (index < playUrls.size() - 1 && shouldTryNextTranscriptCandidate(exception, parseResult)) {
                    log.warn("Transcript candidate failed, trying next. candidate={}/{} host={} reason={}",
                            index + 1, playUrls.size(), safeHost(playUrl), exception.getMessage());
                    continue;
                }
                throw exception;
            }
        }

        throw lastException == null ? new BusinessException(50214, "Video transcript failed") : lastException;
    }

    @Override
    public WriterVO rewriteDouyinVideo(RewriteDTO request) {
        log.info("开始改写文案：" + LocalDateTime.now());
        String translatedText = rewriteCopywriting(request);
        log.info("改写文案完成：" + LocalDateTime.now());
        return new WriterVO(null, translatedText);
    }

    private AsrMedia prepareAudioForAsrWithDirectFallback(String playUrl, DouyinVideoParseResponse parseResult) {
        try {
            return prepareAudioForAsr(playUrl);
        } catch (BusinessException exception) {
            if (shouldSubmitOriginalToAsr(exception, playUrl, parseResult)) {
                log.warn("Audio preprocess failed; submit original media URL to ASR. host={} reason={}",
                        safeHost(playUrl), exception.getMessage());
                return new AsrMedia(playUrl, VOLCENGINE_AUDIO_FORMAT);
            }
            throw exception;
        }
    }

    private boolean shouldSubmitOriginalToAsr(BusinessException exception, String playUrl,
                                              DouyinVideoParseResponse parseResult) {
        if (!isSourceVideoPreprocessFailure(exception) || !StringUtils.hasText(playUrl)) {
            return false;
        }
        if (isStoredUploadUrl(playUrl)) {
            return false;
        }
        return isBilibiliParseResult(parseResult) || detectPlatform(playUrl) == VideoPlatform.BILIBILI;
    }

    private boolean shouldTryNextTranscriptCandidate(BusinessException exception, DouyinVideoParseResponse parseResult) {
        if (exception == null) {
            return false;
        }
        if (exception.getCode() == EMPTY_TRANSCRIPT_CODE) {
            return isBilibiliParseResult(parseResult);
        }
        String message = lower(exception.getMessage());
        return isSourceVideoPreprocessFailure(exception)
                || message.contains("volcengine asr submit failed")
                || message.contains("volcengine asr query failed")
                || message.contains("timed out")
                || message.contains("timeout")
                || message.contains("connection reset")
                || message.contains("connection refused");
    }

    private List<String> transcriptCandidateUrls(DouyinVideoParseResponse parseResult) {
        List<String> candidates = uniqueNonBlank(parseResult == null ? null : parseResult.getPlayUrl());
        if (!isBilibiliParseResult(parseResult)) {
            return candidates;
        }
        JsonNode playData = parseResult.getRawData() == null
                ? null
                : parseResult.getRawData().path("playurl");
        if (playData == null || playData.isMissingNode() || playData.isNull()) {
            return candidates;
        }
        JsonNode durl = playData.path("durl");
        if (durl.isArray()) {
            for (JsonNode item : durl) {
                addTranscriptCandidate(candidates, item.path("url").asText(null));
                addTranscriptCandidateUrls(candidates, item.path("backup_url"));
            }
        }
        JsonNode videos = playData.path("dash").path("video");
        if (videos.isArray()) {
            for (JsonNode item : videos) {
                addTranscriptCandidate(candidates, firstNonBlank(
                        item.path("baseUrl").asText(null),
                        item.path("base_url").asText(null)
                ));
                addTranscriptCandidateUrls(candidates, item.path("backupUrl"));
                addTranscriptCandidateUrls(candidates, item.path("backup_url"));
            }
        }
        return candidates.size() > BILIBILI_TRANSCRIPT_CANDIDATE_LIMIT
                ? new ArrayList<>(candidates.subList(0, BILIBILI_TRANSCRIPT_CANDIDATE_LIMIT))
                : candidates;
    }

    private void addTranscriptCandidateUrls(List<String> candidates, JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            addTranscriptCandidate(candidates, node.asText(null));
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                addTranscriptCandidateUrls(candidates, item);
            }
        }
    }

    private void addTranscriptCandidate(List<String> candidates, String url) {
        String cleaned = trimToNull(url);
        if (!StringUtils.hasText(cleaned)
                || !isLikelyDirectVideoUrl(cleaned)
                || isUnsupportedVideoCodecUrl(cleaned)) {
            return;
        }
        addUniqueNonBlank(candidates, cleaned);
    }

    private boolean isBilibiliParseResult(DouyinVideoParseResponse parseResult) {
        return parseResult != null
                && StringUtils.hasText(parseResult.getSourceEndpoint())
                && parseResult.getSourceEndpoint().startsWith("bilibili-");
    }

    private AsrMedia prepareAudioForAsr(String playUrl) {
        parsePublicHttpUri(playUrl);
        if (!audioPreprocessEnabled) {
            return new AsrMedia(playUrl, VOLCENGINE_AUDIO_FORMAT);
        }
        String datePath = LocalDate.now().toString();
        String baseName = "writer-asr-" + UUID.randomUUID();
        Path targetDir = Path.of(uploadProperties.localRoot(), "writer", "asr", datePath);
        Path sourceVideoFile = targetDir.resolve(baseName + ".mp4");
        Path targetAudioFile = targetDir.resolve(baseName + ".mp3");
        String audioObjectKey = "writer/asr/" + datePath + "/" + baseName + ".mp3";

        try {
            Files.createDirectories(targetDir);
            downloadSourceVideo(playUrl, sourceVideoFile);
            runFfmpegAudioExtract(sourceVideoFile, targetAudioFile);
            long fileSize = Files.size(targetAudioFile);
            if (fileSize <= 0) {
                throw new BusinessException(50214, "Preprocessed audio is empty");
            }
            try (var in = Files.newInputStream(targetAudioFile)) {
                tosUploadService.putPublicObject(
                        audioObjectKey,
                        in,
                        fileSize,
                        PREPROCESSED_AUDIO_CONTENT_TYPE
                );
            }
            String resultUrl = asrAudioAccessUrl(audioObjectKey);
            log.info("ASR audio preprocess finished, size={} url={}", fileSize, resultUrl);
            return new AsrMedia(resultUrl, PREPROCESSED_AUDIO_FORMAT);
        } catch (BusinessException exception) {
            if (isStoredUploadUrl(playUrl) && isSourceVideoPreprocessFailure(exception)) {
                log.warn("Uploaded video preprocess failed; fallback to original media URL for ASR. reason={}",
                        exception.getMessage());
                return new AsrMedia(playUrl, VOLCENGINE_AUDIO_FORMAT);
            }
            throw exception;
        } catch (IOException exception) {
            throw new BusinessException(50214, "ASR audio preprocess failed: " + exception.getMessage());
        } finally {
            deleteIfExists(sourceVideoFile);
            deleteIfExists(targetAudioFile);
        }
    }

    private boolean isSourceVideoPreprocessFailure(BusinessException exception) {
        if (exception == null) {
            return false;
        }
        String message = lower(exception.getMessage());
        return exception.getCode() == 50214
                || message.contains("source video download failed")
                || message.contains("asr audio preprocess failed")
                || message.contains("preprocessed audio");
    }

    private String asrPublicBaseUrl() {
        String tosPublicBaseUrl = tosUploadService.publicBaseUrl();
        if (StringUtils.hasText(tosPublicBaseUrl)) {
            return tosPublicBaseUrl;
        }
        return uploadPublicBaseProvider.effectivePublicBaseUrl();
    }

    private String asrAudioAccessUrl(String audioObjectKey) {
        try {
            String signedUrl = tosUploadService.createPreSignedGetUrl(audioObjectKey, ASR_TOS_SIGNED_URL_EXPIRES_SECONDS);
            if (StringUtils.hasText(signedUrl)) {
                return signedUrl;
            }
        } catch (BusinessException exception) {
            log.warn("Create ASR audio signed URL failed, fallback to public base url. objectKey={} reason={}",
                    audioObjectKey, exception.getMessage());
        }
        String publicBaseUrl = asrPublicBaseUrl();
        if (!StringUtils.hasText(publicBaseUrl)) {
            throw new BusinessException(50001, "Upload public base url is not configured; cannot publish preprocessed audio for ASR");
        }
        return publicBaseUrl + "/" + audioObjectKey;
    }

    private Optional<String> resolveTosObjectKey(String value) {
        if (!StringUtils.hasText(value)) {
            return Optional.empty();
        }
        String normalized = cleanExtractedUrl(value.trim());
        if (normalized.startsWith("tos:")) {
            return normalizeTosObjectKey(normalized.substring(4));
        }
        if (isLikelyTosObjectKey(normalized)) {
            return normalizeTosObjectKey(normalized);
        }
        Optional<URI> sourceUri = firstHttpUri(normalized);
        if (sourceUri.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> fromTosBase = resolveObjectKeyFromBase(sourceUri.get(), tosUploadService.publicBaseUrl());
        if (fromTosBase.isPresent()) {
            return fromTosBase;
        }
        Optional<String> fromUploadBase = resolveObjectKeyFromBase(sourceUri.get(), uploadPublicBaseProvider.effectivePublicBaseUrl());
        if (fromUploadBase.isPresent()) {
            return fromUploadBase;
        }
        String path = sourceUri.get().getRawPath();
        if (StringUtils.hasText(path)) {
            return normalizeTosObjectKey(decodeUrlComponent(path));
        }
        return Optional.empty();
    }

    private Optional<String> resolveObjectKeyFromBase(URI sourceUri, String publicBaseUrl) {
        if (!StringUtils.hasText(publicBaseUrl)) {
            return Optional.empty();
        }
        Optional<URI> baseUri = firstHttpUri(publicBaseUrl);
        if (baseUri.isEmpty() || !StringUtils.hasText(sourceUri.getHost())) {
            return Optional.empty();
        }
        URI base = baseUri.get();
        if (!lower(sourceUri.getHost()).equals(lower(base.getHost()))) {
            return Optional.empty();
        }
        String sourcePath = firstNonBlank(sourceUri.getRawPath(), "");
        String basePath = firstNonBlank(base.getRawPath(), "");
        while (basePath.endsWith("/")) {
            basePath = basePath.substring(0, basePath.length() - 1);
        }
        String objectPath = sourcePath;
        if (StringUtils.hasText(basePath) && sourcePath.startsWith(basePath + "/")) {
            objectPath = sourcePath.substring(basePath.length());
        }
        return normalizeTosObjectKey(decodeUrlComponent(objectPath));
    }

    private Optional<String> normalizeTosObjectKey(String value) {
        if (!StringUtils.hasText(value)) {
            return Optional.empty();
        }
        String key = value.trim();
        while (key.startsWith("/")) {
            key = key.substring(1);
        }
        if (!isLikelyTosObjectKey(key)) {
            return Optional.empty();
        }
        return Optional.of(key);
    }

    private boolean isLikelyTosObjectKey(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String key = value.trim();
        return !key.contains("..")
                && !key.contains("\\")
                && !key.contains("://")
                && (key.startsWith("upload/")
                || key.startsWith("writer/")
                || key.startsWith("tts/")
                || key.startsWith("voice-sample/")
                || key.startsWith("video/"));
    }

    private String decodeUrlComponent(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return value;
        }
    }

    private void downloadSourceVideo(String sourceUrl, Path targetFile) {
        Optional<String> tosObjectKey = resolveTosObjectKey(sourceUrl);
        if (tosObjectKey.isPresent()) {
            downloadSourceVideoFromTos(tosObjectKey.get(), targetFile);
            return;
        }
        VideoPlatform platform = detectPlatform(sourceUrl);
        int maxAttempts = networkAttemptsForPlatform(platform);
        BusinessException lastException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                downloadSourceVideoOnce(sourceUrl, targetFile, platform);
                return;
            } catch (BusinessException exception) {
                deleteIfExists(targetFile);
                if (shouldRetryNetworkRequest(platform, null, exception.getMessage(), attempt, maxAttempts)) {
                    lastException = exception;
                    log.warn("Source video download failed, retrying. platform={} attempt={}/{} reason={}",
                            platform, attempt, maxAttempts, exception.getMessage());
                    sleepBeforeBilibiliRetry(BILIBILI_DOWNLOAD_RETRY_DELAY_MILLIS);
                    continue;
                }
                throw exception;
            }
        }
        throw lastException == null ? new BusinessException(50214, "Source video download failed") : lastException;
    }

    private void downloadSourceVideoFromTos(String objectKey, Path targetFile) {
        try {
            tosUploadService.getPublicObjectToFile(objectKey, targetFile);
            long fileSize = Files.size(targetFile);
            if (fileSize <= 0) {
                throw new BusinessException(50214, "Downloaded source video is empty");
            }
            if (fileSize > sourceVideoMaxBytes) {
                throw new BusinessException(41300, "Source video is too large");
            }
            log.info("ASR source video downloaded from TOS, objectKey={} size={}", objectKey, fileSize);
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new BusinessException(50214, "Source video download failed: " + exception.getMessage());
        }
    }

    private void downloadSourceVideoOnce(String sourceUrl, Path targetFile, VideoPlatform platform) {
        try {
            URI currentUri = parsePublicHttpUri(sourceUrl);
            HttpResponse<InputStream> response = null;
            for (int redirectCount = 0; redirectCount <= MAX_DOWNLOAD_REDIRECTS; redirectCount++) {
                validatePublicHttpUri(currentUri);
                HttpRequest request = buildVideoDownloadRequest(currentUri, platform)
                        .GET()
                        .build();
                response = downloadHttpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (!isRedirect(response.statusCode())) {
                    break;
                }
                closeQuietly(response.body());
                currentUri = resolveRedirectUri(currentUri, response);
            }
            if (response == null || isRedirect(response.statusCode())) {
                throw new BusinessException(50214, "Source video download redirected too many times");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                int statusCode = response.statusCode();
                closeQuietly(response.body());
                throw new BusinessException(50214, "Source video download failed, HTTP " + statusCode);
            }
            OptionalLong contentLength = response.headers().firstValueAsLong("content-length");
            if (contentLength.isPresent() && contentLength.getAsLong() > sourceVideoMaxBytes) {
                closeQuietly(response.body());
                throw new BusinessException(41300, "Source video is too large");
            }
            long fileSize = copyWithLimit(response.body(), targetFile);
            if (fileSize <= 0) {
                throw new BusinessException(50214, "Downloaded source video is empty");
            }
            log.info("ASR source video downloaded, size={}", fileSize);
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new BusinessException(50214, "Source video download failed: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50214, "Source video download was interrupted");
        }
    }
    private VideoDownloadResource openRemoteVideoStream(String playUrl, String sourceUrl,
                                                        DouyinVideoParseResponse parseResult) {
        VideoPlatform platform = detectPlatform(firstNonBlank(sourceUrl, playUrl));
        List<String> candidates = downloadCandidateUrls(playUrl, parseResult);
        BusinessException lastException = null;
        for (int candidateIndex = 0; candidateIndex < candidates.size(); candidateIndex++) {
            String candidateUrl = candidates.get(candidateIndex);
            int maxAttempts = networkAttemptsForDownloadCandidate(platform, candidates.size());
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    if (candidates.size() > 1) {
                        log.info("Remote video download candidate {}/{} host={}",
                                candidateIndex + 1, candidates.size(), safeHost(candidateUrl));
                    }
                    return openRemoteVideoStreamOnce(candidateUrl, platform, parseResult);
                } catch (BusinessException exception) {
                    lastException = exception;
                    if (shouldRetryNetworkRequest(platform, null, exception.getMessage(), attempt, maxAttempts)) {
                        log.warn("Remote video download failed, retrying. platform={} candidate={}/{} attempt={}/{} host={} reason={}",
                                platform, candidateIndex + 1, candidates.size(), attempt, maxAttempts,
                                safeHost(candidateUrl), exception.getMessage());
                        sleepBeforeBilibiliRetry(BILIBILI_DOWNLOAD_RETRY_DELAY_MILLIS);
                        continue;
                    }
                    if (shouldTryNextDownloadCandidate(platform, exception, candidateIndex, candidates.size())) {
                        log.warn("Remote video download candidate failed, trying next. platform={} candidate={}/{} host={} reason={}",
                                platform, candidateIndex + 1, candidates.size(), safeHost(candidateUrl), exception.getMessage());
                        break;
                    }
                    throw exception;
                }
            }
        }
        throw lastException == null ? new BusinessException(50230, "Video download failed") : lastException;
    }

    private List<String> downloadCandidateUrls(String playUrl, DouyinVideoParseResponse parseResult) {
        List<String> candidates = uniqueNonBlank(playUrl);
        if (isBilibiliParseResult(parseResult)) {
            for (String candidate : transcriptCandidateUrls(parseResult)) {
                addUniqueNonBlank(candidates, candidate);
            }
        }
        return candidates.stream()
                .filter(StringUtils::hasText)
                .filter(this::isLikelyDirectVideoUrl)
                .filter(candidate -> !isUnsupportedVideoCodecUrl(candidate))
                .toList();
    }

    private boolean shouldTryNextDownloadCandidate(VideoPlatform platform, BusinessException exception,
                                                   int candidateIndex, int candidateCount) {
        if (platform != VideoPlatform.BILIBILI || candidateIndex >= candidateCount - 1 || exception == null) {
            return false;
        }
        String message = lower(exception.getMessage());
        Integer statusCode = extractHttpStatus(message).orElse(null);
        return isTransientNetworkMessage(message)
                || (statusCode != null && isRetryableHttpStatus(statusCode));
    }

    private VideoDownloadResource openRemoteVideoStreamOnce(String playUrl, VideoPlatform platform,
                                                            DouyinVideoParseResponse parseResult) {
        try {
            URI currentUri = parsePublicHttpUri(playUrl);
            HttpResponse<InputStream> response = null;
            for (int redirectCount = 0; redirectCount <= MAX_DOWNLOAD_REDIRECTS; redirectCount++) {
                validatePublicHttpUri(currentUri);
                HttpRequest request = buildVideoDownloadRequest(currentUri, platform)
                        .GET()
                        .build();
                response = downloadHttpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (!isRedirect(response.statusCode())) {
                    break;
                }
                closeQuietly(response.body());
                currentUri = resolveRedirectUri(currentUri, response);
            }
            if (response == null || isRedirect(response.statusCode())) {
                throw new BusinessException(50230, "视频下载地址跳转次数过多，请稍后重试");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                int statusCode = response.statusCode();
                closeQuietly(response.body());
                throw new BusinessException(50230, "视频下载失败，HTTP " + statusCode);
            }
            long contentLength = response.headers().firstValueAsLong("content-length").orElse(-1L);
            if (contentLength > sourceVideoMaxBytes) {
                closeQuietly(response.body());
                throw new BusinessException(41300, "视频文件过大，暂不支持下载");
            }
            String contentType = response.headers()
                    .firstValue(HttpHeaders.CONTENT_TYPE)
                    .filter(StringUtils::hasText)
                    .orElse(DEFAULT_VIDEO_CONTENT_TYPE);
            return new VideoDownloadResource(
                    buildDownloadFileName(parseResult, platform),
                    contentType,
                    contentLength,
                    sourceVideoMaxBytes,
                    response.body()
            );
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new BusinessException(50230, "视频下载失败：" + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50230, "视频下载被中断");
        }
    }

    private int networkAttemptsForPlatform(VideoPlatform platform) {
        return platform == VideoPlatform.BILIBILI ? BILIBILI_DOWNLOAD_MAX_ATTEMPTS : 1;
    }

    private int networkAttemptsForDownloadCandidate(VideoPlatform platform, int candidateCount) {
        if (platform == VideoPlatform.BILIBILI && candidateCount > 1) {
            return 1;
        }
        return networkAttemptsForPlatform(platform);
    }

    private boolean shouldRetryBilibiliRequest(Integer statusCode, String message, int attempt, int maxAttempts) {
        if (attempt >= maxAttempts) {
            return false;
        }
        if (statusCode != null) {
            return isRetryableHttpStatus(statusCode);
        }
        return isTransientNetworkMessage(message);
    }

    private boolean shouldRetryNetworkRequest(VideoPlatform platform, Integer statusCode, String message,
                                              int attempt, int maxAttempts) {
        if (platform != VideoPlatform.BILIBILI || attempt >= maxAttempts) {
            return false;
        }
        Integer resolvedStatus = statusCode == null ? extractHttpStatus(message).orElse(null) : statusCode;
        if (resolvedStatus != null) {
            return isRetryableHttpStatus(resolvedStatus);
        }
        return isTransientNetworkMessage(message);
    }

    private boolean isRetryableHttpStatus(int statusCode) {
        return statusCode == 408
                || statusCode == 425
                || statusCode == 429
                || (statusCode >= 500 && statusCode <= 599);
    }

    private boolean isTransientNetworkMessage(String message) {
        String lowerMessage = lower(message);
        return lowerMessage.contains("timed out")
                || lowerMessage.contains("timeout")
                || lowerMessage.contains("connection reset")
                || lowerMessage.contains("connection refused")
                || lowerMessage.contains("connection closed")
                || lowerMessage.contains("connection abort")
                || lowerMessage.contains("unexpected end")
                || lowerMessage.contains("premature eof")
                || lowerMessage.contains("temporarily unavailable");
    }

    private Optional<Integer> extractHttpStatus(String message) {
        if (!StringUtils.hasText(message)) {
            return Optional.empty();
        }
        Matcher matcher = Pattern.compile("HTTP\\s+(\\d{3})", Pattern.CASE_INSENSITIVE).matcher(message);
        if (!matcher.find()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.parseInt(matcher.group(1)));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private void sleepBeforeBilibiliRetry(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50200, "Bilibili retry was interrupted");
        }
    }

    private HttpRequest.Builder buildVideoDownloadRequest(URI uri, VideoPlatform platform) {
        VideoPlatform effectivePlatform = platform == null || platform == VideoPlatform.UNKNOWN
                ? detectPlatform(uri == null ? null : uri.getHost())
                : platform;
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(downloadTimeoutSeconds))
                .header(HttpHeaders.USER_AGENT, DOWNLOAD_USER_AGENT)
                .header(HttpHeaders.ACCEPT, "video/mp4,video/*,*/*")
                .header(HttpHeaders.ACCEPT_ENCODING, "identity");
        String referer = refererForPlatform(effectivePlatform, uri == null ? null : uri.getHost());
        if (StringUtils.hasText(referer)) {
            builder.header(HttpHeaders.REFERER, referer);
        }
        if (effectivePlatform == VideoPlatform.XIAOHONGSHU || isXiaohongshuHost(uri.getHost())) {
            builder.header(HttpHeaders.ORIGIN, "https://www.xiaohongshu.com")
                    .header("Sec-Fetch-Dest", "video")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Site", "cross-site");
        } else if (effectivePlatform == VideoPlatform.TIKTOK
                || effectivePlatform == VideoPlatform.INSTAGRAM
                || effectivePlatform == VideoPlatform.FACEBOOK
                || effectivePlatform == VideoPlatform.THREADS) {
            String origin = originFromReferer(referer);
            if (StringUtils.hasText(origin)) {
                builder.header(HttpHeaders.ORIGIN, origin);
            }
            builder.header("Sec-Fetch-Dest", "video")
                    .header("Sec-Fetch-Mode", "no-cors")
                    .header("Sec-Fetch-Site", "cross-site");
        }
        return builder;
    }

    private HttpRequest.Builder buildImageProxyRequest(URI uri, VideoPlatform platform) {
        VideoPlatform effectivePlatform = platform == null || platform == VideoPlatform.UNKNOWN
                ? detectPlatform(uri == null ? null : uri.getHost())
                : platform;
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(Math.min(downloadTimeoutSeconds, SHARE_PAGE_FETCH_TIMEOUT_SECONDS)))
                .header(HttpHeaders.USER_AGENT, DOWNLOAD_USER_AGENT)
                .header(HttpHeaders.ACCEPT, "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                .header(HttpHeaders.ACCEPT_ENCODING, "identity");
        String referer = refererForPlatform(effectivePlatform, uri == null ? null : uri.getHost());
        if (StringUtils.hasText(referer)) {
            builder.header(HttpHeaders.REFERER, referer);
        }
        if (effectivePlatform == VideoPlatform.XIAOHONGSHU || isXiaohongshuHost(uri.getHost())) {
            builder.header(HttpHeaders.ORIGIN, "https://www.xiaohongshu.com")
                    .header("Sec-Fetch-Dest", "image")
                    .header("Sec-Fetch-Mode", "no-cors")
                    .header("Sec-Fetch-Site", "cross-site");
        } else if (effectivePlatform == VideoPlatform.TIKTOK
                || effectivePlatform == VideoPlatform.INSTAGRAM
                || effectivePlatform == VideoPlatform.FACEBOOK
                || effectivePlatform == VideoPlatform.THREADS) {
            String origin = originFromReferer(referer);
            if (StringUtils.hasText(origin)) {
                builder.header(HttpHeaders.ORIGIN, origin);
            }
            builder.header("Sec-Fetch-Dest", "image")
                    .header("Sec-Fetch-Mode", "no-cors")
                    .header("Sec-Fetch-Site", "cross-site");
        }
        return builder;
    }

    private String normalizeImageContentType(String contentType) {
        String normalized = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("image/")) {
            return DEFAULT_COVER_CONTENT_TYPE;
        }
        if ("image/svg+xml".equals(normalized)) {
            return DEFAULT_COVER_CONTENT_TYPE;
        }
        return normalized;
    }

    private String buildCoverFileName(URI uri, String contentType) {
        String path = uri == null ? null : uri.getPath();
        String fileName = null;
        if (StringUtils.hasText(path)) {
            int slash = path.lastIndexOf('/');
            fileName = slash >= 0 ? path.substring(slash + 1) : path;
        }
        fileName = firstNonBlank(cleanIdentifier(fileName), "cover");
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".jpg")
                && !lowerName.endsWith(".jpeg")
                && !lowerName.endsWith(".png")
                && !lowerName.endsWith(".webp")
                && !lowerName.endsWith(".gif")
                && !lowerName.endsWith(".avif")) {
            fileName += imageExtension(contentType);
        }
        return fileName;
    }

    private String imageExtension(String contentType) {
        String normalized = lower(contentType);
        if (normalized.contains("png")) {
            return ".png";
        }
        if (normalized.contains("webp")) {
            return ".webp";
        }
        if (normalized.contains("gif")) {
            return ".gif";
        }
        if (normalized.contains("avif")) {
            return ".avif";
        }
        return ".jpg";
    }

    private String buildDownloadFileName(DouyinVideoParseResponse parseResult, VideoPlatform platform) {
        String fallback = defaultVideoFileName(platform);
        String title = firstNonBlank(
                parseResult == null ? null : parseResult.getTitle(),
                parseResult == null ? null : parseResult.getVideoId(),
                fallback
        );
        String normalized = title.trim()
                .replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]+", "-")
                .replaceAll("\\s+", " ");
        if (normalized.length() > 80) {
            normalized = normalized.substring(0, 80).trim();
        }
        if (!StringUtils.hasText(normalized)) {
            normalized = fallback;
        }
        if (!normalized.toLowerCase(Locale.ROOT).endsWith(".mp4")) {
            normalized += ".mp4";
        }
        return normalized;
    }

    private String refererForPlatform(VideoPlatform platform, String host) {
        VideoPlatform effectivePlatform = platform == null || platform == VideoPlatform.UNKNOWN ? detectPlatform(host) : platform;
        return switch (effectivePlatform) {
            case DOUYIN -> "https://www.douyin.com/";
            case TIKTOK -> "https://www.tiktok.com/";
            case XIAOHONGSHU -> "https://www.xiaohongshu.com/";
            case INSTAGRAM -> "https://www.instagram.com/";
            case YOUTUBE -> "https://www.youtube.com/";
            case TWITTER -> "https://x.com/";
            case THREADS -> "https://www.threads.net/";
            case KUAISHOU -> "https://www.kuaishou.com/";
            case WECHAT_CHANNELS -> "https://channels.weixin.qq.com/";
            case BILIBILI -> "https://www.bilibili.com/";
            case WEIBO -> "https://weibo.com/";
            case LEMON8 -> "https://www.lemon8-app.com/";
            case LINKEDIN -> "https://www.linkedin.com/";
            case REDDIT -> "https://www.reddit.com/";
            case ZHIHU -> "https://www.zhihu.com/";
            case FACEBOOK -> "https://www.facebook.com/";
            case UNKNOWN -> null;
        };
    }

    private String originFromReferer(String referer) {
        if (!StringUtils.hasText(referer)) {
            return null;
        }
        try {
            URI uri = URI.create(referer);
            if (!StringUtils.hasText(uri.getScheme()) || !StringUtils.hasText(uri.getHost())) {
                return null;
            }
            return uri.getScheme() + "://" + uri.getHost();
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String defaultVideoFileName(VideoPlatform platform) {
        VideoPlatform effectivePlatform = platform == null ? VideoPlatform.UNKNOWN : platform;
        return switch (effectivePlatform) {
            case DOUYIN -> "douyin-video";
            case TIKTOK -> "tiktok-video";
            case XIAOHONGSHU -> "xiaohongshu-video";
            case INSTAGRAM -> "instagram-video";
            case YOUTUBE -> "youtube-video";
            case TWITTER -> "x-video";
            case THREADS -> "threads-video";
            case KUAISHOU -> "kuaishou-video";
            case WECHAT_CHANNELS -> "wechat-channels-video";
            case BILIBILI -> "bilibili-video";
            case WEIBO -> "weibo-video";
            case LEMON8 -> "lemon8-video";
            case LINKEDIN -> "linkedin-video";
            case REDDIT -> "reddit-video";
            case ZHIHU -> "zhihu-video";
            case FACEBOOK -> "facebook-video";
            case UNKNOWN -> "share-video";
        };
    }

    private URI resolveRedirectUri(URI currentUri, HttpResponse<?> response) {
        Optional<String> location = response.headers().firstValue(HttpHeaders.LOCATION);
        if (location.isEmpty() || !StringUtils.hasText(location.get())) {
            throw new BusinessException(50214, "Source video download redirect missing location");
        }
        URI nextUri;
        try {
            nextUri = currentUri.resolve(location.get().trim());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(40000, "Invalid media redirect URL");
        }
        validatePublicHttpUri(nextUri);
        return nextUri;
    }

    private boolean isRedirect(int statusCode) {
        return statusCode == 301
                || statusCode == 302
                || statusCode == 303
                || statusCode == 307
                || statusCode == 308;
    }

    private long copyWithLimit(InputStream inputStream, Path targetFile) throws IOException {
        long total = 0L;
        byte[] buffer = new byte[8192];
        try (InputStream in = inputStream;
             OutputStream out = Files.newOutputStream(
                     targetFile,
                     StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING,
                     StandardOpenOption.WRITE
             )) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (read == 0) {
                    continue;
                }
                total += read;
                if (total > sourceVideoMaxBytes) {
                    throw new BusinessException(41300, "Source video is too large");
                }
                out.write(buffer, 0, read);
            }
        }
        return total;
    }

    private URI parsePublicHttpUri(String value) {
        try {
            URI uri = URI.create(value);
            validatePublicHttpUri(uri);
            return uri;
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(40000, "Invalid media URL");
        }
    }

    private void validatePublicHttpUri(URI uri) {
        if (uri == null || !StringUtils.hasText(uri.getScheme()) || !StringUtils.hasText(uri.getHost())) {
            throw new BusinessException(40000, "Invalid media URL");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new BusinessException(40000, "Only http/https media URLs are supported");
        }
        if (isPrivateHost(uri.getHost())) {
            throw new BusinessException(40000, "Private media URLs are not allowed");
        }
    }

    private boolean isPrivateHost(String host) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()
                        || address.isMulticastAddress()
                        || isUniqueLocalIpv6(address)) {
                    return true;
                }
            }
            return false;
        } catch (UnknownHostException exception) {
            throw new BusinessException(40000, "Media URL host cannot be resolved");
        }
    }

    private boolean isUniqueLocalIpv6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    private void closeQuietly(InputStream inputStream) {
        if (inputStream == null) {
            return;
        }
        try {
            inputStream.close();
        } catch (IOException ignored) {
        }
    }

    private void runFfmpegAudioExtract(Path sourceVideoFile, Path targetAudioFile) {
        List<String> command = List.of(
                ffmpegPath,
                "-y",
                "-hide_banner",
                "-loglevel", "warning",
                "-i", sourceVideoFile.toAbsolutePath().toString(),
                "-vn",
                "-af", "aresample=16000,atempo=" + audioSpeed,
                "-c:a", "libmp3lame",
                "-b:a", "32k",
                "-ac", "1",
                "-ar", "16000",
                targetAudioFile.toAbsolutePath().toString()
        );
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> readProcessOutput(process));
            boolean finished = process.waitFor(audioPreprocessTimeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new BusinessException(50214, "ASR audio extract timed out after " + audioPreprocessTimeoutSeconds + " seconds");
            }
            String output = outputFuture.get(3, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                throw new BusinessException(50214, "ASR audio extract failed: " + abbreviate(output, 1000));
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(50214, "ASR audio extract failed: " + exception.getMessage());
        }
    }

    private record AsrMedia(String url, String format) {
    }

    private String readProcessOutput(Process process) {
        try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() < 2000) {
                    output.append(line).append('\n');
                }
            }
            return output.toString();
        } catch (IOException exception) {
            return exception.getMessage();
        }
    }

    private void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("Failed to delete temp source video {}: {}", path, exception.getMessage());
        }
    }

    private String submitVolcengineAsrTask(String playUrl, String format) {
        String taskId = UUID.randomUUID().toString();
        try {
            Map<String, Object> body = Map.of(
                    "app", Map.of(
                            "appid", volcengineAppId,
                            "token", volcengineToken,
                            "cluster", volcengineCluster
                    ),
                    "user", Map.of(
                            "uid", volcengineAppId
                    ),
                    "audio", Map.of(
                            "url", playUrl,
                            "format", format
                    ),
                    "request", Map.of(
                            "reqid", taskId
                    ),
                    "additions", Map.of(
                            "use_itn", "True",
                            "use_punc", "True",
                            "with_speaker_info", "True"
                    )
            );
            HttpRequest request = HttpRequest.newBuilder(URI.create(VOLCENGINE_SUBMIT_URL))
                    .timeout(Duration.ofSeconds(60))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer; " + volcengineToken)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(50212, "Volcengine ASR submit failed: " + volcengineErrorMessage(response));
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode resp = root.path("resp");
            int code = resp.path("code").asInt(-1);
            if (code != VOLCENGINE_SUCCESS_CODE) {
                throw new BusinessException(50212, "Volcengine ASR submit failed: " + volcengineErrorMessage(response));
            }
            String returnedId = resp.path("id").asText(null);
            return StringUtils.hasText(returnedId) ? returnedId : taskId;
        } catch (IOException exception) {
            throw new BusinessException(50212, "Volcengine ASR submit failed: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50212, "Volcengine ASR submit was interrupted");
        }
    }

    private String queryVolcengineTranscript(String taskId) {
        for (int attempt = 0; attempt < 60; attempt++) {
            try {
                Thread.sleep(attempt == 0 ? 5_000 : 10_000);

                Map<String, Object> body = Map.of(
                        "appid", volcengineAppId,
                        "token", volcengineToken,
                        "cluster", volcengineCluster,
                        "id", taskId
                );
                HttpRequest request = HttpRequest.newBuilder(URI.create(VOLCENGINE_QUERY_URL))
                        .timeout(Duration.ofSeconds(60))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer; " + volcengineToken)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new BusinessException(50213, "Volcengine ASR query failed: " + volcengineErrorMessage(response));
                }
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode resp = root.path("resp");
                int code = resp.path("code").asInt(-1);
                if (code == VOLCENGINE_SUCCESS_CODE) {
                    String text = firstNonBlank(
                            textByPaths(resp, "/text"),
                            textByPaths(resp, "/utterances/0/text")
                    );
                    if (!StringUtils.hasText(text)) {
                        throw new BusinessException(EMPTY_TRANSCRIPT_CODE, EMPTY_TRANSCRIPT_MESSAGE);
                    }
                    return text.trim();
                }
                if (!isVolcenginePending(code)) {
                    throw new BusinessException(50213, "Volcengine ASR query failed: " + volcengineErrorMessage(response));
                }
            } catch (IOException exception) {
                throw new BusinessException(50213, "Volcengine ASR query failed: " + exception.getMessage());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new BusinessException(50213, "Volcengine ASR query was interrupted");
            }
        }
        throw new BusinessException(50213, "Volcengine ASR query timed out");
    }

    private boolean isVolcenginePending(int code) {
        return code == 2000 || code == 2001;
    }

    private String volcengineErrorMessage(HttpResponse<String> response) {
        return "http=" + response.statusCode() + ", body=" + abbreviate(response.body(), 500);
    }

    private String rewriteCopywriting(RewriteDTO request) {
        String originalText = request == null ? null : request.getOriginalText();
        if (!StringUtils.hasText(originalText)) {
            throw new BusinessException(50214, "Original transcript is empty, cannot rewrite copywriting");
        }

        try {
            String prompt = buildRewritePrompt(originalText.trim(), request.getStyle(), request.getIntroduce());
            Map<String, Object> body = Map.of(
                    "model", arkModel,
                    "messages", List.of(Map.of(
                            "role", "user",
                            "content", prompt
                    ))
            );
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(arkBaseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(60))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + arkApiKey)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(50214, "Doubao rewrite failed: " + arkErrorMessage(response));
            }

            String rewrittenText = extractArkMessageContent(response.body());
            if (!StringUtils.hasText(rewrittenText)) {
                throw new BusinessException(50214, "Doubao rewrite returned empty text");
            }
            return rewrittenText.trim();
        } catch (IOException exception) {
            throw new BusinessException(50214, "Doubao rewrite failed: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50214, "Doubao rewrite was interrupted");
        }
    }

    private String buildRewritePrompt(String originalText, String style, String introduce) {
        StringBuilder prompt = new StringBuilder(COPY_REWRITE_PROMPT_BASE);
        if (StringUtils.hasText(style)) {
            prompt.append("\n风格：").append(style.trim());
        }
        if (StringUtils.hasText(introduce)) {
            prompt.append("\n用户期望：").append(introduce.trim());
        }
        prompt.append("\n原文：").append(originalText);
        return prompt.toString();
    }

    private String extractArkMessageContent(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            return firstNonBlank(
                    textByPaths(root, "/choices/0/message/content"),
                    textByPaths(root, "/choices/0/text")
            );
        } catch (IOException exception) {
            throw new BusinessException(50214, "Doubao rewrite returned invalid JSON: " + exception.getMessage());
        }
    }

    private String arkErrorMessage(HttpResponse<String> response) {
        String body = response.body();
        if (!StringUtils.hasText(body)) {
            return "http=" + response.statusCode() + ", empty response body";
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            return "http=" + response.statusCode() + ", message=" + firstNonBlank(
                    textByPaths(root, "/error/message"),
                    textByPaths(root, "/message"),
                    abbreviate(body, 500)
            );
        } catch (IOException exception) {
            return "http=" + response.statusCode() + ", body=" + abbreviate(body, 500);
        }
    }

    private List<TikHubEndpoint> buildParseCandidates(VideoPlatform platform, String shareUrl) {
        List<TikHubEndpoint> candidates = new ArrayList<>();
        String firstUrl = extractFirstHttpUrl(shareUrl).orElse(shareUrl);
        switch (platform) {
            case DOUYIN -> {
                addCandidate(candidates, HYBRID_VIDEO_DATA_PATH, "url", firstUrl);
                addCandidate(candidates, DOUYIN_SHARE_VIDEO_PATH, "share_url", firstUrl);
                addCandidate(candidates, HYBRID_VIDEO_DATA_PATH, "url", shareUrl);
                addCandidate(candidates, DOUYIN_SHARE_VIDEO_PATH, "share_url", shareUrl);
            }
            case TIKTOK -> {
                extractTikTokAwemeId(shareUrl).ifPresent(awemeId -> {
                    addCandidate(candidates, TIKTOK_VIDEO_V3_PATH, "aweme_id", awemeId);
                    addCandidate(candidates, TIKTOK_VIDEO_V2_PATH, "aweme_id", awemeId);
                });
                addCandidate(candidates, TIKTOK_SHARE_VIDEO_V2_PATH, "share_url", firstUrl);
                addCandidate(candidates, TIKTOK_SHARE_VIDEO_PATH, "share_url", firstUrl);
                addCandidate(candidates, HYBRID_VIDEO_DATA_PATH, "url", firstUrl);
                if (extractFirstHttpUrl(shareUrl).isEmpty()) {
                    addCandidate(candidates, TIKTOK_SHARE_VIDEO_V2_PATH, "share_url", shareUrl);
                    addCandidate(candidates, TIKTOK_SHARE_VIDEO_PATH, "share_url", shareUrl);
                    addCandidate(candidates, HYBRID_VIDEO_DATA_PATH, "url", shareUrl);
                }
            }
            case XIAOHONGSHU -> {
                extractXiaohongshuNoteId(shareUrl)
                        .ifPresent(noteId -> addCandidate(candidates, XIAOHONGSHU_VIDEO_NOTE_PATH, "note_id", noteId));
                for (String shareTextCandidate : uniqueNonBlank(shareUrl, firstUrl)) {
                    addCandidate(candidates, XIAOHONGSHU_VIDEO_NOTE_PATH, "share_text", shareTextCandidate);
                }
            }
            case INSTAGRAM -> {
                addCandidate(candidates, INSTAGRAM_POST_BY_URL_V2_PATH, "post_url", firstUrl);
                addCandidate(candidates, INSTAGRAM_POST_BY_URL_PATH, "post_url", firstUrl);
            }
            case THREADS -> addCandidate(candidates, THREADS_POST_BY_URL_PATH, "url", firstUrl);
            case KUAISHOU -> {
                extractKuaishouPhotoId(shareUrl)
                        .ifPresent(photoId -> addCandidate(candidates, KUAISHOU_WEB_VIDEO_V2_PATH, "photo_id", photoId));
                addCandidate(candidates, KUAISHOU_WEB_VIDEO_PATH, "share_text", shareUrl);
                addCandidate(candidates, KUAISHOU_APP_VIDEO_BY_URL_PATH, "share_text", shareUrl);
                addCandidate(candidates, KUAISHOU_WEB_VIDEO_BY_URL_PATH, "url", firstUrl);
            }
            case TWITTER -> extractTweetId(shareUrl)
                    .ifPresent(tweetId -> addCandidate(candidates, TWITTER_TWEET_DETAIL_PATH, "tweet_id", tweetId));
            case YOUTUBE -> {
                addCandidate(candidates, YOUTUBE_VIDEO_INFO_PATH, "video_url", firstUrl);
                extractYoutubeVideoId(shareUrl).ifPresent(videoId -> {
                    addCandidate(candidates, YOUTUBE_VIDEO_INFO_PATH, "video_id", videoId);
                    addCandidate(candidates, YOUTUBE_VIDEO_STREAMS_PATH, "video_id", videoId);
                });
                addCandidate(candidates, YOUTUBE_VIDEO_STREAMS_PATH, "video_url", firstUrl);
            }
            case WECHAT_CHANNELS -> addCandidate(candidates, WECHAT_CHANNELS_VIDEO_BY_SHARE_URL_PATH, "share_url", firstUrl);
            case BILIBILI -> addCandidate(candidates, BILIBILI_VIDEO_BY_URL_PATH, "url", firstUrl);
            case WEIBO -> {
                extractWeiboPostId(shareUrl)
                        .ifPresent(postId -> addCandidate(candidates, WEIBO_WEB_POST_DETAIL_PATH, "id", postId));
                extractWeiboPostId(shareUrl)
                        .ifPresent(postId -> addCandidate(candidates, WEIBO_APP_STATUS_DETAIL_PATH, "status_id", postId));
            }
            case LEMON8 -> extractFirstLongId(shareUrl)
                    .ifPresent(itemId -> addCandidate(candidates, LEMON8_POST_DETAIL_PATH, "item_id", itemId));
            case LINKEDIN -> {
                extractLinkedInSlug(shareUrl)
                        .ifPresent(slug -> addCandidate(candidates, LINKEDIN_POST_BY_SLUG_PATH, "slug", slug));
                extractLinkedInPostUrn(shareUrl)
                        .ifPresent(postUrn -> addCandidate(candidates, LINKEDIN_POST_DETAIL_PATH, "post_urn", postUrn));
            }
            case REDDIT -> extractRedditPostId(shareUrl)
                    .ifPresent(postId -> addCandidate(candidates, REDDIT_POST_DETAIL_PATH, "post_id", postId));
            case UNKNOWN -> {
                addCandidate(candidates, HYBRID_VIDEO_DATA_PATH, "url", shareUrl);
                addCandidate(candidates, DOUYIN_SHARE_VIDEO_PATH, "share_url", shareUrl);
            }
            case FACEBOOK -> {
                addCandidate(candidates, HYBRID_VIDEO_DATA_PATH, "url", firstUrl);
                addCandidate(candidates, HYBRID_VIDEO_DATA_PATH, "url", shareUrl);
            }
            case ZHIHU -> {
                addCandidate(candidates, HYBRID_VIDEO_DATA_PATH, "url", firstUrl);
                addCandidate(candidates, HYBRID_VIDEO_DATA_PATH, "url", shareUrl);
            }
        }
        return candidates;
    }

    private void addCandidate(List<TikHubEndpoint> candidates, String path, String queryName, String queryValue) {
        String value = trimToNull(queryValue);
        if (!StringUtils.hasText(path) || !StringUtils.hasText(queryName) || !StringUtils.hasText(value)) {
            return;
        }
        for (TikHubEndpoint candidate : candidates) {
            if (candidate.path().equals(path)
                    && candidate.queryName().equals(queryName)
                    && candidate.queryValue().equals(value)) {
                return;
            }
        }
        candidates.add(new TikHubEndpoint(path, queryName, value));
    }

    private Map<String, String> paramsOf(String key, String value) {
        Map<String, String> params = new LinkedHashMap<>();
        if (StringUtils.hasText(key) && StringUtils.hasText(value)) {
            params.put(key, value.trim());
        }
        return params;
    }

    private Map<String, String> paramsOf(String key1, String value1, String key2, String value2) {
        Map<String, String> params = new LinkedHashMap<>();
        if (StringUtils.hasText(key1) && StringUtils.hasText(value1)) {
            params.put(key1, value1.trim());
        }
        if (StringUtils.hasText(key2) && StringUtils.hasText(value2)) {
            params.put(key2, value2.trim());
        }
        return params;
    }

    private void addXiaohongshuDetailRequests(List<TikHubRequest> candidates, String path, String noteId,
                                              List<String> shareTextCandidates) {
        if (shareTextCandidates != null) {
            for (String shareTextCandidate : shareTextCandidates) {
                addTikHubRequest(candidates, path, paramsOf("share_text", shareTextCandidate));
            }
        }
        addTikHubRequest(candidates, path, paramsOf("note_id", noteId));
    }

    private void addTikHubRequest(List<TikHubRequest> candidates, String path, Map<String, String> queryParams) {
        if (!StringUtils.hasText(path) || queryParams == null || queryParams.isEmpty()) {
            return;
        }
        Map<String, String> cleaned = new LinkedHashMap<>();
        queryParams.forEach((key, value) -> {
            if (StringUtils.hasText(key) && StringUtils.hasText(value)) {
                cleaned.put(key, value.trim());
            }
        });
        if (cleaned.isEmpty()) {
            return;
        }
        for (TikHubRequest candidate : candidates) {
            if (candidate.path().equals(path) && candidate.queryParams().equals(cleaned)) {
                return;
            }
        }
        candidates.add(new TikHubRequest(path, Map.copyOf(cleaned)));
    }

    private Optional<String> extractTweetId(String text) {
        if (!StringUtils.hasText(text)) {
            return Optional.empty();
        }
        Matcher matcher = TWEET_STATUS_PATTERN.matcher(text);
        if (matcher.find()) {
            return Optional.of(matcher.group(1));
        }
        return extractPathSegmentAfter(text, "status")
                .map(this::digitsOnly)
                .filter(StringUtils::hasText);
    }

    private Optional<String> extractYoutubeVideoId(String text) {
        Optional<URI> uri = firstHttpUri(text);
        if (uri.isEmpty()) {
            return Optional.empty();
        }
        String host = lower(uri.get().getHost());
        List<String> segments = pathSegments(uri.get());
        if (host.endsWith("youtu.be") && !segments.isEmpty()) {
            return Optional.ofNullable(cleanIdentifier(segments.get(0)));
        }
        Optional<String> queryVideoId = queryParam(uri.get(), "v").map(this::cleanIdentifier).filter(StringUtils::hasText);
        if (queryVideoId.isPresent()) {
            return queryVideoId;
        }
        return firstSegmentAfter(segments, "shorts")
                .or(() -> firstSegmentAfter(segments, "embed"))
                .or(() -> firstSegmentAfter(segments, "live"))
                .map(this::cleanIdentifier)
                .filter(StringUtils::hasText);
    }

    private Optional<String> extractBilibiliBvId(String text) {
        if (!StringUtils.hasText(text)) {
            return Optional.empty();
        }
        Matcher matcher = BILIBILI_BV_PATTERN.matcher(text);
        return matcher.find() ? Optional.ofNullable(cleanIdentifier(matcher.group(1))).filter(StringUtils::hasText) : Optional.empty();
    }

    private Optional<String> extractWeiboPostId(String text) {
        Optional<String> statusId = extractPathSegmentAfter(text, "status");
        if (statusId.isPresent()) {
            return statusId.map(this::cleanIdentifier).filter(StringUtils::hasText);
        }
        Optional<URI> uri = firstHttpUri(text);
        if (uri.isEmpty()) {
            return Optional.empty();
        }
        List<String> segments = pathSegments(uri.get());
        if (segments.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(cleanIdentifier(segments.get(segments.size() - 1))).filter(StringUtils::hasText);
    }

    private Optional<String> extractLinkedInSlug(String text) {
        return extractPathSegmentAfter(text, "posts")
                .map(this::cleanIdentifier)
                .filter(StringUtils::hasText);
    }

    private Optional<String> extractLinkedInPostUrn(String text) {
        Optional<String> fromPath = extractPathSegmentAfter(text, "update")
                .map(this::cleanIdentifier)
                .filter(StringUtils::hasText);
        if (fromPath.isPresent()) {
            return fromPath;
        }
        Matcher matcher = Pattern.compile("urn:li:activity:\\d+", Pattern.CASE_INSENSITIVE).matcher(text);
        return matcher.find() ? Optional.of(matcher.group()) : Optional.empty();
    }

    private Optional<String> extractRedditPostId(String text) {
        return extractPathSegmentAfter(text, "comments")
                .map(this::cleanIdentifier)
                .filter(StringUtils::hasText);
    }

    private Optional<String> extractFirstLongId(String text) {
        if (!StringUtils.hasText(text)) {
            return Optional.empty();
        }
        Matcher matcher = LONG_NUMBER_PATTERN.matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private Optional<String> extractTikTokAwemeId(String text) {
        if (!StringUtils.hasText(text)) {
            return Optional.empty();
        }
        Optional<URI> uri = firstHttpUri(text);
        if (uri.isPresent()) {
            Optional<String> fromQuery = firstNonBlankOptional(
                    queryParam(uri.get(), "aweme_id"),
                    queryParam(uri.get(), "item_id")
            ).map(this::digitsOnly).filter(this::isLikelyTikTokAwemeId);
            if (fromQuery.isPresent()) {
                return fromQuery;
            }
            List<String> segments = pathSegments(uri.get());
            Optional<String> fromPath = firstNonBlankOptional(
                    firstSegmentAfter(segments, "video"),
                    firstSegmentAfter(segments, "photo")
            ).map(this::digitsOnly).filter(this::isLikelyTikTokAwemeId);
            if (fromPath.isPresent()) {
                return fromPath;
            }
        }
        String normalizedText = cleanExtractedUrl(text);
        if (StringUtils.hasText(normalizedText)) {
            Matcher pathMatcher = TIKTOK_VIDEO_ID_PATTERN.matcher(normalizedText);
            if (pathMatcher.find()) {
                String id = digitsOnly(pathMatcher.group(1));
                if (isLikelyTikTokAwemeId(id)) {
                    return Optional.of(id);
                }
            }
            Matcher longIdMatcher = LONG_NUMBER_PATTERN.matcher(normalizedText);
            while (longIdMatcher.find()) {
                String id = digitsOnly(longIdMatcher.group(1));
                if (isLikelyTikTokAwemeId(id)) {
                    return Optional.of(id);
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> extractXiaohongshuNoteId(String text) {
        if (!StringUtils.hasText(text)) {
            return Optional.empty();
        }
        Optional<URI> uri = firstHttpUri(text);
        if (uri.isPresent()) {
            Optional<String> fromPath = firstNonBlankOptional(
                    firstSegmentAfter(pathSegments(uri.get()), "item"),
                    firstSegmentAfter(pathSegments(uri.get()), "explore")
            ).map(this::cleanIdentifier).filter(this::isLikelyXiaohongshuNoteId);
            if (fromPath.isPresent()) {
                return fromPath;
            }
        }
        String normalizedText = cleanExtractedUrl(text);
        if (StringUtils.hasText(normalizedText)) {
            Matcher matcher = Pattern.compile("(?:/item/|/explore/)([0-9a-fA-F]{20,32})").matcher(normalizedText);
            if (matcher.find()) {
                return Optional.of(matcher.group(1));
            }
        }
        return Optional.empty();
    }

    private Optional<String> extractXiaohongshuXsecToken(String text) {
        return firstHttpUri(text)
                .flatMap(uri -> queryParam(uri, "xsec_token"))
                .map(this::trimToNull)
                .filter(StringUtils::hasText);
    }

    private Optional<String> extractXiaohongshuTitle(String text) {
        if (!StringUtils.hasText(text)) {
            return Optional.empty();
        }
        Matcher bracketMatcher = Pattern.compile("【([^】]+)】").matcher(text);
        if (bracketMatcher.find()) {
            String title = bracketMatcher.group(1);
            int platformIndex = title.indexOf(" | 小红书");
            if (platformIndex >= 0) {
                title = title.substring(0, platformIndex);
            }
            return Optional.ofNullable(trimToNull(title));
        }
        String beforeUrl = extractFirstHttpUrl(text)
                .map(url -> text.substring(0, Math.max(0, text.indexOf(url))))
                .orElse(text);
        return Optional.ofNullable(trimToNull(beforeUrl));
    }

    @SafeVarargs
    private Optional<String> firstNonBlankOptional(Optional<String>... values) {
        if (values == null) {
            return Optional.empty();
        }
        for (Optional<String> value : values) {
            if (value.isPresent() && StringUtils.hasText(value.get())) {
                return value;
            }
        }
        return Optional.empty();
    }

    private boolean isLikelyTikTokAwemeId(String value) {
        return StringUtils.hasText(value) && value.length() >= 15;
    }

    private boolean isLikelyXiaohongshuNoteId(String value) {
        return StringUtils.hasText(value) && value.matches("[0-9a-fA-F]{20,32}");
    }

    private Optional<String> extractPathSegmentAfter(String text, String marker) {
        Optional<URI> uri = firstHttpUri(text);
        if (uri.isEmpty()) {
            return Optional.empty();
        }
        return firstSegmentAfter(pathSegments(uri.get()), marker);
    }

    private Optional<String> firstSegmentAfter(List<String> segments, String marker) {
        for (int index = 0; index + 1 < segments.size(); index++) {
            if (marker.equalsIgnoreCase(segments.get(index))) {
                return Optional.ofNullable(cleanIdentifier(segments.get(index + 1)));
            }
        }
        return Optional.empty();
    }

    private Optional<URI> firstHttpUri(String text) {
        String url = extractFirstHttpUrl(text).orElse(text);
        if (!StringUtils.hasText(url)) {
            return Optional.empty();
        }
        try {
            return Optional.of(URI.create(url));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private Optional<String> extractFirstHttpUrl(String text) {
        if (!StringUtils.hasText(text)) {
            return Optional.empty();
        }
        Matcher matcher = HTTP_URL_PATTERN.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.ofNullable(cleanExtractedUrl(matcher.group()));
    }

    private List<String> pathSegments(URI uri) {
        String path = uri == null ? null : uri.getPath();
        if (!StringUtils.hasText(path)) {
            return List.of();
        }
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/")) {
            String clean = cleanIdentifier(segment);
            if (StringUtils.hasText(clean)) {
                segments.add(clean);
            }
        }
        return segments;
    }

    private Optional<String> queryParam(URI uri, String name) {
        String query = uri == null ? null : uri.getRawQuery();
        if (!StringUtils.hasText(query)) {
            return Optional.empty();
        }
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && name.equals(decodeQueryComponent(pair[0]))) {
                return Optional.ofNullable(decodeQueryComponent(pair[1]));
            }
        }
        return Optional.empty();
    }

    private String decodeQueryComponent(String value) {
        if (value == null) {
            return null;
        }
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return value;
        }
    }

    private String cleanExtractedUrl(String value) {
        String cleaned = trimToNull(value);
        if (!StringUtils.hasText(cleaned)) {
            return null;
        }
        cleaned = cleaned
                .replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("\\u002f", "/")
                .replace("&amp;", "&")
                .replace("\\u0026", "&")
                .replace("\\u003d", "=")
                .replace("\\u003D", "=")
                .replace("\\u003a", ":")
                .replace("\\u003A", ":")
                .replace("\\u0025", "%");
        while (cleaned.endsWith(".")
                || cleaned.endsWith(",")
                || cleaned.endsWith(";")
                || cleaned.endsWith(")")
                || cleaned.endsWith("]")
                || cleaned.endsWith("}")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned;
    }

    private String cleanIdentifier(String value) {
        String cleaned = trimToNull(value);
        if (!StringUtils.hasText(cleaned)) {
            return null;
        }
        int queryIndex = cleaned.indexOf('?');
        if (queryIndex >= 0) {
            cleaned = cleaned.substring(0, queryIndex);
        }
        int fragmentIndex = cleaned.indexOf('#');
        if (fragmentIndex >= 0) {
            cleaned = cleaned.substring(0, fragmentIndex);
        }
        cleaned = cleaned.replaceAll("[^A-Za-z0-9:_-]", "");
        return trimToNull(cleaned);
    }

    private String digitsOnly(String value) {
        String cleaned = value == null ? null : value.replaceAll("\\D+", "");
        return trimToNull(cleaned);
    }

    private String lower(String value) {
        return StringUtils.hasText(value) ? value.toLowerCase(Locale.ROOT) : "";
    }

    private VideoPlatform resolveRequestedPlatform(String requestedPlatform, String shareText) {
        VideoPlatform explicitPlatform = parsePlatformCode(requestedPlatform);
        if (explicitPlatform != VideoPlatform.UNKNOWN) {
            return explicitPlatform;
        }
        return detectPlatform(shareText);
    }

    private VideoPlatform parsePlatformCode(String value) {
        String normalized = lower(value);
        if (!StringUtils.hasText(normalized) || "auto".equals(normalized)) {
            return VideoPlatform.UNKNOWN;
        }
        normalized = normalized.replace("-", "")
                .replace("_", "")
                .replace(" ", "");
        return switch (normalized) {
            case "douyin", "抖音" -> VideoPlatform.DOUYIN;
            case "xiaohongshu", "xhs", "redbook", "小红书" -> VideoPlatform.XIAOHONGSHU;
            case "tiktok", "tk" -> VideoPlatform.TIKTOK;
            case "kuaishou", "ks", "快手" -> VideoPlatform.KUAISHOU;
            case "bilibili", "bili", "b站", "哔哩哔哩" -> VideoPlatform.BILIBILI;
            case "youtube", "yt" -> VideoPlatform.YOUTUBE;
            case "facebook", "fb" -> VideoPlatform.FACEBOOK;
            case "instagram", "ig" -> VideoPlatform.INSTAGRAM;
            default -> VideoPlatform.UNKNOWN;
        };
    }

    private VideoPlatform detectPlatform(String urlOrText) {
        if (!StringUtils.hasText(urlOrText)) {
            return VideoPlatform.UNKNOWN;
        }
        String normalized = urlOrText.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "douyin.com", "iesdouyin.com", "amemv.com", "douyinvod.com")) {
            return VideoPlatform.DOUYIN;
        }
        if (containsAny(normalized, "tiktok.com", "tiktokv.com", "vm.tiktok.com", "vt.tiktok.com", "musical.ly",
                "byteoversea.com", "muscdn.com", "tiktokcdn", "tiktokcdn-us.com")) {
            return VideoPlatform.TIKTOK;
        }
        if (normalized.contains("xiaohongshu.com")
                || normalized.contains("xhslink.com")
                || normalized.contains("xhscdn.com")
                || normalized.contains("xhs.cn")) {
            return VideoPlatform.XIAOHONGSHU;
        }
        if (containsAny(normalized, "instagram.com", "instagr.am", "cdninstagram.com")) {
            return VideoPlatform.INSTAGRAM;
        }
        if (containsAny(normalized, "threads.net", "threads.com")) {
            return VideoPlatform.THREADS;
        }
        if (containsAny(normalized, "youtube.com", "youtu.be", "googlevideo.com")) {
            return VideoPlatform.YOUTUBE;
        }
        if (containsAny(normalized, "twitter.com", "x.com", "t.co", "twimg.com", "video.twimg.com")) {
            return VideoPlatform.TWITTER;
        }
        if (containsAny(normalized, "kuaishou.com", "kwai.com", "gifshow.com", "kwaicdn.com", "ksapisrv.com")) {
            return VideoPlatform.KUAISHOU;
        }
        if (containsAny(normalized, "channels.weixin.qq.com", "weixin.qq.com/sph", "wechat")) {
            return VideoPlatform.WECHAT_CHANNELS;
        }
        if (containsAny(normalized, "bilibili.com", "b23.tv", "bilivideo.com", "hdslb.com", "biliimg.com")) {
            return VideoPlatform.BILIBILI;
        }
        if (containsAny(normalized, "weibo.com", "m.weibo.cn", "weibo.cn")) {
            return VideoPlatform.WEIBO;
        }
        if (containsAny(normalized, "lemon8", "lemon8-app.com")) {
            return VideoPlatform.LEMON8;
        }
        if (containsAny(normalized, "linkedin.com", "licdn.com")) {
            return VideoPlatform.LINKEDIN;
        }
        if (containsAny(normalized, "reddit.com", "redd.it", "redditmedia.com", "v.redd.it")) {
            return VideoPlatform.REDDIT;
        }
        if (containsAny(normalized, "zhihu.com", "zhimg.com")) {
            return VideoPlatform.ZHIHU;
        }
        if (containsAny(normalized, "facebook.com", "fb.watch", "fbcdn.net", "fb.com")) {
            return VideoPlatform.FACEBOOK;
        }
        return VideoPlatform.UNKNOWN;
    }

    private boolean containsAny(String value, String... needles) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean isXiaohongshuHost(String host) {
        if (!StringUtils.hasText(host)) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.endsWith("xiaohongshu.com")
                || normalized.endsWith("xhslink.com")
                || normalized.endsWith("xhscdn.com")
                || normalized.endsWith("xhs.cn");
    }

    private JsonNode callTikHub(String path, String queryName, String queryValue) {
        return callTikHub(path, Map.of(queryName, queryValue));
    }

    private JsonNode callTikHub(String path, Map<String, String> queryParams) {
        BusinessException lastError = null;
        for (int attempt = 1; attempt <= TIKHUB_MAX_ATTEMPTS; attempt++) {
            try {
                return doCallTikHub(path, queryParams);
            } catch (BusinessException exception) {
                lastError = exception;
                if (attempt >= TIKHUB_MAX_ATTEMPTS || !isRetryableTikHubFailure(exception)) {
                    throw exception;
                }
                log.debug("TikHub transient parse failure, retrying. path={}, attempt={}, reason={}",
                        path, attempt, exception.getMessage());
                sleepBeforeTikHubRetry(attempt);
            }
        }
        throw lastError == null ? new BusinessException(50200, "TikHub request failed") : lastError;
    }

    private JsonNode doCallTikHub(String path, Map<String, String> queryParams) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString(tikhubBaseUrl + path);
        if (queryParams != null) {
            queryParams.forEach((name, value) -> {
                if (StringUtils.hasText(name) && StringUtils.hasText(value)) {
                    uriBuilder.queryParam(name, value);
                }
            });
        }
        URI uri = uriBuilder.build()
                .encode()
                .toUri();
        HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header(HttpHeaders.ACCEPT, "application/json")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tikhubApiKey)
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(50200, "TikHub request failed with HTTP " + response.statusCode()
                        + readableTikHubErrorSuffix(response.body()));
            }

            JsonNode body = objectMapper.readTree(response.body());
            int code = body.path("code").asInt(200);
            if (code != 200) {
                String message = firstNonBlank(body.path("message_zh").asText(null), body.path("message").asText(null), "TikHub request failed");
                throw new BusinessException(50200, message);
            }
            return body;
        } catch (IOException exception) {
            throw new BusinessException(50200, "TikHub request failed: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50200, "TikHub request was interrupted");
        }
    }

    private boolean isRetryableTikHubFailure(BusinessException exception) {
        if (exception == null || exception.getCode() != 50200) {
            return false;
        }
        String message = lower(exception.getMessage());
        return message.contains("http 400")
                || message.contains("http 408")
                || message.contains("http 409")
                || message.contains("http 425")
                || message.contains("http 429")
                || message.contains("http 5")
                || message.contains("timed out")
                || message.contains("request failed");
    }

    private void sleepBeforeTikHubRetry(int attempt) {
        try {
            Thread.sleep(TIKHUB_RETRY_DELAY_MILLIS * Math.max(1, attempt));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50200, "TikHub request was interrupted");
        }
    }

    private String readableTikHubErrorSuffix(String body) {
        if (!StringUtils.hasText(body)) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            String message = firstNonBlank(
                    root.path("message_zh").asText(null),
                    root.path("message").asText(null),
                    textByPaths(root, "/detail/0/msg"),
                    textByPaths(root, "/error/message")
            );
            return StringUtils.hasText(message) ? ": " + abbreviate(message, 300) : "";
        } catch (IOException exception) {
            return ": " + abbreviate(body, 300);
        }
    }

    private DouyinVideoParseResponse toVideoParseResponse(JsonNode tikhubResponse, String sourceEndpoint) {
        JsonNode data = tikhubResponse.path("data");
        JsonNode detail = data.path("aweme_detail").isMissingNode() ? data : data.path("aweme_detail");

        return new DouyinVideoParseResponse(
                firstNonBlank(
                        textByPaths(data, "/video_id", "/videoId", "/aweme_id", "/item_id", "/note_id", "/tweet_id", "/post_id", "/id", "/bvid", "/aid", "/cid", "/photoId", "/photo/id", "/photo/photoId", "/data/video_id", "/data/videoId", "/data/bvid", "/data/aid", "/data/cid", "/videoDetails/videoId"),
                        textByPaths(detail, "/video_id", "/videoId", "/aweme_id", "/item_id", "/note_id", "/tweet_id", "/post_id", "/id", "/bvid", "/aid", "/cid", "/photoId", "/photo/id", "/photo/photoId", "/data/video_id", "/data/videoId", "/data/bvid", "/data/aid", "/data/cid", "/videoDetails/videoId"),
                        textByPaths(data, "/itemInfo/itemStruct/id", "/itemStruct/id")
                ),
                findPlayUrl(data),
                firstNonBlank(
                        textByPaths(data, "/title", "/desc", "/description", "/caption", "/captionText", "/text", "/full_text", "/display_title", "/name", "/data/title", "/item/title", "/photo/caption", "/photo/captionText", "/videoDetails/title", "/note_card/title", "/note_card/desc", "/items/0/note_card/title", "/items/0/note_card/desc", "/itemInfo/itemStruct/desc", "/itemStruct/desc"),
                        textByPaths(detail, "/title", "/desc", "/description", "/caption", "/captionText", "/text", "/full_text", "/display_title", "/name", "/data/title", "/item/title", "/photo/caption", "/photo/captionText", "/videoDetails/title", "/note_card/title", "/note_card/desc", "/items/0/note_card/title", "/items/0/note_card/desc")
                ),
                new DouyinAuthorInfo(
                        firstNonBlank(
                                textByPaths(data, "/author/uid", "/author/user_id", "/author/id", "/user/user_id", "/user/userId", "/user/id", "/owner/id", "/owner/mid", "/user/pk", "/channelId", "/videoDetails/channelId", "/photo/userId", "/note_card/user/user_id", "/items/0/note_card/user/user_id"),
                                textByPaths(detail, "/author/uid", "/author/user_id", "/author/id", "/user/user_id", "/user/userId", "/user/id", "/owner/id", "/owner/mid", "/user/pk", "/channelId", "/videoDetails/channelId", "/photo/userId", "/note_card/user/user_id", "/items/0/note_card/user/user_id"),
                                textByPaths(data, "/itemInfo/itemStruct/author/id", "/itemInfo/itemStruct/author/uid", "/itemStruct/author/id", "/itemStruct/author/uid")
                        ),
                        firstNonBlank(textByPaths(data, "/author/sec_uid", "/author/secUid", "/author/unique_id", "/author/uniqueId", "/user/username", "/owner/mid", "/channelId"), textByPaths(detail, "/author/sec_uid", "/author/secUid", "/author/unique_id", "/author/uniqueId", "/user/username", "/owner/mid", "/channelId")),
                        firstNonBlank(
                                textByPaths(data, "/author/nickname", "/author/name", "/author/unique_id", "/author/uniqueId", "/user/nickname", "/user/name", "/user/username", "/owner/username", "/owner/name", "/channelTitle", "/videoDetails/author", "/videoDetails/channelTitle", "/user_name", "/userName", "/note_card/user/nickname", "/items/0/note_card/user/nickname"),
                                textByPaths(detail, "/author/nickname", "/author/name", "/author/unique_id", "/author/uniqueId", "/user/nickname", "/user/name", "/user/username", "/owner/username", "/owner/name", "/channelTitle", "/videoDetails/author", "/videoDetails/channelTitle", "/user_name", "/userName", "/note_card/user/nickname", "/items/0/note_card/user/nickname"),
                                textByPaths(data, "/itemInfo/itemStruct/author/nickname", "/itemInfo/itemStruct/author/uniqueId", "/itemStruct/author/nickname", "/itemStruct/author/uniqueId")
                        ),
                        firstNonBlank(
                                firstUrlFromObject(data.path("author"), "avatar_thumb"),
                                firstUrlFromObject(detail.path("author"), "avatar_thumb"),
                                firstUrlFromObject(data.path("itemInfo").path("itemStruct").path("author"), "avatarThumb"),
                                firstUrlFromObject(data.path("itemInfo").path("itemStruct").path("author"), "avatarMedium"),
                                firstUrlFromObject(data.path("itemInfo").path("itemStruct").path("author"), "avatarLarger"),
                                textByPaths(data, "/user/avatar", "/user/avatar_url", "/user/profile_pic_url", "/user/image", "/owner/profile_pic_url", "/owner/face", "/author/avatarUrl", "/author/avatar_url", "/channelThumbnail/url"),
                                textByPaths(detail, "/user/avatar", "/user/avatar_url", "/user/profile_pic_url", "/user/image", "/owner/profile_pic_url", "/owner/face", "/author/avatarUrl", "/author/avatar_url", "/channelThumbnail/url")
                        )
                ),
                findCoverUrl(data, detail),
                normalizeDurationSeconds(firstNonBlank(
                        textByPaths(data, "/duration", "/duration_ms", "/video/duration", "/video_info/duration", "/video_info/media/duration", "/video_duration", "/length_seconds", "/lengthSeconds", "/videoDetails/lengthSeconds", "/data/duration", "/item/video/duration", "/photo/duration", "/note_card/video/media/duration", "/items/0/note_card/video/media/duration", "/itemInfo/itemStruct/video/duration", "/itemStruct/video/duration"),
                        textByPaths(detail, "/duration", "/duration_ms", "/video/duration", "/video_info/duration", "/video_info/media/duration", "/video_duration", "/length_seconds", "/lengthSeconds", "/videoDetails/lengthSeconds", "/data/duration", "/item/video/duration", "/photo/duration")
                )),
                sourceEndpoint,
                tikhubResponse.path("request_id").asText(null),
                data
        );
    }

    private String findPlayUrl(JsonNode node) {
        String preferredUrl = findPreferredPlayableVideoUrl(node);
        if (StringUtils.hasText(preferredUrl)) {
            return preferredUrl;
        }

        String directUrl = firstSupportedVideoUrl(
                textByPaths(
                        node,
                        "/original_video_url",
                        "/video_data/original_video_url",
                        "/video_url",
                        "/download_url",
                        "/play_url",
                        "/media_url",
                        "/nwm_video_url",
                        "/video_info/video_url",
                        "/video_info/url",
                        "/video_info/download_url",
                        "/video_info/play_url",
                        "/video/url",
                        "/video/play_url",
                        "/video/download_url",
                        "/media/video_url",
                        "/media/play_url",
                        "/media/download_url",
                        "/media/video_versions/0/url",
                        "/video_versions/0/url",
                        "/items/0/video_versions/0/url",
                        "/photo/mainMvUrls/0/url",
                        "/photo/playUrls/0/url",
                        "/photo/videoResource/h264/adaptationSet/0/representation/0/url",
                        "/photo/manifest/h264AdaptationSet/representation/0/url",
                        "/graphql/shortcode_media/video_url",
                        "/shortcode_media/video_url",
                        "/data/shortcode_media/video_url",
                        "/legacy/extended_entities/media/0/video_info/variants/0/url",
                        "/extended_entities/media/0/video_info/variants/0/url",
                        "/tweet/legacy/extended_entities/media/0/video_info/variants/0/url",
                        "/formats/0/url",
                        "/adaptive_formats/0/url",
                        "/streamingData/formats/0/url",
                        "/streamingData/adaptiveFormats/0/url",
                        "/data/formats/0/url",
                        "/data/adaptive_formats/0/url",
                        "/durl/0/url",
                        "/data/durl/0/url",
                        "/dash/video/0/baseUrl",
                        "/dash/video/0/base_url",
                        "/data/dash/video/0/baseUrl",
                        "/data/dash/video/0/base_url",
                        "/result/durl/0/url",
                        "/result/dash/video/0/baseUrl",
                        "/result/dash/video/0/base_url",
                        "/playurl/durl/0/url",
                        "/playurl/dash/video/0/baseUrl",
                        "/playurl/dash/video/0/base_url",
                        "/hls_manifest_url",
                        "/dash_manifest_url",
                        "/video_info/media/stream/h264/0/master_url",
                        "/video_info/media/stream/h264/0/masterUrl",
                        "/video_info/media/stream/h264/0/backup_urls/0",
                        "/video_info/media/stream/h264/0/backupUrls/0",
                        "/video_info/media/stream/h265/0/master_url",
                        "/video_info/media/stream/h265/0/masterUrl",
                        "/video_info/media/stream/h265/0/backup_urls/0",
                        "/video_info/media/stream/h265/0/backupUrls/0",
                        "/note_card/video/media/stream/h264/0/master_url",
                        "/note_card/video/media/stream/h264/0/masterUrl",
                        "/note_card/video/media/stream/h264/0/backup_urls/0",
                        "/note_card/video/media/stream/h264/0/backupUrls/0",
                        "/note_card/video/media/stream/h265/0/master_url",
                        "/note_card/video/media/stream/h265/0/masterUrl",
                        "/note_card/video/media/stream/h265/0/backup_urls/0",
                        "/note_card/video/media/stream/h265/0/backupUrls/0",
                        "/items/0/note_card/video/media/stream/h264/0/master_url",
                        "/items/0/note_card/video/media/stream/h264/0/masterUrl",
                        "/items/0/note_card/video/media/stream/h264/0/backup_urls/0",
                        "/items/0/note_card/video/media/stream/h264/0/backupUrls/0",
                        "/items/0/note_card/video/media/stream/h265/0/master_url",
                        "/items/0/note_card/video/media/stream/h265/0/masterUrl",
                        "/items/0/note_card/video/media/stream/h265/0/backup_urls/0",
                        "/items/0/note_card/video/media/stream/h265/0/backupUrls/0",
                        "/items/0/video_info/media/stream/h264/0/master_url",
                        "/items/0/video_info/media/stream/h264/0/masterUrl",
                        "/items/0/video_info/media/stream/h264/0/backup_urls/0",
                        "/items/0/video_info/media/stream/h264/0/backupUrls/0",
                        "/items/0/video_info/media/stream/h265/0/master_url",
                        "/items/0/video_info/media/stream/h265/0/masterUrl",
                        "/items/0/video_info/media/stream/h265/0/backup_urls/0",
                        "/items/0/video_info/media/stream/h265/0/backupUrls/0",
                        "/feed/0/note_card/video/media/stream/h264/0/master_url",
                        "/feed/0/note_card/video/media/stream/h264/0/masterUrl",
                        "/feed/0/note_card/video/media/stream/h264/0/backup_urls/0",
                        "/feed/0/note_card/video/media/stream/h264/0/backupUrls/0",
                        "/feed/0/note_card/video/media/stream/h265/0/master_url",
                        "/feed/0/note_card/video/media/stream/h265/0/masterUrl",
                        "/feed/0/note_card/video/media/stream/h265/0/backup_urls/0",
                        "/feed/0/note_card/video/media/stream/h265/0/backupUrls/0",
                        "/video/play_addr/url_list/0",
                        "/video/play_addr_h264/url_list/0",
                        "/video/download_addr/url_list/0",
                        "/video/bit_rate/0/play_addr/url_list/0",
                        "/aweme_detail/video/play_addr/url_list/0",
                        "/aweme_detail/video/play_addr_h264/url_list/0",
                        "/aweme_detail/video/download_addr/url_list/0",
                        "/aweme_detail/video/bit_rate/0/play_addr/url_list/0"
                )
        );
        if (StringUtils.hasText(directUrl)) {
            return directUrl;
        }

        return firstNonBlank(
                firstUrlFromObject(node, "play_addr_h264"),
                firstUrlFromObject(node, "play_addr"),
                firstUrlFromObject(node, "download_addr"),
                firstUrlFromObject(node, "backup_urls"),
                firstUrlFromObject(node, "backupUrls"),
                firstUrlFromObject(node, "master_url"),
                firstUrlFromObject(node, "masterUrl"),
                firstUrlFromObject(node, "video_versions"),
                firstUrlFromObject(node, "variants"),
                firstUrlFromObject(node, "formats"),
                firstUrlFromObject(node, "durl"),
                firstUrlFromObject(node, "dash"),
                firstUrlFromObject(node, "mainMvUrls"),
                firstUrlFromObject(node, "playUrls"),
                firstUrlFromObject(node, "adaptive_formats"),
                firstUrlFromObject(node, "adaptiveFormats"),
                firstUrlFromObject(node, "streamingData"),
                firstUrlFromObject(node, "media"),
                findLikelyVideoUrl(node)
        );
    }

    private String findCoverUrl(JsonNode node, JsonNode... extraNodes) {
        List<JsonNode> nodes = new ArrayList<>();
        if (node != null && !node.isMissingNode() && !node.isNull()) {
            nodes.add(node);
        }
        if (extraNodes != null) {
            for (JsonNode extraNode : extraNodes) {
                if (extraNode != null && !extraNode.isMissingNode() && !extraNode.isNull()
                        && extraNode != node) {
                    nodes.add(extraNode);
                }
            }
        }
        for (JsonNode current : nodes) {
            String cover = findCoverUrlInNode(current);
            if (StringUtils.hasText(cover)) {
                return cover;
            }
        }
        return null;
    }

    private String findCoverUrlInNode(JsonNode node) {
        return firstNonBlank(
                textByPaths(
                        node,
                        "/cover",
                        "/cover_url",
                        "/coverUrl",
                        "/originCover",
                        "/dynamicCover",
                        "/animatedCover",
                        "/pic",
                        "/data/pic",
                        "/poster",
                        "/poster_url",
                        "/image",
                        "/image_url",
                        "/image_list/0/url",
                        "/image_list/0/url_default",
                        "/image_list/0/info_list/0/url",
                        "/images/0/url",
                        "/thumbnail_url",
                        "/thumbnailUrl",
                        "/display_url",
                        "/media/thumbnail_url",
                        "/videoDetails/thumbnail/thumbnails/0/url",
                        "/videoDetails/thumbnail/thumbnails/1/url",
                        "/videoDetails/thumbnail/thumbnails/2/url",
                        "/videoDetails/thumbnail/thumbnails/3/url",
                        "/thumbnail/thumbnails/0/url",
                        "/thumbnail/thumbnails/1/url",
                        "/thumbnail/thumbnails/2/url",
                        "/thumbnail/thumbnails/3/url",
                        "/photo/coverUrl",
                        "/photo/coverUrls/0/url",
                        "/item/video/cover",
                        "/graphql/shortcode_media/display_url",
                        "/shortcode_media/display_url",
                        "/items/0/image_versions2/candidates/0/url",
                        "/items/0/image_list/0/url",
                        "/items/0/image_list/0/url_default",
                        "/items/0/image_list/0/info_list/0/url",
                        "/image_versions2/candidates/0/url",
                        "/note_card/image_list/0/url",
                        "/note_card/image_list/0/url_default",
                        "/items/0/note_card/image_list/0/url",
                        "/items/0/note_card/image_list/0/url_default",
                        "/items/0/note_card/image_list/0/info_list/0/url",
                        "/items/0/note_card/video/cover/url",
                        "/feed/0/note_card/image_list/0/url",
                        "/feed/0/note_card/image_list/0/url_default",
                        "/feed/0/note_card/image_list/0/info_list/0/url",
                        "/itemInfo/itemStruct/video/cover",
                        "/itemInfo/itemStruct/video/originCover",
                        "/itemInfo/itemStruct/video/dynamicCover",
                        "/itemInfo/itemStruct/video/animatedCover",
                        "/itemStruct/video/cover",
                        "/itemStruct/video/originCover",
                        "/itemStruct/video/dynamicCover",
                        "/itemStruct/video/animatedCover",
                        "/video/cover/url_list/0",
                        "/video/origin_cover/url_list/0",
                        "/video/dynamic_cover/url_list/0",
                        "/aweme_detail/video/cover/url_list/0",
                        "/aweme_detail/video/origin_cover/url_list/0",
                        "/aweme_detail/video/dynamic_cover/url_list/0"
                ),
                firstUrlFromObject(node, "cover"),
                firstUrlFromObject(node, "origin_cover"),
                firstUrlFromObject(node, "dynamic_cover"),
                firstUrlFromObject(node, "originCover"),
                firstUrlFromObject(node, "dynamicCover"),
                firstUrlFromObject(node, "animatedCover"),
                firstUrlFromObject(node, "thumbnail"),
                firstUrlFromObject(node, "thumbnails"),
                firstUrlFromObject(node, "thumbnail_url"),
                firstUrlFromObject(node, "thumbnailUrl"),
                firstUrlFromObject(node, "coverUrl"),
                firstUrlFromObject(node, "coverUrls"),
                firstUrlFromObject(node, "pic"),
                firstUrlFromObject(node, "poster"),
                findLikelyImageUrl(node)
        );
    }

    private String firstUrlFromObject(JsonNode node, String fieldName) {
        List<JsonNode> matches = new ArrayList<>();
        collectNamedNodes(node, fieldName, matches);
        for (JsonNode match : matches) {
            String url = firstUrlFromUrlNode(match);
            if (StringUtils.hasText(url) && !isUnsupportedVideoCodecUrl(url)) {
                return url;
            }
        }
        for (JsonNode match : matches) {
            String url = firstUrlFromUrlNode(match);
            if (StringUtils.hasText(url)) {
                return url;
            }
        }
        return null;
    }

    private String firstSupportedVideoUrl(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value) && !isUnsupportedVideoCodecUrl(value)) {
                return value;
            }
        }
        return firstNonBlank(values);
    }

    private String findPreferredPlayableVideoUrl(JsonNode node) {
        List<VideoUrlCandidate> candidates = new ArrayList<>();
        collectVideoUrlCandidates(node, "", 0, candidates);
        return candidates.stream()
                .filter(candidate -> candidate.score() > -300)
                .max((left, right) -> Integer.compare(left.score(), right.score()))
                .map(VideoUrlCandidate::url)
                .orElse(null);
    }

    private void collectVideoUrlCandidates(JsonNode node, String path, int inheritedScore,
                                           List<VideoUrlCandidate> candidates) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            String url = trimToNull(node.asText());
            if (StringUtils.hasText(url)
                    && (url.startsWith("http://") || url.startsWith("https://"))
                    && isLikelyVideoUrl(url)) {
                candidates.add(new VideoUrlCandidate(
                        url,
                        inheritedScore + scoreVideoPath(path) + scoreVideoUrl(url)
                ));
            }
            return;
        }
        if (node.isObject()) {
            int objectScore = inheritedScore + scoreVideoObject(node);
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                collectVideoUrlCandidates(field.getValue(), path + "/" + field.getKey(), objectScore, candidates);
            }
            return;
        }
        if (node.isArray()) {
            int index = 0;
            for (JsonNode child : node) {
                collectVideoUrlCandidates(child, path + "/" + index, inheritedScore, candidates);
                index++;
            }
        }
    }

    private int scoreVideoObject(JsonNode node) {
        int score = 0;
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String key = field.getKey().toLowerCase(Locale.ROOT);
            JsonNode value = field.getValue();
            if ("is_bytevc1".equals(key) && value.asBoolean(false)) {
                score -= 650;
            }
            if (value.isTextual() || value.isNumber() || value.isBoolean()) {
                if (key.contains("codec")
                        || key.contains("format")
                        || key.contains("gear")
                        || key.contains("mime")
                        || key.contains("encode")
                        || key.contains("definition")
                        || key.contains("quality")) {
                    score += scoreVideoDescriptor(value.asText());
                }
            }
        }
        return score;
    }

    private int scoreVideoPath(String path) {
        String normalized = lower(path);
        int score = 0;
        if (normalized.contains("play_addr_h264") || normalized.contains("h264") || normalized.contains("avc")) {
            score += 500;
        }
        if (normalized.contains("download_addr") || normalized.contains("download_url")) {
            score += 120;
        }
        if (normalized.contains("video_versions") || normalized.contains("play_addr")) {
            score += 60;
        }
        if (normalized.contains("h265") || normalized.contains("hevc") || normalized.contains("bytevc")) {
            score -= 700;
        }
        return score;
    }

    private int scoreVideoUrl(String url) {
        String normalized = lower(url);
        int score = 0;
        if (normalized.contains(".mp4") || normalized.contains("video_mp4") || normalized.contains("mime_type=video_mp4")) {
            score += 220;
        }
        if (normalized.contains(".m3u8")) {
            score -= 80;
        }
        if (normalized.contains(".webm")) {
            score -= 120;
        }
        if (isLikelyDirectVideoUrl(normalized)) {
            score += 120;
        }
        score += scoreVideoDescriptor(normalized);
        return score;
    }

    private int scoreVideoDescriptor(String value) {
        String normalized = lower(value);
        int score = 0;
        if (normalized.contains("h264") || normalized.contains("h.264") || normalized.contains("avc1") || normalized.contains("avc")) {
            score += 600;
        }
        if (normalized.contains("bvc2") || normalized.contains("bytevc2")) {
            score -= 1200;
        }
        if (normalized.contains("bytevc1")
                || normalized.contains("h265")
                || normalized.contains("h.265")
                || normalized.contains("hevc")
                || normalized.contains("hev1")
                || normalized.contains("hvc1")) {
            score -= 800;
        }
        if (normalized.contains("av01")
                || normalized.contains("av1")
                || normalized.contains("vp09")
                || normalized.contains("vp9")) {
            score -= 600;
        }
        return score;
    }

    private String findLikelyVideoUrl(JsonNode node) {
        List<String> urls = new ArrayList<>();
        collectTextUrls(node, urls);
        String fallback = null;
        for (String url : urls) {
            if (!isLikelyVideoUrl(url) || isUnsupportedVideoCodecUrl(url)) {
                continue;
            }
            if (isLikelyDirectVideoUrl(url)) {
                return url;
            }
            if (fallback == null) {
                fallback = url;
            }
        }
        return fallback;
    }

    private String findLikelyImageUrl(JsonNode node) {
        List<String> urls = new ArrayList<>();
        collectTextUrls(node, urls);
        for (String url : urls) {
            if (isLikelyImageUrl(url)) {
                return url;
            }
        }
        return null;
    }

    private void collectTextUrls(JsonNode node, List<String> urls) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            String value = trimToNull(node.asText());
            if (StringUtils.hasText(value) && (value.startsWith("http://") || value.startsWith("https://"))) {
                urls.add(value);
            }
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                collectTextUrls(fields.next().getValue(), urls);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collectTextUrls(child, urls);
            }
        }
    }

    private boolean isLikelyVideoUrl(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains(".mp4")
                || normalized.contains(".mov")
                || normalized.contains(".webm")
                || normalized.contains(".m3u8")
                || normalized.contains("douyinvod.com")
                || normalized.contains("byteoversea.com")
                || normalized.contains("muscdn.com")
                || normalized.contains("tiktokcdn")
                || normalized.contains("sns-video")
                || normalized.contains("video.twimg.com")
                || normalized.contains("twimg.com/ext_tw_video")
                || normalized.contains("googlevideo.com")
                || normalized.contains("youtube.com/videoplayback")
                || normalized.contains("fbcdn.net")
                || normalized.contains("cdninstagram.com")
                || normalized.contains("instagram.f")
                || normalized.contains("gifshow.com")
                || normalized.contains("kuaishou")
                || normalized.contains("kwaicdn.com")
                || normalized.contains("ksapisrv.com")
                || normalized.contains("oskwai.com")
                || normalized.contains("yximgs.com")
                || normalized.contains("bilivideo.com")
                || normalized.contains("mcdn.bilivideo")
                || normalized.contains("weibocdn.com")
                || normalized.contains("redditmedia.com")
                || normalized.contains("v.redd.it")
                || normalized.contains("licdn.com/dms")
                || normalized.contains("zhimg.com");
    }

    private boolean isLikelyDirectVideoUrl(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains(".mp4")
                || normalized.contains(".mov")
                || normalized.contains(".webm")
                || normalized.contains("mime=video")
                || normalized.contains("googlevideo.com")
                || normalized.contains("video.twimg.com")
                || normalized.contains("fbcdn.net")
                || normalized.contains("cdninstagram.com")
                || normalized.contains("douyinvod.com")
                || normalized.contains("byteoversea.com")
                || normalized.contains("kwaicdn.com")
                || normalized.contains("ksapisrv.com")
                || normalized.contains("oskwai.com")
                || normalized.contains("yximgs.com")
                || normalized.contains("bilivideo.com")
                || normalized.contains("mcdn.bilivideo")
                || normalized.contains("redditmedia.com")
                || normalized.contains("v.redd.it");
    }

    private boolean isUnsupportedVideoCodecUrl(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("bvc2")
                || normalized.contains("bytevc2")
                || normalized.contains("bytevc1")
                || normalized.contains("h265")
                || normalized.contains("h.265")
                || normalized.contains("hevc")
                || normalized.contains("hev1")
                || normalized.contains("hvc1")
                || normalized.contains("av01")
                || normalized.contains("codec=av1")
                || normalized.contains("mime=video/webm")
                || normalized.contains("mime_type=video_webm");
    }

    private boolean isLikelyImageUrl(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains(".jpg")
                || normalized.contains(".jpeg")
                || normalized.contains(".png")
                || normalized.contains(".webp")
                || normalized.contains("sns-img")
                || normalized.contains("sns-webpic")
                || normalized.contains("sns-avatar")
                || normalized.contains("ci.xiaohongshu.com")
                || normalized.contains("i.ytimg.com")
                || normalized.contains("ytimg.com")
                || normalized.contains("hdslb.com")
                || normalized.contains("biliimg.com")
                || normalized.contains("kwaicdn.com")
                || normalized.contains("ksapisrv.com")
                || normalized.contains("gifshow.com")
                || normalized.contains("yximgs.com")
                || normalized.contains("kwimgs.com")
                || normalized.contains("p16-sign")
                || normalized.contains("p19-sign")
                || normalized.contains("p-px-sign")
                || normalized.contains("tos-maliva-p")
                || (normalized.contains("tos-useast") && normalized.contains("-p-"))
                || (normalized.contains("tiktokcdn")
                && (normalized.contains("image") || normalized.contains("jpeg") || normalized.contains("webp")));
    }

    private void collectNamedNodes(JsonNode node, String fieldName, List<JsonNode> matches) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (fieldName.equals(field.getKey())) {
                    matches.add(field.getValue());
                }
                collectNamedNodes(field.getValue(), fieldName, matches);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collectNamedNodes(child, fieldName, matches);
            }
        }
    }

    private String firstUrlFromUrlNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return trimToNull(node.asText());
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                String url = firstUrlFromUrlNode(item);
                if (StringUtils.hasText(url)) {
                    return url;
                }
            }
            return null;
        }
        if (node.isObject()) {
            return firstNonBlank(
                    firstUrlFromUrlNode(node.path("url_list")),
                    firstUrlFromUrlNode(node.path("play_url")),
                    firstUrlFromUrlNode(node.path("download_url")),
                    firstUrlFromUrlNode(node.path("video_url")),
                    firstUrlFromUrlNode(node.path("media_url")),
                    firstUrlFromUrlNode(node.path("baseUrl")),
                    firstUrlFromUrlNode(node.path("base_url")),
                    firstUrlFromUrlNode(node.path("backupUrl")),
                    firstUrlFromUrlNode(node.path("backupUrls")),
                    firstUrlFromUrlNode(node.path("backup_url")),
                    firstUrlFromUrlNode(node.path("backup_urls")),
                    firstUrlFromUrlNode(node.path("masterUrl")),
                    firstUrlFromUrlNode(node.path("master_url")),
                    firstUrlFromUrlNode(node.path("playUrls")),
                    firstUrlFromUrlNode(node.path("mainMvUrls")),
                    firstUrlFromUrlNode(node.path("srcNoMark")),
                    firstUrlFromUrlNode(node.path("url")),
                    firstUrlFromUrlNode(node.path("src")),
                    firstUrlFromUrlNode(node.path("uri"))
            );
        }
        return null;
    }

    private String textByPaths(JsonNode node, String... paths) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        for (String path : paths) {
            JsonNode value = node.at(path);
            String text = textValue(value);
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return null;
    }

    private String textValue(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isTextual() || value.isNumber() || value.isBoolean()) {
            return trimToNull(value.asText());
        }
        if (value.isArray() && !value.isEmpty()) {
            return textValue(value.get(0));
        }
        return null;
    }

    private Long normalizeDurationSeconds(String duration) {
        if (!StringUtils.hasText(duration)) {
            return null;
        }
        String trimmed = duration.trim();
        if (trimmed.matches("\\d{1,2}:\\d{2}(?::\\d{2})?")) {
            String[] parts = trimmed.split(":");
            long total = 0L;
            for (String part : parts) {
                total = total * 60 + Long.parseLong(part);
            }
            return total;
        }
        try {
            long value = Math.round(Double.parseDouble(trimmed));
            return value > 1000 ? value / 1000 : value;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private boolean hasData(JsonNode response) {
        JsonNode data = response == null ? null : response.path("data");
        return data != null && !data.isMissingNode() && !data.isNull();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private List<String> uniqueNonBlank(String... values) {
        List<String> result = new ArrayList<>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            addUniqueNonBlank(result, value);
        }
        return result;
    }

    private void addUniqueNonBlank(List<String> values, String value) {
        String trimmed = trimToNull(value);
        if (!StringUtils.hasText(trimmed) || values.contains(trimmed)) {
            return;
        }
        values.add(trimmed);
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static String trimTrailingSlash(String value) {
        if (!StringUtils.hasText(value)) {
            return "https://api.tikhub.io";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private record TikHubEndpoint(String path, String queryName, String queryValue) {
    }

    private record TikHubRequest(String path, Map<String, String> queryParams) {
    }

    private record FetchedHtmlPage(String finalUrl, String html) {
    }

    private record VideoUrlCandidate(String url, int score) {
    }

    private record CachedParseResult(long expiresAtMillis, DouyinVideoParseResponse response) {
    }

    private enum VideoPlatform {
        DOUYIN("抖音"),
        TIKTOK("TikTok"),
        XIAOHONGSHU("小红书"),
        INSTAGRAM("Instagram"),
        YOUTUBE("YouTube"),
        TWITTER("X/Twitter"),
        THREADS("Threads"),
        KUAISHOU("快手"),
        WECHAT_CHANNELS("微信视频号"),
        BILIBILI("B站"),
        WEIBO("微博"),
        LEMON8("Lemon8"),
        LINKEDIN("LinkedIn"),
        REDDIT("Reddit"),
        ZHIHU("知乎"),
        FACEBOOK("Facebook"),
        UNKNOWN("unknown");

        private final String displayName;

        VideoPlatform(String displayName) {
            this.displayName = displayName;
        }

        private String displayName() {
            return displayName;
        }
    }
}
