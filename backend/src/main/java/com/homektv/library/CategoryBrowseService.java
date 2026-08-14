package com.homektv.library;

import com.homektv.domain.Song;
import com.homektv.repo.ArtistMetadataRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.dto.SongDto;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CategoryBrowseService {
    private final SongRepository songRepository;
    private final ArtistMetadataRepository artistMetaRepo;

    public CategoryBrowseService(SongRepository songRepository, ArtistMetadataRepository artistMetaRepo) {
        this.songRepository = songRepository;
        this.artistMetaRepo = artistMetaRepo;
    }

    public List<Map<String, Object>> artists() {
        // 预加载歌手头像映射
        Map<String, String> avatarMap = new HashMap<>();
        artistMetaRepo.findAll().forEach(a -> {
            if (a.getAvatarUrl() != null && !a.getAvatarUrl().isBlank()) {
                try {
                    avatarMap.put(a.getArtistName(),
                            "/api/artist-avatar/" + URLEncoder.encode(a.getArtistName(), StandardCharsets.UTF_8));
                } catch (Exception ignored) { }
            }
        });
        return validSongs().stream()
                .collect(Collectors.groupingBy(Song::getArtist))
                .entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, List<Song>>>comparingInt(entry -> entry.getValue().size()).reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(entry -> {
                    String name = entry.getKey();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", name);
                    m.put("avatarUrl", avatarMap.getOrDefault(name, ""));
                    m.put("initial", artistInitial(entry.getValue().get(0)));
                    m.put("gender", dominantArtistGender(entry.getValue()));
                    m.put("songCount", entry.getValue().size());
                    return m;
                })
                .toList();
    }

    public List<Map<String, Object>> languages() {
        return counts(validSongs(), Song::getLanguage);
    }

    public List<Map<String, Object>> tags() {
        Map<String, Long> counts = new HashMap<>();
        for (Song song : validSongs()) {
            LinkedHashSet<String> values = new LinkedHashSet<>();
            if (song.getTags() != null) values.addAll(Arrays.asList(song.getTags()));
            if (song.getAiGenres() != null) values.addAll(Arrays.asList(song.getAiGenres()));
            if (song.getAiThemes() != null) values.addAll(Arrays.asList(song.getAiThemes()));
            values.stream().filter(value -> value != null && !value.isBlank())
                    .forEach(value -> counts.merge(value, 1L, Long::sum));
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(100)
                .map(entry -> Map.<String, Object>of("name", entry.getKey(), "songCount", entry.getValue()))
                .toList();
    }

    public List<SongDto> songs(String artist, String artistGender, String language, String tag, String vocalForm, String sort, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        Comparator<Song> comparator = "title".equalsIgnoreCase(sort)
                ? Comparator.comparing(Song::getTitle, String.CASE_INSENSITIVE_ORDER)
                : "new".equalsIgnoreCase(sort)
                    ? Comparator.comparing(Song::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                    : Comparator.comparingInt(Song::getPlayCount).reversed().thenComparing(Song::getTitle);
        return validSongs().stream()
                .filter(song -> blank(artist) || song.getArtist().equalsIgnoreCase(artist))
                .filter(song -> blank(artistGender) || artistGender.equalsIgnoreCase(song.getArtistGender()))
                .filter(song -> blank(language) || song.getLanguage().equalsIgnoreCase(language))
                .filter(song -> blank(vocalForm) || vocalForm.equalsIgnoreCase(song.getAiVocalForm()))
                .filter(song -> blank(tag) || contains(song.getTags(), tag) || contains(song.getAiGenres(), tag) || contains(song.getAiThemes(), tag))
                .sorted(comparator)
                .limit(safeLimit)
                .map(SongDto::from)
                .toList();
    }

    private List<Song> validSongs() {
        return songRepository.findAll().stream().filter(song -> "ok".equals(song.getStatus())).toList();
    }

    private List<Map<String, Object>> counts(List<Song> songs, Function<Song, String> classifier) {
        return songs.stream().map(classifier).filter(value -> value != null && !value.isBlank())
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(entry -> Map.<String, Object>of("name", entry.getKey(), "songCount", entry.getValue())).toList();
    }

    private String artistInitial(Song song) {
        return song.getArtistInit() == null || song.getArtistInit().isBlank() ? "#" : song.getArtistInit().substring(0, 1).toUpperCase();
    }

    static String dominantArtistGender(List<Song> songs) {
        return songs.stream().map(Song::getArtistGender)
                .filter(value -> value != null && !value.isBlank() && !"未知".equals(value))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("未知");
    }

    private boolean contains(String[] values, String expected) {
        return values != null && Arrays.stream(values).anyMatch(expected::equalsIgnoreCase);
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
}
