package io.github.Nyameph.nyaentworks.song.fill;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import io.github.Nyameph.nyaentworks.song.fill.LyricTemplate.FillNote;
import io.github.Nyameph.nyaentworks.song.fill.LyricTemplate.FillTrack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * svp 解析（填词工具设计 2）。纯函数，fixture 硬编码（同 {@code SongGroupNameParserTest} 风格）。
 *
 * <p>{@code sampleSvp} 那个用例读开发者本机的真实样本，样本不在时自动跳过 ——
 * 它在别的机器上不会失败，只是不验证实测值。
 */
public class LyricFillParserTest {

    /** 手工缩样的 svp：两个歌唱轨 + 一个音频参考轨，覆盖四种槽位类型 */
    private static final String SAMPLE = """
            {"version": 153,
             "time": {"meter":[{"index":0,"numerator":4,"denominator":4}],
                      "tempo":[{"position":0,"bpm":120.0}]},
             "tracks": [
               {"name": "填词", "mainGroup": {"notes": [
                  {"lyrics": "br", "onset": 22226400000, "duration": 352800000, "pitch": 66},
                  {"lyrics": "天", "onset": 22579200000, "duration": 352800000, "pitch": 67},
                  {"lyrics": "-",  "onset": 22932000000, "duration": 352800000, "pitch": 67},
                  {"lyrics": "hello", "onset": 23284800000, "duration": 352800000, "pitch": 68}
               ]}},
               {"name": "伴奏", "mainGroup": {"notes": []},
                "mainRef": {"isInstrumental": true, "audio": "伴奏.wav"}},
               {"name": "填词2", "mainGroup": {"notes": [
                  {"lyrics": "涯", "onset": 22579200000, "duration": 352800000, "pitch": 60}
               ]}}
             ]}
            """;

    @Test
    public void parse_keepsOnlySingingTracks() {
        List<FillTrack> tracks = LyricFillParser.parseJson(SAMPLE);

        // 伴奏轨 notes 为空 → 不进骨架；两个填词轨保留，trackIndex = 排定后的位次（0、1）
        assertEquals(2, tracks.size());
        assertEquals(0, tracks.get(0).trackIndex());
        assertEquals("填词", tracks.get(0).trackName());
        assertEquals(1, tracks.get(1).trackIndex());
        assertEquals(1, tracks.get(1).notes().size());
    }

    /**
     * 轨号按 <b>工程显示顺序</b>（{@code dispOrder}，即 SynthV 面板上从上到下的顺序）编，
     * 不按 {@code tracks[]} 数组序。实测《栖凰》：数组里 0..12 是「和声6 吟唱、和声7 吟唱、
     * 副歌4、伴奏、主歌2…」，面板上是「主歌1 - 副本 1、主歌1 - 副本、主歌2…人声」，
     * 按数组序编号时页面的 #号与工程完全对不上（用户实测报的）。
     */
    @Test
    public void parse_numbersTracksByDispOrder() {
        String svp = """
                {"tracks": [
                   {"name": "和声6 吟唱", "dispOrder": 9, "mainGroup": {"notes": [
                      {"lyrics": "ha", "onset": 100, "duration": 100}]}},
                   {"name": "伴奏", "dispOrder": 9, "mainGroup": {"notes": []}},
                   {"name": "副歌4", "dispOrder": 6, "mainGroup": {"notes": [
                      {"lyrics": "副", "onset": 100, "duration": 100}]}},
                   {"name": "人声", "dispOrder": 12, "mainGroup": {"notes": []}},
                   {"name": "主歌1 - 副本 1", "dispOrder": 0, "mainGroup": {"notes": [
                      {"lyrics": "主", "onset": 100, "duration": 100}]}},
                   {"name": "主歌1 - 副本", "dispOrder": 1, "mainGroup": {"notes": [
                      {"lyrics": "歌", "onset": 100, "duration": 100}]}}
                 ]}
                """;

        List<FillTrack> tracks = LyricFillParser.parseJson(svp);

        // 面板顺序：主歌1 - 副本 1(0)、主歌1 - 副本(1)、副歌4(2)、和声6 吟唱(3)
        //（同 dispOrder 9 的伴奏没有音符、不进骨架，人声同理 —— 编号在歌唱轨之间连续）
        assertEquals(List.of("主歌1 - 副本 1", "主歌1 - 副本", "副歌4", "和声6 吟唱"),
                tracks.stream().map(FillTrack::trackName).toList());
        assertEquals(List.of(0, 1, 2, 3), tracks.stream().map(FillTrack::trackIndex).toList());
    }

