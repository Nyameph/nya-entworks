package io.github.Nyameph.nyaentworks.song.fill;

import java.util.List;

/**
 * 一次解析的完整填词骨架（填词工具设计 3 / 4）。
 *
 * <p>{@code tracks} 是 svp 里的**全部**歌唱轨（{@code notes} 非空的轨），
 * {@code trackIndices} 是这次勾选要填的那几轨，{@code lines} 是勾选轨按 onset
 * 合并成一条时间线后的分句。{@code filled} 与 {@code lines} 等长，**每句又是一个列表**：
 * 与那一句的 {@code slots} 一一对应（含 DASH / BREATH 位），空串 = 这一格没填。
 *
 * <p>落库时 {@code tracks} 存 {@code notes_json}、{@code lines} 存 {@code lines_json}、
 * {@code filled} 存 {@code filled_json}（见 {@link LyricFillStore}）。
 *
 * @param fillId        填词项目 id（{@code song_lyric_fill.id}），保存 / 导出回指用
 * @param fillName      填词名（用户可改），空则回落「填词」
 * @param svpPath       svp 模板完整路径（打开时的快照）
 * @param originalName  原曲名原文
 * @param artist        原曲歌手，可能为 null
 * @param bpm           模板曲速（song_original_setting.bpm，扫描时从 svp 工程或 midi
 *                      文件名提取）；前端用它把 onset 换算成小节号。null = 没提取到
 * @param tracks        全部歌唱轨
 * @param lines         勾选轨合并后的分句
 * @param filled        每句的槽位值：{@code filled.get(i).get(k)} 对应 {@code lines.get(i).slots().get(k)}，空串 = 没填
 * @param defaults      每句的默认词占位符（拿 demo 歌词配出来的汉字），与 {@code filled} 同形状。拼音模板每一格都给，汉字模板只给其中夹着的拼音音符（原词是汉字的那几格歌词就在音符里）；文本对齐不成功时全是空串。不落库，每次现算
 * @param gaps          每句的视觉空位（拼音模板匹配出的歌词句内空格），与 {@code filled} 同形状；{@code true} = 这一格之后画一个空位。不落库，每次现算
 * @param brackets      每句的括号声部标记（原歌词写成「A（B）」双声部时，{@code true} = 这一格唱的是括号里那句），与 {@code filled} 同形状。不落库，每次现算
 * @param pinyin        {@code true} = 这次的 {@code defaults} 是拿 demo 歌词配出来的（页面据此把「没匹配到汉字」的拼音格标红）。判据 = 文本对齐成功 **且** 时间线上有拼音音符（原词含字母的音符），不要求整份模板都是拼音 —— 汉字模板里夹着几格拼音也算（实测《气泡少女》只有 38 个拼音音符，旧判据判否 → 那几格既不显示 demo 汉字也不标红）。**模板级、不是句级**：某一整句一个都没匹配上时该行 {@code defaults} 整行为空，但还是要标红 —— 按行判会把错得最狠的整句全空漏掉。不落库，每次现算
 * @param polyphoneIndex 多音字提醒的全局索引（{@link PolyphoneHint#index}）：**醒目**多音字 → 常用读音音节 + **弱**多音字 → 生僻读音音节 + 音节 → 建议换字。前端每键查一次：`readings` 命中画醒目虚线并给建议换字，`rare` 命中只画一道更暗的虚线（不建议换字）。不落库，每次现算
 * @param trackIndices  勾选的轨序号
 * @param lyricFileName 分句用的歌词文件名（样例歌词优先），没有歌词时为 null
 * @param lrcOffset     分句用的整体偏移（秒），无歌词时为 null
 * @param fromCache     true = 直接读了库里已固化的骨架，false = 这次新解析的
 * @param lyricNotice   分句质量提示（null = 不提示）：参照歌词与模板文本对不上时，页面上那句
 *                      「匹配 12%，句界可能不准」。整句由 {@code LyricFillAligner} 拼好
 *                      （阈值只有那一份），页面只画不判。与 {@code defaults} / {@code pinyin}
 *                      同口径：派生、不落库、每次现算
 */
