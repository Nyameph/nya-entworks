package io.github.Nyameph.nyaentworks.song.fill;

import org.junit.jupiter.api.Test;
import io.github.Nyameph.nyaentworks.song.fill.LyricTemplate.FillLine;
import io.github.Nyameph.nyaentworks.song.fill.LyricTemplate.FillNote;
import io.github.Nyameph.nyaentworks.song.fill.LyricTemplate.FillSlot;
import io.github.Nyameph.nyaentworks.song.fill.LyricTemplate.FillTrack;
import io.github.Nyameph.nyaentworks.common.lyric.LyricLine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 分句与切分（填词工具设计 5 / 6）。纯函数，fixture 硬编码。 */
public class LyricFillAlignerTest {

    private static long blick(double seconds) {
        return Math.round(seconds * LyricFillParser.BLICK_PER_SECOND);
    }

    private static FillNote note(String lyrics, double second, double duration) {
        return new FillNote(blick(second), blick(duration), lyrics, LyricFillParser.slotTypeOf(lyrics));
    }

    private static FillTrack track(int index, FillNote... notes) {
        return new FillTrack(index, "轨" + index, List.of(notes));
    }

    /** 句内按组号过滤槽位（保持槽位序 = onset 序），模拟前端按组渲染一行。 */
    private static List<FillSlot> filterGroup(FillLine line, int group) {
        List<FillSlot> out = new ArrayList<>();
        for (int i = 0; i < line.slots().size(); i++) {
            if (line.groups().get(i) == group) {
                out.add(line.slots().get(i));
            }
        }
        return out;
    }

    private static LyricLine lrc(double start, String text) {
        return new LyricLine(start, null, text);
    }

    // ==================== tokenize ====================

    @Test
    public void tokenize_englishAndChinese() {
        assertEquals(List.of("hello", "world", "天", "涯"), LyricFillAligner.tokenize("hello world 天涯"));
        assertEquals(4, LyricFillAligner.countUnits("hello world 天涯"));
    }

    @Test
    public void tokenize_collapsesSpaces() {
        assertEquals(List.of("多", "空", "格"), LyricFillAligner.tokenize("  多  空 格 "));
        assertEquals(0, LyricFillAligner.countUnits("   "));
        assertEquals(0, LyricFillAligner.countUnits(null));
    }

    @Test
    public void tokenize_punctuationIsItsOwnUnit() {
        assertEquals(List.of("天", "涯", "，"), LyricFillAligner.tokenize("天涯，"));
    }

    @Test
    public void tokenize_englishWordStaysWhole() {
        // 一个英文单词 = 一个字 = 一个槽位
        assertEquals(List.of("hello"), LyricFillAligner.tokenize("hello"));
        // 切分只看空格：没有空格的混排是一个 token，含 ASCII 字母就整体算一个槽位
        assertEquals(List.of("hello天"), LyricFillAligner.tokenize("hello天"));
        assertEquals(List.of("hello", "天"), LyricFillAligner.tokenize("hello 天"));
    }

    // ==================== 合并时间线 ====================

    @Test
    public void merge_sortsByOnset() {
        List<FillTrack> tracks = List.of(
                track(0, note("甲", 1.0, 0.5), note("丙", 3.0, 0.5)),
                track(1, note("乙", 2.0, 0.5)));

        List<FillSlot> slots = LyricFillAligner.merge(tracks, List.of(0, 1));

        assertEquals(3, slots.size());
        assertEquals("甲", slots.get(0).original());
        assertEquals("乙", slots.get(1).original());
        assertEquals("丙", slots.get(2).original());
        assertEquals(1, slots.get(1).trackIndex());
    }

    @Test
    public void merge_onlySelectedTracks() {
        List<FillTrack> tracks = List.of(
                track(0, note("甲", 1.0, 0.5)),
                track(1, note("乙", 2.0, 0.5)));

        assertEquals(1, LyricFillAligner.merge(tracks, List.of(1)).size());
        // null / 空 = 全部歌唱轨
        assertEquals(2, LyricFillAligner.merge(tracks, List.of()).size());
        assertEquals(2, LyricFillAligner.merge(tracks, null).size());
    }

    // ==================== 歌词文本对齐（主路径）====================

    private static final List<FillTrack> FOUR_NOTES = List.of(
            track(0, note("天", 16.0, 0.5), note("涯", 16.5, 0.5),
                    note("若", 18.0, 0.5), note("比", 18.5, 0.5)));

    private static final List<LyricLine> TWO_LINES = List.of(lrc(17.0, "天涯"), lrc(19.0, "若比"));

    @Test
    public void split_lyricTextAlignsLines() {
        LyricFillAligner.SplitResult result =
                LyricFillAligner.split(FOUR_NOTES, List.of(0), TWO_LINES, null);

        // 文本对齐下没有「整体偏移」这回事，offset 返回 null
        assertNull(result.offset());
        assertEquals(2, result.lines().size());
        assertEquals("天涯", result.lines().get(0).originalText());
        assertEquals(2, result.lines().get(0).needCount());
        assertEquals("若比", result.lines().get(1).originalText());
        assertEquals(blick(16.0), result.lines().get(0).startOnset());
    }

    @Test
    public void split_prefersLyricTextOverTimestamps() {
        // 歌词时间戳的累积漂移：17.5 的「丙」早于第二行时间戳 17.6，
        // 按时间戳分会被第一句吞掉（《36.5°C》实测有 8 处这种错位）
        List<FillTrack> tracks = List.of(
                track(0, note("甲", 16.0, 0.5), note("乙", 16.5, 0.5),
                        note("丙", 17.5, 0.5), note("丁", 18.0, 0.5)));

        LyricFillAligner.SplitResult result = LyricFillAligner.split(tracks, List.of(0),
                List.of(lrc(16.0, "甲乙"), lrc(17.6, "丙丁")), null);

        assertNull(result.offset());
        assertEquals(2, result.lines().size());
        assertEquals("甲乙", result.lines().get(0).originalText());
        assertEquals("丙丁", result.lines().get(1).originalText());
    }

    @Test
    public void split_hanziTemplateDoesNotMatchRareReadingHomophone() {
        // 同音回退只认常用读音（2026-09-28）：音符「一」(yi) 不得与歌词「听」互相命中 ——
        // 「听」的全量读音里带着古音 yǐn/yí，拿它兜底会让「一」抢走下一句「听听」的开头，
        // 后半句跟着整段串位（实测《因为爱情》前四句：第 4 句多出一个「听」、轨 2 的
        // 「听到都会红着脸躲避」拆到别处）。这里音符「一张」落在「给你」之后、下一句以
        // 「听」开头，正是那个形状：旧口径下第 1 句会变成「一张听听」。
        List<FillTrack> tracks = List.of(
                track(0, note("给", 16.0, 0.5), note("你", 16.5, 0.5),
                        note("一", 17.0, 0.5), note("张", 17.5, 0.5),
                        note("听", 18.0, 0.5), note("听", 18.5, 0.5)));

        LyricFillAligner.SplitResult result = LyricFillAligner.split(tracks, List.of(0),
                List.of(lrc(16.0, "给你"), lrc(18.0, "听听")), null);

        assertEquals(2, result.lines().size());
        assertEquals("给你一张", result.lines().get(0).originalText());
        assertEquals("听听", result.lines().get(1).originalText());
    }

    @Test
    public void split_extraNoteStaysWithItsLine() {
        // 模板在「丙」后多唱一个「奥」、歌词里没有（实测《36.5°C》的「味道奥」）：
        // 多出的字跟前一个已匹配的字同句，不能让后面每句都错位一格
        List<FillTrack> tracks = List.of(
                track(0, note("甲", 16.0, 0.5), note("乙", 16.5, 0.5), note("丙", 17.0, 0.5),
                        note("奥", 17.5, 0.5), note("丁", 18.0, 0.5), note("戊", 18.5, 0.5)));

        LyricFillAligner.SplitResult result = LyricFillAligner.split(tracks, List.of(0),
                List.of(lrc(16.0, "甲乙丙"), lrc(18.0, "丁戊")), null);

        assertEquals(2, result.lines().size());
        assertEquals("甲乙丙奥", result.lines().get(0).originalText());
        assertEquals(4, result.lines().get(0).needCount());
        assertEquals("丁戊", result.lines().get(1).originalText());
    }

    @Test
    public void split_lyricTextLeadingSlotsMergeIntoFirstLine() {
        List<FillTrack> tracks = List.of(
                track(0, note("br", 15.0, 0.5), note("啊", 15.5, 0.5),
                        note("天", 16.0, 0.5), note("涯", 16.5, 0.5)));

        LyricFillAligner.SplitResult result =
                LyricFillAligner.split(tracks, List.of(0), List.of(lrc(17.0, "天涯")), null);

        // 歌词首字之前的槽位（前奏里唱的字）并进首句，不凭空多出一句；br 不占字数
        assertEquals(1, result.lines().size());
        assertEquals("啊天涯", result.lines().getFirst().originalText());
        assertEquals(3, result.lines().getFirst().needCount());
        assertEquals(blick(15.0), result.lines().getFirst().startOnset());
    }

    @Test
    public void split_lyricTextAlignsEnglishWords() {
        List<FillTrack> tracks = List.of(
                track(0, note("hello", 16.0, 0.5), note("world", 16.5, 0.5),
                        note("天", 17.5, 0.5), note("涯", 18.0, 0.5)));

        LyricFillAligner.SplitResult result = LyricFillAligner.split(tracks, List.of(0),
                List.of(lrc(16.0, "hello world"), lrc(17.6, "天涯")), null);

        assertEquals(2, result.lines().size());
        // 原词是直接拼原值，不补空格（与前端 originalText 一致）
        assertEquals("helloworld", result.lines().get(0).originalText());
        assertEquals(2, result.lines().get(0).needCount());
        assertEquals("天涯", result.lines().get(1).originalText());
    }

    @Test
    public void split_fallsBackToTimestampWhenLyricTextDiffers() {
        // 歌词跟模板唱的不是同一首词（配错文件）：匹配率 0 → 回落时间戳，
        // 偏移 = 17.0 − 16.0 = 1.0
        LyricFillAligner.SplitResult result = LyricFillAligner.split(FOUR_NOTES, List.of(0),
                List.of(lrc(17.0, "甲乙"), lrc(19.0, "丙丁")), null);

        assertEquals(1.0, result.offset(), 0.000_001);
        assertEquals(2, result.lines().size());
        assertEquals("天涯", result.lines().get(0).originalText());
        assertEquals("若比", result.lines().get(1).originalText());
    }

    // ==================== 时间戳分句（回落）====================

    @Test
    public void split_manualOffsetForcesTimestampSplit() {
        // 手填偏移 = 明确要求按时间对，即便文本能对齐也走时间戳
        LyricFillAligner.SplitResult result =
                LyricFillAligner.split(FOUR_NOTES, List.of(0), TWO_LINES, 0.5);

        assertEquals(0.5, result.offset(), 0.000_001);
        // lrc 秒 = svp 秒 + 0.5 → 16.0/16.5/18.0/18.5 变成 16.5/17.0/18.5/19.0：
        // 「天」落到首句窗口之前 → 并进首句，「比」仍归末句
        assertEquals(2, result.lines().size());
        assertEquals("天涯若", result.lines().get(0).originalText());
        assertEquals("比", result.lines().get(1).originalText());
    }

