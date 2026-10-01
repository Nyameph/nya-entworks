package io.github.Nyameph.nyaentworks.song.fill;

import org.junit.jupiter.api.Test;
import io.github.Nyameph.nyaentworks.song.fill.LyricTemplate.FillLine;
import io.github.Nyameph.nyaentworks.song.fill.LyricTemplate.FillNote;
import io.github.Nyameph.nyaentworks.song.fill.LyricTemplate.FillSlot;
import io.github.Nyameph.nyaentworks.song.fill.LyricTemplate.FillTrack;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 四个 TEXT 列的 JSON 往返（{@code notes_json} / {@code lines_json} / {@code filled_json} /
 * {@code gaps_json}）。
 *
 * <p>这个测试的存在意义是**守住 fastjson2 对 record 的支持**：整个填词骨架是嵌套 record，
 * 一旦 fastjson2 换了行为（或换成别的 JSON 库），往返不相等会立刻红。
 */
public class LyricFillStoreTest {

    @Test
    public void roundTrip_tracks() {
        List<FillTrack> tracks = List.of(
                new FillTrack(0, "填词", List.of(
                        new FillNote(22579200000L, 352800000L, "天", SlotType.HANZI),
                        new FillNote(22932000000L, 352800000L, "-", SlotType.DASH))),
                new FillTrack(2, "填词2", List.of(
                        new FillNote(22579200000L, 352800000L, "hello", SlotType.ENGLISH))));

        List<FillTrack> back = LyricFillStore.readTracks(LyricFillStore.toJson(tracks));

        assertEquals(tracks, back);
    }

    @Test
    public void roundTrip_lines() {
        List<FillLine> lines = List.of(
                new FillLine(List.of(
                        new FillSlot(0, 0, "天", SlotType.HANZI),
                        new FillSlot(0, 1, "-", SlotType.DASH),
                        new FillSlot(2, 0, "hello", SlotType.ENGLISH)),
                        "天hello", 2, 22579200000L));

        List<FillLine> back = LyricFillStore.readLines(LyricFillStore.toJson(lines));

        assertEquals(lines, back);
    }

    /** 喉塞音标记（glottal）随 notes_json / lines_json 往返不丢；老库没有该字段时读成 false。 */
    @Test
    public void roundTrip_glottalFlag() {
        List<FillTrack> tracks = List.of(
                new FillTrack(0, "填词", List.of(
                        new FillNote(22579200000L, 352800000L, "曾", SlotType.HANZI, true))));
        assertEquals(tracks, LyricFillStore.readTracks(LyricFillStore.toJson(tracks)));

        List<FillLine> lines = List.of(
                new FillLine(List.of(new FillSlot(0, 0, "曾", SlotType.HANZI, true)),
                        "曾", 1, 22579200000L));
        assertEquals(lines, LyricFillStore.readLines(LyricFillStore.toJson(lines)));

        // 存量项目：加字段之前落库的 JSON 没有 glottal —— 读进来是 false，既有项目不受影响
        String oldLines = "[{\"slots\":[{\"trackIndex\":0,\"noteIndex\":0,\"original\":\"曾\","
                + "\"slotType\":\"HANZI\"}],\"originalText\":\"曾\",\"needCount\":1,"
                + "\"startOnset\":22579200000}]";
        List<FillLine> back = LyricFillStore.readLines(oldLines);
        assertEquals(1, back.size());
        assertFalse(back.getFirst().slots().getFirst().glottal());

        String oldTracks = "[{\"trackIndex\":0,\"trackName\":\"填词\",\"notes\":["
                + "{\"onset\":22579200000,\"duration\":352800000,\"lyrics\":\"曾\","
                + "\"slotType\":\"HANZI\"}]}]";
        assertFalse(LyricFillStore.readTracks(oldTracks).getFirst().notes()
                .getFirst().glottal());
    }

    @Test
    public void roundTrip_filled() {
        List<List<String>> filled = List.of(
                List.of("新", "词", "", ""),
                List.of(),
                List.of("hello", "", "世"));

        assertEquals(filled, LyricFillStore.readFilled(LyricFillStore.toJson(filled)));
    }

    @Test
    public void readFilled_toleratesOldDenseFormat() {
        // 旧格式是一句一个稠密字符串，槽位对不上 —— 按「这句没填」读，不能抛异常白屏
        List<List<String>> filled = LyricFillStore.readFilled("[\"新词一\", \"hello 世界\"]");

        assertEquals(2, filled.size());
        assertTrue(filled.stream().allMatch(List::isEmpty));
    }

    @Test
    public void blankColumnsReturnEmpty() {
        assertTrue(LyricFillStore.readTracks(null).isEmpty());
        assertTrue(LyricFillStore.readLines("  ").isEmpty());
        assertTrue(LyricFillStore.readFilled(null).isEmpty());
        assertTrue(LyricFillStore.readGaps(null).isEmpty());
    }

    /** 视觉空位（gaps_json）：布尔二维数组往返；NULL / 空 → 空列表 = 「没编辑过」。 */
    @Test
    public void roundTrip_gaps() {
        List<List<Boolean>> gaps = List.of(
                List.of(true, false, false, true),
                List.of(),
                List.of(false, true));

        assertEquals(gaps, LyricFillStore.readGaps(LyricFillStore.toJson(gaps)));
    }

    /** 脏数据（不是嵌套数组 / 单格不是布尔）按 false 读，不能抛异常 —— 只该少画点，
     *  由 {@code resolveGaps} 的形状校验决定要不要退回现算。 */
    @Test
    public void readGaps_toleratesJunkRows() {
        List<List<Boolean>> gaps = LyricFillStore.readGaps("[true, \"x\", [true, null, \"z\"]]");

        assertEquals(3, gaps.size());
        assertTrue(gaps.get(0).isEmpty());
        assertTrue(gaps.get(1).isEmpty());
        assertEquals(List.of(true, false, false), gaps.get(2));
    }
}
