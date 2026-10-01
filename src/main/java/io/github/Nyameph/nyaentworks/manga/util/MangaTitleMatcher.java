package io.github.Nyameph.nyaentworks.manga.util;

import java.util.HashSet;
import java.util.Set;

/**
 * 本地漫画标题与 e-hentai 画廊标题的确定性匹配，纯静态、不连库。
 * <p>复用 {@link MangaTextUtil#normalizeNameKey}（NFC + trim + 大写）做键；比较口径三级：
 * 精确相等 → 去分隔符后包含 → 不匹配。eh 标题常带 {@code (Cxx)} 展会编号、社团名等前后缀，
 * 所以「包含」要把分隔符（空格/括号/横线等）剥掉再比，别被一个括号挡住。
 */
public final class MangaTitleMatcher {

    private MangaTitleMatcher() {
    }

    public static final double SCORE_EXACT = 1.0;
    public static final double SCORE_CONTAINS = 0.85;

    /**
     * 分级相似度（0~1），用于在一组候选里<b>排序取最佳</b>——{@link #match} 那套只回答
     * 「像不像」（精确/包含/不像三值），同一作者名下几十本候选全判 0 分时无法区分谁更近。
     * <p>三档：精确相等 {@link #SCORE_EXACT}、包含 {@link #SCORE_CONTAINS}、
     * 其余按字符二元组 Dice 系数打分并压在包含档之下（弱相似不许冒充包含命中）。
     *
     * @return 0 表示任一侧为空；否则 0~1
     */
    public static double similarity(String k1, String k2) {
        if (k1 == null || k2 == null || k1.isEmpty() || k2.isEmpty()) {
            return 0;
        }
        if (k1.equals(k2)) {
            return SCORE_EXACT;
        }
        if (containsMatch(k1, k2)) {
            return SCORE_CONTAINS;
        }
        return dice(k1, k2) * SCORE_CONTAINS;
    }

    /** 字符二元组 Dice 系数 {@code 2|A∩B|/(|A|+|B|)}：字符集重合度，对语序不敏感、O(n+m)。 */
    static double dice(String a, String b) {
        if (a.length() < 2 || b.length() < 2) {
            return 0;
        }
        Set<String> sa = bigrams(a);
        Set<String> sb = bigrams(b);
        int inter = 0;
        for (String g : sa) {
            if (sb.contains(g)) {
                inter++;
            }
        }
        return 2.0 * inter / (sa.size() + sb.size());
    }

    static Set<String> bigrams(String s) {
        Set<String> out = new HashSet<>(s.length());
        for (int i = 0; i + 1 < s.length(); i++) {
            out.add(s.substring(i, i + 2));
        }
        return out;
    }

    /** 包含匹配的最短子串长度：更短的（如 eh 标题只带「めぐみん」4 字符）不判包含 */
    public static final int MIN_CONTAINS_LEN = 4;

    /** 确定性匹配方式：精确相等或去分隔符后包含，都记为 TITLE_EXACT（区别于 AI） */
    public static final String METHOD_TITLE_EXACT = "TITLE_EXACT";

    public record Match(String method, double score) {
        static Match none() {
            return new Match(null, 0.0);
        }
    }

    /**
     * 对本地标题与候选的两个标题（原文 + 日文）打分，取较高者。
     *
     * @return 命中时 {@code method=TITLE_EXACT}、{@code score}=1.0 或 0.85；否则 {@code method=null, score=0}
     */
    public static Match match(String localTitle, String ehTitle, String ehTitleJpn) {
        double best = 0;
        String method = null;
        for (String t : new String[]{ehTitle, ehTitleJpn}) {
            if (t == null || t.isBlank()) {
                continue;
            }
            Match m = compare(localTitle, t);
            if (m.score() > best) {
                best = m.score();
                method = m.method();
            }
        }
        return new Match(method, best);
    }

    private static Match compare(String local, String eh) {
        String k1 = MangaTextUtil.normalizeNameKey(local);
        String k2 = MangaTextUtil.normalizeNameKey(eh);
        if (k1 == null || k2 == null) {
            return Match.none();
        }
        if (k1.equals(k2)) {
            return new Match(METHOD_TITLE_EXACT, SCORE_EXACT);
        }
        String s1 = stripSeparators(k1);
        String s2 = stripSeparators(k2);
        if (containsMatch(s1, s2)) {
            return new Match(METHOD_TITLE_EXACT, SCORE_CONTAINS);
        }
        return Match.none();
    }

    /**
     * 包含匹配的收紧门槛：较短一方长度 ≥ {@link #MIN_CONTAINS_LEN} 且至少占较长方一半、
     * 且非续作关系，才允许「互相包含」判定。避免 eh 标题只带本地标题的短前缀
     * （本地「…ごっこ2」、eh 只有「めぐみん」4 字符）就被误判成包含命中。
     */
    public static boolean containsMatch(String s1, String s2) {
        int min = Math.min(s1.length(), s2.length());
        int max = Math.max(s1.length(), s2.length());
        // 判定顺序按「便宜的先算」排：长度比 → 子串包含 → 续作。isSequel 会剥后缀分配新字符串，
        // 而绝大多数候选在 contains 这步就否掉了，把它放最后能省掉几乎所有的字符串分配
        // （本地库逐本匹配要过成千上万行候选，这个顺序是热路径）。三者都是纯判定，换序不改语义。
        return min >= MIN_CONTAINS_LEN && min * 2 >= max
                && (s1.contains(s2) || s2.contains(s1))
                && !isSequel(s1, s2);
    }

    /**
     * 续作关系判定：两串去掉末尾「续作标记」（2、第二部、後編、下巻、続 等）后核心相等、
     * 且原始不等，即视为「同一系列的不同部/卷」——不判包含，交给 AI 或直接判 NO_MATCH。
     * <p>核心长度须 ≥ {@link #MIN_CONTAINS_LEN}，避免极短标题（如单独的「2」、展会编号 C95）
     * 被误判。
     */
    public static boolean isSequel(String s1, String s2) {
        if (s1 == null || s2 == null || s1.equals(s2)) {
            return false;
        }
        String b1 = stripSequelSuffix(s1);
        String b2 = stripSequelSuffix(s2);
        return b1.length() >= MIN_CONTAINS_LEN && b1.equals(b2);
    }

    /** 续作词后缀（去分隔符后、已大写）。纯数字卷号另行剥离。 */
    private static final String[] SEQUEL_WORD_SUFFIXES = {
            "第二部", "第三部", "第四部", "第五部",
            "前編", "後編", "続編", "外伝", "番外編", "続",
            "上巻", "中巻", "下巻",
            "PART2", "PART3", "PART4",
            "2ND", "3RD", "4TH", "5TH",
    };

    /** 去掉末尾续作标记：先匹配无歧义续作词，再剥离末尾 1~2 位数字卷号。 */
    private static String stripSequelSuffix(String s) {
        for (String suf : SEQUEL_WORD_SUFFIXES) {
            if (s.endsWith(suf) && s.length() > suf.length()) {
                return s.substring(0, s.length() - suf.length());
            }
        }
        int i = s.length();
        while (i > 0 && Character.isDigit(s.charAt(i - 1))) {
            i--;
        }
        int digits = s.length() - i;
        if (digits >= 1 && digits <= 2 && i > 0) {
            return s.substring(0, i);
        }
        return s;
    }

    /** 去掉分隔符与空白，只留字母数字（含 CJK），用于「包含」比较。 */
    static String stripSeparators(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