    @Test
    public void split_timestampPathLeadingSlotsMergeIntoFirstLine() {
        List<FillTrack> tracks = List.of(
                track(0, note("br", 15.0, 0.5), note("啊", 15.5, 0.5),
                        note("天", 16.0, 0.5), note("涯", 16.5, 0.5)));

        // 手填偏移 1.0：15.0 / 15.5 都落在首句 17.0 之前
        LyricFillAligner.SplitResult result =
                LyricFillAligner.split(tracks, List.of(0), List.of(lrc(17.0, "天涯")), 1.0);

        // 首句窗口之前的槽位并进首句（br 不占字数），不凭空多出一句
        assertEquals(1, result.lines().size());
        assertEquals("啊天涯", result.lines().getFirst().originalText());
        assertEquals(3, result.lines().getFirst().needCount());
        assertEquals(blick(15.0), result.lines().getFirst().startOnset());
    }

    @Test
    public void splitByLrc_allSlotsBeforeFirstLine() {
        List<FillTrack> tracks = List.of(track(0, note("啊", 15.0, 0.5)));

        LyricFillAligner.SplitResult result =
                LyricFillAligner.split(tracks, List.of(0), List.of(lrc(17.0, "天涯")), -5.0);

        // 整首都落在首句之前：兜底成一句，不丢数据
        assertEquals(1, result.lines().size());
        assertEquals("啊", result.lines().getFirst().originalText());
    }

    // ==================== lrc 元信息过滤 ====================

    @Test
    public void lyricLines_dropsTitleAndMetadata() {
        // 实测原曲 lrc 的开头（demo 歌词没有这些行）
        List<LyricLine> raw = List.of(
                lrc(0.0, "36.5℃ - 音阙诗听 (interestingcn)/李佳思 (Hojo)"),
                lrc(1.42, "词：雪无影"),
                lrc(2.84, "曲：雪无影"),
                lrc(4.26, "编曲：王柏鸿"),
                lrc(5.68, "制作人：殇小谨"),
                lrc(7.10, "混音：李佳韵"),
                lrc(8.52, "母带处理：殇小谨"),
                lrc(9.94, "和声：皎月"),
                lrc(11.36, "和声编写：皎月"),
                lrc(12.78, "配唱制作人：殇小谨"),
                lrc(14.20, "混音室 : Hi Music Studio"),
                lrc(15.62, "出品：音阙诗听"),
                lrc(17.04, "相思是一个辞藻"),
                lrc(19.14, "甜蜜却不觉苦恼"));

        List<LyricLine> kept = LyricFillAligner.lyricLines(raw);

        assertEquals(2, kept.size());
        assertEquals(17.04, kept.getFirst().start(), 0.000_001);
        assertEquals("相思是一个辞藻", kept.getFirst().text());
    }

    /** 原曲 lrc 开头：标题行 + 十几行带时间戳的词曲编曲（demo 歌词没有这些行） */
    private static final List<LyricLine> WITH_METADATA = List.of(
            lrc(0.0, "36.5℃ - 音阙诗听/李佳思"),
            lrc(1.42, "词：雪无影"),
            lrc(17.04, "天涯"),
            lrc(19.14, "若比"));

    @Test
    public void split_metadataLinesAreNotSungText() {
        // 元信息行若不过滤，会当成歌词参与对齐（「词：雪无影」等），把边界全带偏
        LyricFillAligner.SplitResult result =
                LyricFillAligner.split(FOUR_NOTES, List.of(0), WITH_METADATA, null);

        assertNull(result.offset());
        assertEquals(2, result.lines().size());
        assertEquals("天涯", result.lines().get(0).originalText());
        assertEquals("若比", result.lines().get(1).originalText());
    }

    @Test
    public void split_metadataLinesIgnoredInTimestampPath() {
        // 元信息行带时间戳且从 0 秒起：不过滤会把偏移锚到 0 秒，首句窗口吞掉几十个 note。
        // 歌词文本对不上（甲乙丙丁）逼出时间戳路径
        List<LyricLine> raw = List.of(
                lrc(0.0, "36.5℃ - 音阙诗听/李佳思"),
                lrc(1.42, "词：雪无影"),
                lrc(17.04, "甲乙"),
                lrc(19.14, "丙丁"));

        LyricFillAligner.SplitResult result =
                LyricFillAligner.split(FOUR_NOTES, List.of(0), raw, null);

        assertEquals(1.04, result.offset(), 0.000_001);
        // 18.0 + 1.04 = 19.04 仍 < 19.14，「若」留在首句；18.5 + 1.04 = 19.54 归末句
        assertEquals(2, result.lines().size());
        assertEquals("天涯若", result.lines().getFirst().originalText());
    }

    @Test
    public void split_lyricTextMultipleTracksInterleave() {
        List<FillTrack> tracks = List.of(
                track(0, note("甲", 16.0, 0.5), note("丙", 17.0, 0.5)),
                track(1, note("乙", 16.5, 0.5)));

        LyricFillAligner.SplitResult result =
                LyricFillAligner.split(tracks, List.of(0, 1), List.of(lrc(17.0, "甲乙丙")), null);

        // 同唱 / 交替按时间自然呈现，不再分句
        assertEquals(1, result.lines().size());
        assertEquals(List.of("甲", "乙", "丙"),
                result.lines().getFirst().slots().stream().map(FillSlot::original).toList());
        assertEquals(3, result.lines().getFirst().needCount());
    }

    // ==================== br + 大空隙回退 ====================

    @Test
    public void splitByBreathAndGap_whenNoLrc() {
        List<FillTrack> tracks = List.of(
                track(0, note("天", 16.0, 0.5), note("涯", 16.6, 0.5),
                        note("br", 20.0, 0.5), note("若", 20.6, 0.5)));

        LyricFillAligner.SplitResult result = LyricFillAligner.split(tracks, List.of(0), null, null);

        assertNull(result.offset());
        assertEquals(2, result.lines().size());
        assertEquals("天涯", result.lines().get(0).originalText());
        assertEquals(2, result.lines().get(0).needCount());
        // br 落在下一句句首，不占字数
        assertEquals("若", result.lines().get(1).originalText());
        assertEquals(1, result.lines().get(1).needCount());
    }

    @Test
    public void splitByBreathAndGap_ignoresSmallGap() {
        List<FillTrack> tracks = List.of(
                track(0, note("天", 16.0, 0.5), note("涯", 16.75, 0.5)));

        assertEquals(1, LyricFillAligner.split(tracks, List.of(0), null, null).lines().size());
    }

    @Test
    public void split_emptyTimeline() {
        LyricFillAligner.SplitResult result =
                LyricFillAligner.split(List.of(), List.of(), TWO_LINES, null);

        assertEquals(0, result.lines().size());
        assertNull(result.offset());
    }

    // ==================== 派生字段与填词回指 ====================

    @Test
    public void recompute_rebuildsDerivedFields() {
        FillLine broken = new FillLine(
                List.of(new FillSlot(0, 0, "天", SlotType.HANZI),
                        new FillSlot(0, 1, "-", SlotType.DASH),
                        new FillSlot(0, 2, "hello", SlotType.ENGLISH)),
                "错的", 99, 123L);

        FillLine fixed = LyricFillAligner.recompute(broken);

        assertEquals("天hello", fixed.originalText());
        assertEquals(2, fixed.needCount());
        assertEquals(123L, fixed.startOnset());
    }

    @Test
    public void filledByNote_mapsBySlot() {
        List<FillLine> lines = LyricFillAligner.split(FOUR_NOTES, List.of(0), TWO_LINES, null).lines();

        // 每句一行，与句内槽位一一对应；空串 = 这一格没填
        Map<Long, String> map = LyricFillAligner.filledByNote(lines,
                List.of(List.of("新", "词"), List.of("", "界")));

        assertEquals("新", map.get(LyricFillAligner.key(0, 0)));
        assertEquals("词", map.get(LyricFillAligner.key(0, 1)));
        assertNull(map.get(LyricFillAligner.key(0, 2)));   // 空着的格不进表 → 导出回落原词
        assertEquals("界", map.get(LyricFillAligner.key(0, 3)));
    }

    @Test
    public void filledByNote_ignoresBlankSlots() {
        List<FillLine> lines = List.of(new FillLine(
                List.of(new FillSlot(0, 0, "天", SlotType.HANZI),
                        new FillSlot(0, 1, "-", SlotType.DASH),
                        new FillSlot(0, 2, "br", SlotType.BREATH),
                        new FillSlot(0, 3, "涯", SlotType.HANZI)),
                "天涯", 2, 0L));

        Map<Long, String> map = LyricFillAligner.filledByNote(lines,
                List.of(List.of("", "", "", "丁")));

        assertNull(map.get(LyricFillAligner.key(0, 0)));
        assertNull(map.get(LyricFillAligner.key(0, 1)));
        assertNull(map.get(LyricFillAligner.key(0, 2)));
        assertEquals("丁", map.get(LyricFillAligner.key(0, 3)));
    }

    @Test
    public void recompute_openedDashCountsButStaysOutOfOriginalText() {
        // 界面上点开的延音：slotType 改成 HANZI，original 还是 "-"（用它当标记）
        FillLine opened = new FillLine(
                List.of(new FillSlot(0, 0, "天", SlotType.HANZI),
                        new FillSlot(0, 1, "-", SlotType.HANZI),
                        new FillSlot(0, 2, "涯", SlotType.HANZI)),
                "", 0, 0L);

        FillLine fixed = LyricFillAligner.recompute(opened);

        assertEquals("天涯", fixed.originalText());   // 原词里不该多一个 "-"
        assertEquals(3, fixed.needCount());           // 但字数算上它
    }

    @Test
    public void filledByNote_fillsOpenedDash() {
        // 点开填了字的延音：槽位类型已改成 HANZI、original 仍是 "-"
        List<FillLine> lines = List.of(new FillLine(
                List.of(new FillSlot(0, 0, "天", SlotType.HANZI),
                        new FillSlot(0, 1, "-", SlotType.HANZI),
                        new FillSlot(0, 2, "涯", SlotType.HANZI)),
                "天涯", 3, 0L));

        Map<Long, String> map = LyricFillAligner.filledByNote(lines,
                List.of(List.of("甲", "乙", "丙")));

        assertEquals("甲", map.get(LyricFillAligner.key(0, 0)));
        assertEquals("乙", map.get(LyricFillAligner.key(0, 1)));
        assertEquals("丙", map.get(LyricFillAligner.key(0, 2)));
    }

    @Test
    public void filledByNote_doesNotLookAtSlotType() {
        // 格里有值就进表，不管槽位类型是什么 —— 延音被点开填字时前端才把类型改成 HANZI，
        // 按类型分支会漏掉「值在、类型还没改」的数据
        List<FillLine> lines = List.of(new FillLine(
                List.of(new FillSlot(0, 0, "天", SlotType.HANZI),
                        new FillSlot(0, 1, "-", SlotType.DASH)),
                "天", 1, 0L));

        Map<Long, String> map = LyricFillAligner.filledByNote(lines,
                List.of(List.of("", "乙")));

        assertEquals("乙", map.get(LyricFillAligner.key(0, 1)));
    }

