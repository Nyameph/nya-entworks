package io.github.Nyameph.nyaentworks.manga.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import io.github.Nyameph.nyaentworks.common.db.SqlDialect;
import io.github.Nyameph.nyaentworks.manga.config.MangaProperties;
import io.github.Nyameph.nyaentworks.manga.consts.MangaDictMatchMode;
import io.github.Nyameph.nyaentworks.manga.consts.MangaDictType;
import io.github.Nyameph.nyaentworks.manga.dict.MangaDictionary;
import io.github.Nyameph.nyaentworks.manga.entity.MangaDictEntry;
import io.github.Nyameph.nyaentworks.manga.mapper.MangaDictEntryMapper;
import io.github.Nyameph.nyaentworks.manga.util.MangaTextUtil;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 词典的加载与热更新。
 * <p>读取端无锁（取一次 volatile 引用），写入端串行化并在写后重建快照。
 * 词条总量千级，全量重建耗时可忽略，不做增量。
 */
@Service
@RequiredArgsConstructor
public class MangaDictService {

    private final MangaDictEntryMapper mapper;

    /**
     * 只用来读模块总开关。
     *
     * <p>本类<b>没有</b>标 {@link io.github.Nyameph.nyaentworks.common.config.ConditionalOnManga} ——
     * {@code EnvCheckService} 注入着它，标掉会让环境自检整个起不来。于是「漫画关掉时别读词典」
     * 这件事只能落在 {@link #init()} 里（它是全项目唯一一个会干活的 {@code @PostConstruct}）。
     */
    private final MangaProperties manga;

    private final AtomicLong versionSeq = new AtomicLong();
    private volatile MangaDictionary snapshot = MangaDictionary.EMPTY;

    /**
     * 启动时把词条读进内存快照。
     *
     * <p>漫画模块关掉时直接返回、留一个空快照：那时没有任何调用方（Controller 与任务处理器
     * 都随注解一起不注册了），空快照不会被读到；而少这一次查询，也就少一次「模块明明关了、
     * 启动时却还在碰它的库」的意外。
     */
    @PostConstruct
    public void init() {
        if (!manga.isEnabled()) {
            return;
        }
        reload();
    }

    /**
     * 当前词典快照。
     * <p>一次扫描任务应在开始时取一次并全程复用，避免中途词典变更导致
     * 同一批结果前后判定标准不一致。
     */
    public MangaDictionary current() {
        return snapshot;
    }

    /** 重新从库加载并替换快照，版本号递增 */
    public synchronized MangaDictionary reload() {
        List<MangaDictEntry> entries = mapper.selectList(null);
        this.snapshot = MangaDictionary.from(entries, versionSeq.incrementAndGet());
        return this.snapshot;
    }

    /**
     * 新增或更新词条，写入后立即重建快照。
     * <p>{@code matchMode} 未指定时落 {@link MangaDictMatchMode#EXACT}，
     * {@code ignoreCase} 未指定时落 {@code true}；类型不再携带默认语义。
     */
    public synchronized MangaDictEntry save(MangaDictEntry entry) {
        normalizeAndValidate(entry);
        if (entry.getId() == null) {
            mapper.insert(entry);
        } else {
            mapper.updateById(entry);
        }
        reload();
        return entry;
    }

    /** 批量新增，供扫描过程中一次性补录多条未识别项 */
    public synchronized int saveBatch(List<MangaDictEntry> entries) {
        int count = 0;
        for (MangaDictEntry entry : entries) {
            normalizeAndValidate(entry);
            if (mapper.exists(Wrappers.<MangaDictEntry>lambdaQuery()
                    .eq(MangaDictEntry::getDictType, entry.getDictType())
                    .eq(MangaDictEntry::getDictValue, entry.getDictValue()))) {
                continue;
            }
            mapper.insert(entry);
            count++;
        }
        reload();
        return count;
    }

    public synchronized void delete(Long id) {
        mapper.deleteById(id);
        reload();
    }

    public List<MangaDictEntry> list(MangaDictType type, String keyword) {
        return mapper.selectList(dictQuery(type, keyword));
    }

    /** 分页结果，与未归档页同构 */
    public record DictPage(long total, int page, int size, List<MangaDictEntry> items) {
    }

