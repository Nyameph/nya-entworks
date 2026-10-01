package io.github.Nyameph.nyaentworks.song.fill;

/**
 * 歌词槽位类型（填词工具设计 2.4）。判定只看 note 的 {@code lyrics} 取值：
 *
 * <ul>
 *   <li>{@code "-"} → {@link #DASH} 延音（上一个字拉长），不占新字、不可填；
 *   <li>{@code "br"} → {@link #BREATH} 呼吸控制，不计入歌词、不可填；
 *   <li>{@code "0"} → {@link #ZERO} 静音占位，不计入歌词、不可填（2026-09-21 追加）；
 *   <li>含 ASCII 字母 → {@link #ENGLISH} 英文单词，一个 note 一个词，可填；
 *   <li>其余（汉字 / 空） → {@link #HANZI}，可填。
 * </ul>
 *
 * <p>休止 {@code R} 不是 note —— svp 里休止表现为 note 之间的 onset 空隙。
 *
 * <p><b>{@link #ZERO} 与 {@link #DASH} / {@link #BREATH} 的差别只在于导出</b>：三者都不可填、
 * 都不主动填词，lrc 里都不出现；但回填模板文本时 {@code 0} 要原样写回（另两个写 {@code -} /
 * {@code br}）—— 用户口径「和 br、- 一样不主动填词、歌词导出没有但是在反填文本中保留」。
 * 分句上它<b>不站队</b>（既不强制跟前面、也不强制跟后面），跟 {@link #DASH} / {@link #BREATH}
 * 各自的偏向不同，所以必须自成一类、不能并进那两个。
 */
public enum SlotType {

    HANZI,
    ENGLISH,
    DASH,
    BREATH,
    ZERO;

    /** 能否填词。{@code needCount} 只数可填槽位。 */
    public boolean fillable() {
        return this == HANZI || this == ENGLISH;
    }
}