    // ==================== 延音槽位归一（syncDashes）====================

    @Test
    public void syncDashes_closesEmptyOpenedDash() {
        // 旧版本前端「点一下先标 HANZI、填不填另说」留下的空 HANZI 延音：还原成 DASH，
        // 否则「需填」会把它算进去、而界面上它又是跳过不填的
        List<FillLine> lines = List.of(new FillLine(
                List.of(new FillSlot(0, 0, "天", SlotType.HANZI),
                        new FillSlot(0, 1, "-", SlotType.HANZI),
                        new FillSlot(0, 2, "涯", SlotType.HANZI)),
                "天涯", 3, 0L));

        List<FillLine> fixed = LyricFillAligner.syncDashes(lines, List.of(List.of("甲", "", "丙")));

        assertEquals(SlotType.DASH, fixed.getFirst().slots().get(1).slotType());
        assertEquals(2, fixed.getFirst().needCount());
        assertEquals("天涯", fixed.getFirst().originalText());
    }

    @Test
    public void syncDashes_opensDashThatHasValue() {
        // 反方向：重跑分句后按句序号保留的旧填词落到延音格上（槽位类型还是 DASH）
        List<FillLine> lines = List.of(new FillLine(
                List.of(new FillSlot(0, 0, "天", SlotType.HANZI),
                        new FillSlot(0, 1, "-", SlotType.DASH)),
                "天", 1, 0L));

        List<FillLine> fixed = LyricFillAligner.syncDashes(lines, List.of(List.of("", "乙")));

        assertEquals(SlotType.HANZI, fixed.getFirst().slots().get(1).slotType());
        assertEquals(2, fixed.getFirst().needCount());
        assertEquals("天", fixed.getFirst().originalText());
    }

    @Test
    public void syncDashes_leavesConsistentLinesAlone() {
        // 已经自洽的行原样返回（同一引用）：调用方靠这个判断要不要重算派生字段
        FillLine line = new FillLine(
                List.of(new FillSlot(0, 0, "天", SlotType.HANZI),
                        new FillSlot(0, 1, "-", SlotType.DASH),
                        new FillSlot(0, 2, "-", SlotType.HANZI)),
                "天", 2, 0L);

        List<FillLine> fixed = LyricFillAligner.syncDashes(List.of(line),
                List.of(List.of("甲", "", "丙")));

        assertEquals(List.of(line), fixed);
        assertEquals(SlotType.DASH, fixed.getFirst().slots().get(1).slotType());
        assertEquals(SlotType.HANZI, fixed.getFirst().slots().get(2).slotType());
    }

    @Test
    public void syncDashes_toleratesMissingRows() {
        List<FillLine> lines = List.of(new FillLine(
                List.of(new FillSlot(0, 0, "-", SlotType.HANZI)), "天", 1, 0L));

        // filled 比 lines 短（或整块为 null）时不炸，缺的行按「没填」处理
        assertEquals(SlotType.DASH,
                LyricFillAligner.syncDashes(lines, List.of()).getFirst().slots().getFirst().slotType());
        assertEquals(SlotType.DASH,
                LyricFillAligner.syncDashes(lines, null).getFirst().slots().getFirst().slotType());
        assertEquals(List.of(), LyricFillAligner.syncDashes(null, null));
    }

    // ==================== 【角色】标记 ====================

    @Test
    public void lyricLines_stripsRoleTags() {
        // 实测《九九八十一柔情版》demo lrc：纯标记行（【西瓜】）与行内标记（【泥鳅】思归、…）
        List<LyricLine> kept = LyricFillAligner.lyricLines(List.of(
                lrc(52.43, "【西瓜】"),
                lrc(53.15, "上路 巩州遇虎熊（熊山君，寅将军）"),
                lrc(60.0, "【泥鳅】思归、【方块】难归")));

        assertEquals(2, kept.size());
        assertEquals("上路 巩州遇虎熊（熊山君，寅将军）", kept.getFirst().text());
        assertEquals("思归、难归", kept.get(1).text());
    }

    // ==================== 未匹配段独立成句 ====================

    @Test
    public void split_unmatchedSectionSplitsByBreathAtPhrases() {
        // 模板唱一段歌词里没有的词（导唱轨），段内乐句边界是 br：
        // 甲乙丙 / 丁戊己 与歌词对不上，天涯 对上。三段各成一句，不粘进「天涯」句。
        List<FillTrack> tracks = List.of(track(0,
                note("br", 1.0, 0.4), note("甲", 1.4, 0.4), note("乙", 1.8, 0.4), note("丙", 2.2, 0.4),
                note("br", 2.6, 0.4), note("丁", 3.0, 0.4), note("戊", 3.4, 0.4), note("己", 3.8, 0.4),
                note("br", 4.2, 0.4), note("天", 4.6, 0.4), note("涯", 5.0, 0.4)));

        LyricFillAligner.SplitResult result =
                LyricFillAligner.split(tracks, List.of(0), List.of(lrc(4.6, "天涯")), null);

        assertEquals(3, result.lines().size());
        assertEquals("甲乙丙", result.lines().get(0).originalText());
        assertEquals("丁戊己", result.lines().get(1).originalText());
        assertEquals("天涯", result.lines().get(2).originalText());
    }

    @Test
    public void split_shortUnmatchedRunStillJoinsPreviousLine() {
        // 零散 1~2 个未命中字（模板多唱的字，实测《36.5°C》的「奥」）仍跟前句，不独立成句
        List<FillTrack> tracks = List.of(track(0,
                note("甲", 1.0, 0.4), note("乙", 1.4, 0.4), note("奥", 1.8, 0.4),
                note("天", 2.2, 0.4), note("涯", 2.6, 0.4)));

        LyricFillAligner.SplitResult result =
                LyricFillAligner.split(tracks, List.of(0), List.of(lrc(2.2, "天涯")), null);

        assertEquals(1, result.lines().size());
        assertEquals("甲乙奥天涯", result.lines().getFirst().originalText());
    }

    // ==================== 延音不落句首 ====================

    @Test
    public void split_leadingDashMovesIntoPrevLine() {
        // 延音格只会出现在句尾：大空隙断点切在延音前时，
        // 句首的延音并回上一句尾巴，不会把延音留在下一句的开头
        List<FillTrack> tracks = List.of(track(0,
                note("甲", 16.0, 0.5), note("乙", 16.5, 0.3),
                note("-", 18.0, 0.5), note("丙", 18.5, 0.5)));

        LyricFillAligner.SplitResult result =
                LyricFillAligner.split(tracks, List.of(0), null, null);

        assertEquals(2, result.lines().size());
        // 句 1 = 甲、乙 + 并回来的延音；句 2 = 只剩丙
        assertEquals(List.of("甲", "乙", "-"),
                result.lines().get(0).slots().stream().map(FillSlot::original).toList());
        assertEquals("丙", result.lines().get(1).originalText());
        assertEquals(1, result.lines().get(1).slots().size());
    }

    @Test
    public void split_allDashLineMergesAway() {
        // 整句都是延音：并回上一句后整句删掉，不留空句
        List<FillTrack> tracks = List.of(track(0,
                note("甲", 16.0, 0.5),
                note("-", 18.0, 0.5), note("-", 18.5, 0.5)));

        LyricFillAligner.SplitResult result =
                LyricFillAligner.split(tracks, List.of(0), null, null);

        assertEquals(1, result.lines().size());
        assertEquals(3, result.lines().getFirst().slots().size());
    }

    @Test
    public void split_dashAtTrackStartStays() {
        // 音轨最开头就是延音（没有上一句可并）：原地保留在首句
        List<FillTrack> tracks = List.of(track(0,
                note("-", 15.0, 0.5), note("甲", 16.0, 0.5)));

        LyricFillAligner.SplitResult result =
                LyricFillAligner.split(tracks, List.of(0), null, null);

        assertEquals(1, result.lines().size());
        assertEquals("-", result.lines().getFirst().slots().getFirst().original());
    }

    // ==================== 声部组（合唱 / 和声）====================

    @Test
    public void split_unisonTracksMergeIntoOneLineWithGroups() {
        // 需求 2：两轨合唱、音符完全一样 → 同一句里 2 组「原词 + 填写框」；
        // 时间线上只留代表音符（每字只出现一次），组号 0/1 标出成员
        List<FillTrack> tracks = List.of(
                track(0, note("甲", 16.0, 0.5), note("乙", 16.5, 0.5)),
                track(1, note("甲", 16.0, 0.5), note("乙", 16.5, 0.5)));

        LyricFillAligner.SplitResult result =
                LyricFillAligner.split(tracks, List.of(0, 1), List.of(lrc(16.0, "甲乙")), null);

        assertEquals(1, result.lines().size());
        FillLine line = result.lines().getFirst();
        // 槽位保持时间线序（onset），组号标注归属：前端按组过滤后各自行内仍是 onset 序
        assertEquals(List.of("甲", "甲", "乙", "乙"), line.slots().stream().map(FillSlot::original).toList());
        assertEquals(List.of(0, 1, 0, 1), line.groups());
        assertEquals(4, line.needCount());
        // 按组过滤 = 两行「原词 + 填写框」，行内各是自己的词序
        assertEquals(List.of("甲", "乙"), filterGroup(line, 0).stream().map(FillSlot::original).toList());
        assertEquals(List.of("甲", "乙"), filterGroup(line, 1).stream().map(FillSlot::original).toList());
    }

    @Test
    public void split_unisonToleratesQuantizeDriftButNotRealDrift() {
        // 实测《aLIEz》：轨2 与轨1 同词音符 onset 差 0.004~0.05s（网格量化）→ 仍算合唱；
        // 差出容差（0.06s）的同位置不同轨音符 → 不再同组 → 拆句
        List<FillTrack> tracks = List.of(
                track(0, note("甲", 16.0, 0.5), note("乙", 16.5, 0.5)),
                track(1, note("甲", 16.04, 0.5), note("乙", 16.54, 0.5)),
                track(2, note("甲", 16.35, 0.5), note("乙", 16.85, 0.5)));

        LyricFillAligner.SplitResult result =
                LyricFillAligner.split(tracks, List.of(0, 1, 2), List.of(lrc(16.0, "甲乙")), null);

        assertEquals(2, result.lines().size());
        // 第 1 句 = 轨0 + 轨1（量化差在容差内的合唱组，2 组成员）
        assertEquals(List.of(0, 1, 0, 1), result.lines().get(0).groups());
        assertEquals(2, result.lines().get(0).needCount() / 2);
        // 第 2 句 = 轨2（重叠但错位超容差 → 强制拆开，只有自己的槽位）
        assertEquals(List.of(0, 0), result.lines().get(1).groups());
        assertEquals(2, result.lines().get(1).slots().getFirst().trackIndex());
    }

