package com.homektv.web.dto;

import com.homektv.domain.Song;
import com.homektv.domain.SongFile;

/**
 * 管理后台歌曲数据传输对象（DTO），用于封装歌曲及其关联文件的展示信息。
 *
 * Admin song data transfer object (DTO), encapsulating song metadata
 * along with its associated file information for admin panel display.
 */
public record AdminSongDto(
        Long id,
        String title,
        String artist,
        String album,
        String releaseDate,
        String[] aliases,
        String coverPath,
        String[] metadataLocks,
        String language,
        String artistGender,
        String[] tags,
        String mediaType,
        String lyricType,
        int durationMs,
        int playCount,
        String filePath,
        String importSource,
        boolean scraped
) {
    /**
     * 根据歌曲实体和文件实体构建管理后台歌曲 DTO，自动判断导入来源。
     * scraped 默认为 false，适用于不需要刮削状态的场景。
     */
    public static AdminSongDto from(Song song, SongFile file) {
        return from(song, file, false);
    }

    /**
     * 根据歌曲实体、文件实体和刮削状态构建管理后台歌曲 DTO。
     *
     * @param song 歌曲实体
     * @param file 歌曲文件实体，可为 null
     * @param scraped 是否有刮削记录（包含 AUTO_APPLIED / MANUAL_APPLIED / REVIEW 任一状态）
     */
    public static AdminSongDto from(Song song, SongFile file, boolean scraped) {
        String source = file == null || file.getSourcePath() == null
                ? "UNKNOWN"
                : file.isTranscodeRequired() ? "TRANSCODED" : "COPIED";
        return new AdminSongDto(song.getId(), song.getTitle(), song.getArtist(), song.getAlbum(), song.getReleaseDate(),
                song.getAliases(), song.getCoverPath(), song.getMetadataLocks(), song.getLanguage(), song.getArtistGender(), song.getTags(),
                song.getMediaType(), song.getLyricType(), song.getDurationMs(), song.getPlayCount(),
                file == null ? null : file.getFilePath(), source, scraped);
    }
}
