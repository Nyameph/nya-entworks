package io.github.Nyameph.nyaentworks.song.util;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 规范主名的拼法，全项目唯一一份（实现说明 8.1）。
 *
 * <p>规范形状：{@code 作者1 & 作者2 - 曲名（原曲名_原曲作者）#版本}。原先「改名」与
 * 「批量规范化」各拼一遍（{@link SongName#normalizedMainName()}），「添加文件」的表单
 * 又要按表单值拼 —— 三份的下场是同一批歌经不同入口得到不同名字（{@code &} 两侧空格、
 * 全角括号、{@code #} 前有没有空格），这些小差异会让 {@code merge_key} 变掉、
 * 标签与默认倍速全丢。所以拼法收拢到这里，其余入口一律转调。
 *
 * <p>纯静态、无状态、无依赖 —— 单测像 {@code SongNameParserTest} 一样直接跑。
 */
public final class SongNaming {

    private SongNaming() {
    }

    /**
     * 拼一个规范主名：{@code 作者1 & 作者2 - 曲名（原曲名_原曲作者）#版本}
     *
     * @param artists        作者，多作者用 " & " 分隔（本方法内部会切分 + trim + 丢空 + 重新连接）
     * @param title          曲名
     * @param originalTitle  原曲名
     * @param originalArtist 原曲作者；空白时不加 {@code _原曲作者}
     * @param version        编号 / 版本号；空白时不加 {@code #编号}
     * @throws IllegalArgumentException 作者 / 曲名 / 原曲名三者任一为空白
     *         （调用方必须先校验并转成 blockedReason，抛出来 = 闸门漏了，要响）
     */
    public static String build(String artists, String title, String originalTitle,
                               String originalArtist, String version) {
        String joinedArtists = joinArtists(artists);
        String cleanTitle = StringUtils.trimToNull(title);
        String cleanOriginal = StringUtils.trimToNull(originalTitle);
        if (joinedArtists == null || cleanTitle == null || cleanOriginal == null) {
            throw new IllegalArgumentException("作者、曲名、原曲名都要填：artists="
                    + artists + ", title=" + title + ", originalTitle=" + originalTitle);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(joinedArtists).append(" - ").append(cleanTitle);
        sb.append('（').append(cleanOriginal);
        String cleanOriginalArtist = StringUtils.trimToNull(originalArtist);
        if (cleanOriginalArtist != null) {
            sb.append('_').append(cleanOriginalArtist);
        }
        sb.append('）');
        String cleanVersion = StringUtils.trimToNull(version);
        if (cleanVersion != null) {
            sb.append('#').append(cleanVersion);
        }
        return sb.toString();
    }

    /**
     * 编号校验：不必操心空白（空白就是「没有编号」），但<b>不许含 {@code #}</b> ——
     * 解析口径是「按第一个 {@code #} 切版本号」，{@code #} 里再套 {@code #} 会得到
     * {@code 版本 = 2#幼} 这种怪值。
     */
    public static void requireVersion(String version) {
        String clean = StringUtils.trimToNull(version);
        if (clean == null) {
            return;
        }
        if (clean.indexOf('#') >= 0) {
            throw new IllegalArgumentException("编号里不能再有 #：「" + clean
                    + "」。# 是主名与编号的分隔符，编号只要写 # 后面那一段");
        }
    }

    /** 按 " & " 切 → trim → 丢空 → 重新连接。幂等：已是 "A & B" 的再进来还是它 */
    private static String joinArtists(String artists) {
        if (StringUtils.isBlank(artists)) {
            return null;
        }
        List<String> cleaned = new ArrayList<>();
        for (String piece : artists.split("&")) {
            String one = StringUtils.trimToNull(piece);
            if (one != null) {
                cleaned.add(one);
            }
        }
        return cleaned.isEmpty() ? null : String.join(" & ", cleaned);
    }
}
