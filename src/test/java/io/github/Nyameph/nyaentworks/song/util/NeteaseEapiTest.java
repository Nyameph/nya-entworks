package io.github.Nyameph.nyaentworks.song.util;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NeteaseEapi} 的纯函数测试：确定性 + 输出格式 + 用同一 AES key 解密回验明文结构。
 * 只依赖 JCE，无外部环境，可直接 {@code ./mvnw test -Dtest=NeteaseEapiTest}。
 */
class NeteaseEapiTest {

    private static final String API_PATH = "/api/song/enhance/player/url";
    private static final String JSON = "{\"ids\":\"[12345]\",\"br\":320000}";

    @Test
    void buildParams_isDeterministic() {
        assertEquals(NeteaseEapi.buildParams(API_PATH, JSON), NeteaseEapi.buildParams(API_PATH, JSON));
    }

    @Test
    void buildParams_isUppercaseHex() {
        String params = NeteaseEapi.buildParams(API_PATH, JSON);
        assertTrue(params.matches("[0-9A-F]+"), "应为大写 hex，实际: " + params);
    }

    @Test
    void buildParams_decryptBackToSpecPlaintext() throws Exception {
        String params = NeteaseEapi.buildParams(API_PATH, JSON);
        String plain = decrypt(params);
        // 明文应严格等于 path-36cd479b6b5-json-36cd479b6b5-md5digest
        String data = API_PATH + "-36cd479b6b5-" + JSON;
        String digest = md5Hex("nobody" + API_PATH + "use" + data + "md5forencrypt");
        assertEquals(data + "-36cd479b6b5-" + digest, plain);
    }

    private static String decrypt(String hex) throws Exception {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec("e82ckenh8dichen8".getBytes(StandardCharsets.UTF_8), "AES"));
        return new String(cipher.doFinal(bytes), StandardCharsets.UTF_8);
    }

    private static String md5Hex(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest(s.getBytes(StandardCharsets.UTF_8))) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
