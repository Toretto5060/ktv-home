package com.homektv.library;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.musicsource.MusicProvider;
import com.homektv.musicsource.MusicSourceException;
import com.homektv.musicsource.NeteaseCrypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/**
 * 歌手头像抓取服务：从 QQ 音乐、网易云、酷狗三大平台抓取歌手头像 URL 并下载保存。
 */
@Service
public class ArtistScraperService {
    private static final Logger log = LoggerFactory.getLogger(ArtistScraperService.class);
    private static final Duration SCRAPE_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_AVATAR_BYTES = 2 * 1024 * 1024;
    private static final ObjectMapper mapper = new ObjectMapper();

    private final AssetWriter writer;

    public ArtistScraperService(AssetWriter writer) {
        this.writer = writer;
    }

    /** 依次尝试各平台，返回第一个有效头像 URL，失败返回 null。 */
    public String findAvatarUrl(String artistName) {
        String url = tryQqMusicAvatar(artistName);
        if (url != null) return url;
        url = tryNeteaseAvatar(artistName);
        if (url != null) return url;
        return tryKugouAvatar(artistName);
    }

    /** 从远程 URL 下载头像图片并写入 data/artists/ 目录，返回相对路径，失败返回 null。 */
    public String downloadAndSave(String artistName, String remoteUrl) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder(URI.create(remoteUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "HomeKTV/0.1 avatar")
                    .header("Accept", "image/*")
                    .GET().build();
            HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                log.warn("[头像下载] HTTP {} '{}' -> {}", resp.statusCode(), remoteUrl, artistName);
                return null;
            }
            byte[] body = resp.body();
            if (body == null || body.length == 0 || body.length > MAX_AVATAR_BYTES) return null;
            if (!isImageBytes(body)) return null;
            return writer.writeArtistAvatar(artistName, body);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- QQ 音乐 ----

    private String tryQqMusicAvatar(String artistName) {
        try {
            var http = new SimpleHttp(MusicProvider.QQ);
            // 旧版接口搜歌，从歌曲结果中提取歌手 MID（新版已不返回 singer.list）
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("format", "json"); q.put("outCharset", "utf-8"); q.put("ct", 24);
            q.put("qqmusic_ver", 1298); q.put("remoteplace", "txt.yqq.singer");
            q.put("platform", "yqq.json"); q.put("aggr", 0); q.put("cr", 1);
            q.put("p", 1); q.put("n", 3); q.put("w", artistName);
            JsonNode root = http.get("https://c.y.qq.com/soso/fcgi-bin/client_search_cp?" + query(q), Map.of());
            JsonNode songList = root.path("data").path("song").path("list");
            if (!songList.isArray() || songList.isEmpty()) {
                log.debug("[QQ音乐] 未找到歌曲 '{}'，响应: {}", artistName, root.toString());
                return null;
            }
            // 从歌曲结果中提取同名歌手的 MID
            String singerMid = null;
            for (JsonNode song : songList) {
                JsonNode singers = song.path("singer");
                if (!singers.isArray()) continue;
                for (JsonNode singer : singers) {
                    String name = singer.path("name").asText("");
                    if (artistName.equals(name) || name.contains(artistName)) {
                        String mid = singer.path("mid").asText(null);
                        if (mid != null && !mid.isBlank()) { singerMid = mid; break; }
                    }
                }
                if (singerMid != null) break;
            }
            if (singerMid == null) {
                // fallback：取第一个歌手
                singerMid = songList.get(0).path("singer").path(0).path("mid").asText(null);
            }
            if (singerMid == null || singerMid.isBlank()) {
                log.debug("[QQ音乐] 歌手 '{}' 无 singer_mid，响应: {}", artistName, root.toString());
                return null;
            }
            String url = "https://y.gtimg.cn/music/photo_new/T001R300x300M000" + singerMid + ".jpg";
            log.info("[QQ音乐] 找到头像 '{}' -> {}", artistName, url);
            return url;
        } catch (Exception e) { log.warn("[QQ音乐] 头像搜索失败 '{}': {}", artistName, e.getMessage()); }
        return null;
    }

    // ---- 网易云 ----

