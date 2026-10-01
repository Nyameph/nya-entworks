package io.github.Nyameph.nyaentworks.song.util;

import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;

/**
 * 原曲目录里八类文件的主名（不含扩展名）的拼法，全项目唯一一份。
 *
 * <p>原先这八条形状只写在 {@code script/SongOriginalFileRenameUtil.newBaseName} 里（那是一次性
 * 改名脚本）——「新增原曲 / 修改」这个页面入口要按同一套规则改名，两处各拼一遍的下场是
 * 同一批文件经脚本改与经页面改得到不同名字（少一个空格、括号写法不一致），于是库里指着
 * 一个盘上不存在的名字。所以拼法收拢到这里，脚本与页面一律转调（实现说明 §3）。
 *
 * <p><b>八类形状</b>（{@code artist=甲}、{@code raw_name=乙}）：
 * <ul>
 *   <li>{@code original} / {@code lyric}：{@code 甲 - 乙}（作者为空时只留 {@code 乙}，
 *       不留孤零零的 {@code " - "}）</li>
 *   <li>{@code demo}：{@code [demo] 乙}</li>
 *   <li>{@code demoLrc}（样例歌词）：{@code [demo] 乙} —— 与 {@code demo} 同主名、只在扩展名上
 *       区分（{@code .lrc} 对 {@code .wav}）</li>
 *   <li>{@code accompaniment}：{@code [伴奏] 乙}</li>
 *   <li>{@code vocals}：{@code [人声] 乙}</li>
 *   <li>{@code mid}：{@code [BPM=120] 乙}（曲速算不出时是 {@code [BPM=？] 乙}）</li>
 *   <li>{@code svp}：{@code [工程] 乙}</li>
 * </ul>
 *
 * <p><b>{@code demoLrc} 不是</b> {@link io.github.Nyameph.nyaentworks.song.service.SongTemplateService#TYPES}
 * <b>的成员</b>（{@code TYPES} 只有七个：original/lyric/accompaniment/vocals/demo/mid/svp），
 * 但库里有 {@code demo_lrc_file_name} 列、盘上也有这个文件。本类把它当<b>第八个 {@code type} 收</b>，
 * 取值就是字符串 {@code "demoLrc"} —— <b>不要去改 {@code TYPES}</b>（那个列表喂给页面下拉，
 * 多一项会让七类下拉变成八类，扫描侧的判定也跟着变）。
 *
 * <p><b>主名里的 {@code #} 不做任何处理</b>：这里只管「名字怎么拼」，不管「名字合不合法」。
 * 合法性由 {@code GroupFileOps.requireMainName} 一处判（照 {@link SongNaming} 的分工）。
 *
 * <p>纯静态、无状态、无依赖 —— 单测直接跑，不碰 {@code F:\}。
 */
public final class SongOriginalNaming {

    private SongOriginalNaming() {
    }

    /**
     * 拼一个原曲文件的主名（不含扩展名，含点由调用方接）。
     *
     * @param type    八类之一，取值见类注释；不认识的类别<b>抛</b>（调用方必须先校验，
     *                抛出来 = 闸门漏了，要响）
     * @param rawName 原曲名原文
     * @param artist  作者原文；空白时 {@code original} / {@code lyric} 不留 {@code " - "} 前缀
     * @param bpm     曲速文本，只对 {@code mid} 有用；空白时写 {@code ？}
     */
    public static String build(String type, String rawName, String artist, String bpm) {
        String art = StringUtils.trimToEmpty(artist);
        String raw = StringUtils.trimToEmpty(rawName);
        return switch (type) {
            case "original", "lyric" -> art.isEmpty() ? raw : art + " - " + raw;
            case "demo", "demoLrc" -> "[demo] " + raw;
            case "accompaniment" -> "[伴奏] " + raw;
            case "vocals" -> "[人声] " + raw;
            case "mid" -> "[BPM=" + bpmOrPlaceholder(bpm) + "] " + raw;
            case "svp" -> "[工程] " + raw;
            default -> throw new IllegalArgumentException("不认识的文件类别：" + type);
        };
    }

    /**
     * mid 专用的曲速占位：算不出曲速时是<b>字面量「？」</b>，不是空串
     * （{@code [BPM=？]} 是盘上既有的写法，改成空串会让这个名字与盘上其它文件对不上）。
     */
    public static String bpmOrPlaceholder(String bpm) {
        String clean = StringUtils.trimToNull(bpm);
        return clean == null ? "？" : clean;
    }

    /**
     * 曲速的写法：{@code stripTrailingZeros().toPlainString()} —— 与改名脚本、
     * {@code SongTemplateService} 的列表侧同一口径（{@code 120.0000} 要写成 {@code 120}，
     * 不能是 {@code 1.2E+2}，也不能走 {@code String.valueOf} 把标度带出来）。
     * 入参为 {@code null} 时返回 {@code null}（要不要写占位由 {@link #build} 决定）。
     */
    public static String bpmText(BigDecimal bpm) {
        return bpm == null ? null : bpm.stripTrailingZeros().toPlainString();
    }
}