    /**
     * 分页列出词条，类型可选、关键词对值模糊匹配。
     * <p>分页没走 MyBatis-Plus 的分页插件（jsqlparser 模块未引入，interceptor 在那边），
     * 手动 count + limit，与 {@code MangaUnarchivedService} 同一套路；页码已钳制，无注入面。
     */
    public DictPage page(MangaDictType type, String keyword, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 200);
        LambdaQueryWrapper<MangaDictEntry> q = dictQuery(type, keyword);
        long total = mapper.selectCount(q);
        long offset = (long) (safePage - 1) * safeSize;
        List<MangaDictEntry> records = mapper.selectList(
                q.last(SqlDialect.limit(offset, safeSize)));
        return new DictPage(total, safePage, safeSize, records);
    }

    private LambdaQueryWrapper<MangaDictEntry> dictQuery(MangaDictType type, String keyword) {
        return Wrappers.<MangaDictEntry>lambdaQuery()
                .eq(type != null, MangaDictEntry::getDictType, type)
                .like(StringUtils.isNotBlank(keyword), MangaDictEntry::getDictValue, keyword)
                .orderByAsc(MangaDictEntry::getDictValue);
    }

    /**
     * 检索与 {@code value} 疑似重复的既有词条：忽略大小写、并去掉
     * {@code /}、{@code ／}、空格、{@code ・} 等分隔符后比对。
     * <p>原作名存在大量此类变体（如 {@code Fate／Grand Order} 与
     * {@code FateGrand Order}），新增前提示可减少词典膨胀。
     */
    public List<MangaDictEntry> findSimilar(MangaDictType type, String value) {
        String normalized = normalizeForCompare(value);
        if (StringUtils.isEmpty(normalized)) {
            return List.of();
        }
        return mapper.selectList(Wrappers.<MangaDictEntry>lambdaQuery()
                        .eq(MangaDictEntry::getDictType, type))
                .stream()
                .filter(e -> normalized.equals(normalizeForCompare(e.getDictValue())))
                .toList();
    }

    private static String normalizeForCompare(String value) {
        if (value == null) {
            return null;
        }
        return value.replaceAll("[/／\\\\\\s・·．.\\-−―—~～!！?？:：]", "").toLowerCase();
    }

    // ------------------------------------------------------------------
    // 补录辅助：默认匹配方式、重叠检测
    // ------------------------------------------------------------------

    /**
     * 该类型补录时的默认匹配方式。
     * <p>类型本身不含匹配语义（见 {@link MangaDictType}），但实际分布高度集中：
     * 展会几乎都是 {@code C\d+} 这样的正则，修饰性标签是后缀，其余是全等。
     * 表单预填这个值能让多数补录不必改选项 —— 只是默认值，不是约束。
     */
    public static MangaDictMatchMode defaultMatchMode(MangaDictType type) {
        if (type == null) {
            return MangaDictMatchMode.EXACT;
        }
        return switch (type) {
            case EXHIBIT -> MangaDictMatchMode.REGEX;
            case MODIFIER -> MangaDictMatchMode.SUFFIX;
            case MAGAZINE -> MangaDictMatchMode.PREFIX;
            default -> MangaDictMatchMode.EXACT;
        };
    }

    /**
     * 同类型下已经能匹配 {@code value} 的既有词条。
     * <p>与 {@link #findSimilar} 分工不同：那个找的是「长得像的变体」（去掉分隔符后同名），
     * 这个找的是「已经生效、不必新增」的覆盖关系 —— 新展会 {@code COMIC1☆25}
     * 撞上既有正则 {@code COMIC1☆\d+} 就属于后者，多半是漏了 reload 而非缺词条。
     */
    public List<MangaDictEntry> findOverlapping(MangaDictType type, String value) {
        String text = MangaTextUtil.nfc(StringUtils.trimToNull(value));
        if (type == null || text == null) {
            return List.of();
        }
        return mapper.selectList(Wrappers.<MangaDictEntry>lambdaQuery()
                        .eq(MangaDictEntry::getDictType, type))
                .stream()
                .filter(e -> MangaDictionary.matchesEntry(e, text))
                .toList();
    }

    // ------------------------------------------------------------------
    // 诊断，对应 MangaDictDiagnoseTest 的三个方法
    // ------------------------------------------------------------------

    /**
     * 一个类型的词条分布。
     *
     * @param byMatchMode 匹配方式 → 条数，用于发现「某类型只有 PREFIX 没有 REGEX」
     *                    这类静默漏判：靠年月号识别的杂志会全部落空
     * @param nonNfcCount 非 NFC（Unicode 分解形式）的词条数。快照构建时已统一归一，
     *                    故不影响解析，但能解释「库里明明有却匹配不上」的怪现象
     */
    public record TypeStat(MangaDictType dictType, int total,
                           Map<String, Long> byMatchMode, int nonNfcCount) {
    }

    /**
     * 词典总览。
     *
     * @param invalidRegexValues 构建快照时编译失败、已被静默跳过的正则
     */
    public record DictSummary(long version, List<TypeStat> stats,
                              List<String> invalidRegexValues) {
    }

    /** 类型分布 + 无效正则，对应 {@code dumpDictSummary} */
    public DictSummary summary() {
        List<MangaDictEntry> all = mapper.selectList(null);
        Map<MangaDictType, List<MangaDictEntry>> byType = all.stream()
                .filter(e -> e.getDictType() != null)
                .collect(Collectors.groupingBy(MangaDictEntry::getDictType));

        List<TypeStat> stats = new ArrayList<>();
        for (MangaDictType type : MangaDictType.values()) {
            List<MangaDictEntry> list = byType.getOrDefault(type, List.of());
            Map<String, Long> byMode = list.stream()
                    .collect(Collectors.groupingBy(e -> e.effectiveMatchMode().name(),
                            TreeMap::new, Collectors.counting()));
            int nonNfc = (int) list.stream()
                    .filter(e -> e.getDictValue() != null
                            && !Normalizer.isNormalized(e.getDictValue(), Normalizer.Form.NFC))
                    .count();
            stats.add(new TypeStat(type, list.size(), byMode, nonNfc));
        }
        MangaDictionary dict = current();
        return new DictSummary(dict.getVersion(), stats, dict.getInvalidRegexValues());
    }

    /**
     * 试探：{@code text} 命中哪些类型，对应 {@code probeSamples}。
     * <p>排查漏判最快的手段 —— 把可疑的文件夹名片段丢进来，看词典认成什么。
     * 判定走 {@link MangaDictionary#matchesAnySegment}，与解析时一致。
     */
    public record ProbeResult(String text, List<MangaDictType> hitTypes,
                              List<MangaDictEntry> hitEntries) {
    }

    /** 批量补录词典的任务参数（重跑靠它还原类型/匹配方式与逐行词条值） */
    public record BatchParams(MangaDictType dictType, MangaDictMatchMode matchMode,
                              Boolean ignoreCase, String remark, List<String> values) {
    }

    public ProbeResult probe(String text) {
        String probed = StringUtils.trimToNull(text);
        if (probed == null) {
            throw new IllegalArgumentException("试探内容不能为空");
        }
        MangaDictionary dict = current();
        List<MangaDictType> hits = Arrays.stream(MangaDictType.values())
                .filter(type -> dict.matchesAnySegment(type, probed))
                .toList();
        // 命中了哪些类型不足以定位问题，还要说出是哪一条词条 —— 否则改哪一行无从下手
        List<MangaDictEntry> entries = hits.isEmpty() ? List.of()
                : mapper.selectList(Wrappers.<MangaDictEntry>lambdaQuery()
                                .in(MangaDictEntry::getDictType, hits))
                        .stream()
                        .filter(e -> MangaDictionary.matchesEntry(e, probed))
                        .toList();
        return new ProbeResult(probed, hits, entries);
    }

    /**
     * 非 NFC 词条，对应 {@code countNonNfcEntries}。
     * <p>这类行 MySQL 判等而 Java 判不等。快照已统一归一所以解析不受影响，
     * 列出来是为了解释「补录时报唯一键冲突、可库里搜不到」这种矛盾现象。
     */
    public List<MangaDictEntry> listNonNfcEntries() {
        return mapper.selectList(null).stream()
                .filter(e -> e.getDictValue() != null
                        && !Normalizer.isNormalized(e.getDictValue(), Normalizer.Form.NFC))
                .toList();
    }

    private void normalizeAndValidate(MangaDictEntry entry) {
        MangaDictType type = entry.getDictType();
        if (type == null) {
            throw new IllegalArgumentException("dictType 不能为空");
        }
        if (StringUtils.isBlank(entry.getDictValue())) {
            throw new IllegalArgumentException("dictValue 不能为空");
        }
        entry.setDictValue(entry.getDictValue().trim());
        entry.setMatchMode(entry.effectiveMatchMode());
        entry.setIgnoreCase(entry.effectiveIgnoreCase());
        if (entry.getMatchMode() == MangaDictMatchMode.REGEX) {
            try {
                Pattern.compile(entry.getDictValue());
            } catch (Exception e) {
                throw new IllegalArgumentException("正则无法编译：" + e.getMessage(), e);
            }
        }
    }
}
