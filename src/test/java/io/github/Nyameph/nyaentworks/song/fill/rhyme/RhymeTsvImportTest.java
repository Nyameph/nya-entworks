package io.github.Nyameph.nyaentworks.song.fill.rhyme;

import org.junit.jupiter.api.Test;
import io.github.Nyameph.nyaentworks.song.entity.RhymeEntry;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 押韵词表 TSV 解析（填词助手设计 §4.4）。纯函数，fixture 硬编码；
 * 结构照实测（GB18030、转置布局、行头带空格与缩写、`、` 分隔）。
 */
public class RhymeTsvImportTest {

    private static final String TSV = String.join("\n",
            "韵母\t名词-人\t名词-物\t动作\t形容词",
            "a\t爸、妈妈\t木瓜、喇叭\t插、挣扎\t大",
            "o/uo\t我\t被窝\t戳、堕落\t活泼",
            "ang\t船长、长江\t广场\t唱\t",
            "i\t雨\t楼梯\t踢\t稀奇",
            "ang\tax3\t\t唱x\t",
            "");

    /** GB18030 文件字节 → 解码（真实词表就是这条路；UTF-8 会直接失败）。 */
    @Test
    public void decode_gb18030ThenUtf8() {
        byte[] gbk = TSV.getBytes(Charset.forName("GB18030"));
        assertEquals(TSV, RhymeTsv.decode(gbk));
        byte[] utf8 = TSV.getBytes(StandardCharsets.UTF_8);
        assertEquals(TSV, RhymeTsv.decode(utf8));
    }

    /** 解析主路径：行头归一（trim / 缩写 / v 系）、词尾字消歧、单字 CHAR / 多字 WORD、tier=3。 */
    @Test
    public void parse_basicRows() {
        RhymeTsv.Parsed parsed = RhymeTsv.parse(TSV);
        List<RhymeEntry> entries = parsed.entries();
        // 「我」的词尾字是我：wǒ → body o → 干净行（o/uo 两段各一条，同 uk 会在入库时去重）
        RhymeEntry wo = entries.stream().filter(e -> e.getText().equals("我")).findFirst().orElseThrow();
        assertEquals("o", wo.getRhymeBody());
        assertEquals("wǒ", wo.getPinyin());
        assertEquals("CHAR", wo.getEntryType()); // 单字 —— 与 RhymeService / RhymeWordlistService 同口径
        assertEquals(RhymeService.SOURCE_XLSX, wo.getSource());
        assertEquals(3, wo.getTier());
        assertNull(wo.getNote());
        // 「长江」在 ang 列：江(jiāng) 韵身 ang 命中，但 finals 存**实际读音**的 iang
        // ——附录 B 第 11 条：直接存列头会让「按韵母查」查不到它
        RhymeEntry cj = entries.stream().filter(e -> e.getText().equals("长江")).findFirst().orElseThrow();
        assertEquals("WORD", cj.getEntryType()); // 多字
        assertEquals("iang", cj.getFinals());
        assertEquals("ang", cj.getRhymeBody());
        assertEquals("十六唐", cj.getYun18());
        assertNull(cj.getNote());
        // 行头带前后空格（ iu / ie / er 这类实测写法）必须被 trim
        String spaced = "韵母\t名词-物\n iu \t气球\n";
        RhymeTsv.Parsed p2 = RhymeTsv.parse(spaced);
        assertEquals("气球", p2.entries().get(0).getText());
        assertEquals("ou", p2.entries().get(0).getRhymeBody()); // 行头 iu 归一成 iou，韵身 ou（十二侯）
    }

