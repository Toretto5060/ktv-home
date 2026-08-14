package com.homektv.web;

import com.homektv.config.AppProperties;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 歌手头像资源 API。
 * 头像文件保存在 data/artists/ 目录，按歌手名 safe 编码为 .jpg。
 * 歌手名中的特殊字符在存储时被替换为下划线，URL 中使用 URLEncode。
 */
@RestController
@RequestMapping("/api/artist-avatar")
public class ArtistAvatarController {
    private final Path artistsDir;

    public ArtistAvatarController(AppProperties props) {
        this.artistsDir = Path.of(props.getDataPath(), "artists");
    }

    @GetMapping("/{name}")
    public ResponseEntity<Resource> avatar(@PathVariable String name) {
        String decoded;
        try { decoded = java.net.URLDecoder.decode(name, java.nio.charset.StandardCharsets.UTF_8); }
        catch (Exception e) { decoded = name; }

        // 优先直接查找
        Path file = artistsDir.resolve(safeFileName(decoded) + ".jpg");
        if (Files.isRegularFile(file) && Files.isReadable(file)) {
            return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG)
                    .body(new FileSystemResource(file));
        }
        // 备用：尝试各种已知编码（处理特殊字符被替换的情况）
        String[] candidates = {
                decoded.replace(" ", "_"),
                decoded.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fff_-]", "_"),
                decoded.replace("_", "__"),
        };
        for (String cand : candidates) {
            if (cand.equals(decoded)) continue;
            Path alt = artistsDir.resolve(cand + ".jpg");
            if (Files.isRegularFile(alt) && Files.isReadable(alt)) {
                return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG)
                        .body(new FileSystemResource(alt));
            }
        }
        return ResponseEntity.notFound().build();
    }

    private static String safeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fff_-]", "_");
    }
}
