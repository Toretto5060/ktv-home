package com.homektv.repo;

import com.homektv.domain.Song;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;

/**
 * 歌曲数据访问层，负责 {@link Song} 实体的数据库操作。
 *
 * Song data access layer, responsible for database operations on the {@link Song} entity.
 */
public interface SongRepository extends JpaRepository<Song, Long> {

    Optional<Song> findByFingerprint(String fingerprint);

    List<Song> findTop10ByTitleIgnoreCase(String title);

    long countByMediaType(String mediaType);

    long countByStatus(String status);

    java.util.List<Song> findTop50ByOrderByCreatedAtDesc();

    org.springframework.data.domain.Page<Song> findByMediaType(String mediaType, org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<Song> findByStatus(String status, org.springframework.data.domain.Pageable pageable);

    @Query(value = """
            SELECT song.* FROM songs song
            WHERE song.id IN (
                SELECT sf.song_id FROM song_files sf WHERE sf.valid = true
            )
              AND (:keyword = ''
                OR LOWER(song.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(song.artist) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(COALESCE(song.title_py,'')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(COALESCE(song.title_init,'')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(COALESCE(song.artist_py,'')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(COALESCE(song.artist_init,'')) LIKE LOWER(CONCAT('%', :keyword, '%')))
              AND (:type = ''
                OR (:type = 'unrecognized' AND song.status = 'unrecognized')
                OR (:type <> 'unrecognized' AND song.media_type = :type))
              AND (:source = ''
                OR (:source = 'UNKNOWN' AND EXISTS (
                    SELECT 1 FROM song_files f WHERE f.song_id = song.id AND f.valid = true AND f.source_path IS NULL))
                OR (:source = 'COPIED' AND EXISTS (
                    SELECT 1 FROM song_files f WHERE f.song_id = song.id AND f.valid = true
                      AND f.source_path IS NOT NULL AND f.transcode_required = false))
                OR (:source = 'TRANSCODED' AND EXISTS (
                    SELECT 1 FROM song_files f WHERE f.song_id = song.id AND f.valid = true
                      AND f.source_path IS NOT NULL AND f.transcode_required = true)))
              AND (:scraped = '' OR :scraped IS NULL
                OR (:scraped = 'true' AND EXISTS (
                    SELECT 1 FROM music_metadata_scrape_items i
                    WHERE i.song_id = song.id AND i.status IN ('AUTO_APPLIED','MANUAL_APPLIED','REVIEW')))
                OR (:scraped = 'false' AND NOT EXISTS (
                    SELECT 1 FROM music_metadata_scrape_items i
                    WHERE i.song_id = song.id AND i.status IN ('AUTO_APPLIED','MANUAL_APPLIED','REVIEW'))))
            ORDER BY song.id DESC
            """, countQuery = """
            SELECT COUNT(*) FROM songs song
            WHERE song.id IN (
                SELECT sf.song_id FROM song_files sf WHERE sf.valid = true
            )
              AND (:keyword = ''
                OR LOWER(song.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(song.artist) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(COALESCE(song.title_py,'')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(COALESCE(song.title_init,'')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(COALESCE(song.artist_py,'')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(COALESCE(song.artist_init,'')) LIKE LOWER(CONCAT('%', :keyword, '%')))
              AND (:type = ''
                OR (:type = 'unrecognized' AND song.status = 'unrecognized')
                OR (:type <> 'unrecognized' AND song.media_type = :type))
              AND (:source = ''
                OR (:source = 'UNKNOWN' AND EXISTS (
                    SELECT 1 FROM song_files f WHERE f.song_id = song.id AND f.valid = true AND f.source_path IS NULL))
                OR (:source = 'COPIED' AND EXISTS (
                    SELECT 1 FROM song_files f WHERE f.song_id = song.id AND f.valid = true
                      AND f.source_path IS NOT NULL AND f.transcode_required = false))
                OR (:source = 'TRANSCODED' AND EXISTS (
                    SELECT 1 FROM song_files f WHERE f.song_id = song.id AND f.valid = true
                      AND f.source_path IS NOT NULL AND f.transcode_required = true)))
              AND (:scraped = '' OR :scraped IS NULL
                OR (:scraped = 'true' AND EXISTS (
                    SELECT 1 FROM music_metadata_scrape_items i
                    WHERE i.song_id = song.id AND i.status IN ('AUTO_APPLIED','MANUAL_APPLIED','REVIEW')))
                OR (:scraped = 'false' AND NOT EXISTS (
                    SELECT 1 FROM music_metadata_scrape_items i
                    WHERE i.song_id = song.id AND i.status IN ('AUTO_APPLIED','MANUAL_APPLIED','REVIEW'))))
            """, nativeQuery = true)
    Page<Song> searchAdminSongs(@Param("keyword") String keyword,
                                @Param("type") String type,
                                @Param("source") String source,
                                @Param("scraped") String scraped,
                                Pageable pageable);
}
