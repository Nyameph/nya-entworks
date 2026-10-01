package io.github.Nyameph.nyaentworks.song.fill.rhyme;

import org.junit.jupiter.api.Test;
import io.github.Nyameph.nyaentworks.song.entity.RhymeEntry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 韵脚词典去重的算法（填词助手设计 §4.4）。纯逻辑，不碰库、不依赖外部环境。
 *
 * <p>覆盖的正是真实数据里那几类：精确重复（151 组）、轻声对（85 组）、多带调（吗 / 和 /
 * 啊 / 啰）、多字词的语料读音让位（59 组，如 一场 chǎng/cháng）、真多音（单字与「两行都是
 * 词表来源」的多字词，一行都不许动），以及字段冲突（tier / word_class / note / 韵部）的
 * 取舍口径。fixture 里的拼音都是真的，算法按它们现算韵部。
 */
public class RhymeDedupPlanTest {

    /** 建一行：韵部一律不给 —— 去重时会按读音重算，与库里当下的值无关 */
    private static RhymeEntry row(long id, String text, String pinyin, String source, String entryType) {
        RhymeEntry e = new RhymeEntry();
        e.setId(id);
        e.setText(text);
        e.setPinyin(pinyin);
        e.setSource(source);
        e.setEntryType(entryType);
        e.setTier(0);
        e.setFreq(0);
        return e;
    }

    private static RhymeEntry updateOf(RhymeDedupService.DedupPlan plan, long id) {
        return plan.updates().stream().filter(u -> u.getId() == id).findFirst().orElseThrow();
    }

    private static boolean touches(RhymeDedupService.DedupPlan plan, long id) {
        return plan.updates().stream().anyMatch(u -> u.getId() == id);
    }

    // ==================== 第 1 步：同音完全重复 ====================

    /** 精确重复：留来源优先级高的 XLSX、删 MODERN；tier 取组内最小非 0；类型按字数重算 */
    @Test
    public void exactDuplicateKeepsHigherSourceAndFixesEntryType() {
        RhymeEntry modern = row(1, "丝", "sī", "MODERN", "CHAR");
        modern.setTier(1);
        RhymeEntry xlsx = row(2, "丝", "sī", "XLSX", "WORD"); // 根因：导入时被硬编码成 WORD
        xlsx.setTier(3);
        xlsx.setWordClass("名词");

        RhymeDedupService.DedupPlan plan = RhymeDedupService.plan(List.of(modern, xlsx));

        assertEquals(List.of(1L), plan.deleteIds());
        RhymeEntry upd = updateOf(plan, 2);
        assertEquals("CHAR", upd.getEntryType()); // 单字 → CHAR（存量 152 行的修法同此）
        assertEquals(1, upd.getTier());           // 「tier 取最常用」= 组内非 0 最小值
        assertNull(upd.getWordClass());           // 胜出行本来就有词性，不用回填
        assertNull(upd.getFreq());                // freq 取胜出行（0），不跨行回填
        assertEquals(0, plan.failed());
    }

    /** 五档来源优先级：MANUAL > XLSX > CORPUS > MODERN > OPEN，同音的只留最高那一档 */
    @Test
    public void sourcePriorityOrder() {
        RhymeDedupService.DedupPlan plan = RhymeDedupService.plan(List.of(
                row(1, "丝", "sī", "MODERN", "CHAR"),
                row(2, "丝", "sī", "OPEN", "CHAR"),
                row(3, "丝", "sī", "CORPUS", "WORD"),
                row(4, "丝", "sī", "XLSX", "WORD"),
                row(5, "丝", "sī", "MANUAL", "CHAR")));

        assertEquals(List.of(1L, 2L, 3L, 4L), plan.deleteIds());
        assertTrue(touches(plan, 5L), "留下的是 MANUAL 那行");
    }

    /** 为空的字段从组内其他行补齐：胜出行的 word_class / note 为空就回填 */
    @Test
    public void blankFieldsBackfilledFromMergedRows() {
        RhymeEntry manual = row(1, "丝", "sī", "MANUAL", "CHAR"); // MANUAL 优先级最高，但两列都空
        RhymeEntry xlsx = row(2, "丝", "sī", "XLSX", "WORD");
        xlsx.setWordClass("名词");
        xlsx.setNote("导入时与尾字读音不符");

        RhymeDedupService.DedupPlan plan = RhymeDedupService.plan(List.of(manual, xlsx));

        assertEquals(List.of(2L), plan.deleteIds());
        RhymeEntry upd = updateOf(plan, 1);
        assertEquals("名词", upd.getWordClass());
        assertEquals("导入时与尾字读音不符", upd.getNote());
    }

