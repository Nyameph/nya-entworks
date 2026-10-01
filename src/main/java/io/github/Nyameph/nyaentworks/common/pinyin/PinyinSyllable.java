package io.github.Nyameph.nyaentworks.common.pinyin;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个汉字读音的完整分解：声母 / 韵母 / 声调 / 韵身 / 三套韵部。
 *
 * <p>由 {@link #parse(String)} 从带声调的拼音（pinyin-data 口径，如 {@code zhōng}）一次性
 * 拆出，record 不可变。韵部覆盖三套现代普通话押韵体系：
 * <ul>
 *   <li><b>十三辙</b>（曲艺十三辙，13 辙）</li>
 *   <li><b>十四韵</b>（《中华新韵》2005，14 韵部）</li>
 *   <li><b>十八韵</b>（《中华新韵》1941 黎锦熙，18 韵部）</li>
 * </ul>
 *
 * <p>押韵比较以「韵身」（去介音的韵母）为纲：同一韵身在某一韵部体系下归同一韵部，跨体系时用
 * 对应的 {@link #zhe13()} / {@link #yun14()} / {@link #yun18()} 值比较。例：{@code zhōng}
 * 韵母 {@code ong}、韵身 {@code ong}，属「中东 / 十一庚 / 十八东」；{@code huā} 韵母 {@code ua}、
 * 韵身 {@code a}，属「发花 / 一麻 / 一麻」。
 *
 * <p><b>边界</b>：个别非标准拼写（如语气词 {@code 哟=yo}、叹词 {@code 嗯=ń/ň/ǹ}）的韵母不在
 * 韵母表里，{@link #parse(String)} 会返回 {@code null}，由调用方跳过。
 */
public record PinyinSyllable(
        String pinyin,     // 带声调，如 "zhōng"
        String toneLess,   // 无声调，如 "zhong"（保留 ü）
        int tone,          // 1=阴平 2=阳平 3=上声 4=去声，0=轻声
        String initial,    // 声母，如 "zh"；零声母为 ""
        String finals,     // 韵母（含介音），如 "ong"；舌尖元音写作 "-i"
        String rhymeBody,  // 韵身（去介音），如 "ong"
        String zhe13,      // 十三辙名
        String yun14,      // 十四韵名
        String yun18) {    // 十八韵名

    /** 21 个声母里的 18 个单字声母（zh/ch/sh 单独判）。不含 y/w（零声母拼写标记）。 */
    private static final String SINGLE_INITIALS = "bpmfdtnlgkhjqxrzcs";

    /**
     * 韵母 → 韵身（去介音）。约 40 个韵母，含舌尖元音 {@code -i}；{@code iu / ui / un} 这三个**缩写**
     * 与全拼 {@code iou / uei / uen} 并列收着（字典写缩写，全拼只从零声母拼写走到）。
     */
    private static final Map<String, String> RHYME_BODY = Map.ofEntries(
            Map.entry("a", "a"), Map.entry("o", "o"), Map.entry("e", "e"), Map.entry("ê", "ê"),
            Map.entry("ai", "ai"), Map.entry("ei", "ei"), Map.entry("ao", "ao"), Map.entry("ou", "ou"),
            Map.entry("an", "an"), Map.entry("en", "en"), Map.entry("ang", "ang"), Map.entry("eng", "eng"),
            Map.entry("er", "er"), Map.entry("i", "i"), Map.entry("ia", "a"), Map.entry("ie", "ê"),
            Map.entry("iao", "ao"), Map.entry("iou", "ou"), Map.entry("iu", "ou"),
            Map.entry("ian", "an"), Map.entry("in", "en"),
            Map.entry("iang", "ang"), Map.entry("ing", "eng"), Map.entry("iong", "ong"),
            Map.entry("u", "u"), Map.entry("ua", "a"), Map.entry("uo", "o"), Map.entry("uai", "ai"),
            // iou / uei / uen 的**缩写**（iu / ui / un）也要收：字典写的就是缩写（shuǐ、lùn、liù），
            // 全拼只经零声母的 you / wei / wen 走到 —— 少了缩写，这些字的韵身取不到，
            // {@link #parse} 会把整个读音判成「非标准」丢掉（水 / 论 / 六 / 会 / 春 全都拆不出）。
            Map.entry("uei", "ei"), Map.entry("ui", "ei"),
            Map.entry("uan", "an"), Map.entry("uen", "en"), Map.entry("un", "en"),
            Map.entry("uang", "ang"), Map.entry("ueng", "eng"), Map.entry("ong", "ong"),
            Map.entry("ü", "ü"), Map.entry("üe", "ê"), Map.entry("üan", "an"), Map.entry("ün", "en"),
            Map.entry("-i", "-i"));

    /** 韵身 → 十三辙名。 */
    private static final Map<String, String> ZHE13 = Map.ofEntries(
            Map.entry("a", "发花"), Map.entry("o", "梭波"), Map.entry("e", "梭波"), Map.entry("ê", "乜斜"),
            Map.entry("i", "一七"), Map.entry("-i", "一七"), Map.entry("u", "姑苏"), Map.entry("ü", "一七"),
            Map.entry("er", "一七"), Map.entry("ai", "怀来"), Map.entry("ei", "灰堆"), Map.entry("ao", "遥条"),
            Map.entry("ou", "由求"), Map.entry("an", "言前"), Map.entry("en", "人辰"), Map.entry("ang", "江阳"),
            Map.entry("eng", "中东"), Map.entry("ong", "中东"));

    /** 韵身 → 十四韵名（《中华新韵》2005）。 */
    private static final Map<String, String> YUN14 = Map.ofEntries(
            Map.entry("a", "一麻"), Map.entry("o", "二波"), Map.entry("e", "二波"), Map.entry("ê", "三皆"),
            Map.entry("ai", "四开"), Map.entry("ei", "五微"), Map.entry("ao", "六豪"), Map.entry("ou", "七尤"),
            Map.entry("an", "八寒"), Map.entry("en", "九文"), Map.entry("ang", "十唐"), Map.entry("eng", "十一庚"),
            Map.entry("ong", "十一庚"), Map.entry("i", "十二齐"), Map.entry("er", "十二齐"), Map.entry("ü", "十二齐"),
            Map.entry("-i", "十三支"), Map.entry("u", "十四姑"));

    /** 韵身 → 十八韵名（《中华新韵》1941）。 */
    private static final Map<String, String> YUN18 = Map.ofEntries(
            Map.entry("a", "一麻"), Map.entry("o", "二波"), Map.entry("e", "三歌"), Map.entry("ê", "四皆"),
            Map.entry("-i", "五支"), Map.entry("er", "六儿"), Map.entry("i", "七齐"), Map.entry("ei", "八微"),
            Map.entry("ai", "九开"), Map.entry("u", "十姑"), Map.entry("ü", "十一鱼"), Map.entry("ou", "十二侯"),
            Map.entry("ao", "十三豪"), Map.entry("an", "十四寒"), Map.entry("en", "十五痕"), Map.entry("ang", "十六唐"),
            Map.entry("eng", "十七庚"), Map.entry("ong", "十八东"));

    /** 零声母 y/w 拼写 → 规范韵母。其余（a/o/e/ê/ai/… 直接以元音开头）原样返回。 */
    private static final Map<String, String> ZERO_INITIAL_FINAL = Map.ofEntries(
            Map.entry("yi", "i"), Map.entry("ya", "ia"), Map.entry("ye", "ie"),
            Map.entry("yao", "iao"), Map.entry("you", "iou"), Map.entry("yan", "ian"),
            Map.entry("yin", "in"), Map.entry("yang", "iang"), Map.entry("ying", "ing"),
            Map.entry("yong", "iong"), Map.entry("yu", "ü"), Map.entry("yue", "üe"),
            Map.entry("yuan", "üan"), Map.entry("yun", "ün"),
            Map.entry("wu", "u"), Map.entry("wa", "ua"), Map.entry("wo", "uo"),
            Map.entry("wai", "uai"), Map.entry("wei", "uei"), Map.entry("wan", "uan"),
            Map.entry("wen", "uen"), Map.entry("wang", "uang"), Map.entry("weng", "ueng"));

    /**
     * 把一个拼音串**归一成无声调音节的比较键**：转小写、{@code ü} 一族写成 {@code v}、剥掉声调
     * 附标，只留 {@code a-z}。空 / {@code null} 返回空串。
     *
     * <p>这是「两个读音是不是同一个音节」的**唯一口径** —— 字典写 {@code lǜ}、svp 音符常写
     * {@code lu} 或 {@code lv}，不归一就没法比。与 {@link #parse(String)} 的区别：{@code parse}
     * 拆声母韵母、拆不出返回 {@code null}；本方法只做字符串归一，**永远给得出结果**，
     * 所以「拆不出的怪拼写」（如叹词 {@code hng}）照样能参与比较。
     *
     * <p>用 {@code song.fill.PinyinLyricMatcher#normalize} 的口径实现，那边已改为委托本方法，
     * 两处不会再漂移。
     */
    public static String toneless(String pinyin) {
        if (pinyin == null) {
            return "";
        }
        String s = pinyin.toLowerCase()
                .replace('ü', 'v').replace('ǖ', 'v').replace('ǘ', 'v')
                .replace('ǚ', 'v').replace('ǜ', 'v');
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return s.replaceAll("[^a-z]", "");
    }

    /**
     * 从带声调拼音拆出一个读音的完整分解；拆不出（空串、非标准拼写、韵母不在表里）返回 {@code null}。
     */
    public static PinyinSyllable parse(String pinyin) {
        if (pinyin == null || pinyin.isEmpty()) {
            return null;
        }
        String s = pinyin.toLowerCase();

        // 1. 剥声调、得声调号与无声调形式
        StringBuilder sb = new StringBuilder(s.length());
        int tone = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int t = toneOf(c);
            if (t > 0) {
                tone = t;
                sb.append(baseOf(c));
            } else {
                sb.append(c);
            }
        }
        String toneLess = sb.toString();
        if (!allLowerAlpha(toneLess)) {
            return null; // 含非拼音字符（如 ḿ/ń 等叹词、组合附标）
        }

        // 2. 分声母 / 韵母
        String initial;
        String fin;
        if (toneLess.length() >= 2 && isDoubleInitial(toneLess.charAt(0), toneLess.charAt(1))) {
            initial = toneLess.substring(0, 2);
            fin = toneLess.substring(2);
        } else if (SINGLE_INITIALS.indexOf(toneLess.charAt(0)) >= 0) {
            initial = toneLess.substring(0, 1);
            fin = toneLess.substring(1);
        } else {
            initial = "";
            fin = zeroInitialFinal(toneLess);
        }

        // j/q/x 后的 u 实为 ü
        if (initial.length() == 1 && (initial.charAt(0) == 'j' || initial.charAt(0) == 'q'
                || initial.charAt(0) == 'x')) {
            fin = switch (fin) {
                case "u" -> "ü";
                case "ue" -> "üe";
                case "uan" -> "üan";
                case "un" -> "ün";
                default -> fin;
            };
        }
        // 舌尖声母后的 i 是舌尖元音（区别于齐齿 i）
        if (fin.equals("i") && isApicalInitial(initial)) {
            fin = "-i";
        }

        // 3. 韵身与韵部
        String rhymeBody = RHYME_BODY.get(fin);
        if (rhymeBody == null) {
            return null; // 非标准韵母（如 yo 的 io、叹词的 m/n）—— 不参与押韵，跳过
        }
        return new PinyinSyllable(pinyin, toneLess, tone, initial, fin, rhymeBody,
                ZHE13.get(rhymeBody), YUN14.get(rhymeBody), YUN18.get(rhymeBody));
    }

    private static int toneOf(char c) {
        return switch (c) {
            case 'ā', 'ē', 'ī', 'ō', 'ū', 'ǖ' -> 1;
            case 'á', 'é', 'í', 'ó', 'ú', 'ǘ' -> 2;
            case 'ǎ', 'ě', 'ǐ', 'ǒ', 'ǔ', 'ǚ' -> 3;
            case 'à', 'è', 'ì', 'ò', 'ù', 'ǜ' -> 4;
            default -> 0;
        };
    }

    private static char baseOf(char c) {
        return switch (c) {
            case 'ā', 'á', 'ǎ', 'à' -> 'a';
            case 'ē', 'é', 'ě', 'è' -> 'e';
            case 'ī', 'í', 'ǐ', 'ì' -> 'i';
            case 'ō', 'ó', 'ǒ', 'ò' -> 'o';
            case 'ū', 'ú', 'ǔ', 'ù' -> 'u';
            case 'ǖ', 'ǘ', 'ǚ', 'ǜ' -> 'ü';
            default -> c;
        };
    }

    /** 是否 zh/ch/sh 这类双字声母。 */
    private static boolean isDoubleInitial(char c0, char c1) {
        return (c0 == 'z' || c0 == 'c' || c0 == 's') && c1 == 'h';
    }

    /** 是否 z/c/s/zh/ch/sh/r（这些声母后的 i 是舌尖元音）。 */
    private static boolean isApicalInitial(String initial) {
        return switch (initial) {
            case "z", "c", "s", "zh", "ch", "sh", "r" -> true;
            default -> false;
        };
    }

    private static String zeroInitialFinal(String s) {
        return ZERO_INITIAL_FINAL.getOrDefault(s, s);
    }

    private static boolean allLowerAlpha(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= 'a' && c <= 'z') || c == 'ü' || c == 'ê')) {
                return false;
            }
        }
        return true;
    }

    // ==================== 韵部总表（十八韵 18 条聚合） ====================

    /**
     * 一条韵部：韵身 + 三套体系名 + 该韵部包含的全部韵母 + 一个代表字。
     * 由 {@link #table()} 聚合产出，供韵脚词典页的「韵部总表」与前端下拉用。
     *
     * @param body   韵身（去介音），18 个值之一
     * @param yun18  十八韵名，如 十六唐
     * @param zhe13  十三辙名，如 江阳
     * @param yun14  十四韵名，如 十唐
     * @param finals 该韵部包含的全部韵母（含介音，舌尖元音写作 -i），按 {@link #RHYME_BODY} 的字典序
     * @param sample 代表字（写死的一个常用字，只供展示）
     */
    public record Rhyme(String body, String yun18, String zhe13, String yun14,
                        List<String> finals, String sample) {
    }

    /** 十八韵名的固定次序（一麻 → 十八东）。{@link #table()} 按它排序。 */
    private static final List<String> YUN18_ORDER = List.of(
            "一麻", "二波", "三歌", "四皆", "五支", "六儿", "七齐", "八微", "九开",
            "十姑", "十一鱼", "十二侯", "十三豪", "十四寒", "十五痕", "十六唐", "十七庚", "十八东");

    /** 韵身 → 代表字（写死的常用字，只供展示）。 */
    private static final Map<String, String> SAMPLE_CHAR = Map.ofEntries(
            Map.entry("a", "发"), Map.entry("o", "坡"), Map.entry("e", "车"), Map.entry("ê", "月"),
            Map.entry("i", "衣"), Map.entry("-i", "知"), Map.entry("u", "书"), Map.entry("ü", "鱼"),
            Map.entry("er", "而"), Map.entry("ai", "海"), Map.entry("ei", "飞"), Map.entry("ao", "高"),
            Map.entry("ou", "口"), Map.entry("an", "山"), Map.entry("en", "恩"), Map.entry("ang", "江"),
            Map.entry("eng", "星"), Map.entry("ong", "东"));

    /**
     * 十八韵总表：18 条，按十八韵序号（一…十八）排。由 {@link #RHYME_BODY} 反查聚合
     * （韵母 → 韵身），不另写映射表 —— 韵身 ↔ 十八韵是一一对应，加列只会多两处要维护。
     */
    public static List<Rhyme> table() {
        Map<String, List<String>> finalsByBody = new LinkedHashMap<>();
        RHYME_BODY.forEach((fin, body) ->
                finalsByBody.computeIfAbsent(body, k -> new ArrayList<>()).add(fin));
        // YUN18 是 韵身→名，这里要反查（名→韵身）
        Map<String, String> bodyByName = new LinkedHashMap<>();
        YUN18.forEach((body, name) -> bodyByName.put(name, body));
        List<Rhyme> out = new ArrayList<>(YUN18_ORDER.size());
        for (String yun18 : YUN18_ORDER) {
            String body = bodyByName.get(yun18);
            List<String> finals = finalsByBody.getOrDefault(body, List.of());
            out.add(new Rhyme(body, yun18, ZHE13.get(body), YUN14.get(body),
                    List.copyOf(finals), SAMPLE_CHAR.get(body)));
        }
        return List.copyOf(out);
    }
}
