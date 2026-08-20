package com.homektv.musicsource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.ws.ProgressBroadcaster;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

@Service
public class MusicMetadataScrapeWorker {
    private static final Set<String> APPLY_FIELDS = Set.of("title", "artist", "album", "releaseDate", "aliases", "cover", "language");
    private static final Map<String, String> LANGUAGE_KEYWORDS = Map.ofEntries(
            Map.entry("国语", "国语"),
            Map.entry("粤语", "粤语"),
            Map.entry("闽南", "闽南语"),
            Map.entry("闽南语", "闽南语"),
            Map.entry("英语", "英语"),
            Map.entry("日语", "日语"),
            Map.entry("韩语", "韩语"),
            Map.entry("纯音乐", "纯音乐"),
            Map.entry("其他", "其他"),
            Map.entry("英文", "英语"),
            Map.entry("日文", "日语"),
            Map.entry("韩文", "韩语")
    );
    private static final Pattern FILE_PATH_CLEAN = Pattern.compile("[_\\-()（）\\[\\]【】.\\s]+");
    private final JdbcTemplate jdbc;
    private final MusicSourceSearchService searchService;
    private final MusicMetadataApplyService applyService;
    private final MusicSourceConfigService configService;
    private final ObjectMapper mapper;
    private final ProgressBroadcaster progressBroadcaster;

    public MusicMetadataScrapeWorker(JdbcTemplate jdbc, MusicSourceSearchService searchService,
                                     MusicMetadataApplyService applyService, MusicSourceConfigService configService,
                                     ObjectMapper mapper, ProgressBroadcaster progressBroadcaster) {
        this.jdbc = jdbc;
        this.searchService = searchService;
        this.applyService = applyService;
        this.configService = configService;
        this.mapper = mapper;
        this.progressBroadcaster = progressBroadcaster;
    }

    @Async("metadataScrapeExecutor")
    public void process(String batchId) {
        if (!isRunning(batchId)) return;
        jdbc.update("UPDATE music_metadata_scrape_batches SET started_at=COALESCE(started_at,now()),updated_at=now() WHERE id=?", batchId);
        List<Long> itemIds = jdbc.query("SELECT id FROM music_metadata_scrape_items WHERE batch_id=? AND status='PENDING' ORDER BY id",
                (rs, index) -> rs.getLong(1), batchId);
        int concurrency = Math.max(1, Math.min(configService.getConfig().concurrencyLimit(), itemIds.size()));
        if (!itemIds.isEmpty()) {
            try (var executor = Executors.newFixedThreadPool(concurrency)) {
                List<CompletableFuture<Void>> futures = itemIds.stream()
                        .map(id -> CompletableFuture.runAsync(() -> processItem(batchId, id), executor)).toList();
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
            }
        }
        finishIfDone(batchId);
    }

    private void processItem(String batchId, long itemId) {
        if (!isRunning(batchId)) return;
        int claimed = jdbc.update("""
                UPDATE music_metadata_scrape_items SET status='PROCESSING',started_at=now(),error_message=NULL,updated_at=now()
                WHERE id=? AND batch_id=? AND status='PENDING'
                """, itemId, batchId);
        if (claimed == 0) return;
        ItemTarget target = jdbc.query("SELECT song_id FROM music_metadata_scrape_items WHERE id=?",
                rs -> rs.next() ? new ItemTarget((Long) rs.getObject(1)) : null, itemId);
        if (target == null || target.songId() == null) {
            fail(itemId, "歌曲已删除");
            return;
        }
        try {
            List<MusicSourceSearchService.SongMatch> matches = searchService.matches(target.songId(), false);
            MusicSourceSearchService.SongMatch best = matches.isEmpty() ? null : matches.getFirst();
            if (best == null) {
                review(itemId, null, "未找到可用的元数据候选");
                return;
            }
            ExternalTrack track = best.track();
            String json = mapper.writeValueAsString(best);
            jdbc.update("""
                    UPDATE music_metadata_scrape_items SET provider=?,external_id=?,match_score=?,result_json=CAST(? AS jsonb),updated_at=now()
                    WHERE id=?
                    """, track.provider().name(), track.externalId(), best.score(), json, itemId);
            if (!isRunning(batchId)) {
                jdbc.update("UPDATE music_metadata_scrape_items SET status='PENDING',updated_at=now() WHERE id=?", itemId);
                return;
            }
            double threshold = jdbc.queryForObject(
                    "SELECT auto_apply_threshold FROM music_metadata_scrape_batches WHERE id=?", Double.class, batchId);
            if (best.score() < threshold) {
                review(itemId, best, "匹配度低于自动写入阈值，等待人工审核");
                return;
            }
            try {
                MusicMetadataApplyService.ApplyRequest request = buildApplyRequest(target.songId(), APPLY_FIELDS);
                applyService.apply(target.songId(), track.provider(), track.externalId(), request);
                terminal(itemId, "AUTO_APPLIED", null);
            } catch (RuntimeException ex) {
                review(itemId, best, "自动写入未执行：" + safe(ex));
            }
        } catch (Exception ex) {
            fail(itemId, safe(ex));
        }
    }

