package io.github.Nyameph.nyaentworks.common.pinyin;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link PinyinSyllable} / {@link PinyinUtil} 的音节分解与韵部归类。依赖打包的 pinyin-data 字典，可离线跑。 */
public class PinyinUtilTest {

    private static PinyinSyllable parse(String pinyin) {
        PinyinSyllable s = PinyinSyllable.parse(pinyin);
        assertNotNull(s, "parse(" + pinyin + ") 不应为 null");
        return s;
    }

    // ==================== 声调 / 无声调 ====================

    @Test
    public void tone_extractsFromMarkedVowel() {
        assertEquals(1, parse("zhōng").tone());   // 阴平
        assertEquals(2, parse("guó").tone());     // 阳平
        assertEquals(3, parse("wǒ").tone());      // 上声
        assertEquals(4, parse("zhòng").tone());   // 去声
        assertEquals(0, parse("ma").tone());      // 轻声（无标调）
    }

    @Test
    public void toneLess_stripsMarkAndKeepsUmlaut() {
        assertEquals("zhong", parse("zhōng").toneLess());
        assertEquals("zhong", parse("zhòng").toneLess());
        assertEquals("nü", parse("nǚ").toneLess());     // 保留 ü
        assertEquals("lüe", parse("lüè").toneLess());
        assertEquals("ma", parse("ma").toneLess());
    }

    // ==================== 声母 / 韵母 ====================

    @Test
    public void initialAndFinal_split() {
        PinyinSyllable zhong = parse("zhōng");
        assertEquals("zh", zhong.initial());
        assertEquals("ong", zhong.finals());

        PinyinSyllable nv = parse("nǚ");
        assertEquals("n", nv.initial());
        assertEquals("ü", nv.finals());

        PinyinSyllable lue = parse("lüè");
        assertEquals("l", lue.initial());
        assertEquals("üe", lue.finals());
    }

    @Test
    public void initialAndFinal_zeroInitial() {
        assertEquals("", parse("yī").initial());
        assertEquals("i", parse("yī").finals());

        assertEquals("", parse("wǔ").initial());
        assertEquals("u", parse("wǔ").finals());

        assertEquals("", parse("ér").initial());
        assertEquals("er", parse("ér").finals());
    }

    @Test
    public void initialAndFinal_jqxUmlautAndApical() {
        // j/q/x 后的 u 实为 ü
        assertEquals("ü", parse("jǔ").finals());
        assertEquals("üe", parse("jué").finals());
        assertEquals("üan", parse("quán").finals());
        // 舌尖声母后的 i 是舌尖元音
        assertEquals("-i", parse("zhī").finals());
        assertEquals("-i", parse("sì").finals());
        // 非舌尖声母后的 i 是齐齿 i
        assertEquals("i", parse("bī").finals());
    }

    // ==================== 韵部（十三辙 / 十四韵 / 十八韵） ====================

    @Test
    public void rhymeGroups() {
        assertEquals("中东", parse("zhōng").zhe13());
        assertEquals("十一庚", parse("zhōng").yun14());
        assertEquals("十八东", parse("zhōng").yun18());

        assertEquals("发花", parse("huā").zhe13());
        assertEquals("一麻", parse("huā").yun14());
        assertEquals("一麻", parse("huā").yun18());

        assertEquals("梭波", parse("hé").zhe13());
        assertEquals("二波", parse("hé").yun14());
        assertEquals("三歌", parse("hé").yun18());

        assertEquals("姑苏", parse("wǔ").zhe13());
        assertEquals("十四姑", parse("wǔ").yun14());
        assertEquals("十姑", parse("wǔ").yun18());

        assertEquals("一七", parse("zhī").zhe13());
        assertEquals("十三支", parse("zhī").yun14());
        assertEquals("五支", parse("zhī").yun18());

        assertEquals("一七", parse("ér").zhe13());
        assertEquals("十二齐", parse("ér").yun14());
        assertEquals("六儿", parse("ér").yun18());
    }

    // ==================== 多音字 / 字典 ====================