    @Test
    public void split_inclusionSharesLineAndMatchedDefaults() {
        // 需求 2：包含关系（轨1 与轨0 前两拍合唱、少唱一拍）→ 同一句显示轨0 的全部 +
        // 轨1 的子段；拼音模板下 demo 的字同时匹配到两组（「一句歌词可以匹配多次」）
        List<FillTrack> tracks = List.of(
                track(0, note("ni", 16.0, 0.5), note("hao", 16.5, 0.5), note("shi", 17.0, 0.5)),
                track(1, note("ni", 16.0, 0.5), note("hao", 16.5, 0.5)));

        LyricFillAligner.SplitResult result =
                LyricFillAligner.split(tracks, List.of(0, 1), List.of(lrc(16.0, "你好世界")), null);

        assertEquals(1, result.lines().size());
        FillLine line = result.lines().getFirst();
        assertEquals(5, line.slots().size());
        assertEquals(List.of(0, 1, 0, 1, 0), line.groups());
        // 默认词：demo 的字两组都拿到（组内成员与代表共享匹配结果，按时间线序展开）
        assertEquals(List.of("你", "你", "好", "好", "世"), result.defaults().getFirst());
        // 按组过滤：轨0 的三个音一行、轨1 的两个音一行
        assertEquals(List.of("ni", "hao", "shi"), filterGroup(line, 0).stream().map(FillSlot::original).toList());
        assertEquals(List.of("ni", "hao"), filterGroup(line, 1).stream().map(FillSlot::original).toList());
    }

    @Test
    public void split_overlappingDifferentNotesSplitsIntoOwnLines() {
        // 需求 1：轨1 16~17s、轨2 16.5~18s 重叠但唱的词不同 → 拆成两句：
        // 第一句只有轨1 的内容，第二句只有轨2 的内容
        List<FillTrack> tracks = List.of(
                track(0, note("甲", 16.0, 0.5), note("乙", 16.5, 0.5)),
                track(1, note("丁", 16.5, 0.5), note("戊", 17.0, 0.5)));

        LyricFillAligner.SplitResult result =
                LyricFillAligner.split(tracks, List.of(0, 1),
                        List.of(lrc(16.0, "甲乙"), lrc(18.0, "丁戊")), null);

        assertEquals(2, result.lines().size());
        assertEquals("甲乙", result.lines().get(0).originalText());
        assertEquals(List.of(0, 0), result.lines().get(0).groups());
        assertEquals("丁戊", result.lines().get(1).originalText());
        assertEquals(List.of(0, 0), result.lines().get(1).groups());
    }

    // ==================== 歌词归一后的配对（2026-09-13，第 56 条）====================

    /**
     * 实测《气泡少女》121.20~121.28s 那一块（4 个音符：两轨各 2 个，前一个音的音尾搭进下一拍）：
     * 主旋律唱 {@code 高 ao}、和声同音符却写 {@code 高 -} —— 同一个音在两条声部里一处记成拼音、
     * 一处记成延音。延音不是词，不参与「谁包含谁」的判定（{@code compatible} 只看唱词），
     * 于是这块仍是一个声部组、两条声部落在同一句，而不是像修前那样和声自成一组、标上强制断句、
     * 把那格单独挤成一句（整首从第 34 句起句句错位）。
     *
     * <p>延音自己配不上对（{@code alignTo} 严格判等，见第 56 条为什么不做破折号通配），
     * 那一格因此不进时间线 —— 实测同位置的和声音符就是这样不在槽位里的；它本来就不可填，
     * 导出按音符走、不受影响。
     */
    @Test
    public void split_restNoteOnTwinVoiceDoesNotSplitTheLine() {
        List<FillTrack> tracks = List.of(
                track(0, note("高", 16.0, 0.6), note("ao", 16.5, 0.5)),
                track(1, note("高", 16.0, 0.6), note("-", 16.5, 0.5)));

        LyricFillAligner.SplitResult result =
                LyricFillAligner.split(tracks, List.of(0, 1), null, null);

        assertEquals(1, result.lines().size());
        FillLine line = result.lines().getFirst();
        assertEquals(List.of("高", "高", "ao"), originals(line));
        assertEquals(List.of(0, 1, 0), line.groups());
    }

    /**
     * 实测《气泡少女》99.00s 那一拍：主旋律写 {@code 两}、和声写 {@code ‘两}（全角撇号前缀）。
     * 解析阶段就把撇号剥掉（第 56 条），于是配对判等成立、两条声部合成一句；归一之前这里是
     * 2 句（和声那格自己成一句）。这里特意走 {@code parseJson} 而不是 {@link #note} 构造，
     * 要覆盖的就是「解析归一 → 配对」这条链。
     */
    @Test
    public void split_fullWidthGlottalPrefixStillPairsWithMelody() {
        long t0 = blick(16.0);
        long d = blick(0.5);
        String svp = """
                {"tracks": [
                  {"name": "主旋律", "mainGroup": {"notes": [
                     {"lyrics": "两", "onset": %d, "duration": %d},
                     {"lyrics": "玩", "onset": %d, "duration": %d}]}},
                  {"name": "和声", "mainGroup": {"notes": [
                     {"lyrics": "‘两", "onset": %d, "duration": %d},
                     {"lyrics": "‘玩", "onset": %d, "duration": %d}]}}]}
                """.formatted(t0, d, t0 + d, d, t0, d, t0 + d, d);
        List<FillTrack> tracks = LyricFillParser.parseJson(svp);

        LyricFillAligner.SplitResult result =
                LyricFillAligner.split(tracks, List.of(0, 1), null, null);

        assertEquals(1, result.lines().size());
        assertEquals(List.of("两", "两", "玩", "玩"), originals(result.lines().getFirst()));
        assertEquals(List.of(0, 1, 0, 1), result.lines().getFirst().groups());
    }

    @Test
    public void syncGroupCopies_fillsBlankSlotFromTwinGroup() {
        // 需求 2 的联动：同句 2 组、同 onset 同词的槽位之间「有值 → 空」双向复制
        List<FillLine> lines = List.of(new FillLine(
                List.of(new FillSlot(0, 0, "甲", SlotType.HANZI),
                        new FillSlot(0, 1, "乙", SlotType.HANZI),
                        new FillSlot(1, 0, "甲", SlotType.HANZI),
                        new FillSlot(1, 1, "乙", SlotType.HANZI)),
                "甲乙甲乙", 4, 0L,
                List.of(0, 0, 1, 1)));
        List<FillTrack> tracks = List.of(
                track(0, note("甲", 16.0, 0.5), note("乙", 16.5, 0.5)),
                track(1, note("甲", 16.0, 0.5), note("乙", 16.5, 0.5)));

        // 轨0 填了、轨1 全空 → 轨1 抄轨0；部分填的格（丙）不被覆盖
        List<List<String>> filled = LyricFillAligner.syncGroupCopies(lines,
                List.of(new ArrayList<>(List.of("壹", "贰", "", ""))), tracks);

        assertEquals(List.of("壹", "贰", "壹", "贰"), filled.getFirst());
        // 反向：轨1 有值轨0 空 → 轨0 抄轨1
        List<List<String>> reversed = LyricFillAligner.syncGroupCopies(lines,
                List.of(new ArrayList<>(List.of("", "", "叁", "肆"))), tracks);

        assertEquals(List.of("叁", "肆", "叁", "肆"), reversed.getFirst());
        // 两边都有值 / 都为空 → 不动
        List<List<String>> untouched = LyricFillAligner.syncGroupCopies(lines,
                List.of(new ArrayList<>(List.of("壹", "", "叁", ""))), tracks);

        assertEquals(List.of("壹", "", "叁", ""), untouched.getFirst());
    }

    // ==================== 括号句（A（B）双声部）====================

    /** 《红马》副歌的 demo 歌词：「主声部（和声）」写法，括号在行尾。 */
    private static final String RED_HORSE_LYRIC = "我在江南（我在江南，撒把欢，多无邪）";

    /**
     * 汉字模板：轨0 唱括号外的「我在江南」、轨1 唱括号里的「我在江南撒把欢多无邪」，
     * 两条声部在时间上交错（轨1 的「我」在轨0 的「南」之前）。
     */
    private static final List<FillTrack> RED_HORSE_HANZI = List.of(
            track(0, note("我", 33.2, 0.5), note("在", 33.7, 0.5),
                    note("江", 34.2, 0.5), note("南", 34.7, 0.5)),
            track(1, note("我", 34.4, 0.5), note("在", 34.9, 0.5), note("江", 35.4, 0.5),
                    note("南", 35.9, 0.5), note("撒", 36.4, 0.5), note("把", 36.9, 0.5),
                    note("欢", 37.4, 0.5), note("多", 37.9, 0.5), note("无", 38.4, 0.5),
                    note("邪", 38.9, 0.5)));

    /** 同一段的拼音模板（轨1 的「，」不在音符里，靠「谁都读不出」的单元跨过去）。 */
    private static final List<FillTrack> RED_HORSE_PINYIN = List.of(
            track(0, note("wo", 33.2, 0.5), note("zai", 33.7, 0.5),
                    note("jiang", 34.2, 0.5), note("nan", 34.7, 0.5)),
            track(1, note("wo", 34.4, 0.5), note("zai", 34.9, 0.5), note("jiang", 35.4, 0.5),
                    note("nan", 35.9, 0.5), note("sa", 36.4, 0.5), note("ba", 36.9, 0.5),
                    note("huan", 37.4, 0.5), note("duo", 37.9, 0.5), note("wu", 38.4, 0.5),
                    note("xie", 38.9, 0.5)));

    /** 句里槽位的原值序（时间线序 = 主流在前、括号声部在后）。 */
    private static List<String> originals(FillLine line) {
        return line.slots().stream().map(FillSlot::original).toList();
    }

    @Test
    public void brackets_hanziDualVoiceLineMergesIntoOneLineAndMarksSubVoice() {
        List<LyricLine> lyrics = List.of(lrc(33.2, RED_HORSE_LYRIC));

        LyricFillAligner.SplitResult result =
                LyricFillAligner.split(RED_HORSE_HANZI, List.of(0, 1), lyrics, null);

        // 一条 lrc 行 = 一句（时间上并不连续）：主流 4 格拿组 0、括号声部 10 格拿组 1
        assertEquals(1, result.lines().size());
        FillLine line = result.lines().getFirst();
        assertEquals(List.of("我", "在", "江", "南",
                "我", "在", "江", "南", "撒", "把", "欢", "多", "无", "邪"), originals(line));
        assertEquals(List.of(0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), line.groups());
        // 句首取两条声部里最先开口的那个
        assertEquals(blick(33.2), line.startOnset());
        // 汉字模板不给默认词（原词行已显示汉字）
        assertTrue(result.defaults().getFirst().stream().allMatch(String::isEmpty));

        // 括号标记：只有括号声部那 10 格为 true，不落库、每次现算
        assertEquals(List.of(List.of(false, false, false, false,
                        true, true, true, true, true, true, true, true, true, true)),
                LyricFillAligner.brackets(RED_HORSE_HANZI, List.of(0, 1), result.lines(), lyrics));
    }

    @Test
    public void brackets_pinyinDualVoiceLineMatchesBothStreams() {
        List<LyricLine> lyrics = List.of(lrc(33.2, RED_HORSE_LYRIC));

        LyricFillAligner.SplitResult result =
                LyricFillAligner.split(RED_HORSE_PINYIN, List.of(0, 1), lyrics, null);

        assertEquals(1, result.lines().size());
        FillLine line = result.lines().getFirst();
        assertEquals(14, line.slots().size());
        assertEquals(List.of(0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), line.groups());
        // 两条流各自对齐：默认词各归各家（括号流跨过歌词里的逗号）
        assertEquals(List.of("我", "在", "江", "南",
                "我", "在", "江", "南", "撒", "把", "欢", "多", "无", "邪"), result.defaults().getFirst());
        assertEquals(List.of(List.of(false, false, false, false,
                        true, true, true, true, true, true, true, true, true, true)),
                LyricFillAligner.brackets(RED_HORSE_PINYIN, List.of(0, 1), result.lines(), lyrics));
    }

