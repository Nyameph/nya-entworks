package io.github.Nyameph.nyaentworks.song.fill.corpus;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 语料歌词文件选择（填词助手设计 §5.1）：.lrc 优先于 .srt、.txt 兜底；.ass 不收；无可用跳过。 */
public class CorpusLyricPickTest {

    @Test
    public void pick_prefersLrcOverSrtOverTxt() {
        assertEquals(List.of("歌.lrc"),
                CorpusService.pickLyricFiles(List.of("歌.lrc", "歌.srt", "歌.txt")));
        assertEquals(List.of("歌.srt"),
                CorpusService.pickLyricFiles(List.of("歌.srt", "歌.txt")));
        assertEquals(List.of("歌.txt"),
                CorpusService.pickLyricFiles(List.of("歌.txt")));
    }

    /** 同版本多文件同现时只取一个，但不同版本各取一个。 */
    @Test
    public void pick_onePerVariant() {
        assertEquals(List.of("歌B.txt", "歌A.lrc"), // 保持传入顺序（LinkedHashMap 首现序）
                CorpusService.pickLyricFiles(List.of("歌B.txt", "歌A.lrc", "歌A.srt")));
    }

    /** 只有 txt 的组也要收（占实测 557 组的 17%，初稿丢掉它们是错的）。 */
    @Test
    public void pick_txtOnlyGroupStillCollected() {
        assertEquals(List.of("只有文本版.txt"),
                CorpusService.pickLyricFiles(List.of("只有文本版.txt")));
    }

    /** .ass 不收（LyricParser 不解析，既有决定）；空列表给空结果。 */
    @Test
    public void pick_assSkipped() {
        assertTrue(CorpusService.pickLyricFiles(List.of("歌.ass", "歌.mp4")).isEmpty());
        assertTrue(CorpusService.pickLyricFiles(List.of()).isEmpty());
    }

    /** 大小写扩展名无所谓。 */
    @Test
    public void pick_extensionCaseInsensitive() {
        assertEquals(List.of("歌.LRC"), CorpusService.pickLyricFiles(List.of("歌.LRC", "歌.TXT")));
    }
}