    @Test
    public void readings_polyphonicChar() {
        List<PinyinSyllable> rs = PinyinUtil.readings('重');
        List<String> toneLess = rs.stream().map(PinyinSyllable::toneLess).toList();
        assertTrue(toneLess.contains("zhong"), toneLess.toString());
        assertTrue(toneLess.contains("chong"), toneLess.toString());

        // 女：nü（还有 nǜ 去声、rǔ 上声）
        assertTrue(PinyinUtil.readings('女').stream()
                .map(PinyinSyllable::toneLess).anyMatch("nü"::equals));
    }

    @Test
    public void pinyin_returnsRawReadings() {
        assertEquals(List.of("huā"), PinyinUtil.pinyin('花'));
        assertTrue(PinyinUtil.pinyin('重').contains("zhòng"));
        assertTrue(PinyinUtil.pinyin('重').contains("chóng"));
    }

    @Test
    public void isHanzi() {
        assertTrue(PinyinUtil.isHanzi('中'));
        assertTrue(PinyinUtil.isHanzi('重'));
        assertFalse(PinyinUtil.isHanzi('a'));
        assertFalse(PinyinUtil.isHanzi('1'));
        assertFalse(PinyinUtil.isHanzi('，'));
    }

    // ==================== 字级（《通用规范汉字表》）====================

    @Test
    public void tier_firstSecondThirdAndAbsent() {
        assertEquals(1, PinyinUtil.tier('的'));   // 一级常用字
        assertEquals(1, PinyinUtil.tier('重'));
        assertEquals(2, PinyinUtil.tier('仕'));   // 二级
        assertEquals(3, PinyinUtil.tier('侘'));   // 三级（侘傺）
        assertEquals(0, PinyinUtil.tier('a'), "非汉字不在表内");
        assertEquals(0, PinyinUtil.tier(0x5159), "两本字典都没有的生僻汉字，表里也没有");
    }