    /**
     * 《红马》主歌那一段（实测声部）：轨0 唱主声部、轨1 唱括号声部，两条声部在时间上交错。
     * 轨1 的「ni tan yi - - - qu」中间三个延音在时间线上紧挨着<b>轨0</b> 的「ling」——延音
     * 跟着「时间线前一个原子」就会跑进「绫绸缎」那一句，把乐句切碎、还给这一句凭空多出一个
     * 声部（用户实测报的就是这个：`ni tan yi qu` / `- - -` / `qu fan hua xiu he shan - -` 被
     * 拆到三句里）。
     */
    private static final List<LyricLine> RED_HORSE_VERSE_LYRICS = List.of(
            lrc(23.25, "你在清涧（你弹一曲）"), lrc(24.5, "胭脂伞"), lrc(25.5, "绫绸缎"),
            lrc(27.25, "你弹一曲（繁华绣河山）"), lrc(28.5, "繁华绣河山"));

    private static final List<FillTrack> RED_HORSE_VERSE = List.of(
            track(0, note("ni", 23.25, 0.25), note("zai", 23.5, 0.25),
                    note("qing", 23.75, 0.25), note("jian", 24.0, 0.5),
                    note("yan", 24.5, 0.25), note("zhi", 24.75, 0.25), note("san", 25.0, 0.5),
                    note("ling", 25.5, 0.25), note("chou", 25.75, 0.25), note("-", 26.0, 0.25),
                    note("duan", 26.25, 0.5),
                    note("ni", 27.25, 0.25), note("tan", 27.5, 0.25), note("yi", 27.75, 0.25),
                    note("qu", 28.0, 0.5),
                    note("fan", 28.5, 0.25), note("hua", 28.75, 0.5), note("xiu", 29.25, 0.5),
                    note("he", 29.75, 0.25), note("shan", 30.0, 0.5)),
            track(1, note("ni", 24.0, 0.5), note("tan", 24.5, 0.5), note("yi", 25.0, 0.5),
                    note("-", 25.5, 0.25), note("-", 25.75, 0.25), note("-", 26.0, 0.25),
                    note("qu", 26.25, 1.5),
                    note("fan", 28.0, 0.5), note("hua", 28.5, 0.5), note("xiu", 29.0, 0.5),
                    note("he", 30.031, 0.469), note("shan", 30.5, 0.5),
                    note("-", 31.0, 0.25), note("-", 31.25, 0.25)));

    @Test
    public void brackets_innerDashesStayWithTheirOwnVoice() {
        LyricFillAligner.SplitResult result =
                LyricFillAligner.split(RED_HORSE_VERSE, List.of(0, 1), RED_HORSE_VERSE_LYRICS, null);

        // 一句 = 一条 lrc 行，延音不会另起一句、也不会串到隔壁那一句
        assertEquals(5, result.lines().size());

        // 你在清涧（你弹一曲）：括号声部整段连在一起，三个 `-` 留在 yi 与 qu 中间
        assertEquals(List.of("ni", "zai", "qing", "jian",
                        "ni", "tan", "yi", "-", "-", "-", "qu"), originals(result.lines().get(0)));
        assertEquals(List.of(0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1), result.lines().get(0).groups());
        assertEquals(List.of("yan", "zhi", "san"), originals(result.lines().get(1)));
        // 绫绸缎：轨1 的延音不再混进来（这一句本来就只有一个声部）
        assertEquals(List.of("ling", "chou", "-", "duan"), originals(result.lines().get(2)));
        assertEquals(List.of(0, 0, 0, 0), result.lines().get(2).groups());
        // 你弹一曲（繁华绣河山）：尾两个 `-` 跟着括号声部
        assertEquals(List.of("ni", "tan", "yi", "qu",
                        "fan", "hua", "xiu", "he", "shan", "-", "-"), originals(result.lines().get(3)));
        assertEquals(List.of(0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1), result.lines().get(3).groups());
        assertEquals(List.of("fan", "hua", "xiu", "he", "shan"), originals(result.lines().get(4)));

        // 默认词各归各家：主声部拿括号外那句、括号声部拿括号里那句
        assertEquals(List.of("你", "在", "清", "涧", "你", "弹", "一", "", "", "", "曲"),
                result.defaults().get(0));
        assertEquals(List.of("繁", "华", "绣", "河", "山"), result.defaults().get(4));
    }

    /**
     * 《遗世蒹葭》尾声那一句（实测，用户报「第 34 句字词顺序不对」）：主轨唱括号外的
     * 「温柔了几多 天涯倦客」、和声轨唱括号里的「纷飞了勾勒」，两条声部在时间上交错 ——
     * 而且和声轨的「了」与主轨的「了」<b>同拍同词</b>，被 {@code timedOf} 并成一个原子
     * （代表是主轨）。整句按「主流在前、括号声部在后」排下来时，那个原子带着和声轨的「了」
     * 排进了主流那一段，和声轨的槽位就成了「了 纷 - 飞 勾 - 勒」—— 前端按组（＝轨）画行，
     * 那一行的字序整个错掉。
     */
    private static final List<LyricLine> YI_SHI_LYRICS =
            List.of(lrc(213.25, "温柔了几多 天涯倦客（纷飞了勾勒）"));

    private static final List<FillTrack> YI_SHI = List.of(
            track(0, note("温", 213.25, 0.25), note("柔", 213.5, 0.25),
                    note("了", 213.75, 0.25), note("几", 214.0, 0.5),
                    note("多", 214.5, 0.5), note("天", 215.25, 0.25),
                    note("涯", 215.5, 0.25), note("倦", 215.75, 0.25),
                    note("客", 216.0, 0.125), note("-", 216.125, 0.875)),
            track(1, note("纷", 213.0, 0.125), note("-", 213.125, 0.375),
                    note("飞", 213.5, 0.25), note("了", 213.75, 0.25),
                    note("勾", 214.0, 0.25), note("-", 214.25, 0.25),
                    note("勒", 214.5, 1.5)));

    @Test
    public void brackets_unisonAcrossStreamsKeepsEachVoiceInTimeOrder() {
        LyricFillAligner.SplitResult result =
                LyricFillAligner.split(YI_SHI, List.of(0, 1), YI_SHI_LYRICS, null);

        assertEquals(1, result.lines().size());
        FillLine line = result.lines().getFirst();

        // 前端按组画行（songfill.js 的 lineGroups 保持槽位序）：和声那一行的字序必须是时间序
        assertEquals(List.of("纷", "-", "飞", "了", "勾", "-", "勒"),
                filterGroup(line, 1).stream().map(FillSlot::original).toList());
        // 主流那一行同理
        assertEquals(List.of("温", "柔", "了", "几", "多", "天", "涯", "倦", "客", "-"),
                filterGroup(line, 0).stream().map(FillSlot::original).toList());
        // 原词 = 主流那句 + 括号那句（修前重复一个「了」、还把和声的「了」丢了）
        assertEquals("温柔了几多天涯倦客纷飞了勾勒", line.originalText());
        // 括号标记只标可填格（{@code -} 不标），和声那一行亮着「（轨 #1）」靠的是
        // 前端 bracketGroup「组内任一格被标了就算」
        assertEquals(List.of(List.of(false, false, false, false, false, false, false, false,
                        false, false, true, false, true, false, true, false, true)),
                LyricFillAligner.brackets(YI_SHI, List.of(0, 1), result.lines(), YI_SHI_LYRICS));
    }

    @Test
    public void brackets_noTrailingBracketLineStaysOnOldPath() {
        // 歌词里一行行尾括号都没有 → 整条流程走原路径，标记全 false
        List<FillTrack> tracks = List.of(
                track(0, note("甲", 16.0, 0.5), note("乙", 16.5, 0.5)),
                track(1, note("甲", 16.0, 0.5), note("乙", 16.5, 0.5)));
        List<LyricLine> lyrics = List.of(lrc(16.0, "甲乙"));

        LyricFillAligner.SplitResult result =
                LyricFillAligner.split(tracks, List.of(0, 1), lyrics, null);

        assertEquals(List.of(0, 1, 0, 1), result.lines().getFirst().groups());
        assertEquals(List.of(List.of(false, false, false, false)),
                LyricFillAligner.brackets(tracks, List.of(0, 1), result.lines(), lyrics));
    }

    @Test
    public void brackets_inlineBracketIsNotADualVoiceLine() {
        // 行内括号（儿化音 / 注音 / 演唱者标注）不是行尾括号 → 原样留在主流，行为与从前一致
        List<LyricLine> lyrics = List.of(lrc(16.0, "找茬（儿）我奉陪"));
        List<FillTrack> tracks = List.of(
                track(0, note("找", 16.0, 0.5), note("茬", 16.5, 0.5),
                        note("我", 17.0, 0.5), note("奉", 17.5, 0.5), note("陪", 18.0, 0.5)));

        LyricFillAligner.SplitResult result = LyricFillAligner.split(tracks, List.of(0), lyrics, null);

        assertEquals(1, result.lines().size());
        assertEquals(List.of("找", "茬", "我", "奉", "陪"), originals(result.lines().getFirst()));
        assertEquals(List.of(List.of(false, false, false, false, false)),
                LyricFillAligner.brackets(tracks, List.of(0), result.lines(), lyrics));
    }

    @Test
    public void brackets_trailingBracketWithoutSecondVoiceFallsBack() {
        // 行尾括号但括号里是注解（《绝涮双娇》的「锅（儿）」）：没人唱括号里那个字，
        // 一条流都分不出来 → 回落老路径，逗号行尾的那句照旧只按主流分句
        List<LyricLine> lyrics = List.of(lrc(16.0, "锅涮（儿）"));
        List<FillTrack> tracks = List.of(
                track(0, note("锅", 16.0, 0.5), note("涮", 16.5, 0.5)));

        LyricFillAligner.SplitResult result = LyricFillAligner.split(tracks, List.of(0), lyrics, null);

        assertEquals(1, result.lines().size());
        assertEquals(List.of("锅", "涮"), originals(result.lines().getFirst()));
        assertEquals(List.of(List.of(false, false)),
                LyricFillAligner.brackets(tracks, List.of(0), result.lines(), lyrics));
    }

