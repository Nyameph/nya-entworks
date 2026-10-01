package io.github.Nyameph.nyaentworks.manga.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 展会 / 杂志名的容错比对，纯静态、不连库。
 *
 * <p><b>为什么要它</b>：本地 e-hentai 快照与本项目目录名对同一个展会/杂志的写法常常不同 ——
 * {@code C97} / {@code コミックマーケット97} / {@code Comic Market 97} 是同一个展会，
 * {@code COMIC 高 Vol.4} / {@code COMIC Koh Vol.4} 是同一本杂志。直接比归一后的字符串会
 * 大面积错判「不是同一个」。
 *
 * <p><b>口径：期号/日期必须对上，文字部分容错</b>。名字里的数字（展会届数 {@code C97}、
 * 杂志期号 {@code Vol.4}、年月 {@code 2024.05}）是最可靠的区分位 —— 写法再怎么变，
 * {@code C97} 不会变成 {@code C98}。所以：
 * <ol>
 *   <li>两边都有数字且数字对不上 → <b>判定不兼容</b>（C97 vs C98 是不同展会，一票否决）；</li>
 *   <li>数字对上后，文字部分只要「其一是另一方的缩写/子串」或字符重合度够（
 *       {@link #LETTERS_SIM_MIN}）即算兼容 —— 覆盖 {@code C} vs {@code コミックマーケット}
 *       这类缩写与跨语言写法；</li>
 *   <li>一边没数字 → 退化成纯文字比较，不因缺数字就否掉。</li>
 * </ol>
 *
 * <p><b>三态而非二值</b>：{@link #classify} 给出 {@link Verdict#MATCH}（两边都有值且有一对兼容，
 * 算强区分信号）/ {@link Verdict#CONFLICT}（两边都有值但没有一对兼容，该剔除）/
 * {@link Verdict#UNKNOWN}（至少一边没值，判不了）。缺失（本地库没拆出展会、或漫画目录名没写）
 * 落在 UNKNOWN 而不是 CONFLICT —— 拆分工具覆盖不全是常态，缺值当冲突会误杀大量本可命中的漫画。
 */
public final class MangaEventKey {

    private MangaEventKey() {
    }

    /**
     * 文字部分被认为「短到只可能是缩写」的长度上限。{@code C97} 的文字部分是 {@code C}（1 字符），
     * 与 {@code コミックマーケット} 没有任何字符重合，只能靠「短 + 数字相同」判兼容。
     */
    private static final int ABBREV_MAX_LEN = 3;

    /** 文字部分的字符二元组重合度门槛：数字已对上时，文字只需大致像 */
    private static final double LETTERS_SIM_MIN = 0.34;

    /** 一组展会（或杂志）名两两比对的三态结论。 */
    public enum Verdict {
        /** 两边都有值且存在一对兼容：强区分信号，「同一作者同一展会通常只有一部」 */
        MATCH,
        /** 两边都有值但没有一对兼容：不是同一本，该剔除 */
        CONFLICT,
        /** 至少一边没值：判不了，既不加分也不否决 */
        UNKNOWN
    }

    /**
     * 判定两组展会（或两组杂志）名的关系。
     * <p><b>缺值是 {@link Verdict#UNKNOWN} 而不是 {@link Verdict#CONFLICT}</b>：本地库拆分列
     * 覆盖不全、漫画目录名也不总写展会，缺值当冲突会误杀大量本可命中的漫画。
     *
     * @param mine   漫画侧的归一 key 集合（含翻译字典扩充出的另一语言写法）
     * @param theirs 本地库某一行的归一 key 集合（英文列 + 日文列）
     */
    public static Verdict classify(Set<String> mine, Set<String> theirs) {
        if (mine == null || mine.isEmpty() || theirs == null || theirs.isEmpty()) {
            return Verdict.UNKNOWN;
        }
        for (String a : mine) {
            for (String b : theirs) {
                if (compatible(a, b)) {
                    return Verdict.MATCH;
                }
            }
        }
        return Verdict.CONFLICT;
    }

    /** {@link #classify} 判为 {@link Verdict#MATCH}——两边都有值且存在一对兼容。 */
    public static boolean matches(Set<String> mine, Set<String> theirs) {
        return classify(mine, theirs) == Verdict.MATCH;
    }

    /** {@link #classify} 判为 {@link Verdict#CONFLICT}——两边都有值但没有一对兼容。 */
    public static boolean conflicts(Set<String> mine, Set<String> theirs) {
        return classify(mine, theirs) == Verdict.CONFLICT;
    }

    /**
     * 两个归一 key 是否可能指同一个展会/杂志。
     * <p>入参应当已过 {@link MangaEhLocalDb#stripKey}（大写、只留字母数字）。
     */
    public static boolean compatible(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        List<String> na = numbers(a);
        List<String> nb = numbers(b);
        String la = letters(a);
        String lb = letters(b);

        // 两边都有数字：数字是硬约束，对不上就是不同期/届
        if (!na.isEmpty() && !nb.isEmpty()) {
            if (!na.equals(nb)) {
                return false;
            }
            // 数字相同，文字部分放宽：一边是缩写（C vs コミックマーケット）也算
            return lettersCompatible(la, lb);
        }
        // 至少一边没数字：退化成纯文字比较（缺数字不否决）
        return lettersCompatible(la, lb);
    }

    /**
     * 文字部分是否兼容：互为子串、任一边短到只能是缩写、或字符二元组重合度过线。
     * <p>两边都没文字（纯数字名）时视为兼容 —— 此时数字已在上层对上。
     */
    private static boolean lettersCompatible(String la, String lb) {
        if (la.isEmpty() || lb.isEmpty()) {
            return true;
        }
        if (la.equals(lb) || la.contains(lb) || lb.contains(la)) {
            return true;
        }
        if (la.length() <= ABBREV_MAX_LEN || lb.length() <= ABBREV_MAX_LEN) {
            // 缩写与全称无字符重合（C / コミックマーケット），只能靠数字已相同来担保
            return true;
        }
        return MangaTitleMatcher.dice(la, lb) >= LETTERS_SIM_MIN;
    }

    /**
     * 按出现顺序取出名字里的所有数字段，去掉前导零。
     * <p>去前导零是为了让 {@code Vol.04} 与 {@code Vol.4} 对上；保留顺序是为了让
     * {@code 2024.05} 与 {@code 2024.06} 区分得开。
     */
    public static List<String> numbers(String s) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            if (!Character.isDigit(s.charAt(i))) {
                i++;
                continue;
            }
            int j = i;
            while (j < s.length() && Character.isDigit(s.charAt(j))) {
                j++;
            }
            String num = s.substring(i, j).replaceFirst("^0+(?=\\d)", "");
            out.add(num);
            i = j;
        }
        return out;
    }

    /** 去掉所有数字后剩下的文字部分。 */
    public static String letters(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
