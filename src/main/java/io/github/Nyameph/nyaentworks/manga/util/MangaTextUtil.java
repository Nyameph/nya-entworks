package io.github.Nyameph.nyaentworks.manga.util;

import org.apache.commons.lang3.StringUtils;

import java.text.Normalizer;

/**
 * 漫画模块的文本归一工具，纯静态、不依赖 Spring。
 * <p>词典匹配与归档别名匹配必须走同一套归一逻辑，否则会出现「值明明在库里、
 * 却匹配不上」——所以这些方法集中在这里，而不是各处各写一份。
 */
public final class MangaTextUtil {

    private MangaTextUtil() {
    }

    /**
     * 按 Unicode NFC 归一。
     * <p>同一个假名有预组合（{@code ポ} U+30DD）与分解（{@code ホ}+{@code ゚}
     * U+30DB U+309A）两种写法，肉眼与 MySQL 的 {@code utf8mb4_0900_ai_ci} 都视作
     * 相同，但 Java 的 {@code String.equals} 不同 —— 不归一会出现「词条明明在库里、
     * 解析却匹配不上，且因唯一键冲突又补录不进去」。词条侧与待匹配文本侧都要过这一步。
     */
    public static String nfc(String s) {
        return s == null || Normalizer.isNormalized(s, Normalizer.Form.NFC)
                ? s
                : Normalizer.normalize(s, Normalizer.Form.NFC);
    }

    /**
     * 社团名/作者名的比较键：NFC + trim + 大写。
     * <p>与 {@link MangaNameParser#splitGroupArtistToCompare} 的单段处理保持一致
     * （后者额外做了按 {@code 、} 拆分），这样解析侧算出的键与
     * {@code manga_archive_name.name} 能直接对上。
     *
     * @return 归一化后的键；入参为空白时返回 {@code null}
     */
    public static String normalizeNameKey(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        return nfc(raw).trim().toUpperCase();
    }

    /**
     * 转义 SQL {@code LIKE} 模式里的特殊字符，供按路径前缀查询用。
     *
     * <p><b>反斜杠必须转义</b>：{@code LIKE} 里 {@code \} 是转义字符，而本项目按前缀查的
     * 都是 Windows 全路径。不转义的话 {@code F:\MangaGroup\#8\[社团]\%} 会被解析成
     * 「反斜杠全被吃掉、结尾 {@code \%} 是字面百分号而不是通配符」，结果一条都匹配不上 ——
     * 症状是「算出 0 行受影响」，而调用方照样往下执行。
     *
     * <p>{@code %} 与 {@code _} 同理：路径里出现它们会被当通配符而多匹配。
     *
     * <p><b>替换顺序不能反</b>：先反斜杠、后另外两个。反过来会把刚加进去的转义反斜杠
     * 又转义一遍，`\%` 变成 `\\%`（字面反斜杠 + 通配符），语义完全不同。
     */
    public static String escapeLike(String raw) {
        return raw == null ? null : raw
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
