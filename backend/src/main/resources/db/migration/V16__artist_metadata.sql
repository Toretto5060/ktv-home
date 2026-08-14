-- 歌手元数据表：头像本地路径、性别、来源平台，按歌手名去重
CREATE TABLE IF NOT EXISTS artist_metadata (
    artist_name  VARCHAR(200) NOT NULL PRIMARY KEY,
    avatar_url   VARCHAR(1000),
    gender       VARCHAR(20)  NOT NULL DEFAULT '未知',
    source       VARCHAR(20),
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_artist_gender   ON artist_metadata(gender);
CREATE INDEX IF NOT EXISTS idx_artist_updated ON artist_metadata(updated_at);
