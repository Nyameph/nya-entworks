package io.github.Nyameph.nyaentworks.song.fill.rhyme;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 句尾词提取的**首字 / 末字**口径（填词助手设计 §4.5 第 1 条「边界字不是标点、不是虚词」）：
 * 首字看 {@link RhymeService#STOP_HEAD}（的地得了着过 / 语气词 / 我你他她它们），末字看
 * {@link RhymeService#STOP_TAIL}（原有的一串）。
 *
 * <p>与 corpus 包的 {@code RhymeCorpusExtractTest} 分工：那边管整窗策略（窗口长度 / 标点 /
 * 英文边界 / 末字），这里只钉「首字是虚词 → 这个长度的候选作废」以及「两张表没有互相串用」。
 * 纯函数，可离线跑。
 */
public class RhymeTailWordStopHeadTest {

    /** 首字是虚词 → 候选作废，逐长度往上试也都被首字挡掉 → null。 */
    @Test
    public void extract_stopCharHeaded() {
        // 2 字窗「的梦」首字是助词；3 字窗「我的梦」首字是代词 —— 都不是词条
        assertNull(RhymeService.extractTailWord("我的梦"));
    }

    /** 作废只看窗的两头：虚词落在窗中间不算，「甜的梦」的 3 字窗是成立的。 */
    @Test
    public void extract_stopCharInsideWindow() {
        assertEquals("甜的梦", RhymeService.extractTailWord("甜的梦"));
    }

    /**
     * 首字表里**没有**「不 都 还 也 是 在 有 …」：这些字可以合法给词打头，套到首字上会把
     * 真词误杀、逼着窗口往更长的字窗爬、爬出「正都不同」这种更长的垃圾。
     */
    @Test
    public void extract_conjunctionHeadedWordSurvives() {
        assertEquals("不同", RhymeService.extractTailWord("反正都不同"));
    }

    /** 末字仍走原来那一串：「下的」照旧不是词条（末字口径不许回退）。 */
    @Test
    public void extract_tailStopwordStillRejected() {
        assertNull(RhymeService.extractTailWord("下的"));
    }
}