    /** 同 {@code dispOrder}（《栖凰》的和声6/和声7/伴奏 都是 9）按存储序兜底，别乱序。 */
    @Test
    public void parse_sameDispOrderKeepsStorageOrder() {
        String svp = """
                {"tracks": [
                   {"name": "和声7 吟唱", "dispOrder": 9, "mainGroup": {"notes": [
                      {"lyrics": "ha", "onset": 100, "duration": 100}]}},
                   {"name": "和声6 吟唱", "dispOrder": 9, "mainGroup": {"notes": [
                      {"lyrics": "ha", "onset": 100, "duration": 100}]}}
                 ]}
                """;

        List<FillTrack> tracks = LyricFillParser.parseJson(svp);

        assertEquals(List.of("和声7 吟唱", "和声6 吟唱"),
                tracks.stream().map(FillTrack::trackName).toList());
    }

    @Test
    public void parse_slotTypes() {
        List<FillNote> notes = LyricFillParser.parseJson(SAMPLE).getFirst().notes();

        assertEquals(4, notes.size());
        assertEquals(SlotType.BREATH, notes.get(0).slotType());
        assertEquals(SlotType.HANZI, notes.get(1).slotType());
        assertEquals(SlotType.DASH, notes.get(2).slotType());
        assertEquals(SlotType.ENGLISH, notes.get(3).slotType());
        // 只留 lyrics / onset / duration，pitch 丢弃
        assertEquals("hello", notes.get(3).lyrics());
        assertEquals(23284800000L, notes.get(3).onset());
        assertEquals(352800000L, notes.get(3).duration());
    }

    /**
     * 喉塞音前缀撇号（洛春赋一类模板：{@code '曾}、{@code '唱}）：解析时剥掉、核心字照常
     * 判槽位类型，标记进 {@code glottal}（导出回填文本时拼回 {@code '} 前缀，lrc 不含）。
     * 孤立 {@code '}、{@code '-}、{@code 'br} 不算喉塞标记，原样保留。
     */
    @Test
    public void parse_stripsGlottalPrefix() {
        String glottal = """
                {"tracks": [{"name": "填词", "mainGroup": {"notes": [
                   {"lyrics": "'曾", "onset": 100, "duration": 100},
                   {"lyrics": "看", "onset": 200, "duration": 100},
                   {"lyrics": "'", "onset": 300, "duration": 100},
                   {"lyrics": "'-", "onset": 400, "duration": 100},
                   {"lyrics": "'br", "onset": 500, "duration": 100},
                   {"lyrics": "'go", "onset": 600, "duration": 100}
                ]}}]}
                """;
        List<FillNote> notes = LyricFillParser.parseJson(glottal).getFirst().notes();

        // '曾 → 核心「曾」+ 喉塞标记，槽位照常判汉字
        assertEquals("曾", notes.get(0).lyrics());
        assertTrue(notes.get(0).glottal());
        assertEquals(SlotType.HANZI, notes.get(0).slotType());
        // 普通词不受影响
        assertEquals("看", notes.get(1).lyrics());
        assertFalse(notes.get(1).glottal());
        // 孤立 ' / '- / 'br 不算喉塞标记，原样保留（类型判定也维持原行为：HANZI / ENGLISH）
        assertEquals("'", notes.get(2).lyrics());
        assertFalse(notes.get(2).glottal());
        assertEquals(SlotType.HANZI, notes.get(2).slotType());
        assertEquals("'-", notes.get(3).lyrics());
        assertFalse(notes.get(3).glottal());
        assertEquals(SlotType.HANZI, notes.get(3).slotType());
        assertEquals("'br", notes.get(4).lyrics());
        assertFalse(notes.get(4).glottal());
        assertEquals(SlotType.ENGLISH, notes.get(4).slotType());
        // 前缀撇号的英文词：核心照常判英文，标记保留
        assertEquals("go", notes.get(5).lyrics());
        assertTrue(notes.get(5).glottal());
        assertEquals(SlotType.ENGLISH, notes.get(5).slotType());
    }

