package io.github.Nyameph.nyaentworks.song.fill.ai;

import io.github.Nyameph.nyaentworks.common.lyric.LyricLine;
import io.github.Nyameph.nyaentworks.song.fill.LyricFillAligner;
import io.github.Nyameph.nyaentworks.song.fill.LyricFillParser;
import io.github.Nyameph.nyaentworks.song.fill.LyricFillService;
import io.github.Nyameph.nyaentworks.song.fill.LyricTemplate.FillLine;
import io.github.Nyameph.nyaentworks.song.fill.LyricTemplate.FillNote;
import io.github.Nyameph.nyaentworks.song.fill.LyricTemplate.FillSlot;
import io.github.Nyameph.nyaentworks.song.fill.LyricTemplate.FillTrack;
import io.github.Nyameph.nyaentworks.song.fill.SlotType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方案表的「原句 / 当前填词 / 字数」按导出 lrc 口径（{@code lrcText} / {@code lrcFilled} /
 * {@code lrcCount}，填词助手设计 §6.1）与主韵（{@code mainRhymeBody}）。
 * 纯函数，可离线跑；fixture 硬编码，不读磁盘。
 */
public class FillAiPlanLrcTest {

    // ==================== 主韵（众数 / 平票 / 全 null） ====================

    /** 众数：出现次数最多的那个韵身（与「不限」的句比较，多者胜）。 */
    @Test
    public void mainRhyme_takesTheMode() {
        List<FillAiService.PlanLine> lines = List.of(
                line(0, "an"), line(1, "an"), line(2, "en"), line(3, "an"));
        assertEquals("an", FillAiService.mainRhymeBody(lines, List.of(0, 1, 2, 3)));
    }

    /** 平票（两个韵身并列最多）→ 没有占多数的韵。 */
    @Test
    public void mainRhyme_tieIsNull() {
        List<FillAiService.PlanLine> lines = List.of(
                line(0, "an"), line(1, "en"), line(2, "an"), line(3, "en"));
        assertNull(FillAiService.mainRhymeBody(lines, List.of(0, 1, 2, 3)));
    }

    /** 一句都拆不出韵（英文尾 / 拼音串拆不出）→ 没有占多数的韵。 */
    @Test
    public void mainRhyme_allNullIsNull() {
        List<FillAiService.PlanLine> lines = List.of(line(0, null), line(1, null), line(2, null));
        assertNull(FillAiService.mainRhymeBody(lines, List.of(0, 1, 2)));
        assertNull(FillAiService.mainRhymeBody(List.of(), List.of()));
    }

