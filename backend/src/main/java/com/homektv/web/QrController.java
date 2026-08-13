package com.homektv.web;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import javax.imageio.ImageIO;
import jakarta.servlet.http.HttpServletRequest;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 二维码生成（P1.19，详设§11.1）。
 * 内容为 H5 点歌地址，TV 可通过 base_url 参数传入当前连接的 base，
 * 让扫码内容与 TV 当前连接保持一致（内网/公网自动跟随）。未传 base_url
 * 时回落使用请求 host。
 * <p>
 * QR code generation (P1.19, detailed design §11.1).
 * The TV may pass its current H5 base via base_url so the scanned URL
 * matches whatever the TV is currently connected to. Falls back to the
 * request host when base_url is absent.
 */
@RestController
@RequestMapping("/api")
public class QrController {

    private static final Logger log = LoggerFactory.getLogger(QrController.class);

    private final ServerAddressService addressService;

    public QrController(ServerAddressService addressService) {
        this.addressService = addressService;
    }

    /**
     * 返回 PNG 二维码。
     *
     * Returns a PNG code image.
     * @param room    房间号，默认 default / room identifier, defaults to "default"
     * @param size    边长像素，默认 480，钳制 [120, 1080] / side length in pixels, defaults to 480, clamped to [120, 1080]
     * @param content 优先使用：TV 端已经拼好的完整扫码字符串。
     *                传过来后端直接编码，不做任何覆盖。TV 端能用这个值完全控制手机扫码的内容，
     *                跟它自己当前连接的 base 主机的"对外可访问 URL"严格一致 —— 跟反代/Host 头无关。
     *                <p>Preferred: the TV already composed the full scanned string.
     *                Encoded as-is. Lets the TV pin the content to whichever URL is actually
     *                reachable from the phone, independent of any reverse proxy / Host header.
     * @param baseUrl 兼容参数：当 content 缺失时使用，和之前的语义一致。
     *                <p>Legacy: when content is missing, resolve via baseUrl the same way as before.
     * @return PNG 格式的二维码图片字节流 / PNG-format QR code image byte stream
     */
    @GetMapping(value = "/qr", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> qr(
            @RequestParam(defaultValue = "default") String room,
            @RequestParam(defaultValue = "480") int size,
            @RequestParam(required = false) String content,
            @RequestParam(required = false) String baseUrl,
            HttpServletRequest request) throws Exception {

        int px = Math.max(120, Math.min(1080, size));
        String resolved;
        if (content != null && !content.isBlank()) {
            // TV 已拼好，直接用 —— 任何 Host/反代差异都不会影响最终内容。
            // <p>TV pre-composed; use as-is — immune to any Host/proxy drift.
            resolved = content;
        } else if (baseUrl != null && !baseUrl.isBlank()) {
            resolved = addressService.h5Url(room, baseUrl);
        } else {
            resolved = addressService.h5Url(room, request);
        }
        if (resolved == null || resolved.isBlank()) {
            // 空内容会让 ZXing 抛 IllegalArgumentException；显式 400 让 TV 端能区分失败原因。
            log.warn("qr content empty: room={} size={} content={} baseUrl={}", room, px, content, baseUrl);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "QR content is empty");
        }

        // 关键诊断：把 TV 实际发来的 content 和最终 resolved 一起记下。
        // <p>Diagnostic: log both TV-supplied content and the resolved string.
        log.info("qr request: content={} baseUrl={} room={} -> resolved={}", content, baseUrl, room, resolved);

        String text = resolved;

        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, 1);

        BitMatrix matrix = new QRCodeWriter()
                .encode(text, BarcodeFormat.QR_CODE, px, px, hints);

        int black = 0xFF000000; // BufferedImage needs alpha
        int white = 0xFFFFFFFF;
        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[px];
        for (int y = 0; y < px; y++) {
            for (int x = 0; x < px; x++) {
                row[x] = matrix.get(x, y) ? black : white;
            }
            img.setRGB(0, y, px, 1, row, 0, px);
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", bos);

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                // 地址可能变（DHCP/手填），缓存短一些
                .cacheControl(CacheControl.maxAge(30, TimeUnit.SECONDS))
                .body(bos.toByteArray());
    }
}
