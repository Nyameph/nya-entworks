package io.github.Nyameph.nyaentworks.song.fill.rhyme;

import org.junit.jupiter.api.Test;
import io.github.Nyameph.nyaentworks.song.entity.RhymeEntry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量粘贴的纯函数那一半（{@link RhymeService#parseBatchLines}）：多行文本 → 词条 + 失败明细。
 * 不查库、不写库，可离线跑。
 *
 * <p>钉的口径：一行为一条（成功一条词条、失败一条明细，绝不静默丢）、行号按原文数、
 * 手填拼音取最后一个音节（{@code pinyin} 列存的一直是尾字读音）、没手填就按尾字反推。
 */
public class RhymeBatchParseTest {

    /** 每行只有字词：按 1 个汉字 = CHAR / 其余 WORD，韵走尾字反推。 */
    @Test
    public void parse_wordOnlyLines() {
        RhymeService.BatchParsed parsed =
                RhymeService.parseBatchLines("碎星\n知\n", "名词");
        assertEquals(2, parsed.total());
        assertTrue(parsed.failed().isEmpty());

        RhymeEntry word = parsed.entries().get(0);
        assertEquals("WORD", word.getEntryType());
        assertEquals("碎星", word.getText());
        assertEquals("xīng", word.getPinyin()); // 尾字读音
        assertEquals("ing", word.getFinals());
        assertEquals("eng", word.getRhymeBody());
        assertEquals("十七庚", word.getYun18());
        assertEquals(RhymeService.SOURCE_MANUAL, word.getSource());
        assertEquals("名词", word.getWordClass());
        assertEquals(0, word.getFreq());

        RhymeEntry single = parsed.entries().get(1);
        assertEquals("CHAR", single.getEntryType());
        assertEquals("知", single.getText());
        assertEquals("-i", single.getRhymeBody()); // 舌尖元音走五支
    }

    /** 半角空格 / 全角空格 / tab 三种分隔都吃；手填拼音校验通过就按它落韵。 */
    @Test
    public void parse_acceptsAllThreeSeparators() {
        RhymeService.BatchParsed parsed = RhymeService.parseBatchLines(
                "碎星 suì\n碎星　suì\n碎星\tsuì\n", null);
        assertEquals(3, parsed.total());
        assertTrue(parsed.failed().isEmpty());
        for (RhymeEntry e : parsed.entries()) {
            assertEquals("碎星", e.getText());
            assertEquals("suì", e.getPinyin());
            assertEquals("ui", e.getFinals()); // parse 给的是缩写（uei 只从零声母拼写走到）
            assertEquals("ei", e.getRhymeBody());
            assertNull(e.getWordClass());
        }
    }

    /** 整词拼音（{@code suixing}）：取最后一个音节 —— pinyin 列存的是尾字读音。 */
    @Test
    public void parse_multiSyllablePinyinTakesLast() {
        RhymeService.BatchParsed parsed = RhymeService.parseBatchLines("碎星 suixing", null);
        assertEquals(1, parsed.entries().size());
        RhymeEntry e = parsed.entries().get(0);
        assertEquals("碎星", e.getText());
        assertEquals("xing", e.getPinyin());
        assertEquals("ing", e.getFinals());
        assertEquals("eng", e.getRhymeBody());
    }

    /** 拼音本身用空格分开（{@code sui xing}）：行尾那串都算拼音段，取最后一个音节。 */
    @Test
    public void parse_spacedPinyinTakesLastToken() {
        RhymeService.BatchParsed parsed = RhymeService.parseBatchLines("碎星 sui xing", null);
        assertEquals(1, parsed.entries().size());
        assertEquals("碎星", parsed.entries().get(0).getText());
        assertEquals("xing", parsed.entries().get(0).getPinyin());
    }

    /** 手填拼音覆盖尾字默认读音：行 → CHAR + hang（十六唐），不是默认的 xíng。 */
    @Test
    public void parse_typedPinyinOverridesTailReading() {
        RhymeService.BatchParsed parsed = RhymeService.parseBatchLines("行 hang", null);
        RhymeEntry e = parsed.entries().get(0);
        assertEquals("CHAR", e.getEntryType());
        assertEquals("hang", e.getPinyin());
        assertEquals("ang", e.getFinals());
        assertEquals("十六唐", e.getYun18());
    }

    /** 无手填拼音时按尾字反推；有手填但拆不出 → failed（绝不静默丢）。 */
    @Test
    public void parse_failedLinesReportedWithOriginalLineNumbers() {
        RhymeService.BatchParsed parsed =
                RhymeService.parseBatchLines("碎星\nabc\n\n碎星 xyz\n", null);
        assertEquals(3, parsed.total()); // 空行不算
        assertEquals(1, parsed.entries().size());
        assertEquals(2, parsed.failed().size());

        assertEquals(2, parsed.failed().get(0).line());
        assertEquals("abc", parsed.failed().get(0).text());
        assertEquals("尾字不是汉字", parsed.failed().get(0).reason());

        assertEquals(4, parsed.failed().get(1).line());
        assertEquals("碎星 xyz", parsed.failed().get(1).text());
        assertTrue(parsed.failed().get(1).reason().contains("xyz"), parsed.failed().get(1).reason());
    }

    /** 整行都是拼音（没有字词在前）：当字词落，尾字不是汉字 → failed（不当成「拼音」猜）。 */
    @Test
    public void parse_allPinyinLineFails() {
        RhymeService.BatchParsed parsed = RhymeService.parseBatchLines("suixing", null);
        assertTrue(parsed.entries().isEmpty());
        assertEquals(1, parsed.failed().size());
        assertEquals("尾字不是汉字", parsed.failed().get(0).reason());
    }

    /** 空文本 / null / 全空白：0 行 0 失败（不是错误）。 */
    @Test
    public void parse_blankInput() {
        for (String text : new String[] {null, "", "   ", "\n\n", " \t　\n"}) {
            RhymeService.BatchParsed parsed = RhymeService.parseBatchLines(text, "名词");
            assertEquals(0, parsed.total(), String.valueOf(text));
            assertTrue(parsed.entries().isEmpty());
            assertTrue(parsed.failed().isEmpty());
        }
    }

    /** 行尾带 \r（Windows 粘贴）：按 \r\n 拆，不留脏字符。 */
    @Test
    public void parse_crlfLines() {
        RhymeService.BatchParsed parsed = RhymeService.parseBatchLines("碎星\r\n知\r\n", null);
        assertEquals(2, parsed.total());
        assertEquals(List.of("碎星", "知"),
                parsed.entries().stream().map(RhymeEntry::getText).toList());
    }

    // ==================== 拼音是不是人打上去的（决定「已经有」怎么认） ====================

    /** 手填拼音 / 没手填，两种行要分得出来 —— 入库时一个按 (text,pinyin) 认、一个按 text 认。 */
    @Test
    public void parse_flagsTypedPinyin() {
        RhymeService.BatchParsed parsed = RhymeService.parseBatchLines("碎星\n碎星 suìxīng\n", null);
        assertEquals(2, parsed.lines().size());
        assertFalse(parsed.lines().get(0).typedPinyin(), "只给字词：拼音是按尾字反推的");
        assertTrue(parsed.lines().get(1).typedPinyin(), "行尾那串是手打上去的");
        assertEquals("碎星", parsed.lines().get(1).entry().getText());
        assertEquals("xīng", parsed.lines().get(1).entry().getPinyin()); // 手填原样落库（此处是带调写法）
    }

    // ==================== 「已经有这个词」认哪些行（RhymeService.hitRows） ====================

    private static RhymeEntry existing(long id, String text, String pinyin, String source) {
        RhymeEntry e = new RhymeEntry();
        e.setId(id);
        e.setText(text);
        e.setPinyin(pinyin);
        e.setSource(source);
        return e;
    }

    /** 手填了拼音：只认 (text, pinyin) 精确相同的那条 —— 别的读音是另一条词，不是「已经有了」。 */
    @Test
    public void hitRows_typedPinyinMatchesExactReadingOnly() {
        List<RhymeEntry> same = List.of(
                existing(1, "长", "cháng", "XLSX"),
                existing(2, "长", "zhǎng", "OPEN"));
        assertEquals(List.of(1L), ids(RhymeService.hitRows("cháng", true, same)));
        assertEquals(List.of(2L), ids(RhymeService.hitRows("zhǎng", true, same)));
        assertTrue(RhymeService.hitRows("chāng", true, same).isEmpty(), "库里没这个读音 → 走新增");
    }

    /** 只给字词：同 text 的全部算命中（用户没指定读音 = 这个词我认领了，多音各行都归人工）。 */
    @Test
    public void hitRows_untypedTakesEveryReading() {
        List<RhymeEntry> same = List.of(
                existing(1, "长", "cháng", "XLSX"),
                existing(2, "长", "zhǎng", "OPEN"));
        assertEquals(List.of(1L, 2L), ids(RhymeService.hitRows("zhǎng", false, same)));
    }

    /** 库里没有这个 text：两种认法都是空，走 INSERT IGNORE。 */
    @Test
    public void hitRows_emptyWhenTextAbsent() {
        assertTrue(RhymeService.hitRows("xīng", true, List.of()).isEmpty());
        assertTrue(RhymeService.hitRows("xīng", false, List.of()).isEmpty());
    }

    private static List<Long> ids(List<RhymeEntry> rows) {
        return rows.stream().map(RhymeEntry::getId).toList();
    }
}
