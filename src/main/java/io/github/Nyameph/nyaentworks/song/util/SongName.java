package io.github.Nyameph.nyaentworks.song.util;

import java.util.List;

/**
 * 一个歌曲文件名的解析结果（文档 8.1 / 实现说明 5.6）。
 *
 * <pre>
 * 作者1 &amp; 作者2 - 曲名（原曲名）#版本号.mp3
 * └─ artists ─┘   └title┘└original┘ └version┘
 * </pre>
 *
 * @param mainName          去掉扩展名后的原文，<b>归组的键就是它</b>（含版本号）
 * @param artists           作者，按 {@code &} 拆开；解析失败时为空
 * @param title             曲名（不含原曲名括号）
 * @param originalTitle     原曲名。文件名里没写时<b>等于 title</b> —— 原曲没被改词，
 *                          直接翻唱，这样按原曲名统计不必区分两种形态
 * @param originalArtist    原曲作者（原唱）。仅在显式括号内写成
 *                          {@code 原曲名_原曲作者} 时才有值，其余为 null —— 同名原曲
 *                          靠它区分（{@code 后来_刘若英} vs {@code 后来_xxx}）
 * @param version           版本号（不含 {@code #}），没有则为 null。<b>不一定是数字</b>
 * @param originalExplicit  原曲名是否在文件名里显式写了。false 表示是从 title 推的，
 *                          「规范化命名」要补的就是这一批
 * @param looseSeparator    分隔符是 {@code -} 而非标准的 {@code  - }（前后带空格）。
 *                          容错解析了，但标记出来供页面提示规范化
 * @param parseFailedReason 解析失败的原因，成功时为 null。<b>失败不影响播放与打分</b>
 */
public record SongName(String mainName,
                       List<String> artists,
                       String title,
                       String originalTitle,
                       String originalArtist,
                       String version,
                       boolean originalExplicit,
                       boolean looseSeparator,
                       String parseFailedReason) {

    public boolean parsed() {
        return parseFailedReason == null;
    }

    /** 作者原文，用 {@code  & } 连回去 */
    public String artistText() {
        return String.join(" & ", artists);
    }

    /**
     * 是否需要规范化命名：曲名与原曲名不同但原曲名没显式写，说明这是
     * {@code 作者 - 原曲名#版本} 形态，要补成 {@code 作者 - 原曲名（原曲名）#版本}。
     * <p>解析失败的不算 —— 那种要人先把分隔符改对。
     */
    public boolean needsNormalize() {
        return parsed() && !originalExplicit;
    }

    /**
     * 规范化后的主名（不含扩展名）。{@code 作者 - 曲名（原曲名_原曲作者）#版本号}。
     * <p>解析失败时返回原文，调用方应当先判 {@link #parsed()}。
     * <p>拼法在 {@link SongNaming#build}（全项目唯一一份）—— 这里只转调。
     * <b>五个参数一个都不能少</b>：少传 {@code originalArtist} 就等于丢掉
     * {@code _原曲作者}，那会让「批量规范化命名」把已有歌的名字改短、{@code merge_key}
     * 变掉、标签全丢。
     */
    public String normalizedMainName() {
        if (!parsed()) {
            return mainName;
        }
        return SongNaming.build(artistText(), title, originalTitle, originalArtist, version);
    }
}
