package io.github.Nyameph.nyaentworks.song.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link SongOriginalNaming} 的单测：八类名字<b>逐字</b>钉住。
 *
 * <p>纯单测，不连库、不碰 {@code F:\}。存在的理由：这套形状原先只活在改名脚本里，
 * 抽出来时少一个空格、括号写成半角，页面就会把文件改成另一个名字 —— 而库里的字段
 * 跟着变、盘上的旧名文件成了孤儿，症状要等到下次扫描才看得见。
 */
class SongOriginalNamingTest {

    @Test
    void build_allEightTypes_byteForByte() {
        assertEquals("甲 - 乙", SongOriginalNaming.build("original", "乙", "甲", null));
        assertEquals("甲 - 乙", SongOriginalNaming.build("lyric", "乙", "甲", null));
        assertEquals("[demo] 乙", SongOriginalNaming.build("demo", "乙", "甲", null));
        assertEquals("[demo] 乙", SongOriginalNaming.build("demoLrc", "乙", "甲", null));
        assertEquals("[伴奏] 乙", SongOriginalNaming.build("accompaniment", "乙", "甲", null));
        assertEquals("[人声] 乙", SongOriginalNaming.build("vocals", "乙", "甲", null));
        assertEquals("[BPM=120] 乙", SongOriginalNaming.build("mid", "乙", "甲", "120"));
        assertEquals("[工程] 乙", SongOriginalNaming.build("svp", "乙", "甲", null));
    }

    @Test
    void build_blankArtist_originalAndLyricHaveNoDashPrefix() {
        assertEquals("乙", SongOriginalNaming.build("original", "乙", null, null));
        assertEquals("乙", SongOriginalNaming.build("lyric", "乙", "", null));
        assertEquals("乙", SongOriginalNaming.build("original", "乙", "   ", null));
        // 其余六类本来就不带作者，不受影响
        assertEquals("[伴奏] 乙", SongOriginalNaming.build("accompaniment", "乙", "", null));
        assertEquals("[demo] 乙", SongOriginalNaming.build("demo", "乙", "", null));
        assertEquals("[BPM=120] 乙", SongOriginalNaming.build("mid", "乙", "", "120"));
    }

    @Test
    void build_rawNameKeptVerbatim() {
        // 全角括号 / 空格 / # 一律原样保留：安全化是下载那条路（SongResourceSearchService）的事
        assertEquals("甲 - 乙（原曲）#2", SongOriginalNaming.build("original", "乙（原曲）#2", "甲", null));
        assertEquals("[伴奏] 36.5°C", SongOriginalNaming.build("accompaniment", "36.5°C", "甲", null));
        assertEquals("甲 - 乙 的 原曲", SongOriginalNaming.build("lyric", "乙 的 原曲", "甲", null));
    }

    @Test
    void build_midWithoutBpm_writesFullWidthQuestionMark() {
        assertEquals("[BPM=？] 乙", SongOriginalNaming.build("mid", "乙", "甲", null));
        assertEquals("[BPM=？] 乙", SongOriginalNaming.build("mid", "乙", "甲", ""));
        assertEquals("[BPM=？] 乙", SongOriginalNaming.build("mid", "乙", "甲", "  "));
        // 问号是字面量占位，不是「没有前缀」
        assertEquals("？", SongOriginalNaming.bpmOrPlaceholder(null));
    }

    @Test
    void bpmText_sameFormattingAsTheRenameScript() {
        assertEquals("120", SongOriginalNaming.bpmText(new BigDecimal("120.0000")));
        assertEquals("120.5", SongOriginalNaming.bpmText(new BigDecimal("120.5000")));
        assertEquals("0.5", SongOriginalNaming.bpmText(new BigDecimal("0.50")));
        assertNull(SongOriginalNaming.bpmText(null));
        // 与脚本同口径：先 stripTrailingZeros 再 toPlainString，不能出现 1.2E+2
        String text = SongOriginalNaming.bpmText(new BigDecimal("120.0000"));
        assertFalse(text.contains("E"));
        // 走一遍完整链路：库里读回来的 decimal(10,4) 写进 mid 主名
        assertEquals("[BPM=120.5] 乙",
                SongOriginalNaming.build("mid", "乙", "甲", SongOriginalNaming.bpmText(new BigDecimal("120.5000"))));
    }

    @Test
    void build_unknownType_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> SongOriginalNaming.build("other", "乙", "甲", null));
    }
}