    private String tryNeteaseAvatar(String artistName) {
        try {
            var http = new SimpleHttp(MusicProvider.NETEASE);
            Map<String, Object> searchData = new LinkedHashMap<>();
            searchData.put("s", artistName); searchData.put("type", 100); searchData.put("limit", 1); searchData.put("offset", 0);
            Map<String, String> form = Map.of(
                    "params", NeteaseCrypto.eapi("/api/search/get", toJson(searchData)),
                    "encSecKey", NeteaseCrypto.weapi("").encSecKey());
            JsonNode root = http.form("https://music.163.com/eapi/search/get", form,
                    Map.of("Referer", "https://music.163.com/"));
            JsonNode artists = root.path("result").path("artists");
            if (!artists.isArray() || artists.isEmpty()) {
                log.debug("[网易云] 未找到歌手 '{}'，响应: {}", artistName, root.toString());
                return null;
            }
            long artistId = artists.get(0).path("id").asLong(0);
            if (artistId == 0) {
                log.debug("[网易云] 歌手 '{}' 无有效ID，响应: {}", artistName, root.toString());
                return null;
            }
            Map<String, Object> detailData = Map.of("id", artistId);
            Map<String, String> detailForm = Map.of(
                    "params", NeteaseCrypto.eapi("/api/artist/avatar", toJson(detailData)),
                    "encSecKey", NeteaseCrypto.weapi("").encSecKey());
            JsonNode detail = http.form("https://music.163.com/eapi/artist/avatar", detailForm,
                    Map.of("Referer", "https://music.163.com/"));
            String img = detail.path("data").path("avatar").asText(null);
            if (img == null || img.isBlank()) {
                log.debug("[网易云] 歌手 '{}' 无头像，响应: {}", artistName, detail.toString());
                return null;
            }
            String url = img.replace("http://", "https://");
            log.info("[网易云] 找到头像 '{}' -> {}", artistName, url);
            return url;
        } catch (Exception e) { log.warn("[网易云] 头像搜索失败 '{}': {}", artistName, e.getMessage()); }
        return null;
    }

    // ---- 酷狗 ----

    private String tryKugouAvatar(String artistName) {
        try {
            var http = new SimpleHttp(MusicProvider.KUGOU);
            Map<String, String> sq = new LinkedHashMap<>();
            sq.put("keyword", artistName); sq.put("page", "1"); sq.put("pagesize", "1");
            sq.put("userid", "-1"); sq.put("clientver", ""); sq.put("platform", "WebFilter");
            JsonNode searchRoot = http.get("https://songsearch.kugou.com/song_search_v2?" + query(sq), Map.of());
            JsonNode lists = searchRoot.path("data").path("lists");
            if (!lists.isArray() || lists.isEmpty()) {
                log.debug("[酷狗] 未找到歌手 '{}'，响应: {}", artistName, searchRoot.toString());
                return null;
            }
            String singerId = text(lists.get(0), "singerid", "SingerId");
            if (singerId == null || singerId.isBlank()) {
                log.debug("[酷狗] 歌手 '{}' 无 singerId，响应: {}", artistName, searchRoot.toString());
                return null;
            }
            String now = String.valueOf(System.currentTimeMillis() / 1000);
            Map<String, String> params = new LinkedHashMap<>();
            params.put("method", "info.get_singer"); params.put("platform", "Android");
            params.put("appid", "1005"); params.put("clientver", "12050");
            params.put("clienttime", now); params.put("singerid", singerId); params.put("hash", "");
            params.put("signature", kgSignature(params, ""));
            String apiUrl = "https://gateway.kugou.com/krcserver/v1/token?" + query(params);
            JsonNode detail = http.get(apiUrl, Map.of(
                    "x-router", " singerinfo.kugou.com",
                    "User-Agent", "Android12-ati9z-12050-46-0-DiscoveryDRADProtocol-wifi"));
            String avatar = detail.path("data").path("imgUrl").asText(null);
            if (avatar == null || avatar.isBlank()) {
                log.debug("[酷狗] 歌手 '{}' 无头像，响应: {}", artistName, detail.toString());
                return null;
            }
            String url = avatar.replace("\\", "").replace("\"", "");
            log.info("[酷狗] 找到头像 '{}' -> {}", artistName, url);
            return url;
        } catch (Exception e) { log.warn("[酷狗] 头像搜索失败 '{}': {}", artistName, e.getMessage()); }
        return null;
    }

