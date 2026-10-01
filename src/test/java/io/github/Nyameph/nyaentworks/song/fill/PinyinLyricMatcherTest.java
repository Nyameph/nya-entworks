package io.github.Nyameph.nyaentworks.song.fill;

import org.junit.jupiter.api.Test;
import io.github.Nyameph.nyaentworks.song.fill.LyricTemplate.FillNote;
import io.github.Nyameph.nyaentworks.song.fill.LyricTemplate.FillTrack;
import io.github.Nyameph.nyaentworks.common.lyric.LyricLine;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 拼音模板 ↔ demo 歌词匹配（填词工具增强）。纯函数，fixture 硬编码。 */
public class PinyinLyricMatcherTest {

    private static long blick(double seconds) {
        return Math.round(seconds * LyricFillParser.BLICK_PER_SECOND);
    }

    private static FillNote note(String lyrics, double second) {
        return new FillNote(blick(second), blick(0.5), lyrics, LyricFillParser.slotTypeOf(lyrics));
    }

    private static FillTrack track(int index, FillNote... notes) {
        return new FillTrack(index, "轨" + index, List.of(notes));
    }

    private static LyricLine lrc(double start, String text) {
        return new LyricLine(start, null, text);
    }

    // ==================== 归一化 ====================

    @Test
    public void normalize_stripsToneAndUmlaut() {
        assertEquals("zhong", PinyinLyricMatcher.normalize("zhōng"));
        assertEquals("zhong", PinyinLyricMatcher.normalize("zhong4"));
        assertEquals("zhong", PinyinLyricMatcher.normalize("ZHONG"));
        assertEquals("nv", PinyinLyricMatcher.normalize("nǚ"));
        assertEquals("nv", PinyinLyricMatcher.normalize("nü"));
        assertEquals("lve", PinyinLyricMatcher.normalize("lüe"));
        assertEquals("", PinyinLyricMatcher.normalize(null));
        assertEquals("", PinyinLyricMatcher.normalize(" "));
    }

    // ==================== 读音 / 多音字 ====================

    @Test
    public void readings_polyphonicChar() {
        // 「重」是多音字：zhòng / chóng（/ tóng）
        assertTrue(PinyinLyricMatcher.readings("重").contains("chong"));
        assertTrue(PinyinLyricMatcher.readings("重").contains("zhong"));
        assertTrue(PinyinLyricMatcher.readings("好").contains("hao"));
        // 非单个汉字（英文词 / 标点）无读音
        assertTrue(PinyinLyricMatcher.readings("hello").isEmpty());
        assertTrue(PinyinLyricMatcher.readings("，").isEmpty());
    }

    @Test
    public void commonReadings_dropsRareToneReadings() {
        // 全量字典带古音 / 异体音：「听」tīng/yǐn/yí、「夏」xià/jiǎ、「那」nà/nuó/nèi/nè/nǎi
        assertTrue(PinyinLyricMatcher.readings("听").contains("yi"));
        assertTrue(PinyinLyricMatcher.readings("听").contains("yin"));
        assertEquals(Set.of("ting"), PinyinLyricMatcher.commonReadings("听"));
        assertEquals(Set.of("xia"), PinyinLyricMatcher.commonReadings("夏"));
        // 当年同音回退要兜的是**常用**同音字（《被风吹过的夏天》纳/那、《不问ciaga》淋/林），
        // 这些都在常用读音里，不受影响
        assertEquals(Set.of("na"), PinyinLyricMatcher.commonReadings("纳"));
        assertEquals(Set.of("na"), PinyinLyricMatcher.commonReadings("那"));
        assertEquals(Set.of("lin"), PinyinLyricMatcher.commonReadings("林"));
        // 非单个汉字（英文词 / 多字 / 标点）无读音
        assertTrue(PinyinLyricMatcher.commonReadings("CD").isEmpty());
        assertTrue(PinyinLyricMatcher.commonReadings("爱情").isEmpty());
    }

    @Test
    public void matches_anyReadingWins() {
        assertTrue(PinyinLyricMatcher.matches("chong", "重"));
        assertTrue(PinyinLyricMatcher.matches("zhong", "重"));
        assertFalse(PinyinLyricMatcher.matches("zhang", "重"));
        assertTrue(PinyinLyricMatcher.matches("wo", "我"));
        assertFalse(PinyinLyricMatcher.matches("wo", "hello"));
    }

