package io.github.Nyameph.nyaentworks.manga.dict;

import org.apache.commons.lang3.StringUtils;
import io.github.Nyameph.nyaentworks.manga.consts.MangaDictMatchMode;
import io.github.Nyameph.nyaentworks.manga.consts.MangaDictType;
import io.github.Nyameph.nyaentworks.manga.entity.MangaDictEntry;
import io.github.Nyameph.nyaentworks.manga.util.MangaTextUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 词典快照：不可变，构建时完成索引与正则编译，之后只读。
 * <p>解析过程只读快照、不查库，因此全盘扫描时零 IO；
 * 同时保证一次扫描任务全程使用同一判定标准——即使中途用户改了词典。
 * <p>{@link #getVersion()} 用于「预演 → 执行」之间的一致性校验：
 * 预演结果记录当时的版本，执行前比对，不一致说明词典已变更，需重新预演。
 */
public final class MangaDictionary {

    /** 空词典，供不依赖词典的场景（如仅调用 static 工具方法）使用 */
    public static final MangaDictionary EMPTY = from(List.of(), 0L);

    private final long version;

    /** EXACT + ignoreCase 的词条，值已转小写，O(1) 查找 */
    private final Map<MangaDictType, Set<String>> exactIgnoreCase;
    /** EXACT + 大小写敏感的词条 */
    private final Map<MangaDictType, Set<String>> exactCaseSensitive;
    /** PREFIX / SUFFIX / CONTAINS 词条，按类型分组 */
    private final Map<MangaDictType, List<AffixEntry>> affixes;
    /** REGEX 词条合并编译后的 Pattern，缺失表示该类型无该大小写策略的正则词条 */
    private final Map<MangaDictType, Pattern> mergedPatterns;
    /** 同上，但带 {@link Pattern#CASE_INSENSITIVE} */
    private final Map<MangaDictType, Pattern> mergedPatternsIgnoreCase;
    /** 构建时编译失败、已被跳过的正则词条 */
    private final List<String> invalidRegexValues;

    /** 前缀/后缀/包含类词条 */
    private record AffixEntry(String value, MangaDictMatchMode mode, boolean ignoreCase) {
    }

    /**
     * 按 Unicode NFC 归一，见 {@link MangaTextUtil#nfc}。
     * <p>归档别名（{@code manga_archive_name.name}）也走这一套，故实现放在
     * {@link MangaTextUtil}，两侧共用。
     */
    private static String nfc(String s) {
        return MangaTextUtil.nfc(s);
    }

    private MangaDictionary(long version,
                            Map<MangaDictType, Set<String>> exactIgnoreCase,
                            Map<MangaDictType, Set<String>> exactCaseSensitive,
                            Map<MangaDictType, List<AffixEntry>> affixes,
                            Map<MangaDictType, Pattern> mergedPatterns,
                            Map<MangaDictType, Pattern> mergedPatternsIgnoreCase,
                            List<String> invalidRegexValues) {
        this.version = version;
        this.exactIgnoreCase = exactIgnoreCase;
        this.exactCaseSensitive = exactCaseSensitive;
        this.affixes = affixes;
        this.mergedPatterns = mergedPatterns;
        this.mergedPatternsIgnoreCase = mergedPatternsIgnoreCase;
        this.invalidRegexValues = invalidRegexValues;
    }

    public long getVersion() {
        return version;
    }

    /**
     * 由词条列表构建快照。
     * <p>REGEX 词条逐条编译校验后再合并 join，单条语法错误不会连坐整个类型：
     * 编译失败的条目被跳过并记入 {@link #getInvalidRegexValues()}。
     */
    public static MangaDictionary from(Collection<MangaDictEntry> entries, long version) {
        Map<MangaDictType, Set<String>> exactIgnore = new EnumMap<>(MangaDictType.class);
        Map<MangaDictType, Set<String>> exactSensitive = new EnumMap<>(MangaDictType.class);
        Map<MangaDictType, List<AffixEntry>> affixMap = new EnumMap<>(MangaDictType.class);
        Map<MangaDictType, List<String>> regexMap = new EnumMap<>(MangaDictType.class);
        Map<MangaDictType, List<String>> regexIgnoreCaseMap = new EnumMap<>(MangaDictType.class);
        List<String> invalidRegex = new ArrayList<>();

        for (MangaDictEntry entry : entries) {
            MangaDictType type = entry.getDictType();
            String value = nfc(entry.getDictValue());
            if (type == null || StringUtils.isBlank(value)) {
                continue;
            }
            MangaDictMatchMode mode = entry.effectiveMatchMode();
            boolean ignoreCase = entry.effectiveIgnoreCase();

            switch (mode) {
                case EXACT -> {
                    if (ignoreCase) {
                        exactIgnore.computeIfAbsent(type, k -> new HashSet<>())
                                .add(value.toLowerCase());
                    } else {
                        exactSensitive.computeIfAbsent(type, k -> new HashSet<>()).add(value);
                    }
                }
                case PREFIX, SUFFIX, CONTAINS -> affixMap.computeIfAbsent(type, k -> new ArrayList<>())
                        .add(new AffixEntry(value, mode, ignoreCase));
                case REGEX -> {
                    // 逐条编译校验，避免一条语法错误导致整个类型的正则失效
                    try {
                        Pattern.compile(value);
                        (ignoreCase ? regexIgnoreCaseMap : regexMap)
                                .computeIfAbsent(type, k -> new ArrayList<>()).add(value);
                    } catch (Exception e) {
                        invalidRegex.add(value);
                    }
                }
            }
        }

        Map<MangaDictType, Pattern> patterns = mergeRegex(regexMap, 0);
        Map<MangaDictType, Pattern> patternsIgnoreCase = mergeRegex(regexIgnoreCaseMap, Pattern.CASE_INSENSITIVE);

        return new MangaDictionary(version, exactIgnore, exactSensitive, affixMap, patterns,
                patternsIgnoreCase, List.copyOf(invalidRegex));
    }

    /**
     * 把同类型的正则 join 成一条，整体加锚点。
     * <p>每条词条自己不带锚点：库里存 {@code C\d+} 而非 {@code ^C\d+$}，
     * 语义统一为「整体匹配」。
     */
    private static Map<MangaDictType, Pattern> mergeRegex(Map<MangaDictType, List<String>> source, int flags) {
        Map<MangaDictType, Pattern> result = new EnumMap<>(MangaDictType.class);
        source.forEach((type, list) ->
                result.put(type, Pattern.compile("^(?:" + StringUtils.join(list, "|") + ")$", flags)));
        return result;
    }

    /** 构建时编译失败、已被跳过的正则词条，供管理页提示 */
    public List<String> getInvalidRegexValues() {
        return invalidRegexValues;
    }

    // ------------------------------------------------------------------
    // 匹配入口
    // ------------------------------------------------------------------

    /**
     * 判断 {@code text} 是否命中该类型的任一词条，
     * 各词条按其自身的 {@code matchMode} 与 {@code ignoreCase} 判定。
     */
    public boolean matches(MangaDictType type, String rawText) {
        if (StringUtils.isEmpty(rawText)) {
            return false;
        }
        String text = nfc(rawText);
        Set<String> ignoreSet = exactIgnoreCase.get(type);
        if (ignoreSet != null && ignoreSet.contains(text.toLowerCase())) {
            return true;
        }
        Set<String> sensitiveSet = exactCaseSensitive.get(type);
        if (sensitiveSet != null && sensitiveSet.contains(text)) {
            return true;
        }
        List<AffixEntry> list = affixes.get(type);
        if (list != null) {
            for (AffixEntry e : list) {
                if (matchAffix(text, e)) {
                    return true;
                }
            }
        }
        Pattern pattern = mergedPatterns.get(type);
        if (pattern != null && pattern.matcher(text).matches()) {
            return true;
        }
        Pattern patternIgnoreCase = mergedPatternsIgnoreCase.get(type);
        return patternIgnoreCase != null && patternIgnoreCase.matcher(text).matches();
    }

    /**
     * 单条词条是否命中 {@code rawText}，与 {@link #matches} 同一套判定规则。
     * <p>批量匹配走上面那些索引，本方法是给词典管理页的重叠检测用的：合并后的
     * Pattern 只能回答「命中了没有」，页面要说出<b>哪一条</b>已能匹配新词。
     * <p>REGEX 会当场编译，语法错误按「不命中」处理 —— 那种词条本来就已经
     * 静默失效（见 {@link #getInvalidRegexValues()}）。
     */
    public static boolean matchesEntry(MangaDictEntry entry, String rawText) {
        if (entry == null || StringUtils.isEmpty(rawText) || StringUtils.isBlank(entry.getDictValue())) {
            return false;
        }
        String text = nfc(rawText);
        String value = nfc(entry.getDictValue());
        boolean ignoreCase = entry.effectiveIgnoreCase();
        return switch (entry.effectiveMatchMode()) {
            case EXACT -> ignoreCase ? text.equalsIgnoreCase(value) : text.equals(value);
            case PREFIX, SUFFIX, CONTAINS -> matchAffix(text,
                    new AffixEntry(value, entry.effectiveMatchMode(), ignoreCase));
            case REGEX -> {
                try {
                    // 与 mergeRegex 一致：词条自身不带锚点，语义是整体匹配
                    yield Pattern.compile("^(?:" + value + ")$",
                            ignoreCase ? Pattern.CASE_INSENSITIVE : 0).matcher(text).matches();
                } catch (Exception e) {
                    yield false;
                }
            }
        };
    }

    private static boolean matchAffix(String text, AffixEntry e) {
        return switch (e.mode()) {
            case PREFIX -> e.ignoreCase()
                    ? StringUtils.startsWithIgnoreCase(text, e.value())
                    : text.startsWith(e.value());
            case SUFFIX -> e.ignoreCase()
                    ? StringUtils.endsWithIgnoreCase(text, e.value())
                    : text.endsWith(e.value());
            case CONTAINS -> e.ignoreCase()
                    ? StringUtils.containsIgnoreCase(text, e.value())
                    : text.contains(e.value());
            default -> false;
        };
    }

    /**
     * 判断 {@code text} 本身、或其按 {@code ", "} 与 {@code "、"} 拆分后的任一段是否命中。
     * <p>对应原 {@code findNodeByDictionary} 中对多原作合写（如
     * {@code (FateGrand Order、Fate stay night)}）的处理。
     */
    public boolean matchesAnySegment(MangaDictType type, String rawText) {
        if (StringUtils.isEmpty(rawText)) {
            return false;
        }
        // matches 内部会再归一一次，这里归一是为了让 split 的分隔符判断也基于 NFC
        String text = nfc(rawText);
        if (matches(type, text.trim())) {
            return true;
        }
        for (String part : text.split(", ")) {
            if (matches(type, part.trim())) {
                return true;
            }
            for (String sub : part.split("、")) {
                if (matches(type, sub.trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 该类型是否存在任一词条 */
    public boolean hasEntry(MangaDictType type) {
        return exactIgnoreCase.containsKey(type)
                || exactCaseSensitive.containsKey(type)
                || affixes.containsKey(type)
                || mergedPatterns.containsKey(type)
                || mergedPatternsIgnoreCase.containsKey(type);
    }
}
