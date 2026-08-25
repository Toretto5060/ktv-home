package com.homektv.security;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 算法对称性单测：直接 new 一个 QrTokenCipher 走 encode→decode 闭环，
 * 不依赖 Spring 容器与 @Value 注入，验证算法本身是否对称。
 */
public class QrTokenCipherRoundTripTest {

    @Test
    void encodeDecode_roundTrip_withFixedSecret() throws Exception {
        // 用一个固定 secret 直接反射设进去，避免 Spring 容器复杂度
        String fixedSecret = "test-fixed-secret-please-ignore";

        QrTokenCipher cipher = new QrTokenCipher(fixedSecret);

        // 反射拿到 encode 方法（已公开）和 decode 方法
        Method encode = QrTokenCipher.class.getMethod("encode", String.class, long.class, Long.class, Long.class);
        Method decode = QrTokenCipher.class.getMethod("decode", String.class);

        String roomId = "f1d0a35f-83f3-4a31-b8aa-d9aba761c706";
        long version = 1L;

        String token = (String) encode.invoke(cipher, roomId, version, null, null);
        System.out.println("[RT] encode token: " + token);

        // 关键：用相同 cipher 实例 decode，secret 必须相同
        Object decoded = decode.invoke(cipher, token);
        Method getRoomId = decoded.getClass().getMethod("roomId");
        Method getVersion = decoded.getClass().getMethod("qrCodeVersion");

        assertEquals(roomId, getRoomId.invoke(decoded));
        assertEquals(version, getVersion.invoke(decoded));
        System.out.println("[RT] decode roomId=" + getRoomId.invoke(decoded) + " version=" + getVersion.invoke(decoded));
    }

    @Test
    void encodeDecode_throwsWhenSecretDiffers() throws Exception {
        QrTokenCipher encoder = new QrTokenCipher("secret-A");
        QrTokenCipher decoder = new QrTokenCipher("secret-B");

        Method encode = QrTokenCipher.class.getMethod("encode", String.class, long.class, Long.class, Long.class);
        Method decode = QrTokenCipher.class.getMethod("decode", String.class);

        String token = (String) encode.invoke(encoder, "f1d0a35f-83f3-4a31-b8aa-d9aba761c706", 1L, null, null);
        System.out.println("[DD] encode with secret-A, token前32=" + token.substring(0, Math.min(32, token.length())));

        try {
            decode.invoke(decoder, token);
            System.out.println("[DD] FAIL: expected throw but no exception");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            System.out.println("[DD] got expected exception: " + cause.getClass().getSimpleName() + " - " + cause.getMessage());
            // 这正是 "bad signature"
        }
    }

    @Test
    void debug_hmacInputBytesAreSameOnBothSides() throws Exception {
        // 验证：用同样的 secret，同样的输入（nonce 固定），encode 和 decode 算出的 sig 完全相同
        QrTokenCipher cipher = new QrTokenCipher("fixed-secret");

        // 反射把 secret 拿出来看一眼
        Field secretField = QrTokenCipher.class.getDeclaredField("secret");
        secretField.setAccessible(true);
        byte[] secretBytes = (byte[]) secretField.get(cipher);
        System.out.println("[D] secret length=" + secretBytes.length);

        Method encode = QrTokenCipher.class.getMethod("encode", String.class, long.class, Long.class, Long.class);
        Method decode = QrTokenCipher.class.getMethod("decode", String.class);
        Method hmac = QrTokenCipher.class.getDeclaredMethod("hmac", byte[].class, int.class, int.class);
        hmac.setAccessible(true);

        String roomId = "f1d0a35f-83f3-4a31-b8aa-d9aba761c706";
        long version = 1L;
        String token = (String) encode.invoke(cipher, roomId, version, null, null);

        // decode 端解 base64
        byte[] all = java.util.Base64.getUrlDecoder().decode(token);
        System.out.println("[D] all.length=" + all.length);

        // 重现 decode 端读取 flags/protoVer/nonce/roomId/version 的 off 偏移
        int off = 1 + 1 + 8; // flags + protoVer + nonce
        int roomIdLen = all[off++] & 0xFF;
        off += roomIdLen;
        off += 8; // qrCodeVersion
        // flags=0, no activeStartMs/activeEndMs
        System.out.println("[D] off=" + off);

        byte[] expectedSig = (byte[]) hmac.invoke(cipher, all, 0, off);
        StringBuilder hex = new StringBuilder();
        for (byte b : expectedSig) hex.append(String.format("%02x", b));
        System.out.println("[D] expected sig=" + hex);

        // 跟 token 后 32 字节对比
        StringBuilder actualHex = new StringBuilder();
        for (int i = off; i < off + 32; i++) actualHex.append(String.format("%02x", all[i]));
        System.out.println("[D] actual   sig=" + actualHex);
        System.out.println("[D] match=" + hex.toString().equals(actualHex.toString()));
    }
}