    @Test
    public void matches_umlautWrittenAsUOrV() {
        // 读音字典按规范记 ü（归一成 v），svp 里的音符常写成 u —— 两种写法都要认。
        // 实测《Masked bitcH》「掠过漫不经心一个吻」的音符写 lue、字典读 lve，
        // 只认一种时那一句 9 个字一个都没配上。
        assertTrue(PinyinLyricMatcher.matches("lue", "掠"));
        assertTrue(PinyinLyricMatcher.matches("lve", "掠"));
        assertTrue(PinyinLyricMatcher.matches("nue", "虐"));
        assertTrue(PinyinLyricMatcher.matches("nve", "虐"));
        // 女：字典给 ru / nv 两个读音，nu 是 nv 的另一种写法
        assertTrue(PinyinLyricMatcher.matches("nu", "女"));
        assertTrue(PinyinLyricMatcher.matches("nv", "女"));
        // j/q/x/y 后的 u 本来就是 ü 的简写，没有第二种写法，不产生多余候选
        assertEquals(Set.of("jue"), PinyinLyricMatcher.noteVariants("jue"));
        assertEquals(Set.of("qu"), PinyinLyricMatcher.noteVariants("qu"));
        // 多出来的候选不会误命中：怒读 nu（真 u），不会因为变体匹配上 nü 的字
        assertTrue(PinyinLyricMatcher.matches("nu", "怒"));
        assertFalse(PinyinLyricMatcher.matches("lv", "怒"));
        // 声母不是 l/n 的 u 不动
        assertEquals(Set.of("du"), PinyinLyricMatcher.noteVariants("du"));
        assertEquals(Set.of("lue", "lve"), PinyinLyricMatcher.noteVariants("lue"));
        assertEquals(Set.of("lve", "lue"), PinyinLyricMatcher.noteVariants("lüe"));
        assertEquals(Set.of(), PinyinLyricMatcher.noteVariants(""));
    }

    // ==================== 检测 ====================

    @Test
    public void isPinyinTemplate_detects() {
        assertTrue(PinyinLyricMatcher.isPinyinTemplate(List.of("wo", "ai"), List.of("我", "爱")));
        // 音符里有汉字（汉字模板）→ 不是拼音模板
        assertFalse(PinyinLyricMatcher.isPinyinTemplate(List.of("我", "爱"), List.of("我", "爱")));
        // demo 没有汉字 → 不是拼音模板
        assertFalse(PinyinLyricMatcher.isPinyinTemplate(List.of("wo", "ai"), List.of("hello")));
        assertFalse(PinyinLyricMatcher.isPinyinTemplate(List.of(), List.of("我")));
    }

    @Test
    public void isPinyinTemplate_toleratesJunkNonAsciiNotes() {
        // 实测《aLIEz》：有一个音符的歌词是手误的数字 0（被 slotTypeOf 判成 HANZI）。
        // 原来全有全无的判定让整首掉出拼音路径；现在容忍个别手误，仍判是拼音模板。
        List<String> aliezNotes = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            aliezNotes.add("yuan");
        }
        aliezNotes.set(20, "0");
        assertTrue(PinyinLyricMatcher.isPinyinTemplate(aliezNotes, List.of("夜")));

