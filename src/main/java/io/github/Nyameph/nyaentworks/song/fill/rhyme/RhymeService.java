package io.github.Nyameph.nyaentworks.song.fill.rhyme;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import io.github.Nyameph.nyaentworks.common.db.SqlDialect;
import io.github.Nyameph.nyaentworks.common.pinyin.PinyinSyllable;
import io.github.Nyameph.nyaentworks.common.pinyin.PinyinUtil;
import io.github.Nyameph.nyaentworks.manga.util.MangaTextUtil;
import io.github.Nyameph.nyaentworks.song.fill.LyricFillAligner;
import io.github.Nyameph.nyaentworks.song.entity.RhymeEntry;
import io.github.Nyameph.nyaentworks.song.mapper.RhymeEntryMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 韵脚词典：锚解析 / 归一 / 查询 / 维护 / 种子（填词助手设计 §4）。
 *
 * <p><b>锚解析是本次的核心纯函数</b>（{@link #anchorOf}，有单测）：一个汉字展开它全部读音、
 * 一个韵母/韵部名归一后解析，得到查询键集合（韵身或韵母）。拆不出的读音跳过不编造
 * （{@code PinyinSyllable.parse} 返回 null 是正常情况）。
 *
 * <p><b>归一口径</b>（{@link #normalizeFinal}）：{@code v→ü}、{@code ve→üe}、{@code van→üan}、
 * {@code vn→ün}、{@code ui→uei}、{@code iu→iou}、{@code un→uen} —— 用户词表的行头写的是
 * 缩写与 v 系，字典与解析器走全拼。舌尖元音 {@code -i}（知/蚩那一路）单独放行，它不能走
 * {@code parse("i")}（那是七齐）。
 *
 * <p>查询键是 {@code rhyme_body} 不是 {@code yun18}（两者一一对应，韵身短、是机器口径）；
 * 排序口径 {@code tier ASC, freq DESC, 字符数(text) ASC, id ASC}（一级字 → 语料高频 → 短词 → 稳定）。
 */
@Service
@RequiredArgsConstructor
public class RhymeService {

    /** 单批插入行数（种子展开与批量粘贴共用）。 */
    private static final int INSERT_BATCH = 500;

    /**
     * {@code /entries} 的 limit 上限：前端一次拉回、在客户端按韵分块。
     *
     * <p>2026-09-14 由 2000 放宽到 20000：开源词表入库后 {@code rhyme_entry} 从 1.3 万涨到约 5.8 万行，
     * 单个韵身最多约 1.3 万条 —— 原来的 2000 那条依据（「实测最大一档 1904 条」）随之失效，
     * 不放开就会把结果截断、「显示更多」点到底也看不全。**前端 {@code rhymequery.js} 的
     * {@code LIMIT} 常量必须与这里对齐**，两边不一致同样是静默截断。
     */
    private static final int MAX_LIMIT = 20000;

    public static final String SOURCE_MODERN = "MODERN";
    public static final String SOURCE_XLSX = "XLSX";
    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_CORPUS = "CORPUS";

    /**
     * 开源词表（{@code phrase-pinyin-data} + jieba，见 {@link RhymeWordlistService}，§12）：
     * 词表文件在 classpath 里、由「导入开源词表」任务灌进来，重新执行幂等。
     */
    public static final String SOURCE_OPEN = "OPEN";

    /** 词性固定枚举（§4.6）。前四个来源是用户 XLSX 的列头，已归一：
     *  「名词-人」「名词-物」→ 名词，「动作」→ 动词。
     *  <p><b>这是唯一的一份</b>：页面上的「改词性」下拉、词性补全的 prompt 与校验、查询结果的分块
     *  顺序全从它来（2026-09-15 加了「单字」后，{@code RhymePosService} 的 prompt 里那句
     *  「这八个之一」也改成了不写数字的措辞 —— 写死数字就会和这张表漂移）。 */
    public static final List<String> WORD_CLASSES =
            List.of("名词", "动词", "形容词", "副词", "代词", "数量词", "虚词", "单字", "其他");

    private final RhymeEntryMapper mapper;

    // ==================== 锚解析（纯函数，必须有单测） ====================

    /**
     * 锚解析结果。
     *
     * @param keys       查询键集合（{@code granularity=yun18} 时是韵身、{@code finals} 时是韵母），
     *                   去重、稳定序；未认出为空
     * @param recognized 是否认出了这个锚；false 时 {@code message} 说明原因
     * @param message    未认出时的说明（「认不出这个锚：XXX」）
     */
    public record Anchor(List<String> keys, boolean recognized, String message) {

        public static Anchor of(List<String> keys) {
            return new Anchor(List.copyOf(keys), true, null);
        }

        public static Anchor of(Set<String> keys) {
            return new Anchor(List.copyOf(keys), true, null);
        }

        public static Anchor fail(String message) {
            return new Anchor(List.of(), false, message);
        }

        public boolean empty() {
            return keys.isEmpty();
        }
    }

    /**
     * 把锚解析成查询键集合。
     *
     * <pre>
     * anchor 是单个汉字 → 全部读音（按常用度排）逐个 PinyinSyllable，取韵身 / 韵母集合
     * anchor 是别的字符串 → 归一 → PinyinSyllable.parse → 拆得出同上
     *   → 拆不出 → 试「十八韵名精确匹配」→ 拿到韵身
     *   → 还是认不出 → 空集合 + message
     * </pre>
     *
     * @param anchor      一个汉字（如 心）或韵母/韵部名（如 ang / v / 十八东）。必填
     * @param granularity {@code yun18}（默认，按韵身）/ {@code finals}（按韵母）
     */
    public static Anchor anchorOf(String anchor, String granularity) {
        boolean byFinals = "finals".equals(granularity);
        String a = anchor == null ? "" : anchor.trim();
        if (a.isEmpty()) {
            return Anchor.fail("锚不能为空");
        }
        // 单个汉字：展开全部读音（多音字自然全展开，这是需求要的）
        if (a.codePointCount(0, a.length()) == 1) {
            int cp = a.codePointAt(0);
            if (PinyinUtil.isHanzi(cp)) {
                Set<String> keys = new LinkedHashSet<>();
                for (PinyinSyllable s : PinyinUtil.readingsByFrequency(cp)) {
                    addKey(keys, s, byFinals);
                }
                if (keys.isEmpty()) {
                    return Anchor.fail("「" + a + "」的读音都拆不出韵部");
                }
                return Anchor.of(keys);
            }
        }
        // 韵母 / 韵部名：先归一（v 系与缩写表），再解析
        String normalized = normalizeFinal(a);
        if (normalized.equals("-i")) {
            // 舌尖元音单独放行：parse("-i") 走不通（含连字符），parse("i") 又是七齐
            return Anchor.of(List.of("-i"));
        }
        PinyinSyllable parsed = PinyinSyllable.parse(normalized);
        if (parsed != null) {
            Set<String> keys = new LinkedHashSet<>();
            addKey(keys, parsed, byFinals);
            return Anchor.of(keys);
        }
        // 十八韵名精确匹配（如「十八东」）
        for (PinyinSyllable.Rhyme rhyme : PinyinSyllable.table()) {
            if (rhyme.yun18().equals(a)) {
                return Anchor.of(List.of(rhyme.body()));
            }
        }
        return Anchor.fail("认不出这个锚：" + a);
    }

    /** 按粒度取一个读音的键（韵身或韵母），塞进目标集合。 */
    private static void addKey(Set<String> keys, PinyinSyllable s, boolean byFinals) {
        String key = byFinals ? s.finals() : s.rhymeBody();
        if (key != null && !key.isEmpty()) {
            keys.add(key);
        }
    }

    /**
     * 行头 / 锚的韵母写法归一：trim、转小写、v 系改 ü、缩写（ui/iu/un）改全拼、van→üan。
     * 其余原样返回。与 TSV 导入共用这一份，两处不许漂移。
     */
    public static String normalizeFinal(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().toLowerCase();
        return switch (s) {
            case "v" -> "ü";
            case "ve" -> "üe";
            case "van" -> "üan";
            case "vn" -> "ün";
            case "ui" -> "uei";
            case "iu" -> "iou";
            case "un" -> "uen";
            default -> s;
        };
    }

    // ==================== meta ====================

    /** 下拉粒度。 */
    public record Granularity(String key, String label) {
    }

    /**
     * 韵部总表的一项：{@link PinyinSyllable.Rhyme} 的六个展示字段（字段名不变，前端两条链都在读）
     * + 两个计数。
     *
     * @param count         该韵部（韵身）下的总条数
     * @param finalsCounts  {@code {韵母: 条数}}，键与 {@code finals} 清单一一对应（韵部总表要显示
     *                      「每个韵母下有多少条」）
     */
    public record RhymeMeta(String body, String yun18, String zhe13, String yun14,
                            List<String> finals, String sample,
                            long count, Map<String, Long> finalsCounts) {
    }

    /** {@code GET /meta} 的返回：一次拉全，前端缓存。 */
    public record Meta(List<Granularity> granularities, List<RhymeMeta> rhymes,
                       List<String> wordClasses, List<String> sources) {
    }

    /**
     * 韵部总表 + 词性枚举 + 来源清单。
     *
     * <p>两个计数由<b>两条 GROUP BY</b> 一次查完再拼上去（按韵身 / 按韵身+韵母），不是 18 个韵部
     * 各查一次库。
     *
     * <p>词性下发的是固定枚举 {@link #WORD_CLASSES}，<b>不再</b> {@code SELECT DISTINCT}：
     * 历史值里的「名词-人 / 名词-物 / 动作」由 {@code RhymePosService} 的补全任务归一到枚举，
     * 查询侧只认枚举（两处口径靠这一个常量对齐）。
     */
    public Meta meta() {
        Map<String, Long> bodyCounts = new LinkedHashMap<>();
        for (Map<String, Object> row : mapper.selectMaps(Wrappers.<RhymeEntry>query()
                .select("rhyme_body", "COUNT(*) AS cnt")
                .groupBy("rhyme_body"))) {
            bodyCounts.put(String.valueOf(row.get("rhyme_body")), countOf(row.get("cnt")));
        }
        Map<String, Map<String, Long>> finalsCounts = new LinkedHashMap<>();
        for (Map<String, Object> row : mapper.selectMaps(Wrappers.<RhymeEntry>query()
                .select("rhyme_body", "finals", "COUNT(*) AS cnt")
                .groupBy("rhyme_body", "finals"))) {
            finalsCounts.computeIfAbsent(String.valueOf(row.get("rhyme_body")),
                            k -> new LinkedHashMap<>())
                    .put(String.valueOf(row.get("finals")), countOf(row.get("cnt")));
        }
        List<RhymeMeta> rhymes = PinyinSyllable.table().stream().map(r -> {
            Map<String, Long> perFinals = new LinkedHashMap<>();
            Map<String, Long> counts = finalsCounts.getOrDefault(r.body(), Map.of());
            for (String finals : r.finals()) {
                perFinals.put(finals, counts.getOrDefault(finals, 0L));
            }
            return new RhymeMeta(r.body(), r.yun18(), r.zhe13(), r.yun14(), r.finals(), r.sample(),
                    bodyCounts.getOrDefault(r.body(), 0L), perFinals);
        }).toList();
        return new Meta(
                List.of(new Granularity("yun18", "十八韵"), new Granularity("finals", "韵母")),
                rhymes,
                WORD_CLASSES,
                List.of(SOURCE_MODERN, SOURCE_XLSX, SOURCE_MANUAL, SOURCE_CORPUS, SOURCE_OPEN));
    }

    private static long countOf(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    // ==================== 查询 ====================

    /**
     * 一条查询结果行（实体原样下发，字段语义见 {@link RhymeEntry}）。
     *
     * <p>{@code message} <b>只在锚认不出时有值</b>（「锚不能为空」/「认不出这个锚：XXX」/
     * 「「哟」的读音都拆不出韵部」）。它跟着 data 一起下发，不走 {@code ApiResult.message} ——
     * 锚认不出不是失败（{@code success} 仍是 true），而前端要把这句原话摆出来，
     * 「锚认不出」与「认出了但这个韵没有词条」才讲得清（§4.6②）。
     */
    public record EntryPage(long total, String anchor, String granularity,
                            List<String> rhymeBodies, List<String> yun18Names,
                            List<RhymeEntry> items, String message) {
    }

    /**
     * 按锚查词典。锚认不出时返回 0 条 + message（不抛错，前端好展示）。
     *
     * @param type      CHAR / WORD / 空 = 都要
     * @param wordClass 词性精确过滤，空不过滤（页面已无此入口，后端留着，同 {@code type}）
     * @param sources   来源多选（MODERN / XLSX / MANUAL / CORPUS / OPEN），空 / 全空串 = 不过滤
     * @param q         关键字（text LIKE %q%），通配符转义见 {@link MangaTextUtil#escapeLike}
     * @param limit     默认 50，上限 {@value #MAX_LIMIT}（前端拉回一次后在客户端按韵分块）
     */
    public EntryPage entries(String anchor, String granularity, String type,
                             String wordClass, List<String> sources, String q, Integer limit) {
        String gran = StringUtils.defaultIfBlank(granularity, "yun18");
        Anchor parsed = anchorOf(anchor, gran);
        if (!parsed.recognized()) {
            return new EntryPage(0, StringUtils.defaultString(anchor), gran,
                    List.of(), List.of(), List.of(), parsed.message());
        }
        int capped = limit == null || limit <= 0 ? 50 : Math.min(limit, MAX_LIMIT);
        var query = Wrappers.<RhymeEntry>lambdaQuery()
                .in("finals".equals(gran) ? RhymeEntry::getFinals : RhymeEntry::getRhymeBody,
                        parsed.keys());
        if (StringUtils.isNotBlank(type)) {
            query.eq(RhymeEntry::getEntryType, type);
        }
        if (StringUtils.isNotBlank(wordClass)) {
            query.eq(RhymeEntry::getWordClass, wordClass);
        }
        List<String> sourceFilter = cleanSources(sources);
        if (!sourceFilter.isEmpty()) {
            query.in(RhymeEntry::getSource, sourceFilter);
        }
        if (StringUtils.isNotBlank(q)) {
            // 通配符在 Java 侧拼（SQLite 没有 CONCAT），转义后的模式仍走绑定参数
            query.apply(SqlDialect.likeContains("text"),
                    "%" + MangaTextUtil.escapeLike(q.trim()) + "%");
        }
        long total = mapper.selectCount(query);
        // 长度取**字符数**：MySQL 的 LENGTH 是字节数、SQLite 的 length() 才是字符数，
        // 原样写下来两边的排序键就不是一回事了（见 SqlDialect#charLength）
        query.last("ORDER BY tier ASC, freq DESC, " + SqlDialect.charLength("text")
                + " ASC, id ASC LIMIT " + capped);
        List<RhymeEntry> items = mapper.selectList(query);
        // 展示名：命中的键 → 韵身 → 十八韵名（finals 粒度下去重后的韵身集合）
        Set<String> bodies = new LinkedHashSet<>();
        if ("finals".equals(gran)) {
            for (PinyinSyllable.Rhyme rhyme : PinyinSyllable.table()) {
                if (parsed.keys().stream().anyMatch(rhyme.finals()::contains)) {
                    bodies.add(rhyme.body());
                }
            }
        } else {
            bodies.addAll(parsed.keys());
        }
        List<String> yun18Names = PinyinSyllable.table().stream()
                .filter(r -> bodies.contains(r.body()))
                .map(PinyinSyllable.Rhyme::yun18).toList();
        return new EntryPage(total, StringUtils.defaultString(anchor), gran,
                List.copyOf(bodies), yun18Names, items, null);
    }

    /**
     * 来源多选 → 查询用的 IN 列表：丢空白项、去重（保序）。
     * 返回值空 = <b>不筛选</b>（「一个都没勾」与「勾了全部」在页面上都是「不限」，
     * 前端勾满五个就等于不过滤，两边结果一致，不做特例）。
     */
    private static List<String> cleanSources(List<String> sources) {
        Set<String> out = new LinkedHashSet<>();
        if (sources != null) {
            for (String s : sources) {
                String v = StringUtils.trimToNull(s);
                if (v != null) {
                    out.add(v);
                }
            }
        }
        return List.copyOf(out);
    }

    // ==================== 种子：现代规范字表全展开 ====================

    /**
     * 种子结果。
     *
     * @param skipped  true = 库里已有 MODERN 行，这次没跑（{@code force=false} 时）
     * @param built    展开出的词条数（含会被 IGNORE 的重复）
     * @param inserted 实际写入行数
     */
    public record SeedResult(boolean skipped, int built, int inserted) {
    }

    /** 库里是否已有种子（单字是恒定的，跑过一次就不用再跑）。 */
    public boolean modernSeeded() {
        Long count = mapper.selectCount(Wrappers.<RhymeEntry>lambdaQuery()
                .eq(RhymeEntry::getSource, SOURCE_MODERN));
        return count != null && count > 0;
    }

    /**
     * 展开 kTGHZ2013 的 8105 个码点：每个码点的每个读音 → {@code PinyinSyllable.parse} →
     * 拆得出就一行 CHAR（拆不出跳过）。分批 {@code INSERT IGNORE}（靠 uk_text_pinyin 幂等，
     * 字表更新后重跑也只补新增）。
     *
     * <p><b>绝对不要用 pinyin.txt 做种子</b>：合并字典带古音/异体音，会把常用字塞进
     * 按现代读音根本不押韵的韵部（「母」带 wǔ → 十姑）。生僻字走反查端点 /of，能力不丢。
     *
     * @param force true = 已有种子的也重跑（只补新增）；false = 已有就跳过
     */
    public SeedResult seedModern(boolean force) {
        if (!force && modernSeeded()) {
            return new SeedResult(true, 0, 0);
        }
        List<RhymeEntry> batch = new ArrayList<>(INSERT_BATCH);
        int[] built = {0};
        int[] inserted = {0};
        PinyinUtil.forEachModern((cp, readings) -> {
            String text = new String(Character.toChars(cp));
            int tier = PinyinUtil.tier(cp);
            for (String reading : readings) {
                PinyinSyllable s = PinyinSyllable.parse(reading);
                if (s == null) {
                    continue; // 拆不出的读音跳过，不编造韵部
                }
                RhymeEntry e = new RhymeEntry();
                e.setEntryType("CHAR");
                e.setText(text);
                e.setPinyin(reading);
                e.setFinals(s.finals());
                e.setRhymeBody(s.rhymeBody());
                e.setYun18(s.yun18());
                e.setSource(SOURCE_MODERN);
                e.setTier(tier);
                e.setFreq(0);
                batch.add(e);
                built[0]++;
                if (batch.size() >= INSERT_BATCH) {
                    inserted[0] += mapper.insertIgnoreBatch(batch);
                    batch.clear();
                }
            }
        });
        if (!batch.isEmpty()) {
            inserted[0] += mapper.insertIgnoreBatch(batch);
        }
        return new SeedResult(false, built[0], inserted[0]);
    }

    // ==================== 尾字读音消歧（TSV 导入与语料回填共用） ====================

    /**
     * 词尾字读音消歧（§4.4 第 6 条 / §4.5 第 3 条的共用方法，别写两份）。
     *
     * <p>读音里挑「韵身 == {@code targetBody}」的那个；有多个取<b>最常用的</b>
     * （{@code readingsByFrequency} 把常用音排在前）。没有与 target 一致的 → 返回
     * 最常用的第一个读音（调用方按行头落库并在 note 里标不符）。
     * {@code targetBody} 传 null = 不消歧，直接取最常用的第一个（语料词回填用）。
     * 完全没有可解析读音 → {@code null}。
     */
    public static PinyinSyllable resolveReading(int codePoint, String targetBody) {
        List<PinyinSyllable> readings = PinyinUtil.readingsByFrequency(codePoint);
        if (readings.isEmpty()) {
            return null;
        }
        if (targetBody != null) {
            for (PinyinSyllable reading : readings) {
                if (targetBody.equals(reading.rhymeBody())) {
                    return reading;
                }
            }
        }
        return readings.getFirst();
    }

    // ==================== 句尾词提取（语料回填词典，启发式） ====================

    /**
     * 停用虚词表（写死，别自由发挥）：边界字落在这些字上的窗口不看成词。
     * 「的了着过」这类助词与「我你他」这类代词打头 / 收尾的片段都不是词条。
     */
    /**
     * 只可能出现在词首的停用字：结构助词（的 地 得 着 过）、语气词（吗 呢 吧 …）与代词
     * （我 你 他 她 它 们）。它们作末字时不算停用——「是」「在」「有」这类不同，见
     * {@link #STOP_TAIL}。
     */
    static final String STOP_HEAD = "的地得了着过吗呢吧啊呀哦哈嗯我你他她它们";

    /**
     * 句尾窗口的停用字表（**原有的一串，一字未改**）。这一串里「是 在 有 就 也 都 还 又
     * 很 太 不 和 与 或 而 但 却」其实可以合法地给一个词打头（不同、都会、还好…），混在
     * 一张表里套到首字上会把「不同」这类真词误杀、逼着窗口往 3/4 字爬、爬出更长的垃圾
     * （「反正都不同」→「正都不同」），所以首字另用 {@link #STOP_HEAD}。
     */
    static final String STOP_TAIL = "的地得了着过吗呢吧啊呀哦哈嗯我你他她它们是在有就也都还又很太不和与或而但却";

    /**
     * 从一句歌词的句尾提取词条候选（每句至多一个，§4.5）。
     *
     * <p>句尾 2~4 字窗口、优先 2 字（中文歌词的双字词最多）；窗口内不跨标点
     * （从句尾倒着收集汉字单元，遇到英文 / 标点 / 数字即停）；<b>首字或末字是停用虚词</b>的
     * 窗口不算词（「下的」「走了」「的梦」都不是词条）。不足 2 个汉字 → null。
     *
     * <p>首字与末字各判各的表：首字看 {@link #STOP_HEAD}（只看末字的话「我 你 他 她 它 们
     * 的 了」这些永不生效，「…甜的梦」就会抽出「的梦」这种不是词的东西），末字看
     * {@link #STOP_TAIL}（「下的」「走了」靠它挡掉）。
     */
    public static String extractTailWord(String text) {
        List<String> units = LyricFillAligner.tokenize(text);
        List<Integer> tail = new ArrayList<>(4); // 倒序：tail.get(0) = 句尾最后一个汉字
        for (int k = units.size() - 1; k >= 0 && tail.size() < 4; k--) {
            String unit = units.get(k);
            if (LyricFillAligner.hasAsciiLetter(unit)) {
                break; // 英文单元：硬边界（英文句尾本就不判韵）
            }
            if (!unit.codePoints().allMatch(PinyinUtil::isHanzi)) {
                if (tail.isEmpty()) {
                    continue; // 句尾的标点 / 数字：跳过，窗口取它前面那串汉字（同 §5.2 句尾字口径）
                }
                break; // 已经在收词再遇标点 = 窗口会跨标点，停
            }
            unit.codePoints().forEach(tail::add);
        }
        if (tail.size() < 2) {
            return null;
        }
        // 优先 2 字，2 字不成再看 3、4 字；首字或末字是停用虚词的整窗作废（「下的」「走了」「的梦」
        // 不是词条）。收进 tail 的一定是汉字单元，标点进不来，所以首字只可能栽在虚词上。
        for (int len = 2; len <= Math.min(4, tail.size()); len++) {
            StringBuilder word = new StringBuilder();
            for (int k = len - 1; k >= 0; k--) { // 倒序列表从深到浅拼 = 正序
                word.appendCodePoint(tail.get(k));
            }
            String candidate = word.toString();
            int firstCp = candidate.codePointAt(0);
            int lastCp = candidate.codePointBefore(candidate.length());
            if (STOP_HEAD.contains(new String(Character.toChars(firstCp)))
                    || STOP_TAIL.contains(new String(Character.toChars(lastCp)))) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    // ==================== 批量粘贴入库（每行「字词」或「字词 拼音」） ====================

    /** 批量请求：{@code text} 多行文本（每行「字词」或「字词 拼音」），{@code wordClass} 可选。 */
    public record BatchRequest(String text, String wordClass) {
    }

    /** 一条被丢弃的行：原始行号（1 起）/ 原行文本 / 原因。前端渲染成表格。 */
    public record BatchFailed(int line, String text, String reason) {
    }

    /**
     * 一行解析出来的东西：词条 + <b>这行的拼音是不是人打上去的</b>。
     *
     * <p>{@code typedPinyin} 决定入库时怎么认「已经有这个词」（用户 2026-09-15 指定）：
     * 打了拼音 → 按 {@code (text, pinyin)} 认；只打了字词 → 同 {@code text} 的<b>全部</b>算命中。
     */
    public record BatchLine(RhymeEntry entry, boolean typedPinyin) {
    }

    /** 纯解析结果：词条（source=MANUAL）+ 丢弃明细。 */
    public record BatchParsed(List<BatchLine> lines, int total, List<BatchFailed> failed) {

        /** 只要词条（不关心拼音是不是手打的）时用这个，省得到处 {@code .map(BatchLine::entry)}。 */
        public List<RhymeEntry> entries() {
            return lines.stream().map(BatchLine::entry).toList();
        }
    }

    /**
     * 批量入库结果。四个数加上失败明细就是全部行，界面直接照抄：
     * {@code total == inserted + promoted + skipped + failed.size()}。
     *
     * @param total    解析到的行数（去空行后的行数 = inserted + promoted + skipped + failed）
     * @param inserted 实际插入行数
     * @param promoted 库里已有、这次只把 {@code source} 改成 MANUAL 的<b>行数</b>（2026-09-15 增）。
     *                 一行文本可能提升多行（只给字词时同 text 的全部算命中），所以它按<b>行</b>数
     *                 而不是按输入行数计
     * @param skipped  既没新增也没提升的行数：撞 {@code uk_text_pinyin} 插不进去、或命中的全是
     *                 现代规范字表（MODERN 不动）。<b>只数解析成功但什么都没做的</b>，
     *                 失败行在 {@code failed} 里，不重复计
     * @param failed   逐行失败明细
     */
    public record BatchResult(int total, int inserted, int promoted, int skipped,
                              List<BatchFailed> failed) {
    }

    /** 行内分隔符：半角空格 / 全角空格 / tab 都吃。 */
    private static final String BLANKS = " \t　";

    /**
     * 去首尾空白（含全角空格 U+3000）。<b>不能直接用 {@link String#trim()}</b> —— 它只吃
     * {@code <= U+0020}，全角空格会留在词尾，尾字就成空格了（判成「尾字不是汉字」）。
     */
    private static String trimBlanks(String s) {
        int from = 0;
        int to = s.length();
        while (from < to && BLANKS.indexOf(s.charAt(from)) >= 0) {
            from++;
        }
        while (to > from && BLANKS.indexOf(s.charAt(to - 1)) >= 0) {
            to--;
        }
        return s.substring(from, to);
    }

    /** 拼音字符（判据用，含带调元音与 ü / ê）：ASCII 字母 + 数字 + 下面这些。 */
    private static final String PINYIN_CHARS = "üêāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜ";

    /**
     * 多行文本 → 词条 + 丢弃明细（<b>纯函数</b>：不查库、不写库，单测钉这一个）。
     *
     * <pre>
     * 按行拆、每行 trim、空行跳过（行号仍按原文数，方便人对着原文找）
     * 行尾的拼音段：从最后一个空白往前，连续「全是拼音字符」的那一串算手填拼音，
     *   它前面就是字词；没有这样的尾巴 → 整行都是字词
     * 手填拼音 → PinyinSyllable.parse（整词拼音如 suixing 取最后一个音节）→ 拆不出：failed
     * 没手填   → 尾字走 {@link #resolveReading}(尾字, null) 反推（与单条添加、语料回填同一份）
     * 尾字不是汉字 / 反推不出 → failed，绝不静默丢
     * </pre>
     *
     * <p>{@code rhyme_entry.pinyin} 存的语义一直是<b>尾字读音</b>（多音字一音一行），所以手填的
     * 整词拼音只取最后一个音节。
     */
    public static BatchParsed parseBatchLines(String text, String wordClass) {
        List<BatchLine> lines = new ArrayList<>();
        List<BatchFailed> failed = new ArrayList<>();
        int total = 0;
        String[] raw = StringUtils.defaultString(text)
                .replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
        for (int i = 0; i < raw.length; i++) {
            String line = trimBlanks(raw[i]);
            if (line.isEmpty()) {
                continue;
            }
            total++;
            int lineNo = i + 1;
            // 行尾的拼音段（可能一个 token，也可能「sui xing」两个）；前面没字词时整行当字词
            int pinyinStart = pinyinTailStart(line);
            String word = line;
            String typedPinyin = null;
            if (pinyinStart > 0) {
                word = trimBlanks(line.substring(0, pinyinStart));
                String tail = trimBlanks(line.substring(pinyinStart));
                int lastBlank = lastBlank(tail);
                typedPinyin = lastBlank < 0 ? tail : tail.substring(lastBlank + 1);
                if (word.isEmpty()) {
                    word = line;
                    typedPinyin = null;
                }
            }
            int last = word.codePointBefore(word.length());
            if (!PinyinUtil.isHanzi(last)) {
                failed.add(new BatchFailed(lineNo, line, "尾字不是汉字"));
                continue;
            }
            PinyinSyllable reading = typedPinyin == null
                    ? resolveReading(last, null) : lastSyllable(typedPinyin);
            if (reading == null) {
                failed.add(new BatchFailed(lineNo, line, typedPinyin == null
                        ? "尾字「" + new String(Character.toChars(last)) + "」的读音都拆不出韵部"
                        : "读音「" + typedPinyin + "」拆不出韵部，检查一下写法"));
                continue;
            }
            RhymeEntry e = new RhymeEntry();
            e.setEntryType(word.codePointCount(0, word.length()) == 1 ? "CHAR" : "WORD");
            e.setText(word);
            e.setPinyin(StringUtils.defaultIfBlank(reading.pinyin(), typedPinyin));
            e.setFinals(reading.finals());
            e.setRhymeBody(reading.rhymeBody());
            e.setYun18(reading.yun18());
            e.setWordClass(StringUtils.trimToNull(wordClass));
            e.setSource(SOURCE_MANUAL);
            e.setTier(PinyinUtil.tier(last));
            e.setFreq(0);
            lines.add(new BatchLine(e, typedPinyin != null));
        }
        return new BatchParsed(lines, total, failed);
    }

    /**
     * 这一行算命中了哪些已有行（<b>纯函数</b>，单测钉这一个）。
     *
     * @param typedPinyin 这行的拼音是不是人打上去的：<b>是</b> → 同 {@code (text, pinyin)} 才算命中；
     *                    <b>否</b>（拼音是按尾字反推的）→ 传进来的同 text 行全部算命中
     */
    static List<RhymeEntry> hitRows(String pinyin, boolean typedPinyin, List<RhymeEntry> sameText) {
        if (!typedPinyin) {
            return List.copyOf(sameText);
        }
        List<RhymeEntry> hits = new ArrayList<>();
        for (RhymeEntry r : sameText) {
            if (pinyin != null && pinyin.equals(r.getPinyin())) {
                hits.add(r);
            }
        }
        return hits;
    }

    /**
     * 批量入库：解析 → 逐行认「库里有没有」→ 有的<b>提升为 MANUAL</b>、没有的 {@code INSERT IGNORE}。
     *
     * <p><b>「已经有」怎么认</b>（用户 2026-09-15 指定，按这行的拼音是不是手打的而不同）：
     * <ul>
     *   <li>打了拼音（{@code 碎星 suìxīng}）→ 按 {@code (text, pinyin)} 精确认：命中就只提升它。
     *       同 text 的别的读音不受影响 —— 那是另一个读音，不是「已经有了」</li>
     *   <li>只打了字词（{@code 碎星}）→ 同 {@code text} 的<b>全部</b>算命中，一起提升。用户没指定
     *       读音，就是「这个词我认领了」，多音的各行都归人工</li>
     * </ul>
     * 提升<b>只改 {@code source}</b>，别的字段一个都不动（不重算韵部、不改 tier / freq）。
     *
     * <p><b>MODERN 不动、算跳过</b>：现代规范字表是自动生成的种子，改成 MANUAL 会让它变成可删除
     * （{@link #delete} 拒删 MODERN）、还会干扰启动时的种子判定（{@code modernSeeded}）。
     */
    public BatchResult addBatch(BatchRequest request) {
        BatchParsed parsed = parseBatchLines(request == null ? null : request.text(),
                request == null ? null : request.wordClass());
        List<RhymeEntry> pending = new ArrayList<>(parsed.lines().size());
        int promoted = 0;
        int skipped = 0;
        for (BatchLine line : parsed.lines()) {
            RhymeEntry e = line.entry();
            // 唯一键 uk_text_pinyin 的前缀就是 text，这条走得了索引（一次批量几百行，无所谓）
            List<RhymeEntry> same = mapper.selectList(Wrappers.<RhymeEntry>lambdaQuery()
                    .eq(RhymeEntry::getText, e.getText()));
            List<RhymeEntry> hits = hitRows(e.getPinyin(), line.typedPinyin(), same);
            if (hits.isEmpty()) {
                pending.add(e); // 库里没有 → 走原来的 INSERT IGNORE
                continue;
            }
            int n = 0;
            for (RhymeEntry r : hits) {
                if (SOURCE_MODERN.equals(r.getSource())) {
                    continue; // 字表是种子：不动
                }
                r.setSource(SOURCE_MANUAL);
                mapper.updateById(r);
                n++;
            }
            if (n == 0) {
                skipped++; // 命中的全是字表，这一行什么都没做（不插新行：撞唯一键）
            } else {
                promoted += n;
            }
        }
        int inserted = 0;
        for (int from = 0; from < pending.size(); from += INSERT_BATCH) {
            inserted += mapper.insertIgnoreBatch(
                    pending.subList(from, Math.min(pending.size(), from + INSERT_BATCH)));
        }
        // skipped 只数「解析成功但什么都没做」的（命中的全是 MODERN / 撞 uk_text_pinyin 没插进去）；
        // 失败行已经进了 failed，再算进 skipped 就会出现 3 = 0 + 3 + 2 这种对不上的数。
        return new BatchResult(parsed.total(), inserted, promoted,
                skipped + (pending.size() - inserted), parsed.failed());
    }

    /** 行内最后一个空白的位置；没有空白返回 -1。 */
    private static int lastBlank(String line) {
        for (int i = line.length() - 1; i >= 0; i--) {
            if (BLANKS.indexOf(line.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 行尾那串「全是拼音字符」的 token 的起点（如 {@code 碎星 suixing} 的 {@code 4}）；
     * 整行都是拼音（没有字词在前）或行尾不是拼音时返回 -1。
     */
    private static int pinyinTailStart(String line) {
        int start = line.length();
        // 按空白切 token，从后往前收「全是拼音字符」的那一段
        int from = line.length();
        while (from > 0) {
            int blank = lastBlank(line.substring(0, from));
            String token = blank < 0 ? line.substring(0, from) : line.substring(blank + 1, from);
            if (token.isEmpty() || !isPinyinToken(token)) {
                break;
            }
            start = blank < 0 ? 0 : blank + 1;
            from = blank < 0 ? 0 : blank;
        }
        return start == line.length() ? -1 : start;
    }

    /** 是否「全是拼音字符」（字母 + 声调符号 + 数字）：手填拼音的判据。 */
    private static boolean isPinyinToken(String token) {
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || PINYIN_CHARS.indexOf(c) >= 0;
            if (!ok) {
                return false;
            }
        }
        return !token.isEmpty();
    }

    /**
     * 手填读音 → 一个音节：整串能 {@code parse} 就用它；整词拼音（{@code suixing}）按
     * 「最长可解析前缀」从左往右切，取<b>最后一个</b>音节（{@code pinyin} 列存的是尾字读音）。
     * 切不动返回 {@code null}（调用方记 failed，不猜）。
     */
    static PinyinSyllable lastSyllable(String pinyin) {
        if (StringUtils.isBlank(pinyin)) {
            return null;
        }
        PinyinSyllable whole = PinyinSyllable.parse(trimBlanks(pinyin));
        if (whole != null) {
            return whole;
        }
        String s = trimBlanks(pinyin);
        PinyinSyllable last = null;
        int i = 0;
        while (i < s.length()) {
            PinyinSyllable piece = null;
            for (int end = s.length(); end > i; end--) {
                piece = PinyinSyllable.parse(s.substring(i, end));
                if (piece != null) {
                    i = end;
                    break;
                }
            }
            if (piece == null) {
                return null;
            }
            last = piece;
        }
        return last;
    }

    // ==================== 维护（只写 rhyme_entry，字典页查询页签的词性编辑用） ====================

    /**
     * 修改请求：只允许 wordClass / note。
     *
     * <p>{@code text} / {@code pinyin} 特意声明出来（而不是留给 Jackson 当未知字段丢掉）：
     * 传了它们要<b>明确拒绝</b>（§4.6④），静默忽略会让人以为「改名成功了」。
     */
    public record UpdateRequest(String wordClass, String note, String text, String pinyin) {
    }

    /**
     * 只允许改 wordClass / note（改 text 或 pinyin 等于换了一条，要删了重加）。
     *
     * <p>带了 text / pinyin 的请求<b>拒绝并说明原因</b>，不静默忽略：韵部列是按文本与读音算的，
     * 放过去就会出现「词条写着 A、韵部还是 B」的行，比报个错难查得多。
     */
    public RhymeEntry updateNote(long id, UpdateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空（只改 wordClass / note）");
        }
        if (StringUtils.isNotBlank(request.text()) || StringUtils.isNotBlank(request.pinyin())) {
            throw new IllegalArgumentException("只允许改词性与备注：改文本或读音等于换一条词条，"
                    + "请先删掉这条，再按新文本重新添加");
        }
        RhymeEntry e = mapper.selectById(id);
        if (e == null) {
            throw new IllegalArgumentException("词条不存在（id=" + id + "）");
        }
        e.setWordClass(StringUtils.trimToNull(request.wordClass()));
        e.setNote(StringUtils.trimToNull(request.note()));
        mapper.updateById(e);
        return e;
    }

    /**
     * 批量改词性请求：{@code ids} 是页面上勾选的词条，{@code wordClass} 是它们的新词性。
     *
     * <p>{@code wordClass} 留空 = <b>清空</b>词性（回到「未分类」那一块），不是「不改」——
     * 「不改」用不着发这个请求。这个语义与删除按钮旁边那个下拉是一致的：下拉里选「不填词性」
     * 就是把这一列抹掉。
     */
    public record WordClassRequest(List<Long> ids, String wordClass) {
    }

    /**
     * 批量改词性：把勾选的多条一次改成同一个词性（词典页查询页签的「改词性」）。
     *
     * <p><b>为什么按 ids 一条 UPDATE 而不是逐条 updateById</b>：勾选可能横跨多个词性块与多页，
     * 几十上百条各自一次往返没有意义；这里改的是同一列同一个值，本就该是一句 {@code IN}。
     *
     * <p><b>为什么必须用 {@code LambdaUpdateWrapper} 而不是 {@code updateById}</b>：
     * MyBatis-Plus 默认的 {@code NOT_NULL} 更新策略会跳过 null 字段，清空词性那一下会
     * <b>静默不生效</b> —— 页面显示「已改 3 条」而库里一个字段都没动（§7.28）。
     *
     * <p><b>枚举外的写法一律拒绝</b>（不落「其他」）：{@link #WORD_CLASSES} 是查询侧分块的依据，
     * 放进一个枚举外的值等于把这一列又弄脏回去，而且是从页面上弄脏的。空串走清空、不算枚举外。
     *
     * <p><b>MODERN 行不拒绝</b>：字表是「不能删」的种子，改词性不影响种子判定
     * （{@code RhymeSeedRunner} 用 {@code INSERT IGNORE}，不比对 word_class），
     * 而且字表行本来就该有词性。
     *
     * @return 实际改动的行数（ids 里有查不到的 id 时小于 ids.size()，不报错）
     */
    public int updateWordClass(WordClassRequest request) {
        if (request == null || request.ids() == null || request.ids().isEmpty()) {
            throw new IllegalArgumentException("先勾选要改的词条");
        }
        String wordClass = StringUtils.trimToNull(request.wordClass());
        if (wordClass != null && !WORD_CLASSES.contains(wordClass)) {
            throw new IllegalArgumentException("词性只能是：" + String.join(" / ", WORD_CLASSES)
                    + "；留空表示清空词性");
        }
        return mapper.update(null, Wrappers.<RhymeEntry>lambdaUpdate()
                .set(RhymeEntry::getWordClass, wordClass)
                .in(RhymeEntry::getId, request.ids()));
    }

    /** 删除请求：按 id 集合或按来源整批，二选一。MODERN 是种子，删了下次启动又回来 → 拒绝。 */
    public record DeleteRequest(List<Long> ids, String source) {
    }

    /** @return 删除的行数 */
    public int delete(DeleteRequest request) {
        if (request.ids() != null && !request.ids().isEmpty()) {
            List<RhymeEntry> rows = mapper.selectBatchIds(request.ids());
            for (RhymeEntry row : rows) {
                if (SOURCE_MODERN.equals(row.getSource())) {
                    throw new IllegalArgumentException("单字词典（MODERN）是种子，不能删；"
                            + "整批删语料词用「按来源删除 CORPUS」");
                }
            }
            return mapper.deleteByIds(request.ids());
        }
        String source = StringUtils.defaultString(request.source());
        if (SOURCE_MODERN.equals(source)) {
            throw new IllegalArgumentException("MODERN 是种子来源，不能整批删（删除了下次启动也会回来）");
        }
        // OPEN 必须在白名单里：它是最需要「一键整体摘除」的一批（4.4 万条外部导入，
        // 换个词表 / 想剔掉不合适的词都靠这条），与 ids 分支「只拒 MODERN」的口径也一致。
        if (!SOURCE_CORPUS.equals(source) && !SOURCE_MANUAL.equals(source)
                && !SOURCE_XLSX.equals(source) && !SOURCE_OPEN.equals(source)) {
            throw new IllegalArgumentException("按来源删除只支持 CORPUS / MANUAL / XLSX / OPEN");
        }
        return mapper.delete(Wrappers.<RhymeEntry>lambdaQuery()
                .eq(RhymeEntry::getSource, source));
    }
}