    /**
     * 全角撇号 / 撇号后夹空格 / 词两端的引号与空白，都在解析阶段清掉（2026-09-13，第 56 条）。
     *
     * <p>实测《气泡少女》：主旋律写 {@code 两}、和声写 {@code ‘两}（U+2018 前缀），另一处写
     * {@code ' 急}（撇号后夹空格）—— 配对判等（{@code Objects.equals(lyrics)}）因此判不等，
     * 和声配不上对、各自成句，从第 34 句起凭空多出 4 句。归一放在解析里，是唯一事实来源：
     * 配对、存下来的原词、前端的孪生格、导出回填看到的都是同一个值。
     */
    @Test
    public void parse_normalizesGlottalVariantsAndQuoteJunk() {
        String mixed = """
                {"tracks": [{"name": "填词", "mainGroup": {"notes": [
                   {"lyrics": "‘两", "onset": 100, "duration": 100},
                   {"lyrics": "’玩", "onset": 200, "duration": 100},
                   {"lyrics": "' 急", "onset": 300, "duration": 100},
                   {"lyrics": "\\"高", "onset": 400, "duration": 100},
                   {"lyrics": " “潮” ", "onset": 500, "duration": 100}
                ]}}]}
                """;

        List<FillNote> notes = LyricFillParser.parseJson(mixed).getFirst().notes();

        // 全角撇号与 ASCII 撇号同义：核心字照常判槽位类型，标记进 glottal
        assertEquals("两", notes.get(0).lyrics());
        assertTrue(notes.get(0).glottal());
        assertEquals(SlotType.HANZI, notes.get(0).slotType());
        assertEquals("玩", notes.get(1).lyrics());
        assertTrue(notes.get(1).glottal());
        // 撇号与核心字之间夹的空格一并清掉
        assertEquals("急", notes.get(2).lyrics());
        assertTrue(notes.get(2).glottal());
        // 引号只是杂字符：剥掉，但不算喉塞标记（导出不会凭空补个撇号回去）
        assertEquals("高", notes.get(3).lyrics());
        assertFalse(notes.get(3).glottal());
        // 首尾空白 + 两头引号一起剥
        assertEquals("潮", notes.get(4).lyrics());
        assertFalse(notes.get(4).glottal());
    }

    @Test
    public void parse_sortsNotesByOnset() {
        String unsorted = """
                {"tracks": [{"name": "填词", "mainGroup": {"notes": [
                   {"lyrics": "乙", "onset": 300, "duration": 100},
                   {"lyrics": "甲", "onset": 100, "duration": 100}
                ]}}]}
                """;
        List<FillNote> notes = LyricFillParser.parseJson(unsorted).getFirst().notes();

        assertEquals("甲", notes.get(0).lyrics());
        assertEquals("乙", notes.get(1).lyrics());
    }

    /**
     * 空存根 mainGroup（{@code notes: []}、带 uuid）+ {@code mainRef.groupID} 指向 library
     * 真组（实测《免我蹉跎苦》整轨如此——SynthV 2.0 把音符抽到 library 后轨上留的就是存根）：
     * 兜底要能收到音符；{@code groups[]} 再挂同一组同一偏移也不双收。
     */
    @Test
    public void parse_fallsBackToLibraryWhenMainGroupIsEmptyStub() {
        String svp = """
                {"library": [
                   {"name": "main", "uuid": "11111111-2222-3333-4444-555555555555", "notes": [
                      {"lyrics": "天", "onset": 100, "duration": 100}
                   ]}
                 ],
                 "tracks": [
                   {"name": "填词",
                    "mainGroup": {"name": "main", "uuid": "11111111-2222-3333-4444-555555555555",
                                  "notes": []},
                    "mainRef": {"groupID": "11111111-2222-3333-4444-555555555555", "blickOffset": 0},
                    "groups": [{"groupID": "11111111-2222-3333-4444-555555555555", "blickOffset": 0}]
                   }
                 ]}
                """;
        List<FillNote> notes = LyricFillParser.parseJson(svp).getFirst().notes();

        assertEquals(1, notes.size(), "存根兜底收到音符，且 groups 同组同偏移不双收");
        assertEquals("天", notes.getFirst().lyrics());
        assertEquals(100L, notes.getFirst().onset());
    }

    @Test
    public void parse_stripsTrailingNul() {
        // 真实 svp 末尾多 1 字节 00，直接 parse 会报「多余的字符」
        List<FillTrack> tracks = LyricFillParser.parseJson(SAMPLE + "\u0000");

        assertEquals(2, tracks.size());
    }

