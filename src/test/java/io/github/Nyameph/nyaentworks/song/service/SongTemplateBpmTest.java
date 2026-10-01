package io.github.Nyameph.nyaentworks.song.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 曲速提取的纯函数测试（写临时文件即可，不碰 F 盘、不连库）：{@link SongTemplateService#bpmFromSvp}
 * 与 {@link SongTemplateService#bpmFromMidi}。
 *
 * <p>svp 侧回归的是「svp 里有曲速却读不出来」：SynthV 写出的 svp 是「单行 JSON + 尾部一个 NUL
 * 填充字节」，截取 JSON 时若把结尾的 {@code }} 也切掉，解析必失败、返回 null，扫描与
 * 规范化命名就全部退化成 midi 文件名里的 {@code [BPM=NN]}。
 *
 * <p>mid 侧回归的是「只信文件名」的两个坑：文件名里大量写着 {@code [BPM=？]}（内容里才有真值），
 * 以及「末尾数字」兜底会误命中（{@code 远走高飞2.mid} → 2）。所以 midi 的字节要按事件结构走，
 * 不能裸扫 {@code FF 51 03}（SysEx 数据里撞上就会读出一个假的曲速）。
 */
class SongTemplateBpmTest {

    /** NUL 填充字节（写成 (char) 0，免得源码里出现真控制字符） */
    private static final String NUL = String.valueOf((char) 0);

    @TempDir
    Path tmp;

    /** 一份带曲速的最小 svp 工程文本（{@code tempo} 与真实工程同形状） */
    private static String svp(String bpmText) {
        return "{\"version\":153,\"time\":{\"meter\":[{\"index\":0,\"numerator\":4,"
                + "\"denominator\":4}],\"tempo\":[{\"position\":0,\"bpm\":" + bpmText + "}]},"
                + "\"library\":[],\"tracks\":[],\"renderConfig\":{}}";
    }

    private BigDecimal bpmOf(String text) throws Exception {
        Path file = tmp.resolve("x.svp");
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        return SongTemplateService.bpmFromSvp(file);
    }

    /** 按数值比较，避开 BigDecimal 的标度差异（84 与 8.4E+1 数值相同） */
    private static void assertBpm(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    @Test
    void readsTempoFromSvpWithTrailingNulPadding() throws Exception {
        // SynthV 的真实写法：JSON 后面跟一个 NUL 字节 —— 尾巴没掐干净就什么都读不到
        assertBpm("84", bpmOf(svp("84.000084000084") + NUL));
    }

    @Test
    void readsTempoFromCleanJson() throws Exception {
        assertBpm("84", bpmOf(svp("84")));
    }

    @Test
    void keepsFractionalTempo() throws Exception {
        assertBpm("87.5", bpmOf(svp("87.5") + NUL));
        assertBpm("143.75", bpmOf(svp("143.75") + NUL));
    }

    @Test
    void roundsFloatNoiseInTempo() throws Exception {
        // 实测库里两种噪声写法：84.000084000084（整数拍速）与 262.001
        assertBpm("84", bpmOf(svp("84.000084000084") + NUL));
        assertBpm("262", bpmOf(svp("262.001") + NUL));
    }

    @Test
    void missingTempoIsNullNotZero() throws Exception {
        assertNull(bpmOf("{\"version\":153,\"time\":{\"meter\":[]},\"tracks\":[]}"));
    }

    @Test
    void brokenOrMissingFileIsNull() throws Exception {
        assertNull(bpmOf("not a json at all"));
        assertNull(SongTemplateService.bpmFromSvp(null));
        assertNull(SongTemplateService.bpmFromSvp(tmp.resolve("不存在.svp")));
    }

    // ==================== midi 侧（读文件内容的 tempo 元事件） ====================

    /** 微秒 / 四分音符 → tempo 元事件（{@code FF 51 03 tttttt}），前面带 delta 变长量 */
    private static byte[] tempo(int delta, int micros) {
        return join(vlq(delta), raw(0xFF, 0x51, 0x03,
                micros >> 16 & 0xFF, micros >> 8 & 0xFF, micros & 0xFF));
    }

    /** 音符事件（channel 0，note on） */
    private static byte[] noteOn(int delta, int note, int velocity) {
        return join(vlq(delta), raw(0x90, note, velocity));
    }

    /** 带 delta 的元事件：{@code FF type len data…} */
    private static byte[] meta(int delta, int type, byte[] data) {
        return join(vlq(delta), raw(0xFF, type), vlq(data.length), data);
    }

    /** SysEx 事件：{@code F0 len data…}（data 里可以塞任何字节） */
    private static byte[] sysex(int delta, byte[] data) {
        return join(vlq(delta), raw(0xF0), vlq(data.length), data);
    }

    private static byte[] join(byte[]... parts) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static byte[] raw(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    /** MIDI 变长量（delta 时间 / 元事件长度）：每字节低 7 位有效、最高位 = 还有后续 */
    private static byte[] vlq(int value) {
        int shift = 28;
        while (shift > 0 && (value >>> shift & 0x7F) == 0) {
            shift -= 7;
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (; shift > 0; shift -= 7) {
            out.write(value >>> shift & 0x7F | 0x80);
        }
        out.write(value & 0x7F);
        return out.toByteArray();
    }

    /** 一个 MTrk 块：{@code MTrk} + 长度 + 事件字节 */
    private static byte[] chunk(byte[] events) {
        return join("MTrk".getBytes(StandardCharsets.US_ASCII), raw(
                events.length >> 24 & 0xFF, events.length >> 16 & 0xFF,
                events.length >> 8 & 0xFF, events.length & 0xFF), events);
    }

    /** 一份 MIDI 文件：{@code MThd}(格式 1、480 分/四分) + 若干轨 */
    private static byte[] midi(byte[]... tracks) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.writeBytes(join("MThd".getBytes(StandardCharsets.US_ASCII),
                raw(0, 0, 0, 6), raw(0, 1), raw(0, tracks.length), raw(1, 0xE0)));
        for (byte[] t : tracks) {
            out.writeBytes(chunk(t));
        }
        return out.toByteArray();
    }

    /** 把字节写成一个 .mid 再走正式入口（与扫描同一路径） */
    private BigDecimal midiOf(byte[] bytes) throws Exception {
        Path file = tmp.resolve("x.mid");
        Files.write(file, bytes);
        return SongTemplateService.bpmFromMidi(file);
    }

    @Test
    void readsTempoFromMidiContent() throws Exception {
        // 500000 微秒/四分音符 = 120 BPM（最常见的写法）
        assertBpm("120", midiOf(midi(tempo(0, 500_000), noteOn(0, 60, 100))));
    }

    @Test
    void roundsMicrosecondTempo() throws Exception {
        // MIDI 存的是整数微秒，带小数的拍速本来就换算不整：抹 2 位小数正好还原
        assertBpm("99", midiOf(midi(tempo(0, 606_061))));        // 99.0001…
        assertBpm("130.5", midiOf(midi(tempo(0, 459_770))));     // 130.5001…
        assertBpm("87.5", midiOf(midi(tempo(0, 685_714))));      // 87.4999…
    }

    @Test
    void takesFirstTempoWhenTrackChangesSpeed() throws Exception {
        // 多段曲速（实测库里 10 首）：与 svp 侧取 tempo[0] 同口径，只取第一段
        assertBpm("120", midiOf(midi(tempo(0, 500_000), tempo(480, 400_000))));
    }

    @Test
    void walksMultiByteDeltaAndRunningStatus() throws Exception {
        // 曲速故意放在后面：前面每个事件都得按结构走对，游标才到得了它
        byte[] events = join(
                noteOn(0, 60, 100),
                join(vlq(0), raw(60, 0)),                 // running status：省略状态字节的 note on
                join(vlq(128), raw(0x90, 62, 100)),       // delta = 128（变长量两个字节）
                join(vlq(0), raw(0xC0, 5)),               // program change：数据只有 1 字节
                tempo(0, 500_000));
        assertBpm("120", midiOf(midi(events)));
    }

    @Test
    void sysexPayloadIsNotTreatedAsTempo() throws Exception {
        // SysEx 数据里正好躺着 `FF 51 03 07 A1 20`（= 500000 微秒 = 120 BPM）：
        // 裸扫字节会读出一个假曲速，按事件结构走就读不到
        byte[] payload = raw(0x7D, 0xFF, 0x51, 0x03, 0x07, 0xA1, 0x20, 0xF7);
        assertNull(midiOf(midi(sysex(0, payload), noteOn(0, 60, 100))));
    }

    @Test
    void textMetaContainingTempoBytesIsNotTreatedAsTempo() throws Exception {
        // 文本元事件（如轨名）里同样能塞进这些字节 —— 裸扫 `FF 51 03` 的老坑
        assertNull(midiOf(midi(meta(0, 0x01, raw('1', '2', '0', 0xFF, 0x51, 0x03,
                0x07, 0xA1, 0x20)))));
    }

    @Test
    void noTempoEventIsNull() throws Exception {
        // 实测《耍把戏》两轨就是这样（内容里没有 tempo），此时只能退到文件名
        assertNull(midiOf(midi(noteOn(0, 60, 100), noteOn(480, 62, 100))));
        assertNull(midiOf(midi()));                       // 一轨都没有
    }

    @Test
    void brokenMidiIsNull() throws Exception {
        byte[] good = midi(tempo(0, 500_000));
        assertNull(midiOf("不是 midi，是一段文本".getBytes(StandardCharsets.UTF_8)));
        assertNull(midiOf(new byte[0]));
        assertNull(midiOf(java.util.Arrays.copyOf(good, 12)));                  // 头部都不完整
        assertNull(midiOf(java.util.Arrays.copyOf(good, 20)));                  // 截在轨的标记 / 长度上
        assertNull(midiOf(java.util.Arrays.copyOf(good, good.length - 4)));     // 截在 tempo 事件中间
        assertNull(SongTemplateService.bpmFromMidi(null));
        assertNull(SongTemplateService.bpmFromMidi(tmp.resolve("不存在.mid")));
    }
}