    /** 尾字读音与行头韵不符：仍按行头落库 + note 标出（用户的表比算法权威，但不静默）。 */
    @Test
    public void parse_mismatchedTailGetsNote() {
        String tsv = "韵母\t名词-物\ni\t雨\n";
        RhymeTsv.Parsed parsed = RhymeTsv.parse(tsv);
        assertEquals(1, parsed.entries().size());
        RhymeEntry e = parsed.entries().get(0);
        assertEquals("雨", e.getText());
        assertEquals("i", e.getFinals()); // 行头归一后的韵母
        assertEquals("i", e.getRhymeBody());
        assertNotNull(e.getNote());
        assertTrue(e.getNote().contains("不符"), "note 应指出不符：" + e.getNote());
        assertTrue(e.getNote().contains("ǔ"), "note 应带实际读音：" + e.getNote());
    }

    /** 尾字不是汉字（英文 / 数字 / 标点结尾，含实测的 x、\ 笔误）：丢弃 + failed 明细。 */
    @Test
    public void parse_nonHanziTailDroppedWithDetail() {
        RhymeTsv.Parsed parsed = RhymeTsv.parse(TSV);
        assertEquals(2, parsed.failed().size(), "ax3 与 唱x 都该被丢");
        assertEquals("ax3", parsed.failed().get(0).text());
        assertEquals("尾字不是汉字", parsed.failed().get(0).reason());
        assertEquals(6, parsed.failed().get(0).row()); // 行号从 1 数（含表头行）
        assertTrue(parsed.total() > parsed.failed().size());
    }

    /** 行头认不出：整份导入失败并指明行号 —— 静默跳过会让人以为导入成功了。 */
    @Test
    public void parse_unknownHeaderFailsLoudly() {
        String tsv = "韵母\t名词-物\na\t爸\nxx\t球\n";
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> RhymeTsv.parse(tsv));
        assertTrue(ex.getMessage().contains("第 3 行"), ex.getMessage());
        assertTrue(ex.getMessage().contains("xx"), ex.getMessage());
    }

    /** o/uo 一格两个韵母：同一个词落两条（finals 不同、韵身相同）。 */
    @Test
    public void parse_multiFinalHeaderLandsMultipleRows() {
        RhymeTsv.Parsed parsed = RhymeTsv.parse(TSV);
        List<RhymeEntry> wo = parsed.entries().stream()
                .filter(e -> e.getText().equals("我")).toList();
        assertEquals(2, wo.size());
        assertEquals("o", wo.get(0).getRhymeBody());
        assertEquals("o", wo.get(1).getRhymeBody()); // 韵身相同
    }

    /** 舌尖元音行头 -i（实测词表第 5 行）：parse 吃不下连字符，必须按五支直取。 */
    @Test
    public void parse_apicalHeaderRow() {
        RhymeTsv.Parsed parsed = RhymeTsv.parse("韵母\t名词-物\n-i\t知识\n");
        assertEquals(1, parsed.entries().size());
        RhymeEntry e = parsed.entries().get(0);
        // 识(shí) 韵身 -i 命中：干净落库
        assertEquals("-i", e.getFinals());
        assertEquals("-i", e.getRhymeBody());
        assertEquals("五支", e.getYun18());
        assertNull(e.getNote());
    }

    /** 单元格切词：、 为主，容忍 , ， ; ； 与空白。 */
    @Test
    public void splitWords_tolerantSeparators() {
        assertEquals(List.of("爸", "妈", "伯", "婆"),
                RhymeTsv.splitWords("爸、妈，伯; 婆；"));
        assertEquals(List.of(), RhymeTsv.splitWords("  "));
    }

/** 列头 = 词性，原样存（「动作」不映射成「动词」），进 wordClass。 */
    @Test
    public void parse_wordClassFromColumnHead() {
        RhymeTsv.Parsed parsed = RhymeTsv.parse(TSV);
        RhymeEntry cha = parsed.entries().stream()
                .filter(e -> e.getText().equals("插")).findFirst().orElseThrow();
        assertEquals("动作", cha.getWordClass());
        RhymeEntry da = parsed.entries().stream()
                .filter(e -> e.getText().equals("大")).findFirst().orElseThrow();
        assertEquals("形容词", da.getWordClass());
    }
}
