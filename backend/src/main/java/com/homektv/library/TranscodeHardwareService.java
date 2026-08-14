package com.homektv.library;

import com.homektv.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Service
public class TranscodeHardwareService {

    private final Path driRoot;
    private final Path drmSysRoot;
    private final Path rkmppDevice;
    private final String ffmpegPath;

    public TranscodeHardwareService(
            @Value("${app.transcode.dri-path:/dev/dri}") String driPath,
            @Value("${app.transcode.drm-sys-path:/sys/class/drm}") String drmSysPath,
            @Value("${app.transcode.rkmpp-device-path:/dev/mpp_service}") String rkmppDevicePath,
            @Value("${app.transcode.ffmpeg-path:ffmpeg}") String ffmpegPath) {
        this.driRoot = Path.of(driPath);
        this.drmSysRoot = Path.of(drmSysPath);
        this.rkmppDevice = Path.of(rkmppDevicePath);
        this.ffmpegPath = ffmpegPath;
    }

    public HardwareStatus detect() {
        HardwareStatus rockchip = detectRockchip();
        if (rockchip != null) return rockchip;
        if (!Files.isDirectory(driRoot)) {
            return HardwareStatus.unavailable("未找到 /dev/dri 或 /dev/mpp_service 硬件加速设备");
        }
        List<Path> devices;
        try (var stream = Files.list(driRoot)) {
            devices = stream.filter(path -> path.getFileName().toString().startsWith("renderD"))
                    .sorted().toList();
        } catch (IOException e) {
            return HardwareStatus.unavailable("无法读取硬件加速设备：" + e.getMessage());
        }
        if (devices.isEmpty()) return HardwareStatus.unavailable("未找到 VAAPI render 设备");

        String lastFailure = null;
        for (Path device : devices) {
            String vendor = readVendor(device);
            if (vendor == null) continue;
            List<String> codecs = new ArrayList<>();
            String[] failure = new String[1];
            ProbeResult h264 = smokeTest(device, "h264_vaapi");
            if (h264.success()) {
                codecs.add("h264");
            } else {
                failure[0] = "h264_vaapi";
            }
            ProbeResult hevc = smokeTest(device, "hevc_vaapi");
            if (hevc.success()) {
                codecs.add("hevc");
            } else if (failure[0] == null) {
                failure[0] = "hevc_vaapi";
            }
            if (!codecs.isEmpty()) {
                return new HardwareStatus(true, vendor, device.toString(), List.copyOf(codecs), "vaapi", null);
            }
            lastFailure = failure[0] + "@" + device.getFileName() + ": " + trimLog(firstFailure(h264, hevc));
        }
        if (lastFailure == null) {
            return HardwareStatus.unavailable("检测到 Intel/AMD 设备，但 FFmpeg VAAPI 编码测试失败");
        }
        return HardwareStatus.unavailable("检测到 Intel/AMD 设备，但 FFmpeg VAAPI 编码测试失败：" + lastFailure);
    }

    public HardwareStatus requireAvailable(String codec) {
        HardwareStatus status = detect();
        if (!status.available()) {
            throw new ApiException("HARDWARE_ACCELERATOR_NOT_FOUND", status.reason());
        }
        if (!status.supportedCodecs().contains(codec)) {
            throw new ApiException("HARDWARE_CODEC_UNAVAILABLE",
                    status.vendor() + " 设备不支持 " + codec.toUpperCase(Locale.ROOT) + " 硬件编码");
        }
        return status;
    }

    private String readVendor(Path device) {
        Path vendorFile = drmSysRoot.resolve(device.getFileName()).resolve("device/vendor");
        try {
            String value = Files.readString(vendorFile).trim().toLowerCase(Locale.ROOT);
            return switch (value) {
                case "0x8086" -> "Intel VAAPI";
                case "0x1002" -> "AMD VAAPI";
                default -> null;
            };
        } catch (IOException e) {
            return null;
        }
    }