    /**
     * 括号短语被剔除后重走时，单元空间<b>同步收缩</b> —— 括号行之后各行的视觉空位不能错位。
     *
     * <p>修前（§13 第 45 条）：走法用收缩空间（去掉括号短语）算出 {@code noteToLyric}，Alignment
     * 却仍配<b>未收缩</b>的 units / gapAfter，下标取值整体前移一格 —— 行内空格从「四」后挪到句尾
     * 「五」后，lrc 里成了「四五 」而不是「四 五」。凡是歌词里有行尾括号，后面每一行的空格都会错。
     */
    @Test
    public void brackets_subPhraseRemovalKeepsLaterLinesGapsAligned() {
        // 括号里的「三」有音符唱到 → 走法认领它，却拿不出「双声部重叠」的证据 → 触发剔除重走
        List<LyricLine> lyrics = List.of(lrc(0.0, "一二（三）"), lrc(2.0, "四 五"));
        List<FillTrack> tracks = List.of(track(0,
                note("一", 0.0, 0.5), note("二", 0.5, 0.5), note("三", 1.0, 0.5),
                note("四", 1.5, 0.5), note("五", 2.0, 0.5)));

        LyricFillAligner.SplitResult result =
                LyricFillAligner.split(tracks, List.of(0), lyrics, null);

        // 空位在「四」之后，不在句尾的「五」之后
        assertEquals(List.of(true, false), result.gaps().get(1));
        // 导出时按当前分句现算的那一份（LyricFillService.export 走的是这条）同样不能错位
        assertEquals(List.of(true, false),
                LyricFillAligner.gaps(tracks, List.of(0), result.lines(), lyrics).get(1));
    }

    // ==================== 走法回归（用户实测报的两个 bug + 两处走法修正）====================

    /**
     * bug 1（《不问ciaga》实测，用户报到第15/27句）：轨0 与轨1 在<b>同一拍</b>上各自起头
     * ——轨0 收尾的「li」和轨1 起头的「lin」都在 47.72s，轨0 紧随的延音排在轨1 的下一个
     * 音符之前。旧的重排按「时间线上前一个可填原子」取行号，那正是轨1 的「淋」，延音于是
     * 跑进轨1 那一句，给那一句凭空多出一个第0轨的槽位（第27句的 {@code gui}/{@code ri}
     * 同型）。走法里延音跟本轨，结构上不可能再串台。
     */
    private static final List<LyricLine> ONSET_CLASH_LYRICS =
            List.of(lrc(47.0, "入梦里"), lrc(47.7, "淋瓢泼"));

    private static final List<FillTrack> ONSET_CLASH = List.of(
            track(0, note("ru", 47.0, 0.3), note("meng", 47.3, 0.3),
                    note("li", 47.72, 0.28), note("-", 48.0, 0.5)),
            track(1, note("lin", 47.72, 0.5), note("piao", 48.22, 0.5), note("po", 48.72, 0.5)));

    @Test
    public void walk_trailingDashStaysWithItsOwnTrackOnOnsetClash() {
        LyricFillAligner.SplitResult result =
                LyricFillAligner.split(ONSET_CLASH, List.of(0, 1), ONSET_CLASH_LYRICS, null);

        assertEquals(2, result.lines().size());
        // 延音留在本轨那一句的句尾（这一句从头到尾只有第0轨一条声部）
        assertEquals(List.of("ru", "meng", "li", "-"), originals(result.lines().get(0)));
        assertTrue(result.lines().get(0).slots().stream().allMatch(s -> s.trackIndex() == 0));
        // 轨1 那一句干干净净，没有第0轨的槽位混进来
        FillLine next = result.lines().get(1);
        assertEquals(List.of("lin", "piao", "po"), originals(next));
        assertTrue(next.slots().stream().allMatch(s -> s.trackIndex() == 1));
        assertEquals(1, next.groups().stream().distinct().count());
    }

    /**
     * bug 2（《不问ciaga》实测，用户把 2:22 的歌词改成行尾括号后报）：括号行里轨0 唱括号外、
     * 轨1 唱括号里，两条声部在时间上交错；紧接着的下一句（同一条轨0）被夹在轨1 的音符中间。
     * 旧的括号双流路径整条跳过重排，"未命中原子沿用前一个命中的行号"，下一句的「wo tan na」
     * 于是粘进了上一句。走法里短语进行中粘在当前轨，两句话各归各家。
     */
    private static final List<LyricLine> BRACKET_CLASH_LYRICS = List.of(
            lrc(93.5, "你的一抹笑意又入梦里（撒水袖唱罢旧事舞曲）"),
            lrc(97.25, "我叹那春花秋月不问别离"));

    private static final List<FillTrack> BRACKET_CLASH = List.of(
            track(0, note("ni", 93.50, 0.25), note("de", 93.75, 0.25), note("yi", 94.00, 0.25),
                    note("mo", 94.25, 0.25), note("xiao", 94.50, 0.25), note("yi", 94.75, 0.25),
                    note("you", 95.00, 0.25), note("ru", 95.25, 0.25), note("meng", 95.50, 0.25),
                    note("li", 95.75, 0.25), note("-", 96.00, 0.25),
                    note("wo", 97.25, 0.25), note("tan", 97.50, 0.25), note("na", 97.75, 0.25),
                    note("chun", 98.00, 0.25), note("-", 98.25, 0.25), note("hua", 98.50, 0.25),
                    note("qiu", 98.75, 0.25), note("yue", 99.00, 0.25), note("bu", 99.25, 0.25),
                    note("-", 99.50, 0.25), note("wen", 99.75, 0.25), note("bie", 100.00, 0.25),
                    note("li", 100.25, 0.25)),
            track(1, note("sa", 95.75, 0.5), note("shui", 96.25, 0.5), note("xiu", 96.75, 0.5),
                    note("chang", 97.25, 0.5), note("ba", 97.75, 0.5), note("jiu", 98.25, 0.5),
                    note("shi", 98.75, 0.5), note("wu", 99.25, 0.5), note("qu", 99.75, 0.5)));

    @Test
    public void walk_bracketLineKeepsBothVoicesAndNextSentenceIntact() {
        LyricFillAligner.SplitResult result =
                LyricFillAligner.split(BRACKET_CLASH, List.of(0, 1), BRACKET_CLASH_LYRICS, null);

        assertEquals(2, result.lines().size());

        // 括号行：主流 10 字 + 句尾延音 = 11 格，括号声部 9 字整段跟在后面
        FillLine bracket = result.lines().get(0);
        assertEquals(20, bracket.slots().size());
        assertEquals(11, bracket.slots().stream().filter(s -> s.trackIndex() == 0).count());
        assertEquals(9, bracket.slots().stream().filter(s -> s.trackIndex() == 1).count());
        // 括号声部连在主流之后、中间不被主流打断（两条声部在时间上其实是交错的）
        assertEquals(List.of("sa", "shui", "xiu", "chang", "ba", "jiu", "shi", "wu", "qu"),
                originals(bracket).subList(11, 20));
        assertTrue(bracket.slots().subList(11, 20).stream().allMatch(s -> s.trackIndex() == 1));

        // 下一句整句留在自己这一句里，没被上一句的声部粘走
        FillLine next = result.lines().get(1);
        assertEquals(List.of("wo", "tan", "na", "chun", "-", "hua", "qiu", "yue", "bu", "-",
                "wen", "bie", "li"), originals(next));
        assertEquals(13, next.slots().size());
        assertTrue(next.slots().stream().allMatch(s -> s.trackIndex() == 0));
        assertEquals(1, next.groups().stream().distinct().count());
    }

    /**
     * 双轨<b>逐字接力</b>唱同一句（《被风吹过的夏天》实测「只剩寂寞肯沉淀」＝轨2 唱前四字、
     * 轨3 唱后三字）。汉字模板的死单元判据若只看本轨往后几个音符，轨2 会把「肯沉淀」整个
     * 当成「谁都读不出」跳掉、短语于是「完成」，轨3 再唱就成了未匹配 —— 一句碎成两句。
     */
    private static final List<LyricLine> RELAY_LYRICS = List.of(lrc(60.0, "只剩寂寞肯沉淀"));

    private static final List<FillTrack> RELAY = List.of(
            track(0, note("只", 60.0, 0.3), note("剩", 60.3, 0.3),
                    note("寂", 60.6, 0.3), note("寞", 60.9, 0.3)),
            track(1, note("肯", 61.2, 0.3), note("沉", 61.5, 0.3), note("淀", 61.8, 0.3)));

    @Test
    public void walk_twoTrackRelaySingsOneSentence() {
        LyricFillAligner.SplitResult result =
                LyricFillAligner.split(RELAY, List.of(0, 1), RELAY_LYRICS, null);

        assertEquals(1, result.lines().size());
        FillLine line = result.lines().getFirst();
        assertEquals("只剩寂寞肯沉淀", line.originalText());
        assertEquals(List.of("只", "剩", "寂", "寞", "肯", "沉", "淀"), originals(line));
        assertEquals(List.of(0, 0, 0, 0, 1, 1, 1), line.groups());
    }

    /**
     * 两条轨交替演唱时的句尾（《霜雪千年》末段的实测数据，全长 3.4 秒的节选）：轨1 先唱
     * 「消融你眉间」，随后两条轨<b>同唱</b>「悲戚霜雪」，轨1 比轨0 早 0.0441 秒起音。
     *
     * <p>轨1 的 {@code shuang} 只有 0.0441 秒长，正好在轨0 的 {@code shuang} 起音处结束 ——
     * <b>首尾相接不算重叠</b>（{@link LyricFillAligner} 建块的口径），于是它自成一个块、自成
     * 一个原子，而轨1 的 bei/qi/xue 都因为与轨0 同拍同词被并进了轨0 代表的原子。走法处理这个
     * 原子时它谁都连不上（「悲戚霜雪」这一句已被轨0 唱完，下一句又起于「悲」），只能按
     * 「本轨最近归属」兜底 —— 兜底若记的是<b>代表轨</b>（轨0）那一份，轨1 的最近归属还停在
     * 更早的「消融你眉间」，这个音符就被送回上一句了（用户实测：第 49 句的 shuang 挂到了
     * 第 48 句「消融你眉间」后面）。兜底必须按<b>本轨</b>记，与 {@code buildLines} 的
     * {@code lastByTrack} 同一个口径。
     */
    private static final List<LyricLine> FROST_TAIL_LYRICS =
            List.of(lrc(0.0, "消融你眉间"), lrc(2.8, "悲戚霜雪"), lrc(3.2, "悲戚霜雪"));

    private static final List<FillTrack> FROST_TAIL = List.of(
            track(0, note("bei", 1.8081, 0.3528), note("qi", 2.3373, 0.3528),
                    note("shuang", 2.8665, 0.0441), note("-", 2.9106, 0.1764),
                    note("xue", 3.2193, 2.6901)),
            track(1, note("xiao", 0.0, 0.1764), note("rong", 0.1764, 0.1764),
                    note("ni", 0.3528, 0.4410), note("mei", 0.8820, 0.3969),
                    note("jian", 1.4112, 0.3528), note("bei", 1.7640, 0.4410),
                    note("qi", 2.2932, 0.4410), note("shuang", 2.8224, 0.0441),
                    note("-", 2.8665, 0.2205), note("xue", 3.1752, 0.1764)));

