package com.sxxian.multiagentcreator.image.adapter;

import cn.hutool.core.util.StrUtil;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sxxian.multiagentcreator.config.ChinaImageSearchConfig;
import com.sxxian.multiagentcreator.model.dto.image.ImageData;
import com.sxxian.multiagentcreator.model.dto.image.ImageRequest;
import com.sxxian.multiagentcreator.model.enums.ImageMethodEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.sxxian.multiagentcreator.constant.ArticleConstant.PICSUM_URL_TEMPLATE;

@Service
@Slf4j
public class ChinaImageSearchService implements ImageSearchService {

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
    private static final int MAX_IMAGE_BYTES = 12 * 1024 * 1024;

    @Resource
    private ChinaImageSearchConfig config;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(20))
            .build();

    @Override
    public String searchImage(String keywords) {
        return searchCandidates(keywords).stream()
                .map(ImageCandidate::imageUrl)
                .filter(StrUtil::isNotBlank)
                .findFirst()
                .orElse(null);
    }

    @Override
    public ImageData getImageData(ImageRequest request) {
        String keywords = request == null ? null : request.getEffectiveParam(false);
        for (ImageCandidate candidate : searchCandidates(keywords)) {
            ImageData imageData = download(candidate);
            if (imageData != null && imageData.isValid()) {
                return imageData;
            }
        }
        return null;
    }

    @Override
    public ImageMethodEnum getMethod() {
        return ImageMethodEnum.CHINA_IMAGE_SEARCH;
    }

    @Override
    public String getFallbackImage(int position) {
        return String.format(PICSUM_URL_TEMPLATE, position);
    }

    List<ImageCandidate> extractCandidates(String html) {
        List<ImageCandidate> candidates = new ArrayList<>();
        if (StrUtil.isBlank(html)) {
            return candidates;
        }

        Document document = Jsoup.parse(html);
        Elements anchors = document.select("a.iusc");
        for (Element anchor : anchors) {
            ImageCandidate candidate = parseIusc(anchor.attr("m"));
            if (candidate != null) {
                candidates.add(candidate);
            }
        }

        if (candidates.isEmpty()) {
            Elements images = document.select("img.mimg");
            for (Element image : images) {
                String src = firstNotBlank(image.attr("data-src"), image.attr("src"));
                if (StrUtil.isNotBlank(src)) {
                    candidates.add(new ImageCandidate(src, "", src));
                }
            }
        }
        return candidates;
    }

    private List<ImageCandidate> searchCandidates(String keywords) {
        if (StrUtil.isBlank(keywords)) {
            log.warn("China image search keywords is blank");
            return List.of();
        }

        String searchText = normalizeSearchText(keywords);
        String fetchUrl = buildSearchUrl(searchText);
        log.info("China image search: {} -> {}", keywords, searchText);

        try {
            Document document = Jsoup.connect(fetchUrl)
                    .timeout(config.getTimeout())
                    .userAgent(USER_AGENT)
                    .get();
            List<ImageCandidate> candidates = extractCandidates(document.html());
            List<ImageCandidate> filtered = candidates.stream()
                    .filter(this::isUsefulCandidate)
                    .limit(Math.max(1, config.getMaxCandidates()))
                    .toList();
            if (filtered.isEmpty()) {
                log.warn("China image search found no usable image, keywords={}", keywords);
            }
            return filtered;
        } catch (Exception e) {
            log.error("China image search failed, keywords={}", keywords, e);
            return List.of();
        }
    }

    private String normalizeSearchText(String keywords) {
        String trimmed = keywords.trim();
        if (containsAny(trimmed, "海报", "剧照", "角色", "官方", "电影", "动漫", "动画")) {
            return trimmed;
        }
        return trimmed + " " + config.getSuffix();
    }

    private String buildSearchUrl(String searchText) {
        String encodedText = URLEncoder.encode(searchText, StandardCharsets.UTF_8);
        return String.format("%s?q=%s&mmasync=1", config.getSearchUrl(), encodedText);
    }

    private ImageCandidate parseIusc(String metadata) {
        if (StrUtil.isBlank(metadata)) {
            return null;
        }
        try {
            JsonObject json = JsonParser.parseString(metadata).getAsJsonObject();
            String murl = getString(json, "murl");
            String purl = getString(json, "purl");
            String turl = getString(json, "turl");
            String imageUrl = firstNotBlank(murl, turl);
            if (StrUtil.isBlank(imageUrl)) {
                return null;
            }
            return new ImageCandidate(imageUrl, purl, turl);
        } catch (Exception e) {
            return null;
        }
    }

    private ImageData download(ImageCandidate candidate) {
        for (String url : List.of(candidate.imageUrl(), candidate.thumbnailUrl())) {
            if (StrUtil.isBlank(url)) {
                continue;
            }
            ImageData imageData = downloadUrl(url, candidate.pageUrl());
            if (imageData != null && imageData.isValid()) {
                return imageData;
            }
        }
        return null;
    }

    private ImageData downloadUrl(String imageUrl, String referer) {
        try {
            Request.Builder requestBuilder = new Request.Builder()
                    .url(imageUrl)
                    .addHeader("User-Agent", USER_AGENT)
                    .addHeader("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8");
            if (StrUtil.isNotBlank(referer)) {
                requestBuilder.addHeader("Referer", referer);
            }

            try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.warn("China image download failed, url={}, code={}", imageUrl, response.code());
                    return null;
                }
                String contentType = response.header("Content-Type", "image/jpeg");
                if (!contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
                    return null;
                }
                byte[] bytes = response.body().bytes();
                if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
                    log.warn("China image size invalid, url={}, size={}", imageUrl, bytes.length);
                    return null;
                }
                return ImageData.fromBytes(bytes, contentType);
            }
        } catch (IOException | IllegalArgumentException e) {
            log.warn("China image download exception, url={}", imageUrl, e);
            return null;
        }
    }

    private boolean isUsefulCandidate(ImageCandidate candidate) {
        if (candidate == null || StrUtil.isBlank(candidate.imageUrl())) {
            return false;
        }
        String url = candidate.imageUrl().toLowerCase(Locale.ROOT);
        if (url.startsWith("data:") || url.contains("base64")) {
            return false;
        }
        String host = hostOf(firstNotBlank(candidate.pageUrl(), candidate.imageUrl()));
        if (StrUtil.isBlank(host)) {
            return true;
        }
        return isDomesticHost(host) || !host.contains(".");
    }

    private boolean isDomesticHost(String host) {
        String lowerHost = host.toLowerCase(Locale.ROOT);
        return lowerHost.endsWith(".cn")
                || lowerHost.contains("baidu.")
                || lowerHost.contains("bilibili.")
                || lowerHost.contains("douban.")
                || lowerHost.contains("qq.")
                || lowerHost.contains("sina.")
                || lowerHost.contains("weibo.")
                || lowerHost.contains("sohu.")
                || lowerHost.contains("163.")
                || lowerHost.contains("1905.")
                || lowerHost.contains("mtime.")
                || lowerHost.contains("maoyan.")
                || lowerHost.contains("thepaper.");
    }

    private String hostOf(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getHost();
        } catch (Exception e) {
            return "";
        }
    }

    private String getString(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
    }

    private boolean containsAny(String text, String... tokens) {
        for (String token : tokens) {
            if (text.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String firstNotBlank(String... values) {
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }

    record ImageCandidate(String imageUrl, String pageUrl, String thumbnailUrl) {
    }
}