    /**
     * 构建自动写入请求：自动检测歌曲文件名中的语种，
     * 如果歌曲当前语种为"未知"且检测到语种关键字，则注入 language override。
     * 语种只在未手动锁定时注入。
     */
    private MusicMetadataApplyService.ApplyRequest buildApplyRequest(long songId, Set<String> fields) {
        String currentLanguage = jdbc.queryForObject(
                "SELECT s.language FROM songs s WHERE s.id=?", String.class, songId);
        String detectedLanguage = "未知".equals(currentLanguage) ? detectLanguageFromFilePath(songId) : null;
        if (detectedLanguage != null) {
            var overrides = new java.util.LinkedHashMap<String, String>();
            overrides.put("language", detectedLanguage);
            return new MusicMetadataApplyService.ApplyRequest(fields, overrides);
        }
        return new MusicMetadataApplyService.ApplyRequest(fields);
    }

    private String detectLanguageFromFilePath(long songId) {
        String path = jdbc.queryForObject("""
                SELECT sf.file_path FROM song_files sf
                WHERE sf.song_id=? AND sf.valid=true
                ORDER BY sf.priority DESC LIMIT 1
                """, String.class, songId);
        if (path == null || path.isBlank()) return null;
        String clean = FILE_PATH_CLEAN.matcher(path).replaceAll(" ");
        for (Map.Entry<String, String> entry : LANGUAGE_KEYWORDS.entrySet()) {
            if (clean.contains(entry.getKey())) return entry.getValue();
        }
        return null;
    }

    private boolean isRunning(String batchId) {
        List<String> values = jdbc.query("SELECT status FROM music_metadata_scrape_batches WHERE id=?",
                (rs, index) -> rs.getString(1), batchId);
        return !values.isEmpty() && "RUNNING".equals(values.getFirst());
    }

    private void review(long itemId, MusicSourceSearchService.SongMatch match, String message) {
        if (match == null) {
            terminal(itemId, "REVIEW", message);
            return;
        }
        terminal(itemId, "REVIEW", message);
    }

    private void fail(long itemId, String message) { terminal(itemId, "FAILED", message); }

    private void terminal(long itemId, String status, String message) {
        jdbc.update("""
                UPDATE music_metadata_scrape_items SET status=?,error_message=?,finished_at=now(),updated_at=now() WHERE id=?
                """, status, ProviderJson.clean(message, 1000), itemId);
    }

    private void finishIfDone(String batchId) {
        Integer active = jdbc.queryForObject("""
                SELECT COUNT(*) FROM music_metadata_scrape_items WHERE batch_id=? AND status IN ('PENDING','PROCESSING')
                """, Integer.class, batchId);
        if (active != null && active == 0) {
            jdbc.update("""
                    UPDATE music_metadata_scrape_batches SET status='COMPLETED',finished_at=now(),updated_at=now()
                    WHERE id=? AND status='RUNNING'
                    """, batchId);
        }
        broadcastProgress(batchId);
    }

    private void broadcastProgress(String batchId) {
        if (progressBroadcaster == null) return;
        try {
            var batch = jdbc.queryForObject("""
                    SELECT id,mode,status,auto_apply_threshold,skipped_existing,created_at,started_at,finished_at
                    FROM music_metadata_scrape_batches WHERE id=?
                    """, (rs, i) -> {
                var m = new java.util.LinkedHashMap<String, Object>();
                m.put("id", rs.getString("id"));
                m.put("mode", rs.getString("mode"));
                m.put("status", rs.getString("status"));
                m.put("skippedExisting", rs.getInt("skipped_existing"));
                m.put("startedAt", rs.getTimestamp("started_at"));
                m.put("finishedAt", rs.getTimestamp("finished_at"));
                return m;
            }, batchId);
            if (batch == null) return;
            var countEntries = jdbc.query("""
                    SELECT status, COUNT(*) AS cnt FROM music_metadata_scrape_items WHERE batch_id=? GROUP BY status
                    """, (rs, i) -> java.util.Map.entry(rs.getString(1), rs.getLong(2)), batchId);
            long total = 0, completed = 0;
            for (var e : countEntries) {
                total += e.getValue();
                if (Set.of("AUTO_APPLIED", "REVIEW", "MANUAL_APPLIED", "FAILED").contains(e.getKey())) {
                    completed += e.getValue();
                }
            }
            batch.put("total", total);
            batch.put("completed", completed);
            batch.put("exists", true);
            progressBroadcaster.broadcastScrapeProgress(batch);
        } catch (Exception ignored) { }
    }

    private static String safe(Throwable ex) {
        String value = ex.getMessage();
        if ((value == null || value.isBlank()) && ex.getCause() != null) value = ex.getCause().getMessage();
        return ProviderJson.clean(value == null || value.isBlank() ? ex.getClass().getSimpleName() : value, 1000);
    }

    private record ItemTarget(Long songId) {}
}