    @Test
    public void walk_tinyNoteTouchingOtherVoiceStaysInItsOwnSentence() {
        LyricFillAligner.SplitResult result =
                LyricFillAligner.split(FROST_TAIL, List.of(0, 1), FROST_TAIL_LYRICS, null);

        assertEquals(2, result.lines().size());
        // 上一句只有轨1 的「消融你眉间」，轨1 那个短音符不许跟进来
        FillLine first = result.lines().get(0);
        assertEquals("xiaorongnimeijian", first.originalText());
        assertEquals(List.of("xiao", "rong", "ni", "mei", "jian"), originals(first));
        assertEquals(List.of(0, 0, 0, 0, 0), first.groups());

        // 两条轨的「悲戚霜雪」在同一句里：轨1 的短 shuang 与它本轨的 bei/qi/xue 同句
        FillLine second = result.lines().get(1);
        assertEquals(List.of("bei", "bei", "qi", "qi", "shuang", "shuang", "-", "-",
                "xue", "xue"), originals(second));
        assertEquals(2, second.groups().stream().distinct().count(), "两条轨各成一组");
    }

    /**
     * 模板原词与 demo 歌词的同音出入（《被风吹过的夏天》模板写「纳个夏天」「心中de热」，
     * demo 写「那个夏天」「心中的热」）。汉字路径原本只认原值相等，这两行整段对不上，
     * 碎成没有歌词归属的独立句 —— 现在按读音回退（{@code 纳}/{@code 那}）与
     * 「字母音 ↔ 汉字读音」（{@code de}/{@code 的}）都能配上。
     *
     * <p>前面两句是<b>严格相等</b>的普通句：整首的对齐率全靠它们撑过走法的闸门
     * （{@code MIN_LYRIC_MATCH_RATIO}）；少了它们这首太小，会整体回落单流路径，
     * 这条 fixture 就测不到走法里的同音回退。
     */
    private static final List<LyricLine> HOMOPHONE_LYRICS = List.of(
            lrc(16.0, "天涯若比邻"), lrc(18.0, "海内存知己"), lrc(20.0, "那个夏天"));

    private static final List<FillTrack> HOMOPHONE = List.of(
            track(0, note("天", 16.0, 0.5), note("涯", 16.5, 0.5), note("若", 17.0, 0.5),
                    note("比", 17.5, 0.5), note("邻", 18.0, 0.5),
                    note("海", 18.5, 0.5), note("内", 19.0, 0.5), note("存", 19.5, 0.5),
                    note("知", 20.0, 0.5),
                    note("己", 20.5, 0.25), note("纳", 20.75, 0.25), note("个", 21.0, 0.25),
                    note("夏", 21.25, 0.25), note("天", 21.5, 0.75)));

    @Test
    public void walk_hanziTemplateToleratesHomophoneTypos() {
        LyricFillAligner.SplitResult result =
                LyricFillAligner.split(HOMOPHONE, List.of(0), HOMOPHONE_LYRICS, null);

        // 三句都配上了歌词；「纳个夏天」按读音认到「那个夏天」这一行
        assertEquals(3, result.lines().size());
        assertEquals("天涯若比邻", result.lines().get(0).originalText());
        assertEquals("海内存知己", result.lines().get(1).originalText());
        FillLine homophone = result.lines().get(2);
        assertEquals(List.of("纳", "个", "夏", "天"), originals(homophone));
        assertEquals(1, homophone.groups().stream().distinct().count());
    }

    /**
     * 拼音模板 + 一条「歌词里根本没有对应句」的声部（轨1 的「撒水袖唱罢旧事舞曲」）：
     * 走法把它单独成句、那一句的默认词整行为空。
     *
     * <p>这里锁的是<b>判据的层级</b>：页面标红的条件曾经写成「这一行的默认词不全空」，
     * 于是整句一个汉字都没配上的句子 —— 恰恰最需要报警的那种 —— 反而一格都不标红
     * （用户实测：不问ciaga 第 31 句 sa/shui/xiu/… 九个拼音格全无提示）。判据必须是
     * 模板级的 {@code result.pinyin()}，与某一句配没配上无关。
     */
    private static final List<LyricLine> ORPHAN_LYRICS = List.of(
            lrc(0.0, "你的一抹笑意又入梦里"), lrc(5.0, "我叹那春花秋月不问别离"),
            lrc(10.0, "阁楼里写一纸相思未停笔"));

    private static final List<FillTrack> ORPHAN = List.of(
            track(0, note("ni", 0.0, 0.5), note("de", 0.5, 0.5), note("yi", 1.0, 0.5),
                    note("mo", 1.5, 0.5), note("xiao", 2.0, 0.5), note("yi", 2.5, 0.5),
                    note("you", 3.0, 0.5), note("ru", 3.5, 0.5), note("meng", 4.0, 0.5),
                    note("li", 4.5, 0.5), note("wo", 5.0, 0.5), note("tan", 5.5, 0.5),
                    note("na", 6.0, 0.5), note("chun", 6.5, 0.5), note("hua", 7.0, 0.5),
                    note("qiu", 7.5, 0.5), note("yue", 8.0, 0.5), note("bu", 8.5, 0.5),
                    note("wen", 9.0, 0.5), note("bie", 9.5, 0.5), note("li", 10.0, 0.5),
                    note("ge", 10.5, 0.5), note("lou", 11.0, 0.5), note("li", 11.5, 0.5),
                    note("xie", 12.0, 0.5), note("yi", 12.5, 0.5), note("zhi", 13.0, 0.5),
                    note("xiang", 13.5, 0.5), note("si", 14.0, 0.5), note("wei", 14.5, 0.5),
                    note("ting", 15.0, 0.5), note("bi", 15.5, 0.5)),
            track(1, note("sa", 2.25, 0.5), note("shui", 2.75, 0.5), note("xiu", 3.25, 0.5),
                    note("chang", 3.75, 0.5), note("ba", 4.25, 0.5), note("jiu", 4.75, 0.5),
                    note("shi", 5.25, 0.5), note("wu", 5.75, 0.5), note("qu", 6.25, 0.5)));

    @Test
    public void walk_pinyinTemplateStaysFlaggedWhenOneSentenceMatchesNothing() {
        LyricFillAligner.SplitResult result =
                LyricFillAligner.split(ORPHAN, List.of(0, 1), ORPHAN_LYRICS, null);

        // 模板级判据：整句没配上不影响「这次走了拼音匹配」这件事
        assertTrue(result.pinyin(), "拼音模板即使某句全没配上也要报 true（页面据此标红）");
        // 轨1 那条声部独立成句，且这一句整行默认词为空 —— 正是旧判据会漏掉标红的那一句
        int row = -1;
        for (int i = 0; i < result.lines().size(); i++) {
            if (result.lines().get(i).slots().stream().allMatch(s -> s.trackIndex() == 1)) {
                row = i;
                break;
            }
        }
        assertTrue(row >= 0, "没造出「轨1 独立成句」的条件，这条测试就没意义了");
        assertEquals(List.of("sa", "shui", "xiu", "chang", "ba", "jiu", "shi", "wu", "qu"),
                originals(result.lines().get(row)));
        assertTrue(result.defaults().get(row).stream().allMatch(String::isEmpty),
                "这一句的默认词应当整行为空 —— 但模板仍要报拼音，页面才标得红");
    }

    // ==================== br 句首归位（分句后处理） ====================

    private static FillLine lineOf(List<FillSlot> slots, List<Integer> groups) {
        return lineOf(slots, groups, 0L);
    }

    private static FillLine lineOf(List<FillSlot> slots, List<Integer> groups, long startOnset) {
        return new FillLine(List.copyOf(slots), "", slots.size(), startOnset, groups);
    }

    /** 测试槽位：轨 0、按传入下标，onset 用数组下标（够区分先后即可）。 */
    private static FillSlot slot(String original, SlotType type, int noteIndex) {
        return new FillSlot(0, noteIndex, original, type);
    }

    /**
     * 句尾 br 前移到下一句句首：走法把换气跟正在唱的短语挂到句尾，而 br 是换气点、
     * 下一句起唱的标志（无歌词粗分路径本来就归下句句首——《免我蹉跎苦》实测两条口径不一）。
     */
    @Test
    public void relocateTrailingBreathsMovesLineEndBreathToNextHead() {
        FillSlot[] all = {slot("我", SlotType.HANZI, 0), slot("br", SlotType.BREATH, 1),
                slot("随", SlotType.HANZI, 2), slot("你", SlotType.HANZI, 3)};

        List<FillLine> out = LyricFillAligner.relocateTrailingBreaths(List.of(
                lineOf(List.of(all[0], all[1]), List.of(0, 0), 0L),
                lineOf(List.of(all[2], all[3]), List.of(0, 0), 2L)));

        assertEquals(2, out.size());
        assertEquals(List.of("我"), originals(out.get(0)));
        assertEquals(List.of("br", "随", "你"), originals(out.get(1)));
        assertEquals(2L, out.get(1).startOnset(), "句首时间不因迁入的 br 提前——那是这句开口唱的时刻");
    }

    /**
     * 迁入的 br 离这句很远（这条轨的孤立换气：br 之后隔很久才开口唱）时，句首时间<b>不能</b>
     * 跟着提前——实测《还是会寂寞》轨 2 的 br 在 87.0 s、唱 "da da li lai" 在 140.25 s，
     * 提前会把这行导成 1:27（实际 2:20），还排到上一句前面去。
     */
    @Test
    public void relocateTrailingBreathsKeepsOwnStartWhenBreathIsFarAway() {
        FillSlot[] all = {slot("我", SlotType.HANZI, 0), slot("br", SlotType.BREATH, 1),
                slot("da", SlotType.ENGLISH, 2)};

        List<FillLine> out = LyricFillAligner.relocateTrailingBreaths(List.of(
                lineOf(List.of(all[0], all[1]), List.of(0, 0), 1L),
                lineOf(List.of(all[2]), List.of(0), 1400L)));

        assertEquals(List.of("br", "da"), originals(out.get(1)));
        assertEquals(1400L, out.get(1).startOnset(), "br 在 1，这句 1400 才唱，句首时间留在 1400");
    }

    /** 尾部 run 里 br 之前还有延音：延音是拉长上一个字，留在原句；br 及其后的延音（拉长换气）一起走。 */
    @Test
    public void relocateTrailingBreathsKeepsWordDashMovesBreathDash() {
        FillSlot[] all = {slot("我", SlotType.HANZI, 0), slot("-", SlotType.DASH, 1),
                slot("br", SlotType.BREATH, 2), slot("-", SlotType.DASH, 3),
                slot("随", SlotType.HANZI, 4)};

        List<FillLine> out = LyricFillAligner.relocateTrailingBreaths(List.of(
                lineOf(List.of(all[0], all[1], all[2], all[3]), List.of(0, 0, 0, 0)),
                lineOf(List.of(all[4]), List.of(0))));

        assertEquals(List.of("我", "-"), originals(out.get(0)));
        assertEquals(List.of("br", "-", "随"), originals(out.get(1)));
    }

    /** 最后一句的句尾 br 是收尾换气：没有下一句，原地保留。 */
    @Test
    public void relocateTrailingBreathsKeepsTailOfLastLine() {
        FillSlot[] all = {slot("我", SlotType.HANZI, 0), slot("随", SlotType.HANZI, 1),
                slot("br", SlotType.BREATH, 2)};

        List<FillLine> out = LyricFillAligner.relocateTrailingBreaths(List.of(
                lineOf(List.of(all[0]), List.of(0)),
                lineOf(List.of(all[1], all[2]), List.of(0, 0))));

        assertEquals(2, out.size());
        assertEquals(List.of("随", "br"), originals(out.get(1)), "最后一句的收尾换气留在句尾");
    }

