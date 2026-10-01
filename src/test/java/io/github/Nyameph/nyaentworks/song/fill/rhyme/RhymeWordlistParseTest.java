package io.github.Nyameph.nyaentworks.song.fill.rhyme;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.Nyameph.nyaentworks.song.entity.RhymeEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 开源词表（{@code rhyme/open-rhyme-words.tsv}）的行解析与词性映射
 * （{@link RhymeWordlistService#toEntry} / {@link RhymeWordlistService#posToClass}）。纯函数，可离线跑。
 *
 * <p>钉四件事：词性映射必须落在 {@link RhymeService#WORD_CLASSES} 里（表外的落「其他」）、
 * 坏行返回 null 而不是抛异常或产出半条、{@code entryType} 按字数分、整份词表<b>每一行都拆得出韵部</b>。
 * 最后一条是这份数据集的准入门槛 —— 有拆不出的行说明生成口径错了，不该悄悄进库。
 */
public class RhymeWordlistParseTest {

    /** 正常行：词 / 逐字拼音 / 词频 / jieba 词性，四列齐全。 */
    @Test
    public void toEntry_plainWord() {
        RhymeEntry e = RhymeWordlistService.toEntry("一下子\tyī xià zi\t2333\tm");
        assertNotNull(e);
        assertEquals("WORD", e.getEntryType());
        assertEquals("一下子", e.getText());
        // pinyin 存的一直是**尾字读音**（多音字一音一行），整串拼音取最后一个音节
        assertEquals("zi", e.getPinyin());
        assertEquals("-i", e.getFinals());
        assertEquals("-i", e.getRhymeBody());
        assertEquals("五支", e.getYun18());
        assertEquals("数量词", e.getWordClass());
        assertEquals(RhymeService.SOURCE_OPEN, e.getSource());
        assertEquals(2333, e.getFreq());
        assertNull(e.getNote());
    }

    /** 儿化音：尾音节是 `er`，韵身与十八韵都该认出来（这是外部词表最容易翻车的一类）。 */
    @Test
    public void toEntry_erhuaTail() {
        RhymeEntry e = RhymeWordlistService.toEntry("一丁点儿\tyī dīng diǎn er\t24\tm");
        assertNotNull(e);
        assertEquals("er", e.getPinyin());
        assertEquals("er", e.getRhymeBody());
        assertEquals("六儿", e.getYun18());
    }

    /** 单字走 CHAR、多字走 WORD（与 {@code /entries/batch} 同一判法）。 */
    @Test
    public void toEntry_entryTypeByLength() {
        assertEquals("CHAR", RhymeWordlistService.toEntry("马\tmǎ\t100\tn").getEntryType());
        assertEquals("WORD", RhymeWordlistService.toEntry("兵马\tbīng mǎ\t100\tn").getEntryType());
    }

    /** 坏行一律 null（调用方按失败计数），不抛异常、也不产出半条。 */
    @ParameterizedTest
    @ValueSource(strings = {
            "词\tpīn yīn",              // 缺列
            "词\tpīn yīn\tabc\tn",      // 词频不是数字
            "词\tzzz\t10\tn",           // 拼音拆不出韵部
            "\tpīn yīn\t10\tn",         // 词是空
            "词\t\t10\tn",              // 拼音是空
            "",                         // 空行
    })
    public void toEntry_badLineIsNull(String line) {
        assertNull(RhymeWordlistService.toEntry(line));
    }

    /**
     * 词性映射：8 值枚举内的对应关系，加上「表外的落其他」。
     *
     * <p>{@code i}（成语）/ {@code l}（惯用语）/ {@code j}（简称）故意没有对应 —— 它们不属于
     * 那八个里的任何一个，硬塞进「名词/动词」会让词典页的词性过滤骗人。
     */
    @ParameterizedTest
    @CsvSource({
            "n,名词", "nz,名词", "vn,名词", "s,名词", "t,名词",
            "v,动词",
            "a,形容词", "z,形容词",
            "d,副词",
            "r,代词",
            "m,数量词", "q,数量词",
            "c,虚词", "p,虚词", "u,虚词", "y,虚词", "e,虚词", "o,虚词",
            "i,其他", "l,其他", "j,其他", "b,其他", "f,其他",
            "nr,其他", "unknown,其他",
    })
    public void posToClass_mapsIntoEnum(String pos, String expected) {
        assertEquals(expected, RhymeWordlistService.posToClass(pos));
    }

    @Test
    public void posToClass_nullAndBlankFallToOther() {
        assertEquals("其他", RhymeWordlistService.posToClass(null));
        assertEquals("其他", RhymeWordlistService.posToClass(""));
        assertEquals("其他", RhymeWordlistService.posToClass("   "));
    }

    /** 映射表的值必须全是枚举内的值 —— 落个枚举外的等于这一列又脏回去（查询侧只认枚举）。 */
    @Test
    public void posToClass_valuesAreAllInWordClasses() {
        for (String cls : RhymeWordlistService.POS_TO_CLASS.values()) {
            assertTrue(RhymeService.WORD_CLASSES.contains(cls), cls + " 不在 WORD_CLASSES 里");
        }
    }

    /**
     * 已知拆不出的两行：尾音节 {@code yō} / {@code yo}。普通话里 {@code yo} 只作叹词用，
     * 不是标准音节，{@link io.github.Nyameph.nyaentworks.common.pinyin.PinyinSyllable} 的韵母表里没有它，
     * 所以这两行<b>该</b>拆不出 —— 它们会进导入任务的 {@code failed} 计数（不静默丢）。
     * 除了这两个词，别的行拆不出都说明词表生成口径坏了。
     */
    private static final Set<String> KNOWN_UNPARSEABLE = Set.of("哎哟", "啊哟");

    /**
     * 整份词表：除两个已知叹词外每一行都拆得出韵部，且词性都在枚举里。
     *
     * <p>不钉确切行数（重新生成词表不该逼着改测试），钉的是「这份数据能不能整份进库」。
     */
    @Test
    public void wordlist_everyLineParses() throws Exception {
        List<String> lines = RhymeWordlistService.readDataLines();
        assertTrue(lines.size() > 10000, "词表行数太少，是不是读错了文件：" + lines.size());
        List<String> unexpected = new ArrayList<>();
        for (String line : lines) {
            RhymeEntry e = RhymeWordlistService.toEntry(line);
            if (e == null) {
                String word = line.split("\t", -1)[0];
                if (!KNOWN_UNPARSEABLE.contains(word)) {
                    unexpected.add(line);
                }
                continue;
            }
            assertTrue(RhymeService.WORD_CLASSES.contains(e.getWordClass()),
                    "词性不在枚举里：" + e.getWordClass());
        }
        assertEquals(List.of(), unexpected, "有意外拆不出的行（只允许 " + KNOWN_UNPARSEABLE + "）");
    }

    /** 词表头部的 `#` 注释与空行不该被当成数据行读进来。 */
    @Test
    public void readDataLines_skipsComments() throws Exception {
        for (String line : RhymeWordlistService.readDataLines()) {
            assertTrue(!line.isEmpty() && line.charAt(0) != '#', "注释/空行混进来了：" + line);
        }
    }

    /**
     * 回归：{@link RhymeService#lastSyllable} <b>单独吃不下</b>空格分隔的整串拼音
     * （它逐字符按最长可解析前缀切，切到空格就整个返回 null）—— 所以 {@code toEntry} 必须先取尾音节。
     *
     * <p>这个坑没有单测就会静默：整份词表 46,787 行会全部解析失败，而任务只会报一句「解析失败 N 行」。
     */
    @Test
    public void lastSyllable_aloneCannotEatSpacedPinyin() {
        assertNull(RhymeService.lastSyllable("yī xià zi"));
        assertNotNull(RhymeService.lastSyllable(RhymeWordlistService.tailSyllable("yī xià zi")));
    }

    /** 尾音节提取：半角空格 / tab / 全角空格都算分隔符；空串与 null 返回 null。 */
    @Test
    public void tailSyllable_takesLastToken() {
        assertEquals("zi", RhymeWordlistService.tailSyllable("yī xià zi"));
        assertEquals("er", RhymeWordlistService.tailSyllable("yī dīng diǎn er"));
        assertEquals("mǎ", RhymeWordlistService.tailSyllable("mǎ"));
        assertEquals("xià", RhymeWordlistService.tailSyllable("  yī   xià  "));
        assertEquals("zi", RhymeWordlistService.tailSyllable("yī　xià　zi"));
        assertNull(RhymeWordlistService.tailSyllable(null));
        assertNull(RhymeWordlistService.tailSyllable("   "));
    }
}
