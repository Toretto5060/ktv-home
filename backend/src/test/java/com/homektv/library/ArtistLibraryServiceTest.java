package com.homektv.library;

import com.homektv.domain.ArtistMetadata;
import com.homektv.domain.Song;
import com.homektv.musicsource.MusicSourceConfig;
import com.homektv.musicsource.MusicSourceConfigService;
import com.homektv.repo.ArtistMetadataRepository;
import com.homektv.repo.SongRepository;
import com.homektv.ws.ProgressBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ArtistLibraryServiceTest {
    private ArtistMetadataRepository metaRepo;
    private SongRepository songRepo;
    private ArtistScraperService scraper;
    private MusicSourceConfigService configService;
    private ArtistLibraryService service;

    @BeforeEach
    void setUp() {
        metaRepo = mock(ArtistMetadataRepository.class);
        songRepo = mock(SongRepository.class);
        scraper = mock(ArtistScraperService.class);
        configService = mock(MusicSourceConfigService.class);
        MusicSourceConfig config = new MusicSourceConfig(true, Set.of(), 20, 5, 6, 1, 0, 0.95);
        when(configService.getConfig()).thenReturn(config);
        service = new ArtistLibraryService(metaRepo, songRepo, scraper, configService,
                mock(ProgressBroadcaster.class));
    }

    @Test
    void groupsSameNameAndReturnsAtMostThreeRepresentativeSongs() {
        List<Song> library = java.util.stream.IntStream.rangeClosed(1, 7)
                .mapToObj(index -> song((long) index, "同名歌手", "歌曲" + index, "未知", index))
                .toList();
        when(songRepo.findAll()).thenReturn(library);
        when(metaRepo.findAll()).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = service.list("同名", null, null, null, 0, 100);
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");

        assertThat(content).hasSize(1);
        assertThat(content.get(0)).containsEntry("name", "同名歌手").containsEntry("songCount", 7);
        assertThat((List<?>) content.get(0).get("songs")).hasSize(3);
    }

    @Test
    void filtersByGender() {
        Song male = song(1L, "歌手", "歌曲一", "男歌手", 1);
        Song female = song(2L, "歌手", "歌曲二", "女歌手", 2);
        when(songRepo.findAll()).thenReturn(List.of(male, female));
        when(metaRepo.findAll()).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = service.list(null, "男歌手", null, null, 0, 100);
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");

        assertThat(content).hasSize(1);
        assertThat(content.get(0)).containsEntry("name", "歌手").containsEntry("gender", "男歌手");
    }

    @Test
    void applyUpdatesGenderAndReturnsCorrectMap() {
        Song sample = song(1L, "歌手", "歌曲", "未知", 1);
        when(songRepo.findAll()).thenReturn(List.of(sample));
        when(metaRepo.findById("歌手")).thenReturn(java.util.Optional.of(new ArtistMetadata()));

        Map<String, Object> result = service.apply("歌手", "男歌手");

        assertThat(result).containsEntry("name", "歌手").containsEntry("gender", "男歌手");
        verify(metaRepo).save(any(ArtistMetadata.class));
    }

    @Test
    void scrapeBatchFetchesAvatarAndSaves() {
        when(metaRepo.findById("歌手")).thenReturn(java.util.Optional.of(new ArtistMetadata()));
        when(scraper.findAvatarUrl("歌手")).thenReturn(new ArtistScraperService.ScrapeResult("https://example.com/avatar.jpg", null));
        when(scraper.downloadAndSave("歌手", "https://example.com/avatar.jpg")).thenReturn("avatars/歌手.jpg");
        when(songRepo.findAll()).thenReturn(List.of(song(1L, "歌手", "歌曲", "未知", 1)));

        List<Map<String, Object>> results = service.scrapeBatch(List.of("歌手"));

        assertThat(results).hasSize(1);
        assertThat((String) results.get(0).get("avatarUrl")).isNotEmpty();
        verify(metaRepo).save(any(ArtistMetadata.class));
    }

    @Test
    void scrapeBatchFallsBackToEmptyAvatarOnFailure() {
        when(metaRepo.findById("歌手")).thenReturn(java.util.Optional.of(new ArtistMetadata()));
        when(scraper.findAvatarUrl("歌手")).thenReturn(null);
        when(songRepo.findAll()).thenReturn(List.of(song(1L, "歌手", "歌曲", "未知", 1)));

        List<Map<String, Object>> results = service.scrapeBatch(List.of("歌手"));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("avatarUrl")).isEqualTo("");
    }

    @Test
    void syncWithSongsDeletesStaleMetadata() {
        ArtistMetadata stale = new ArtistMetadata();
        stale.setArtistName("已删除歌手");
        when(songRepo.findAll()).thenReturn(List.of(song(1L, "当前歌手", "歌曲", "未知", 1)));
        when(metaRepo.findAll()).thenReturn(List.of(stale));

        int deleted = service.syncWithSongs();

        assertThat(deleted).isEqualTo(1);
        verify(metaRepo).deleteAll(List.of(stale));
    }

    @Test
    void statsReturnsCorrectCounts() {
        Song male = song(1L, "歌手甲", "歌曲一", "男歌手", 1);
        Song female = song(2L, "歌手乙", "歌曲二", "女歌手", 2);
        when(songRepo.findAll()).thenReturn(List.of(male, female));
        when(metaRepo.findAll()).thenReturn(List.of());

        Map<String, Object> result = service.stats();

        assertThat(result).containsEntry("total", 2L);
        assertThat(result).containsEntry("hasAvatar", 0L);
        assertThat(result).containsEntry("noAvatar", 2L);
    }

    private Song song(Long id, String artist, String title, String gender, int playCount) {
        Song song = new Song();
        song.setId(id);
        song.setArtist(artist);
        song.setTitle(title);
        song.setArtistGender(gender);
        song.setStatus("ok");
        song.setLanguage("国语");
        song.setMediaType("KTV_VIDEO");
        song.setPlayCount(playCount);
        return song;
    }
}
