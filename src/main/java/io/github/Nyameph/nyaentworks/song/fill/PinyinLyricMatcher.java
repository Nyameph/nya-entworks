package io.github.Nyameph.nyaentworks.song.fill;

import io.github.Nyameph.nyaentworks.common.pinyin.PinyinSyllable;
import io.github.Nyameph.nyaentworks.common.pinyin.PinyinUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 拼音模板 ↔ demo 歌词匹配（填词工具增强）。纯函数，可单测。
 *
 * <p>当模板 note 的 {@code lyrics} 是拼音（SynthV 工程里 {@code zhong}/{@code guo} 这类
 * 音节，被 {@link LyricFillParser#slotTypeOf} 判成 {@link SlotType#ENGLISH}）而 demo 歌词
 * 是汉字时，汉字对不上拼音，既分不了句、占位符也只剩拼音。这里把 demo 汉字经多音字读音
 * 匹配到拼音音符，产出「每个音符命中哪个汉字」的对齐，交给 {@link LyricFillAligner} 反推
 * 句边界与默认词占位符。
 *
 * <p><b>多音字</b>：用 {@link PinyinUtil#pinyin(int)} 取每个汉字的<b>全部读音</b>（本地
 * pinyin-data 字典，带声调），经 {@link #normalize} 归一（剥声调、{@code ü}→{@code v}）后音符
 * 拼音命中任一读音即算匹配。整句语义消歧不做——靠「任一读音匹配 + 跳过规则」兜底。
 *
 * <p><b>跳过规则</b>（需求原文「跳过 2 个以内的字、后续连续匹配超过一句才跳过」）：
 * 单调对齐时，当前音符与当前汉字不匹配，就尝试跳过 1~2 个汉字；<b>仅当</b>跳过后从
 * {@code (音符, 汉字)} 起的连续匹配游程跨过 &gt;1 个歌词行（即「连续匹配超过一句」；无行信息
 * 时退回游程长度 ≥ {@link #MIN_SKIP_RUN_UNITS}）才接受跳过——被跳的汉字是 demo 里多出来的
 * 字，不占音符。仍不匹配则该音符是模板多出来的（无对应汉字），前移音符。
 *
 * <p><b>死字规则</b>：当前汉字在之后 {@link #DEAD_UNIT_LOOKAHEAD} 个音符里谁都读不出
 * （demo 有而模板没唱的字，实测《aLIEz》的「扎」——模板把它并进了延音格），直接丢弃。
 * 跳过规则要跨行证据才接受、经常等不到；而 j 一旦焊死在一个永远读不出的字上，
 * 后面整首都会被拖崩（实测 40+ 句 0 命中、匹配率掉到 0.24）。
 *
 * <p><b>匹配率门控</b>：全程匹配到的音符占比低于 {@link #MIN_MATCH_RATIO} 就判「不是拼音模板
 * / 配错文件」，整段作废回落现有分句（时间戳 / br+空隙），别硬套。
 */
public final class PinyinLyricMatcher {

    /** 拼音路径的最低匹配率（匹配音符数 ÷ 音符总数）。低于它认为不是拼音模板 / 配错文件。 */
    public static final double MIN_MATCH_RATIO = 0.5;

    /** 无歌词行信息（整份歌词只有一行等退化情况）时，跳过校验退回的连续匹配单元数下限。 */
    private static final int MIN_SKIP_RUN_UNITS = 3;

    /**
     * 拼音模板判定的放宽倍率：非字母可填音符容忍 1 个（模板手误，实测《aLIEz》的 {@code 0}），
     * 音符多时放宽到 1/{@value #NON_ASCII_TOLERANCE}（5%）——真正的汉字模板非字母占绝对多数，不受影响。
     */
    private static final int NON_ASCII_TOLERANCE = 20;

    private PinyinLyricMatcher() {
    }

    /** 一次对齐结果。{@code noteToLyric} 按下标对应音符序列，值 = 命中的汉字单元下标，-1 未命中。 */
    public record Match(boolean matched, double ratio, int[] noteToLyric) {

        /** 未匹配（不是拼音模板 / 匹配率不足）的兜底值。 */
        public static Match empty(int size) {
            int[] map = new int[size];
            Arrays.fill(map, -1);
            return new Match(false, 0.0, map);
        }
    }

    // ==================== 检测 ====================

    /**
     * 是否「拼音模板」：可填音符的原值<b>几乎全部</b>含 ASCII 字母（拼音/英文，即几乎全被
     * {@code slotTypeOf} 判成 {@code ENGLISH}），且 demo 歌词里<b>有汉字</b>（非 ASCII 单元）。
     * 两者都满足才走拼音路径；否则沿用现有汉字 LCS 路径。
     *
     * <p><b>为什么不要求全部</b>：全有全无太脆。实测《aLIEz》模板有一个音符的歌词是手误的
     * {@code 0}（数字，被 {@code slotTypeOf} 判成 HANZI），仅这一格就让整首掉出拼音路径：
     * 分句退回时间戳、输入区退回灰显拼音。这里允许少量非字母音符（1 个，大模板放宽到
     * {@link #NON_ASCII_TOLERANCE} 分之一），它们匹配不上任何读音，在 {@link #align} 里按
     * 「模板多出来的音符」被前移跳过。真正的汉字模板非字母占绝对多数，照样判否；
     * 门槛有两条：字母音符必须占多数，非字母不超过 max(1, 5%)。
     */
    public static boolean isPinyinTemplate(List<String> noteUnits, List<String> lyricUnits) {
        if (noteUnits == null || noteUnits.isEmpty() || lyricUnits == null || lyricUnits.isEmpty()) {
            return false;
        }
        if (lyricUnits.stream().noneMatch(u -> !LyricFillAligner.hasAsciiLetter(u))) {
            return false;
        }
        long nonAscii = noteUnits.stream()
                .filter(u -> !LyricFillAligner.hasAsciiLetter(u))
                .count();
        // 双重门槛：字母音符必须占多数（一半以上），非字母不超过 max(1, 5%)
        return nonAscii * 2 < noteUnits.size()
                && nonAscii <= Math.max(1, noteUnits.size() / NON_ASCII_TOLERANCE);
    }

    // ==================== 归一与读音 ====================

    /**
     * 归一化拼音，音符侧（svp 里的拼音）与汉字读音侧（pinyin-data 带声调读音）都过这一道，
     * 保证 {@code zhōng}/{@code zhong4}/{@code zhong}、{@code nü}/{@code nv} 都能对到同一声：
     * 小写 → {@code ü} 家族显式归到 {@code v}（别让 NFD 把 {@code ü} 分解成 {@code u}，
     * 否则跟汉字读音侧的 {@code v} 对不上）→ NFD 剥其余声调结合符（{@code ā á ǎ à → a}）
     * → 去声调数字与空白等非字母。
     *
     * <p>实现已提到 {@link PinyinSyllable#toneless}（{@code common.pinyin} 包，免得 {@code PinyinUtil}
     * 那边要判音节时又抄一份、两处慢慢漂移）；本方法只留这个名字，调用方不用改。
     */
    public static String normalize(String pinyin) {
        return PinyinSyllable.toneless(pinyin);
    }

    /**
     * 音符拼音的全部候选取值（归一化后）。读音字典一律按规范写法记 {@code ü}（归一成 {@code v}），
     * 而 svp 工程里的音符常把它写成 {@code u} —— {@code lue}/{@code lve}、{@code nu}/{@code nv}
     * 两种写法在同一份工程里混着来。只认一种就会「一个音符卡住、整句全判未匹配」
     * （实测《Masked bitcH》「掠过漫不经心一个吻」的音符写 {@code lue}、字典读 {@code lve}，
     * 那一句 9 个字一个都没配上）。
     *
     * <p>ü 只可能出现在声母 {@code l} / {@code n} 之后（{@code j/q/x/y} 后的 {@code u}
     * 本来就是 ü 的简写，不存在第二种写法），所以候选最多两个。多出来的那个候选对
     * {@code luo}/{@code nuo} 这类真读 u 的音节只是永远命不中，没有副作用。
     */
    public static Set<String> noteVariants(String pinyin) {
        String normalized = normalize(pinyin);
        if (normalized.isEmpty()) {
            return Set.of();
        }
        String other = umlautVariant(normalized);
        return other == null ? Set.of(normalized) : Set.of(normalized, other);
    }

    /** {@code lue}↔{@code lve}、{@code lu}↔{@code lv}（{@code n} 同理）；不适用返回 null。 */
    private static String umlautVariant(String normalized) {
        if (normalized.length() < 2
                || (normalized.charAt(0) != 'l' && normalized.charAt(0) != 'n')) {
            return null;
        }
        char c = normalized.charAt(1);
        if (c == 'u') {
            return normalized.charAt(0) + "v" + normalized.substring(2);
        }
        if (c == 'v') {
            return normalized.charAt(0) + "u" + normalized.substring(2);
        }
        return null;
    }

    /** 音符拼音是否命中某个歌词单元（汉字取全部读音、任一命中即可；英文/标点无读音，恒 false）。 */
    public static boolean matches(String notePinyin, String lyricUnit) {
        Set<String> target = readings(lyricUnit);
        if (target.isEmpty()) {
            return false;
        }
        for (String sound : noteVariants(notePinyin)) {
            if (target.contains(sound)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 一个歌词单元的<b>常用</b>读音（归一化后）：与 {@link #readings} 同口径，只把生僻 /
     * 古音（{@link PinyinUtil#commonSyllables} 之外的那些）剔掉。非单个汉字返回空集。
     *
     * <p><b>只给对齐的「同音回退」用</b>（2026-09-28）：那条回退是为了兜模板与 demo 之间的
     * 一两个<b>常用</b>同音字出入（模板「纳」对 demo「那」、「淋」对「林」），拿全量读音去兜会
     * 造出假同音 —— 「听」在合并字典里带着古音 {@code yǐn}/{@code yí}，于是音符「一」({@code yi})
     * 命中歌词「听」、音符「听」命中歌词「因」({@code yin})，走法在第一个字上就错开短语、
     * 整段串位（实测《因为爱情》前四句：第 4 句多出一个「听」、轨 2 的「听到都会红着脸躲避」
     * 拆到别处）。严格相等那条路不受影响（同字照样命中），所以唱歌的人真唱了个生僻音时
     * 只是少了这条兜底，不会反过来错配。
     */
    public static Set<String> commonReadings(String unit) {
        Set<String> result = new HashSet<>();
        if (unit == null || unit.isEmpty() || LyricFillAligner.hasAsciiLetter(unit)) {
            return result;
        }
        int[] cps = unit.codePoints().toArray();
        if (cps.length != 1) {
            return result;
        }
        int cp = cps[0];
        if (!PinyinUtil.isHanzi(cp)) {
            return result;
        }
        for (String p : PinyinUtil.commonSyllables(cp)) {
            String n = normalize(p);
            if (!n.isEmpty()) {
                result.add(n);
            }
        }
        return result;
    }

    /** 一个歌词单元的全部读音（归一化后）。非单个汉字（英文词 / 标点 / 空）返回空集。 */
    public static Set<String> readings(String unit) {
        Set<String> result = new HashSet<>();
        if (unit == null || unit.isEmpty() || LyricFillAligner.hasAsciiLetter(unit)) {
            return result;
        }
        int[] cps = unit.codePoints().toArray();
        if (cps.length != 1) {
            return result;
        }
        int cp = cps[0];
        if (!PinyinUtil.isHanzi(cp)) {
            return result;
        }
        for (String p : PinyinUtil.pinyin(cp)) {
            result.add(normalize(p));
        }
        return result;
    }

    // ==================== 对齐 ====================

    /**
     * 单调对齐：音符序列（可填槽位原值）与汉字序列（tokenize 后的 demo）逐位对上，
     * 命中记录到 {@code noteToLyric}。见类头注释的跳过规则与匹配率门控。
     */
    public static Match align(List<String> noteUnits, List<String> lyricUnits, List<Integer> lyricLineOf) {
        if (!isPinyinTemplate(noteUnits, lyricUnits)) {
            return Match.empty(noteUnits == null ? 0 : noteUnits.size());
        }
        int n = noteUnits.size();
        int[] map = new int[n];
        Arrays.fill(map, -1);
        int i = 0;
        int j = 0;
        while (i < n && j < lyricUnits.size()) {
            if (matches(noteUnits.get(i), lyricUnits.get(j))) {
                map[i] = j;
                i++;
                j++;
                continue;
            }
            // 「死字」：从当前音符起的窗口内没有任何音符读得出这个汉字，它就永远撞不上
            //——是 demo 里模板没唱的字（实测《aLIEz》的「扎」，模板把「挣扎」的「扎」
            // 并进了延音格）。直接丢弃；不像跳过规则那样要跨行证据，因为等不到：
            // j 卡死在这一格会把后面整首拖崩（实测 40+ 句、匹配率掉到 0.24）。
            if (!readableAhead(i, j, noteUnits, lyricUnits)) {
                j++;
                continue;
            }
            // 不匹配：尝试跳过 1~2 个汉字，仅当跳过后能连续匹配超过一句才跳过
            int skip = -1;
            for (int k = 1; k <= 2 && j + k < lyricUnits.size(); k++) {
                if (matches(noteUnits.get(i), lyricUnits.get(j + k))
                        && continuousRun(i, j + k, noteUnits, lyricUnits, lyricLineOf)) {
                    skip = k;
                    break;
                }
            }
            if (skip > 0) {
                // 跳过的汉字是 demo 里多出来的字，不占音符；音符 i 下一轮再对
                j += skip;
                continue;
            }
            // 没有可接受的跳过：这个音符是模板多出来的（无对应汉字），前移音符
            i++;
        }
        int matched = (int) Arrays.stream(map).filter(idx -> idx >= 0).count();
        double ratio = n == 0 ? 0.0 : (double) matched / n;
        return new Match(ratio >= MIN_MATCH_RATIO, ratio, map);
    }

    /**
     * 「死字」判定窗口：从音符 {@code i} 起的窗口内（含自身）没有任何音符命中歌词单元
     * {@code j} 就算死字。窗口不用太大——音符这侧本来就允许逐个前移，真正要防的是
     * j 被一个永远读不出的字焊死。
     */
    private static final int DEAD_UNIT_LOOKAHEAD = 8;

    /** 歌词单元 {@code j} 在音符 {@code i} 起的窗口内是否还有音符读得出它。 */
    private static boolean readableAhead(int i, int j, List<String> noteUnits, List<String> lyricUnits) {
        int end = Math.min(noteUnits.size(), i + DEAD_UNIT_LOOKAHEAD);
        for (int k = i; k < end; k++) {
            if (matches(noteUnits.get(k), lyricUnits.get(j))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从 {@code (i, j)} 起，连续匹配游程是否「超过一句」：游程内命中的汉字跨过了歌词行边界
     * （即触及 ≥2 行），或（无行信息时）游程长度 ≥ {@link #MIN_SKIP_RUN_UNITS}。
     */
    private static boolean continuousRun(int i, int j, List<String> noteUnits,
                                         List<String> lyricUnits, List<Integer> lyricLineOf) {
        boolean hasLineInfo = lyricLineOf != null && !lyricLineOf.isEmpty();
        int firstLine = hasLineInfo && j < lyricLineOf.size() ? lyricLineOf.get(j) : -1;
        int len = 0;
        while (i < noteUnits.size() && j < lyricUnits.size()
                && matches(noteUnits.get(i), lyricUnits.get(j))) {
            if (hasLineInfo && j < lyricLineOf.size() && lyricLineOf.get(j) != firstLine) {
                return true;
            }
            len++;
            i++;
            j++;
        }
        return len >= MIN_SKIP_RUN_UNITS;
    }
}
