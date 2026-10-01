package io.github.Nyameph.nyaentworks.song.fill.corpus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 采集入库的行长口径（填词助手设计 §5.2）：超长行（整页字幕之类）不入库。
 *
 * <p>界值必须钉住：{@code lyric_corpus_line.text} 是 {@code VARCHAR(500)}，把 480 写成 500
 * 就会在插入时炸，写成 400 就会白丢真歌词。丢的行数由 {@code CollectCount.skippedLong}
 * 报进任务结果（§15 M2 偏差清单第 8 条）。
 */
public class CorpusLineSkipTest {

    @Test
    public void isOverlong_boundaryIsInclusiveUpTo480() {
        assertEquals(480, CorpusService.MAX_LINE_LENGTH);
        assertFalse(CorpusService.isOverlong("啊".repeat(480))); // 480 字仍可入库
        assertTrue(CorpusService.isOverlong("啊".repeat(481)));  // 481 字丢掉并计数
    }

    @Test
    public void isOverlong_nullIsNotLong() {
        assertFalse(CorpusService.isOverlong(null));
        assertFalse(CorpusService.isOverlong(""));
    }
}
