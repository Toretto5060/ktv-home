package com.homektv.web;

import com.homektv.library.ArtistLibraryService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/artists")
public class ArtistLibraryController {
    private final ArtistLibraryService service;

    public ArtistLibraryController(ArtistLibraryService service) { this.service = service; }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) String gender,
                                    @RequestParam(required = false) Boolean reviewed,
                                    @RequestParam(required = false) String avatar,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        Boolean avatarBool = null;
        if (avatar != null && !avatar.isBlank()) {
            if ("true".equalsIgnoreCase(avatar)) avatarBool = Boolean.TRUE;
            else if ("false".equalsIgnoreCase(avatar)) avatarBool = Boolean.FALSE;
        }
        return service.list(keyword, gender, reviewed, avatarBool, page, size);
    }

    /** 手动刮削单个歌手头像。 */
    @PostMapping("/scrape")
    public Map<String, Object> scrape(@RequestBody ScrapeRequest request) {
        return service.scrape(request.artist());
    }

    /** 批量刮削歌手头像。 */
    @PostMapping("/scrape-batch")
    public List<Map<String, Object>> scrapeBatch(@RequestBody ScrapeBatchRequest request) {
        List<String> artists = request == null || request.artists() == null ? List.of() : request.artists();
        return service.scrapeBatch(artists);
    }

    /** 更新歌手信息（性别 / 头像路径）。 */
    @PutMapping
    public Map<String, Object> update(@RequestBody UpdateRequest request) {
        return service.update(request.name(), request.gender(), request.avatarUrl());
    }

    /** 同步：删除 songs 表中不存在的歌手元数据。 */
    @PostMapping("/sync")
    public Map<String, Object> sync() {
        int deleted = service.syncWithSongs();
        return Map.of("deleted", deleted);
    }

    public record ScrapeRequest(String artist) {}
    public record ScrapeBatchRequest(List<String> artists) {}
    public record UpdateRequest(String name, String gender, String avatarUrl) {}
}
