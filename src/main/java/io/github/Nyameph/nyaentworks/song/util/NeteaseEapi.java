package io.github.Nyameph.nyaentworks.song.util;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 网易云 eapi 请求加密（NeteaseCloudMusicApi 同款算法），纯函数，无外部依赖。
 *
 * <p>用途：{@code /api/song/enhance/player/url} 等 eapi 接口要求把请求体加密成
 * {@code params} 表单字段；只有配了登录 cookie（{@code MUSIC_U}）的 VIP 直链渠道
 * （{@code SongResourceSearchService#downloadNeteaseVipAudio}）才会调用。
 *
 * <p>算法（公开资料，客户端内置）：
 * <ol>
 *   <li>{@code data = apiPath + "-36cd479b6b5-" + json}</li>
 *   <li>{@code digest = MD5("nobody" + apiPath + "use" + data + "md5forencrypt")}（小写 hex）</li>
 *   <li>{@code plain = data + "-36cd479b6b5-" + digest}</li>
 *   <li>{@code params = HEX(AES-128-ECB/PKCS5(plain, key="e82ckenh8dichen8")).toUpperCase()}</li>
 * </ol>
 */
public final class NeteaseEapi {

    /** eapi 固定的 AES key（官方客户端内置，社区公开） */
    private static final byte[] AES_KEY = "e82ckenh8dichen8".getBytes(StandardCharsets.UTF_8);

    /** 拼接固定分隔符（与客户端一致） */
    private static final String SEP = "-36cd479b6b5-";

    private NeteaseEapi() {
    }

    /**
     * 把「接口路径 + JSON 请求体」加密成 eapi 的 {@code params} 值。
     *
     * @param apiPath 接口路径，如 {@code /api/song/enhance/player/url}
     * @param json    JSON 请求体，如 {@code {"ids":"[123]","br":320000}}
     * @return 大写 HEX 字符串，直接作为表单 {@code params} 字段提交
     */
    public static String buildParams(String apiPath, String json) {
        try {
            String data = apiPath + SEP + json;
            String digest = md5Hex("nobody" + apiPath + "use" + data + "md5forencrypt");
            String plain = data + SEP + digest;
            byte[] encrypted = aesEcb128(plain.getBytes(StandardCharsets.UTF_8), AES_KEY);
            return toHex(encrypted).toUpperCase();
        } catch (Exception e) {
            throw new IllegalStateException("eapi 加密失败: " + e.getMessage(), e);
        }
    }

    private static byte[] aesEcb128(byte[] data, byte[] key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        return cipher.doFinal(data);
    }

    private static String md5Hex(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        return toHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    /** 小写 hex */
    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
