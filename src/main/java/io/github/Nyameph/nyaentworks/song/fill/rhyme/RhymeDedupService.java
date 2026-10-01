package io.github.Nyameph.nyaentworks.song.fill.rhyme;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import io.github.Nyameph.nyaentworks.common.db.SqlDialect;
import io.github.Nyameph.nyaentworks.common.pinyin.PinyinSyllable;
import io.github.Nyameph.nyaentworks.song.entity.RhymeEntry;
import io.github.Nyameph.nyaentworks.song.mapper.RhymeEntryMapper;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * 韵脚词典去重（填词助手设计 §4.4）：把「同一个词、同一个音」的重复行并成一行，
 * 让唯一键 {@code uk_text_pinyin(text, pinyin)} 能加得上去，也让它长期成立。
 *
 * <p><b>为什么会有重复</b>：旧唯一键是 {@code uk_entry(entry_type, text, pinyin)}，
 * {@code entry_type} 参与唯一性，于是「同一个字、同一个音，一个存成 CHAR 一个存成 WORD」
 * 不撞键、两行都留。根因是 {@code RhymeTsv} 曾把单字硬编码成 {@code "WORD"}（已修）。
 *
 * <p><b>合并不掉的东西</b>：真正的多音词（末音节声调不同，如「上」shǎng/shàng、
 * 「一场」chǎng/cháng）一行都不动 —— 那是两个读音，不是重复。
 *
 * <p><b>为什么要挂在生产任务末尾自动跑</b>：被合并掉的行会被对应的生产任务原样灌回来
 * （语料重扫重建 CORPUS、{@code /seed} 强制重跑、{@code /import-open} 重导），
 * 所以「跑一次 SQL 就干净了」不成立。规则全从组内现算，本任务<b>幂等</b>，重复跑是安全的。
 *
 * <p><b>取消值口径</b>（用户 2026-09-14 裁决）：
 * <ul>
 *   <li>来源优先级 {@code MANUAL > XLSX > CORPUS > MODERN > OPEN}（见 {@link #SOURCE_ORDER}）</li>
 *   <li>冲突字段：{@code tier} 取组内非 0 最小值（最常用），其余取<b>胜出行</b>的值</li>
 *   <li>为空的字段（{@code word_class} / {@code note}）从组内其他行补齐</li>
 *   <li>轻声对：单字删轻声留带调；<b>多字词保留词表的真实读音</b> —— CORPUS 行的读音是
 *       拿词的尾字当单字猜的（{@code CorpusService} 的 {@code resolveReading(tailCp, null)}），
 *       多字词以词表为准，所以 CORPUS 行让位（<b>与有没有轻声无关</b>，见 {@link #plan}）</li>
 * </ul>
 *
 * <p><b>数据红线（§9.4）</b>：{@link DedupResult} 全是数字，日志与 {@code result_json}
 * 里绝不出现词条原文 —— 词条来自用户词表与语料。
 */
@Service
@RequiredArgsConstructor
public class RhymeDedupService {

    /**
     * 来源优先级（用户 2026-09-14 指定），索引越小越优先。
     * 五档之外的值（将来新加的来源）排最后：不假想它的优先级。
     */
    static final List<String> SOURCE_ORDER = List.of(
            RhymeService.SOURCE_MANUAL, RhymeService.SOURCE_XLSX, RhymeService.SOURCE_CORPUS,
            RhymeService.SOURCE_MODERN, RhymeService.SOURCE_OPEN);

    /** 一次删 / 一次更新多少行（与 {@link RhymeService} 的 {@code INSERT_BATCH} 同量级）。 */
    private static final int BATCH = 500;

    private final RhymeEntryMapper mapper;

    /**
     * 去重计划：{@code deleteIds} 要删的行、{@code updates} 要改的行（只装与现状不同的）。
     *
     * @param groups 扫到的 text 组数（有重复的 text 数，不是组数里被改的那些）
     * @param failed 读音拆不出、跳过没猜的行数
     */
    public record DedupPlan(List<Long> deleteIds, List<RhymeEntry> updates, int groups, int failed) {
    }

    /** 任务结果（进 {@code result_json} 与任务说明行）：<b>只有计数，没有词</b>。 */
    public record DedupResult(int scanned, int groups, int deleted, int updated,
                              int entryTypeFixed, int failed) {
    }

    // ==================== IO ====================

    /**
     * 跑一轮去重：取候选行 → 算计划 → <b>先删后改</b> → 扫 entry_type 脏行。
     *
     * <p><b>顺序不能反</b>：旧唯一键 {@code uk_entry} 含 {@code entry_type}，把 XLSX 的 WORD
     * 行改成 CHAR 会撞上同 text 同音的 MODERN CHAR 行，只有删除步骤已经把后者清掉才不撞。
     */
    public DedupResult dedup(AsyncTaskContext context) {
        context.message("正在扫重复词…");
        List<RhymeEntry> rows = mapper.selectDuplicateCandidates();
        DedupPlan plan = plan(rows);
        context.message("待查 " + rows.size() + " 行，命中 " + plan.deleteIds().size() + " 行重复");

        int deleted = 0;
        List<Long> ids = plan.deleteIds();
        for (int from = 0; from < ids.size(); from += BATCH) {
            deleted += mapper.deleteByIds(ids.subList(from, Math.min(ids.size(), from + BATCH)));
        }
        int updated = 0;
        for (RhymeEntry upd : plan.updates()) {
            updated += mapper.updateById(upd);
        }

        // 顺带扫掉同源的存量脏行：RhymeTsv 那个硬编码把单字标成了 WORD。1 字的 text 必然是
        // CHAR，本就该这么算（与三处调用点同口径）。幂等：第二次跑命中 0 行。
        int entryTypeFixed = mapper.update(null, Wrappers.<RhymeEntry>lambdaUpdate()
                .set(RhymeEntry::getEntryType, "CHAR")
                .eq(RhymeEntry::getEntryType, "WORD")
                .apply(SqlDialect.charLength("text") + " = 1"));

        if (plan.failed() > 0) {
            context.log("读音拆不出 " + plan.failed() + " 行已跳过（不猜、不动）");
        }
        context.message("删除 " + deleted + " 行 / 补齐 " + updated + " 行 / 修正类型 "
                + entryTypeFixed + " 行");
        return new DedupResult(rows.size(), plan.groups(), deleted, updated, entryTypeFixed,
                plan.failed());
    }

    // ==================== 算法（纯函数，有单测） ====================

    /**
     * 算一份去重计划，不碰库。
     *
     * <p>按 {@code text} 分组，组内三步，<b>顺序不能反</b>：
     *
     * <pre>
     * 第 1 步 · 多字词的语料读音让位（**组级**，与读音无关）：
     *         多字词，组内同时有 CORPUS 行与非 CORPUS 行 → 淘汰全部 CORPUS 行
     *         被淘汰行的字段（word_class / note / tier）并给组内优先级最高的非 CORPUS 行
     *
     * 第 2 步 · 同音完全重复（**簇级**）：剩余行按末音节的去调形式分簇，簇内 pinyin 逐字
     *         相同的只留优先级最高的 1 行
     *
     * 第 3 步 · 轻声对（**簇级**）：簇内既有轻声行（tone == 0）又有带调行 → 淘汰轻声行
     *         （保留全部带调读音）。只有一边时是「同一个音节的不同声调」，属真多音，一行不动
     * </pre>
     *
     * <p><b>第 1 步为什么是组级、且不看读音</b>（2026-09-14，两轮推广）：CORPUS 行的读音取自
     * {@code resolveReading(尾字, null)} —— 拿词的<b>尾字当单字</b>取最常用音，与整词怎么读
     * 无关。它与词表读音<b>同音</b>时本来就会撞唯一键（压根插不进这一行），能留在库里的必然是
     * <b>不同音</b>的错读音。所以「词表里已有这个词」就足以判定 CORPUS 行该走，不必管读音。
     * <br>第一轮把它当成「轻声对 2a」的变体放在簇内，漏了「一场」chǎng/cháng 这类两个都带调的；
     * 第二轮发现<b>跨簇</b>的更多（「主角」jiǎo 猜音 vs jué 词表 —— 去调后 jiao/jue 都不是一个簇，
     * 实测 24 组），根因是这步本来就不该受「同音」约束。**单字不受影响**：尾字就是整词，猜的也准。
     *
     * <p>取值只在「发生了删除」的地方做，且簇内<b>不跨读音</b>回填：簇里合并完还剩多个不同拼音
     * （真多音混了一个重复对）时，只在「拼音逐字相同」的桶内回填 —— 否则会把 háng 的词性
     * 回填给 xíng。第 1 步的回填目标按优先级选一个人（同 text 的 CORPUS 行 word_class 是按词判的、
     * 不分读音，所以并给谁都不算跨词污染）。
     */
    static DedupPlan plan(List<RhymeEntry> rows) {
        Map<String, List<RhymeEntry>> byText = new LinkedHashMap<>();
        for (RhymeEntry row : rows) {
            byText.computeIfAbsent(row.getText(), k -> new ArrayList<>()).add(row);
        }
        List<Long> deleteIds = new ArrayList<>();
        List<RhymeEntry> updates = new ArrayList<>();
        int failed = 0;
        for (List<RhymeEntry> group : byText.values()) {
            // 读音拆不出的行不猜：不参与合并、也不删（与词表导入 / 补词性同一口径）
            List<RhymeEntry> usable = new ArrayList<>(group.size());
            for (RhymeEntry row : group) {
                if (tailSyllable(row.getPinyin()) == null) {
                    failed++;
                } else {
                    usable.add(row);
                }
            }
            if (usable.size() < 2) {
                continue;
            }
            // 幸存行 id → 它吸收的行（含自己）。被淘汰的行也留着，但 key 进了 dead 就不产出
            Map<Long, List<RhymeEntry>> mergedBy = new LinkedHashMap<>();
            for (RhymeEntry row : usable) {
                mergedBy.computeIfAbsent(row.getId(), k -> new ArrayList<>()).add(row);
            }
            Set<Long> dead = new LinkedHashSet<>();

            // 第 1 步 · 多字词的语料读音让位（组级，与读音无关）
            RhymeEntry keeper = corpusKeeper(usable);
            if (keeper != null) {
                for (RhymeEntry row : usable) {
                    if (RhymeService.SOURCE_CORPUS.equals(row.getSource())) {
                        dead.add(row.getId());
                        absorb(mergedBy, keeper, row);
                    }
                }
            }

            // 第 2、3 步 · 逐簇：同音完全重复 + 轻声对
            for (List<RhymeEntry> cluster : clusterByToneLess(alive(usable, dead))) {
                dedupCluster(cluster, dead, mergedBy);
            }

            if (dead.isEmpty()) {
                continue; // 真多音：一行都不动
            }
            for (RhymeEntry row : usable) {
                if (dead.contains(row.getId())) {
                    deleteIds.add(row.getId());
                }
            }
            // 只写「真的吸收了别人」的幸存行：没并进东西的行一个字节都不动（否则真多音里
            // 那个无辜的读音会被无谓地重写一遍韵部）
            for (Map.Entry<Long, List<RhymeEntry>> e : mergedBy.entrySet()) {
                if (!dead.contains(e.getKey()) && e.getValue().size() > 1) {
                    collectUpdate(e.getValue(), updates);
                }
            }
        }
        return new DedupPlan(List.copyOf(deleteIds), List.copyOf(updates), byText.size(), failed);
    }

    /**
     * 第 1 步的回填目标：多字词里优先级最高的非 CORPUS 行；不该让位时返回 {@code null}
     * （单字词，或组内全是没有词表行可参照的 CORPUS 行 —— 那时删了就等于把这个词抹掉）。
     */
    private static RhymeEntry corpusKeeper(List<RhymeEntry> usable) {
        if (!isMultiChar(usable)) {
            return null;
        }
        RhymeEntry best = null;
        boolean corpus = false;
        for (RhymeEntry row : usable) {
            if (RhymeService.SOURCE_CORPUS.equals(row.getSource())) {
                corpus = true;
            } else if (best == null || compare(row, best) < 0) {
                best = row;
            }
        }
        return best == null ? null : (corpus ? best : null);
    }

    /**
     * 按末音节的<b>去调形式</b>分簇：{@code lǜ} → {@code lü}、{@code lù} → {@code lu}，
     * 两者不同，所以「绿」的两个读音不会被错并（{@link PinyinSyllable#toneLess()} 保留 ü）。
     * 只有同簇才可能「同音」——不同簇是同一个字的不同读音，永远不碰。
     */
    private static List<List<RhymeEntry>> clusterByToneLess(List<RhymeEntry> rows) {
        Map<String, List<RhymeEntry>> clusters = new LinkedHashMap<>();
        for (RhymeEntry row : rows) {
            PinyinSyllable s = tailSyllable(row.getPinyin());
            clusters.computeIfAbsent(s.toneLess(), k -> new ArrayList<>()).add(row);
        }
        return new ArrayList<>(clusters.values());
    }

    /** 一个簇的第 2、3 步：同音完全重复 + 轻声对，被淘汰的行记进 {@code dead}。 */
    private static void dedupCluster(List<RhymeEntry> cluster, Set<Long> dead,
                                     Map<Long, List<RhymeEntry>> mergedBy) {
        // 第 2 步：拼音逐字相同的行只留一个（被淘汰的并给同桶的胜出行 —— 不跨读音）
        Map<String, List<RhymeEntry>> buckets = new LinkedHashMap<>();
        for (RhymeEntry row : cluster) {
            buckets.computeIfAbsent(row.getPinyin(), k -> new ArrayList<>()).add(row);
        }
        List<RhymeEntry> survivors = new ArrayList<>();
        for (List<RhymeEntry> bucket : buckets.values()) {
            RhymeEntry winner = winnerOf(bucket);
            survivors.add(winner);
            for (RhymeEntry row : bucket) {
                if (row != winner) {
                    dead.add(row.getId());
                    absorb(mergedBy, winner, row);
                }
            }
        }
        // 第 3 步：轻声对。两边都要有才算「同一个音、只差声调有无」——
        // 只有轻声行没有带调行时（簇里就一个轻声读音）删了就等于把这个词抹掉，不能删。
        // 多字词的 CORPUS 行已在第 1 步清掉，这里剩的主要是单字（们 mén/men）。
        // 被删的轻声行不并给谁：它与胜出行的拼音不同（men vs mén），合并会跨读音。
        if (hasLight(survivors) && hasToned(survivors)) {
            for (RhymeEntry row : survivors) {
                if (toneOf(row) == 0) {
                    dead.add(row.getId());
                }
            }
        }
    }

    /** 把 {@code row} 并进 {@code keeper} 的合并组（它自己那组会因为 id 进了 dead 而不产出）。 */
    private static void absorb(Map<Long, List<RhymeEntry>> mergedBy, RhymeEntry keeper,
                               RhymeEntry row) {
        mergedBy.get(keeper.getId()).add(row);
    }

    /** 还没被淘汰的行（保序）。 */
    private static List<RhymeEntry> alive(List<RhymeEntry> rows, Set<Long> dead) {
        List<RhymeEntry> out = new ArrayList<>(rows.size());
        for (RhymeEntry row : rows) {
            if (!dead.contains(row.getId())) {
                out.add(row);
            }
        }
        return out;
    }

    /**
     * 幸存行要改哪些列；一列都不用改就不产出（别写空 UPDATE）。
     * {@code merged} 的第一个元素是幸存行本行（见 {@link #plan} 的合并组构造）。
     */
    private static void collectUpdate(List<RhymeEntry> merged, List<RhymeEntry> out) {
        RhymeEntry survivor = merged.getFirst();
        RhymeEntry upd = new RhymeEntry();
        upd.setId(survivor.getId());
        boolean changed = false;

        String entryType = entryTypeOf(survivor.getText());
        if (!Objects.equals(entryType, survivor.getEntryType())) {
            upd.setEntryType(entryType);
            changed = true;
        }
        int tier = mergedTier(merged);
        if (!Objects.equals(survivor.getTier(), tier)) {
            upd.setTier(tier);
            changed = true;
        }
        String wordClass = firstNonBlank(merged, RhymeEntry::getWordClass);
        if (StringUtils.isBlank(survivor.getWordClass()) && StringUtils.isNotBlank(wordClass)) {
            upd.setWordClass(wordClass);
            changed = true;
        }
        String note = firstNonBlank(merged, RhymeEntry::getNote);
        if (StringUtils.isBlank(survivor.getNote()) && StringUtils.isNotBlank(note)) {
            upd.setNote(note);
            changed = true;
        }
        // 韵部一律由读音重算（确定性派生，不参与取舍）：唯一一个韵部冲突组（翁）靠它取到对的值
        PinyinSyllable s = tailSyllable(survivor.getPinyin());
        if (s != null) {
            if (!Objects.equals(s.finals(), survivor.getFinals())) {
                upd.setFinals(s.finals());
                changed = true;
            }
            if (!Objects.equals(s.rhymeBody(), survivor.getRhymeBody())) {
                upd.setRhymeBody(s.rhymeBody());
                changed = true;
            }
            if (!Objects.equals(s.yun18(), survivor.getYun18())) {
                upd.setYun18(s.yun18());
                changed = true;
            }
        }
        if (changed) {
            out.add(upd);
        }
    }

    // ==================== 小工具 ====================

    /**
     * 词的尾音节：先按空白取尾段（词表的整串拼音 {@code yī xià zi}），再交给
     * {@link RhymeService#lastSyllable}（它只吃单音节与连写）。两个都是包私有的静态方法，
     * 所以本类必须留在 {@code song.fill.rhyme} 包。
     */
    static PinyinSyllable tailSyllable(String pinyin) {
        return RhymeService.lastSyllable(RhymeWordlistService.tailSyllable(pinyin));
    }

    private static int toneOf(RhymeEntry row) {
        PinyinSyllable s = tailSyllable(row.getPinyin());
        return s == null ? -1 : s.tone();
    }

    private static boolean hasLight(List<RhymeEntry> rows) {
        return rows.stream().anyMatch(r -> toneOf(r) == 0);
    }

    private static boolean hasToned(List<RhymeEntry> rows) {
        return rows.stream().anyMatch(r -> toneOf(r) > 0);
    }

    private static boolean isMultiChar(List<RhymeEntry> rows) {
        String text = rows.getFirst().getText();
        return text != null && text.codePointCount(0, text.length()) > 1;
    }

    /** 优先级最高的一行：source 档位小者优先，同档取 id 小者（先入库的），保证可复现。 */
    private static RhymeEntry winnerOf(List<RhymeEntry> bucket) {
        RhymeEntry best = null;
        for (RhymeEntry row : bucket) {
            if (best == null || compare(row, best) < 0) {
                best = row;
            }
        }
        return best;
    }

    private static int compare(RhymeEntry a, RhymeEntry b) {
        int bySource = Integer.compare(sourceRank(a.getSource()), sourceRank(b.getSource()));
        if (bySource != 0) {
            return bySource;
        }
        return Long.compare(idOf(a), idOf(b));
    }

    /** 来源档位：表外（将来新加的来源）排最后，不假想它的优先级。 */
    static int sourceRank(String source) {
        int i = SOURCE_ORDER.indexOf(source);
        return i < 0 ? SOURCE_ORDER.size() : i;
    }

    private static long idOf(RhymeEntry row) {
        return row.getId() == null ? Long.MAX_VALUE : row.getId();
    }

    /** 组内非 0 tier 的最小值（1 最常用）；全 0 / 全空 → 0。 */
    private static int mergedTier(List<RhymeEntry> rows) {
        int min = 0;
        for (RhymeEntry r : rows) {
            Integer t = r.getTier();
            if (t != null && t > 0 && (min == 0 || t < min)) {
                min = t;
            }
        }
        return min;
    }

    /** 组内第一个非空值（「为空的字段从其他合并的字段中补齐」）。 */
    private static String firstNonBlank(List<RhymeEntry> rows,
                                        Function<RhymeEntry, String> getter) {
        for (RhymeEntry r : rows) {
            String v = getter.apply(r);
            if (StringUtils.isNotBlank(v)) {
                return v;
            }
        }
        return null;
    }

    /** 与三处调用点同口径：1 个字是 CHAR，多字是 WORD。 */
    private static String entryTypeOf(String text) {
        String t = StringUtils.defaultString(text);
        return t.codePointCount(0, t.length()) == 1 ? "CHAR" : "WORD";
    }
}
