package com.homektv.musicsource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.domain.Song;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import com.homektv.ws.ProgressBroadcaster;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class MusicMetadataScrapeService {
    private static final Set<String> ITEM_STATUSES = Set.of(
            "PENDING", "PROCESSING", "AUTO_APPLIED", "REVIEW", "MANUAL_APPLIED", "FAILED");
    private final JdbcTemplate jdbc;
    private final SongRepository songRepository;
    private final MusicSourceConfigService configService;
    private final MusicMetadataScrapeWorker worker;
    private final MusicMetadataApplyService applyService;
    private final ObjectMapper mapper;
    private final ProgressBroadcaster progressBroadcaster;

    public MusicMetadataScrapeService(JdbcTemplate jdbc, SongRepository songRepository,
                                      MusicSourceConfigService configService, MusicMetadataScrapeWorker worker,
                                      MusicMetadataApplyService applyService, ObjectMapper mapper,
                                      ProgressBroadcaster progressBroadcaster) {
        this.jdbc = jdbc;
        this.songRepository = songRepository;
        this.configService = configService;
        this.worker = worker;
        this.applyService = applyService;
        this.mapper = mapper;
        this.progressBroadcaster = progressBroadcaster;
    }

    @Transactional
    public Map<String, Object> start(boolean all, List<Long> requestedIds, Double requestedThreshold) {
        MusicSourceConfig config = configService.getConfig();
        if (!config.enabled() || config.providers().isEmpty())
            throw new ApiException("MUSIC_SOURCES_NOT_CONFIGURED", "请先在系统设置中启用元数据刮削平台");
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM music_metadata_scrape_batches WHERE status IN ('RUNNING','PAUSED')", Integer.class);
        if (active != null && active > 0)
            throw new ApiException("METADATA_SCRAPE_ACTIVE", "已有刮削任务正在运行或暂停，请先完成当前任务");
        double threshold = requestedThreshold == null ? config.autoApplyThreshold() : requestedThreshold;
        if (threshold < 0.5 || threshold > 1)
            throw new ApiException("METADATA_SCRAPE_THRESHOLD_INVALID", "自动写入阈值必须在 0.5 到 1 之间");
        List<Song> requestedSongs;
        if (all) {
            List<Long> ids = jdbc.query("""
                    SELECT DISTINCT song_id FROM song_files WHERE valid=true ORDER BY song_id
                    """, (rs, index) -> rs.getLong(1));
            requestedSongs = songRepository.findAllById(ids);
        } else {
            List<Long> ids = requestedIds == null ? List.of() : requestedIds.stream()
                    .filter(java.util.Objects::nonNull).filter(id -> id > 0).distinct().limit(500).toList();
            if (ids.isEmpty()) throw new ApiException("METADATA_SCRAPE_EMPTY", "请选择至少一首歌曲");
            requestedSongs = songRepository.findAllById(ids);
        }
        Set<Long> alreadyScraped = alreadyScrapedSongIds(requestedSongs.stream().map(Song::getId).toList());
        List<Song> songs = requestedSongs.stream().filter(song -> !alreadyScraped.contains(song.getId())).toList();
        int skippedExisting = requestedSongs.size() - songs.size();
        if (!requestedSongs.isEmpty() && songs.isEmpty())
            throw new ApiException("METADATA_SCRAPE_ALREADY_COMPLETED", "所选歌曲均已刮削，请从 KTV 曲库的单曲“元数据刮削”入口重新刮削");
        if (songs.isEmpty()) throw new ApiException("METADATA_SCRAPE_EMPTY", "KTV 曲库中没有可刮削歌曲");
        String batchId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO music_metadata_scrape_batches(id,mode,status,auto_apply_threshold,skipped_existing,started_at,updated_at)
                VALUES (?,?, 'RUNNING',?,?,now(),now())
                """, batchId, all ? "ALL" : requestedSongs.size() == 1 ? "SINGLE" : "SELECTED", threshold, skippedExisting);
        for (Song song : songs) {
            jdbc.update("""
                    INSERT INTO music_metadata_scrape_items(batch_id,song_id,original_title,original_artist,status,updated_at)
                    VALUES (?,?,?,?, 'PENDING',now())
                    """, batchId, song.getId(), song.getTitle(), song.getArtist());
        }
        dispatchAfterCommit(batchId);
        if (progressBroadcaster != null) progressBroadcaster.broadcastScrapeProgress(details(batchId, "", 0, 20));
        return details(batchId, "", 0, 20);
    }

    public Map<String, Object> latest() {
        List<String> ids = jdbc.query("SELECT id FROM music_metadata_scrape_batches ORDER BY created_at DESC LIMIT 1",
                (rs, index) -> rs.getString(1));
        return ids.isEmpty() ? Map.of("exists", false) : details(ids.getFirst(), "", 0, 20);
    }

    public Map<String, Object> details(String batchId, String status, int page, int size) {
        requireBatch(batchId);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        String filter = status == null ? "" : status.strip().toUpperCase();
        if (!filter.isEmpty() && !ITEM_STATUSES.contains(filter))
            throw new ApiException("METADATA_SCRAPE_STATUS_INVALID", "刮削状态筛选无效");
        Map<String, Object> batch = jdbc.query("""
                SELECT id,mode,status,auto_apply_threshold,skipped_existing,created_at,started_at,finished_at,updated_at
                FROM music_metadata_scrape_batches WHERE id=?
                """, rs -> rs.next() ? batchRow(rs) : null, batchId);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String itemStatus : ITEM_STATUSES) counts.put(itemStatus, 0L);
        jdbc.query("SELECT status,COUNT(*) amount FROM music_metadata_scrape_items WHERE batch_id=? GROUP BY status",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        counts.put(rs.getString("status"), rs.getLong("amount")), batchId);
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        long completed = counts.get("AUTO_APPLIED") + counts.get("REVIEW") + counts.get("MANUAL_APPLIED") + counts.get("FAILED");
        String where = filter.isEmpty() ? "" : " AND status=?";
        Object[] countArgs = filter.isEmpty() ? new Object[]{batchId} : new Object[]{batchId, filter};
        Long filtered = jdbc.queryForObject("SELECT COUNT(*) FROM music_metadata_scrape_items WHERE batch_id=?" + where,
                Long.class, countArgs);
        List<Map<String, Object>> items = filter.isEmpty()
                ? jdbc.query("SELECT * FROM music_metadata_scrape_items WHERE batch_id=? ORDER BY updated_at DESC,id DESC LIMIT ? OFFSET ?",
                (rs, index) -> itemRow(rs), batchId, safeSize, safePage * safeSize)
                : jdbc.query("SELECT * FROM music_metadata_scrape_items WHERE batch_id=? AND status=? ORDER BY updated_at DESC,id DESC LIMIT ? OFFSET ?",
                (rs, index) -> itemRow(rs), batchId, filter, safeSize, safePage * safeSize);
        batch.put("exists", true);
        batch.put("total", total);
        batch.put("completed", completed);
        batch.put("autoApplied", counts.get("AUTO_APPLIED"));
        batch.put("manualApplied", counts.get("MANUAL_APPLIED"));
        batch.put("review", counts.get("REVIEW"));
        batch.put("failed", counts.get("FAILED"));
        batch.put("pending", counts.get("PENDING"));
        batch.put("processing", counts.get("PROCESSING"));
        batch.put("items", items);
        batch.put("page", safePage);
        batch.put("totalPages", Math.max(1, (int) Math.ceil((filtered == null ? 0 : filtered) / (double) safeSize)));
        return batch;
    }

    private void broadcastScrapeProgress(String batchId) {
        if (progressBroadcaster != null) {
            progressBroadcaster.broadcastScrapeProgress(details(batchId, "", 0, 20));
        }
    }

    public Map<String, Object> pause(String batchId) {
        requireBatch(batchId);
        jdbc.update("UPDATE music_metadata_scrape_batches SET status='PAUSED',updated_at=now() WHERE id=? AND status='RUNNING'", batchId);
        Map<String, Object> result = details(batchId, "", 0, 20);
        if (progressBroadcaster != null) progressBroadcaster.broadcastScrapeProgress(result);
        return result;
    }

    public Map<String, Object> resume(String batchId) {
        requireBatch(batchId);
        MusicSourceConfig config = configService.getConfig();
        if (!config.enabled() || config.providers().isEmpty())
            throw new ApiException("MUSIC_SOURCES_NOT_CONFIGURED", "请先启用元数据刮削平台");
        int changed = jdbc.update("UPDATE music_metadata_scrape_batches SET status='RUNNING',finished_at=NULL,updated_at=now() WHERE id=? AND status='PAUSED'", batchId);
        if (changed == 0) throw new ApiException("METADATA_SCRAPE_NOT_PAUSED", "当前任务不是暂停状态");
        jdbc.update("UPDATE music_metadata_scrape_items SET status='PENDING',updated_at=now() WHERE batch_id=? AND status='PROCESSING'", batchId);
        worker.process(batchId);
        Map<String, Object> result = details(batchId, "", 0, 20);
        if (progressBroadcaster != null) progressBroadcaster.broadcastScrapeProgress(result);
        return result;
    }

    public Map<String, Object> retryItem(String batchId, long itemId) {
        requireItem(batchId, itemId);
        jdbc.update("""
                UPDATE music_metadata_scrape_items SET status='PENDING',provider=NULL,external_id=NULL,match_score=NULL,
                result_json=NULL,error_message=NULL,started_at=NULL,finished_at=NULL,updated_at=now()
                WHERE id=? AND batch_id=? AND status IN ('REVIEW','FAILED')
                """, itemId, batchId);
        jdbc.update("UPDATE music_metadata_scrape_batches SET status='RUNNING',finished_at=NULL,updated_at=now() WHERE id=?", batchId);
        worker.process(batchId);
        Map<String, Object> result = details(batchId, "", 0, 20);
        if (progressBroadcaster != null) progressBroadcaster.broadcastScrapeProgress(result);
        return result;
    }

    public Map<String, Object> applyItem(String batchId, long itemId, Set<String> fields, Map<String, String> overrides,
                                         String requestedProvider, String requestedExternalId, boolean completeOnly) {
        Map<String, Object> item = requireItem(batchId, itemId);
        Number songIdValue = (Number) item.get("songId");
        Long songId = songIdValue == null ? null : songIdValue.longValue();
        String provider = requestedProvider == null || requestedProvider.isBlank() ? (String) item.get("provider") : requestedProvider;
        String externalId = requestedExternalId == null || requestedExternalId.isBlank() ? (String) item.get("externalId") : requestedExternalId;
        if (songId == null) throw new ApiException("METADATA_SCRAPE_NO_SONG", "该记录没有关联歌曲");
        boolean manualOnly = provider == null || provider.isBlank() || externalId == null || externalId.isBlank();
        if (completeOnly) {
            provider = null;
            externalId = null;
        } else if (manualOnly) {
            applyService.applyManual(songId, new MusicMetadataApplyService.ApplyRequest(fields, overrides));
            provider = null;
            externalId = null;
        } else {
            applyService.apply(songId, MusicProvider.parse(provider), externalId,
                    new MusicMetadataApplyService.ApplyRequest(fields, overrides));
            provider = MusicProvider.parse(provider).name();
        }
        jdbc.update("""
                UPDATE music_metadata_scrape_items SET status='MANUAL_APPLIED',provider=?,external_id=?,error_message=NULL,finished_at=now(),updated_at=now()
                WHERE id=? AND batch_id=?
                """, provider, externalId, itemId, batchId);
        return details(batchId, "", 0, 20);
    }

    private Set<Long> alreadyScrapedSongIds(List<Long> songIds) {
        if (songIds.isEmpty()) return Set.of();
        // REVIEW 状态不视为"已刮削"——用户可能只是暂存了候选还没确认，
        // 重新发起任务时应保留候选项，避免历史数据被覆盖。
        String placeholders = String.join(",", java.util.Collections.nCopies(songIds.size(), "?"));
        String sql = """
                SELECT DISTINCT song_id FROM (
                    SELECT song_id FROM music_metadata_scrape_items
                    WHERE status IN ('AUTO_APPLIED','MANUAL_APPLIED','FAILED')
                    UNION ALL
                    SELECT song_id FROM song_external_matches WHERE status='APPLIED'
                ) scraped WHERE song_id IN (%s)
                """.formatted(placeholders);
        return new LinkedHashSet<>(jdbc.query(sql, (rs, index) -> rs.getLong(1), songIds.toArray()));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedBatches() {
        jdbc.update("UPDATE music_metadata_scrape_items SET status='PENDING',updated_at=now() WHERE status='PROCESSING'");
        jdbc.update("UPDATE music_metadata_scrape_batches SET status='PAUSED',updated_at=now() WHERE status='RUNNING'");
    }

    private Map<String, Object> requireItem(String batchId, long itemId) {
        List<Map<String, Object>> rows = jdbc.query("SELECT * FROM music_metadata_scrape_items WHERE id=? AND batch_id=?",
                (rs, index) -> itemRow(rs), itemId, batchId);
        if (rows.isEmpty()) throw new ApiException("METADATA_SCRAPE_ITEM_NOT_FOUND", "刮削记录不存在");
        return rows.getFirst();
    }

    private void requireBatch(String batchId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM music_metadata_scrape_batches WHERE id=?", Integer.class, batchId);
        if (count == null || count == 0) throw new ApiException("METADATA_SCRAPE_NOT_FOUND", "刮削任务不存在");
    }

    private void dispatchAfterCommit(String batchId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            worker.process(batchId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() { worker.process(batchId); }
        });
    }

    private Map<String, Object> batchRow(ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("batchId", rs.getString("id"));
        row.put("mode", rs.getString("mode"));
        row.put("status", rs.getString("status"));
        row.put("autoApplyThreshold", rs.getDouble("auto_apply_threshold"));
        row.put("skippedExisting", rs.getInt("skipped_existing"));
        row.put("createdAt", rs.getObject("created_at", OffsetDateTime.class));
        row.put("startedAt", rs.getObject("started_at", OffsetDateTime.class));
        row.put("finishedAt", rs.getObject("finished_at", OffsetDateTime.class));
        row.put("updatedAt", rs.getObject("updated_at", OffsetDateTime.class));
        return row;
    }

    private Map<String, Object> itemRow(ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("songId", rs.getObject("song_id", Long.class));
        row.put("title", rs.getString("original_title"));
        row.put("artist", rs.getString("original_artist"));
        row.put("status", rs.getString("status"));
        row.put("provider", rs.getString("provider"));
        row.put("externalId", rs.getString("external_id"));
        row.put("score", rs.getObject("match_score", Double.class));
        row.put("error", rs.getString("error_message"));
        String result = rs.getString("result_json");
        try { row.put("result", result == null ? null : mapper.readTree(result)); }
        catch (Exception ignored) { row.put("result", null); }
        row.put("startedAt", rs.getObject("started_at", OffsetDateTime.class));
        row.put("finishedAt", rs.getObject("finished_at", OffsetDateTime.class));
        return row;
    }
}
