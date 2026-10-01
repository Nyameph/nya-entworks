package io.github.Nyameph.nyaentworks.song.fill;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * svp（synthV 工程）→ {@link LyricTemplate.FillTrack} 骨架。纯函数，可单测。
 *
 * <p>svp 是<b>纯 JSON</b>（不是 SQLite），UTF-8，末尾多 1 字节 {@code 00}
 * （实测 {@code F:\歌曲\temp\36.5°C_李佳思\测试用.svp} 以 {@code }} 结束、后跟 {@code \0}）。
 *
 * <p><b>轨序号按工程显示顺序排，不按 {@code tracks[]} 数组序</b>：SynthV 面板上从上到下的
 * 先后由每轨的 {@code dispOrder} 决定，数组序只是存储序，两者实测经常不一样（《栖凰》数组
 * 是「和声6 吟唱、和声7 吟唱、副歌4、伴奏、主歌2、…」，面板顺序是「主歌1 - 副本 1、
 * 主歌1 - 副本、主歌2、…、人声」）。用户看的是面板顺序，所以这里按 {@code dispOrder}
 * 排序后<b>重新编号</b>（{@link LyricTemplate.FillTrack#trackIndex()} = 排定后的位次），
 * 后面所有「按轨号排序 / 标 #号」的地方（组号编排、导出先后、页面上的 {@code #n} 与轨色）
 * 自然都跟面板一致。同 {@code dispOrder}（实测《栖凰》的和声6/和声7/伴奏 都是 9）按存储序
 * 兜底；旧工程压根没写 {@code dispOrder} 时退回数组序（= 老行为）。
 *
 * <p><b>歌唱轨判定 = 该轨有没有音符</b>。音符可能落在两处：旧格式直接塞在
 * {@code mainGroup.notes}；新格式（SynthV 2.0 note-group）抽到顶层 {@code library}、
 * 由 {@code track.groups[].groupID} 按 uuid 引用。两种都收。实测音频参考轨（伴奏 / 原声）
 * 无音符、带 {@code audio} 字段；不要用 {@code isInstrumental} 或 {@code audio} 作主判据
 * （填词工具设计 2.2）。
 *
 * <p><b>库组引用必须叠加 {@code blickOffset}</b>：新格式里工程常把音符组存到 library 的
 * 「原始位置」，再用引用的 {@code blickOffset} 挪到实际位置（正负都有——实测《九九八十一柔情版》
 * 主歌1 的 offset 是 -81.25 小节、副歌4 是 +60 小节）。不叠 offset，各轨位置全错、不同轨还会
 * 叠在同一处。同一 groupID 可以以不同 offset 挂多次（重复段落），去重按 {@code groupID+offset}。
 * 引用上的 {@code blickAbsoluteBegin/End} 是显示裁剪窗（End 为 -1 = 不限），完全落在窗外
 * 的音符跳过（SynthV 里也看不见）。
 *
 * <p>音符只取 {@code lyrics} / {@code onset} / {@code duration}，{@code pitch} 丢弃。
 * 注意字段名是复数 <b>{@code lyrics}</b>，不是 {@code lyric}。
 *
 * <p>时间单位是 blick：<b>1 秒 = {@value #BLICK_PER_SECOND}</b>（1 拍 = 705600000，
 * 120 BPM 下 1 拍 0.5 秒）。校验：首个汉字 onset 22579200000 → 精确 16.000 秒。
 */
public final class LyricFillParser {

    /** 1 秒的 blick 数。实测：ust Length=240（半拍）↔ svp duration=352800000，比例 1470000。 */
    public static final long BLICK_PER_SECOND = 1_411_200_000L;

    private LyricFillParser() {
    }

    /** 读文件解析。文件不存在 / 读不了时抛 {@link IllegalStateException}（消息直接给人看）。 */
    public static List<LyricTemplate.FillTrack> parse(Path svpPath) {
        String text;
        try {
            text = Files.readString(svpPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读 svp 失败：" + svpPath + "（" + e.getMessage() + "）", e);
        }
        return parseJson(text);
    }

    /** 解析 svp 文本。末尾的 NUL 字节在这里剥掉。 */
    public static List<LyricTemplate.FillTrack> parseJson(String raw) {
        JSONObject root;
        try {
            root = JSON.parseObject(stripTrailingNul(raw));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("svp 不是合法 JSON：" + e.getMessage());
        }
        if (root == null) {
            throw new IllegalArgumentException("svp 内容为空");
        }
        JSONArray tracks = root.getJSONArray("tracks");
        if (tracks == null || tracks.isEmpty()) {
            throw new IllegalArgumentException("svp 里没有 tracks，确认这是 synthV 导出的工程文件");
        }

        // 新格式（SynthV 2.0 note-group）：音符抽到顶层 library，track 用 groups[].groupID 引用；
        // 旧格式 library 为空、音符直接塞在 mainGroup.notes 里。两种都收。
        Map<String, JSONArray> libraryNotes = indexLibrary(root);

        List<Placed> placed = new ArrayList<>();
        for (int ti = 0; ti < tracks.size(); ti++) {
            JSONObject track = tracks.getJSONObject(ti);
            if (track == null) {
                continue;
            }
            List<LyricTemplate.FillNote> parsed = new ArrayList<>();
            collectNotes(track, libraryNotes, parsed);
            // 音频参考轨（伴奏 / 原声）无音符 —— 空轨不进骨架
            if (parsed.isEmpty()) {
                continue;
            }
            parsed.sort(Comparator.comparingLong(LyricTemplate.FillNote::onset));
            // dispOrder = SynthV 面板上的先后；工程没写（旧格式）就用数组序兜底
            Integer dispOrder = track.getInteger("dispOrder");
            placed.add(new Placed(ti, dispOrder == null ? ti : dispOrder,
                    track.getString("name"), List.copyOf(parsed)));
        }
        if (placed.isEmpty()) {
            throw new IllegalArgumentException("svp 里没有带音符的歌唱轨（音频参考轨的 notes 是空的）");
        }
        // 按面板顺序重编号：trackIndex 从此就是「工程里第几轨」（同 dispOrder 按存储序兜底），
        // 见类头注释 —— 别再改回 tracks[] 数组下标，那会让页面的 #号与工程对不上
        placed.sort(Comparator.comparingInt(Placed::dispOrder).thenComparingInt(Placed::index));
        List<LyricTemplate.FillTrack> result = new ArrayList<>(placed.size());
        for (int i = 0; i < placed.size(); i++) {
            Placed p = placed.get(i);
            result.add(new LyricTemplate.FillTrack(i, p.name(), p.notes()));
        }
        return result;
    }

    /** 一条有音符的轨 + 它的工程显示序（排序用，排完就按位次重新编号）。 */
    private record Placed(int index, int dispOrder, String name,
                          List<LyricTemplate.FillNote> notes) {
    }

    /** 顶层 {@code library}（NoteGroup 数组）→ uuid → notes。旧格式 library 空或缺省，返回空 map。 */
    private static Map<String, JSONArray> indexLibrary(JSONObject root) {
        Map<String, JSONArray> map = new HashMap<>();
        JSONArray library = root.getJSONArray("library");
        if (library == null) {
            return map;
        }
        for (int i = 0; i < library.size(); i++) {
            JSONObject group = library.getJSONObject(i);
            if (group == null) {
                continue;
            }
            String uuid = group.getString("uuid");
            JSONArray notes = group.getJSONArray("notes");
            if (uuid != null && notes != null && !notes.isEmpty()) {
                map.put(uuid, notes);
            }
        }
        return map;
    }

    /**
     * 一条轨的全部音符：{@code mainGroup.notes}（旧格式内联，按 {@code mainRef} 摆位）+
     * {@code groups[].groupID} 引用的 library 音符（新格式，按各引用自己的摆位）。
     *
     * <p>main 组的<b>空存根</b>（SynthV 2.0 把音符抽到 library 后，轨上的 mainGroup 只剩
     * {@code {name, uuid, parameters, notes: []}}、真正音符在 library 由 {@code mainRef.groupID}
     * 指向——实测《免我蹉跎苦》整轨如此）等同「没有内联」：内联 notes 为 null / 空数组都要
     * 落到 mainRef 兜底，不然整轨音符全丢。兜底消费过的 groupID+offset 预登记进去重 set，
     * 防 {@code groups[]} 再挂同一组同一偏移导致双收。
     */
    private static void collectNotes(JSONObject track, Map<String, JSONArray> libraryNotes,
                                     List<LyricTemplate.FillNote> out) {
        JSONObject mainRef = track.getJSONObject("mainRef");
        JSONObject mainGroup = track.getJSONObject("mainGroup");
        JSONArray mainNotes = mainGroup == null ? null : mainGroup.getJSONArray("notes");
        Set<String> seen = new HashSet<>();
        if (mainNotes != null && !mainNotes.isEmpty()) {
            addNotes(mainNotes, placementOf(mainRef), out);
        } else if (mainRef != null && mainRef.getString("groupID") != null) {
            // 兜底：main 组没有内联（没写 mainGroup / 空存根），音符只在 library 里
            addNotes(libraryNotes.get(mainRef.getString("groupID")), placementOf(mainRef), out);
            seen.add(mainRef.getString("groupID") + '/' + mainRef.getLongValue("blickOffset"));
        }
        JSONArray groups = track.getJSONArray("groups");
        if (groups == null) {
            return;
        }
        for (int gi = 0; gi < groups.size(); gi++) {
            JSONObject ref = groups.getJSONObject(gi);
            if (ref == null) {
                continue;
            }
            String groupId = ref.getString("groupID");
            // 同一组可以以不同 offset 挂多次（重复段落），去重按 groupID+offset
            if (groupId == null || !seen.add(groupId + '/' + ref.getLongValue("blickOffset"))) {
                continue;
            }
            addNotes(libraryNotes.get(groupId), placementOf(ref), out);
        }
    }

    /** 一个组引用的摆位：blickOffset 平移 + blickAbsoluteBegin/End 裁剪窗（End 为 -1 = 不限）。 */
    private record Placement(long offset, long absoluteBegin, long absoluteEnd) {

        static final Placement UNPLACED = new Placement(0, 0, -1);
    }

    /** 读引用上的摆位；字段缺省时按「不平移、不裁剪」处理（旧格式没有这些字段）。 */
    private static Placement placementOf(JSONObject ref) {
        if (ref == null) {
            return Placement.UNPLACED;
        }
        long begin = ref.containsKey("blickAbsoluteBegin") ? ref.getLongValue("blickAbsoluteBegin") : 0;
        long end = ref.containsKey("blickAbsoluteEnd") ? ref.getLongValue("blickAbsoluteEnd") : -1;
        return new Placement(ref.getLongValue("blickOffset"), begin, end);
    }

    /** 一组 note 逐个解析进 out，先按摆位平移；完全落在裁剪窗外的丢弃（SynthV 里也看不见）。 */
    private static void addNotes(JSONArray notes, Placement placement, List<LyricTemplate.FillNote> out) {
        if (notes == null || notes.isEmpty()) {
            return;
        }
        for (int ni = 0; ni < notes.size(); ni++) {
            JSONObject note = notes.getJSONObject(ni);
            if (note == null) {
                continue;
            }
            long onset = note.getLongValue("onset") + placement.offset();
            long noteEnd = onset + note.getLongValue("duration");
            if (noteEnd <= placement.absoluteBegin()
                    || (placement.absoluteEnd() >= 0 && onset >= placement.absoluteEnd())) {
                continue;
            }
            String lyrics = note.getString("lyrics");
            GlottalLyric parsed = GlottalLyric.of(lyrics);
            out.add(new LyricTemplate.FillNote(
                    onset,
                    note.getLongValue("duration"),
                    parsed.core(),
                    slotTypeOf(parsed.core()),
                    parsed.glottal()));
        }
    }

    /**
     * 剥掉喉塞音前缀撇号（SynthV 的喉塞起音记号，{@code '曾} 一类）：以撇号开头、
     * 剥完全部前导撇号后还剩内容、且剩的不是 {@code -} / {@code br} / {@code 0}，才算喉塞标记 ——
     * 核心字照常参与槽位判定与歌词匹配，标记进 {@code glottal} 供导出回填时拼回前缀。
     * 其余（孤立 {@code '}、{@code '-}、{@code 'br}、{@code '0}、普通词）不剥，维持原行为。
     *
     * <p><b>撇号的变体、夹带的空白、词两端的引号一并清掉</b>（2026-09-13，第 56 条）：
     * 原来只认 ASCII {@code '}，于是输入法打岔出的全角撇号（{@code ‘两} / {@code ’两}）与
     * 撇号后夹的空格（{@code ' 急}）都留在了<b>原词</b>里 —— 同一拍上主旋律写 {@code 两}、
     * 和声写 {@code ‘两}，配对判等（{@code Objects.equals(lyrics)}）直接判不等，合唱 / 和声
     * 因此配不上对、各自成句（实测《气泡少女》从第 34 句起多出 4 句，见第 56 条）。
     * 全角撇号与 ASCII 撇号同义（都进 {@code glottal}）；词首 / 词尾的引号（{@code " “ ”}）
     * 与空白只当杂字符剥掉、不算喉塞记号。
     */
    private record GlottalLyric(String core, boolean glottal) {

        /** 词首的喉塞记号：ASCII 撇号 + 输入法打岔出的全角撇号（都算喉塞标记）。 */
        private static final String GLOTTAL_MARKS = "'‘’";

        /** 词首 / 词尾一律剥掉的杂字符：引号，不算喉塞标记。 */
        private static final String QUOTE_JUNK = "\"“”";

        static GlottalLyric of(String raw) {
            if (raw == null) {
                return new GlottalLyric(null, false);
            }
            String core = trim(raw);
            int k = 0;
            while (k < core.length() && GLOTTAL_MARKS.indexOf(core.charAt(k)) >= 0) {
                k++;
            }
            if (k > 0) {
                String rest = trim(core.substring(k));   // 「' 曾」这种撇号与核心字之间夹空格的
                if (!rest.isEmpty() && !"-".equals(rest) && !"br".equals(rest)
                        && !"0".equals(rest)) {
                    return new GlottalLyric(rest, true);
                }
            }
            return new GlottalLyric(core, false);
        }

        /** 剥掉首尾的空白与引号类杂字符（喉塞记号不在此列，它要参与判定）。 */
        private static String trim(String s) {
            int from = 0;
            int to = s.length();
            while (from < to && isJunk(s.charAt(from))) {
                from++;
            }
            while (to > from && isJunk(s.charAt(to - 1))) {
                to--;
            }
            return s.substring(from, to);
        }

        private static boolean isJunk(char c) {
            return Character.isWhitespace(c) || QUOTE_JUNK.indexOf(c) >= 0;
        }
    }

    /**
     * 槽位类型判定（填词工具设计 2.4）。
     *
     * <p>{@code "-"} 延音、{@code "br"} 呼吸控制、{@code "0"} 静音占位、含 ASCII 字母算英文单词、
     * 其余算汉字。空 / null 按汉字处理 —— 它确实是一个待填的槽位。喉塞音前缀撇号（{@code '曾}）
     * 在进入判定前已剥掉（见 {@link GlottalLyric}），这里只看核心字。
     */
    public static SlotType slotTypeOf(String lyrics) {
        if ("-".equals(lyrics)) {
            return SlotType.DASH;
        }
        if ("br".equals(lyrics)) {
            return SlotType.BREATH;
        }
        if ("0".equals(lyrics)) {
            return SlotType.ZERO;
        }
        if (LyricFillAligner.hasAsciiLetter(lyrics)) {
            return SlotType.ENGLISH;
        }
        return SlotType.HANZI;
    }

    /** blick → 秒（按 120bpm 口径 = BLICK_PER_SECOND；与旧调用兼容） */
    public static double secondsOf(long onset) {
        return secondsOf(onset, null);
    }

    /**
     * blick → 秒：**曲速参与换算**——1 拍 = 705600000 blick（固定），
     * 每秒 blick 数 = 705600000 × bpm / 60 = 11760000 × bpm；bpm=120 时恰为
     * {@link #BLICK_PER_SECOND}（旧口径）。{@code bpm} 为 null / 非正时按 120 兜底。
     */
    public static double secondsOf(long onset, Double bpm) {
        double effective = bpm == null || bpm <= 0 ? 120.0 : bpm;
        return onset / (BLICK_PER_SECOND * effective / 120.0);
    }

    /** lrc 时间戳 {@code [mm:ss.xx]}，按百分秒四舍五入。 */
    public static String lrcTime(double seconds) {
        long totalCs = Math.max(0, Math.round(seconds * 100));
        return String.format("[%02d:%02d.%02d]", totalCs / 6000, (totalCs % 6000) / 100, totalCs % 100);
    }

    /**
     * 剥掉 JSON 主体之后的尾巴。svp 末尾有 1 字节 {@code 00}，直接 parse 会报
     * 「多余的字符」；截到最后一个 {@code }} 为止（JSON 主体必然以它结束）。
     */
    private static String stripTrailingNul(String raw) {
        if (raw == null) {
            return null;
        }
        int end = raw.lastIndexOf('}');
        return end < 0 ? raw : raw.substring(0, end + 1);
    }
}