        // 放宽不等于放水：字母音符不占多数（一半是汉字）→ 仍判否
        assertFalse(PinyinLyricMatcher.isPinyinTemplate(List.of("wo", "我"), List.of("我")));
        // 非字母超限（40 个里 3 个非字母 > 5%）→ 判否
        List<String> tooMany = new ArrayList<>(aliezNotes);
        tooMany.set(21, "我");
        tooMany.set(22, "我");
        assertFalse(PinyinLyricMatcher.isPinyinTemplate(tooMany, List.of("夜")));
        // 底线：一个字母音符都没有 → 判否
        assertFalse(PinyinLyricMatcher.isPinyinTemplate(List.of("0"), List.of("夜")));
    }

    // ==================== 对齐 / 跳过规则 ====================

    @Test
    public void split_pinyinTemplateWithJunkDigitNoteStillMatches() {
        // 实测《aLIEz》回归：模板里混进一个手误数字音符「0」，全有全无的模板判定曾让
        // 整首掉出拼音路径（分句退回时间戳、输入区灰显拼音）；现在容忍它继续走拼音路径
        List<FillTrack> tracks = List.of(track(0,
                note("sui", 16.0), note("lei", 16.5), note("0", 17.0), note("zheng", 17.5),
                note("fa", 18.0)));

        LyricFillAligner.SplitResult result = LyricFillAligner.split(tracks, List.of(0),
                List.of(lrc(16.0, "随泪蒸发")), null);

        // 走的是拼音路径（没有整体偏移），而不是退回时间戳分句
        assertNull(result.offset());
        // 「0」匹配不上任何读音 → 不占字；其余按读音对上
        assertEquals(List.of("随", "泪", "", "蒸", "发"), result.defaults().get(0));
    }

    @Test
    public void align_skipsUpToTwoExtraCharsWhenRunCrossesLine() {
        // demo「我多爱你很好」比模板「wo ai ni hen hao」多一个「多」：跳过 1 个字后能连续匹配
        // 跨过歌词行边界（爱在行 0、你在行 1），所以「多」被跳过、不占音符
        PinyinLyricMatcher.Match m = PinyinLyricMatcher.align(
                List.of("wo", "ai", "ni", "hen", "hao"),
                List.of("我", "多", "爱", "你", "很", "好"),
                List.of(0, 0, 0, 1, 1, 1));

        assertTrue(m.matched());
        assertArrayEquals(new int[]{0, 2, 3, 4, 5}, m.noteToLyric());
    }

    @Test
    public void align_extraNoteAdvancesWithoutMatch() {
        // 模板比 demo 多一个音符（「xi」没有对应汉字）：该音符前移、记为 -1
        PinyinLyricMatcher.Match m = PinyinLyricMatcher.align(
                List.of("wo", "xi", "ai"),
                List.of("我", "爱"),
                List.of(0, 0));

        assertTrue(m.matched());
        assertArrayEquals(new int[]{0, -1, 1}, m.noteToLyric());
    }

    @Test
    public void align_dropsDeadCharInsteadOfWedging() {
        // 实测《aLIEz》回归：demo「挣扎中堕落无法」的「扎」在模板里没有对应音符
        //（模板把它并进了延音格），且模板把这些字都唱了两遍（双声部拷贝）。
        // 没有死字规则时 j 会焊死在「扎」上，后面整首 0 命中（实测匹配率掉到 0.24）
        PinyinLyricMatcher.Match m = PinyinLyricMatcher.align(
                List.of("zheng", "zheng", "zhong", "zhong", "duo", "duo",
                        "luo", "luo", "wu", "fa"),
                List.of("挣", "扎", "中", "堕", "落", "无", "法"),
                List.of(0, 0, 0, 0, 0, 1, 1));

        assertTrue(m.matched());
        assertArrayEquals(new int[]{0, -1, 2, -1, 3, -1, 4, -1, 5, 6}, m.noteToLyric());
    }

    @Test
    public void align_lowRatioNotMatched() {
        // 音符拼音与汉字读音完全对不上（配错文件）：匹配率 0 → 判未匹配，回落现有分句
        PinyinLyricMatcher.Match m = PinyinLyricMatcher.align(
                List.of("wo", "ai"),
                List.of("他", "她", "它", "的"),
                List.of(0, 0, 0, 0));

        assertFalse(m.matched());
    }

    // ==================== 分句 + 默认词（走 LyricFillAligner.split）====================

    @Test
    public void split_pinyinTemplateSplitsAndFillsDefaults() {
        List<FillTrack> tracks = List.of(track(0,
                note("ni", 16.0), note("hao", 16.5), note("shi", 17.0), note("jie", 17.5),
                note("he", 18.0), note("ping", 18.5)));

        LyricFillAligner.SplitResult result = LyricFillAligner.split(tracks, List.of(0),
                List.of(lrc(16.0, "你好世界"), lrc(18.0, "和平")), null);

        // 拼音路径没有「整体偏移」这回事
        assertNull(result.offset());
        assertEquals(2, result.lines().size());
        assertEquals("nihaoshijie", result.lines().get(0).originalText());
        assertEquals(4, result.lines().get(0).needCount());
        assertEquals("heping", result.lines().get(1).originalText());

        // 默认词占位符：命中的可填槽位放汉字
        assertEquals(List.of("你", "好", "世", "界"), result.defaults().get(0));
        assertEquals(List.of("和", "平"), result.defaults().get(1));
    }

    @Test
    public void split_matchesUmlautNoteWrittenAsU() {
        // 实测《Masked bitcH》回归：demo「[00:19.81]掠过漫不经心一个吻」对应的音符是
        // 「lue - guo man - - bu jing - - xin yi - ge wen」。音符把 ü 写成 u（lue），
        // 字典读 lve —— 只认一种写法时这句 9 个字一个都没配上（整句默认词全空）。
        List<FillTrack> tracks = List.of(track(0,
                note("lue", 16.0), note("-", 16.5), note("guo", 17.0), note("man", 17.5),
                note("-", 18.0), note("-", 18.5), note("bu", 19.0), note("jing", 19.5),
                note("-", 20.0), note("-", 20.5), note("xin", 21.0), note("yi", 21.5),
                note("-", 22.0), note("ge", 22.5), note("wen", 23.0)));

        LyricFillAligner.SplitResult result = LyricFillAligner.split(tracks, List.of(0),
                List.of(lrc(16.0, "掠过漫不经心一个吻")), null);

        assertTrue(result.pinyin(), "音符是拼音、歌词是汉字 → 拼音模板");
        assertEquals(1, result.lines().size());
        assertEquals(9, result.lines().get(0).needCount(), "9 个拼音音符对应 9 个字");
        // 延音格（-）没有默认词，占位保持空格子
        assertEquals(List.of("掠", "", "过", "漫", "", "", "不", "经", "", "",
                "心", "一", "", "个", "吻"), result.defaults().get(0));
    }

    @Test
    public void split_pinyinTemplateMarksLyricSpaceAsGapWithoutSplitting() {
        // demo「五百年前一场疯 腾霄又是孙悟空」是一句（行内带空格），模板 14 个拼音音节：
        // 空格不占音符、不断句，只在「疯」之后标一个视觉空位（gaps[0][6] = true）
        List<FillTrack> tracks = List.of(track(0,
                note("wu", 16.0), note("bai", 16.5), note("nian", 17.0), note("qian", 17.5),
                note("yi", 18.0), note("chang", 18.5), note("feng", 19.0), note("teng", 19.5),
                note("xiao", 20.0), note("you", 20.5), note("shi", 21.0), note("sun", 21.5),
                note("wu", 22.0), note("kong", 22.5)));

        LyricFillAligner.SplitResult result = LyricFillAligner.split(tracks, List.of(0),
                List.of(lrc(16.0, "五百年前一场疯 腾霄又是孙悟空")), null);

        // 行内空格不断句：整句还是一句
        assertEquals(1, result.lines().size());
        assertEquals(14, result.lines().get(0).needCount());

        // 默认词占位符：14 个汉字
        assertEquals(List.of("五", "百", "年", "前", "一", "场", "疯",
                "腾", "霄", "又", "是", "孙", "悟", "空"), result.defaults().get(0));

        // 视觉空位：只有「疯」之后（下标 6）有，其余没有
        List<Boolean> gaps = result.gaps().get(0);
        assertEquals(14, gaps.size());
        for (int k = 0; k < 14; k++) {
            assertEquals(k == 6, gaps.get(k).booleanValue(), "下标 " + k);
        }
    }

    @Test
    public void split_hanziTemplateMarksLyricSpaceAsGap() {
        // 汉字模板（原词就是汉字，如《九九八十一》）：demo「五百年前一场疯 腾霄又是孙悟空」
        // 里的行内空格要透出成视觉空位 —— 空格不占音符、不断句，只在「疯」之后标一个空位
        //（gaps[0][6] = true）。这是汉字模板路径，不是拼音模板路径。
        List<FillTrack> tracks = List.of(track(0,
                note("五", 16.0), note("百", 16.5), note("年", 17.0), note("前", 17.5),
                note("一", 18.0), note("场", 18.5), note("疯", 19.0), note("腾", 19.5),
                note("霄", 20.0), note("又", 20.5), note("是", 21.0), note("孙", 21.5),
                note("悟", 22.0), note("空", 22.5)));

        LyricFillAligner.SplitResult result = LyricFillAligner.split(tracks, List.of(0),
                List.of(lrc(16.0, "五百年前一场疯 腾霄又是孙悟空")), null);

        // 行内空格不断句：整句还是一句
        assertEquals(1, result.lines().size());
        assertEquals(14, result.lines().get(0).needCount());

        // 汉字模板没有默认词占位符（原词行已显示汉字），全是空串
        assertEquals(List.of("", "", "", "", "", "", "", "", "", "", "", "", "", ""),
                result.defaults().get(0));

        // 视觉空位：只有「疯」之后（下标 6）有，其余没有
        List<Boolean> gaps = result.gaps().get(0);
        assertEquals(14, gaps.size());
        for (int k = 0; k < 14; k++) {
            assertEquals(k == 6, gaps.get(k).booleanValue(), "下标 " + k);
        }
    }

    @Test
    public void split_hanziTemplateHasNoDefaults() {
        // 汉字模板不受影响：默认词是每句一个空列表（与 lines 同形状）
        List<FillTrack> tracks = List.of(track(0,
                note("天", 16.0), note("涯", 16.5), note("若", 18.0), note("比", 18.5)));

        LyricFillAligner.SplitResult result = LyricFillAligner.split(tracks, List.of(0),
                List.of(lrc(17.0, "天涯"), lrc(19.0, "若比")), null);

        assertEquals(2, result.lines().size());
        assertEquals(List.of("", ""), result.defaults().get(0));
        assertEquals(List.of("", ""), result.defaults().get(1));
    }

    @Test
    public void defaults_cachedPathProjectsOntoManualLines() {
        // 直读缓存路径：defaults 按当前分句（即使手动拆过）现算
        List<FillTrack> tracks = List.of(track(0,
                note("ni", 16.0), note("hao", 16.5), note("shi", 17.0)));

        // 手动把「你好」并成一句、把「世」单独成一句
        List<LyricTemplate.FillLine> lines = List.of(
                new LyricTemplate.FillLine(
                        List.of(new LyricTemplate.FillSlot(0, 0, "ni", SlotType.ENGLISH),
                                new LyricTemplate.FillSlot(0, 1, "hao", SlotType.ENGLISH)),
                        "nihao", 2, blick(16.0)),
                new LyricTemplate.FillLine(
                        List.of(new LyricTemplate.FillSlot(0, 2, "shi", SlotType.ENGLISH)),
                        "shi", 1, blick(17.0)));

        List<List<String>> defaults = LyricFillAligner.defaults(tracks, List.of(0), lines,
                List.of(lrc(16.0, "你好世界")));

        assertEquals(List.of("你", "好"), defaults.get(0));
        assertEquals(List.of("世"), defaults.get(1));
    }

    @Test
    public void gaps_cachedPathMarksHanziLyricSpace() {
        // 直读缓存路径：汉字模板的 gaps 按当前分句现算（打开已存在的填词走的是这条路，
        // 不是 split）。歌词「五百年前一场疯 腾霄又是孙悟空」里的行内空格标在「疯」之后。
        List<FillTrack> tracks = List.of(track(0,
                note("五", 16.0), note("百", 16.5), note("年", 17.0), note("前", 17.5),
                note("一", 18.0), note("场", 18.5), note("疯", 19.0), note("腾", 19.5),
                note("霄", 20.0), note("又", 20.5), note("是", 21.0), note("孙", 21.5),
                note("悟", 22.0), note("空", 22.5)));

        List<String> chars = List.of("五", "百", "年", "前", "一", "场", "疯",
                "腾", "霄", "又", "是", "孙", "悟", "空");
        List<LyricTemplate.FillSlot> slots = new ArrayList<>();
        for (int i = 0; i < chars.size(); i++) {
            slots.add(new LyricTemplate.FillSlot(0, i, chars.get(i), SlotType.HANZI));
        }
        List<LyricTemplate.FillLine> lines = List.of(
                new LyricTemplate.FillLine(slots, "五百年前一场疯腾霄又是孙悟空", 14, blick(16.0)));

        List<List<Boolean>> gaps = LyricFillAligner.gaps(tracks, List.of(0), lines,
                List.of(lrc(16.0, "五百年前一场疯 腾霄又是孙悟空")));

        List<Boolean> row = gaps.get(0);
        assertEquals(14, row.size());
        for (int k = 0; k < 14; k++) {
            assertEquals(k == 6, row.get(k).booleanValue(), "下标 " + k);
        }
    }
}
