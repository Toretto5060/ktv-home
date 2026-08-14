-- 将 music_metadata_scrape_items.song_id 外键从 SET NULL 改为 CASCADE
-- 删除歌曲时自动清理关联的刮削记录，不再留孤儿 item
ALTER TABLE music_metadata_scrape_items
    DROP CONSTRAINT IF EXISTS fk_metadata_scrape_song,
    ADD CONSTRAINT fk_metadata_scrape_song
        FOREIGN KEY (song_id) REFERENCES songs(id) ON DELETE CASCADE;