    /** 韵部由读音重算：翁那组的 XLSX 行写着错的 ong / 十八东，重算覆盖成 ueng / 十七庚 */
    @Test
    public void readingRebuildsFinalsBodyAndYun18() {
        RhymeEntry xlsx = row(1, "翁", "wēng", "XLSX", "WORD");
        xlsx.setFinals("ong");
        xlsx.setRhymeBody("ong");
        xlsx.setYun18("十八东");
        RhymeEntry modern = row(2, "翁", "wēng", "MODERN", "CHAR");
        modern.setFinals("ueng");
        modern.setRhymeBody("eng");
        modern.setYun18("十七庚");

        RhymeDedupService.DedupPlan plan = RhymeDedupService.plan(List.of(xlsx, modern));

        assertEquals(List.of(2L), plan.deleteIds());
        RhymeEntry upd = updateOf(plan, 1);
        assertEquals("ueng", upd.getFinals());
        assertEquals("eng", upd.getRhymeBody());
        assertEquals("十七庚", upd.getYun18());
    }

    // ==================== 第 2 步：轻声对 ====================

    /** 轻声对·多字词：CORPUS 的读音是拿尾字当单字猜的 → 淘汰 CORPUS，留词表的真实读音 */
    @Test
    public void lightTonePairKeepsWordlistReadingForMultiChar() {
        RhymeEntry corpus = row(1, "爷爷", "yé", "CORPUS", "WORD");
        corpus.setFreq(2);
        RhymeEntry open = row(2, "爷爷", "ye", "OPEN", "WORD");
        open.setFreq(2077);

        RhymeDedupService.DedupPlan plan = RhymeDedupService.plan(List.of(corpus, open));

        assertEquals(List.of(1L), plan.deleteIds());
        assertTrue(!touches(plan, 1L), "被删的行不该再被更新");
    }

    /** 轻声对·单字：删轻声行、留带调（单字的读音可信，不走多字词那条规则） */
    @Test
    public void lightTonePairDropsLightSyllableForSingleChar() {
        RhymeDedupService.DedupPlan plan = RhymeDedupService.plan(List.of(
                row(1, "们", "mén", "MODERN", "CHAR"),
                row(2, "们", "men", "MODERN", "CHAR")));

        assertEquals(List.of(2L), plan.deleteIds());
    }

    /** 一组里有多个带调读音（吗 má/mǎ/ma）：删轻声、两个带调都留，且不跨读音回填 */
    @Test
    public void multipleTonedReadingsAllSurvive() {
        RhymeDedupService.DedupPlan plan = RhymeDedupService.plan(List.of(
                row(1, "吗", "má", "MODERN", "CHAR"),
                row(2, "吗", "mǎ", "MODERN", "CHAR"),
                row(3, "吗", "ma", "MODERN", "CHAR")));

        assertEquals(List.of(3L), plan.deleteIds());
        assertTrue(plan.updates().isEmpty(), "两个带调读音各是各的，谁都不用改");
    }

    /** 全轻声（得 dé/děi/de 里只有 de 是轻声）：删轻声那一行，带调的两行都留 */
    @Test
    public void lightOnlyRowIsDropped() {
        RhymeDedupService.DedupPlan plan = RhymeDedupService.plan(List.of(
                row(1, "得", "dé", "MODERN", "CHAR"),
                row(2, "得", "děi", "MODERN", "CHAR"),
                row(3, "得", "de", "MODERN", "CHAR")));

        assertEquals(List.of(3L), plan.deleteIds());
    }

    // ==================== 真多音：一行都不动 ====================

    /** 真多音·异调（上 shǎng/shàng）：同簇但都是带调的，属两个读音，不合并 */
    @Test
    public void truePolyphoneSameClusterUntouched() {
        RhymeDedupService.DedupPlan plan = RhymeDedupService.plan(List.of(
                row(1, "上", "shǎng", "MODERN", "CHAR"),
                row(2, "上", "shàng", "MODERN", "CHAR")));

        assertTrue(plan.deleteIds().isEmpty());
        assertTrue(plan.updates().isEmpty());
    }

