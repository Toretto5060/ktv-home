package com.homektv.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.homektv.domain.ArtistMetadata;
import com.homektv.domain.Song;
import com.homektv.musicsource.MusicSourceConfigService;
import com.homektv.repo.ArtistMetadataRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import com.homektv.ws.ProgressBroadcaster;
import com.homektv.ws.WsEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 歌手库服务（v2）：从 songs 表聚合歌手，合并 artist_metadata 中的头像与性别。
 */
@Service
public class ArtistLibraryService {
    private static final Set<String> GENDERS = Set.of("男歌手", "女歌手", "组合", "未知");

    private final ArtistMetadataRepository metaRepo;
    private final SongRepository songRepo;
    private final ArtistScraperService scraper;
    private final MusicSourceConfigService configService;
    private final ProgressBroadcaster progressBroadcaster;

    /** 后台刮削任务状态。null = 无任务。 */
    private final AtomicReference<ScrapeAllTask> backgroundTask = new AtomicReference<>();

    public ArtistLibraryService(ArtistMetadataRepository metaRepo, SongRepository songRepo,
                                ArtistScraperService scraper, MusicSourceConfigService configService,
                                ProgressBroadcaster progressBroadcaster) {
        this.metaRepo = metaRepo;
        this.songRepo = songRepo;
        this.scraper = scraper;
        this.configService = configService;
        this.progressBroadcaster = progressBroadcaster;
    }

    /** 启动后台刮削：扫描所有无头像歌手，在后台异步执行。 */
    public void startBackgroundScrape() {
        if (!backgroundTask.compareAndSet(null, new ScrapeAllTask())) return;
        int intervalMs = configService.getConfig().requestIntervalMs();
        Thread.ofVirtual().start(() -> runBackgroundScrape(intervalMs));
    }

    /** 查询后台任务状态，供前端轮询。 */
    public Map<String, Object> backgroundTaskStatus() {
        ScrapeAllTask task = backgroundTask.get();
        if (task == null) return Map.of("running", false);
        return task.status();
    }

    /** 暂停后台刮削。 */
    public void pauseBackgroundScrape() {
        ScrapeAllTask task = backgroundTask.get();
        if (task == null || !task.isRunning()) return;
        task.pause();
    }

    /** 继续后台刮削。 */
    public void resumeBackgroundScrape() {
        ScrapeAllTask task = backgroundTask.get();
        if (task == null) return;
        task.resume();
        synchronized (task) { task.notifyAll(); }
    }

    /**
     * 分页列表：从 songs 表聚合所有歌手，合并 artist_metadata 中的头像和性别。
     * @param page 页码（从 0 开始）
     * @param size 每页条数
     */
    public Map<String, Object> list(String keyword, String gender, Boolean reviewed, Boolean avatar,
                                    int page, int size) {
        Map<String, ArtistMetadata> metaMap = new HashMap<>();
        metaRepo.findAll().forEach(m -> metaMap.put(m.getArtistName(), m));

        String q = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);

        // 后台任务正在跑时，排除已完成刮削的artist（即将落库，但尚未可见）
        Set<String> skipSet = Collections.emptySet();
        ScrapeAllTask task = backgroundTask.get();
        if (task != null && task.isRunning()) {
            int skipDone = task.getDone();
            if (skipDone > 0) {
                // 收集已完成刮削的artist名（按扫描顺序前skipDone个无头像artist，用第一位歌手名去重）
                Set<String> unscrapedNames = validSongs().stream()
                        .map(s -> {
                            String[] parts = splitArtistNames(s.getArtist());
                            return parts.length > 0 ? parts[0].trim() : s.getArtist();
                        })
                        .filter(a -> !a.isBlank())
                        .distinct()
                        .filter(key -> {
                            ArtistMetadata m = metaMap.get(key);
                            return m == null || m.getAvatarUrl() == null || m.getAvatarUrl().isBlank();
                        })
                        .limit(skipDone)
                        .collect(Collectors.toSet());
                skipSet = unscrapedNames;
            }
        }

        final Set<String> finalSkipSet = skipSet;