    @Test
    public void parse_rejectsJsonWithoutSingingTrack() {
        String noNotes = """
                {"tracks": [{"name": "伴奏", "mainGroup": {"notes": []}}]}
                """;
        assertThrows(IllegalArgumentException.class, () -> LyricFillParser.parseJson(noNotes));
    }

    @Test
    public void parse_newFormatResolvesNotesFromLibrary() {
        // 新格式（SynthV 2.0 note-group）：音符抽到顶层 library（按 uuid 存），track 用
        // groups[].groupID 引用，mainGroup.notes 是空的。修 bug：《九九八十一柔情版》就是这种。
        String newFormat = """
                {"version": 153,
                 "library": [
                   {"uuid": "g1", "notes": [
                      {"lyrics": "天", "onset": 22579200000, "duration": 352800000},
                      {"lyrics": "涯", "onset": 22932000000, "duration": 352800000}
                   ]},
                   {"uuid": "g2", "notes": [
                      {"lyrics": "若", "onset": 23284800000, "duration": 352800000}
                   ]}
                 ],
                 "tracks": [
                   {"name": "主歌 沨漪", "mainGroup": {"notes": []},
                    "groups": [{"groupID": "g1"}]},
                   {"name": "伴奏", "mainGroup": {"notes": []},
                    "mainRef": {"isInstrumental": true, "audio": "伴奏.wav"}},
                   {"name": "副歌 沨漪", "mainGroup": {"notes": []},
                    "groups": [{"groupID": "g2"}]}
                 ]}
                """;

        List<FillTrack> tracks = LyricFillParser.parseJson(newFormat);

        // 伴奏轨 groups 为空 → 不进骨架；两个带 groupID 的歌唱轨保留
        assertEquals(2, tracks.size());
        assertEquals(0, tracks.get(0).trackIndex());
        assertEquals("主歌 沨漪", tracks.get(0).trackName());
        assertEquals(List.of("天", "涯"),
                tracks.get(0).notes().stream().map(FillNote::lyrics).toList());
        assertEquals(1, tracks.get(1).trackIndex());
        assertEquals("副歌 沨漪", tracks.get(1).trackName());
        assertEquals(List.of("若"),
                tracks.get(1).notes().stream().map(FillNote::lyrics).toList());
    }

    @Test
    public void secondsOf_sixteen() {
        // 实测首个汉字 onset = 22579200000 → 精确 16.000 秒
        assertEquals(16.0, LyricFillParser.secondsOf(22579200000L), 0.000_001);
        assertEquals(1.0, LyricFillParser.secondsOf(LyricFillParser.BLICK_PER_SECOND), 0.000_001);
    }

    @Test
    public void lrcTime_formats() {
        assertEquals("[00:16.00]", LyricFillParser.lrcTime(16.0));
        assertEquals("[01:05.30]", LyricFillParser.lrcTime(65.3));
        assertEquals("[00:00.00]", LyricFillParser.lrcTime(-1));
    }

    /** 真实样本的实测值：4 个音轨里 2 个歌唱轨、共 807 个 note、首个汉字精确 16.000 秒 */
    @Test
    public void sampleSvp_matchesMeasuredValues() {
        Path sample = Path.of("F:\\歌曲\\temp\\36.5°C_李佳思\\测试用.svp");
        Assumptions.assumeTrue(Files.isRegularFile(sample), "本机没有样本 svp，跳过实测校验");

        List<FillTrack> tracks = LyricFillParser.parse(sample);
        assertEquals(2, tracks.size(), "模板 4 轨里只有 2 个歌唱轨（伴奏 / 原声 notes 为空）");
        assertEquals(807, tracks.stream().mapToLong(t -> t.notes().size()).sum());

        long firstHanziOnset = tracks.stream()
                .flatMap(t -> t.notes().stream())
                .filter(n -> n.slotType() == SlotType.HANZI)
                .mapToLong(FillNote::onset)
                .min().orElseThrow();
        assertEquals(22579200000L, firstHanziOnset);
        assertEquals(16.0, LyricFillParser.secondsOf(firstHanziOnset), 0.000_001);
    }