    /**
     * 多字词的语料读音让位（一场 chǎng/CORPUS + cháng/OPEN）：<b>两个都带调、没有轻声行</b>，
     * 原规则按「真多音」放过，但 CORPUS 那个读音是拿尾字「场」的单字音猜的 —— 同音的话第 1 步
     * 早就合并了，能剩下就说明 CORPUS 必错。所以删 CORPUS、留词表的 cháng（实测这类 59 组）。
     */
    @Test
    public void corpusReadingYieldsToWordlistForMultiChar() {
        RhymeDedupService.DedupPlan plan = RhymeDedupService.plan(List.of(
                row(1, "一场", "chǎng", "CORPUS", "WORD"),
                row(2, "一场", "cháng", "OPEN", "WORD")));

        assertEquals(List.of(1L), plan.deleteIds());
        assertTrue(!touches(plan, 1L), "被删的行不该再被更新");
        assertNull(updateOf(plan, 2L).getPinyin(), "pinyin 从不被改写：它就是分簇键");
    }

    /** 多字词、两行都是词表来源（真多音，如词表同时收了「重重」chóng/zhòng）：一行都不动 */
    @Test
    public void truePolyphoneMultiCharWithoutCorpusUntouched() {
        RhymeDedupService.DedupPlan plan = RhymeDedupService.plan(List.of(
                row(1, "重重", "chóng", "OPEN", "WORD"),
                row(2, "重重", "zhòng", "OPEN", "WORD")));

        assertTrue(plan.deleteIds().isEmpty());
        assertTrue(plan.updates().isEmpty());
    }

    /** 多字词但只有 CORPUS 行（没有词表行可参照）：不许动，否则把这个词直接抹掉 */
    @Test
    public void multiCharWithOnlyCorpusRowsUntouched() {
        RhymeDedupService.DedupPlan plan = RhymeDedupService.plan(List.of(
                row(1, "阿卡", "kǎ", "CORPUS", "WORD"),
                row(2, "阿卡", "kā", "CORPUS", "WORD")));

        assertTrue(plan.deleteIds().isEmpty());
        assertTrue(plan.updates().isEmpty());
    }

    /**
     * 真多音里混了一个重复对（实测有 9 组这种）：只并「拼音逐字相同」的那两行，
     * <b>另一个读音一行都不许碰</b> —— 否则 háng 的词性会被回填给 shàng。
     */
    @Test
    public void duplicateInsidePolyphoneDoesNotCrossReadings() {
        RhymeEntry xlsx = row(1, "上", "shǎng", "XLSX", "WORD");
        xlsx.setWordClass("动词");
        RhymeDedupService.DedupPlan plan = RhymeDedupService.plan(List.of(
                xlsx,
                row(2, "上", "shǎng", "MODERN", "CHAR"),
                row(3, "上", "shàng", "MODERN", "CHAR")));

        assertEquals(List.of(2L), plan.deleteIds());
        assertEquals("CHAR", updateOf(plan, 1).getEntryType());
        assertTrue(!touches(plan, 3L), "另一个读音不许被回填");
    }

    /** 不同韵（绿 lǜ / lù）：去调形式不同（保留 ü），分属两簇，永远不碰 */
    @Test
    public void differentRhymeNeverMerged() {
        RhymeDedupService.DedupPlan plan = RhymeDedupService.plan(List.of(
                row(1, "绿", "lǜ", "MODERN", "CHAR"),
                row(2, "绿", "lù", "MODERN", "CHAR")));

        assertTrue(plan.deleteIds().isEmpty());
    }

    // ==================== 拆不出的行：不猜、不动 ====================

    /** 读音拆不出韵部的行计进 failed 并跳过（不猜、不删，与词表导入同口径） */
    @Test
    public void unparsableReadingSkipped() {
        RhymeDedupService.DedupPlan plan = RhymeDedupService.plan(List.of(
                row(1, "哟", "yo", "MODERN", "CHAR"),
                row(2, "哟", "yo", "XLSX", "WORD")));

        assertEquals(2, plan.failed());
        assertTrue(plan.deleteIds().isEmpty());
        assertTrue(plan.updates().isEmpty());
    }
}
