package io.github.Nyameph.nyaentworks.song.fill;

import io.github.Nyameph.nyaentworks.common.lyric.LyricLine;
import io.github.Nyameph.nyaentworks.common.lyric.LyricParser;
import io.github.Nyameph.nyaentworks.common.lyric.TextDecoder;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本机语料回归快照（不进 CI）：对 {@code F:/歌曲/模板} 下每个「[工程] *.svp + [demo] *.lrc」
 * 跑一遍 {@link LyricFillAligner#split}，把分句 / 槽位 / 默认词 / 视觉空位全量写成文本
 * （UTF-8），供改动前后 diff。语料目录不存在时跳过（与 ParserTest 对本机样本的
 * assumeTrue 门控同一惯例）。快照路径可用 {@code -Dcorpus.snapshot.out=} 覆盖。
 */
class LyricFillCorpusSnapshotTest {

    private static final Path CORPUS = Path.of("F:/歌曲/模板");

    @Test
    void snapshot() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(CORPUS), "本机语料目录不存在，跳过");
        StringBuilder out = new StringBuilder();
        try (Stream<Path> dirs = Files.list(CORPUS)) {
            List<Path> sorted = dirs.filter(Files::isDirectory).sorted().toList();
            for (Path dir : sorted) {
                snapshotDir(dir, out);
            }
        }
        String snapshot = out.toString();
        Path target = Path.of(System.getProperty("corpus.snapshot.out", "target/corpus-snapshot.txt"));
        Files.createDirectories(target.toAbsolutePath().getParent());
        Files.writeString(target, snapshot, StandardCharsets.UTF_8);
        assertTrue(snapshot.lines().anyMatch(l -> l.startsWith("== ")), "语料一个都没解析出来");
    }

    private void snapshotDir(Path dir, StringBuilder out) {
        Path svp = find(dir, "[工程]", ".svp");
        Path lrc = find(dir, "[demo]", ".lrc");
        if (svp == null || lrc == null) {
            return;
        }
        try {
            List<LyricTemplate.FillTrack> tracks = LyricFillParser.parse(svp);
            List<LyricLine> lyrics = LyricParser.parse(TextDecoder.read(lrc), "lrc");
            LyricFillAligner.SplitResult result =
                    LyricFillAligner.split(tracks, null, lyrics, null, bpmOf(svp));
            out.append("== ").append(dir.getFileName())
                    .append(" pinyin=").append(result.pinyin()).append('\n');
            for (int i = 0; i < result.lines().size(); i++) {
                LyricTemplate.FillLine line = result.lines().get(i);
                out.append(String.format(Locale.ROOT, "| %d | %.2f |", i,
                        line.startOnset() / (double) LyricFillParser.BLICK_PER_SECOND));
                for (int k = 0; k < line.slots().size(); k++) {
                    LyricTemplate.FillSlot slot = line.slots().get(k);
                    out.append(' ').append(slot.trackIndex()).append(':').append(slot.noteIndex())
                            .append(':').append(slot.original()).append(':')
                            .append(slot.slotType().name().charAt(0))
                            .append(':').append(groupOf(line, k));
                }
                if (i < result.defaults().size()) {
                    out.append(" | d:");
                    result.defaults().get(i).forEach(d -> out.append(d.isEmpty() ? "·" : d));
                }
                if (i < result.gaps().size()) {
                    out.append(" | g:");
                    result.gaps().get(i).forEach(g -> out.append(g ? "1" : "0"));
                }
                out.append('\n');
            }
        } catch (Exception e) {
            out.append("== ").append(dir.getFileName()).append(" !! ").append(e).append('\n');
        }
    }

    private static int groupOf(LyricTemplate.FillLine line, int k) {
        List<Integer> groups = line.groups();
        return groups != null && k < groups.size() ? groups.get(k) : -1;
    }

    /** 工程曲速（time.tempo[0].bpm），与扫描入库同一口径；读不出返回 null（按 120 兜底）。 */
    private static Double bpmOf(Path svp) throws IOException {
        String raw = Files.readString(svp, StandardCharsets.UTF_8);
        int end = raw.lastIndexOf('}');
        JSONObject root = JSON.parseObject(end < 0 ? raw : raw.substring(0, end + 1));
        JSONObject time = root.getJSONObject("time");
        if (time == null || time.getJSONArray("tempo") == null || time.getJSONArray("tempo").isEmpty()) {
            return null;
        }
        Double bpm = time.getJSONArray("tempo").getJSONObject(0).getDouble("bpm");
        return bpm == null || bpm <= 0 ? null : bpm;
    }

    private static Path find(Path dir, String prefix, String suffix) {
        List<Path> hits = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> {
                String name = p.getFileName().toString();
                return name.startsWith(prefix) && name.toLowerCase(Locale.ROOT).endsWith(suffix);
            }).sorted().forEach(hits::add);
        } catch (IOException e) {
            return null;
        }
        return hits.isEmpty() ? null : hits.getFirst();
    }
}