    /** 新格式真实样本：《九九八十一柔情版》——音符抽到顶层 library，5 轨里 4 个歌唱轨。 */
    @Test
    public void sampleSvpNewFormat_resolvesFromLibrary() {
        Path sample = Path.of("F:\\歌曲\\模板\\九九八十一柔情版_叶洛洛,西瓜JUN,知性的小方块\\[工程] 九九八十一柔情版.svp");
        Assumptions.assumeTrue(Files.isRegularFile(sample), "本机没有柔情版 svp 样本，跳过实测校验");

        List<FillTrack> tracks = LyricFillParser.parse(sample);
        assertEquals(4, tracks.size(), "5 轨里 4 个歌唱轨（伴奏 groups 为空）");
        // 副歌4(70) + 副歌3(344) + 主歌1(207) + 主歌2(324)
        assertEquals(945, tracks.stream().mapToLong(t -> t.notes().size()).sum());

        // 修 bug：库组引用必须叠加 blickOffset（该工程全靠 offset 摆位，正负都有）。
        // 各轨首个音符的有效位置（120bpm 4/4，1 小节 = 2822400000 blick），与 SynthV 里的
        // 小节号一致：主歌1 ≈ 10.8（用户口中的 11 小节起）、主歌2 ≈ 26.7、副歌3 ≈ 47.5、
        // 副歌4 ≈ 107.5（用户：108 小节中间）。不叠 offset 时主歌1 会落在 92 小节、
        // 副歌3/副歌4 叠在 47.5 小节 —— 正是用户报的「主歌2 在最前、副歌4 混进副歌3」。
        Map<String, Long> firstOnsetByTrack = new HashMap<>();
        for (FillTrack t : tracks) {
            firstOnsetByTrack.put(t.trackName(), t.notes().getFirst().onset());
        }
        assertEquals(30448917773L, firstOnsetByTrack.get("主歌1 沨漪"));
        assertEquals(75315148176L, firstOnsetByTrack.get("主歌2 沨漪"));
        assertEquals(134174630114L, firstOnsetByTrack.get("副歌3 沨漪"));
        assertEquals(303518630114L, firstOnsetByTrack.get("副歌4 沨漪"));
    }

    /** 库组引用的摆位：blickOffset 平移、同组多位置、裁剪窗丢弃窗外音符。 */
    @Test
    public void parse_newFormatAppliesBlickOffsetAndClip() {
        String svp = """
                {"library": [
                   {"uuid": "g1", "notes": [
                      {"lyrics": "甲", "onset": 1000, "duration": 500},
                      {"lyrics": "乙", "onset": 2000, "duration": 500},
                      {"lyrics": "丙", "onset": 3000, "duration": 500}
                   ]}
                 ],
                 "tracks": [
                   {"name": "平移", "mainGroup": {"notes": []},
                    "groups": [{"groupID": "g1", "blickOffset": 100000}]},
                   {"name": "同组两处", "mainGroup": {"notes": []},
                    "groups": [{"groupID": "g1", "blickOffset": 200000},
                               {"groupID": "g1", "blickOffset": 300000}]},
                   {"name": "裁剪", "mainGroup": {"notes": []},
                    "groups": [{"groupID": "g1", "blickOffset": 100000,
                                "blickAbsoluteBegin": 0, "blickAbsoluteEnd": 101600}]}
                 ]}
                """;

        List<FillTrack> tracks = LyricFillParser.parseJson(svp);
        assertEquals(3, tracks.size());

        // 平移：onset 全部 +100000
        List<FillNote> moved = tracks.get(0).notes();
        assertEquals(List.of("甲", "乙", "丙"), moved.stream().map(FillNote::lyrics).toList());
        assertEquals(101000L, moved.get(0).onset());

        // 同一组以两个 offset 挂两次 → 两次都出现（不能按 groupID 去重丢掉）
        List<FillNote> twice = tracks.get(1).notes();
        assertEquals(6, twice.size());
        assertEquals(201000L, twice.getFirst().onset());
        assertEquals(301000L, twice.get(3).onset());

        // 裁剪窗 [0, 101600)：甲(101000~101500) 在窗内保留，乙/丙 完全在窗外丢弃
        List<FillNote> clipped = tracks.get(2).notes();
        assertEquals(List.of("甲"), clipped.stream().map(FillNote::lyrics).toList());
    }

    @Test
    public void hasAsciiLetter_onlyCountsAscii() {
        assertTrue(LyricFillAligner.hasAsciiLetter("hello"));
        assertTrue(LyricFillAligner.hasAsciiLetter("天涯hello"));
        assertTrue(!LyricFillAligner.hasAsciiLetter("天涯"));
        assertTrue(!LyricFillAligner.hasAsciiLetter("「」"));
    }
}