    /**
     * 句内组号按<b>轨号</b>编排（工程里的顺序），不看组号的首现序、也不看槽位在句内的先后：
     * 页面上声部行的上下顺序、括号标签里的轨号、批量填词吃字的顺序、导出的文本顺序全从它来。
     * 实测《栖凰》「谯鼓响」那句的组标签排成 11 / 7 / 12（搬来的 br 让首现序跳了）。
     */
    @Test
    public void groupIdsByTrackNumbersByTrackOrder() {
        // 两句拼一起的旧号：轨 7 那组首现在前（号 5）、轨 4 那组首现在后（号 2）
        List<FillSlot> slots = List.of(
                new FillSlot(7, 0, "br", SlotType.BREATH),
                new FillSlot(7, 1, "鼓", SlotType.HANZI),
                new FillSlot(4, 0, "谯", SlotType.HANZI),
                new FillSlot(4, 1, "响", SlotType.HANZI));

        assertEquals(List.of(1, 1, 0, 0),
                LyricFillAligner.groupIdsByTrack(slots, List.of(5, 5, 2, 2)),
                "轨 4 = 组 0、轨 7 = 组 1：按轨号，不按句内首现");

        // 旧数据没有 groups（整句一组）：原样兜成单组
        assertEquals(List.of(0, 0, 0, 0), LyricFillAligner.groupIdsByTrack(slots, null));
    }

    /** 声部组按轨号排序：组里最小的轨号小的排前面（导出文本、页面上声部行同一口径）。 */
    @Test
    public void groupIndexesOrdersGroupsByTrack() {
        List<FillSlot> slots = List.of(
                new FillSlot(4, 0, "先", SlotType.HANZI),
                new FillSlot(2, 0, "后", SlotType.HANZI));
        FillLine line = new FillLine(slots, "先后", 2, 0L, List.of(1, 0));

        assertEquals(List.of(0, 1), new ArrayList<>(LyricFillAligner.groupIndexes(line).keySet()),
                "轨 2（组 0）排在轨 4（组 1）前面，虽然轨 4 的槽位在句内更靠前");
    }
// ==================== cleanLines（语料采集重构，填词助手设计 §5.2） ====================

    @Test
    public void cleanLines_keepsUntitledTimedRowsLikeLyricLines() {
        List<LyricLine> lrc = List.of(
                lrc(0.0, "曲名 - 歌手"),
                lrc(0.0, "词：某人"),
                lrc(1.0, "【主唱】"),
                lrc(2.0, "春风又绿江南岸"));
        assertEquals(List.of(new LyricLine(2.0, null, "春风又绿江南岸")),
                LyricFillAligner.cleanLines(lrc));
        assertEquals(List.of(new LyricLine(2.0, null, "春风又绿江南岸")),
                LyricFillAligner.lyricLines(lrc));
    }

    /** cleanLines 与 lyricLines 的唯一区别：start == null 的行保留（txt 歌词靠它收进语料）。 */
    @Test
    public void cleanLines_keepsUntimedRows_lyricLinesStillDrops() {
        List<LyricLine> txt = List.of(
                new LyricLine(null, null, "曲名 - 歌手"),
                new LyricLine(null, null, ""),
                new LyricLine(null, null, "月光下的"),
                new LyricLine(null, null, "凤尾竹"));
        assertEquals(List.of(
                        new LyricLine(null, null, "月光下的"),
                        new LyricLine(null, null, "凤尾竹")),
                LyricFillAligner.cleanLines(txt));
        assertTrue(LyricFillAligner.lyricLines(txt).isEmpty(), "lyricLines 旧行为不变：无时间轴全丢");
    }

    // ==================== isLineFilled（语料配对「整句填满」判据，§5.6） ====================

    @Test
    public void isLineFilled_checksEachFillableSlotByIndex() {
        FillLine line = new FillLine(List.of(
                new FillSlot(0, 0, "春", SlotType.HANZI),
                new FillSlot(0, 1, "-", SlotType.DASH),
                new FillSlot(0, 2, "风", SlotType.HANZI)), null, 2, 0L);
        // dash 位不是可填槽位：它的空串不算缺
        assertTrue(LyricFillAligner.isLineFilled(line, List.of("新", "", "风")));
        // 任何一个可填槽位空着 = 没填满
        assertTrue(!LyricFillAligner.isLineFilled(line, List.of("新", "", "")));
        assertTrue(!LyricFillAligner.isLineFilled(line, List.of("新", "")));
        assertTrue(!LyricFillAligner.isLineFilled(null, List.of("新")));
        // 全是延音的句（needCount 0）：没有「填满」可言
        FillLine dashOnly = new FillLine(List.of(
                new FillSlot(0, 0, "-", SlotType.DASH)), null, 0, 0L);
        assertTrue(!LyricFillAligner.isLineFilled(dashOnly, List.of("新")));
    }

    // ==================== 分句质量提示（SplitResult#notice） ====================

    /**
     * <b>强档</b>：走法没过 {@link LyricFillAligner#MIN_LYRIC_MATCH_RATIO}（实测《学猫叫》12%）。
     * 形态照抄那一例 —— 模板里是 demo 版的词、参照歌词却是原声版，只有几个字撞上，整份对不上。
     * 提示必须点名去原曲页核对样例歌词（提示语在 aligner 里拼，页面只画不判）。
     */
    @Test
    public void split_noticeStrongWhenLyricTextMismatches() {
        List<FillTrack> tracks = List.of(track(0,
                note("握", 0.0, 0.5), note("腰", 0.5, 0.5), note("穿", 1.0, 0.5),
                note("你", 1.5, 0.5), note("的", 2.0, 0.5), note("外", 2.5, 0.5),
                note("套", 3.0, 0.5)));
        // 参照歌词整份是原声版（分母大）：7 个音符最多撞上几个字，比率远低于 50%
        List<LyricLine> lyrics = List.of(
                lrc(0.0, "我要穿你的外套"),
                lrc(3.0, "问你身上的味道"),
                lrc(6.0, "想你想得睡不着"));

        String notice = LyricFillAligner.split(tracks, List.of(0), lyrics, null).notice();

        assertTrue(notice != null && notice.contains("对不上"), String.valueOf(notice));
        assertTrue(notice.contains("样例歌词"), notice);
    }

    /**
     * <b>弱档</b>：走法通过了（≥ 50%）但只贴到 75%（4 个字里对上 3 个）—— 句界大体对、个别可能不准。
     * 两档的分界线是<b>已有的两个决策点</b>，不另造阈值（见 {@code noticeOf}）。
     */
    @Test
    public void split_noticeWeakWhenWalkPartiallyMatches() {
        List<FillTrack> tracks = List.of(track(0,
                note("天", 0.0, 0.5), note("涯", 0.5, 0.5), note("若", 1.0, 0.5),
                note("喵", 1.5, 0.5)));

        String notice = LyricFillAligner.split(tracks, List.of(0),
                List.of(lrc(0.0, "天涯若比")), null).notice();

        assertEquals("参照歌词只对上一部分（匹配 75%），个别句界可能不准", notice);
    }

    /**
     * 0.85 是弱提示的<b>下边界（含）</b>：20 个单元对上 17 个 = 0.85，正好不提示。
     * （后 3 个音符是 子丑寅，歌词剩的是 辛壬癸 —— 不是同音字，也不在模板唱过的字里，
     * 于是各自按未匹配处理，命中数恰好 17。）
     */
    @Test
    public void split_noticeSilentAtWeakThreshold() {
        List<FillTrack> tracks = List.of(track(0,
                note("一", 0.0, 0.5), note("二", 0.5, 0.5), note("三", 1.0, 0.5),
                note("四", 1.5, 0.5), note("五", 2.0, 0.5), note("六", 2.5, 0.5),
                note("七", 3.0, 0.5), note("八", 3.5, 0.5), note("九", 4.0, 0.5),
                note("十", 4.5, 0.5), note("甲", 5.0, 0.5), note("乙", 5.5, 0.5),
                note("丙", 6.0, 0.5), note("丁", 6.5, 0.5), note("戊", 7.0, 0.5),
                note("己", 7.5, 0.5), note("庚", 8.0, 0.5),
                note("子", 8.5, 0.5), note("丑", 9.0, 0.5), note("寅", 9.5, 0.5)));

        LyricFillAligner.SplitResult result = LyricFillAligner.split(tracks, List.of(0),
                List.of(lrc(0.0, "一二三四五六七八九十甲乙丙丁戊己庚辛壬癸")), null);

        assertNull(result.notice(), "0.85 刚好够、不算「只对上一部分」");
    }

    /** 全都对上（1.0）也不提示。 */
    @Test
    public void split_noticeSilentWhenWalkMatchesFully() {
        List<FillTrack> tracks = List.of(track(0,
                note("天", 0.0, 0.5), note("涯", 0.5, 0.5), note("若", 1.0, 0.5)));

        assertNull(LyricFillAligner.split(tracks, List.of(0),
                List.of(lrc(0.0, "天涯若")), null).notice());
    }

    /**
     * 没有参照歌词（{@link LyricFillAligner.Mode#OFF}）不提示：那不是「对不上」，是没得对。
     * 手填整体偏移同样是 OFF —— 它表达的是「我明确要求按时间对」，与文本对不对无关。
     */
    @Test
    public void split_noticeSilentWithoutTextAlign() {
        List<FillTrack> tracks = List.of(track(0, note("天", 0.0, 0.5), note("涯", 0.5, 0.5)));

        assertNull(LyricFillAligner.split(tracks, List.of(0), List.of(), null).notice());
        assertNull(LyricFillAligner.split(tracks, List.of(0),
                List.of(lrc(0.0, "我要穿你的外套")), 0.5).notice());
    }

    // ==================== 视觉空位落在延音链之后 ====================

    /**
     * 实测《夜奔》第 25 句（demo 歌词「数尽更筹 听残银漏」）：模板里「筹」的下一个音符正是它的
     * 延音 {@code -}，空位列原先画在「字」与「-」之间（把有效音符和它的延音断开）。标记必须沿延音
     * 链推到链尾那一格，且**只有链尾**那一格是 true。
     */
    @Test
    public void gapsLandOnProlongationTail() {
        List<FillTrack> tracks = List.of(track(0,
                note("数", 0.0, 0.5), note("尽", 0.5, 0.5), note("更", 1.0, 0.5),
                note("筹", 1.5, 0.5), note("-", 2.0, 0.5), note("听", 2.5, 0.5),
                note("-", 3.0, 0.5), note("残", 3.5, 0.5), note("银", 4.0, 0.5),
                note("-", 4.5, 0.5), note("漏", 5.0, 0.5), note("-", 5.5, 0.5)));

        LyricFillAligner.SplitResult result = LyricFillAligner.split(tracks, List.of(0),
                List.of(lrc(0.0, "数尽更筹 听残银漏")), null);

        assertEquals(1, result.lines().size());
        assertEquals(12, result.lines().getFirst().slots().size(), "分句不受空位标记影响");
        assertEquals(List.of(false, false, false, false, true,
                        false, false, false, false, false, false, false),
                result.gaps().getFirst(), "空位列画在「筹」的延音之后，不在「筹」与它的「-」之间");
    }
}