    // ---- 图片校验 ----

    private static boolean isImageBytes(byte[] bytes) {
        if (bytes.length < 4) return false;
        if (bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8 && bytes[2] == (byte) 0xFF) return true;
        if (bytes[0] == (byte) 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47) return true;
        if (bytes[0] == 0x47 && bytes[1] == 0x49 && bytes[2] == 0x46 && bytes[3] == 0x38) return true;
        if (bytes[0] == 0x52 && bytes[1] == 0x49 && bytes[2] == 0x46 && bytes[3] == 0x46
                && bytes.length >= 12 && bytes[8] == 0x57 && bytes[9] == 0x45 && bytes[10] == 0x42 && bytes[11] == 0x50) return true;
        return false;
    }

    // ---- 工具方法 ----

    private static String query(Map<String, ?> values) {
        return values.entrySet().stream()
                .map(e -> urlEncode(e.getKey()) + "=" + urlEncode(String.valueOf(e.getValue())))
                .reduce("", (a, b) -> a + (a.isEmpty() ? "" : "&") + b);
    }

    private static String urlEncode(String s) {
        try { return URLEncoder.encode(s, StandardCharsets.UTF_8); } catch (Exception e) { return s; }
    }

    private static String toJson(Object obj) {
        try { return mapper.writeValueAsString(obj); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private static String text(JsonNode node, String... fields) {
        for (String f : fields) {
            String v = node.path(f).asText(null);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String kgSignature(Map<String, ?> params, String body) {
        try {
            String joined = params.entrySet().stream()
                    .filter(e -> !"signature".equals(e.getKey()))
                    .sorted(Comparator.comparing(Map.Entry::getKey))
                    .map(e -> e.getKey() + "=" + String.valueOf(e.getValue()))
                    .reduce("", String::concat);
            byte[] digest = MessageDigest.getInstance("MD5").digest(
                    ("OIlwieks28dk2k092lksi2UIkp" + joined + body + "OIlwieks28dk2k092lksi2UIkp")
                            .getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(32);
            for (byte b : digest) out.append(String.format("%02x", b & 0xff));
            return out.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    // ---- 内联简化 HTTP 客户端 ----

    private static class SimpleHttp {
        private final HttpClient client = HttpClient.newHttpClient();
        private final MusicProvider provider;

        SimpleHttp(MusicProvider provider) { this.provider = provider; }

        JsonNode get(String url, Map<String, ?> headers) {
            try {
                var builder = HttpRequest.newBuilder(URI.create(url)).timeout(SCRAPE_TIMEOUT).GET();
                headers.forEach((k, v) -> builder.header(k, String.valueOf(v)));
                builder.header("Accept", "application/json").header("User-Agent", "HomeKTV/0.1");
                return mapper.readTree(client.send(builder.build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body());
            } catch (Exception e) { throw new MusicSourceException(provider, "HTTP 请求失败", e); }
        }

        JsonNode form(String url, Map<String, String> form, Map<String, ?> headers) {
            try {
                String body = form.entrySet().stream()
                        .map(e -> urlEncode(e.getKey()) + "=" + urlEncode(e.getValue()))
                        .reduce("", (a, b) -> a + (a.isEmpty() ? "" : "&") + b);
                var builder = HttpRequest.newBuilder(URI.create(url)).timeout(SCRAPE_TIMEOUT)
                        .header("Content-Type", "application/x-www-form-urlencoded");
                headers.forEach((k, v) -> builder.header(k, String.valueOf(v)));
                builder.header("Accept", "application/json").header("User-Agent", "HomeKTV/0.1");
                builder.POST(HttpRequest.BodyPublishers.ofString(body));
                return mapper.readTree(client.send(builder.build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body());
            } catch (Exception e) { throw new MusicSourceException(provider, "表单请求失败", e); }
        }
    }
}