    /** 「不限」不是「一个韵」而是「这句没有韵」，所以不参与投票：1 句 an + 9 句不限仍是 an。 */
    @Test
    public void mainRhyme_ignoresUnlimitedLines() {
        List<FillAiService.PlanLine> mostlyUnlimited = new ArrayList<>();
        mostlyUnlimited.add(line(0, "an"));
        for (int i = 1; i < 10; i++) {
            mostlyUnlimited.add(line(i, null));
        }
        assertEquals("an", FillAiService.mainRhymeBody(mostlyUnlimited,
                List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)));
        // 多数句押 an、少数不限 → 仍是 an（不限不参与投票，投也投不出结果）
        assertEquals("an", FillAiService.mainRhymeBody(
                List.of(line(0, "an"), line(1, "an"), line(2, "an"), line(3, null), line(4, null)),
                List.of(0, 1, 2, 3, 4)));
    }

    /** 全句都不限（英文尾 / 拆不出韵）→ 没有占多数的韵（下拉置灰）。 */
    @Test
    public void mainRhyme_allUnlimitedIsNull() {
        assertNull(FillAiService.mainRhymeBody(
                List.of(line(0, null), line(1, null)), List.of(0, 1)));
    }

    /** refill 只算勾选的句（{@code continue} / {@code full} 算全部）：同一个方案两种目标两种主韵。 */
    @Test
    public void mainRhyme_countsOnlyTargetLines() {
        List<FillAiService.PlanLine> lines = List.of(
                line(0, "an"), line(1, "an"), line(2, "en"), line(3, "en"), line(4, "en"));
        assertEquals("an", FillAiService.mainRhymeBody(lines, List.of(0, 1, 2)));      // 2 an : 1 en
        assertEquals("en", FillAiService.mainRhymeBody(lines, List.of(0, 1, 2, 3, 4))); // 3 en : 2 an
        assertNull(FillAiService.mainRhymeBody(lines, List.of()));                      // 没有目标句
        assertEquals("en", FillAiService.mainRhymeBody(lines, null));                   // 空 targets = 全算
    }

    /** 主韵的标签与逐句的标签同一写法（前端下拉与方案表列不会两样）。 */
    @Test
    public void mainRhymeLabelMatchesLineLabel() {
        String body = "an";
        assertEquals(FillAiPrompts.rhymeLabelOf(body),
                FillAiPrompts.rhymeLabelOf(FillAiService.mainRhymeBody(
                        List.of(line(0, body)), List.of(0))));
    }

    // ==================== 原句 / 字数按导出 lrc 口径 ====================

    private static long blick(double second) {
        return Math.round(second * LyricFillParser.BLICK_PER_SECOND);
    }

    private static FillNote note(String lyrics, double second, double duration) {
        return new FillNote(blick(second), blick(duration), lyrics,
                LyricFillParser.slotTypeOf(lyrics));
    }

    private static FillTrack track(int index, FillNote... notes) {
        return new FillTrack(index, "轨" + index, List.of(notes));
    }

    /**
     * 一句 2 个声部、每声部 2 格、两组逐音符完全相同（整轨复制成「副本」的工程）：
     * 导出 lrc 只会写一份 → {@code lrcText} = 2 字、{@code lrcCount} = 2，
     * 而 {@code needCount}（可填槽位数）仍是 4。前端「字数」与模型看到的字数一致（都是 2）。
     */
    @Test
    public void twinCopyGroupsMergeIntoOneLrcLine() {
        FillTrack a = track(0, note("甲", 0.0, 0.5), note("乙", 0.5, 0.5));
        FillTrack b = track(1, note("甲", 0.0, 0.5), note("乙", 0.5, 0.5));   // 完全相同的副本轨
        FillLine line = new FillLine(
                List.of(new FillSlot(0, 0, "甲", SlotType.HANZI),
                        new FillSlot(0, 1, "乙", SlotType.HANZI),
                        new FillSlot(1, 0, "甲", SlotType.HANZI),
                        new FillSlot(1, 1, "乙", SlotType.HANZI)),
                "甲乙甲乙", 4, 0L, List.of(0, 0, 1, 1));

        List<String> texts = LyricFillService.lrcTexts(
                List.of(a, b), List.of(0, 1), List.of(line), List.of(), List.of()).original();

        assertEquals(List.of("甲乙"), texts);
        assertEquals(4, line.needCount());                                   // 槽位数没变
        assertEquals(2, LyricFillAligner.tokenize(texts.getFirst()).size()); // 字数基准 = 2
    }

    /** 被包含的声部（短的那组 ⊆ 长的那组）同样只写长的那个，字数按合并后算。 */
    @Test
    public void coveredGroupMergesIntoTheLongerOne() {
        FillTrack a = track(0, note("甲", 0.0, 0.5), note("乙", 0.5, 0.5), note("丙", 1.0, 0.5));
        FillTrack b = track(1, note("甲", 0.0, 0.5), note("乙", 0.5, 0.5));
        FillLine line = new FillLine(
                List.of(new FillSlot(0, 0, "甲", SlotType.HANZI),
                        new FillSlot(0, 1, "乙", SlotType.HANZI),
                        new FillSlot(0, 2, "丙", SlotType.HANZI),
                        new FillSlot(1, 0, "甲", SlotType.HANZI),
                        new FillSlot(1, 1, "乙", SlotType.HANZI)),
                "甲乙丙甲乙", 5, 0L, List.of(0, 0, 0, 1, 1));

        List<String> texts = LyricFillService.lrcTexts(
                List.of(a, b), List.of(0, 1), List.of(line), List.of(), List.of()).original();

        assertEquals(List.of("甲乙丙"), texts);
    }

    /**
     * 两个互不包含的声部（都不带括号）：导出写成一行的两段（空格连接），
     * 字数 = 两段之和 —— 模型给一行「N 字」，回填时各声部按组吃掉自己的格子。
     */
    @Test
    public void disjointGroupsJoinWithSpaceInOneLine() {
        FillTrack a = track(0, note("我", 0.0, 0.5), note("在", 0.5, 0.5));
        FillTrack b = track(1, note("你", 0.0, 0.5), note("好", 0.5, 0.5));
        FillLine line = new FillLine(
                List.of(new FillSlot(0, 0, "我", SlotType.HANZI),
                        new FillSlot(0, 1, "在", SlotType.HANZI),
                        new FillSlot(1, 0, "你", SlotType.HANZI),
                        new FillSlot(1, 1, "好", SlotType.HANZI)),
                "我在你好", 4, 0L, List.of(0, 0, 1, 1));

        List<String> texts = LyricFillService.lrcTexts(
                List.of(a, b), List.of(0, 1), List.of(line), List.of(), List.of()).original();

        assertEquals(List.of("我在 你好"), texts);
        assertEquals(4, LyricFillAligner.tokenize(texts.getFirst()).size());  // 空格不占字数
    }

    /**
     * 括号声部（原歌词是「A（B）」）：导出写成 {@code A（B）}，两个全角括号各算 1 个单元，
     * 于是 {@code lrcCount} 比实际格数多 2（模型若照原样回「A（B）」正好对上；不带括号回
     * 「AB」会被判字数不足，那条由下面的缺口容忍兜住）。这条钉住 {@code lrcText} / {@code lrcCount}
     * 本身的口径：与导出 lrc 完全一致，不因为「括号不占格」而动它。
     */
    @Test
    public void bracketVoiceLineCountsTheParens() {
        FillTrack a = track(0, note("我", 0.0, 0.5), note("在", 0.5, 0.5));
        FillTrack b = track(1, note("你", 0.3, 0.5), note("好", 0.8, 0.5));  // 括号声部稍后开口
        List<LyricLine> lyrics = List.of(new LyricLine(0.0, null, "我在（你好）"));
        // 分句与真实链路一样先跑 split（括号标记是它算出来的，槽位分组也由它定）
        LyricFillAligner.SplitResult split =
                LyricFillAligner.split(List.of(a, b), List.of(0, 1), lyrics, null);

        List<String> texts = LyricFillService.lrcTexts(List.of(a, b), List.of(0, 1),
                split.lines(), lyrics, List.of()).original();

        assertEquals(List.of("我在（你好）"), texts);
        assertEquals(6, LyricFillAligner.tokenize(texts.getFirst()).size());   // 4 格 + 2 个括号
    }

    // ==================== 括号句的缺口容忍（validateLine / lengthMark） ====================

    /**
     * 括号句的 {@code lrcCount} 是 6，但两个全角括号是记谱用的结构符号、不占格
     * （前端 {@code batchUnits} 会把它们丢掉）→ 模型真实可落字数是 4。缺口容忍 = 1 + 2 = 3，
     * 于是「照抄括号回 6 字」与「不带括号回 4 字」都通过（后者 ⚠），少 1 字（3 字）也照通用规则
     * 放行；再少（2 字）与超限（7 字）仍不合格 —— 两条路都不会白重试一轮。
     */
    @Test
    public void bracketLineToleratesTheParenGap() {
        String lrcText = "我在（你好）";
        int need = LyricFillAligner.tokenize(lrcText).size();      // 6
        int slack = FillAiService.structuralSlack(lrcText);        // 2
        assertEquals(6, need);
        assertEquals(2, slack);

        FillAiService.LineCheck exact = FillAiService.validateLine("我在（你好）", need, null, slack);
        assertTrue(exact.ok());
        assertFalse(exact.warning());                              // 照抄括号：正合适

        FillAiService.LineCheck noParen = FillAiService.validateLine("我在你好", need, null, slack);
        assertTrue(noParen.ok());                                  // 不带括号：⚠ 放行
        assertTrue(noParen.warning());
        assertEquals("少 2 字", noParen.message());

        assertTrue(FillAiService.validateLine("我在你", need, null, slack).ok());   // 3 字：⚠ 放行
        assertFalse(FillAiService.validateLine("我在", need, null, slack).ok());    // 2 字：不足
        assertFalse(FillAiService.validateLine("我在（你好）呀", need, null, slack).ok()); // 7 字：超限
        // 非括号句 slack=0：缺口容忍仍是通用那 1 字
        assertTrue(FillAiService.validateLine("春风又绿江南", 7, null, 0).ok());
        assertFalse(FillAiService.validateLine("春风又绿江", 7, null, 0).ok());
    }

    /** {@code lengthMark}（界面上的 ✓/⚠/✗）与 {@code validateLine} 同一口径。 */
    @Test
    public void lengthMarkMatchesValidateLine() {
        for (int units = 1; units <= 8; units++) {
            String mark = FillAiService.lengthMark(units, 6, 2);
            boolean pass = FillAiService.validateLine("甲".repeat(units), 6, null, 2).ok();
            assertEquals(!"fail".equals(mark), pass, units + " 单元：mark=" + mark);
        }
        assertEquals("ok", FillAiService.lengthMark(6, 6, 2));
        assertEquals("warn", FillAiService.lengthMark(4, 6, 2));   // 不带括号那 4 格
        assertEquals("fail", FillAiService.lengthMark(7, 6, 2));   // 超限不放宽
    }

    /**
     * 原词里的 {@code -}（延音占位）不进 lrc，也不算字数；{@code br}（换气）同样的口径。
     * 句内空位（视觉空位）在 lrc 里是空格，同样不占字数。
     */
    @Test
    public void dashAndBreathDoNotCountAsUnits() {
        FillTrack a = track(0, note("甲", 0.0, 0.5), note("-", 0.5, 0.5),
                note("br", 1.0, 0.3), note("乙", 1.3, 0.5));
        FillLine line = new FillLine(
                List.of(new FillSlot(0, 0, "甲", SlotType.HANZI),
                        new FillSlot(0, 1, "-", SlotType.DASH),
                        new FillSlot(0, 2, "br", SlotType.BREATH),
                        new FillSlot(0, 3, "乙", SlotType.HANZI)),
                "甲-乙", 3, 0L);

        List<String> texts = LyricFillService.lrcTexts(
                List.of(a), List.of(0), List.of(line), List.of(), List.of()).original();

        String text = texts.getFirst();
        assertFalse(text.contains("-"), text);
        assertFalse(text.contains("br"), text);
        assertEquals(2, LyricFillAligner.countUnits(text), text);   // 补出的空格不占字数
    }

    /** 与导出的 lrc 正文同口径：{@code lrcCount} 就是 {@code tokenize(lrcText).size()}（逐例自洽）。 */
    @Test
    public void lrcCountIsTokenizeSizeOfLrcText() {
        FillTrack a = track(0, note("春", 0.0, 0.5), note("风", 0.5, 0.5), note("又", 1.0, 0.5));
        FillLine line = new FillLine(
                List.of(new FillSlot(0, 0, "春", SlotType.HANZI),
                        new FillSlot(0, 1, "风", SlotType.HANZI),
                        new FillSlot(0, 2, "又", SlotType.HANZI)),
                "春风又", 3, 0L);

        List<String> texts = LyricFillService.lrcTexts(
                List.of(a), List.of(0), List.of(line), List.of(), List.of()).original();

        assertEquals(List.of("春风又"), texts);
        assertEquals(3, LyricFillAligner.tokenize(texts.getFirst()).size());
        assertEquals(LyricFillAligner.countUnits(texts.getFirst()),
                LyricFillAligner.tokenize(texts.getFirst()).size());
    }

    /**
     * 拼音模板：原句要的是<b>歌词里的汉字</b>（导出那份的 defaults 回落），不是粘在一起的拼音串
     * —— 粘成一串的 ASCII token 在 {@code tokenize} 里整串只算 1 个单元，字数会离谱地小。
     */
    @Test
    public void pinyinTemplateFallsBackToMatchedHanzi() {
        FillTrack a = track(0, note("chun", 0.0, 0.5), note("feng", 0.5, 0.5),
                note("you", 1.0, 0.5));
        List<LyricLine> lyrics = List.of(new LyricLine(0.0, null, "春风又"));
        LyricFillAligner.SplitResult split =
                LyricFillAligner.split(List.of(a), List.of(0), lyrics, null);

        List<String> texts = LyricFillService.lrcTexts(List.of(a), List.of(0),
                split.lines(), lyrics, List.of()).original();

        assertEquals(List.of("春风又"), texts);
        assertEquals(3, LyricFillAligner.tokenize(texts.getFirst()).size());
    }

    // ==================== 当前填词（lrcFilled） ====================

    /**
     * 当前填词也走同一条拼装：一句 2 个声部副本、每声部 2 格，只填了保留组那两格 →
     * lrc 口径合并后<b>只有 N 字</b>（副本那份镜像不重复出字），与导出 lrc 完全一致。
     */
    @Test
    public void lrcFilledMergesTwinVoices() {
        FillTrack a = track(0, note("甲", 0.0, 0.5), note("乙", 0.5, 0.5));
        FillTrack b = track(1, note("甲", 0.0, 0.5), note("乙", 0.5, 0.5));   // 完全相同的副本轨
        FillLine line = new FillLine(
                List.of(new FillSlot(0, 0, "甲", SlotType.HANZI),
                        new FillSlot(0, 1, "乙", SlotType.HANZI),
                        new FillSlot(1, 0, "甲", SlotType.HANZI),
                        new FillSlot(1, 1, "乙", SlotType.HANZI)),
                "甲乙甲乙", 4, 0L, List.of(0, 0, 1, 1));
        // 当前填词：保留组填「春 风」，副本组照前端的 mirrorCopies 也有一份（合并后不重复出字）
        List<List<String>> filled = List.of(List.of("春", "风", "春", "风"));

        LyricFillService.LrcTexts texts = LyricFillService.lrcTexts(
                List.of(a, b), List.of(0, 1), List.of(line), List.of(), filled);

        assertEquals("春风", texts.filled().getFirst());                       // 只出 N 字
        assertEquals(2, LyricFillAligner.tokenize(texts.filled().getFirst()).size());
        assertEquals("甲乙", texts.original().getFirst());                     // 原词那份没被带偏
    }

    /**
     * 没填过的句给<b>空串</b>（不是原词、也不是 {@code null}）：前端拿它当输入框默认值，
     * 回落成原词会看着像「已经填过了」，{@code null} 会画成 "null"。
     */
    @Test
    public void lrcFilledIsEmptyWhenUntouched() {
        FillTrack a = track(0, note("甲", 0.0, 0.5), note("乙", 0.5, 0.5));
        FillTrack b = track(1, note("丙", 0.0, 0.5), note("丁", 0.5, 0.5));
        FillLine line = new FillLine(
                List.of(new FillSlot(0, 0, "甲", SlotType.HANZI),
                        new FillSlot(0, 1, "乙", SlotType.HANZI),
                        new FillSlot(1, 0, "丙", SlotType.HANZI),
                        new FillSlot(1, 1, "丁", SlotType.HANZI)),
                "甲乙丙丁", 4, 0L, List.of(0, 0, 1, 1));

        // 没有任何填词值（空表 / 全空串 / 缺这一句）都是「没填过」
        for (List<List<String>> filled : List.of(List.<List<String>>of(),
                List.of(List.of("", "", "", "")), List.of(List.of("", "")))) {
            LyricFillService.LrcTexts texts = LyricFillService.lrcTexts(
                    List.of(a, b), List.of(0, 1), List.of(line), List.of(), filled);
            assertEquals("", texts.filled().getFirst(), "filled=" + filled);
            assertEquals("甲乙 丙丁", texts.original().getFirst());   // 原词那份照旧
        }
    }

    // ==================== JSONL（语料配对）那条链没被改坏 ====================

    /**
     * 语料导出的句子行仍是「原词 + 该原词自己的字数」：{@code CorpusController.pairsJsonl} 传的是
     * {@code countUnits(originalText)} 与 {@code originalText}（不是 lrc 口径），拼装函数没换语义。
     */
    @Test
    public void jsonlChainStillUsesItsOwnTextAndCount() {
        String originalText = "春风又绿江南岸";
        int count = LyricFillAligner.countUnits(originalText);
        assertEquals(7, count);
        assertEquals("1(7字, " + FillAiPrompts.rhymeLabelOf("十六唐") + ")：" + originalText,
                FillAiPrompts.sentenceLine(1, count, "十六唐", originalText, 0));
    }

    /** 英文词整词算 1：JSONL 那条链的字数与文本同口径（与 lrc 口径同一套 tokenize）。 */
    @Test
    public void jsonlChainCountsEnglishWordsAsOne() {
        String originalText = "跟我一起 move on";
        assertEquals(6, LyricFillAligner.countUnits(originalText));   // 4 汉字 + 2 英文词
        assertEquals("1(6字, 不限)：跟我一起 move on（含英文 2 词）",
                FillAiPrompts.sentenceLine(1, LyricFillAligner.countUnits(originalText), null,
                        originalText, 2));
    }

    // ==================== 辅助 ====================

    /** 一行方案：主韵只看 index / rhymeBody，其余字段随便给。 */
    private static FillAiService.PlanLine line(int index, String rhymeBody) {
        return new FillAiService.PlanLine(index, 0, "原词", "词", rhymeBody,
                rhymeBody == null ? null : FillAiPrompts.rhymeLabelOf(rhymeBody),
                false, 0, "原词", "", 2);
    }

    /** 主韵只吃 rhymeBody：与「原句 / 填词 / 字数」字段无关（同一行几种口径互不牵连）。 */
    @Test
    public void mainRhymeIgnoresTextAndCountFields() {
        FillAiService.PlanLine longText = new FillAiService.PlanLine(0, 12, "一二三四五六",
                "六", "an", FillAiPrompts.rhymeLabelOf("an"), false, 0, "一二三 四五六", "", 6);
        assertEquals("an", FillAiService.mainRhymeBody(List.of(longText), List.of(0)));
        assertTrue(longText.lrcCount() != longText.needCount());
    }
}