public record LyricTemplate(Long fillId,
                            String fillName,
                            String svpPath,
                            String originalName,
                            String artist,
                            java.math.BigDecimal bpm,
                            List<FillTrack> tracks,
                            List<FillLine> lines,
                            List<List<String>> filled,
                            List<List<String>> defaults,
                            List<List<Boolean>> gaps,
                            List<List<Boolean>> brackets,
                            boolean pinyin,
                            PolyphoneHint.Index polyphoneIndex,
                            List<Integer> trackIndices,
                            String lyricFileName,
                            Double lrcOffset,
                            boolean fromCache,
                            String lyricNotice) {

    /**
     * 一个歌唱轨。
     *
     * @param trackIndex 轨号，<b>按工程显示顺序</b>排定：{@code 0} = SynthV 面板上最上面那条
     *                   歌唱轨，往后依次。由 {@code dispOrder} 决定、排完重新编号（见
     *                   {@link LyricFillParser} 类头），<b>不是</b> svp {@code tracks[]} 的
     *                   数组下标 —— 实测两者经常不一致（《栖凰》数组序与面板序完全不同）。
     *                   页面上的一切排序 / 编号（{@code #n} 轨号、轨色、句内组号、导出先后）
     *                   都按它走，于是跟用户点开工程看到的顺序一致
     * @param trackName  轨名（角色 / 声部）
     * @param notes      按 onset 排序的音符
     */
    public record FillTrack(int trackIndex, String trackName, List<FillNote> notes) {
    }

    /**
     * 一个音符。只留填词要用的字段，{@code pitch} 等一律丢弃。
     *
     * @param onset    起始时间（blick）
     * @param duration 时长（blick）
     * @param lyrics   原始槽位取值（喉塞音前缀撇号已剥掉，见 {@code glottal}）
     * @param slotType 槽位类型
     * @param glottal  喉塞音标记：svp 里这一格写的是 {@code '曾}（前缀撇号，SynthV 的
     *                 喉塞起音记号）。解析时撇号剥掉、{@code lyrics} 留核心字，这里记
     *                 「有标记」；填词照常填核心字，导出回填文本时把 {@code '} 前缀拼回去
     *                 （SynthV 才能保持喉塞起音），lrc 歌词则不含它
     */
    public record FillNote(long onset, long duration, String lyrics, SlotType slotType,
                           boolean glottal) {

        /** 兼容构造器：无喉塞标记的旧形态（也方便测试构造普通音符）。 */
        public FillNote(long onset, long duration, String lyrics, SlotType slotType) {
            this(onset, duration, lyrics, slotType, false);
        }
    }

    /**
     * 时间线上的一个槽位，回指到具体音符。与音符一一对应（DASH / BREATH / ZERO 也有槽位）。
     *
     * <p><b>填了字的记号格</b>：界面上点一下 {@code -} / {@code 0} 槽位就能往里填一个字，
     * 保存时该槽位的 {@code slotType} 就地改成 {@code HANZI}、{@code original} 仍是
     * {@code "-"} / {@code "0"}（{@code br} 不可手填）。用 {@code original} 做标记而不是给
     * 这个 record 加字段：老库的 {@code lines_json} 里没有那个字段，加了解析还得兼容。
     * 改完之后「需填字数」自然把它算进去（{@link SlotType#fillable()}），而
     * {@link LyricFillAligner#originalTextOf} 靠 {@code original} 是记号把它排除在原词之外。
     * 导出不看 {@code slotType} —— 填词是按槽位下标回指音符的
     * （{@link LyricFillAligner#filledByNote}）。
     *
     * @param trackIndex 所属轨
     * @param noteIndex  在该轨 notes 里的下标
     * @param original   原词（音符的 lyrics 值；喉塞音前缀撇号已剥掉，见 {@code glottal}）
     * @param slotType   槽位类型
     * @param glottal    喉塞音标记（与 {@link FillNote#glottal} 同源）：这一格在 svp 里带
     *                   前缀撇号。只作标记用——原词 / 匹配 / lrc 都按剥掉撇号的核心字走，
     *                   导出回填文本时再拼回 {@code '} 前缀。老库 {@code lines_json} 没有
     *                   这个字段，fastjson2 读进来是 {@code false}，旧数据不受影响
     */
    public record FillSlot(int trackIndex, int noteIndex, String original, SlotType slotType,
                           boolean glottal) {

        /** 兼容构造器：无喉塞标记的旧形态。 */
        public FillSlot(int trackIndex, int noteIndex, String original, SlotType slotType) {
            this(trackIndex, noteIndex, original, slotType, false);
        }
    }

    /**
     * 一句（填词输入单位）。
     *
     * @param slots        句内槽位，按声部组序（组间按 onset、组内按轨序 + onset）
     * @param originalText 原词（拼接可填槽位的原值）
     * @param needCount    需填字数（= 可填槽位数，含合唱重复组）
     * @param startOnset   句首槽位的 onset，导出 lrc 的时间戳
     * @param groups       每个槽位所属的声部组号（句内从 0 递增），与 {@code slots} 等长；
     *                     null = 旧数据未分组（整句一组）。合唱 / 和声轨的音符相同时并为
     *                     同一组，在界面上是同一句里的多组「原词 + 填写框」
     */
    public record FillLine(List<FillSlot> slots, String originalText, int needCount, long startOnset,
                           List<Integer> groups) {

        /** 兼容构造器：未分组的旧形态（整句一组）。 */
        public FillLine(List<FillSlot> slots, String originalText, int needCount, long startOnset) {
            this(slots, originalText, needCount, startOnset, null);
        }
    }
}
