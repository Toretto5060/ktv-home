package com.homektv.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 房间二维码加密工具（P1.x）。
 * 二维码内容包含：
 * <ul>
 *   <li>房间 ID（UUID）</li>
 *   <li>房间开放开始时间（ISO-8601 epoch millis，可选）</li>
 *   <li>房间开放结束时间（ISO-8601 epoch millis）</li>
 *   <li>8 字节随机 nonce（防重放）</li>
 *   <li>QR 版本号（房间表 qr_code_version，自增；用于让旧 QR 失效）</li>
 *   <li>HMAC-SHA256 签名（防伪造）</li>
 * </ul>
 *
 * 输出：Base64(URL-safe) 的密文，所有信息不可见、不可篡改。
 *
 * <p>协议版本号：当前为 2。protoVer=1（无 qrCodeVersion 字段）视为旧格式，直接判为
 * "unsupported version" — 部署后所有历史 token 失效。
 *
 * The room QR payload is encrypted:
 * <ul>
 *   <li>room ID (UUID)</li>
 *   <li>active start epoch millis (optional)</li>
 *   <li>active end epoch millis</li>
 *   <li>8-byte random nonce</li>
 *   <li>QR version (mirrors room.qr_code_version; used to invalidate stale QR codes)</li>
 *   <li>HMAC-SHA256 signature</li>
 * </ul>
 *
 * Output: Base64(URL-safe). All info is opaque and tamper-proof.
 */
@Component
public class QrTokenCipher {

    private static final String ALGO = "HmacSHA256";
    /** 当前协议版本号。改动布局后必须自增；旧 token decode 时会被判为 unsupported。 */
    private static final byte PROTO_VER = 2;
    private final byte[] secret;
    private final SecureRandom random = new SecureRandom();

