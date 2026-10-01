package io.github.Nyameph.nyaentworks.song.fill;

import org.junit.jupiter.api.Test;
import io.github.Nyameph.nyaentworks.song.entity.SongOriginalSetting;
import io.github.Nyameph.nyaentworks.song.fill.LyricTemplate.FillLine;
import io.github.Nyameph.nyaentworks.song.fill.LyricTemplate.FillNote;
import io.github.Nyameph.nyaentworks.song.fill.LyricTemplate.FillSlot;
import io.github.Nyameph.nyaentworks.song.fill.LyricTemplate.FillTrack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * lrc 导出（填词工具设计 9）。测文本拼装与「空白期补空行」（句间 + 结尾）的时间口径 ——
 * 句末时刻按音符的 {@code onset + duration} 算，延音算、换气不算。fixture 硬编码。
 * 另含「重新解析」的旧轨号 → 新轨号重映射（音符序列优先、轨名次之、位次兜底，见第 53 条）。
 *
 * <p><b>每份 lrc 末尾恒定有一行曲终空歌词</b>（最后一个音符结束 + 1 秒，见 §13 第 74 条），
 * 所以「正文到此为止」这一类断言一律先过 {@link #bodyOf(String)}。
 */
public class LyricFillServiceLrcTest {

    /**
     * 去掉末尾那行曲终空歌词后的正文（含换行）。测「最后一行正文长什么样」时用它 ——
     * 直接 {@code endsWith} 会被那行恒定的空歌词挡住。
     */
    private static String bodyOf(String lrc) {
        return lrc.substring(0, lrc.lastIndexOf('\n', lrc.length() - 2) + 1);
    }

    private static long blick(double seconds) {
        return Math.round(seconds * LyricFillParser.BLICK_PER_SECOND);
    }

    private static FillNote note(String lyrics, double second, double duration) {
        return new FillNote(blick(second), blick(duration), lyrics, LyricFillParser.slotTypeOf(lyrics));
    }

    /** 喉塞音符：svp 里写作 `'字`（前缀撇号），解析后留核心字 + glottal 标记。 */
    private static FillNote gNote(String lyrics, double second, double duration) {
        return new FillNote(blick(second), blick(duration), lyrics,
                LyricFillParser.slotTypeOf(lyrics), true);
    }

    /** 一个音轨 + 按音符顺序铺满的槽位（第 k 个音符 → 第 k 个槽位），可拼成一句。 */
    private record Fixture(FillTrack track, List<FillSlot> slots) {
    }

    private static Fixture fixture(int trackIndex, FillNote... notes) {
        List<FillSlot> slots = new ArrayList<>(notes.length);
        for (int i = 0; i < notes.length; i++) {
            slots.add(new FillSlot(trackIndex, i, notes[i].lyrics(), notes[i].slotType(),
                    notes[i].glottal()));
        }
        return new Fixture(new FillTrack(trackIndex, "填词" + trackIndex, List.of(notes)), slots);
    }

    /** 该 fixture 单独成句（句首 = 首个音符的 onset）。 */
    private static FillLine line(Fixture f) {
        return new FillLine(List.copyOf(f.slots()), "词", f.slots().size(),
                f.track().notes().get(0).onset());
    }

    private static SongOriginalSetting setting() {
        SongOriginalSetting s = new SongOriginalSetting();
        s.setRawName("测试曲");
        s.setArtist("测试歌手");
        return s;
    }

    /** 三句：第 1 句唱到 2 秒，第 2 句 10 秒起（空 8 秒 → 补空行），第 3 句紧接第 2 句（不补）。 */
    @Test
    public void blankLineOnLongGap() {
        Fixture a = fixture(0, note("第", 0.5, 0.5), note("一", 1.0, 1.0));
        Fixture b = fixture(1, note("第", 10.0, 0.5));
        Fixture c = fixture(2, note("三", 11.0, 0.5));

        String lrc = LyricFillService.buildLrc(setting(),
                List.of(a.track(), b.track(), c.track()),
                List.of(line(a), line(b), line(c)),
                List.of(List.of("第", "一"), List.of("二"), List.of("三")), null);

        assertEquals("""
                [ti:测试曲]
                [00:00.50]第一
                [00:02.00]
                [00:10.00]二
                [00:11.00]三
                [00:12.50]
                """, lrc);
    }

    /** 间隔 1 秒整不算空白期（阈值是「超过」），多 1 毫秒才算。 */
    @Test
    public void gapThresholdIsStrictlyGreaterThanOneSecond() {
        Fixture a = fixture(0, note("甲", 0.0, 1.0));      // 1.0 秒唱完
        Fixture b = fixture(1, note("乙", 2.0, 1.0));      // 间隔 1.000 秒 → 不补
        Fixture c = fixture(0, note("丙", 4.0, 1.0));      // 5.0 秒唱完
        Fixture d = fixture(1, note("丁", 6.001, 1.0));    // 间隔 1.001 秒 → 补

        String exactly = LyricFillService.buildLrc(setting(), List.of(a.track(), b.track()),
                List.of(line(a), line(b)), List.of(List.of("甲"), List.of("乙")), null);
        assertFalse(exactly.contains("[00:01.00]\n"), exactly);

        String over = LyricFillService.buildLrc(setting(), List.of(c.track(), d.track()),
                List.of(line(c), line(d)), List.of(List.of("丙"), List.of("丁")), null);
        assertTrue(over.contains("[00:05.00]\n"), over);
    }

    /** 句内空格（视觉空位）在 lrc 里还原：gap=true 的槽位之后补一个空格。 */
    @Test
    public void lineTextRestoresInnerSpaces() {
        // 模拟「五百年前一场疯 腾霄又是孙悟空」：第 7 字之后是歌词句内空格
        Fixture f = fixture(0, note("疯", 0.0, 0.5), note("腾", 0.5, 0.5), note("霄", 1.0, 0.5));

        String lrc = LyricFillService.buildLrc(setting(), List.of(f.track()),
                List.of(line(f)),
                List.of(List.of("一场疯", "", "")),
                List.of(),
                List.of(List.of(true, false, false)),
                null);
        assertTrue(lrc.contains("[00:00.00]一场疯 腾霄"), lrc);

        // 没传 gaps（老重载）或 gap=false 时不加空格
        String plain = LyricFillService.buildLrc(setting(), List.of(f.track()),
                List.of(line(f)),
                List.of(List.of("一场疯", "", "")),
                List.of(),
                List.of(List.of(false, false, false)),
                null);
        assertTrue(plain.contains("[00:00.00]一场疯腾霄"), plain);

        // 延音格自己不出字，但空位标记被推到延音链尾之后（LyricFillAligner#prolongationEnd）：
        // 句里已经写过字 → 这一格的空位补在整串延音之后
        Fixture g = fixture(0, note("甲", 0.0, 0.5), note("-", 0.5, 0.5), note("乙", 1.0, 0.5));
        String withDash = LyricFillService.buildLrc(setting(), List.of(g.track()),
                List.of(line(g)),
                List.of(List.of("", "", "")),
                List.of(),
                List.of(List.of(false, true, false)),
                null);
        assertTrue(withDash.contains("[00:00.00]甲 乙"), withDash);

        // 句首的延音格上标空位不产生行首空格（与 br 空位同口径：空格是「词唱完了」的分隔，
        // 前面一个字都还没唱就不该有）
        Fixture lead = fixture(0, note("-", 0.0, 0.5), note("甲", 0.5, 0.5));
        String leadDash = LyricFillService.buildLrc(setting(), List.of(lead.track()),
                List.of(line(lead)), List.of(List.of("", "")), List.of(),
                List.of(List.of(true, false)), null);
        assertTrue(leadDash.contains("[00:00.00]甲"), leadDash);
    }

    /**
     * 批量填词写进格子的「-」占位符**不进歌词**，不论来源（填的词 / 匹配汉字 / 原词）——
     * 它只负责占位对齐，唱出来就错了。纯占位符的格子不算「有产出」，后面不补视觉空位空格。
     */
    @Test
    public void lineTextStripsDashPlaceholders() {
        Fixture f = fixture(0, note("我", 0.0, 0.5), note("在", 0.5, 0.5),
                note("这", 1.0, 0.5), note("里", 1.5, 0.5));
        List<FillTrack> tracks = List.of(f.track());
        List<FillLine> lines = List.of(line(f));

        // ① 填的词是「-」（批量填词把占位符当普通单元写进格子，界面上看得见）
        String filled = LyricFillService.buildLrc(setting(), tracks, lines,
                List.of(List.of("我", "-", "这", "里")), null);
        assertTrue(bodyOf(filled).endsWith("[00:00.00]我这里\n"), filled);

        // ② 匹配汉字是「-」
        String viaDefault = LyricFillService.buildLrc(setting(), tracks, lines,
                List.of(List.of("", "", "", "")),
                List.of(List.of("我", "-", "这", "里")), null);
        assertTrue(bodyOf(viaDefault).endsWith("[00:00.00]我这里\n"), viaDefault);

        // ③ 占位符剔干净后这一格什么都不剩 → 它后面不补视觉空位空格（否则导出一个孤空格）
        String withGap = LyricFillService.buildLrc(setting(), tracks, lines,
                List.of(List.of("我", "-", "这", "里")), List.of(),
                List.of(List.of(false, true, false, false)), null);
        assertTrue(bodyOf(withGap).endsWith("[00:00.00]我这里\n"), withGap);
    }

    /** 句尾换气不算「字」：末尾 br 的时长不延长句末时刻（否则空白期会算短、甚至算没）。 */
    @Test
    public void trailingBreathDoesNotExtendLineEnd() {
        Fixture a = fixture(0, note("甲", 0.0, 1.0), note("br", 1.0, 2.0));
        Fixture b = fixture(1, note("乙", 4.5, 1.0));

        String lrc = LyricFillService.buildLrc(setting(), List.of(a.track(), b.track()),
                List.of(line(a), line(b)), List.of(List.of("甲", ""), List.of("乙")), null);

        // 句末按 1.0 秒算：4.5 − 1.0 = 3.5 秒 > 1 秒 → 补空行，时间戳是换气前那个字的结束
        assertTrue(lrc.contains("[00:01.00]\n"), lrc);
    }

    /** 延音算「字」：末尾 - 拉长时句末时刻跟着往后，间隔不满阈值（1 秒整，阈值是「超过」）就不补。 */
    @Test
    public void trailingDashExtendsLineEnd() {
        Fixture a = fixture(0, note("甲", 0.0, 1.0), note("-", 1.0, 3.0));
        Fixture b = fixture(1, note("乙", 5.0, 1.0));

        String lrc = LyricFillService.buildLrc(setting(), List.of(a.track(), b.track()),
                List.of(line(a), line(b)), List.of(List.of("甲", ""), List.of("乙")), null);

        // 句末 = 4.0 秒，间隔 1 秒 → 不补
        assertFalse(lrc.contains("[00:04.00]\n"), lrc);
    }

    /** 结尾的间奏同样补空行：最后一句唱完到全曲结束（所有轨最晚的音符结束）超过 1 秒。 */
    @Test
    public void blankLineAtEndWhenOutroIsLong() {
        // 甲唱到 1 秒，之后是同轨一个 5 秒起、4 秒长的换气 → 全曲到 9 秒结束
        Fixture a = fixture(0, note("甲", 0.0, 1.0), note("br", 5.0, 4.0));

        String lrc = LyricFillService.buildLrc(setting(), List.of(a.track()),
                List.of(line(a)), List.of(List.of("甲", "")), null);

        // 两行空歌词：第 3 行是「末句唱完」的间奏清屏，第 4 行是恒定的曲终清屏
        assertEquals("""
                [ti:测试曲]
                [00:00.00]甲
                [00:01.00]
                [00:10.00]
                """, lrc);
    }

    /** 曲终恒定补一行空歌词：最后一个音符结束 + 1 秒，正文之外不牵扯别的。 */
    @Test
    public void tailBlankLineAlwaysFollowsLastNote() {
        // 末句尾音就是全曲最后一个音符（唱完即曲终，间奏那一支不触发）→ 只有这一行
        Fixture a = fixture(0, note("甲", 0.0, 1.0));

        String lrc = LyricFillService.buildLrc(setting(), List.of(a.track()),
                List.of(line(a)), List.of(List.of("甲")), null);

        assertEquals("""
                [ti:测试曲]
                [00:00.00]甲
                [00:02.00]
                """, lrc);

        // 时间按「所有轨里最晚一个音符的结束」算，不是句末那个字 —— 别的轨拖得更长也认
        Fixture b = fixture(1, note("乙", 3.0, 2.0));
        String two = LyricFillService.buildLrc(setting(), List.of(a.track(), b.track()),
                List.of(line(a), line(b)), List.of(List.of("甲"), List.of("乙")), null);
        assertTrue(two.endsWith("[00:06.00]\n"), two);   // 5.0 秒结束 + 1 秒

        // 一个音符都没有（空模板）：没有「最后音符」可依，不补 —— 只剩头
        assertEquals("[ti:测试曲]\n", LyricFillService.buildLrc(setting(), List.of(),
                List.of(), List.of(), null));
    }

    /**
     * 曲终那行的「1 秒」按<b>模板曲速</b>折算：blick 是「1 拍 = 705600000」，与 bpm 无关，
     * 直接加 {@code BLICK_PER_SECOND}（120bpm 口径的 1 秒）在 60bpm 上会变成 2 秒。
     */
    @Test
    public void tailBlankLineSecondFollowsTempo() {
        Fixture a = fixture(0, note("甲", 0.0, 1.0));   // 1 秒（120bpm 口径）= 1411200000 blick
        SongOriginalSetting slow = setting();
        slow.setBpm(new java.math.BigDecimal(60));      // 60bpm：同样的 blick 是 2 秒

        String lrc = LyricFillService.buildLrc(slow, List.of(a.track()),
                List.of(line(a)), List.of(List.of("甲")), null);

        // 甲唱到 2.0 秒（60bpm 口径），+1 秒 = 3.0 秒；按 120bpm 口径加就错了，是 4.0 秒
        assertEquals("[ti:测试曲]\n[00:00.00]甲\n[00:03.00]\n", lrc);
    }

    /** 拼音模板没填到：defaults（匹配出的汉字）优先于原拼音写进歌词。 */
    @Test
    public void buildLrcPrioritizesMatchedHanziOverPinyin() {
        Fixture a = fixture(0, note("ni", 0.0, 0.5), note("hao", 0.5, 0.5));
        List<FillTrack> tracks = List.of(a.track());
        List<FillLine> lines = List.of(line(a));
        List<List<String>> filled = List.of(List.of("", ""));       // 都没填
        List<List<String>> defaults = List.of(List.of("你", "好"));  // 匹配出的汉字

        String lrc = LyricFillService.buildLrc(setting(), tracks, lines, filled, defaults, null);

        assertTrue(bodyOf(lrc).endsWith("[00:00.00]你好\n"), lrc);
    }

    /** 没填到的槽位回落原词，换气槽位不写进歌词文本。 */
    @Test
    public void textFallsBackToOriginalAndSkipsBreath() {
        Fixture a = fixture(0, note("原", 0.0, 0.5), note("br", 0.5, 0.5), note("词", 1.0, 0.5));

        String lrc = LyricFillService.buildLrc(setting(), List.of(a.track()),
                List.of(line(a)), List.of(List.of("新", "", "")), null);

        assertTrue(bodyOf(lrc).endsWith("[00:00.00]新词\n"), lrc);
    }

    /** 头部：offsetMs 为 0 / null 都不写 [offset:]。 */
    @Test
    public void offsetOnlyWhenNonZero() {
        Fixture a = fixture(0, note("甲", 0.0, 1.0));
        List<FillTrack> tracks = List.of(a.track());
        List<FillLine> lines = List.of(line(a));
        List<List<String>> filled = List.of(List.of("甲"));

        assertFalse(LyricFillService.buildLrc(setting(), tracks, lines, filled, 0).contains("[offset:"));
        assertFalse(LyricFillService.buildLrc(setting(), tracks, lines, filled, null).contains("[offset:"));
        assertTrue(LyricFillService.buildLrc(setting(), tracks, lines, filled, 500).contains("[offset:+500]"));
        assertTrue(LyricFillService.buildLrc(setting(), tracks, lines, filled, -500).contains("[offset:-500]"));
    }

    /** 需求 3：合唱句里某组的音符完全被另一组包含 → 只导出长的那组（两组相同也只导一份）。 */
    @Test
    public void buildLrcDropsCoveredGroup() {
        Fixture a = fixture(0, note("甲", 0.0, 0.5), note("乙", 0.5, 0.5), note("丙", 1.0, 0.5));
        Fixture b = fixture(1, note("甲", 0.0, 0.5), note("乙", 0.5, 0.5));
        // 一句：轨0 的全部 3 槽（组0）+ 轨1 的 2 槽（组1），轨1 ⊆ 轨0
        FillLine line = new FillLine(
                List.of(new FillSlot(0, 0, "甲", SlotType.HANZI),
                        new FillSlot(0, 1, "乙", SlotType.HANZI),
                        new FillSlot(0, 2, "丙", SlotType.HANZI),
                        new FillSlot(1, 0, "甲", SlotType.HANZI),
                        new FillSlot(1, 1, "乙", SlotType.HANZI)),
                "甲乙丙甲乙", 5, 0L,
                List.of(0, 0, 0, 1, 1));

        String lrc = LyricFillService.buildLrc(setting(),
                List.of(a.track(), b.track()),
                List.of(line),
                List.of(List.of("壹", "", "", "", "")), null);

        // 只导出长的那组（轨0 的甲乙丙，甲填了「壹」），轨1 重复的「甲乙」不再出现
        assertTrue(lrc.contains("[00:00.00]壹乙丙\n"), lrc);
    }

    /**
     * 两组<b>完全相同</b>（互相包含）只导一份：整轨复制成「副本」的工程（实测《免我蹉跎苦》，
     * 每句两组逐音符完全相同）——原先两组都被判「被包含」而全丢，导出的 lrc 整行只剩时间戳。
     */
    @Test
    public void buildLrcKeepsOneOfIdenticalGroups() {
        Fixture a = fixture(0, note("甲", 0.0, 0.5), note("乙", 0.5, 0.5));
        Fixture b = fixture(1, note("甲", 0.0, 0.5), note("乙", 0.5, 0.5));   // 完全相同的副本轨
        FillLine line = new FillLine(
                List.of(new FillSlot(0, 0, "甲", SlotType.HANZI),
                        new FillSlot(0, 1, "乙", SlotType.HANZI),
                        new FillSlot(1, 0, "甲", SlotType.HANZI),
                        new FillSlot(1, 1, "乙", SlotType.HANZI)),
                "甲乙甲乙", 4, 0L,
                List.of(0, 0, 1, 1));

        String lrc = LyricFillService.buildLrc(setting(),
                List.of(a.track(), b.track()),
                List.of(line),
                List.of(List.of("新", "", "", "")), null);

        // 组0（填「新」+回落「乙」）保留导出，完全相同的组1 不再重复
        assertTrue(lrc.contains("[00:00.00]新乙\n"), lrc);
    }

    /** 括号声部：原歌词是「A（B）」双声部时，导出写回括号形式（主流在前、括号流在后）。 */
    @Test
    public void buildLrcWrapsBracketVoiceInParens() {
        Fixture a = fixture(0, note("我", 0.0, 0.5), note("在", 0.5, 0.5));
        Fixture b = fixture(1, note("你", 0.0, 0.5), note("好", 0.5, 0.5));
        // 一句两组：轨0 的「我在」（组0）+ 轨1 的「你好」（组1，括号声部）
        FillLine line = new FillLine(
                List.of(new FillSlot(0, 0, "我", SlotType.HANZI),
                        new FillSlot(0, 1, "在", SlotType.HANZI),
                        new FillSlot(1, 0, "你", SlotType.HANZI),
                        new FillSlot(1, 1, "好", SlotType.HANZI)),
                "我在你好", 4, 0L,
                List.of(0, 0, 1, 1));

        String lrc = LyricFillService.buildLrc(setting(),
                List.of(a.track(), b.track()),
                List.of(line),
                List.of(List.of("我", "在", "你", "好")),
                List.of(), List.of(),
                List.of(List.of(false, false, true, true)), null);

        assertTrue(lrc.contains("[00:00.00]我在（你好）\n"), lrc);
    }

    /** 主流那一组全是休止（没词）时省略主流，写成「（B）」；没传 brackets 的老重载行为不变。 */
    @Test
    public void buildLrcBracketOnlyAndNoBracketsFallback() {
        Fixture a = fixture(0, note("-", 0.0, 0.5));    // 主流声部这一句是休止
        Fixture b = fixture(1, note("你", 0.0, 0.5));
        FillLine line = new FillLine(
                List.of(new FillSlot(0, 0, "-", SlotType.DASH),
                        new FillSlot(1, 0, "你", SlotType.HANZI)),
                "你", 1, 0L, List.of(0, 1));

        String only = LyricFillService.buildLrc(setting(),
                List.of(a.track(), b.track()), List.of(line),
                List.of(List.of("", "你")), List.of(), List.of(),
                List.of(List.of(false, true)), null);
        assertTrue(only.contains("[00:00.00]（你）\n"), only);

        // 老重载（没有 brackets）：两组照旧用空格连接，与改动前一致
        Fixture c = fixture(0, note("我", 0.0, 0.5));
        FillLine other = new FillLine(
                List.of(new FillSlot(0, 0, "我", SlotType.HANZI),
                        new FillSlot(1, 0, "你", SlotType.HANZI)),
                "我你", 2, 0L, List.of(0, 1));
        String plain = LyricFillService.buildLrc(setting(),
                List.of(c.track(), b.track()), List.of(other),
                List.of(List.of("我", "你")), null);
        assertTrue(plain.contains("[00:00.00]我 你\n"), plain);
    }

    /**
     * 一句里两个互不包含的声部：谁先写进歌词看<b>轨号</b>（工程里的顺序），不看槽位在句内的
     * 先后 —— 补进来的 br / 延音会让槽位序跳，《栖凰》「谯鼓响」那句的组标签实测排成 11 / 7 / 12。
     */
    @Test
    public void buildLrcOrdersVoicesByTrack() {
        Fixture a = fixture(2, note("乙", 1.0, 0.5));    // 轨 2 唱得晚，但轨号小
        Fixture b = fixture(4, note("甲", 0.0, 0.5));    // 轨 4 唱得早
        FillLine line = new FillLine(
                List.of(new FillSlot(4, 0, "甲", SlotType.HANZI),
                        new FillSlot(2, 0, "乙", SlotType.HANZI)),
                "甲乙", 2, 0L, List.of(1, 0));

        String lrc = LyricFillService.buildLrc(setting(),
                List.of(a.track(), b.track()), List.of(line),
                List.of(List.of("甲", "乙")), List.of(), List.of(),
                List.of(), null);

        assertTrue(lrc.contains("[00:00.00]乙 甲\n"), lrc);
    }

    /** 需求：多音字的回填替换只改导出的回填模板文本，歌词本体（filled 值）不动。 */
    @Test
    public void buildTrackTextsAppliesPolySwap() {
        Fixture a = fixture(0, note("zhong", 0.0, 0.5), note("yi", 0.5, 0.5));
        long k0 = LyricFillAligner.key(0, 0);
        long k1 = LyricFillAligner.key(0, 1);

        // 回填模板文本：音符 0 的「重」换成单音字「众」，音符 1 的「一」不动
        var texts = LyricFillService.buildTrackTexts(List.of(a.track()),
                Map.of(k0, "重", k1, "一"), Map.of(), Map.of(k0, "众"));

        assertEquals("众 一", texts.getFirst().text());
    }

    /**
     * 喉塞音（svp 里 {@code '曾} 一类，解析后核心字 + glottal 标记）：回填模板文本把
     * {@code '} 前缀拼回去 —— 不论值来自填的词、匹配汉字还是原词回落（SynthV 靠前缀
     * 保持喉塞起音）。
     */
    @Test
    public void buildTrackTextsRestoresGlottalPrefix() {
        Fixture f = fixture(0, gNote("曾", 0.0, 0.5), note("看", 0.5, 0.5));
        long k0 = LyricFillAligner.key(0, 0);
        long k1 = LyricFillAligner.key(0, 1);

        // ① 填「啊」→ '啊；旁边普通格对照（无前缀）
        var filled = LyricFillService.buildTrackTexts(List.of(f.track()),
                Map.of(k0, "啊", k1, "看"), Map.of(), Map.of());
        assertEquals("'啊 看", filled.getFirst().text());

        // ② 没填 → 回落原词，撇号照样拼回（不丢标记）
        var plain = LyricFillService.buildTrackTexts(List.of(f.track()),
                Map.of(), Map.of(), Map.of());
        assertEquals("'曾 看", plain.getFirst().text());

        // ③ 拼音模板匹配出的汉字（defaults）也带前缀
        var viaDefault = LyricFillService.buildTrackTexts(List.of(f.track()),
                Map.of(), Map.of(k0, "增", k1, "刊"), Map.of());
        assertEquals("'增 刊", viaDefault.getFirst().text());

        // ④ 回填替换字（多音字 swap）排最前，同样带前缀
        var viaSwap = LyricFillService.buildTrackTexts(List.of(f.track()),
                Map.of(), Map.of(), Map.of(k0, "憎"));
        assertEquals("'憎 看", viaSwap.getFirst().text());
    }

    /**
     * 「导出拼音」开关（{@code pinyin=true}）：回填模板文本里的汉字转成无声调拼音，其余 token
     * （{@code -} / {@code br} / 英文词）原样；替换字与喉塞音前缀各按既定口径走。
     * 转换在**最后一步**做，所以 {@code '曾} → {@code 'ceng}（撇号留着，它表达喉塞起音）。
     */
    @Test
    public void buildTrackTextsExportsPinyin() {
        Fixture f = fixture(0, gNote("曾", 0.0, 0.5), note("行", 0.5, 0.5),
                note("-", 1.0, 0.5), note("br", 1.5, 0.3), note("hi", 1.8, 0.5));
        long k0 = LyricFillAligner.key(0, 0);
        long k1 = LyricFillAligner.key(0, 1);

        // ① 全部回落原词：汉字转拼音（多音字取常用读音），占位 / 换气 / 英文原样
        var plain = LyricFillService.buildTrackTexts(List.of(f.track()),
                Map.of(), Map.of(), Map.of(), true);
        assertEquals("'ceng xing - br hi", plain.getFirst().text());

        // ② 开关关着（老重载）：与改动前完全一致
        var off = LyricFillService.buildTrackTexts(List.of(f.track()),
                Map.of(), Map.of(), Map.of(), false);
        assertEquals("'曾 行 - br hi", off.getFirst().text());

        // ③ 填的词、拼音模板匹配出的汉字（defaults）同样转拼音
        var filled = LyricFillService.buildTrackTexts(List.of(f.track()),
                Map.of(k1, "女"), Map.of(k0, "增"), Map.of(), true);
        assertEquals("'zeng nv - br hi", filled.getFirst().text());

        // ④ 回填替换排最前 → 读音跟着替换字走（「行」xíng 换成单音字「航」→ hang）
        var swapped = LyricFillService.buildTrackTexts(List.of(f.track()),
                Map.of(k1, "行"), Map.of(), Map.of(k1, "航"), true);
        assertEquals("'ceng hang - br hi", swapped.getFirst().text());
    }

    /** lrc 歌词不含喉塞音撇号：填的词与原词回落都按核心字走（{@code '曾} 唱出来是「曾」）。 */
    @Test
    public void buildLrcDropsGlottalPrefix() {
        Fixture f = fixture(0, gNote("曾", 0.0, 0.5), gNote("经", 0.5, 0.5));

        // 填了：填的词原样进歌词
        String filled = LyricFillService.buildLrc(setting(), List.of(f.track()),
                List.of(line(f)), List.of(List.of("啊", "过")), null);
        assertTrue(bodyOf(filled).endsWith("[00:00.00]啊过\n"), filled);

        // 没填：回落原词（已剥掉撇号的核心字）
        String plain = LyricFillService.buildLrc(setting(), List.of(f.track()),
                List.of(line(f)), List.of(List.of("", "")), null);
        assertTrue(bodyOf(plain).endsWith("[00:00.00]曾经\n"), plain);
    }

    /**
     * 手动填进换气格的值只进回填文本（SynthV 里 br note 带自定义词），不进 lrc 歌词 ——
     * 换气不是「字」。
     */
    @Test
    public void breathManualFillOnlyGoesToTrackText() {
        Fixture f = fixture(0, note("甲", 0.0, 0.5), note("br", 0.5, 0.5), note("乙", 1.0, 0.5));
        List<FillLine> lines = List.of(line(f));
        List<List<String>> filled = List.of(List.of("新", "哈", "词"));

        String lrc = LyricFillService.buildLrc(setting(), List.of(f.track()),
                lines, filled, null);
        assertTrue(bodyOf(lrc).endsWith("[00:00.00]新词\n"), lrc);

        var texts = LyricFillService.buildTrackTexts(List.of(f.track()),
                LyricFillAligner.filledByNote(lines, filled), Map.of(), Map.of());
        assertEquals("新 哈 词", texts.getFirst().text());
    }

    /**
     * 无歌词匹配时的空位锚点（breathGaps）：br 后的空位在 lrc 里还原成句内空格 ——
     * 但句首 br（前面还没有字）不产生行首空格，句尾 br 不产生尾空格，
     * 与歌词空位相邻时也不叠出双空格。
     */
    @Test
    public void breathAnchorGapRestoresMidLineSpaceOnly() {
        // 句中 br：甲 [br] 乙 → 「新 词」
        Fixture mid = fixture(0, note("甲", 0.0, 0.5), note("br", 0.5, 0.3), note("乙", 0.8, 0.5));
        String midLrc = LyricFillService.buildLrc(setting(), List.of(mid.track()),
                List.of(line(mid)), List.of(List.of("新", "", "词")), List.of(),
                List.of(List.of(false, true, false)), null);
        assertTrue(bodyOf(midLrc).endsWith("[00:00.00]新 词\n"), midLrc);

        // 句首 br：空位只是格间留白，不导出行首空格
        Fixture lead = fixture(0, note("br", 0.0, 0.3), note("甲", 0.3, 0.5));
        String leadLrc = LyricFillService.buildLrc(setting(), List.of(lead.track()),
                List.of(line(lead)), List.of(List.of("", "新")), List.of(),
                List.of(List.of(true, false)), null);
        assertTrue(bodyOf(leadLrc).endsWith("[00:00.00]新\n"), leadLrc);

        // 句尾 br：后面没有字再进来，不留尾空格
        Fixture tail = fixture(0, note("甲", 0.0, 0.5), note("br", 0.5, 0.3));
        String tailLrc = LyricFillService.buildLrc(setting(), List.of(tail.track()),
                List.of(line(tail)), List.of(List.of("新", "")), List.of(),
                List.of(List.of(false, true)), null);
        assertTrue(bodyOf(tailLrc).endsWith("[00:00.00]新\n"), tailLrc);

        // 歌词空位（甲后的 gap）+ br 空位相邻：只补一个空格
        Fixture both = fixture(0, note("甲", 0.0, 0.5), note("br", 0.5, 0.3), note("乙", 0.8, 0.5));
        String bothLrc = LyricFillService.buildLrc(setting(), List.of(both.track()),
                List.of(line(both)), List.of(List.of("新", "", "词")), List.of(),
                List.of(List.of(true, true, false)), null);
        assertTrue(bodyOf(bothLrc).endsWith("[00:00.00]新 词\n"), bothLrc);
    }

    /** 无歌词匹配的粗分（br + 大空隙）：分句在 br 处断开（br 归下一句句首），空位兜底标在 br 后。 */
    @Test
    public void splitWithoutLyricsAnchorsGapsAfterBreath() {
        Fixture f = fixture(0, note("br", 0.0, 0.3), note("甲", 0.3, 0.5),
                note("br", 0.8, 0.3), note("乙", 1.1, 0.5));

        LyricFillAligner.SplitResult split =
                LyricFillAligner.split(List.of(f.track()), List.of(0), List.of(), null);

        assertEquals(2, split.lines().size());
        assertEquals(List.of(true, false), split.gaps().get(0));
        assertEquals(List.of(true, false), split.gaps().get(1));
    }

    /**
     * lrc 里的空位必须落在延音链**之后**：「数尽更筹 - 听残银漏」这种形状（被匹配的字后面
     * 紧跟一个 {@code -}）在改动前会在「字」与它的 {@code -} 之间插一个空格、把延音与
     * 前面的有效音符断开（实测《夜奔》第 25 / 27 句）。标记挪到链尾后，导出文本一个字符都不变。
     */
    @Test
    public void gapOnProlongationTailKeepsLrcTextIntact() {
        // 数 尽 更 筹 - 听 - 残 银 - 漏 -（「筹」后面是它的延音）
        Fixture f = fixture(0, note("数", 0.0, 0.5), note("尽", 0.5, 0.5), note("更", 1.0, 0.5),
                note("筹", 1.5, 0.5), note("-", 2.0, 0.5), note("听", 2.5, 0.5),
                note("-", 3.0, 0.5), note("残", 3.5, 0.5), note("银", 4.0, 0.5),
                note("-", 4.5, 0.5), note("漏", 5.0, 0.5), note("-", 5.5, 0.5));
        List<FillLine> lines = List.of(line(f));

        List<List<String>> blank = List.of(List.of("", "", "", "", "", "", "", "", "", "", "", ""));

        // 空位挂在链尾的延音格上（「筹」之后的空位由第 3 格改挂第 4 格那个「-」）
        String lrc = LyricFillService.buildLrc(setting(), List.of(f.track()), lines, blank,
                List.of(), List.of(List.of(false, false, false, false, true,
                        false, false, false, false, false, false, false)),
                List.of(), null);
        assertTrue(lrc.contains("[00:00.00]数尽更筹 听残银漏\n"), lrc);

        // 空位若还挂在「筹」上（改动前的落点），导出的文本一模一样 —— 挪标记不动歌词
        String onWord = LyricFillService.buildLrc(setting(), List.of(f.track()), lines, blank,
                List.of(), List.of(List.of(false, false, false, true, false,
                        false, false, false, false, false, false, false)),
                List.of(), null);
        assertEquals(lrc, onWord);
    }

    // ==================== 视觉空位取哪一份 ====================

    /** 一句三格的 fixture（空位用例只关心形状，音符内容无关紧要）。 */
    private static FillLine threeSlots() {
        Fixture f = fixture(0, note("甲", 0.0, 0.5), note("乙", 0.5, 0.5), note("丙", 1.0, 0.5));
        return line(f);
    }

    private static final List<List<Boolean>> DERIVED = List.of(List.of(true, false, false));

    /** 前端刚编辑的优先；形状对不上就退用库里存的；库里那份也不对就退回现算。 */
    @Test
    public void resolveGapsPrefersEditedThenStoredThenDerived() {
        List<FillLine> lines = List.of(threeSlots());
        List<List<Boolean>> edited = List.of(List.of(false, true, false));

        // ① 编辑过的形状对 → 用它（库里那份更好的也不看：页面上的就是准的）
        assertEquals(edited, LyricFillService.resolveGaps(lines, edited, "[[]]", DERIVED));

        // ② 没传（老客户端 / 没编辑过）→ 用库里的
        assertEquals(edited, LyricFillService.resolveGaps(lines, null,
                LyricFillStore.toJson(edited), DERIVED));

        // ③ 库里是 NULL（从没编辑过）→ 现算
        assertEquals(DERIVED, LyricFillService.resolveGaps(lines, null, null, DERIVED));
    }

    /** 形状对不上的空位一律不认：错一格就是**另一个位置上的空格**，比没有更糟。 */
    @Test
    public void resolveGapsRejectsWrongShapes() {
        List<FillLine> lines = List.of(threeSlots());

        // 行内长度不符（3 个槽位只给 2 个）
        assertEquals(DERIVED, LyricFillService.resolveGaps(lines, List.of(List.of(true, false)),
                null, DERIVED));
        // 行数不符
        assertEquals(DERIVED, LyricFillService.resolveGaps(lines,
                List.of(List.of(true, false, false), List.of()), null, DERIVED));
        // 库里那份同样要过形状校验（手改过的脏 JSON）
        assertEquals(DERIVED, LyricFillService.resolveGaps(lines, null, "[[true]]", DERIVED));
        // 空列表也是「对不上」
        assertEquals(DERIVED, LyricFillService.resolveGaps(lines, List.of(), null, DERIVED));
    }

    /** 多行的形状校验逐行看：只错一行就当整份不认。 */
    @Test
    public void resolveGapsChecksEveryRow() {
        Fixture a = fixture(0, note("甲", 0.0, 0.5));
        Fixture b = fixture(1, note("乙", 1.0, 0.5));
        List<FillLine> lines = List.of(line(a), line(b));
        List<List<Boolean>> derived = List.of(List.of(false), List.of(false));

        assertEquals(derived, LyricFillService.resolveGaps(lines,
                List.of(List.of(true), List.of(true, true)), null, derived));
        assertEquals(List.of(List.of(true), List.of(true)), LyricFillService.resolveGaps(lines,
                List.of(List.of(true), List.of(true)), null, derived));
    }

    // ==================== 重新解析：旧轨号 → 新轨号 ====================

    /** 造一条轨（重映射看轨号 + 轨名 + 音符序列）。 */
    private static FillTrack track(int index, String name, FillNote... notes) {
        return new FillTrack(index, name, List.of(notes));
    }

    /**
     * 同名轨要靠<b>音符序列</b>区分（实测 24 个工程有同名歌唱轨，《木偶戏DJ版》23 条轨里一半
     * 重名）—— 两条都叫「副歌」的轨只有音符能说明谁是谁，按位次配会把已填的字配到另一条轨上。
     */
    @Test
    public void remapTracksMatchesByNotesNotByPosition() {
        FillNote[] a = {note("甲", 1.0, 0.5), note("乙", 1.5, 0.5)};
        FillNote[] b = {note("丙", 2.0, 0.5), note("丁", 2.5, 0.5)};
        // 旧（数组序）：A、B；新（面板序）：B、A —— 同名，仅音符不同
        List<FillTrack> old = List.of(track(0, "副歌", a), track(1, "副歌", b));
        List<FillTrack> now = List.of(track(0, "副歌", b), track(1, "副歌", a));

        assertEquals(Map.of(0, 1, 1, 0), LyricFillService.remapTracks(old, now));
    }

    /**
     * 旧库数据的轨号是 svp 数组序，重解析出来的是工程显示序（《栖凰》实测两者完全不同）——
     * 音符对不上（svp 改过）时按轨名配，勾选与已填的字才不会落到别的轨上。
     */
    @Test
    public void remapTracksFallsBackToTrackName() {
        // 旧（数组序）：主歌1-副本1 / 主歌1-副本 / 主歌2 / 和声5
        List<FillTrack> old = List.of(track(0, "主歌1-副本1", note("旧甲", 1.0, 0.5)),
                track(1, "主歌1-副本", note("旧乙", 1.0, 0.5)),
                track(2, "主歌2", note("旧丙", 1.0, 0.5)),
                track(3, "和声5", note("旧丁", 1.0, 0.5)));
        // 新（面板序）：主歌2 / 主歌1-副本1 / 和声5 / 主歌1-副本 —— 音符都改过（换了词）
        List<FillTrack> now = List.of(track(0, "主歌2", note("新丙", 1.0, 0.5)),
                track(1, "主歌1-副本1", note("新甲", 1.0, 0.5)),
                track(2, "和声5", note("新丁", 1.0, 0.5)),
                track(3, "主歌1-副本", note("新乙", 1.0, 0.5)));

        assertEquals(Map.of(0, 1, 1, 3, 2, 0, 3, 2),
                LyricFillService.remapTracks(old, now));
    }

    /** 名字也缺失 / 对不上（老数据没存轨名）时按剩余位次兜底，不会漏轨。 */
    @Test
    public void remapTracksFallsBackToPosition() {
        List<FillTrack> old = List.of(track(0, null, note("旧甲", 1.0, 0.5)),
                track(1, "旧的轨名", note("旧乙", 1.0, 0.5)));
        List<FillTrack> now = List.of(track(0, "主歌1", note("新甲", 1.0, 0.5)),
                track(1, "主歌2", note("新乙", 1.0, 0.5)));

        assertEquals(Map.of(0, 0, 1, 1), LyricFillService.remapTracks(old, now));
    }

    /** 旧骨架的槽位轨号就地换成新轨号，其余字段（原词 / 组号 / 句时间）一律不动。 */
    @Test
    public void remapTrackIndicesRewritesSlotsOnly() {
        FillSlot keep = new FillSlot(0, 1, "甲", LyricFillParser.slotTypeOf("甲"));
        FillSlot move = new FillSlot(2, 5, "乙", LyricFillParser.slotTypeOf("乙"));
        FillLine line = new FillLine(List.of(keep, move), "甲乙", 2, blick(1.0),
                List.of(0, 1));

        List<FillLine> out = LyricFillService.remapTrackIndices(List.of(line),
                Map.of(0, 3, 2, 0));

        assertEquals(1, out.size());
        assertEquals(3, out.get(0).slots().get(0).trackIndex());
        assertEquals(1, out.get(0).slots().get(0).noteIndex());      // 音符下标不动
        assertEquals("甲", out.get(0).slots().get(0).original());
        assertEquals(0, out.get(0).slots().get(1).trackIndex());
        assertEquals(5, out.get(0).slots().get(1).noteIndex());
        assertEquals(blick(1.0), out.get(0).startOnset());
        assertEquals(List.of(0, 1), out.get(0).groups());
    }

    // ==================== 歌词头（2026-10-01，§13 第 75 条） ====================

    /**
     * 给了填词名与署名，头部是「曲名 → 填词名 → 署名 → offset」四行，顺序固定。
     *
     * <p>上面那四个重载都传 {@code null}，所以不对它们额外断言 —— 那些用例的
     * {@code assertEquals} 整份 lrc 逐字比对，本身就在钉「不多写一行」这件事。
     */
    @Test
    public void headerCarriesTitleAlbumAndSignature() {
        Fixture a = fixture(0, note("甲", 0.0, 1.0));

        String lrc = LyricFillService.buildLrc(setting(), List.of(a.track()), List.of(line(a)),
                List.of(List.of("甲")), List.of(), List.of(), List.of(), "我的填词", "某某", null);
        assertEquals("""
                [ti:测试曲]
                [al:我的填词]
                [by:某某]
                [00:00.00]甲
                [00:02.00]
                """, lrc);

        // offset 排在最后：它是播放器解析的数值标签，混在三个文字标签中间读着别扭
        String withOffset = LyricFillService.buildLrc(setting(), List.of(a.track()),
                List.of(line(a)), List.of(List.of("甲")), List.of(), List.of(), List.of(),
                "我的填词", "某某", 500);
        assertTrue(withOffset.startsWith("""
                [ti:测试曲]
                [al:我的填词]
                [by:某某]
                [offset:+500]
                """), withOffset);
    }

    /** 没配署名（null / 空串 / 纯空白）就整行不写；填词名同理。**不写空标签** —— `[by:]` 是假信息。 */
    @Test
    public void noSignatureLineWhenNotConfigured() {
        Fixture a = fixture(0, note("甲", 0.0, 1.0));

        for (String sig : new String[]{null, "", "   "}) {
            String lrc = LyricFillService.buildLrc(setting(), List.of(a.track()), List.of(line(a)),
                    List.of(List.of("甲")), List.of(), List.of(), List.of(), "我的填词", sig, null);
            assertFalse(lrc.contains("[by:"), lrc);
            assertTrue(lrc.contains("[al:我的填词]\n"), lrc);
        }

        for (String name : new String[]{null, "", "   "}) {
            String lrc = LyricFillService.buildLrc(setting(), List.of(a.track()), List.of(line(a)),
                    List.of(List.of("甲")), List.of(), List.of(), List.of(), name, "某某", null);
            assertFalse(lrc.contains("[al:"), lrc);
            assertTrue(lrc.contains("[by:某某]\n"), lrc);
        }
    }

    /**
     * 标签值不许写出畸形 lrc：换行会把标签断成两行（后半截没时间戳、播放器按垃圾行丢），
     * {@code ]} 会提前闭合标签。两个来源都是真会带这些字符的 —— 库里的原曲名、页面上手打的署名。
     */
    @Test
    public void tagValuesCannotBreakTheHeader() {
        SongOriginalSetting setting = setting();
        setting.setRawName("测试]曲\n第二行");
        Fixture a = fixture(0, note("甲", 0.0, 1.0));

        String lrc = LyricFillService.buildLrc(setting, List.of(a.track()), List.of(line(a)),
                List.of(List.of("甲")), List.of(), List.of(), List.of(),
                "第一行\n第二行", "某某]ver.2", null);

        assertTrue(lrc.startsWith("""
                [ti:测试）曲 第二行]
                [al:第一行 第二行]
                [by:某某）ver.2]
                """), lrc);
        // 头部还是恰好三个标签行，没被折行出来的残片撑破（lines() 对结尾换行不产空元素）
        assertEquals(3, lrc.substring(0, lrc.indexOf("[00:00.00]")).lines().count());
    }
}
