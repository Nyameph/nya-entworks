package io.github.Nyameph.nyaentworks.common.pinyin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * 汉字 → 拼音通用工具。字典存本地（classpath 资源，数据源
 * <a href="https://github.com/mozillazg/pinyin-data">mozillazg/pinyin-data</a>），
 * 运行期懒加载后常驻内存，不依赖外挂盘 / 数据库。
 *
 * <p>两套字典：
 * <ul>
 *   <li><b>合并字典</b> {@code pinyin/pinyin.txt}（较新较全，多来源合并）——{@link #pinyin(int)} /
 *       {@link #readings(int)} 用它；读音多、带古音/异体音，适合「任一读音命中即可」的匹配场景。</li>
 *   <li><b>现代标准字典</b> {@code pinyin/kTGHZ2013.txt}（《通用规范汉字字典》2013，通用规范汉字
 *       的现代普通话读音，无古音/异体音）——{@link #modernPinyin(int)} / {@link #forEachModern}
 *       用它；适合「按现代读音判多音字」等要求干净读音的场景（古音会误判多音字，如「母」在合并
 *       字典里带 wǔ、「市」带 fú）。</li>
 * </ul>
 *
 * <p>两个层次（均基于合并字典）：
 * <ul>
 *   <li>{@link #pinyin(int)} —— 该字的<b>全部带声调读音</b>（原始串，未分解），多音字返回多条；
 *       供拼音匹配等「只看读音、不要分解」的场景（如 {@code song.fill.PinyinLyricMatcher}）。</li>
 *   <li>{@link #readings(int)} —— 全部读音的<b>完整分解</b>（{@link PinyinSyllable}：声母/韵母/
 *       声调/韵身/十三辙/十四韵/十八韵），为押韵等场景铺路；个别非标准读音拆不出会被跳过。</li>
 *   <li>{@link #toPinyin(String)} —— <b>文本 → 无声调拼音</b>（汉字逐字转、多音字取常用读音、
 *       其余字符原样），填词页「导出拼音」用它。</li>
 * </ul>
 *
 * <p><b>字级与读音常用度</b>（多音字提醒分级、韵脚候选排序用）：
 * <ul>
 *   <li>{@code pinyin/hanzi_tier.txt} —— 《通用规范汉字表》（2013）的一/二/三级，走 {@link #tier(int)}。</li>
 *   <li>{@code pinyin/reading_rare.txt} + {@code pinyin/reading_common.txt} —— <b>人工维护</b>的读音修正表
 *       （文件头各有完整口径）。常用读音 = kTGHZ2013 的读音 − 前者 + 后者；只出现在合并字典里的
 *       古音/异体音自动算生僻。入口是 {@link #commonSyllables(int)} / {@link #rareSyllables(int)}。</li>
 * </ul>
 */
public final class PinyinUtil {

    private static final String DICT_RESOURCE = "/pinyin/pinyin.txt";

    private static final String MODERN_DICT_RESOURCE = "/pinyin/kTGHZ2013.txt";

    /** 字级表（《通用规范汉字表》2013 的一/二/三级）。 */
    private static final String TIER_RESOURCE = "/pinyin/hanzi_tier.txt";

    /** 生僻读音表（人工维护，见文件头）。把「词典收了但实际很冷门」的读音挑出来。 */
    private static final String RARE_READING_RESOURCE = "/pinyin/reading_rare.txt";

    /** 补录常用读音表（人工维护，见文件头）。词典里没有、但实际会用的读音。 */
    private static final String COMMON_READING_RESOURCE = "/pinyin/reading_common.txt";

    /** 码点 → 全部带声调读音（不可变，合并字典）。懒加载，线程安全的 double-checked 初始化。 */
    private static volatile Map<Integer, List<String>> DICT;

    /** 码点 → 现代标准读音（不可变，kTGHZ2013）。懒加载，线程安全的 double-checked 初始化。 */
    private static volatile Map<Integer, List<String>> MODERN_DICT;

    /** 码点 → 字级（1 一级 / 2 二级 / 3 三级）。不在表内的字查不到，由 {@link #tier} 兜 0。 */
    private static volatile Map<Integer, Integer> TIER;

    /** 码点 → 被降到「生僻」的音节（无声调，见 {@link PinyinSyllable#toneless}）。 */
    private static volatile Map<Integer, Set<String>> RARE_READINGS;

    /** 码点 → 补录进来的常用读音（文件原样，无声调）。词典里没有这些音，靠本表补。 */
    private static volatile Map<Integer, List<String>> COMMON_READINGS;

    private PinyinUtil() {
    }

    /** 是否汉字（含 CJK 扩展区，按 Unicode 脚本 HAN 判）。 */
    public static boolean isHanzi(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    /** 该字的全部带声调读音（原始串，未分解）。非汉字 / 无读音返回空列表。 */
    public static List<String> pinyin(int codePoint) {
        List<String> readings = dict().get(codePoint);
        return readings == null ? List.of() : readings;
    }

    /** BMP 内单字便利重载；超出 BMP 的汉字请用 {@link #pinyin(int)}。 */
    public static List<String> pinyin(char hanzi) {
        return pinyin((int) hanzi);
    }

    /** 该字的全部读音完整分解（多音字 → 多条）。拆不出的非标准读音跳过。 */
    public static List<PinyinSyllable> readings(int codePoint) {
        List<String> raw = pinyin(codePoint);
        if (raw.isEmpty()) {
            return List.of();
        }
        List<PinyinSyllable> out = new ArrayList<>(raw.size());
        for (String r : raw) {
            PinyinSyllable s = PinyinSyllable.parse(r);
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    /** BMP 内单字便利重载；超出 BMP 的汉字请用 {@link #readings(int)}。 */
    public static List<PinyinSyllable> readings(char hanzi) {
        return readings((int) hanzi);
    }

    /**
     * 遍历合并字典里全部「码点 → 带声调读音列表」条目，供需要反向建索引的场景使用。
     * 触发懒加载，之后常驻内存。带古音/异体音，见 {@link #modernPinyin}。
     */
    public static void forEach(BiConsumer<Integer, List<String>> action) {
        dict().forEach(action);
    }

    /** 该字在现代标准字典（kTGHZ2013，《通用规范汉字字典》）里的带声调读音；无读音返回空列表。 */
    public static List<String> modernPinyin(int codePoint) {
        List<String> readings = modernDict().get(codePoint);
        return readings == null ? List.of() : readings;
    }

    /** BMP 内单字便利重载；超出 BMP 的汉字请用 {@link #modernPinyin(int)}。 */
    public static List<String> modernPinyin(char hanzi) {
        return modernPinyin((int) hanzi);
    }

    /**
     * 遍历现代标准字典里全部「码点 → 带声调读音列表」条目，供按现代读音反向建索引（如判多音字）使用。
     * 触发懒加载，之后常驻内存。
     */
    public static void forEachModern(BiConsumer<Integer, List<String>> action) {
        modernDict().forEach(action);
    }

    // ==================== 字级 + 读音常用度分级 ====================
    //
    // 「一个字的哪个读音算常用」= 现代标准字典（kTGHZ2013）里的读音
    //                        − reading_rare.txt 降级的
    //                        + reading_common.txt 补录的
    // 只出现在合并字典里的古音／异体音自动算生僻（「他」的 tuó、「母」的 wú、「市」的 fú）。
    // 填词页的多音字提醒分级、以及将来韵脚词典的候选排序都走这一套。

    /** 该字的常用度等级：1 一级 / 2 二级 / 3 三级 / 0 不在《通用规范汉字表》里。 */
    public static int tier(int codePoint) {
        Integer t = tiers().get(codePoint);
        return t == null ? 0 : t;
    }

    /** BMP 内单字便利重载；超出 BMP 的汉字请用 {@link #tier(int)}。 */
    public static int tier(char hanzi) {
        return tier((int) hanzi);
    }

    /**
     * 该字的**全部**无声调音节，去重、稳定有序。三处的并集：合并字典 + 现代标准字典 +
     * {@code reading_common.txt} 补录的。
     *
     * <p>为什么要带上现代字典：两本字典**并不严格包含**（现代字典里有个别字合并字典没有，
     * 如 U+3D14）。只取合并字典的话，{@link #commonSyllables} 会算出「常用音不在全部读音里」，
     * 「常用 ∪ 生僻 = 全部」这条不变量就破了。
     */
    public static List<String> allSyllables(int codePoint) {
        Set<String> out = new LinkedHashSet<>();
        addToneless(out, pinyin(codePoint));
        addToneless(out, modernPinyin(codePoint));
        out.addAll(supplement(codePoint));
        return List.copyOf(out);
    }

    /**
     * 该字的**常用**无声调音节，去重、稳定有序 —— 「现代汉语／歌词里真的会这样唱」的那些。
     * 现代标准字典的读音减去 {@code reading_rare.txt} 里降级的，再加 {@code reading_common.txt}
     * 补录的。**可能为空**（如「戌」把唯一的冷门音降掉后），调用方要守。
     */
    public static List<String> commonSyllables(int codePoint) {
        Set<String> out = new LinkedHashSet<>();
        Set<String> rare = rareReadings().getOrDefault(codePoint, Set.of());
        for (String raw : modernPinyin(codePoint)) {
            String n = PinyinSyllable.toneless(raw);
            if (!n.isEmpty() && !rare.contains(n)) {
                out.add(n);
            }
        }
        out.addAll(supplement(codePoint));
        return List.copyOf(out);
    }

    /** 把一串带声调读音归一成无声调音节，逐个塞进目标集合（空的跳过）。 */
    private static void addToneless(Set<String> target, List<String> rawReadings) {
        for (String raw : rawReadings) {
            String n = PinyinSyllable.toneless(raw);
            if (!n.isEmpty()) {
                target.add(n);
            }
        }
    }

    /**
     * 该字的**生僻**无声调音节 = {@link #allSyllables} − {@link #commonSyllables}。
     * 即「要么是古音／异体音，要么是 {@code reading_rare.txt} 降下来的冷门现代音」。
     */
    public static List<String> rareSyllables(int codePoint) {
        Set<String> common = new LinkedHashSet<>(commonSyllables(codePoint));
        List<String> out = new ArrayList<>();
        for (String s : allSyllables(codePoint)) {
            if (!common.contains(s)) {
                out.add(s);
            }
        }
        return List.copyOf(out);
    }

    /** 该音节是不是这个字的常用读音。 */
    public static boolean isCommonSyllable(int codePoint, String syllable) {
        return commonSyllables(codePoint).contains(PinyinSyllable.toneless(syllable));
    }

    /**
     * 该字的全部读音按常用度排序（**常用的在前**），返回完整分解。合并字典 + 补录读音都要，
     * 因为韵脚场景要「全部读音平铺」。组内保持字典原顺序（稳定排序），拆不出的读音跳过。
     *
     * <p>分级数据缺失时退化为原顺序（排序键取不到就都算常用），**不抛异常** ——
     * 这是 §0.5 明确要求的降级路径，别让它变成硬依赖。
     */
    public static List<PinyinSyllable> readingsByFrequency(int codePoint) {
        Set<String> common = new LinkedHashSet<>(commonSyllables(codePoint));
        List<PinyinSyllable> out = new ArrayList<>();
        for (String raw : allRawReadings(codePoint)) {
            PinyinSyllable s = PinyinSyllable.parse(raw);
            if (s != null) {
                out.add(s);
            }
        }
        out.sort(Comparator.comparingInt(s -> common.contains(PinyinSyllable.toneless(s.toneLess())) ? 0 : 1));
        return out;
    }

    /** BMP 内单字便利重载；超出 BMP 的汉字请用 {@link #readingsByFrequency(int)}。 */
    public static List<PinyinSyllable> readingsByFrequency(char hanzi) {
        return readingsByFrequency((int) hanzi);
    }

    // ==================== 文本 → 拼音 ====================

    /**
     * 文本 → 拼音：**汉字**逐个换成无声调拼音，其余字符（ASCII 字母 / 数字 / 标点 / 空白）
     * 原样保留。
     *
     * <p><b>多音字取一个常用读音</b>（{@link #readingsByFrequency} 的第一个，即「常用读音」
     * 里字典排最前的那条；常用读音一个都没有时才退到生僻音）。调用方若知道该读哪个音
     * （填词页的多音字回填替换），先把字换好再走这里，不必自己判读音。
     *
     * <p>输出**无声调**：SynthV 一类演唱工具按音节注音、不吃声调；并且与工程里的拼音音符
     * 同一口径 —— ü 一族写作 {@code v}（「女」→ {@code nv}、「绿」→ {@code lv}、{@code nüè}
     * → {@code nve}），见 {@link PinyinSyllable#toneless}。
     *
     * <p>取不到读音的字（生僻字 / 非标准拼写如语气词 {@code 哟}）**原样保留** ——
     * 宁可留个汉字，也别让它变成空、把整句的词对位打乱。
     */
    public static String toPinyin(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (isHanzi(codePoint)) {
                String syllable = preferredSyllable(codePoint);
                out.append(syllable.isEmpty() ? new String(Character.toChars(codePoint)) : syllable);
            } else {
                out.appendCodePoint(codePoint);
            }
        }
        return out.toString();
    }

    /** 该字最常用的无声调音节（无声调形式）；一个读音都取不出返回空串。 */
    private static String preferredSyllable(int codePoint) {
        for (PinyinSyllable syllable : readingsByFrequency(codePoint)) {
            String toneless = PinyinSyllable.toneless(syllable.pinyin());
            if (!toneless.isEmpty()) {
                return toneless;
            }
        }
        return "";
    }

    /**
     * 遍历 {@code reading_common.txt} 补录过读音的字。这些字**不在合并字典里也照样有读音**，
     * 所以 {@link #forEach} 扫不到 —— 要按字建索引的场景得再走一遍这里（如
     * {@code song.fill.PolyphoneHint} 的多音字索引）。触发懒加载，之后常驻内存。
     */
    public static void forEachSupplement(BiConsumer<Integer, List<String>> action) {
        commonReadings().forEach(action);
    }

    /**
     * 该字的全部带声调读音：合并字典原样在前，补上现代字典里合并字典没有的、再接补录读音。
     * 与 {@link #allSyllables} 覆盖同一批音节，只是保留带声调写法与原始顺序。
     */
    private static List<String> allRawReadings(int codePoint) {
        List<String> out = new ArrayList<>(pinyin(codePoint));
        for (String raw : modernPinyin(codePoint)) {
            if (!out.contains(raw)) {
                out.add(raw);
            }
        }
        out.addAll(supplementRaw(codePoint));
        return out;
    }

    private static Map<Integer, List<String>> dict() {
        Map<Integer, List<String>> d = DICT;
        if (d == null) {
            synchronized (PinyinUtil.class) {
                d = DICT;
                if (d == null) {
                    d = load(DICT_RESOURCE);
                    DICT = d;
                }
            }
        }
        return d;
    }

    private static Map<Integer, List<String>> modernDict() {
        Map<Integer, List<String>> d = MODERN_DICT;
        if (d == null) {
            synchronized (PinyinUtil.class) {
                d = MODERN_DICT;
                if (d == null) {
                    d = load(MODERN_DICT_RESOURCE);
                    MODERN_DICT = d;
                }
            }
        }
        return d;
    }

    private static Map<Integer, Integer> tiers() {
        Map<Integer, Integer> t = TIER;
        if (t == null) {
            synchronized (PinyinUtil.class) {
                t = TIER;
                if (t == null) {
                    t = Collections.unmodifiableMap(loadTiers());
                    TIER = t;
                }
            }
        }
        return t;
    }

    private static Map<Integer, Set<String>> rareReadings() {
        Map<Integer, Set<String>> r = RARE_READINGS;
        if (r == null) {
            synchronized (PinyinUtil.class) {
                r = RARE_READINGS;
                if (r == null) {
                    r = Collections.unmodifiableMap(loadRareReadings());
                    RARE_READINGS = r;
                }
            }
        }
        return r;
    }

    private static Map<Integer, List<String>> commonReadings() {
        Map<Integer, List<String>> c = COMMON_READINGS;
        if (c == null) {
            synchronized (PinyinUtil.class) {
                c = COMMON_READINGS;
                if (c == null) {
                    c = Collections.unmodifiableMap(loadCommonReadings());
                    COMMON_READINGS = c;
                }
            }
        }
        return c;
    }

    /** 补录读音（文件原样）。没有返回空列表。 */
    private static List<String> supplementRaw(int codePoint) {
        List<String> list = commonReadings().get(codePoint);
        return list == null ? List.of() : list;
    }

    /** 补录读音归一成无声调音节（去空、去重）。 */
    private static Set<String> supplement(int codePoint) {
        Set<String> out = new LinkedHashSet<>();
        for (String raw : supplementRaw(codePoint)) {
            String n = PinyinSyllable.toneless(raw);
            if (!n.isEmpty()) {
                out.add(n);
            }
        }
        return out;
    }

    private static Map<Integer, List<String>> load(String resource) {
        Map<Integer, List<String>> dict = new HashMap<>();
        forEachLine(resource, line -> parseLine(line, dict));
        return Collections.unmodifiableMap(dict);
    }

    /**
     * 读 `hanzi_tier.txt`：每行 {@code U+XXXX<TAB>等级}。等级不是 1/2/3 的行跳过
     * （表头注释由 {@link #forEachLine} 滤掉）。
     */
    private static Map<Integer, Integer> loadTiers() {
        Map<Integer, Integer> tiers = new HashMap<>();
        forEachLine(TIER_RESOURCE, line -> {
            int tab = line.indexOf('\t');
            if (tab < 0) {
                tab = line.indexOf(' ');
            }
            if (tab < 0) {
                return;
            }
            Integer cp = parseCodePoint(line.substring(0, tab));
            if (cp == null) {
                return;
            }
            int t;
            try {
                t = Integer.parseInt(line.substring(tab + 1).trim());
            } catch (NumberFormatException e) {
                return;
            }
            if (t >= 1 && t <= 3) {
                tiers.put(cp, t);
            }
        });
        return tiers;
    }

    /**
     * 读 `reading_rare.txt`（格式与字典一样：{@code U+XXXX: 音节  # 汉字 · 备注}），
     * 音节归一成无声调形式并去重 —— 判定时两边都过 {@link PinyinSyllable#toneless}，写法不敏感。
     */
    private static Map<Integer, Set<String>> loadRareReadings() {
        Map<Integer, Set<String>> out = new HashMap<>();
        for (Map.Entry<Integer, List<String>> e : load(RARE_READING_RESOURCE).entrySet()) {
            Set<String> syllables = new LinkedHashSet<>();
            for (String raw : e.getValue()) {
                String n = PinyinSyllable.toneless(raw);
                if (!n.isEmpty()) {
                    syllables.add(n);
                }
            }
            if (!syllables.isEmpty()) {
                out.put(e.getKey(), Set.copyOf(syllables));
            }
        }
        return out;
    }

    /** 读 `reading_common.txt`，原样保留写法与顺序（要补进读音列表）。 */
    private static Map<Integer, List<String>> loadCommonReadings() {
        return load(COMMON_READING_RESOURCE);
    }

    /** 逐行读 classpath 文本资源，跳过空行与 {@code #} 注释行。资源缺失直接抛（配置错误，越早越好）。 */
    private static void forEachLine(String resource, java.util.function.Consumer<String> action) {
        try (InputStream in = PinyinUtil.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("拼音资源不存在：" + resource);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && trimmed.charAt(0) != '#') {
                    action.accept(trimmed);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取拼音资源失败：" + resource, e);
        }
    }

    /** 解析 {@code U+XXXX}；不合法返回 {@code null}。 */
    private static Integer parseCodePoint(String token) {
        String s = token.trim();
        if (!s.startsWith("U+") && !s.startsWith("u+")) {
            return null;
        }
        try {
            return Integer.parseInt(s.substring(2), 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 解析一行 {@code U+4E2D: zhōng,zhòng  # 中}；注释行 / 空行 / 格式异常跳过。
     *
     * <p>同一个字出现多行时**累加**而不是覆盖 —— 读音修正表（{@code reading_rare.txt} 等）
     * 为了「一行一个读音、各带自己的备注」会把一个字拆成好几行（如「单」chán / shàn），
     * 覆盖的话只会剩下最后一行。两本字典里每个字都只出现一次，累加对它们没有影响。
     */
    private static void parseLine(String line, Map<Integer, List<String>> dict) {
        if (line.isEmpty() || line.charAt(0) == '#') {
            return;
        }
        int colon = line.indexOf(':');
        if (colon < 0) {
            return;
        }
        Integer codePoint = parseCodePoint(line.substring(0, colon));
        if (codePoint == null) {
            return;
        }
        String readingsPart = line.substring(colon + 1);
        int hash = readingsPart.indexOf('#');
        if (hash >= 0) {
            readingsPart = readingsPart.substring(0, hash);
        }
        List<String> readings = new ArrayList<>(dict.getOrDefault(codePoint, List.of()));
        for (String p : readingsPart.split(",")) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty() && !readings.contains(trimmed)) {
                readings.add(trimmed);
            }
        }
        if (!readings.isEmpty()) {
            dict.put(codePoint, Collections.unmodifiableList(readings));
        }
    }
}