    @Test
    public void tier_tableIsTheFull8105AndCoversTheModernDict() {
        // 字级表来自《通用规范汉字表》，恰好 8105 字；单测顺带守住条数别被改坏。
        int[] counts = new int[4];
        for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
            int t = PinyinUtil.tier(cp);
            if (t >= 1 && t <= 3) {
                counts[t]++;
            }
        }
        assertEquals(3500, counts[1], "一级字应为 3500");
        assertEquals(3000, counts[2], "二级字应为 3000");
        assertEquals(1605, counts[3], "三级字应为 1605");
        assertEquals(8105, counts[1] + counts[2] + counts[3]);
        // 现代标准字典（8105 字）里的每个字都该有字级 —— 两个来源本该是同一批字
        List<String> missing = new java.util.ArrayList<>();
        PinyinUtil.forEachModern((cp, raw) -> {
            if (PinyinUtil.tier(cp) == 0) {
                missing.add(new String(Character.toChars(cp)));
            }
        });
        assertTrue(missing.isEmpty(), "现代字典里有字级缺失：" + String.join("", missing));
    }

    // ==================== 读音常用度分级 ====================

    @Test
    public void syllables_splitIntoCommonAndRare() {
        // 「行」xíng/háng 都是常用音（字典顺序是 háng 在前，这里只看集合与个数）；
        // hèng/hàng 只在合并字典里（「道行」hèng、地名 hàng），自动算生僻
        assertEquals(List.of("hang", "xing"), PinyinUtil.commonSyllables('行'));
        assertEquals(List.of("heng"), PinyinUtil.rareSyllables('行'));
        // 「他」tā 常用、tuó 只活在合并字典里（古音）→ 自动算生僻
        assertEquals(List.of("ta"), PinyinUtil.commonSyllables('他'));
        assertEquals(List.of("tuo"), PinyinUtil.rareSyllables('他'));
        // 「区」qū 常用、ōu 在 reading_rare.txt 里被降级
        assertEquals(List.of("qu"), PinyinUtil.commonSyllables('区'));
        assertTrue(PinyinUtil.rareSyllables('区').contains("ou"));
    }

    @Test
    public void syllables_commonPlusRareIsAll() {
        // 两个集合互补且覆盖全部音节（这是分级的基本不变量）
        List<String> bad = new java.util.ArrayList<>();
        PinyinUtil.forEach((cp, raw) -> {
            java.util.Set<String> union = new java.util.LinkedHashSet<>(PinyinUtil.commonSyllables(cp));
            union.addAll(PinyinUtil.rareSyllables(cp));
            if (!union.equals(new java.util.LinkedHashSet<>(PinyinUtil.allSyllables(cp)))) {
                bad.add(new String(Character.toChars(cp)));
            }
            for (String s : PinyinUtil.commonSyllables(cp)) {
                if (!PinyinUtil.isCommonSyllable(cp, s)) {
                    bad.add(new String(Character.toChars(cp)) + ":" + s);
                }
            }
        });
        assertTrue(bad.isEmpty(), bad.size() + " 个字的分级自相矛盾：" + String.join(" ", bad));
    }

    @Test
    public void readingsByFrequency_putsCommonFirst() {
        // 「行」xíng 比 háng 常用（字典顺序里 xing 本来就在前，换个顺序靠后的字才看得出来）
        List<PinyinSyllable> rs = PinyinUtil.readingsByFrequency('区');
        assertEquals("qu", rs.get(0).toneLess(), rs.toString());
        assertEquals("ou", rs.get(rs.size() - 1).toneLess(), rs.toString());

        // 「他」：常用音 tā 在前，古音 tuó 在后
        List<PinyinSyllable> ta = PinyinUtil.readingsByFrequency('他');
        assertEquals("ta", ta.get(0).toneLess());
        assertTrue(ta.stream().anyMatch(s -> "tuo".equals(s.toneLess())));
    }

    @Test
    public void readingsByFrequency_coversSupplementReadings() {
        // 「骑」两本词典都只有 qí，jì 来自 reading_common.txt 的补录
        assertFalse(PinyinUtil.pinyin('骑').contains("jì"), "词典里本来就没有 jì");
        List<String> all = PinyinUtil.allSyllables('骑');
        assertTrue(all.containsAll(List.of("qi", "ji")), all.toString());
        // jì 补进来就是常用音，所以「骑」有 qí / jì 两个常用读音
        assertEquals(List.of("qi", "ji"), PinyinUtil.commonSyllables('骑'));
        assertTrue(PinyinUtil.rareSyllables('骑').isEmpty());
    }

    @Test
    public void readingsByFrequency_neverThrowsForUnknownChars() {
        // 分级数据缺失 / 非汉字 → 空列表，不抛（§0.5 明确要求的降级路径）
        int unknown = 0x5159;   // 两本字典都没有的生僻汉字
        assertTrue(PinyinUtil.readingsByFrequency('a').isEmpty());
        assertTrue(PinyinUtil.readingsByFrequency(unknown).isEmpty());
        assertTrue(PinyinUtil.commonSyllables(unknown).isEmpty());
        assertTrue(PinyinUtil.allSyllables(unknown).isEmpty());
        assertEquals(0, PinyinUtil.tier(unknown));
    }

    // ==================== 文本 → 拼音（「导出拼音」开关） ====================

    @Test
    public void toPinyin_hanziToTonelessSyllable() {
        assertEquals("hua", PinyinUtil.toPinyin("花"));
        assertEquals("zhongguo", PinyinUtil.toPinyin("中国"), "逐字转，不加分隔");
        assertEquals("shuidiaogetou·mingyuejishiyou", PinyinUtil.toPinyin("水调歌头·明月几时有"),
                "「·」不是汉字，原样留在中间");
    }

    @Test
    public void toPinyin_umlautWrittenAsV() {
        // 与工程里拼音音符同一口径（PinyinSyllable.toneless）：ü 一族写作 v
        assertEquals("nv", PinyinUtil.toPinyin("女"));
        assertEquals("lv", PinyinUtil.toPinyin("绿"));
        assertEquals("lve", PinyinUtil.toPinyin("略"));
    }

    @Test
    public void toPinyin_polyphonePicksTheMostCommonReading() {
        // 多音字取 readingsByFrequency 的第一条：生僻音（古音 / 异体音）一律排在常用音后面
        assertEquals("zhong", PinyinUtil.toPinyin("重"));   // zhòng，不是 chóng/tóng
        assertEquals("xing", PinyinUtil.toPinyin("行"));    // xíng，不是 háng / 降级的 hèng
        assertEquals("ta", PinyinUtil.toPinyin("他"));      // tā，不是古音 tuó
        // 「长」zhǎng / cháng 两个都是常用音，组内按字典原顺序 → 取合并字典在前的 zhǎng
        // （两个读音都合法，要指定哪个音就用多音字回填替换，别指望这里猜准）
        assertEquals("zhang", PinyinUtil.toPinyin("长"));
    }

    /**
     * 缩写韵母（iu / ui / un）必须参与拆解 —— 字典写的是缩写（shuǐ、lùn、liù），全拼 uei/uen/iou
     * 只从零声母的 wei/wen/you 走到。少了它们，这些字的韵身取不到，{@link PinyinSyllable#parse}
     * 会把整条读音判成非标准而丢弃 —— 2000 多个常见字的「导出拼音」会原样留汉字（水 / 论 / 六 /
     * 会 / 春 / 脆 / 腿 …）。
     */
    @Test
    public void parse_abbreviatedFinals() {
        assertEquals("ui", parse("shuǐ").finals());
        assertEquals("ei", parse("shuǐ").rhymeBody(), "灰堆辙");
        assertEquals("un", parse("lùn").finals());
        assertEquals("en", parse("lùn").rhymeBody(), "人辰辙");
        assertEquals("iu", parse("liù").finals());
        assertEquals("ou", parse("liù").rhymeBody(), "由求辙");
        // j/q/x 后的 un 仍是 ün（徐、军），别被缩写合并吃掉
        assertEquals("ün", parse("jūn").finals());
        // 零声母本来就写全拼，两条路给出的韵身一致
        assertEquals(parse("wēi").rhymeBody(), parse("huī").rhymeBody());
        assertEquals(parse("yōu").rhymeBody(), parse("qiū").rhymeBody());
    }

    @Test
    public void toPinyin_abbreviatedFinalsAreConverted() {
        assertEquals("shui", PinyinUtil.toPinyin("水"));
        assertEquals("lun", PinyinUtil.toPinyin("论"));
        assertEquals("liu", PinyinUtil.toPinyin("六"));
        assertEquals("hui", PinyinUtil.toPinyin("会"));
        assertEquals("chun", PinyinUtil.toPinyin("春"));
        assertEquals("dui", PinyinUtil.toPinyin("对"));
        assertEquals("niu", PinyinUtil.toPinyin("牛"));
    }

    /**
     * 韵母表只可能「漏」在缩写上，其余拆不出的读音都是**非标准拼写**（叹词 / 语气词的 {@code ḿ}、
     * {@code ń}、{@code hng}、{@code yo} 一类），这是设计内的跳过。这条守住「漏的字只有这些」
     * —— 新增一个漏掉的韵母，这里就会多出一个字。
     */
    @Test
    public void readingsByFrequency_onlyInterjectionSpellingsAreDropped() {
        List<String> blank = new java.util.ArrayList<>();
        PinyinUtil.forEach((cp, raw) -> {
            if (!PinyinUtil.pinyin(cp).isEmpty() && PinyinUtil.readingsByFrequency(cp).isEmpty()) {
                blank.add(new String(Character.toChars(cp)));
            }
        });
        List<String> expected = new java.util.ArrayList<>(List.of("㕶", "哟", "喲", "嗯", "𠮾", "𥦷"));
        java.util.Collections.sort(expected);
        java.util.Collections.sort(blank);
        assertEquals(expected, blank);
    }

    @Test
    public void toPinyin_keepsSeparatorsAndNonHanzi() {
        // 槽位占位 / 换气 / 英文词 / 撇号 / 标点 / 数字 / 空白一律原样（SynthV 靠它们认槽位）
        assertEquals("- br ' hi A1 ，。《》", PinyinUtil.toPinyin("- br ' hi A1 ，。《》"));
        assertEquals("Nǐ hǎo", PinyinUtil.toPinyin("Nǐ hǎo"), "带声调的拉丁字母不是汉字，不动");
        assertEquals("'ceng", PinyinUtil.toPinyin("'曾"), "喉塞音前缀留着，只转后面的汉字");
    }

    @Test
    public void toPinyin_unconvertibleKeepsHanzi() {
        // 叹词「嗯」只有 ń/ňg 一类非标准拼写：宁可留个汉字，也别变成空把词对位打乱
        assertEquals("嗯", PinyinUtil.toPinyin("嗯"));
        int unknown = 0x5159;
        assertEquals(new String(Character.toChars(unknown)),
                PinyinUtil.toPinyin(new String(Character.toChars(unknown))));
        assertEquals("", PinyinUtil.toPinyin(""));
        assertNull(PinyinUtil.toPinyin(null));
    }
}