        List<Map<String, Object>> all = validSongs().stream()
                // 按第一位歌手名分组，合并所有变体（A, A/B, A&C 都归到 A）
                .collect(Collectors.groupingBy(s -> {
                    String[] parts = splitArtistNames(s.getArtist());
                    return parts.length > 0 ? parts[0].trim() : s.getArtist();
                }))
                .entrySet().stream()
                .filter(e -> !e.getKey().isBlank())
                .filter(e -> {
                    // 关键词匹配：搜索任意原始歌手名
                    if (!q.isBlank()) {
                        boolean match = e.getValue().stream()
                                .anyMatch(s -> s.getArtist().toLowerCase(Locale.ROOT).contains(q));
                        if (!match) return false;
                    }
                    return !finalSkipSet.contains(e.getKey());
                })
                .map(e -> {
                    String firstArtist = e.getKey();
                    List<Song> songs = e.getValue();
                    // 显示名：优先取没有分隔符的原始歌手名（单歌手），否则取第一位歌手名
                    String displayName = songs.stream()
                            .map(Song::getArtist)
                            .filter(a -> !a.contains("/") && !a.contains("&") && !a.contains("，") && !a.contains(","))
                            .findFirst()
                            .orElse(firstArtist);
                    ArtistMetadata meta = metaMap.get(firstArtist);
                    String g = meta != null ? meta.getGender() : dominantGender(songs);
                    if (gender != null && !gender.isBlank() && !gender.equals(g)) return null;
                    boolean hasAvatar = meta != null && meta.getAvatarUrl() != null && !meta.getAvatarUrl().isBlank();
                    if (avatar != null && !avatar && hasAvatar) return null;
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("name", displayName);
                    map.put("gender", g);
                    map.put("songCount", songs.size());
                    map.put("avatarUrl", hasAvatar ? "/api/artist-avatar/" + urlEncode(firstArtist) : "");
                    map.put("hasAvatar", hasAvatar);
                    map.put("songs", representativeSongs(songs));
                    return map;
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt((Map<String, Object> m) -> (Integer) m.get("songCount")).reversed()
                        .thenComparing(m -> (String) m.get("name")))
                .toList();

        int total = all.size();
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        int from = Math.min(safePage * safeSize, total);
        int to = Math.min(from + safeSize, total);
        List<Map<String, Object>> content = from < total ? all.subList(from, to) : List.of();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content);
        result.put("total", total);
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("totalPages", (total + safeSize - 1) / safeSize);
        return result;
    }

    /**
     * 全量统计：有头像歌手数、无头像歌手数、歌手总数。
     * 不分页，用于页面顶部统计栏。
     * 按第一位歌手名分组，合并变体（如 A 和 A/B 都归到 A）。
     * 如果后台刮削任务正在运行，"无头像"数会减去已完成的artist数（他们还没落库但即将落库）。
     */
    public Map<String, Object> stats() {
        Map<String, ArtistMetadata> metaMap = new HashMap<>();
        metaRepo.findAll().forEach(m -> metaMap.put(m.getArtistName(), m));

        // 按第一位歌手名分组
        Map<String, List<Song>> grouped = validSongs().stream()
                .collect(Collectors.groupingBy(s -> {
                    String[] parts = splitArtistNames(s.getArtist());
                    return parts.length > 0 ? parts[0].trim() : s.getArtist();
                }))
                .entrySet().stream()
                .filter(e -> !e.getKey().isBlank())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        long total = grouped.size();
        long hasAvatar = grouped.values().stream()
                .filter(songs -> {
                    if (songs.isEmpty()) return false;
                    String firstArtist = songs.get(0).getArtist();
                    String[] parts = splitArtistNames(firstArtist);
                    String key = parts.length > 0 ? parts[0].trim() : firstArtist;
                    if (key.isEmpty()) return false;
                    ArtistMetadata meta = metaMap.get(key);
                    return meta != null && meta.getAvatarUrl() != null && !meta.getAvatarUrl().isBlank();
                })
                .count();

        long noAvatar = total - hasAvatar;
        // 后台刮削正在运行时，已完成的不计入"待刮削"
        ScrapeAllTask task = backgroundTask.get();
        if (task != null) {
            int running = task.getDone();
            if (running > 0) noAvatar = Math.max(0, noAvatar - running);
        }

        return Map.of(
                "total", total,
                "hasAvatar", hasAvatar,
                "noAvatar", noAvatar
        );
    }

    public Map<String, Object> get(String artist) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) list(null, null, null, null, 0, Integer.MAX_VALUE).get("content");
        return content.stream()
                .filter(m -> artist.equals(m.get("name")))
                .findFirst()
                .orElseThrow(() -> new ApiException("ARTIST_NOT_FOUND", "歌手不存在"));
    }

    @Transactional
    public Map<String, Object> apply(String artist, String gender) {
        if (!GENDERS.contains(gender)) throw new ApiException("INVALID_ARTIST_GENDER", "歌手类型无效");
        return update(artist, gender, null);
    }

    @Transactional
    public Map<String, Object> update(String artistName, String gender, String avatarUrl) {
        ArtistMetadata meta = metaRepo.findById(artistName)
                .orElseGet(() -> { ArtistMetadata m = new ArtistMetadata(); m.setArtistName(artistName); return m; });
        if (gender != null && !gender.isBlank()) meta.setGender(gender);
        if (avatarUrl != null) meta.setAvatarUrl(avatarUrl.isBlank() ? null : avatarUrl);
        metaRepo.save(meta);
        return toMap(artistName, meta);
    }

    /** 手动刮削单个歌手头像。 */
    @Transactional
    public Map<String, Object> scrape(String artist) {
        return scrapeBatch(List.of(artist)).stream().findFirst().orElse(Map.of());
    }

    /** 批量刮削歌手头像（前台调用）。 */
    public List<Map<String, Object>> scrapeBatch(Collection<String> artistNames) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (String name : artistNames) {
            try {
                ArtistMetadata meta = metaRepo.findById(name).orElseGet(() -> {
                    ArtistMetadata m = new ArtistMetadata(); m.setArtistName(name); m.setGender("未知"); return m;
                });
                ArtistScraperService.ScrapeResult result = scraper.findAvatarUrl(name);
                String relPath = null;
                if (result != null && result.url() != null) {
                    relPath = scraper.downloadAndSave(name, result.url());
                }
                // 只在下载成功时更新头像，避免覆盖已有数据
                if (relPath != null) {
                    meta.setAvatarUrl(relPath);
                    meta.setSource("SCRAPED");
                }
                // 性别更新逻辑：仅当刮削到性别且当前为"未知"时才更新
                if (result != null && result.gender() != null && !result.gender().isBlank()) {
                    String cur = meta.getGender();
                    if (cur == null || "未知".equals(cur) || !GENDERS.contains(cur)) {
                        meta.setGender(result.gender());
                    }
                }
                metaRepo.save(meta);
                results.add(toMap(name, meta));
            } catch (Exception e) {
                Map<String, Object> fail = new LinkedHashMap<>();
                fail.put("name", name); fail.put("avatarUrl", ""); fail.put("gender", "未知");
                fail.put("songs", List.of()); fail.put("songCount", 0);
                results.add(fail);
            }
        }
        return results;
    }

    /** 同步：删除不在 songs 表中的歌手元数据。 */
    @Transactional
    public int syncWithSongs() {
        Set<String> allArtists = validSongs().stream().map(Song::getArtist)
                .filter(a -> a != null && !a.isBlank()).collect(Collectors.toSet());
        List<ArtistMetadata> stale = metaRepo.findAll().stream()
                .filter(a -> !allArtists.contains(a.getArtistName())).toList();
        metaRepo.deleteAll(stale);
        return stale.size();
    }

    // ---- 辅助 ----

    private List<Song> validSongs() {
        return songRepo.findAll().stream().filter(s -> "ok".equals(s.getStatus())).toList();
    }

    private String dominantGender(List<Song> songs) {
        return songs.stream().map(Song::getArtistGender)
                .filter(g -> g != null && !g.isBlank() && !"未知".equals(g))
                .collect(Collectors.groupingBy(g -> g, Collectors.counting())).entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("未知");
    }

    /** 按 "/" "&" "，" 等分隔符拆分组合歌手名，返回各独立歌手名列表。 */
    private String[] splitArtistNames(String artist) {
        if (artist == null || artist.isBlank()) return new String[0];
        return artist.split("[\\\\/&,\uff0c，；;、\t]+");
    }

    // ---- 后台刮削任务 ----

    /** 后台全量刮削任务状态。 */
    private class ScrapeAllTask {
        private volatile boolean running = false;
        private volatile boolean paused = false;
        private volatile int total = 0;
        private volatile int done = 0;
        private volatile int succeeded = 0;
        private volatile String phase = "IDLE";

        synchronized void start() { this.running = true; this.paused = false; this.phase = "SCANNING"; }
        synchronized void setTotal(int n) { this.total = n; this.phase = "SCRAPING"; }
        synchronized void markDone(boolean ok) {
            this.done++;
            if (ok) this.succeeded++;
            broadcastArtistScrapeProgress();
        }
        synchronized void finish() { this.running = false; this.paused = false; this.phase = "DONE"; broadcastArtistScrapeProgress(); }
        synchronized boolean isRunning() { return this.running; }
        synchronized boolean isPaused() { return this.paused; }
        synchronized void pause() { this.paused = true; this.phase = "PAUSED"; }
        synchronized void resume() { this.paused = false; this.phase = "SCRAPING"; }
        synchronized boolean waitWhilePaused() {
            while (paused) {
                try { wait(); } catch (InterruptedException ignored) { return false; }
            }
            return true;
        }
        synchronized int getDone() { return this.done; }

        synchronized Map<String, Object> status() {
            return Map.of(
                    "running", running,
                    "paused", paused,
                    "phase", phase,
                    "total", total,
                    "done", done,
                    "succeeded", succeeded
            );
        }
    }

    private void broadcastArtistScrapeProgress() {
        if (progressBroadcaster == null) return;
        ScrapeAllTask task = backgroundTask.get();
        if (task != null) {
            progressBroadcaster.broadcastToH5(WsEvent.ARTIST_SCRAPE_PROGRESS, task.status());
        }
    }

    private void runBackgroundScrape(int intervalMs) {
        ScrapeAllTask task = backgroundTask.get();
        task.start();
        try {
            // 收集所有需要刮削的歌手（组合歌手只取第一位）
            Set<String> toScrapeSet = new HashSet<>();
            validSongs().forEach(s -> {
                String[] parts = splitArtistNames(s.getArtist());
                if (parts.length > 0) {
                    String first = parts[0].trim();
                    if (!first.isBlank()) toScrapeSet.add(first);
                }
            });

            Map<String, ArtistMetadata> metaMap = new HashMap<>();
            metaRepo.findAll().forEach(m -> metaMap.put(m.getArtistName(), m));
            List<String> toScrape = toScrapeSet.stream()
                    .filter(name -> {
                        ArtistMetadata m = metaMap.get(name);
                        return m == null || m.getAvatarUrl() == null || m.getAvatarUrl().isBlank();
                    })
                    .toList();
            task.setTotal(toScrape.size());
            for (String name : toScrape) {
                try {
                    ArtistMetadata meta = metaRepo.findById(name).orElseGet(() -> {
                        ArtistMetadata m = new ArtistMetadata(); m.setArtistName(name); m.setGender("未知"); return m;
                    });
                    ArtistScraperService.ScrapeResult result = scraper.findAvatarUrl(name);
                    String relPath = null;
                    if (result != null && result.url() != null) {
                        relPath = scraper.downloadAndSave(name, result.url());
                    }
                    if (relPath != null) {
                        meta.setAvatarUrl(relPath);
                        meta.setSource("SCRAPED");
                        if (result.gender() != null && !result.gender().isBlank()) {
                            String cur = meta.getGender();
                            if (cur == null || "未知".equals(cur) || !GENDERS.contains(cur)) {
                                meta.setGender(result.gender());
                            }
                        }
                        metaRepo.save(meta);
                        task.markDone(true);
                    } else {
                        task.markDone(false);
                    }
                } catch (Exception e) {
                    task.markDone(false);
                }
                try { Thread.sleep(intervalMs); } catch (InterruptedException ignored) {}
                if (!task.waitWhilePaused()) {
                    task.finish();
                    return;
                }
            }
        } finally {
            task.finish();
        }
    }

    private List<Map<String, Object>> representativeSongs(List<Song> songs) {
        return songs.stream()
                .sorted(Comparator.comparingInt(Song::getPlayCount).reversed().thenComparing(Song::getTitle, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .limit(3)
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", s.getId());
                    m.put("title", s.getTitle());
                    m.put("coverUrl", s.getCoverPath() == null ? "" : "/api/cover/" + s.getId());
                    return m;
                })
                .toList();
    }

    private Map<String, Object> toMap(String name, ArtistMetadata meta) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("gender", meta.getGender());
        map.put("avatarUrl", (meta.getAvatarUrl() != null && !meta.getAvatarUrl().isBlank())
                ? "/api/artist-avatar/" + urlEncode(name) : "");
        map.put("songs", representativeSongs(validSongs().stream().filter(s -> name.equals(s.getArtist())).toList()));
        map.put("songCount", validSongs().stream().filter(s -> name.equals(s.getArtist())).count());
        return map;
    }

    private static String urlEncode(String s) {
        try { return URLEncoder.encode(s, StandardCharsets.UTF_8); } catch (Exception e) { return s; }
    }
}
