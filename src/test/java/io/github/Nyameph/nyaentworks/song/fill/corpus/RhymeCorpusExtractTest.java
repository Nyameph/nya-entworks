package io.github.Nyameph.nyaentworks.song.fill.corpus;

import org.junit.jupiter.api.Test;
import io.github.Nyameph.nyaentworks.song.fill.rhyme.RhymeService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 句尾词提取与句尾注音（填词助手设计 §4.5 / §5.2）。纯函数，可离线跑。
 */
public class RhymeCorpusExtractTest {

    /** 句尾 2~4 字窗口、优先 2 字。 */
    @Test
    public void extract_prefersTwoCharWord() {
        assertEquals("一面", RhymeService.extractTailWord("多想见你一面"));
        assertEquals("南岸", RhymeService.extractTailWord("春风又绿江南岸"));
    }

    /** 末字是停用虚词的窗口不算词：「月光下的」三档全废 → null。 */
    @Test
    public void extract_stopCharEndingRejected() {
        assertNull(RhymeService.extractTailWord("月光下的"));
        assertNull(RhymeService.extractTailWord("走了"));
        assertNull(RhymeService.extractTailWord("我等你"));
    }

    /** 窗口内不跨标点：句尾标点拦住窗口，往前取。 */
    @Test
    public void extract_windowStopsAtPunctuation() {
        assertEquals("一面", RhymeService.extractTailWord("多想见你一面。"));
        assertNull(RhymeService.extractTailWord("啊！！！"));
    }

    /** 英文 / 数字边界：窗口停在英文单元前；整句没有 2 个连续汉字 → null。 */
    @Test
    public void extract_englishAndShortTails() {
        assertEquals("天涯", RhymeService.extractTailWord("hello 天涯"));
        assertNull(RhymeService.extractTailWord("love 爱"));
        assertNull(RhymeService.extractTailWord("hello world"));
        assertNull(RhymeService.extractTailWord(""));
        assertNull(RhymeService.extractTailWord(null));
    }

    /** 句尾注音：汉字尾 → tail_char + 全读音韵部逗号连接。 */
    @Test
    public void tailOf_hanziTail() {
        CorpusService.TailRhyme t = CorpusService.tailOf("春风又绿江南岸");
        assertEquals("岸", t.tailChar());
        assertEquals("àn", t.tailPinyin());
        assertEquals("an", t.finals());
        assertEquals("十四寒", t.yun18());
    }

    /** 多音字：全部读音的韵母 / 十八韵去重逗号连接（行 xíng/háng → ang+ing 两韵）。 */
    @Test
    public void tailOf_polyphoneJoinsAllReadings() {
        CorpusService.TailRhyme t = CorpusService.tailOf("干这一行");
        assertEquals("行", t.tailChar());
        assertTrue(t.finals().contains("ang"), t.finals());
        assertTrue(t.finals().contains("ing"), t.finals());
        assertTrue(t.yun18().contains("十六唐"), t.yun18());
        assertTrue(t.yun18().contains("十七庚"), t.yun18());
    }

    /** 英文句尾 / 无汉字句：tail_char 留空、韵部列留空（不判韵、不编造）。 */
    @Test
    public void tailOf_englishAndEmpty() {
        assertNull(CorpusService.tailOf("love you more").tailChar());
        assertNull(CorpusService.tailOf("love you more").yun18());
        assertNull(CorpusService.tailOf("。。。").tailChar());
        assertNull(CorpusService.tailOf("").tailChar());
    }
}
