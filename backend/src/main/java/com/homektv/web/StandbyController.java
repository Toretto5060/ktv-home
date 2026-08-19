package com.homektv.web;

import com.homektv.config.AppProperties;
import com.homektv.library.SettingService;
import com.homektv.library.StandbyContentService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 待机画面控制器，处理待机内容展示、Logo 上传与图片访问。
 *
 * Standby controller for standby content display, logo upload, and image serving.
 */
@RestController
@RequestMapping("/api")
public class StandbyController {
    private final StandbyContentService service;
    private final SettingService settingService;
    private final Path dataRoot;

    public StandbyController(StandbyContentService service, SettingService settingService, AppProperties properties) {
        this.service = service;
        this.settingService = settingService;
        this.dataRoot = Path.of(properties.getDataPath());
    }

    /**
     * 获取待机画面内容。
     *
     * Get standby content.
     * @return 包含待机画面信息的 Map / map containing standby content info
     */
    @GetMapping("/standby/content")
    public Map<String, Object> content() { return service.content(); }

    /**
     * 上传待机画面 Logo 图片。
     *
     * Upload standby logo image.
     * @param file 上传的图片文件 / uploaded image file
     * @return 更新后的待机画面内容 / updated standby content
     */
    @PostMapping(value = "/admin/standby/logo", consumes = "multipart/form-data")
    public Map<String, Object> uploadLogo(@RequestPart("file") MultipartFile file) {
        service.uploadLogo(file);
        return service.content();
    }

    /**
     * 获取待机画面 Logo 图片。
     *
     * Serve standby logo image.
     * @return Logo 图片资源或 404 / logo image resource or 404
     */
    @GetMapping("/standby/logo")
    public ResponseEntity<Resource> logo() {
        Object value = settingService.getAll().get("standby_logo_path");
        if (value == null) return ResponseEntity.notFound().build();
        String rel = value.toString();
        if (rel.isBlank()) return ResponseEntity.notFound().build();
        Path file = dataRoot.resolve(rel).normalize();
        // 必须仍是 dataRoot 下的常规文件（否则空字符串 → dataRoot 目录，或配置错误指向目录，
        // 会触发 ResourceHttpMessageConverter.writeContent 报 "Is a directory"）。
        if (!file.startsWith(dataRoot.normalize()) || !Files.isRegularFile(file) || !Files.isReadable(file)) {
            return ResponseEntity.notFound().build();
        }
        String name = file.getFileName().toString().toLowerCase();
        MediaType type = name.endsWith(".png") ? MediaType.IMAGE_PNG : name.endsWith(".webp") ? MediaType.parseMediaType("image/webp") : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok().contentType(type).body(new FileSystemResource(file));
    }
}
