package com.homektv.library;

import com.homektv.domain.ArtistMetadata;
import com.homektv.domain.Song;
import com.homektv.repo.ArtistMetadataRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
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

    public ArtistLibraryService(ArtistMetadataRepository metaRepo, SongRepository songRepo,
                                ArtistScraperService scraper) {
        this.metaRepo = metaRepo;
        this.songRepo = songRepo;
        this.scraper = scraper;
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

        List<Map<String, Object>> all = validSongs().stream()
                .collect(Collectors.groupingBy(Song::getArtist))
                .entrySet().stream()
                .filter(e -> q.isBlank() || e.getKey().toLowerCase(Locale.ROOT).contains(q))
                .map(e -> {
                    String name = e.getKey();
                    List<Song> songs = e.getValue();
                    ArtistMetadata meta = metaMap.get(name);
                    String g = meta != null ? meta.getGender() : dominantGender(songs);
                    if (gender != null && !gender.isBlank() && !gender.equals(g)) return null;
                    boolean hasAvatar = meta != null && meta.getAvatarUrl() != null && !meta.getAvatarUrl().isBlank();
                    if (avatar != null && !avatar && hasAvatar) return null;
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("name", name);
                    map.put("gender", g);
                    map.put("songCount", songs.size());
                    map.put("avatarUrl", hasAvatar ? "/api/artist-avatar/" + urlEncode(name) : "");
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
     */
    public Map<String, Object> stats() {
        Map<String, ArtistMetadata> metaMap = new HashMap<>();
        metaRepo.findAll().forEach(m -> metaMap.put(m.getArtistName(), m));

        long total = validSongs().stream().map(Song::getArtist).distinct().count();
        long hasAvatar = validSongs().stream()
                .collect(Collectors.groupingBy(Song::getArtist))
                .entrySet().stream()
                .filter(e -> {
                    ArtistMetadata meta = metaMap.get(e.getKey());
                    return meta != null && meta.getAvatarUrl() != null && !meta.getAvatarUrl().isBlank();
                })
                .count();

        return Map.of(
                "total", total,
                "hasAvatar", hasAvatar,
                "noAvatar", total - hasAvatar
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

    /** 批量刮削歌手头像。 */
    public List<Map<String, Object>> scrapeBatch(Collection<String> artistNames) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (String name : artistNames) {
            try {
                ArtistMetadata meta = metaRepo.findById(name).orElseGet(() -> {
                    ArtistMetadata m = new ArtistMetadata(); m.setArtistName(name); m.setGender("未知"); return m;
                });
                String remoteUrl = scraper.findAvatarUrl(name);
                String relPath = null;
                if (remoteUrl != null && !remoteUrl.isBlank()) {
                    relPath = scraper.downloadAndSave(name, remoteUrl);
                }
                meta.setAvatarUrl(relPath);
                if (relPath != null) meta.setSource("SCRAPED");
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
