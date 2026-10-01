package io.github.Nyameph.nyaentworks.common.media;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 媒体端点的<b>临时预览令牌</b>：让「还没导进受管根的文件」也能在页面上试听。
 *
 * <p><b>为什么需要它</b>：{@link MediaStreamService} 的两道校验之一是「{@code normalize()}
 * 后仍落在受管根内」（实现说明坑 12），而原曲页「新增原曲」选的文件天生在受管根<b>之外</b>
 * （源可以是任意盘）。没有这条路，用户在导入前点「▶」只会拿到一句「不在受管的媒体目录下」，
 * 而导入前的试听恰恰是这一步最有用的功能（选错文件、选错类别都能当场发现）。
 *
 * <p><b>它换来的放行面有多大</b>：令牌 = {@code HMAC-SHA256(本次进程的随机密钥, 规范化绝对路径)}，
 * 密钥在启动时由 {@link SecureRandom} 生成、<b>只活在内存里</b>。所以
 * <ul>
 *   <li>伪造不了：拿不到密钥就算不出别的路径的令牌（比「开一个目录白名单」小得多）；</li>
 *   <li>过一会儿就没了：重启后端即全部失效，前端手里的 URL 也就一起作废；</li>
 *   <li>只放行<b>签名过的那一个路径</b>，不是那个目录、更不是整个盘。</li>
 * </ul>
 * 令牌只经 {@code POST /api/song/template/import-expand} 下发，而那个接口只给本次表单里
 * 用户自己选中的文件签名 —— 与「谁能读这个文件」等价。
 *
 * <p><b>不要把它当成鉴权</b>：这是单机自用项目，服务端只监听 {@code 127.0.0.1}
 * （实现说明第 8 节）。它只是把「受管根」这道闸门按需开一条缝，不是身份认证。
 */
@Component
public class MediaPreviewTokens {

    private static final String ALGORITHM = "HmacSHA256";

    /** 启动时随机生成，只活在内存里 —— 重启即失效，这是「临时」两个字的全部实现 */
    private final SecretKeySpec key;

    public MediaPreviewTokens() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        this.key = new SecretKeySpec(secret, ALGORITHM);
    }

    /**
     * 给一个绝对路径签令牌。同一路径在同一进程里恒得同一个串（前端来回重画不会抖动）。
     *
     * <p>路径<b>先规范化</b>再签，与 {@link MediaStreamService#requireManagedMedia} 校验时
     * 用的那个 {@code Path} 同口径 —— 两边不一致的下场是「签出来的令牌永远验不过」，
     * 而症状是「预览按钮点了没反应」。
     */
    public String sign(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        return HexFormat.of().formatHex(hmac(canonical(path)));
    }

    /**
     * 校验令牌是不是给<b>这个</b>路径签的（恒定时间比较，不给逐字节试探留时间差）。
     *
     * @param target 已经 {@code toAbsolutePath().normalize()} 过的路径（调用方那一步不能省）
     */
    public boolean matches(Path target, String token) {
        if (target == null || token == null || token.isBlank()) {
            return false;
        }
        byte[] expected = hmac(target.toString());
        byte[] given;
        try {
            given = HexFormat.of().parseHex(token.trim());
        } catch (IllegalArgumentException e) {
            return false;   // 不是十六进制串：当伪造处理，不抛
        }
        return MessageDigest.isEqual(expected, given);
    }

    private static String canonical(String path) {
        return Path.of(path).toAbsolutePath().normalize().toString();
    }

    private byte[] hmac(String text) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            // HmacSHA256 是 JDK 必备算法，走不到这儿；真走到了也不该放行
            throw new IllegalStateException("算不出预览令牌：" + e.getMessage(), e);
        }
    }
}