    private HardwareStatus detectRockchip() {
        if (!Files.exists(rkmppDevice)) return null;
        if (!Files.isReadable(rkmppDevice) || !Files.isWritable(rkmppDevice)) {
            return HardwareStatus.unavailable("检测到瑞芯微 MPP 设备，但应用没有访问权限：" + rkmppDevice);
        }
        List<String> codecs = new ArrayList<>();
        ProbeResult h264 = smokeTestRkmpp("h264_rkmpp");
        if (h264.success()) codecs.add("h264");
        ProbeResult hevc = smokeTestRkmpp("hevc_rkmpp");
        if (hevc.success()) codecs.add("hevc");
        if (!codecs.isEmpty()) {
            return new HardwareStatus(true, "Rockchip RK MPP", rkmppDevice.toString(),
                    List.copyOf(codecs), "rkmpp", null);
        }
        String failure = trimLog(firstFailure(h264, hevc));
        String message = "检测到瑞芯微 MPP 设备，但当前 FFmpeg 未提供可用的 h264_rkmpp/hevc_rkmpp 编码器";
        if (!failure.isEmpty()) message += "：" + failure;
        return HardwareStatus.unavailable(message);
    }

    private ProbeResult smokeTest(Path device, String encoder) {
        ProcessResult result = run(List.of(ffmpegPath, "-hide_banner", "-loglevel", "error",
                "-vaapi_device", device.toString(), "-f", "lavfi", "-i", "color=size=128x128:rate=1",
                "-vf", "format=nv12,hwupload", "-frames:v", "1", "-c:v", encoder, "-f", "null", "-"));
        return new ProbeResult(result.success(), result.output());
    }

    private ProbeResult smokeTestRkmpp(String encoder) {
        ProcessResult result = run(List.of(ffmpegPath, "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "color=size=128x128:rate=1", "-frames:v", "1",
                "-c:v", encoder, "-f", "null", "-"));
        return new ProbeResult(result.success(), result.output());
    }

    private static String firstFailure(ProbeResult a, ProbeResult b) {
        if (!a.success()) return a.output();
        if (!b.success()) return b.output();
        return "";
    }

    private static String trimLog(String log) {
        if (log == null) return "";
        String trimmed = log.trim();
        if (trimmed.length() > 240) trimmed = trimmed.substring(0, 240) + "...";
        return trimmed.replace('\n', ' ');
    }

    public record ProcessResult(boolean success, String output) {}

    public record ProbeResult(boolean success, String output) {}

    ProcessResult run(List<String> command) {
        StringBuilder buffer = new StringBuilder();
        Process process = null;
        Thread reader = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            final Process target = process;
            reader = new Thread(() -> {
                try (var in = new java.io.BufferedReader(
                        new java.io.InputStreamReader(target.getInputStream(),
                                java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        synchronized (buffer) {
                            if (buffer.length() < 16384) buffer.append(line).append('\n');
                        }
                    }
                } catch (IOException ignored) {
                }
            }, "transcode-probe-reader");
            reader.setDaemon(true);
            reader.start();
            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ProcessResult(false, trimToString(buffer) + " [timed out after 15s]");
            }
            reader.join(TimeUnit.SECONDS.toMillis(2));
            return new ProcessResult(process.exitValue() == 0, trimToString(buffer));
        } catch (IOException e) {
            return new ProcessResult(false, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null && process.isAlive()) process.destroyForcibly();
            return new ProcessResult(false, "interrupted");
        }
    }

    private static String trimToString(StringBuilder builder) {
        synchronized (builder) {
            return builder.toString();
        }
    }

    public record HardwareStatus(boolean available, String vendor, String device,
                                 List<String> supportedCodecs, String acceleration, String reason) {
        static HardwareStatus unavailable(String reason) {
            return new HardwareStatus(false, null, null, List.of(), null, reason);
        }
    }
}