    public QrTokenCipher(@Value("${ktv.qr.secret:ktv-default-qr-secret-change-me-in-prod}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 生成加密的二维码 token。
     *
     * @param roomId         房间 UUID 字符串
     * @param qrCodeVersion  房间 QR 版本号（每次 generateQrCode 自增）
     * @param activeStartMs  开放开始时间（epoch millis），可为 null
     * @param activeEndMs    开放结束时间（epoch millis），可为 null
     * @return Base64(URL-safe) 字符串
     */
    public String encode(String roomId, long qrCodeVersion, Long activeStartMs, Long activeEndMs) {
        // 布局（protoVer=2）：
        //   [flags:1][ver:1][nonce:8][roomIdLen:1][roomId:N][qrCodeVersion:8][startMs?:8][endMs?:8][sig:32]
        byte[] roomIdBytes = roomId.getBytes(StandardCharsets.UTF_8);
        if (roomIdBytes.length > 127) {
            throw new IllegalArgumentException("roomId too long");
        }

        int size = 1 + 8 + 1 + roomIdBytes.length + 8; // flags+protoVer+nonce+roomIdLen+roomId+qrCodeVersion
        byte flags = 0;
        if (activeStartMs != null) {
            flags |= 0x01;
            size += 8;
        }
        if (activeEndMs != null) {
            flags |= 0x02;
            size += 8;
        }
        size += 32; // HMAC

        byte[] buf = new byte[size];
        int off = 0;
        buf[off++] = PROTO_VER;
        byte[] nonce = new byte[8];
        random.nextBytes(nonce);
        System.arraycopy(nonce, 0, buf, off, 8);
        off += 8;
        buf[off++] = (byte) roomIdBytes.length;
        System.arraycopy(roomIdBytes, 0, buf, off, roomIdBytes.length);
        off += roomIdBytes.length;
        writeLong(buf, off, qrCodeVersion);
        off += 8;
        if (activeStartMs != null) {
            writeLong(buf, off, activeStartMs);
            off += 8;
        }
        if (activeEndMs != null) {
            writeLong(buf, off, activeEndMs);
            off += 8;
        }
        // 签名覆盖 flags + protoVer + nonce + payload
        byte[] sig = hmac(buf, 0, off);
        System.arraycopy(sig, 0, buf, off, sig.length);

        // flags 加到密文前部
        byte[] out = new byte[size + 1];
        out[0] = flags;
        System.arraycopy(buf, 0, out, 1, size);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
    }

    /**
     * 解密二维码 token，校验签名与有效期。
     *
     * @param token 加密字符串
     * @return 解密后的载荷
     * @throws QrTokenInvalidException token 无效、签名错误或已过期
     */
    public Decoded decode(String token) {
        if (token == null || token.isBlank()) {
            throw new QrTokenInvalidException("empty token");
        }
        byte[] all;
        try {
            all = Base64.getUrlDecoder().decode(token);
        } catch (IllegalArgumentException e) {
            throw new QrTokenInvalidException("invalid base64");
        }
        if (all.length < 1 + 1 + 8 + 1 + 8 + 32) {
            throw new QrTokenInvalidException("token too short");
        }
        byte flags = all[0];
        int off = 1;
        if (all[off++] != PROTO_VER) {
            throw new QrTokenInvalidException("unsupported version");
        }
        // 跳过 nonce
        off += 8;
        int roomIdLen = all[off++] & 0xFF;
        if (off + roomIdLen + 8 + 32 > all.length) {
            throw new QrTokenInvalidException("truncated");
        }
        byte[] roomIdBytes = new byte[roomIdLen];
        System.arraycopy(all, off, roomIdBytes, 0, roomIdLen);
        off += roomIdLen;
        String roomId = new String(roomIdBytes, StandardCharsets.UTF_8);
        long qrCodeVersion = readLong(all, off);
        off += 8;

        Long activeStartMs = null;
        if ((flags & 0x01) != 0) {
            activeStartMs = readLong(all, off);
            off += 8;
        }
        Long activeEndMs = null;
        if ((flags & 0x02) != 0) {
            activeEndMs = readLong(all, off);
            off += 8;
        }
        // 验证签名：覆盖 all[0..off)（含 flags、protoVer、nonce、payload）
        byte[] expected = hmac(all, 0, off);
        if (off + 32 != all.length) {
            throw new QrTokenInvalidException("trailing data");
        }
        if (!constantTimeEquals(expected, 0, all, off, 32)) {
            throw new QrTokenInvalidException("bad signature");
        }
        return new Decoded(roomId, qrCodeVersion, activeStartMs, activeEndMs);
    }

    private byte[] hmac(byte[] data, int offset, int length) {
        try {
            Mac mac = Mac.getInstance(ALGO);
            mac.init(new SecretKeySpec(secret, ALGO));
            return mac.doFinal(java.util.Arrays.copyOfRange(data, offset, offset + length));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failure", e);
        }
    }

    private static void writeLong(byte[] buf, int off, long v) {
        for (int i = 7; i >= 0; i--) {
            buf[off + i] = (byte) (v & 0xFF);
            v >>>= 8;
        }
    }

    private static long readLong(byte[] buf, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (buf[off + i] & 0xFFL);
        }
        return v;
    }

    private static boolean constantTimeEquals(byte[] a, int ao, byte[] b, int bo, int len) {
        if (a.length - ao < len || b.length - bo < len) return false;
        int r = 0;
        for (int i = 0; i < len; i++) r |= (a[ao + i] ^ b[bo + i]);
        return r == 0;
    }

    /** 解密后的载荷。 */
    public record Decoded(String roomId, long qrCodeVersion, Long activeStartMs, Long activeEndMs) {
        public boolean isExpired(long nowMs) {
            return activeEndMs != null && nowMs > activeEndMs;
        }
        public boolean isNotStarted(long nowMs) {
            return activeStartMs != null && nowMs < activeStartMs;
        }
    }

    /** 二维码 token 无效时抛出的异常（签名错误、过期、被篡改等）。 */
    public static class QrTokenInvalidException extends RuntimeException {
        public QrTokenInvalidException(String message) { super(message); }
    }
}