package com.homektv.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.domain.Song;
import com.homektv.domain.Playlist;
import com.homektv.domain.PlaylistSong;
import com.homektv.library.AssetWriter;
import com.homektv.repo.AiAnalysisTaskRepository;
import com.homektv.repo.MediaImportRecordRepository;
import com.homektv.repo.PlaylistRepository;
import com.homektv.repo.PlaylistSongRepository;
import com.homektv.repo.SongRepository;
import com.homektv.ws.ProgressBroadcaster;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiLibraryServiceTest {
    @Test
    void playlistCandidateContainsTrustedScrapedMetadata() {
        Song song = new Song();
        song.setId(36L);
        song.setTitle("刮削歌名");
        song.setArtist("刮削歌手");
        song.setLanguage("粤语");
        song.setVocalForm("对唱");
        song.setAlbum("平台专辑");
        song.setReleaseDate("1999-01-01");
        song.setAliases(new String[]{"歌曲别名"});
        song.setTags(new String[]{"聚会"});
        song.setAiGenres(new String[]{"流行"});
        song.setAiThemes(new String[]{"怀旧"});
        song.setAiEra("90年代");
        song.setDurationMs(210_000);
        song.setMetadataProvenance("""
                {"title":{"source":"QQ","externalId":"qq-36","trusted":true},
                 "album":{"source":"NETEASE","externalId":"ne-36","trusted":true},
                 "aliases":{"source":"UNTRUSTED","trusted":false}}
                """);

        Map<String, Object> candidate = service().playlistCandidate(song);

        assertThat(candidate).containsEntry("id", 36L)
                .containsEntry("album", "平台专辑")
                .containsEntry("releaseDate", "1999-01-01")
                .containsEntry("metadataScraped", true)
                .containsEntry("durationSeconds", 210L);
        assertThat(candidate.get("aliases")).isEqualTo(java.util.List.of("歌曲别名"));
        assertThat(candidate.get("genres")).isEqualTo(java.util.List.of("流行"));
        assertThat(candidate.get("themes")).isEqualTo(java.util.List.of("怀旧"));
        assertThat(candidate.get("metadataSources")).isEqualTo(Map.of("title", "QQ", "album", "NETEASE"));
    }

    @Test
    void playlistPreviewAcceptsFewerSongsAndCommonNestedResponse() throws Exception {
        SongRepository songRepository = mock(SongRepository.class);
        OpenAiCompatibleClient aiClient = mock(OpenAiCompatibleClient.class);
        ObjectMapper objectMapper = new ObjectMapper();
        Song first = song(1L, "第一首", "歌手甲");
        Song second = song(2L, "第二首", "歌手乙");
        Song third = song(3L, "第三首", "歌手丙");
        when(songRepository.findAll()).thenReturn(List.of(first, second, third));
        when(aiClient.completeJsonPromptOnly(anyString(), anyString(), anyString(), anyInt())).thenReturn(
                objectMapper.readTree("{\"intent\":\"轻松聚会\",\"maxSongs\":100}"),
                objectMapper.readTree("{\"playlist\":{\"playlistName\":\"少而精\",\"songs\":[{\"songId\":1},{\"id\":2},{\"id\":999}]}}"));

        Map<String, Object> result = service(songRepository, mock(PlaylistRepository.class),
                mock(PlaylistSongRepository.class), aiClient, objectMapper).previewPlaylist("轻松聚会", 500);

        assertThat(result).containsEntry("name", "少而精")
                .containsEntry("limit", 100)
                .containsEntry("selectedCount", 2);
        assertThat(result.get("songIds")).isEqualTo(List.of(1L, 2L));
    }

    @Test
    void addingSongBeyondPlaylistLimitIsRejected() {
        SongRepository songRepository = mock(SongRepository.class);
        PlaylistRepository playlistRepository = mock(PlaylistRepository.class);
        PlaylistSongRepository playlistSongRepository = mock(PlaylistSongRepository.class);
        Playlist playlist = new Playlist();
        when(playlistRepository.findById(9L)).thenReturn(Optional.of(playlist));
        when(songRepository.findById(101L)).thenReturn(Optional.of(song(101L, "新歌", "歌手")));
        when(playlistSongRepository.findByPlaylistIdOrderBySortOrder(9L)).thenReturn(
                java.util.stream.LongStream.rangeClosed(1, 100).mapToObj(id -> {
                    PlaylistSong item = new PlaylistSong();
                    item.setSongId(id);
                    item.setPlaylistId(9L);
                    return item;
                }).toList());

        AiLibraryService service = service(songRepository, playlistRepository, playlistSongRepository,
                mock(OpenAiCompatibleClient.class), new ObjectMapper());

        assertThatThrownBy(() -> service.addPlaylistSong(9L, 101L))
                .hasMessageContaining("最多包含 100 首");
    }

    private Song song(long id, String title, String artist) {
        Song song = new Song();
        song.setId(id);
        song.setTitle(title);
        song.setArtist(artist);
        return song;
    }

    private AiLibraryService service() {
        return service(mock(SongRepository.class), mock(PlaylistRepository.class),
                mock(PlaylistSongRepository.class), mock(OpenAiCompatibleClient.class), new ObjectMapper());
    }

    private AiLibraryService service(SongRepository songRepository, PlaylistRepository playlistRepository,
                                     PlaylistSongRepository playlistSongRepository,
                                     OpenAiCompatibleClient aiClient, ObjectMapper objectMapper) {
        AiConfigService configService = mock(AiConfigService.class);
        when(configService.resolve()).thenReturn(new AiConfigService.ResolvedConfig(true, "http://ai.test/v1",
                "bulk-model", "", 30, 0.97, 0.92, AiConfigService.JsonMode.AUTO, 2, 1, "secret"));
        return new AiLibraryService(mock(AiAnalysisTaskRepository.class), songRepository,
                playlistRepository, playlistSongRepository, mock(AiAnalysisWorker.class),
                objectMapper, configService, mock(AssetWriter.class),
                mock(AiClassificationApplier.class), aiClient, mock(MediaImportRecordRepository.class),
                mock(ProgressBroadcaster.class));
    }
}
