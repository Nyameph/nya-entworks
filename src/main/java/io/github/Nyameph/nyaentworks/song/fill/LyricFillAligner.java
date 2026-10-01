package io.github.Nyameph.nyaentworks.song.fill;

import io.github.Nyameph.nyaentworks.song.fill.LyricTemplate.FillLine;
import io.github.Nyameph.nyaentworks.song.fill.LyricTemplate.FillNote;
import io.github.Nyameph.nyaentworks.song.fill.LyricTemplate.FillSlot;
import io.github.Nyameph.nyaentworks.song.fill.LyricTemplate.FillTrack;
import io.github.Nyameph.nyaentworks.common.lyric.LyricLine;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 分句与切分（填词工具设计 5 / 6）。纯函数，可单测。
 *
 * <p><b>声部组</b>（多轨合唱 / 和声，2026-09-10 追加）：勾选轨的音符不再无脑混排成一条
 * 时间线，而是先按「时间重叠」聚块（跨轨音符区间相交才连边——首尾相接的接力段不算），
 * 块内按轨比对音符序列：双向任一是子集（onset 差 ≤ {@link #UNISON_TOLERANCE_BLICK}
 * 且同词，容差兜住网格量化差，实测《aLIEz》轨2 对轨1 有 11 处 0.004~0.05s 的量化偏差）
 * 就并成<b>同一句的重复组</b>——时间线上只留每组的代表音符，展开时一个字同时落到
 * 合唱的每一轨（「一句歌词可以匹配多次」）；互相都不是子集（重叠但唱的词不同）则拆成
 * 独立组并标 {@code forcedBreak}——见 {@link #segmentsOf}。
 *
 * <p><b>贪心逐轨走法</b>（2026-09-11 重构，取代 orderByLyric / 括号双流两套启发式）：
 * 文本对齐不再把整条时间线喂给一个单调游标，而是按<b>声部</b>逐句消化——
 * <ol>
 *   <li>「-」延音 / 「br」呼吸跟<b>本轨</b>正在唱的那句（轨还没有归属时跟时间线前一个原子），
 *       它是前一个有效音节的延续，与之前的音符是一个整体；</li>
 *   <li>两条轨<b>不并成一句</b>，除非一段音符完全重合（合唱，见声部组）或歌词是
 *       {@code A（B）} 行尾括号（括号短语与主流短语共享一句）；</li>
 *   <li>声部交叉时<b>同轨连续</b>优先：一个短语从哪条轨开口就在哪条轨上唱完，唱完一句
 *       才看别的轨有没有更早开始的音符、切过去解析下一句——不按时间轮流匹配；</li>
 *   <li>「一句」不止一行歌词：按<b>空格</b>和<b>行尾括号</b>切出的短语都算（走法的匹配单元），
 *       输出仍按 lrc 行聚合。切到一条轨时，从若干未完成短语里选「这条轨接下来的音符
 *       能连上最长一段」的那句（不是歌词顺序的下一句——实测《不问ciaga》30.3s 切到轨0时
 *       歌词游标在第 8 行「反复回忆」、轨0 唱的是第 10 行「我叹那春花秋月不问别离」，
 *       按下一句走会整段错位）。</li>
 * </ol>
 *
 * <p><b>分句三级回落</b>（都作用于代表时间线）：
 * <ol>
 *   <li>贪心逐轨走法（主）——对不上（匹配率低于 {@link #MIN_LYRIC_MATCH_RATIO}）时先试
 *       旧的单调对齐（拼音 / LCS，整个时间线一起走），再不行退时间戳。见 {@link #textAlign}。</li>
 *   <li>时间戳分句——歌词文件缺失、或界面上手填了偏移时：lrc 一行一句，用行时间戳
 *       + 整体偏移把 note 归句。见 {@link #splitByLrc}。</li>
 *   <li>没有 lrc 时——{@code br} 处断开 + 大空隙处断开（只能分「段」，粗）。</li>
 * </ol>
 * 三级之外还有手动：界面任意句边界「断开 / 与上一句合并」，改的是槽位在句间的划分，
 * 结果落 {@code lines_json}（前端做，后端保存时用 {@link #recompute} 重算派生字段）。
 *
 * <p><b>为什么按歌词文本而不是时间戳</b>：lrc 的时间戳是给原声唱的，与模板音符的
 * onset 不是同一套时刻。实测《36.5°C》：整首偏移算下来是 0，但逐句看有累积漂移，
 * 57 句里有 8 处边界切错（上一句末尾多吞一个字、下一句少一个字），界面上很难看出来。
 * 按文本对齐则不受漂移影响，只要歌词和模板唱的是同一首词就准。
 *
 * <p><b>为什么不用空隙分句</b>：实测空隙只有 20 个，且大空隙（20s / 14s / 9.5s）
 * 全是间奏，句间停顿仅 0.125~0.25s 甚至无缝 —— 空隙分不出「句」，只能分「段」；
 * {@code br} 只有 23 个，也不足 47 句。
 */
public final class LyricFillAligner {

    /**
     * 大空隙阈值：1 秒。句间停顿实测仅 0.125~0.25 秒，跨过 1 秒的基本是间奏 / 换段。
     * 无 lrc 时只能靠它粗分，所以刻意取小。
     */
    public static final long GAP_THRESHOLD_BLICK = LyricFillParser.BLICK_PER_SECOND;

    /**
     * 文本对齐的最低匹配率（匹配上的歌词字数 ÷ 歌词总字数）。低于它认为「这份歌词
     * 跟模板唱的不是同一首词」（配错了文件、或是另一个版本），退回时间戳分句。
     * 实测同一首词的不同版本（demo 歌词 vs 模板原词，差几个错别字）约 0.97。
     */
    public static final double MIN_LYRIC_MATCH_RATIO = 0.5;

    /**
     * 走法「对得齐」的匹配率：走法通过了门控但只贴到这个数以下时，页面上给一句弱提示
     * （个别句界可能不准）。实测同一首词的不同版本约 0.97，所以这是「明显缺字」而
     * 非「版本差异」的区间。**只作提示、不改分句** —— 判据用的是走法已有的匹配率。
     */
    public static final double WEAK_MATCH_RATIO = 0.85;

    /** 文本对齐的规模上限（音符数 × 歌词字数）：超了就不做，免得长歌吃光内存。 */
    private static final long MAX_ALIGN_CELLS = 16_000_000L;

    /**
     * 未匹配段独立成句的最小连续未命中可填槽数。断点（br / 大空隙）两侧至少有一段这么长的
     * 未命中槽位才断——更短的零散未命中字（模板多唱的字）维持跟前句的原行为。
     */
    private static final int UNMATCHED_SECTION_MIN = 3;

    /**
     * 合唱同一音符的 onset 判定容差：实测《aLIEz》轨2 与轨1 同词音符的 onset 差
     * 0.004~0.05 秒（网格量化），严格相等会把合唱误拆成两句。
     */
    public static final long UNISON_TOLERANCE_BLICK = Math.round(0.06 * LyricFillParser.BLICK_PER_SECOND);

    private LyricFillAligner() {
    }

    /**
     * 分句结果：句子 + 这次实际用的偏移（无 lrc 时为 null）+ 默认词占位符 + 视觉空位 +
     * 默认词是不是拿 demo 歌词配出来的。{@code defaults} 与 {@code gaps} 都和 {@code lines}
     * 同形状（{@code .get(i).get(k)} 对应 {@code lines.get(i).slots().get(k)}）。
     * {@code defaults} 在文本对齐路径放匹配出的汉字 —— 拼音模板每一格都放，汉字模板只放
     * 它夹着的那几格拼音音符（原词是汉字的那几格，歌词就在音符里，不必灰显）；{@code gaps}
     * 在两条路径都标「这一格之后要画一个空位」（歌词句内的空格，不占槽位、不断句、不能填，
     * 仅作视觉分隔），其余路径空串 / 全 false。
     *
     * <p><b>{@code pinyin} 是模板级、不是句级的</b>：表述「这次的默认词来自 demo 歌词匹配、
     * 没配上的拼音格要标红」（= 文本对齐成功且时间线上有拼音格，见 {@link #demoMatched}）。
     * 某一整句一个汉字都没匹配上时，那一行的 {@code defaults} 整行为空，但页面照样要把那句
     * 的格子标红。用「这行有没有默认词」当判据会让<b>错得最狠的整句全空反而最不报警</b>
     * （见 {@link LyricFillService} 下发的同名字段；直读缓存路径的同一判据见
     * {@link #readHints}）。
     *
     * <p><b>{@code notice}</b> = 这次分句的质量提示（{@code null} = 不提示），由
     * {@link #noticeOf} 拼好整句下发 —— 页面只画不判（与 {@code blockedReason} 同口径）。
     * 文案与阈值只有这一份：两档的分界线是<b>已有的两个决策点</b>（走法过没过
     * {@link #MIN_LYRIC_MATCH_RATIO}、过的人是不是贴到 {@link #WEAK_MATCH_RATIO}），
     * 不另造判据。典型来路是参照歌词配错了文件（见 {@link #noticeOf}）。
     */
    public record SplitResult(List<FillLine> lines, Double offset,
                              List<List<String>> defaults, List<List<Boolean>> gaps,
                              boolean pinyin, String notice) {
    }

    /**
     * 时间线原子：代表轨的一个音符。{@code members} 是各声部对齐到这一拍的成员槽位
     *（声部序；某声部没唱这一拍就没有它的槽位），{@code voiceIds} 与之平行——同一声部
     * 贯穿整组用同一个键，句内按它聚合「原词 + 填写框」组；{@code forcedBreak} = 组前
     * 强制断句（同块重叠但音符不同被拆开的声部，各自成句）。
     */
    private record Timed(long onset, long duration, FillSlot slot,
                         List<FillSlot> members, List<Integer> voiceIds, boolean forcedBreak) {
    }

    /** lrc 元信息行前缀：{@code 词：} / {@code 编曲 : } 等（全角半角冒号都算） */
    private static final Pattern META_PREFIX = Pattern.compile(
            "^(作词|词|作曲|曲|编曲|制作人|混音|母带处理|母带|和声编写|和声|配唱制作人|"
                    + "混音室|录音室|录音|出品|监制|吉他|贝斯|鼓|统筹|发行|策划|封面|文案|"
                    + "演唱|原唱|翻唱|OP|SP)\\s*[:：]");

    /**
     * 【角色】标记（SynthV 合唱工程的演唱者分工，如「【西瓜】」「【合】」）不是歌词：
     * 整行只有标记的整行丢掉，行内的剥掉再参与对齐——不然标记里的字会混进歌词单元，
     * 纯标记行还会把真正的首句顶成第 2 句（实测《九九八十一柔情版》由此整段前奏粘进第 0 句）。
     */
    private static final Pattern ROLE_TAG = Pattern.compile("【[^【】]*】");

    // ==================== 合并时间线（声部组化） ====================

    /**
     * 分句后处理：延音格只出现在<b>句尾</b>，不出现在句首 —— 断句切在延音前时，把句首的
     * 连续延音格并回上一句尾巴（上一句存在才并；音轨最开头的延音没有上一句，原地保留，
     * 那是音符本身以延音起头的特例）。整句都是延音的并进上一句后删掉。
     *
     * <p>defaults / gaps 不用搬：它们按（轨, 音符）键投影，槽集合不变则内容不变 ——
     * 但调用方必须在重排<b>之后</b>再算它们，行结构才对得上。
     */
    private static List<FillLine> relocateLeadingDashes(List<FillLine> lines, Map<Long, Long> onsets) {
        List<FillLine> out = new ArrayList<>(lines.size());
        for (FillLine line : lines) {
            if (out.isEmpty()) {
                out.add(line);
                continue;
            }
            FillLine prev = out.get(out.size() - 1);
            List<FillSlot> slots = line.slots();
            int head = 0;
            while (head < slots.size() && slots.get(head).slotType() == SlotType.DASH) {
                head++;
            }
            if (head == 0) {
                out.add(line);
                continue;
            }
            // 句首延音并回上一句：搬来的延音归上一句里「自己那条轨」的组
            out.set(out.size() - 1,
                    mergeInto(prev, slots.subList(0, head), false, prev.startOnset()));
            List<FillSlot> rest = new ArrayList<>(slots.subList(head, slots.size()));
            if (rest.isEmpty()) {
                continue;   // 整句都是延音：并完删句
            }
            long start = earliestOf(rest, onsets, line.startOnset());
            out.add(new FillLine(rest, originalTextOf(rest), needCountOf(rest), start,
                    groupIdsByTrack(rest, line.groups() == null ? null
                            : line.groups().subList(Math.min(head, line.groups().size()),
                            line.groups().size()))));
        }
        return out;
    }

    /**
     * 分句后处理：换气（{@code br}）只出现在<b>句首</b>，不出现在句尾 —— 走法把 br 跟着
     * 正在唱的短语挂到了句尾，而 br 是换气点、也是<b>这条轨</b>下一句起唱的标志。把句尾
     * （最后一个可填槽之后的第一个 br 起，含其后跟随的延音——那是把换气拉长）的槽位送到
     * 「这条轨后面第一次开口」的那句句首。
     *
     * <p>找的是<b>同轨</b>的下一句，不是紧挨着的下一句：下一句常常是别的声部在唱。实测
     * 《栖凰》「谯鼓响」那句（轨 4 / 8）被轨 7 / 11 / 12 的句尾 br 糊住，句尾 br 一搬进来
     * 就多一行 br，还因为组号按首现序拼、两条轨并进了一组，导出歌词重复成两个「谯鼓响」。
     * 这条轨后面不再开口（收尾换气）的槽位原地留；整句 br / 延音（没有可填槽）的句不往外送，
     * 避免级联。
     *
     * <p>先于 {@link #relocateLeadingDashes} 跑：先把 br 送走，再把下一句句首的延音并回
     * 上句尾巴 ——「我 br | - 随」两步收敛到「我 - | br 随」。defaults / gaps 与延音回迁
     * 同口径：按（轨, 音符）键投影，调用方必须在重排<b>之后</b>再算它们。
     *
     * <p>目标句的句首时间<b>不</b>因迁入的 br 提前（用目标句自己的 {@code startOnset}）：
     * 句首时间是导出 lrc 的时间戳，该是这句<b>开口唱</b>的时刻。实测《还是会寂寞》轨 2 的
     * 首个音符是孤立的 {@code br}（87.0 s，之后到 140.25 s 才唱 "da"），按 min 提前会把
     * "da da li lai" 这行导成 1:27，还让它排到上一行（130.0 s）前面去。
     */
    static List<FillLine> relocateTrailingBreaths(List<FillLine> lines) {
        Map<Integer, List<Integer>> linesOfTrack = new HashMap<>();   // 轨 → 它出现在哪些句（升序）
        for (int i = 0; i < lines.size(); i++) {
            for (FillSlot slot : lines.get(i).slots()) {
                List<Integer> where = linesOfTrack.computeIfAbsent(slot.trackIndex(),
                        t -> new ArrayList<>());
                if (where.isEmpty() || where.getLast() != i) {
                    where.add(i);
                }
            }
        }
        Map<Integer, List<FillSlot>> head = new HashMap<>();    // 目标句 → 并到它句首的槽位
        Map<Integer, FillLine> trimmed = new HashMap<>();       // 抽走尾段后剩下的那些句
        for (int i = 0; i < lines.size(); i++) {
            FillLine line = lines.get(i);
            int lastFillable = -1;
            for (int k = 0; k < line.slots().size(); k++) {
                if (line.slots().get(k).slotType().fillable()) {
                    lastFillable = k;
                }
            }
            if (lastFillable < 0) {
                continue;   // 整句 br / 延音：原地不动
            }
            int breath = -1;
            for (int k = lastFillable + 1; k < line.slots().size(); k++) {
                if (line.slots().get(k).slotType() == SlotType.BREATH) {
                    breath = k;
                    break;
                }
            }
            if (breath < 0) {
                continue;   // 句尾没有 br
            }
            List<FillSlot> kept = new ArrayList<>(line.slots().size());
            List<Integer> keptIds = new ArrayList<>(line.slots().size());
            for (int k = 0; k < line.slots().size(); k++) {
                FillSlot slot = line.slots().get(k);
                int target = k < breath ? -1 : nextLineOf(linesOfTrack.get(slot.trackIndex()), i);
                if (target < 0) {
                    kept.add(slot);
                    keptIds.add(idOf(line.groups(), k));
                } else {
                    head.computeIfAbsent(target, t -> new ArrayList<>()).add(slot);
                }
            }
            if (kept.size() < line.slots().size()) {
                trimmed.put(i, new FillLine(kept, originalTextOf(kept), needCountOf(kept),
                        line.startOnset(), keptIds));
            }
        }
        List<FillLine> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            FillLine line = trimmed.getOrDefault(i, lines.get(i));
            List<FillSlot> moved = head.getOrDefault(i, List.of());
            if (moved.isEmpty()) {
                out.add(line == lines.get(i) ? line : new FillLine(line.slots(),
                        line.originalText(), line.needCount(),
                        line.startOnset(), groupIdsByTrack(line.slots(), line.groups())));
                continue;
            }
            // 句首时间不因迁入的 br 提前：那是这句开口唱的时刻，br 比它早多少都不算（见 javadoc）
            out.add(mergeInto(line, moved, true, line.startOnset()));
        }
        return out;
    }

    /** {@code where} 里严格晚于 {@code i} 的第一个句下标；没有了返回 -1。 */
    private static int nextLineOf(List<Integer> where, int i) {
        if (where == null) {
            return -1;
        }
        for (int at : where) {
            if (at > i) {
                return at;
            }
        }
        return -1;
    }

    /**
     * 句内组号：按「组里最小的轨号」升序 0 递增 —— 一个声部组的身份就是它的轨（合唱 / 和声
     * 并成一组的轨，见 {@link #splitBlock} 与 {@link #timedOf}），编号按工程里的轨序走，
     * 页面自上而下、导出与批量填词同一口径。{@code ids} = 逐槽原始组号（null / 短了按 0 兜底，
     * 与历史数据「整句一组」一致）。
     *
     * <p>不能按「句内首现序」编号：那是句内局部的号，两句拼一起时同一个号指的不是同一组，
     * 搬来的槽位会并进别的轨的组里，一行混进两条轨（参见 {@link #prepend}）。
     */
    static List<Integer> groupIdsByTrack(List<FillSlot> slots, List<Integer> ids) {
        Map<Integer, Integer> minTrack = new HashMap<>();
        for (int k = 0; k < slots.size(); k++) {
            minTrack.merge(idOf(ids, k), slots.get(k).trackIndex(), Math::min);
        }
        Map<Integer, Integer> renumbered = new HashMap<>();
        minTrack.entrySet().stream()
                .sorted(Comparator.<Map.Entry<Integer, Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparingInt(Map.Entry::getKey))
                .forEach(e -> renumbered.put(e.getKey(), renumbered.size()));
        List<Integer> out = new ArrayList<>(slots.size());
        for (int k = 0; k < slots.size(); k++) {
            out.add(renumbered.get(idOf(ids, k)));
        }
        return List.copyOf(out);
    }

    /** 逐槽原始组号：缺省按 0（历史数据没有 groups → 整句一组）。 */
    private static int idOf(List<Integer> ids, int k) {
        return ids != null && k < ids.size() ? ids.get(k) : 0;
    }

    /**
     * 把 {@code moved} 槽位并进 {@code dest} 句（{@code atHead} = 并到句首，否则接到句尾）：
     * 搬来的槽位归「dest 句里自己那条轨」的组 —— 组身份是轨，所以按轨找组；dest 句没有这条
     * 轨就新开一组。
     *
     * <p>不能按组号拼：两边都是句内局部号，同一个号在两句里往往是不同的轨（实测《栖凰》
     * 「谯鼓响」句被搬来的 br 糊成「#11+#8」一组，导出时两组互不包含，歌词就重复了一遍）。
     */
    private static FillLine mergeInto(FillLine dest, List<FillSlot> moved, boolean atHead,
                                      long start) {
        List<Integer> destIds = dest.groups();
        Map<Integer, Integer> groupOfTrack = new HashMap<>();
        for (int k = 0; k < dest.slots().size(); k++) {
            groupOfTrack.putIfAbsent(dest.slots().get(k).trackIndex(), idOf(destIds, k));
        }
        int fresh = destIds == null || destIds.isEmpty()
                ? 0 : destIds.stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
        List<Integer> movedIds = new ArrayList<>(moved.size());
        for (FillSlot slot : moved) {
            // 注意装箱：三元里写 0 会把 get 的结果拆箱，查不到轨直接 NPE
            Integer group = destIds == null ? Integer.valueOf(0) : groupOfTrack.get(slot.trackIndex());
            movedIds.add(group != null ? group : fresh++);
        }
        List<Integer> destSlotIds = new ArrayList<>(dest.slots().size());
        for (int k = 0; k < dest.slots().size(); k++) {
            destSlotIds.add(idOf(destIds, k));
        }
        List<FillSlot> slots = new ArrayList<>(moved.size() + dest.slots().size());
        List<Integer> ids = new ArrayList<>(moved.size() + dest.slots().size());
        slots.addAll(atHead ? moved : dest.slots());
        ids.addAll(atHead ? movedIds : destSlotIds);
        slots.addAll(atHead ? dest.slots() : moved);
        ids.addAll(atHead ? destSlotIds : movedIds);
        return new FillLine(slots, originalTextOf(slots), needCountOf(slots), start,
                groupIdsByTrack(slots, ids));
    }

    /** 槽位里最早的一个 onset（查不到音符就返回 {@code fallback}）。 */
    private static long earliestOf(List<FillSlot> slots, Map<Long, Long> onsets, long fallback) {
        return slots.stream()
                .map(s -> onsets.get(key(s.trackIndex(), s.noteIndex())))
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .min()
                .orElse(fallback);
    }

    /** 全部音符的（轨, 下标）→ onset：延音回指句首时间戳用。 */
    private static Map<Long, Long> onsetsOf(List<FillTrack> tracks) {
        Map<Long, Long> onsets = new HashMap<>();
        for (FillTrack track : tracks) {
            List<FillNote> notes = track.notes();
            for (int i = 0; i < notes.size(); i++) {
                onsets.put(key(track.trackIndex(), i), notes.get(i).onset());
            }
        }
        return onsets;
    }

    /**
     * 勾选轨合并成的代表时间线（每声部组一个代表槽位）。{@code selected} 为 null / 空 =
     * 全部歌唱轨。
     */
    public static List<FillSlot> merge(List<FillTrack> tracks, List<Integer> selected) {
        return timeline(tracks, selected).stream().map(Timed::slot).toList();
    }

    /** 一次待归组音符：所属轨 + 轨内下标 + 音符本体。 */
    private record Item(int track, int noteIndex, FillNote note) {
    }

    private static List<Timed> timeline(List<FillTrack> tracks, List<Integer> selected) {
        boolean all = selected == null || selected.isEmpty();
        List<Item> items = new ArrayList<>();
        for (FillTrack track : tracks) {
            if (!all && !selected.contains(track.trackIndex())) {
                continue;
            }
            List<FillNote> notes = track.notes();
            for (int i = 0; i < notes.size(); i++) {
                items.add(new Item(track.trackIndex(), i, notes.get(i)));
            }
        }
        if (items.isEmpty()) {
            return List.of();
        }
        // 同 onset 用轨序 + 轨内下标兜底，保证顺序稳定可复现
        items.sort(Comparator.comparingLong((Item it) -> it.note().onset())
                .thenComparingInt(Item::track)
                .thenComparingInt(Item::noteIndex));

        // 跨轨音符区间相交才连块（首尾相接的接力段不算重叠）；同轨音符不连边，
        // 自己跟自己永远是一个组的候选。onset 已排序，越过本音符的结束点即可停。
        int n = items.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < n; i++) {
            Item a = items.get(i);
            long aEnd = a.note().onset() + a.note().duration();
            for (int j = i + 1; j < n; j++) {
                Item b = items.get(j);
                if (b.track() == a.track()) {
                    continue;
                }
                if (b.note().onset() >= aEnd) {
                    break;
                }
                parent[find(parent, j)] = find(parent, i);
            }
        }
        // 连通块（块内 = 时间交织的一段），块间时间天然不重叠、按序输出
        Map<Integer, List<Integer>> blocks = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            blocks.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
        }

        List<Timed> out = new ArrayList<>();
        for (List<Integer> block : blocks.values()) {
            out.addAll(splitBlock(blockOf(block, items)));
        }
        return out;
    }

    /** 块号 → 块内音符（按 onset 序）。 */
    private static List<Item> blockOf(List<Integer> indexes, List<Item> items) {
        List<Item> block = new ArrayList<>();
        for (int i : indexes) {
            block.add(items.get(i));
        }
        return block;
    }

    /**
     * 一个块（时间交织的一段）→ 声部组时间线：块内按轨分组，双向任一是子集的轨并成
     * 同一组（合唱 / 和声），剩下的各自成组；同块拆开的多组里，除时间最早的组外
     * 全部标强制断句（重叠但唱的词不同 → 各自算一句，需求 1）。
     */
    private static List<Timed> splitBlock(List<Item> block) {
        Map<Integer, List<Item>> byTrack = new LinkedHashMap<>();
        for (Item item : block) {
            byTrack.computeIfAbsent(item.track(), k -> new ArrayList<>()).add(item);
        }
        List<List<Item>> voices = new ArrayList<>(byTrack.values());
        int m = voices.size();
        int[] parent = new int[m];
        for (int i = 0; i < m; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < m; i++) {
            for (int j = i + 1; j < m; j++) {
                if (compatible(voices.get(i), voices.get(j))) {
                    parent[find(parent, j)] = find(parent, i);
                }
            }
        }
        Map<Integer, List<List<Item>>> parts = new LinkedHashMap<>();
        for (int i = 0; i < m; i++) {
            parts.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(voices.get(i));
        }
        List<List<List<Item>>> groups = new ArrayList<>(parts.values());
        groups.sort(Comparator.comparingLong(p -> p.getFirst().getFirst().note().onset()));
        boolean split = groups.size() > 1;
        List<Timed> out = new ArrayList<>(groups.size());
        for (int p = 0; p < groups.size(); p++) {
            out.addAll(timedOf(groups.get(p), split && p > 0));
        }
        return out;
    }

    /**
     * 一个声部组 → 时间线原子（代表轨的<b>每个</b>音符一个）：代表 = 可填音符最多的成员
     *（平局取轨序小）；各声部与代表按 onset 容差 + 同词对齐到每一拍，某声部没唱这拍
     * 就没有它的槽位（包含关系的短声部）。声部键 = 轨号——一个轨就是一个声部，
     * 句内按它聚合成一行「原词 + 填写框」（同轨的两段在同一句里连成一行）。
     */
    private static List<Timed> timedOf(List<List<Item>> members, boolean forcedBreak) {
        List<List<Item>> ordered = new ArrayList<>(members);
        ordered.sort(Comparator.comparingInt(p -> p.getFirst().track()));
        int best = 0;
        for (int i = 1; i < ordered.size(); i++) {
            if (fillableCount(ordered.get(i)) > fillableCount(ordered.get(best))) {
                best = i;
            }
        }
        List<Item> rep = ordered.get(best);
        // 各声部 ↔ 代表的逐拍对齐：at[v][i] = 声部 v 对齐到代表第 i 拍的音符下标（-1 没唱）
        List<List<Integer>> at = new ArrayList<>(ordered.size());
        for (int v = 0; v < ordered.size(); v++) {
            at.add(v == best ? identity(rep.size()) : alignTo(ordered.get(v), rep));
        }
        List<Timed> out = new ArrayList<>(rep.size());
        for (int i = 0; i < rep.size(); i++) {
            Item head = rep.get(i);
            List<FillSlot> slots = new ArrayList<>(ordered.size());
            List<Integer> ids = new ArrayList<>(ordered.size());
            for (int v = 0; v < ordered.size(); v++) {
                int hit = at.get(v).get(i);
                if (hit >= 0) {
                    Item member = ordered.get(v).get(hit);
                    slots.add(new FillSlot(member.track(), member.noteIndex(),
                            member.note().lyrics(), member.note().slotType(),
                            member.note().glottal()));
                    ids.add(voiceId(ordered.get(v)));
                }
            }
            // 强制断句只标组的首原子：整组连续成句，不会自己拆碎自己
            out.add(new Timed(head.note().onset(), head.note().duration(),
                    new FillSlot(head.track(), head.noteIndex(),
                            head.note().lyrics(), head.note().slotType(),
                            head.note().glottal()),
                    List.copyOf(slots), List.copyOf(ids), forcedBreak && i == 0));
        }
        return out;
    }

    private static List<Integer> identity(int n) {
        List<Integer> ids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ids.add(i);
        }
        return ids;
    }

    /**
     * 声部对齐到代表的逐拍表：{@code at[j] = 声部对齐到代表第 j 拍的音符下标（-1 该拍没唱）}。
     * 表长 = 代表的音符数 —— 短声部（少唱几拍，比如《aLIEz》轨2 比 轨1 少一段）在没唱的
     * 拍上就是 -1，槽位展开时那一拍不产生它的成员格。
     *
     * <p>这里判等<b>只认同词</b>（延音的宽容只用于 {@link #compatible} 的「谁被谁包含」）：
     * 延音要留在<b>自己那条轨</b>的句尾（实测《红马》括号声部中间那三个 {@code -}，见
     * {@code LyricFillAlignerTest.brackets_innerDashesStayWithTheirOwnVoice}），
     * 一旦让延音去贴隔壁声部同拍的音符，它就会跑到别人那一句里；配不上对的延音就是不进
     * 时间线（导出按音符走，不受影响）。
     */
    private static List<Integer> alignTo(List<Item> voice, List<Item> rep) {
        int[] at = new int[rep.size()];
        Arrays.fill(at, -1);
        int i = 0;
        int j = 0;
        while (i < voice.size() && j < rep.size()) {
            long ov = voice.get(i).note().onset();
            long or = rep.get(j).note().onset();
            if (Math.abs(ov - or) <= UNISON_TOLERANCE_BLICK
                    && Objects.equals(voice.get(i).note().lyrics(), rep.get(j).note().lyrics())) {
                at[j] = i;
                i++;
                j++;
            } else if (ov < or) {
                i++;
            } else {
                j++;
            }
        }
        List<Integer> result = new ArrayList<>(rep.size());
        for (int x : at) {
            result.add(x);
        }
        return result;
    }

    /** 声部键 = 轨号：一个轨就是一个声部，同一句里的同一轨聚成一行「原词 + 填写框」。 */
    private static int voiceId(List<Item> voice) {
        return voice.getFirst().track();
    }

    /** 一轨的音符序列里可填（含点开的延音）的数量。 */
    private static int fillableCount(List<Item> voice) {
        return (int) voice.stream().filter(it -> it.note().slotType().fillable()).count();
    }

    /**
     * 两轨序列是否「同一句的重复组」：按 onset 容差 + 同词双指针配对，双向任一轨的
     * 音符全部配上对方（相等或包含都算；交错重叠的不算 → 拆句）。
     *
     * <p><b>比的是「词」，延音先拿掉</b>（2026-09-13，第 56 条）：{@code -} 是「上一音的
     * 延续」、不是一个词，同一个音两轨写法不同（实测《气泡少女》主旋律把尾音拼成拼音
     * {@code ao}、主和声留个 {@code -}）时，旧口径 2 个音符只配上 1 对、两轨都不算被包含
     * → 和声自成一组并标强制断句 → 孤立原子自成一「句」，整首从第 34 句起句句错位。
     * 拿掉延音只影响「谁被谁包含」的判定，<b>不影响槽位</b>：延音仍在时间线上、仍留在
     * 自己那条轨那一句里（见 {@link #alignTo}）。
     */
    private static boolean compatible(List<Item> a, List<Item> b) {
        List<Item> wordsA = wordsOnly(a);
        List<Item> wordsB = wordsOnly(b);
        if (wordsA.isEmpty() || wordsB.isEmpty()) {
            // 有一侧整段都是延音（没词）：没有词可比，退回原口径 —— 延音也得自己跟对面
            // 同拍同写法才算（实测《红马》括号声部中间那三个 `-` 就是这样留在本轨的）
            int paired = pairCount(a, b);
            return paired == a.size() || paired == b.size();
        }
        int paired = pairCount(wordsA, wordsB);
        return paired == wordsA.size() || paired == wordsB.size();
    }

    /**
     * 只留唱词的音符：延音 {@code -} / 静音占位 {@code 0} 不是词（见 {@link #compatible}）。
     *
     * <p>{@code 0} 与 {@code -} 同族（都是「不主动填词、不进歌词、只留在回填文本里」的记号格）：
     * 同一个音两轨写法不同（主旋律写 {@code ao}、和声留个 {@code 0}）时，照词比对会判不等 ——
     * 和声因此自成一组并标强制断句，正是第 56 条 {@code -} 踩过的坑。{@code br} 不在此列
     * （换气是两条轨都要写的节拍记号，行为维持原样）。
     */
    private static List<Item> wordsOnly(List<Item> voice) {
        List<Item> out = new ArrayList<>(voice.size());
        for (Item item : voice) {
            SlotType type = item.note().slotType();
            if (type != SlotType.DASH && type != SlotType.ZERO) {
                out.add(item);
            }
        }
        return out;
    }

    /** 有序序列的双指针配对数：onset 差在容差内且同词的音符对。 */
    private static int pairCount(List<Item> a, List<Item> b) {
        int i = 0;
        int j = 0;
        int paired = 0;
        while (i < a.size() && j < b.size()) {
            long oa = a.get(i).note().onset();
            long ob = b.get(j).note().onset();
            if (Math.abs(oa - ob) <= UNISON_TOLERANCE_BLICK) {
                if (Objects.equals(a.get(i).note().lyrics(), b.get(j).note().lyrics())) {
                    paired++;
                    i++;
                    j++;
                } else if (oa <= ob) {
                    i++;
                } else {
                    j++;
                }
            } else if (oa < ob) {
                i++;
            } else {
                j++;
            }
        }
        return paired;
    }

    /** 并查集寻根（路径压缩）。 */
    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    // ==================== 分句 ====================

    /**
     * 分句：优先按歌词文本对齐，对不上再按时间戳，没有歌词则按 {@code br} + 大空隙。
     *
     * @param manualOffset 手填的整体偏移（秒）。给了就<b>强制走时间戳分句</b>——它表达的是
     *                     「我明确要求按时间对」；null = 走自动路径（先文本后时间戳）
     */
    public static SplitResult split(List<FillTrack> tracks, List<Integer> selected,
                                    List<LyricLine> lrcLines, Double manualOffset) {
        return split(tracks, selected, lrcLines, manualOffset, null);
    }

    /**
     * 同上，{@code bpm} = 模板曲速（song_original_setting.bpm）：秒换算 / 时间戳分句的
     * lrc 秒对齐都按曲速折算（见 {@link LyricFillParser#secondsOf(long, Double)}）。
     */
    public static SplitResult split(List<FillTrack> tracks, List<Integer> selected,
                                    List<LyricLine> lrcLines, Double manualOffset, Double bpm) {
        List<Timed> timeline = timeline(tracks, selected);
        if (timeline.isEmpty()) {
            return new SplitResult(List.of(), null, List.of(), List.of(), false, null);
        }
        List<LyricLine> lyrics = lyricLines(lrcLines);
        // 这次分句的质量来路（提示用）。没做文本对齐的两条路（手填偏移 / 没有参照歌词）
        // 恒为 OFF —— 没有「对不上」这回事，也就没有提示。
        AlignQuality quality = new AlignQuality(Mode.OFF, 0);
        if (!lyrics.isEmpty() && manualOffset == null) {
            // 贪心逐轨走法（拼音模板走拼音↔汉字、汉字模板按原值，行尾括号 A（B）拆括号短语）。
            // 一次对齐同时反推句边界 + 默认词占位符 + 视觉空位 + 括号声部标记。
            TextAlignRun run = textAlignRun(timeline, lyrics);
            if (run.text() != null) {
                TextAlign text = run.text();
                Units u = text.units();
                Alignment a = text.alignment();
                Assign assign = text.assign();
                List<FillLine> lines = relocateLeadingDashes(
                        relocateTrailingBreaths(buildLines(text.timeline(), assign)),
                        onsetsOf(tracks));
                // 文本对齐下不存在「一个整体偏移」这回事，返回 null（页面的偏移框留空）；
                // 默认词：拼音模板每一格都给，汉字模板只给夹在中间的拼音格（原词是汉字的那几格
                // 歌词就在音符里，灰显同一个字没意义 —— 见 defaults(...) 的 pinyinSlotsOnly）
                return new SplitResult(lines, null,
                        defaults(lines, u.fillable(), a.noteToLyric(), a.units(), !a.pinyin()),
                        gaps(lines, u.fillable(), a.noteToLyric(), a.gapAfter()),
                        demoMatched(text), noticeOf(run.quality()));
            }
            // 文本对齐失败（走法没过门控、单调对齐也没对上）：匹配率留着，下面按时间戳分句，
            // 提示照发 —— 「对不上」这件事与后面用哪条路分句无关
            quality = run.quality();
        }
        List<Double> starts = startsOf(lyrics);
        if (starts.isEmpty()) {
            List<FillLine> lines = relocateLeadingDashes(
                    relocateTrailingBreaths(splitByBreathAndGap(timeline)), onsetsOf(tracks));
            return new SplitResult(lines, null, emptyDefaults(lines), breathGaps(lines), false,
                    noticeOf(quality));
        }
        Double offset = manualOffset != null ? manualOffset : autoOffset(timeline, starts, bpm);
        double effective = offset == null ? 0 : offset;
        List<FillLine> lines = relocateLeadingDashes(
                relocateTrailingBreaths(splitByLrc(timeline, starts, effective, bpm)),
                onsetsOf(tracks));
        return new SplitResult(lines, effective, emptyDefaults(lines), breathGaps(lines), false,
                noticeOf(quality));
    }

    /** 歌词行里所有有时间的起点，升序 */
    private static List<Double> startsOf(List<LyricLine> lyrics) {
        return lyrics.stream()
                .map(LyricLine::start)
                .filter(Objects::nonNull)
                .sorted()
                .toList();
    }

    /**
     * 只留「歌词行」：丢掉无时间轴、空白、以及 lrc 开头的标题行与词曲编曲等元信息行。
     *
     * <p><b>必须过滤</b>：原曲 lrc（非 demo）开头是 {@code [00:00.00]曲名 - 歌手} 加十几行
     * {@code 词：/曲：/编曲：…}。它们会被当成歌词参与文本对齐（把边界带偏），时间戳路径下
     * 还会让 {@link #autoOffset} 把首句锚到 0 秒、偏移算成「−首个 note 时刻」——两种错法
     * 都表现为整首错位、界面上看不出来。demo 歌词（纯歌词）不受影响。
     */
    public static List<LyricLine> lyricLines(List<LyricLine> lrcLines) {
        return cleanLines(lrcLines).stream()
                .filter(line -> line.start() != null)
                .toList();
    }

    /**
     * 清洗规则（与 {@link #lyricLines} 完全相同的那一套），但<b>保留无时间轴的行</b>。
     * 语料采集用这个：txt 歌词没有时间轴（{@code start} 全为 null），按 lyricLines 走会
     * 得到零行且静默（填词助手设计 §5.2）。
     *
     * <p>标题行判定把 {@code start == null} 也算「行首」：txt 的首行「曲名 - 歌手」
     * 同样该滤掉。对 lyricLines 无影响 —— 那边 start == null 的行本来就被过滤，
     * 永远走不到这个分支。改清洗规则两处必须一起看（{@code LyricFillAlignerTest} 守着）。
     */
    public static List<LyricLine> cleanLines(List<LyricLine> lrcLines) {
        if (lrcLines == null || lrcLines.isEmpty()) {
            return List.of();
        }
        List<LyricLine> result = new ArrayList<>();
        boolean kept = false;
        for (LyricLine line : lrcLines) {
            if (StringUtils.isBlank(line.text())) {
                continue;
            }
            String text = ROLE_TAG.matcher(line.text().trim()).replaceAll("").trim();
            // 整行只有【角色】标记（如「【西瓜】」）→ 不是歌词行
            if (text.isEmpty()) {
                continue;
            }
            if (META_PREFIX.matcher(text).find()) {
                continue;
            }
            // 首行的「曲名 - 歌手」标题行；只在还没收下任何歌词行时才判，避免误杀正文
            if (!kept && (line.start() == null || line.start() == 0) && text.contains(" - ")) {
                continue;
            }
            kept = true;
            result.add(new LyricLine(line.start(), line.end(), text));
        }
        return result;
    }

    /** 自动偏移 = lrc 首句秒 − 首个可填 note 秒。任一侧缺失时返回 null（按 0 处理）。 */
    public static Double autoOffset(List<Timed> timeline, List<Double> starts) {
        return autoOffset(timeline, starts, null);
    }

    /** 同上，bpm 参与秒换算。 */
    public static Double autoOffset(List<Timed> timeline, List<Double> starts, Double bpm) {
        Double noteSec = timeline.stream()
                .filter(t -> t.slot().slotType().fillable())
                .map(t -> LyricFillParser.secondsOf(t.onset(), bpm))
                .findFirst().orElse(null);
        if (noteSec == null || starts.isEmpty()) {
            return null;
        }
        return starts.getFirst() - noteSec;
    }

    /**
     * 按歌词文本分句（主路径）：把歌词逐字（英文按词）与时间线上可填槽位的原词做最长
     * 公共子序列对齐，第 i 句歌词的首字落在哪个槽位，第 i 句就从那里开始。
     *
     * <p><b>未匹配的槽位跟前一个已匹配的槽位同句</b>，这是为了「模板多唱了一个字」：
     * 实测《36.5°C》里模板在「幸福不知玫瑰香的味道<b>奥</b>」多一个「奥」、歌词没有，
     * 按字数硬切会让它之后的每一句都错位一格；跟上一句则它留在原句，后面照旧对齐。
     * 同理，歌词开头之前的槽位（前奏里唱的字）跟不到任何已匹配槽位，归第一句。
     *
     * <p><b>对不上就返回 null</b>：匹配率低于 {@link #MIN_LYRIC_MATCH_RATIO} 说明歌词
     * 跟模板唱的不是同一首词，交给时间戳分句，别硬套。
     *
     * @return 分句结果；文本对不上时 null（调用方回落时间戳）
     */
    /**
     * 时间线 + 歌词切出的对齐单元：可填槽位原值、歌词单元、每个歌词单元所属行、行内空位标记、
     * 歌词行原文。拼音模板判定与旧的单调回落路径用；走法自己另有短语单元空间（{@link Phrases}）。
     */
    private record Units(List<Timed> fillable, List<String> noteUnits,
                         List<String> lyricUnits, List<Integer> lyricLineOf,
                         List<Boolean> lyricGapAfter, List<LyricLine> lyricLines) {
    }

    private static Units units(List<Timed> timeline, List<LyricLine> lyrics) {
        List<Timed> fillable = timeline.stream()
                .filter(t -> t.slot().slotType().fillable())
                .toList();
        List<String> noteUnits = fillable.stream()
                .map(t -> Objects.toString(t.slot().original(), ""))
                .toList();
        List<String> lyricUnits = new ArrayList<>();
        List<Integer> lyricLineOf = new ArrayList<>();   // 每个歌词单元属于第几行
        List<Boolean> lyricGapAfter = new ArrayList<>(); // 每个歌词单元后是否跟着行内空格（视觉空位）
        for (int i = 0; i < lyrics.size(); i++) {
            int before = lyricUnits.size();
            tokenizeLine(lyrics.get(i).text(), lyricUnits, lyricGapAfter);
            for (int k = before; k < lyricUnits.size(); k++) {
                lyricLineOf.add(i);
            }
        }
        return new Units(fillable, noteUnits, lyricUnits, lyricLineOf, lyricGapAfter, lyrics);
    }

    /**
     * 汉字模板：原词与歌词文本的 LCS 对齐，返回 {@code noteToLyric}（下标 = 可填槽位序，
     * 值 = 歌词单元序，-1 = 没匹配上）。对不上（空 / 超大 / 匹配率低于
     * {@link #MIN_LYRIC_MATCH_RATIO}）返回 null，调用方回落时间戳分句。
     */
    private static int[] textMatch(Units u) {
        return lcsMatch(u.noteUnits(), u.lyricUnits());
    }

    /**
     * 汉字模板的一条流与歌词文本的 LCS 对齐 + 歌词侧匹配率把关（{@link #textMatch} 与括号
     * 双流共用）。
     */
    private static int[] lcsMatch(List<String> noteUnits, List<String> lyricUnits) {
        if (noteUnits.isEmpty() || lyricUnits.isEmpty()
                || (long) noteUnits.size() * lyricUnits.size() > MAX_ALIGN_CELLS) {
            return null;
        }
        int[] match = lcsMap(noteUnits, lyricUnits);
        long matched = Arrays.stream(match).filter(index -> index >= 0).count();
        if ((double) matched / lyricUnits.size() < MIN_LYRIC_MATCH_RATIO) {
            return null;
        }
        return match;
    }

    /**
     * 可填槽位 → 句序号。命中歌词单元的槽位归到它所在行；未命中的跟前一个已命中的同句
     * （{@code noteToLyric[i] == -1} 的槽位沿用上一个句序号，句首之前归第 0 句）。
     */
    private static int[] lineOfNote(List<Integer> lyricLineOf, int[] noteToLyric) {
        int[] lineOfNote = new int[noteToLyric.length];
        int current = 0;
        for (int i = 0; i < noteToLyric.length; i++) {
            if (noteToLyric[i] >= 0) {
                current = lyricLineOf.get(noteToLyric[i]);
            }
            lineOfNote[i] = current;
        }
        return lineOfNote;
    }

    /**
     * 未匹配段的段号（每个可填槽位一个，时间线序）：走法 / 旧路径给出「每个可填槽位 → 行号」
     * 之后，本方法决定「哪些未命中槽位要独立成句」——未命中的零散 1~2 个字跟上一句
     * （并入所在行的桶），但<b>本声部</b>断点（br / 相对本声部上一原子的大空隙）两侧有
     * ≥ {@value #UNMATCHED_SECTION_MIN} 个连续未命中槽位时，段号 +1 起一段。
     *
     * <p><b>游程与空隙都按声部算</b>（2026-09-11 随走法重构）：两条声部交错时，时间线上
     * 前一个原子往往是隔壁声部的——隔壁的命中不该打断本声部「没对上的一串」的计数，
     * 隔壁的音符也不该填掉本声部休止形成的空隙（实测《不问ciaga》轨1 唱的「撒水袖唱罢
     * 旧事舞曲」不在歌词里、与轨0 逐字交错，按时间线口径它的 17 秒休止被轨0 填满、整段
     * 粘进上一句的尾巴）。声部拆分的 {@code forcedBreak} 不再参与分段：一句由哪条轨唱
     * 由走法按短语判定，对不上的和声靠本声部的断点分段。
     *
     * <p><b>声部 = 这条轨</b>（2026-09-21）：空隙量的是「<b>这条轨</b>上一拍」到这一拍的距离，
     * 按代表轨 + 合唱成员轨一起记（{@link #tracksOf}）——成员轨的音符在时间线上由别的轨
     * 代表，只按代表轨记就会拿一个很旧的原子量出假空隙。取最新那个原子：本轨接着唱就不算断点。
     *
     * <p><b>三个记号格怎么站队</b>（需求：断句规则）：{@code -} 是「上一个音的延续」，
     * <b>与它前面的音一体</b> —— 断点落在延音前面时，延音槽位本身按本轨上一个可填槽位归到
     * 上一句（{@link #buildLines} 的 {@code lastByTrack}），再兜一道
     * {@link #relocateLeadingDashes} 把万一落到句首的延音并回上一句尾巴，断点实际落在延音
     * <b>之后</b>；{@code br} 是换气点，<b>与它后面的音一体</b> —— 断点落在 br 这一拍
     * （本方法用它推进段号，br 槽位本身由 {@link #relocateTrailingBreaths} 送到下一句句首）；
     * {@code 0}（静音占位）两者皆可，不站队——它只按本轨的空隙走，和普通音符一样。
     * 三者的空隙都按<b>本轨</b>量：真延音紧接上一拍的尾巴，空隙本就是 0。
     *
     * <p><b>没进歌词的长段独立成句</b>：模板可能唱一段歌词里没有的词（实测《九九八十一柔情版》
     * 主歌1 是原版词的导唱轨、demo 歌词是柔情版新词，整段对不上）——这种段不能整个粘进第一句 /
     * 最后一句（实测曾把 136 个音符粘成一句）。
     */
    private static int[] segmentsOf(List<Timed> timeline, int[] noteToLyric) {
        int total = noteToLyric.length;
        int[] fillableAt = new int[total];
        int fillCount = 0;
        for (int i = 0; i < timeline.size(); i++) {
            if (timeline.get(i).slot().slotType().fillable()) {
                fillableAt[fillCount++] = i;
            }
        }
        // 声部内上一个 / 下一个可填槽位（-1 无）：游程计数不跨声部
        int[] prevOnVoice = new int[total];
        int[] nextOnVoice = new int[total];
        Arrays.fill(prevOnVoice, -1);
        Arrays.fill(nextOnVoice, -1);
        Map<Integer, Integer> lastFillOfVoice = new HashMap<>();
        for (int f = 0; f < total; f++) {
            int voice = timeline.get(fillableAt[f]).slot().trackIndex();
            prevOnVoice[f] = lastFillOfVoice.getOrDefault(voice, -1);
            lastFillOfVoice.put(voice, f);
        }
        Map<Integer, Integer> nextFillOfVoice = new HashMap<>();
        for (int f = total - 1; f >= 0; f--) {
            int voice = timeline.get(fillableAt[f]).slot().trackIndex();
            nextOnVoice[f] = nextFillOfVoice.getOrDefault(voice, -1);
            nextFillOfVoice.put(voice, f);
        }
        // 声部内以每个可填槽位为端点的连续未命中游程长度（含自身；命中的为 0）
        int[] runAfter = new int[total];
        int[] runBefore = new int[total];
        for (int k = total - 1; k >= 0; k--) {
            int next = nextOnVoice[k];
            runAfter[k] = noteToLyric[k] >= 0 ? 0 : 1 + (next >= 0 ? runAfter[next] : 0);
        }
        for (int k = 0; k < total; k++) {
            int prev = prevOnVoice[k];
            runBefore[k] = noteToLyric[k] >= 0 ? 0 : 1 + (prev >= 0 ? runBefore[prev] : 0);
        }
        // 声部内上一个原子（含非可填）：空隙判定不跨声部。**按具体轨记**（代表轨 + 成员轨，
        // 与 {@link #tracksOf} / {@code carried} / {@link #buildLines} 的 lastByTrack 同口径）：
        // 合唱 / 和声整块时间线原子由别的轨代表，本轨自己在这条时间线上根本没有原子 —— 只按
        // 代表轨记，本轨的下一拍就会拿一个很旧的原子去量空隙，量出一个假的大空隙、把一段
        // 连续的乐句从中间切开（与第 64 条 carried 的错法同源）。取<b>最新</b>的那个原子
        // （min 空隙）：这条轨接着上一拍唱了，就不算断点。
        Map<Integer, Timed> lastAtomOfTrack = new HashMap<>();
        // 声部拆分组（重叠但唱词不同被拆开的组）唱的是歌词里没有的内容时自成一段；
        // 交错演唱会让每个音符各自成块、各带一个拆分标记——同一段未匹配只断一次
        Set<Integer> fbOpenOfVoice = new HashSet<>();

        int[] segOf = new int[total];
        int segment = 0;
        int next = 0;   // 下一个可填槽位序
        for (int i = 0; i < timeline.size(); i++) {
            Timed timed = timeline.get(i);
            int voice = timed.slot().trackIndex();
            SlotType type = timed.slot().slotType();
            long gap = Long.MAX_VALUE;
            for (int track : tracksOf(timed)) {
                Timed prev = lastAtomOfTrack.put(track, timed);
                if (prev == timed) {
                    continue;   // 同一个轨在一次原子里出现两次（代表轨也在 members 里）
                }
                if (prev != null) {
                    gap = Math.min(gap, timed.onset() - (prev.onset() + prev.duration()));
                }
            }
            // 延音（{@code -}）跟<b>它前面那个音</b>一体：真延音紧接上一拍的尾巴，按本轨量出来
            // 的空隙本就是 0，这里不需要额外开恩 —— 断点万一落在它前面，{@link #relocateLeadingDashes}
            // 会把句首的延音并回上一句尾巴（「- 应跟前一个音一体」就是这么实现的）
            boolean boundary = gap != Long.MAX_VALUE
                    && (type == SlotType.BREATH || gap > GAP_THRESHOLD_BLICK);
            if (boundary) {
                if (next < total && (runAfter[next] >= UNMATCHED_SECTION_MIN
                        || (next > 0 && runBefore[next - 1] >= UNMATCHED_SECTION_MIN))) {
                    segment++;
                    fbOpenOfVoice.remove(voice);
                }
            }
            if (timed.forcedBreak() && next < total && noteToLyric[next] < 0
                    && fbOpenOfVoice.add(voice)) {
                segment++;
            }
            if (timed.slot().slotType().fillable()) {
                if (noteToLyric[next] >= 0) {
                    fbOpenOfVoice.remove(voice);   // 这条轨唱回了歌词，下一段未匹配重新判
                }
                segOf[next++] = segment;
            }
        }
        return segOf;
    }

    // ==================== 短语模型 + 贪心逐轨走法 ====================

    /**
     * 行尾括号：{@code A（B）} 里的 {@code （B）}。只看<b>整行最后一个括号</b>、且括号外还有
     * 正文的行 —— 行内括号（儿化音、注音、说话人标注，如「不惹是非但谁找茬（儿）我必奉陪到底」）
     * 原样留在主流，行为与从前一致。括号为空、括号里再套括号都不算。
     */
    private static final Pattern TRAILING_BRACKET =
            Pattern.compile("^(.*[^\\s(（)）])\\s*[(（]([^()（）]+)[)）]\\s*$");

    /**
     * 选句时「一段连续匹配」的长度上限：只看个大概，越长越容易分清这条轨接下来唱的是哪句。
     */
    private static final int PAIR_RUN_CAP = 32;

    /**
     * 「括号声部真在唱」的判定容差（0.25 秒，按 120bpm 折算）：兜两个声部之间的网格量化差，
     * 首尾相接的接力段不该算同时。
     */
    private static final long SIMULTANEOUS_TOLERANCE_BLICK =
            Math.round(0.25 * LyricFillParser.BLICK_PER_SECOND);

    /**
     * 选句时候选短语的窗口：只看歌词序前 {@value} 个未完成短语。两条声部同一时刻最多各唱
     * 一句、行尾括号的主流 / 括号短语相邻，窗口只需要盖住「正在空中的几句」；不设窗口会让
     * 远处同音的句子来抢（重唱段歌词相同，靠平局取歌词序靠前也只能兜一部分）。
     */
    private static final int PHRASE_WINDOW = 8;

    /**
     * 歌词短语：走法的「一句」。一行 lrc 按<b>空格</b>切成若干短语，行尾括号 {@code A（B）}
     * 再拆出括号短语（{@code sub=true}）——「一句不仅仅指一行歌词，也可以是空格或括号隔开的
     * 一句」。短语的 {@code units} 用 {@link #tokenize} 切（英文按词、中文逐字），
     * {@code gaps} 是单元后的行内空位标记（视觉空位）。
     */
    private record Phrase(int line, boolean sub, List<String> units, List<Boolean> gaps) {
    }

    /**
     * 全部短语 + 投影用的合并单元空间：短语按<b>阅读序</b>排（行号升序、同一行的主流在前、
     * 括号短语紧随），{@code baseOf[p]} = 短语 p 首单元在合并空间里的下标——
     * {@code noteToLyric} 的取值域，{@link #defaults} / {@link #gaps} 的投影逻辑一字不改能用。
     * 阅读序同时是走法的候选序：两段文本相同的短语（重唱 / 主流与括号声部唱同一句）竞争时，
     * 平局取阅读序靠前——《红马》主歌「你弹一曲（繁华绣河山）」的括号声部比第 3 行的
     * 主流先开口，靠这个顺序把「你弹一曲」判给括号声部、第 3 行留给后开口的主声部。
     */
    private record Phrases(List<Phrase> phrases, List<String> units, List<Boolean> gapAfter,
                           int[] baseOf) {
    }

    /**
     * 歌词 → 短语模型：每行先看行尾括号（有则拆主流 / 括号两段），再各按空格切短语。
     * 一行没有任何行尾括号时没有括号短语，行为与从前一致。
     */
    private static Phrases phrasesOf(List<LyricLine> lyrics) {
        List<Phrase> phrases = new ArrayList<>();
        for (int i = 0; i < lyrics.size(); i++) {
            String text = lyrics.get(i).text();
            Matcher m = TRAILING_BRACKET.matcher(text);
            boolean bracket = m.matches();
            phrases.addAll(phrasesOfLine(i, false, bracket ? m.group(1) : text));
            if (bracket) {
                phrases.addAll(phrasesOfLine(i, true, m.group(2)));
            }
        }
        List<String> units = new ArrayList<>();
        List<Boolean> gapAfter = new ArrayList<>();
        int[] baseOf = new int[phrases.size()];
        for (int p = 0; p < phrases.size(); p++) {
            baseOf[p] = units.size();
            units.addAll(phrases.get(p).units());
            gapAfter.addAll(phrases.get(p).gaps());
        }
        return new Phrases(phrases, units, gapAfter, baseOf);
    }

    /** 一段行内文本（主流或括号流）按空格切短语；最后一个短语之后不标视觉空位。 */
    private static List<Phrase> phrasesOfLine(int line, boolean sub, String text) {
        List<Phrase> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        String[] tokens = text.trim().split("\\s+");
        for (int t = 0; t < tokens.length; t++) {
            List<String> units = new ArrayList<>();
            List<Boolean> gaps = new ArrayList<>();
            tokenizeLine(tokens[t], units, gaps);
            if (units.isEmpty()) {
                continue;
            }
            // 短语之间的空格 = 视觉空位，标在最后一个单元后（行内最后一个短语不标）
            if (t < tokens.length - 1 && !gaps.isEmpty()) {
                gaps.set(gaps.size() - 1, true);
            }
            out.add(new Phrase(line, sub, units, gaps));
        }
        return out;
    }

    /**
     * 一次与歌词的对齐：{@code noteToLyric} 下标 = 可填槽位序、值 = 合并单元空间的单元序
     * （-1 未命中）；{@code units} / {@code gapAfter} 是这次对齐用的单元空间（走法 = 短语
     * 合并空间，旧的单调回落 = 原样歌词单元）。{@code sub} 下标同 {@code noteToLyric}，
     * true = 括号短语。
     */
    private record Alignment(int[] noteToLyric, boolean[] sub, boolean pinyin,
                             List<String> units, List<Boolean> gapAfter) {
    }

    /**
     * 每个可填槽位（时间线序）的造句归属：行号 + 段号（{@link #segmentsOf}）+ 是否括号声部。
     * 桶键 = (段, 行)：命中的槽位段恒为 0、按行分桶；未命中的长段（br / 大空隙 / 声部拆分处
     * 断开）段号递增、独立成句。
     */
    private record Assign(int[] lineOf, int[] segOf, boolean[] sub) {
    }

    /**
     * 走法结果：{@code pass} = 匹配率达标（达标才拿它造句）、{@code ratio} = 命中的单元数 ÷
     * 单元总数、命中表（投影用）+ 造句归属。**没达标的也要把 {@code ratio} 带出来**：它是
     * 「这份歌词跟模板对不上」最直接的证据（见 {@link #noticeOf}），不达标时命中表与归属
     * 一律为 {@code null}（调用方只会在达标时用它们）。
     */
    private record WalkResult(boolean pass, double ratio, int[] noteToLyric, Assign assign) {
    }

    /** 这次文本对齐走了哪条路（{@link #noticeOf} 据此挑提示档位）。 */
    enum Mode {
        /** 贪心逐轨走法 */
        WALK,
        /** 走法没过门控，回落旧的单调对齐（拼音↔汉字 / LCS） */
        SINGLE,
        /** 两条都失败，回落时间戳分句 */
        TIMELINE,
        /** 压根没做文本对齐（没有参照歌词，或界面手填了整体偏移） */
        OFF
    }

    /** 文本对齐的来路 + 走法的匹配率（提示用；{@link Mode#SINGLE} / {@link Mode#TIMELINE} 下也有值）。 */
    record AlignQuality(Mode mode, double ratio) {
    }

    /**
     * 直读缓存路径要的两件事：默认词是不是 demo 配出来的（页面据此标红拼音格）+
     * 分句质量提示。一次跑出两样（对齐只跑一趟）。
     */
    public record ReadHints(boolean pinyin, String notice) {
    }

    /**
     * 把一次对齐的来路翻成给用户看的一句话（{@code null} = 不提示）。两档：
     * <ul>
     *   <li><b>强提示</b>：走法没过门控（回落了单调对齐 / 时间戳）——「对不上」。
     *       最典型的来路是参照歌词配错了文件：实测《学猫叫》的音符里是 demo 版
     *       「握腰穿你的外套」，而库里 {@code demo_lrc_file_name} 为空、退回用原声 lrc 的
     *       「我要穿你的外套」，走法只有 <b>12%</b> 命中 → 回落单调对齐 → 句界切错
     *       （「握腰 / 穿你的外套问 / 你身上的味道」）；</li>
     *   <li><b>弱提示</b>：走法通过了但只贴到 {@link #WEAK_MATCH_RATIO} 以下 ——
     *       「只对上一部分」。</li>
     * </ul>
     * 没有参照歌词（{@link Mode#OFF}）不提示：那不是「对不上」，是本来就没得对。
     */
    private static String noticeOf(AlignQuality quality) {
        if (quality == null || quality.mode() == Mode.OFF
                || (quality.mode() == Mode.WALK && quality.ratio() >= WEAK_MATCH_RATIO)) {
            return null;
        }
        String pct = Math.round(Math.max(0, quality.ratio()) * 100) + "%";
        if (quality.mode() == Mode.WALK) {
            return "参照歌词只对上一部分（匹配 " + pct + "），个别句界可能不准";
        }
        return "参照歌词与模板文本对不上（匹配 " + pct + "），句界可能不准 —— "
                + "去原曲页核对「样例歌词」是不是配错了";
    }

    /**
     * 一次文本对齐 + 造句归属。分句（{@link #split}）与默认词 / 视觉空位 / 括号标记
     * （{@link #defaults} / {@link #gaps} / {@link #brackets}）共用同一条路径，
     * 保证三处算的是同一套对齐结果。
     */
    private record TextAlign(List<Timed> timeline, Units units, Alignment alignment, Assign assign) {
    }

    /**
     * 同 {@link #textAlignRun}，只要对齐结果（默认词 / 视觉空位 / 括号标记三处用它）。
     */
    private static TextAlign textAlign(List<Timed> timeline, List<LyricLine> lyrics) {
        return textAlignRun(timeline, lyrics).text();
    }

    /**
     * 文本对齐 + 这次走了哪条路（分句质量提示用）。主路径是<b>贪心逐轨走法</b>
     * （{@link #walk}，按声部逐句消化）；走法对不上（匹配率不足）回落旧的单调对齐
     * （整条时间线一个游标，拼音 / LCS）。两条都失败 {@code text = null}
     * （调用方回落时间戳 / br+空隙分句），但 {@code quality} 仍带着走法的匹配率。
     */
    private static TextAlignRun textAlignRun(List<Timed> timeline, List<LyricLine> lyrics) {
        Units u = units(timeline, lyrics);
        Phrases ph = phrasesOf(lyrics);
        boolean pinyin = PinyinLyricMatcher.isPinyinTemplate(u.noteUnits(), ph.units());
        WalkResult w = walk(timeline, ph, pinyin);
        // 实际生效的短语空间。走法认领了括号短语、却拿不出「两条声部同时开口」的证据时，
        // 括号短语会被剔除后重走，**单元空间跟着收缩**（{@code phrasesWithoutSub} 会重建
        // units / gapAfter）。Alignment 必须配收缩后的那一份：{@code noteToLyric} 的取值域
        // 是收缩空间的单元序，配未收缩的表取值会整体前移——括号行之后每一行的默认词与
        // 视觉空位全错位一格（空格挪到句尾）。
        Phrases used = ph;
        if (w.pass() && anySub(w) && !subVoiceEvidence(timeline, w)) {
            // 多半是儿化音 / 注音型括号，把括号短语从候选里去掉重走（那几个音符按未匹配处理）
            used = phrasesWithoutSub(ph);
            w = walk(timeline, used, pinyin);
        }
        if (w.pass()) {
            return new TextAlignRun(new TextAlign(timeline, u,
                    new Alignment(w.noteToLyric(), w.assign().sub(), pinyin, used.units(), used.gapAfter()),
                    w.assign()), new AlignQuality(Mode.WALK, w.ratio()));
        }
        // 旧的单调回落（拼音模板走拼音↔汉字、汉字模板走 LCS），各自带匹配率门控
        int[] noteToLyric = noteToLyric(u);
        if (noteToLyric == null) {
            return new TextAlignRun(null, new AlignQuality(Mode.TIMELINE, w.ratio()));
        }
        int[] lineOf = lineOfNote(u.lyricLineOf(), noteToLyric);
        return new TextAlignRun(new TextAlign(timeline, u,
                new Alignment(noteToLyric, null, pinyin, u.lyricUnits(), u.lyricGapAfter()),
                new Assign(lineOf, segmentsOf(timeline, noteToLyric), new boolean[noteToLyric.length])),
                new AlignQuality(Mode.SINGLE, w.ratio()));
    }

    /** 一次文本对齐的结果 + 来路（{@link #textAlignRun}）。 */
    private record TextAlignRun(TextAlign text, AlignQuality quality) {
    }

    /** 走法有没有把任何可填槽位归给括号短语。 */
    private static boolean anySub(WalkResult w) {
        for (boolean b : w.assign().sub()) {
            if (b) {
                return true;
            }
        }
        return false;
    }

    /**
     * 「括号声部真在唱」的证据：某个归给括号短语的可填原子，与某个归给主流短语的可填原子
     * 在时间上相交、且分属不同轨（容差 {@link #SIMULTANEOUS_TOLERANCE_BLICK}）。没有这个
     * 证据的括号行多半是注解——儿化音「（儿）」、妖怪名、注音这类，不能当双声部处理。
     * 主流和括号声部若真要合唱同一句，两条轨必然有同时开口的一拍。
     */
    private static boolean subVoiceEvidence(List<Timed> timeline, WalkResult w) {
        List<Integer> fillableAt = new ArrayList<>();
        for (int i = 0; i < timeline.size(); i++) {
            if (timeline.get(i).slot().slotType().fillable()) {
                fillableAt.add(i);
            }
        }
        for (int a = 0; a < fillableAt.size(); a++) {
            if (!w.assign().sub()[a]) {
                continue;
            }
            for (int b = 0; b < fillableAt.size(); b++) {
                if (w.assign().sub()[b]) {
                    continue;
                }
                Timed s = timeline.get(fillableAt.get(a));
                Timed m = timeline.get(fillableAt.get(b));
                if (s.slot().trackIndex() != m.slot().trackIndex() && overlaps(s, m)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 括号短语全部剔除后的短语模型（单元空间同步收缩）。 */
    private static Phrases phrasesWithoutSub(Phrases ph) {
        List<Phrase> kept = ph.phrases().stream().filter(p -> !p.sub()).toList();
        if (kept.size() == ph.phrases().size()) {
            return ph;
        }
        List<String> units = new ArrayList<>();
        List<Boolean> gapAfter = new ArrayList<>();
        int[] baseOf = new int[kept.size()];
        for (int p = 0; p < kept.size(); p++) {
            baseOf[p] = units.size();
            units.addAll(kept.get(p).units());
            gapAfter.addAll(kept.get(p).gaps());
        }
        return new Phrases(kept, units, gapAfter, baseOf);
    }

    /**
     * 贪心逐轨走法（2026-09-11 重构，取代 orderByLyric 重排 + 括号双流两套启发式）。
     *
     * <p><b>怎么走</b>：声部 = 声部组的代表轨，各有自己的音符序。循环：
     * <ol>
     *   <li>取「下一个未消费原子<b>最早</b>」的声部，从它开始解析；</li>
     *   <li>可填原子先<b>选句</b>：在歌词序前 {@link #PHRASE_WINDOW} 个未完成短语里，选「这条轨
     *       接下来的音符能<b>连上最长一段</b>」的那句（{@link #runLength}，平局取歌词序靠前——
     *       重复副歌按序消费；《红马》括号声部先开口时，主流短语「我在江南」只是它的前缀，
     *       括号短语那一段更长、自然胜出）。<b>不是</b>「歌词顺序的下一句」——实测《不问ciaga》
     *       30.3s 切到轨0 时歌词游标在第 8 行「反复回忆」、轨0 唱的是第 10 行「我叹那春花秋月
     *       不问别离」，按下一句走会整段死跳错位；</li>
     *   <li>选中后就<b>粘在这条轨上</b>逐音符消化这句（死单元跳过、跳 1~2 个 demo 多写的字要
     *       续得上才跳），唱完一句才回到第 1 步重新选轨——<b>不按时间轮流匹配</b>，同轨的音符
     *       是一个整体。「-」/「br」跟本轨正在唱的这句（唱完一句后紧随的延音也归它 = 句尾延音），
     *       轨还没有归属时跟时间线前一个原子；</li>
     *   <li>谁都连不上的原子按「未匹配」处理：归<b>本轨最近归属的句子</b>（跨声部交错时时间线上
     *       前一个原子往往是隔壁声部的），交给 {@link #segmentsOf} 决定要不要独立成段。</li>
     * </ol>
     *
     * <p><b>为什么不等价于「每条轨各自跑一遍单调对齐」</b>：单拉一条轨对齐时，长休止会让游标
     * 撞上休止前位置的同音字假命中（实测《广寒宫》「午」对到第 17 行的「吾」），后面整段串位。
     * 走法的选句按「未完成短语」算——一句被谁唱掉就完成了，后来者不会再撞它；候选窗口又只看
     * 空中的几句，休止前后的同音段落早已不在窗口里。
     *
     * <p>匹配率（命中的单元数 ÷ 单元总数）低于 {@link #MIN_LYRIC_MATCH_RATIO} 时
     * {@code pass = false}，调用方回落旧的单调对齐 / 时间戳分句 —— 匹配率照样带出来，它正是
     * 「这份歌词配错了」的证据。
     */
    private static WalkResult walk(List<Timed> timeline, Phrases ph, boolean pinyin) {
        List<Integer> fillableAt = new ArrayList<>();
        for (int i = 0; i < timeline.size(); i++) {
            if (timeline.get(i).slot().slotType().fillable()) {
                fillableAt.add(i);
            }
        }
        int n = fillableAt.size();
        if (n == 0 || ph.units().isEmpty()) {
            return new WalkResult(false, 0, null, null);
        }
        int[] noteToLyric = new int[n];
        Arrays.fill(noteToLyric, -1);
        int[] lineOf = new int[n];
        boolean[] sub = new boolean[n];

        // 声部 = 代表轨 → 各自的时间线原子序（onset 序）；合唱成员跟着代表走
        Map<Integer, List<Integer>> voiceAtoms = new LinkedHashMap<>();
        Map<Integer, List<String>> voiceUnits = new LinkedHashMap<>();   // 各声部的可填原值序
        int[] fillIndexOf = new int[timeline.size()];                    // 原子 → 全局可填槽位序
        Arrays.fill(fillIndexOf, -1);
        int fillCount = 0;
        for (int i = 0; i < timeline.size(); i++) {
            int voice = timeline.get(i).slot().trackIndex();
            voiceAtoms.computeIfAbsent(voice, k -> new ArrayList<>()).add(i);
            if (timeline.get(i).slot().slotType().fillable()) {
                fillIndexOf[i] = fillCount++;
                voiceUnits.computeIfAbsent(voice, k -> new ArrayList<>())
                        .add(Objects.toString(timeline.get(i).slot().original(), ""));
            }
        }
        Map<Integer, Integer> pos = new HashMap<>();        // 各声部消费游标（原子序）
        Map<Integer, Integer> fillUsed = new HashMap<>();   // 各声部已消费的可填单元数
        // 轨 → 最近归属 [line, sub]。**按轨记，不按代表轨记**：合唱 / 和声时整块时间线原子由
        // 另一条轨代表，本轨的音符只是它的成员，本轨自己在这条时间线上根本没有原子；只按代表轨
        // 记，「本轨最近唱到哪一句」就会停在更早的那一句上，未匹配的原子被送回错处（实测
        // 《霜雪千年》末段：轨1 的 bei/qi/xue 都并进了轨0 代表的块，只有 shuang 自成一块 ——
        // 它被送回了上一句「消融你眉间」）。与 {@link #buildLines} 的 lastByTrack 同一个口径。
        Map<Integer, int[]> carried = new HashMap<>();
        for (Timed atom : timeline) {
            for (int track : tracksOf(atom)) {
                carried.putIfAbsent(track, new int[]{0, 0});
            }
        }
        for (Integer voice : voiceAtoms.keySet()) {
            pos.put(voice, 0);
            fillUsed.put(voice, 0);
        }
        // 汉字路径的死单元基准：整个模板唱过的音符原值（不分轨 —— 逐字接力的后半句在别的轨上）
        Set<String> knownNotes = new HashSet<>();
        for (List<String> units : voiceUnits.values()) {
            knownNotes.addAll(units);
        }
        List<UnitMatch> matchers = new ArrayList<>(ph.phrases().size());
        for (Phrase p : ph.phrases()) {
            matchers.add(new UnitMatch(pinyin, p.units(), knownNotes));
        }
        int[] cursor = new int[ph.phrases().size()];        // 短语单元游标（多声部接力共享）

        while (true) {
            Integer voice = earliestVoice(voiceAtoms, pos, timeline);
            if (voice == null) {
                break;
            }
            List<Integer> atoms = voiceAtoms.get(voice);
            int p = pos.get(voice);
            Timed atom = timeline.get(atoms.get(p));
            if (!atom.slot().slotType().fillable()) {
                // 延音 / 呼吸跟本轨正在唱的句（没有进行中的归属时跟最近归属）
                pos.put(voice, p + 1);
                continue;
            }
            // 选句：候选 = 歌词序前 PHRASE_WINDOW 个未完成短语，按本轨能连上的最长一段。
            // 首个剩余单元必须真实命中（不吃开头的死字跳过）——否则一个前面唱岔的字会
            // 跳过某句开头、把后面副歌那句偷走（实测《还是会寂寞》第二遍副歌的「着」
            // 跳过「跟」抢走了「跟着我勇敢的走下去」，整段跟着串位）。
            int best = -1;
            int bestRun = 0;
            int seen = 0;
            for (int f = 0; f < ph.phrases().size() && seen < PHRASE_WINDOW; f++) {
                if (cursor[f] >= ph.phrases().get(f).units().size()) {
                    continue;   // 已完成
                }
                seen++;
                UnitMatch m = matchers.get(f);
                List<String> units = ph.phrases().get(f).units();
                int from = fillUsed.get(voice);
                List<String> notes = voiceUnits.get(voice);
                if (from >= notes.size() || !m.test(notes.get(from), cursor[f])) {
                    continue;   // 开口对不上：不进候选
                }
                int run = runLength(notes, from, units, cursor[f], m);
                if (run > bestRun) {
                    bestRun = run;
                    best = f;
                }
            }
            if (bestRun == 0) {
                // 未匹配：归本轨最近归属
                int[] own = carried.get(voice);
                lineOf[fillIndexOf[atoms.get(p)]] = own[0];
                sub[fillIndexOf[atoms.get(p)]] = own[1] != 0;
                carry(carried, timeline.get(atoms.get(p)), own);
                fillUsed.put(voice, fillUsed.get(voice) + 1);
                pos.put(voice, p + 1);
                continue;
            }
            // 消费选中的短语：粘在本轨上逐音符唱，唱完 / 唱岔为止
            Phrase phrase = ph.phrases().get(best);
            UnitMatch m = matchers.get(best);
            while (pos.get(voice) < atoms.size()) {
                int j = atoms.get(pos.get(voice));
                Timed a = timeline.get(j);
                if (!a.slot().slotType().fillable()) {
                    pos.put(voice, pos.get(voice) + 1);   // 句中 / 句尾延音都归本句
                    continue;
                }
                if (cursor[best] >= phrase.units().size()) {
                    break;   // 单元耗尽后的可填原子 → 交还选轨
                }
                String unit = Objects.toString(a.slot().original(), "");
                int fill = fillUsed.get(voice);
                if (m.test(unit, cursor[best])) {
                    int slot = fillIndexOf[j];
                    noteToLyric[slot] = ph.baseOf()[best] + cursor[best];
                    lineOf[slot] = phrase.line();
                    sub[slot] = phrase.sub();
                    carry(carried, a, new int[]{phrase.line(), phrase.sub() ? 1 : 0});
                    cursor[best]++;
                    fillUsed.put(voice, fill + 1);
                    pos.put(voice, pos.get(voice) + 1);
                    continue;
                }
                if (m.never(cursor[best])) {
                    cursor[best]++;   // 死单元（标点 / 注音谁都读不出）：跳过，音符留着再对
                    continue;
                }
                int skip = acceptableSkip(unit, voiceUnits.get(voice), fill,
                        phrase.units(), cursor[best], m);
                if (skip > 0) {
                    cursor[best] += skip;   // demo 多写的字，不占音符
                    continue;
                }
                break;   // 本轨这句到此为止（唱岔 / 接力给别的轨）
            }
            // 归属已在消费时按轨记进 carried（进这里时至少消费了一个原子），不需另记
        }

        long matched = Arrays.stream(noteToLyric).filter(idx -> idx >= 0).count();
        double ratio = (double) matched / ph.units().size();
        if (ratio < MIN_LYRIC_MATCH_RATIO) {
            // 不达标：命中表与归属都作废（调用方走回落），只把匹配率带出去供提示用
            return new WalkResult(false, ratio, null, null);
        }
        return new WalkResult(true, ratio, noteToLyric,
                new Assign(lineOf, segmentsOf(timeline, noteToLyric), sub));
    }

    /**
     * 下一个未消费原子最早的声部（同拍取原子在时间线上靠前的，保证顺序稳定可复现）；
     * 全部唱完返回 null。
     */
    private static Integer earliestVoice(Map<Integer, List<Integer>> voiceAtoms, Map<Integer, Integer> pos,
                                         List<Timed> timeline) {
        Integer best = null;
        int bestAtom = Integer.MAX_VALUE;
        for (Map.Entry<Integer, List<Integer>> entry : voiceAtoms.entrySet()) {
            int p = pos.get(entry.getKey());
            if (p >= entry.getValue().size()) {
                continue;
            }
            int atom = entry.getValue().get(p);
            if (atom < bestAtom) {
                bestAtom = atom;
                best = entry.getKey();
            }
        }
        return best;
    }

    /**
     * 把一个时间线原子的归属 [行, sub] 记到它涉及的<b>每一轨</b>上（代表轨 + 合唱成员轨，
     * 与 {@link #tracksOf} 同口径）——本轨的音符常常由别的轨代表（合唱 / 和声整块并组），
     * 只记代表轨会让本轨「最近唱到哪一句」落后一步，见 {@code walk} 里 carried 的注释。
     */
    private static void carry(Map<Integer, int[]> carried, Timed atom, int[] own) {
        for (int track : tracksOf(atom)) {
            carried.put(track, own);
        }
    }

    /**
     * 音符 {@code unit} 连不上当前单元时，能不能跳过 1~2 个歌词单元（demo 比「唱法」多写的字，
     * 不占音符）：要求跳到的单元读得出这个音符，且之后还能续上——下一个音符接得上跳后的
     * 再下一个单元，或短语正好到头。空跳会把后面的单元序整体带歪。
     */
    private static int acceptableSkip(String unit, List<String> notes, int fill,
                                      List<String> units, int cursor, UnitMatch m) {
        for (int k = 1; k <= 2 && cursor + k < units.size(); k++) {
            if (!m.test(unit, cursor + k)) {
                continue;
            }
            boolean continues = cursor + k + 1 >= units.size()
                    || (fill + 1 < notes.size() && m.test(notes.get(fill + 1), cursor + k + 1));
            if (continues) {
                return k;
            }
        }
        return 0;
    }

    /**
     * 从音符序第 {@code from} 格起，连续命中 {@code units} 上 {@code cursor} 起的单元数
     *（上限 {@link #PAIR_RUN_CAP}）。途中「谁都读不出」的单元（标点、英文、演唱者标注 ——
     * 括号句里的顿号逗号全在这类）直接跨过去，不然游标会永远焊死在它上面，后面整段判不出来。
     */
    private static int runLength(List<String> notes, int from, List<String> units, int cursor,
                                 UnitMatch match) {
        int k = 0;
        int j = cursor;
        while (k < PAIR_RUN_CAP && from + k < notes.size() && j < units.size()) {
            if (match.test(notes.get(from + k), j)) {
                k++;
                j++;
                continue;
            }
            if (match.never(j)) {
                j++;
                continue;
            }
            break;
        }
        return k;
    }
    /** 两个原子在时间上相交（含 {@link #SIMULTANEOUS_TOLERANCE_BLICK} 容差）。 */
    private static boolean overlaps(Timed a, Timed b) {
        return a.onset() < b.onset() + b.duration() + SIMULTANEOUS_TOLERANCE_BLICK
                && b.onset() < a.onset() + a.duration() + SIMULTANEOUS_TOLERANCE_BLICK;
    }

    /** 流内单元匹配：拼音模板走「音符拼音 ↔ 歌词汉字读音」（读音预取），汉字模板走原值相等。 */
    private static final class UnitMatch {

        private final List<String> units;
        private final List<Set<String>> readings;   // 拼音路径：每个歌词单元的全部读音；汉字路径为 null
        private final Set<String> knownNotes;       // 汉字路径的死单元基准：全部声部唱过的音符原值
        private final Map<String, Set<String>> soundCache = new HashMap<>();   // 音符单元 → 它的音
        private final Map<String, Set<String>> commonNoteCache = new HashMap<>();   // 音符单元 → 它的常用音
        private final Map<String, Set<String>> commonTargetCache = new HashMap<>(); // 歌词单元 → 它的常用音
        private final Map<Integer, Boolean> neverCache = new HashMap<>();      // 歌词单元 → 是不是死单元

        UnitMatch(boolean pinyin, List<String> units, Set<String> knownNotes) {
            this.units = units;
            this.readings = pinyin
                    ? units.stream().map(PinyinLyricMatcher::readings).toList() : null;
            this.knownNotes = knownNotes;
        }

        boolean test(String noteUnit, int index) {
            if (noteUnit == null || index < 0 || index >= units.size()) {
                return false;
            }
            if (readings != null) {
                // 候选音而不是单个归一值：ü 有 lue/lve 两种写法（见 noteVariants）
                for (String sound : sounds(noteUnit)) {
                    if (readings.get(index).contains(sound)) {
                        return true;
                    }
                }
                return false;
            }
            String target = units.get(index);
            if (noteUnit.equals(target)) {
                return true;
            }
            // 同音回退：模板原词与 demo 歌词常有一两个同音字出入（实测《被风吹过的夏天》
            // 模板「纳个夏天」「心中de热」，demo 写「那个夏天」「心中的热」；《不问ciaga》
            // 模板「淋」对 demo「林」）。严格相等下这两行整段对不上，碎成独立句。
            //
            // 两侧都只认**常用读音**（2026-09-28，见 PinyinLyricMatcher#commonReadings）：
            // 全量字典里带着古音，音符「一」与歌词「听」（古音 yǐn/yí）会互相命中、音符「听」
            // 与歌词「因」也是 —— 那种假同音让走法在第一个字上就错开短语，整段串位
            // （实测《因为爱情》前四句）。上面那条严格相等不受影响。
            for (String sound : commonSounds(noteUnit)) {
                if (fallbackReadings(target).contains(sound)) {
                    return true;
                }
            }
            return false;
        }

        /** 同音回退用的音符侧常用音：含字母的还是按拼音写法（{@link #sounds}），汉字取常用读音。 */
        private Set<String> commonSounds(String unit) {
            return commonNoteCache.computeIfAbsent(unit, u -> hasAsciiLetter(u)
                    ? PinyinLyricMatcher.noteVariants(u)
                    : PinyinLyricMatcher.commonReadings(u));
        }

        /** 同音回退用的歌词侧常用音。两个缓存分开：含字母的单元两侧语义不同（音符侧是拼音写法）。 */
        private Set<String> fallbackReadings(String unit) {
            return commonTargetCache.computeIfAbsent(unit, PinyinLyricMatcher::commonReadings);
        }

        /**
         * 音符单元的音：含字母的按拼音（{@link PinyinLyricMatcher#noteVariants}，ü 的两种写法
         * 都算），单个汉字取全部读音，其余（标点 / 多字）为空。
         *
         * <p><b>拼音路径与汉字路径共用这一处</b>——两条路径的「音符能读成什么」必须是同一个
         * 口径，否则又会各算各的（死单元那次就是两边判据不一致，见 {@link #never}）。
         */
        private Set<String> sounds(String unit) {
            return soundCache.computeIfAbsent(unit, u -> hasAsciiLetter(u)
                    ? PinyinLyricMatcher.noteVariants(u)
                    : PinyinLyricMatcher.readings(u));
        }

        /**
         * 这个单元是不是「谁都读不出」—— 拼音路径看有没有读音（标点、英文、数字都没有），
         * 汉字路径看<b>整个模板</b>有没有唱过它（逗号、括号里的演唱者标注之类永远等不上）。
         * 这类单元哪条流都命不中，做连续匹配时应当直接跨过去，否则游标会焊死在它上面，
         * 后面整段判不出流。
         *
         * <p><b>汉字路径为什么看全局而不是本轨往后几个音符</b>（2026-09-11 修）：双轨
         * <b>逐字接力</b>唱同一句时，后半句正是另一条轨唱的，本轨往后怎么找都找不到它 ——
         * 按本轨判定会把「肯沉淀」整个判成死单元吃掉（实测《被风吹过的夏天》
         * 「只剩寂寞肯沉淀」＝轨2 唱前四字、轨3 唱后三字，走法在轨2 上把后三字跳光、
         * 短语于是「完成」，轨3 再唱就成了未匹配，一首歌 62 句碎成 78 句）。
         *
         * <p><b>判据必须和 {@link #test} 同口径</b>（2026-09-11 修）：这里曾经拿音符原值做
         * {@code contains}，而 {@code test} 已经放宽到同音 —— 模板写「纳」、歌词写「那」时，
         * 「那」在 {@code test} 里本来能配上，却先被 {@code never} 当成死单元跳过去了，
         * 于是同音回退形同虚设。现在直接问「全局有没有哪个音符能配上它」，同一个判据。
         */
        boolean never(int index) {
            if (index < 0 || index >= units.size()) {
                return true;
            }
            return neverCache.computeIfAbsent(index, i -> {
                if (readings != null) {
                    return readings.get(i).isEmpty();
                }
                return knownNotes.stream().noneMatch(n -> test(n, i));
            });
        }
    }

    /**
     * 按归属造句（走法与旧的单调回落共用）：桶键 =（段, 行），同一条 lrc 行的原子归一句，
     * <b>允许这一句在时间线上不连续</b>（《红马》里「你在清涧（你弹一曲）」的主流在 33.2s、
     * 括号流散在 34.3~37.5s，中间还夹着同一轨的「胭脂伞」，按时间切片必然把它们切碎）。
     *
     * <p>桶内<b>主流原子在前、括号流原子在后</b>（各自仍是时间序）—— 组号按句内首现序编，
     * 主声部就稳定拿到第 0 组、括号声部第 1 组。句首 onset 取桶内最小 onset（两条声部谁先
     * 开口按谁算）。桶之间按首次出现序。
     *
     * <p><b>延音 / 呼吸跟本轨</b>：非可填原子不该跟「时间线前一个原子」——两条声部在时间线上
     * 交错，前一个原子往往是<b>隔壁声部</b>的，跟着它就跑到隔壁的句里去了（实测《红马》轨2
     * 的「你弹一曲」中间那三个 {@code -} 被「绫绸缎」那一拍带走，既把乐句切碎、又让这一句
     * 凭空多出一个声部；实测《不问ciaga》47.72s 轨0 的「离」与轨1 的「淋」同拍起音，轨0 随后的
     * 1.2 秒延音跟了时间线前一个原子（轨1 的「淋」）、凭空多出一组）。本轨还没唱过就退回
     * 「跟前一个原子」（与 {@link #lineOfNote} 同口径）。流标记（{@code sub}）同理：跟本轨
     * 上一个可填槽位走。
     *
     * <p><b>多成员原子按成员拆开</b>：两条声部在同一拍都唱 {@code -} 时会被并成一个原子
     *（{@link #timedOf} 按 onset + 同词配对），这种原子只能有一个桶 —— 拆成单成员原子，
     * 两条声部的延音才各自跟着自己那句走。
     */
    private static List<FillLine> buildLines(List<Timed> timeline, Assign assign) {
        Map<Long, List<Piece>> byKey = new LinkedHashMap<>();
        Map<Integer, int[]> lastByTrack = new HashMap<>();   // 轨 → 最近归属 [段, 行, sub]
        int next = 0;
        int[] current = {0, 0, 0};   // 时间线前一个原子的归属（本轨还没唱过时的兜底，句首之前归第一句）
        for (Timed timed : timeline) {
            if (timed.slot().slotType().fillable()) {
                current = new int[]{assign.segOf()[next], assign.lineOf()[next],
                        assign.sub()[next] ? 1 : 0};
                next++;
                for (int track : tracksOf(timed)) {
                    lastByTrack.put(track, current);
                }
                addPiece(byKey, keyOf(current), new Piece(timed, current[2] != 0));
                continue;
            }
            List<FillSlot> members = timed.members();
            for (int k = 0; k < members.size(); k++) {
                int track = members.get(k).trackIndex();
                int[] own = lastByTrack.get(track);
                int[] use = own != null ? own : current;
                addPiece(byKey, keyOf(use), new Piece(
                        members.size() == 1 ? timed : pieceOf(timed, k), use[2] != 0));
            }
        }
        List<FillLine> lines = new ArrayList<>(byKey.size());
        List<Piece> pending = new ArrayList<>();   // 整句没有可填槽位（前奏）：并进下一句
        for (List<Piece> bucket : byKey.values()) {
            bucket.sort(Comparator.comparingInt((Piece p) -> p.sub() ? 1 : 0));
            List<Piece> pieces = new ArrayList<>(pending.size() + bucket.size());
            pieces.addAll(pending);
            pending.clear();
            pieces.addAll(bucket);
            if (pieces.stream().noneMatch(p -> p.atom().slot().slotType().fillable())) {
                pending.addAll(pieces);
                continue;
            }
            List<Timed> atoms = pieces.stream().map(Piece::atom).toList();
            FillLine built = lineWithStreams(pieces);
            long start = atoms.stream().mapToLong(Timed::onset).min().orElse(built.startOnset());
            lines.add(start == built.startOnset() ? built
                    : new FillLine(built.slots(), built.originalText(), built.needCount(),
                            start, built.groups()));
        }
        if (!pending.isEmpty()) {
            lines.add(line(pending.stream().map(Piece::atom).toList()));
        }
        return lines;
    }

    /** 归属 [段, 行, sub] 的桶键——sub 不进键（主流与括号声部共享一句，桶内再排序）。 */
    private static long keyOf(int[] a) {
        return ((long) a[0] << 32) | a[1];
    }

    /** 桶里的一个原子 + 它的流标记（拆开的多成员原子各有各的标记）。 */
    private record Piece(Timed atom, boolean sub) {
    }

    private static void addPiece(Map<Long, List<Piece>> byKey, long key, Piece piece) {
        byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(piece);
    }

    /** 多成员原子的第 {@code k} 个成员拆成单成员原子（声部键跟着那个成员走）。 */
    private static Timed pieceOf(Timed timed, int k) {
        return new Timed(timed.onset(), timed.duration(), timed.members().get(k),
                List.of(timed.members().get(k)), List.of(timed.voiceIds().get(k)),
                timed.forcedBreak());
    }

    /** 一个时间线原子涉及的所有轨：代表轨 + 合唱成员轨（成员轨的延音才会跟着它走）。 */
    private static List<Integer> tracksOf(Timed timed) {
        List<Integer> tracks = new ArrayList<>(1 + timed.members().size());
        tracks.add(timed.slot().trackIndex());
        for (FillSlot member : timed.members()) {
            tracks.add(member.trackIndex());
        }
        return tracks;
    }

    // ==================== 默认词占位符 ====================

    /**
     * 默认词（拿 demo 歌词配出来的汉字占位符）：与 {@code lines} / {@code filled} 同形状，命中的
     * 可填槽位放汉字，其余（未命中 / DASH / BREATH）空串。{@code fillable} 与 {@code noteToLyric}
     * 按下标对应（都是时间线里可填槽位的顺序）。命中的字同时写给组的全部成员——
     * 合唱的两边灰显同一个字（「一句歌词可以匹配多次」）。
     *
     * @param pinyinSlotsOnly 只给<b>拼音格</b>（原词写着拼音的音符）放默认词。汉字模板走这条：
     *                        它那些汉字格的原词就是歌词，再灰显一遍同一个字没有意义；而夹在
     *                        汉字里的拼音格（实测《气泡少女》主旋律前两句写 {@code zao qi yao…}、
     *                        后面全是汉字）正需要 demo 汉字来提醒「这一格唱什么」（用户实测报的）
     */
    private static List<List<String>> defaults(List<FillLine> lines, List<Timed> fillable,
                                               int[] noteToLyric, List<String> lyricUnits,
                                               boolean pinyinSlotsOnly) {
        Map<Long, String> byKey = new HashMap<>();
        for (int i = 0; i < fillable.size() && i < noteToLyric.length; i++) {
            int j = noteToLyric[i];
            if (j >= 0 && j < lyricUnits.size()) {
                String word = lyricUnits.get(j);
                FillSlot rep = fillable.get(i).slot();
                byKey.put(key(rep.trackIndex(), rep.noteIndex()), word);
                for (FillSlot member : fillable.get(i).members()) {
                    if (member.slotType().fillable()) {
                        byKey.put(key(member.trackIndex(), member.noteIndex()), word);
                    }
                }
            }
        }
        return projectDefaults(lines, byKey, pinyinSlotsOnly);
    }

    /** 这条时间线有没有拼音格：原词写着拼音（含 ASCII 字母，即 {@link SlotType#ENGLISH}）的可填槽位。 */
    private static boolean hasPinyinCell(List<Timed> timeline) {
        return timeline.stream().anyMatch(t -> t.slot().slotType() == SlotType.ENGLISH);
    }

    /**
     * 直读缓存路径也要现算默认词（默认词不落库）：给定当前分句 {@code lines}（可能是用户手动
     * 断开 / 合并过的），重跑一次文本对齐、按槽位回指。对齐不成功时返回每句一个空列表
     * （仍与 {@code lines} 同形状），保证下发到前端是「与 slots 等长」。
     *
     * <p>「给哪些格」与 {@link #split} 同一口径：拼音模板给每一格，汉字模板只给其中的拼音格
     * （{@code pinyinSlotsOnly}）。
     */
    public static List<List<String>> defaults(List<FillTrack> tracks, List<Integer> selected,
                                              List<FillLine> lines, List<LyricLine> lrcLines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        List<LyricLine> lyrics = lyricLines(lrcLines);
        if (lyrics.isEmpty()) {
            return emptyDefaults(lines);
        }
        TextAlign text = textAlign(timeline(tracks, selected), lyrics);
        if (text == null) {
            return emptyDefaults(lines);
        }
        Alignment a = text.alignment();
        return defaults(lines, text.units().fillable(), a.noteToLyric(), a.units(), !a.pinyin());
    }

    /**
     * 直读缓存路径（{@link LyricFillService} 里「库里已固化骨架」那一支）要的两件事：
     * <ul>
     *   <li>{@code pinyin} = 这次下发的默认词是不是<b>拿 demo 歌词配出来的</b> —— 页面据此决定
     *       「没匹配到汉字的拼音格标红」。与 {@link #defaults} 共用同一个门（{@link #textAlign}
     *       成功、且时间线上有拼音格），保证标红与默认词口径一致、直读路径与 {@link #split}
     *       口径一致。</li>
     *   <li>{@code notice} = 分句质量提示（{@link #noticeOf}）—— 这条路径的分句是库里固化的，
     *       但「这份参照歌词跟模板对不上」照样要说；不然用户只能在页面上看着错的句界猜。</li>
     * </ul>
     * 两样一次跑出：那条路为了标红本来就要跑一趟对齐，不要再跑第二趟。
     *
     * <p><b>不是「整份模板的音符都是拼音」</b>：拼音模板当然算，汉字模板里夹着几格拼音也算
     * （实测《气泡少女》主旋律整首歌只有 38 个拼音音符、其余是汉字，旧判据判否 → 那几格拼音
     * 既不显示 demo 汉字也不标红，用户看着就是「模板没去匹配 demo 歌词」）。
     *
     * <p><b>判据是模板级的，不看某一句配没配上</b>：整句一个汉字都没匹配上时那一行的
     * {@code defaults} 整行为空，但还是要标红 —— 用「这行有没有默认词」当判据会把
     * 错得最狠的整句全空漏掉（用户实测报的就是这个）。
     */
    public static ReadHints readHints(List<FillTrack> tracks, List<Integer> selected,
                                       List<LyricLine> lrcLines) {
        List<LyricLine> lyrics = lyricLines(lrcLines);
        if (lyrics.isEmpty()) {
            return new ReadHints(false, null);
        }
        TextAlignRun run = textAlignRun(timeline(tracks, selected), lyrics);
        return new ReadHints(run.text() != null && demoMatched(run.text()), noticeOf(run.quality()));
    }

    /** 这份对齐结果要不要按「默认词来自 demo 歌词」下发（标红同理）：对齐成功 + 有拼音格。 */
    private static boolean demoMatched(TextAlign text) {
        return text != null && (text.alignment().pinyin() || hasPinyinCell(text.timeline()));
    }

    private static List<List<String>> emptyDefaults(List<FillLine> lines) {
        return projectDefaults(lines, Map.of(), false);
    }

    /**
     * 把「(轨, 音符) → 汉字」按槽位下标投到每句，得到与 {@code lines} 同形状的二维表。
     * {@code pinyinSlotsOnly} = 只投给拼音格（原词含 ASCII 字母的槽位），其余格留空串。
     */
    private static List<List<String>> projectDefaults(List<FillLine> lines, Map<Long, String> byKey,
                                                      boolean pinyinSlotsOnly) {
        List<List<String>> result = new ArrayList<>(lines.size());
        for (FillLine line : lines) {
            List<String> row = new ArrayList<>(line.slots().size());
            for (FillSlot slot : line.slots()) {
                row.add(pinyinSlotsOnly && !hasAsciiLetter(slot.original()) ? ""
                        : byKey.getOrDefault(key(slot.trackIndex(), slot.noteIndex()), ""));
            }
            result.add(row);
        }
        return result;
    }

    // ==================== 视觉空位 ====================

    /**
     * 视觉空位（歌词句内空格，拼音模板与汉字模板共用）：与 {@code lines} / {@code filled}
     * 同形状，{@code true} = 这一格之后要画一个空位（歌词里这个字后面跟着空格）。命中的
     * 可填槽位且其歌词单元后跟空格时为 true，其余（未命中 / BREATH）false。
     * 命中的空位同样展开到组的全部可填成员。
     *
     * <p>命中的槽位若后面紧跟自己那串延音（{@code -}），标记会沿链推到链尾那一格
     * （{@link #prolongationEnd}）—— 所以 {@code DASH} 格也可能是 true，且**只有延音链尾
     * 那一格**是：字与它的延音之间不画空位。
     */
    private static List<List<Boolean>> gaps(List<FillLine> lines, List<Timed> fillable,
                                            int[] noteToLyric, List<Boolean> lyricGapAfter) {
        Set<Long> gapAfter = new HashSet<>();
        for (int i = 0; i < fillable.size() && i < noteToLyric.length; i++) {
            int j = noteToLyric[i];
            if (j >= 0 && j < lyricGapAfter.size() && Boolean.TRUE.equals(lyricGapAfter.get(j))) {
                FillSlot rep = fillable.get(i).slot();
                gapAfter.add(key(rep.trackIndex(), rep.noteIndex()));
                for (FillSlot member : fillable.get(i).members()) {
                    if (member.slotType().fillable()) {
                        gapAfter.add(key(member.trackIndex(), member.noteIndex()));
                    }
                }
            }
        }
        return projectGaps(lines, gapAfter);
    }

    /**
     * 直读缓存路径也要现算视觉空位（与默认词一样不落库）：给定当前分句 {@code lines}（可能是
     * 用户手动断开 / 合并过的），重跑一次对齐（拼音模板走拼音↔汉字、汉字模板走 LCS 文本对齐）、
     * 按槽位回指。对不上（无歌词 / 对齐失败）时按 {@code br} 兜底（见 {@link #breathGaps}），
     * 仍与 {@code lines} 同形状，保证下发到前端是「与 slots 等长」。
     */
    public static List<List<Boolean>> gaps(List<FillTrack> tracks, List<Integer> selected,
                                           List<FillLine> lines, List<LyricLine> lrcLines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        List<LyricLine> lyrics = lyricLines(lrcLines);
        if (lyrics.isEmpty()) {
            return breathGaps(lines);
        }
        TextAlign text = textAlign(timeline(tracks, selected), lyrics);
        if (text == null) {
            return breathGaps(lines);
        }
        Alignment a = text.alignment();
        return gaps(lines, text.units().fillable(), a.noteToLyric(), a.gapAfter());
    }

    /**
     * 括号声部标记（与 {@code lines} / {@code defaults} 同形状）：{@code true} = 这一格属于
     * 行尾括号里的那个声部。和默认词 / 视觉空位一样<b>不落库、每次现算</b> —— 用户在界面上
     * 手动断开 / 合并之后，标记跟着音符走，不会错位；库里也不多一列。
     */
    public static List<List<Boolean>> brackets(List<FillTrack> tracks, List<Integer> selected,
                                               List<FillLine> lines, List<LyricLine> lrcLines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        List<LyricLine> lyrics = lyricLines(lrcLines);
        if (lyrics.isEmpty()) {
            return emptyBrackets(lines);
        }
        TextAlign text = textAlign(timeline(tracks, selected), lyrics);
        if (text == null || text.alignment().sub() == null) {
            return emptyBrackets(lines);
        }
        Alignment a = text.alignment();
        List<Timed> fillable = text.units().fillable();
        Set<Long> subKeys = new HashSet<>();
        for (int i = 0; i < fillable.size() && i < a.sub().length; i++) {
            if (!a.sub()[i]) {
                continue;
            }
            FillSlot rep = fillable.get(i).slot();
            subKeys.add(key(rep.trackIndex(), rep.noteIndex()));
            for (FillSlot member : fillable.get(i).members()) {
                if (member.slotType().fillable()) {
                    subKeys.add(key(member.trackIndex(), member.noteIndex()));
                }
            }
        }
        return projectBrackets(lines, subKeys);
    }

    private static List<List<Boolean>> emptyBrackets(List<FillLine> lines) {
        return projectBrackets(lines, Set.of());
    }

    /** 把「属于括号声部」的 (轨, 音符) 按槽位下标投到每句（与 {@link #projectDefaults} 同口径）。 */
    private static List<List<Boolean>> projectBrackets(List<FillLine> lines, Set<Long> subKeys) {
        List<List<Boolean>> result = new ArrayList<>(lines.size());
        for (FillLine line : lines) {
            List<Boolean> row = new ArrayList<>(line.slots().size());
            for (FillSlot slot : line.slots()) {
                row.add(subKeys.contains(key(slot.trackIndex(), slot.noteIndex())));
            }
            result.add(row);
        }
        return result;
    }

    /**
     * 一条时间线与歌词文本的对齐（{@code noteToLyric}，下标 = 可填槽位序、值 = 歌词单元序）：
     * 拼音模板走拼音↔汉字匹配，汉字模板走 LCS 文本对齐。两者对不上都返回 null。
     */
    private static int[] noteToLyric(Units u) {
        if (PinyinLyricMatcher.isPinyinTemplate(u.noteUnits(), u.lyricUnits())) {
            PinyinLyricMatcher.Match m =
                    PinyinLyricMatcher.align(u.noteUnits(), u.lyricUnits(), u.lyricLineOf());
            return m.matched() ? m.noteToLyric() : null;
        }
        return textMatch(u);
    }

    private static List<List<Boolean>> emptyGaps(List<FillLine> lines) {
        return projectGaps(lines, Set.of());
    }

    /**
     * 无歌词匹配时的视觉空位兜底：每个换气（{@code br}）槽位之后画一个空位。
     *
     * <p>{@code br} 是换气点，也是下一句 / 句内下一词的起点标志（分句粗分路径里它就在
     * 句首，走法路径下也会落在句中）。歌词匹配不上时空位没有别处可依，就按 {@code br}
     * 兜底 —— 句中 {@code br} 的空位导出 lrc 时还原成句内空格，句首 {@code br} 的空位
     * 只是格间留白（lrc 不产生行首空格，见 {@code LyricFillService#lineText}）。
     * 有歌词匹配时仍以匹配出的空位为准，不叠加。
     */
    private static List<List<Boolean>> breathGaps(List<FillLine> lines) {
        Set<Long> afterBreath = new HashSet<>();
        for (FillLine line : lines) {
            for (FillSlot slot : line.slots()) {
                if (slot.slotType() == SlotType.BREATH) {
                    afterBreath.add(key(slot.trackIndex(), slot.noteIndex()));
                }
            }
        }
        return projectGaps(lines, afterBreath);
    }

    /**
     * 把「(轨, 音符) 后有空位」按槽位下标投到每句，得到与 {@code lines} 同形状的二维布尔表。
     * 投影<b>之前</b>先把标记推到延音链尾（{@link #prolongationEnd}）—— 字与它的 {@code -}
     * 之间不许插空位，空位列要画在整串延音之后。
     */
    private static List<List<Boolean>> projectGaps(List<FillLine> lines, Set<Long> gapAfter) {
        Set<Long> after = prolongationEnd(lines, gapAfter);
        List<List<Boolean>> result = new ArrayList<>(lines.size());
        for (FillLine line : lines) {
            List<Boolean> row = new ArrayList<>(line.slots().size());
            for (FillSlot slot : line.slots()) {
                row.add(after.contains(key(slot.trackIndex(), slot.noteIndex())));
            }
            result.add(row);
        }
        return result;
    }

    /**
     * 把空位标记沿本轨推到「延音链的尾巴」上：{@code -} 是它前面那个有效音符的延音（用户
     * 拍板的口径：<b>{@code -} 跟在有效音符后面</b>），字与它的延音之间不许插空位。标记落在
     * 某一格时，若同一句里紧跟着同轨连续的 {@code -}，就挂到最后一个 {@code -} 上 ——
     * 空位列于是画在整串延音之后，导出 lrc 的空格也补在延音之后（{@code LyricFillService#lineText}
     * 为延音格补这一手：延音格自己不出字）。
     *
     * <p>实测《夜奔》第 25 句 demo 歌词是「数尽更筹 听残银漏」、第 27 句是「急走忙逃 顾不德忠和孝」，
     * 模板里「筹」「逃」的下一个音符都正好是它们的 {@code -}：标记停在字上时空位列就把字与它的
     * 延音断开了。
     *
     * <p>只认<b>同一句内紧邻</b>的延音（同一轨、{@code noteIndex} 逐个 +1）：跨句的延音由
     * {@code relocateLeadingDashes} 并回上一句，真出现跨句残片（手动分行、整段重排）时标记
     * 原地不动 —— 退化成旧行为，不会把空位弄丢。
     */
    private static Set<Long> prolongationEnd(List<FillLine> lines, Set<Long> after) {
        if (after.isEmpty()) {
            return after;
        }
        Map<Long, Long> next = new HashMap<>();   // (轨, 音符) → 紧跟其后的延音槽键
        for (FillLine line : lines) {
            List<FillSlot> slots = line.slots();
            for (int k = 1; k < slots.size(); k++) {
                FillSlot cur = slots.get(k);
                FillSlot prev = slots.get(k - 1);
                if (cur.slotType() == SlotType.DASH && cur.trackIndex() == prev.trackIndex()
                        && cur.noteIndex() == prev.noteIndex() + 1) {
                    next.put(key(prev.trackIndex(), prev.noteIndex()),
                            key(cur.trackIndex(), cur.noteIndex()));
                }
            }
        }
        Set<Long> moved = new HashSet<>(after.size());
        for (long k : after) {
            Long cur = k;
            for (Long step = next.get(cur); step != null; step = next.get(cur)) {
                cur = step;
            }
            moved.add(cur);
        }
        return moved;
    }

    /**
     * 最长公共子序列：返回 {@code a} 每个下标对应的 {@code b} 下标（-1 = 没匹配上）。
     * 只存回溯方向（每格 1 字节）不存整张长度表，免得长歌把内存吃光。
     */
    private static int[] lcsMap(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        byte[] dir = new byte[n * m];      // 0 = 匹配, 1 = 走 a, 2 = 走 b
        int[] below = new int[m + 1];      // dp[i+1][*]
        int[] row = new int[m + 1];        // dp[i][*]
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                if (a.get(i).equals(b.get(j))) {
                    row[j] = below[j + 1] + 1;
                    dir[i * m + j] = 0;
                } else if (below[j] >= row[j + 1]) {
                    row[j] = below[j];
                    dir[i * m + j] = 1;
                } else {
                    row[j] = row[j + 1];
                    dir[i * m + j] = 2;
                }
            }
            int[] swap = below;
            below = row;
            row = swap;
            Arrays.fill(row, 0);
        }
        int[] map = new int[n];
        Arrays.fill(map, -1);
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            byte d = dir[i * m + j];
            if (d == 0) {
                map[i] = j;
                i++;
                j++;
            } else if (d == 1) {
                i++;
            } else {
                j++;
            }
        }
        return map;
    }

    /**
     * lrc 行对齐（回落）。偏移的定义是 <b>{@code lrc 秒 = svp 秒 + offset}</b>（自动偏移取
     * {@code lrc 首句 − 首个可填 note}，实测约 1 秒），所以 note 换算到 lrc 时间轴后
     * 落在 {@code [start_i, start_{i+1})} 就归第 i 句。
     *
     * <p><b>首句窗口之前的槽位并进首句</b>：自动偏移把首个可填 note 锚到首句起点，所以
     * 首句窗口里的 note 换算后必然全部 {@code < start_0}（实测首句 7 个 note 全在窗口前），
     * 单独成句会凭空多出一句、且首句反而空掉。只有整首都落在首句之前时才兜底单独成句。
     */
    private static List<FillLine> splitByLrc(List<Timed> timeline, List<Double> starts, double offset,
                                             Double bpm) {
        List<FillLine> lines = new ArrayList<>();
        List<Timed> leading = new ArrayList<>();
        List<Timed> bucket = new ArrayList<>();
        int pointer = 0;
        int currentIndex = -1;
        for (Timed timed : timeline) {
            // 声部拆分组：无论落在哪个歌词窗口，都自成一句
            if (timed.forcedBreak() && !bucket.isEmpty()) {
                lines.add(line(bucket));
                bucket = new ArrayList<>();
                currentIndex = -1;
            }
            double lrcSec = LyricFillParser.secondsOf(timed.onset()) + offset;
            while (pointer < starts.size() && lrcSec >= starts.get(pointer)) {
                pointer++;
            }
            int index = pointer - 1;   // -1 = 在首句之前
            if (index < 0) {
                leading.add(timed);
                continue;
            }
            if (bucket.isEmpty()) {
                bucket.addAll(leading);
                leading.clear();
                currentIndex = index;
            } else if (index != currentIndex) {
                lines.add(line(bucket));
                bucket = new ArrayList<>();
                currentIndex = index;
            }
            bucket.add(timed);
        }
        if (!bucket.isEmpty()) {
            lines.add(line(bucket));
        }
        if (!leading.isEmpty()) {
            // 全部槽位都在首句之前（lrc 起点比整首都晚）：兜底成一句，不丢数据
            lines.add(line(leading));
        }
        return lines;
    }

    /**
     * 无 lrc 的粗分：{@code br} 前断开 + 大空隙处断开 + 声部拆分组强制断开。
     *
     * <p><b>空隙按本轨量</b>（2026-09-21）：量的是「这条轨上一拍」到这一拍的距离，不是
     * 「时间线上前一个原子」的——两条声部交错时前一个原子往往是隔壁声部的，本轨一段
     * 连续乐句会被隔壁那一拍截开（实测形态：轨0 的长音还没结束、轨1 在中间插了一拍短音，
     * 轨0 的下一个音就被判成新句）；反过来隔壁填掉本轨的休止时，整段会粘成一句。按
     * {@link #segmentsOf} 同口径（代表轨 + 成员轨，取最新那个原子）。
     *
     * <p><b>延音（{@code -}）跟前一个音一体</b>：真延音紧接上一拍的尾巴，按本轨量出来的空隙本就
     * 是 0，这里不必额外开恩 —— 断点万一落在它前面，{@link #relocateLeadingDashes} 会把句首的
     * 延音并回上一句尾巴（同 {@link #segmentsOf}）。
     */
    private static List<FillLine> splitByBreathAndGap(List<Timed> timeline) {
        List<FillLine> lines = new ArrayList<>();
        List<Timed> bucket = new ArrayList<>();
        Map<Integer, Timed> lastOfTrack = new HashMap<>();
        for (Timed timed : timeline) {
            long gap = Long.MAX_VALUE;
            for (int track : tracksOf(timed)) {
                Timed prev = lastOfTrack.put(track, timed);
                if (prev == timed) {
                    continue;   // 同一个轨在一次原子里出现两次（代表轨也在 members 里）
                }
                if (prev != null) {
                    gap = Math.min(gap, timed.onset() - (prev.onset() + prev.duration()));
                }
            }
            boolean breathBreak = timed.slot().slotType() == SlotType.BREATH && !bucket.isEmpty();
            boolean gapBreak = gap != Long.MAX_VALUE && gap > GAP_THRESHOLD_BLICK;
            if ((breathBreak || gapBreak || timed.forcedBreak()) && !bucket.isEmpty()) {
                lines.add(line(bucket));
                bucket = new ArrayList<>();
            }
            bucket.add(timed);
        }
        if (!bucket.isEmpty()) {
            lines.add(line(bucket));
        }
        return lines;
    }

    /**
     * 一句：按声部（组）展开句内全部成员槽位——<b>声部优先</b>（每组是界面上的一行
     * 「原词 + 填写框」，组内按 onset），组号写入 {@code groups}（按句内首次出现序 0 递增，
     * 与 {@code slots} 等长）。句首 onset 取第一个原子的 onset —— 导出 lrc 的时间戳
     *（组内成员与代表最多差一个量化容差，时间戳精度足够）。
     */
    private static FillLine line(List<Timed> bucket) {
        // 槽位保持时间线序（onset），组号标注归属——前端按组过滤后各自仍是 onset 序
        List<FillSlot> slots = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        Map<Integer, Integer> groupOfVoice = new HashMap<>();
        for (Timed timed : bucket) {
            for (int i = 0; i < timed.members().size(); i++) {
                Integer group = groupOfVoice.get(timed.voiceIds().get(i));
                if (group == null) {
                    group = groupOfVoice.size();
                    groupOfVoice.put(timed.voiceIds().get(i), group);
                }
                slots.add(timed.members().get(i));
                ids.add(group);
            }
        }
        return new FillLine(slots, originalTextOf(slots), needCountOf(slots),
                bucket.getFirst().onset(), groupIdsByTrack(slots, ids));
    }

    /**
     * 桶里一个成员槽位：槽位本身 + 它那条声部的原始组号 + 它所在原子的 onset + 它归主流还是括号流。
     */
    private record Held(FillSlot slot, int group, long onset, boolean sub) {
    }

    /**
     * 把<b>被搬过来的</b>成员插回它自己那条声部的时间序上（别的成员一律顺序追加，
     * 见 {@link #lineWithStreams}）：从后往前找同组里最后一个不晚于它的槽位，插到那后面；
     * 同组里比它晚的都在它后面（继续往前找），一个都不比它早就插在段首。
     *
     * <p><b>只在同一条轨里比</b> —— 段内其它声部的槽位一格不动。整段按 onset 重排是不行的：
     * 时间线里同一个原子的成员本来就是相邻的，重排会把不同声部交错起来，原词就成了
     * 「甲声部的字夹在乙声部里」。
     */
    private static void insertHeld(List<Held> list, Held held) {
        int at = list.size();
        for (int i = list.size() - 1; i >= 0; i--) {
            Held prev = list.get(i);
            if (prev.group() != held.group()) {
                continue;
            }
            if (prev.onset() <= held.onset()) {
                at = i + 1;
                break;
            }
            at = i;
        }
        list.add(at, held);
    }

    /**
     * 本句里每条轨唱的是哪条流：只有它在<b>本句的代表原子</b>全是括号声部时才算「括号轨」
     * （一条轨在本句既唱主流、又唱括号流时算主流 —— 保守，与从前一致）。桶里没出现过的轨
     * 静默缺席，调用方退回原子自己的流标记。
     */
    private static Map<Integer, Boolean> subTracksOf(List<Piece> pieces) {
        Map<Integer, Boolean> out = new HashMap<>();
        for (Piece piece : pieces) {
            out.merge(piece.atom().slot().trackIndex(), piece.sub(), (a, b) -> a && b);
        }
        return out;
    }

    /**
     * 句内槽位的排出（{@link #buildLines} 的那一支）：在 {@link #line(List)} 之上
     * <b>逐成员定流</b>，保证「组内按 onset」这条不变量。
     *
     * <p>「主流在前、括号声部在后」这条排序（{@link #buildLines}）整体看是对的，但
     * <b>一个多成员原子横跨两条流时，它会把某一条轨的槽位次序打乱</b>：实测《遗世蒹葭》第 34 句，
     * 和声轨的「了」与主轨的「了」同拍同词被并成一个原子（{@link #timedOf} 的代表是主轨），
     * 整个原子按「主流」排在前面，那条和声轨的槽位就成了「了 纷 - 飞 勾 - 勒」——「了」跑到
     * 它自己那句的开头。前端是按组（＝轨）画行的（{@code songfill.js} 的 {@code lineGroups}），
     * 于是那一行的字序整个错掉（用户报的就是这个）。
     *
     * <p>所以成员归哪一段，看<b>它那条轨在本句唱的是哪条流</b>（{@link #subTracksOf}），不看
     * 它所在的原子被谁认领。没被搬动的成员一律顺序追加 —— 时间线怎么排就怎么排，同一个原子的
     * 成员相邻（合唱那一行就是这么来的）；只有「它那条轨的流与它所在原子的流不同」的成员
     * 才用 {@link #insertHeld} 插回它自己那条轨的时间序上，且**等这一句的成员都到齐了再插**
     * （{@code walk} 是按短语吐原子的，那个多成员原子跟着主流那句先来，先插就会插到段首 ——
     * 就是用户报的那个「了 纷 - 飞 勾 - 勒」）。一条轨在本句唱的是哪条流桶里没说时
     * （{@code getOrDefault} 那条退路）当作没搬动，与从前一致。
     */
    private static FillLine lineWithStreams(List<Piece> pieces) {
        Map<Integer, Boolean> subTracks = subTracksOf(pieces);
        Map<Integer, Integer> groupOfVoice = new HashMap<>();
        List<Held> main = new ArrayList<>();
        List<Held> bracketed = new ArrayList<>();
        List<Held> moved = new ArrayList<>();
        for (Piece piece : pieces) {
            Timed atom = piece.atom();
            List<FillSlot> members = atom.members();
            for (int i = 0; i < members.size(); i++) {
                int track = atom.voiceIds().get(i);
                Integer group = groupOfVoice.get(track);
                if (group == null) {
                    group = groupOfVoice.size();
                    groupOfVoice.put(track, group);
                }
                FillSlot slot = members.get(i);
                boolean sub = members.size() > 1
                        ? subTracks.getOrDefault(slot.trackIndex(), piece.sub())
                        : piece.sub();
                Held held = new Held(slot, group, atom.onset(), sub);
                if (sub == piece.sub()) {
                    (sub ? bracketed : main).add(held);
                } else {
                    moved.add(held);
                }
            }
        }
        for (Held held : moved) {
            insertHeld(held.sub() ? bracketed : main, held);
        }
        List<FillSlot> slots = new ArrayList<>(main.size() + bracketed.size());
        List<Integer> ids = new ArrayList<>(main.size() + bracketed.size());
        for (Held held : main) {
            slots.add(held.slot());
            ids.add(held.group());
        }
        for (Held held : bracketed) {
            slots.add(held.slot());
            ids.add(held.group());
        }
        return new FillLine(slots, originalTextOf(slots), needCountOf(slots),
                pieces.getFirst().atom().onset(), groupIdsByTrack(slots, ids));
    }

    /**
     * 原词 = 拼接可填槽位的原值。{@code -} / {@code br} / {@code 0} 不参与 —— 被点开的延音
     * 或静音占位（{@code slotType} 已改成 {@code HANZI}，见 {@link FillSlot}）也不参与，
     * 否则原词里会凭空多一个 {@code -} / {@code 0}。
     */
    public static String originalTextOf(List<FillSlot> slots) {
        StringBuilder sb = new StringBuilder();
        for (FillSlot slot : slots) {
            String original = slot.original();
            if (slot.slotType().fillable() && original != null
                    && !"-".equals(original) && !"br".equalsIgnoreCase(original)
                    && !"0".equals(original)) {
                sb.append(original);
            }
        }
        return sb.toString();
    }

    /** 需填字数 = 可填槽位数（含合唱重复组——另一组空格会联动复制，实际只需填一遍）。 */
    public static int needCountOf(List<FillSlot> slots) {
        return (int) slots.stream().filter(s -> s.slotType().fillable()).count();
    }

    /**
     * 一句是否<b>整句填满</b>：每个可填槽位（含合唱重复组）在自己的槽位下标上都有非空值。
     * 语料配对「整句填满才收」的判据（填词助手设计 §5.6）—— 按槽位下标对号，
     * 不能数整行非空串（dash / 换气位也占 filled 的下标）。
     */
    public static boolean isLineFilled(FillLine line, List<String> values) {
        if (line == null || line.needCount() <= 0) {
            return false;
        }
        for (int k = 0; k < line.slots().size(); k++) {
            if (!line.slots().get(k).slotType().fillable()) {
                continue;
            }
            String value = values != null && k < values.size() ? values.get(k) : null;
            if (StringUtils.isBlank(value)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 用槽位重算一句的派生字段（原词 / 需填字数）。保存时走一遍，
     * 让「前端手动断开 / 合并后只改了槽位划分」的数据在库里也是自洽的。
     */
    public static FillLine recompute(FillLine line) {
        return new FillLine(line.slots(), originalTextOf(line.slots()), needCountOf(line.slots()),
                line.startOnset(), line.groups());
    }

    // ==================== 跨组联动 ====================

    /**
     * 句内跨声部组联动复制：相同音符（onset 容差 + 同原词）之间「有值 → 空」双向复制——
     * 合唱的另一组没填的格自动抄已填那组的字（需求 2）。前端输入时已实时联动，
     * 这里是保存 / 导出的兜底（旧数据、或没经过页面的调用）。没有 {@code groups} 的句
     * （旧格式）整句跳过。
     */
    public static List<List<String>> syncGroupCopies(List<FillLine> lines, List<List<String>> filled,
                                                     List<FillTrack> tracks) {
        if (lines == null || filled == null || tracks == null || tracks.isEmpty()) {
            return filled;
        }
        Map<Long, Long> onsets = new HashMap<>();
        for (FillTrack track : tracks) {
            List<FillNote> notes = track.notes();
            for (int i = 0; i < notes.size(); i++) {
                onsets.put(key(track.trackIndex(), i), notes.get(i).onset());
            }
        }
        boolean changed = false;
        List<List<String>> result = new ArrayList<>(filled.size());
        for (int i = 0; i < lines.size(); i++) {
            List<String> row = i < filled.size() && filled.get(i) != null ? filled.get(i) : List.of();
            FillLine line = lines.get(i);
            List<String> copied = line.groups() == null
                    ? row : copyAcrossGroups(line, row, onsets);
            if (copied != row) {
                changed = true;
            }
            result.add(copied);
        }
        return changed ? result : filled;
    }

    /** 一句内的跨组复制；没有可复制的空格时原行原样返回（同一引用）。 */
    private static List<String> copyAcrossGroups(FillLine line, List<String> row,
                                                 Map<Long, Long> onsets) {
        List<FillSlot> slots = line.slots();
        List<Integer> groups = line.groups();
        Map<Integer, List<Integer>> byGroup = new LinkedHashMap<>();
        for (int si = 0; si < groups.size() && si < slots.size(); si++) {
            byGroup.computeIfAbsent(groups.get(si), k -> new ArrayList<>()).add(si);
        }
        if (byGroup.size() < 2) {
            return row;
        }
        List<Integer> ids = new ArrayList<>(byGroup.keySet());
        List<String> out = null;   // 惰性拷贝：没有要复制的就不新建行
        for (int g = 0; g < ids.size(); g++) {
            for (int h = g + 1; h < ids.size(); h++) {
                for (int[] pair : pairSlots(slots, byGroup.get(ids.get(g)), byGroup.get(ids.get(h)), onsets)) {
                    String va = at(row, pair[0]);
                    String vb = at(row, pair[1]);
                    if (va.isBlank() == vb.isBlank()) {
                        continue;
                    }
                    if (out == null) {
                        out = new ArrayList<>(row);
                        while (out.size() < slots.size()) {
                            out.add("");
                        }
                    }
                    if (va.isBlank()) {
                        out.set(pair[0], vb);
                    } else {
                        out.set(pair[1], va);
                    }
                }
            }
        }
        return out != null ? out : row;
    }

    private static String at(List<String> row, int index) {
        return index < row.size() ? row.get(index) : "";
    }

    /**
     * 两组槽位按「onset 容差 + 同原词」双指针配对（组内各自按 onset 有序），返回
     * {@code [a 组槽序, b 组槽序]}。组间联动复制与导出去重共用同一口径。
     */
    public static List<int[]> pairSlots(List<FillSlot> slots, List<Integer> a, List<Integer> b,
                                        Map<Long, Long> onsets) {
        List<int[]> pairs = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            FillSlot sa = slots.get(a.get(i));
            FillSlot sb = slots.get(b.get(j));
            Long oa = onsets.get(key(sa.trackIndex(), sa.noteIndex()));
            Long ob = onsets.get(key(sb.trackIndex(), sb.noteIndex()));
            if (oa == null || ob == null) {
                // 骨架里找不到音符（数据不一致）：跳过这一对，防死循环
                i++;
                j++;
                continue;
            }
            long diff = oa - ob;
            if (Math.abs(diff) <= UNISON_TOLERANCE_BLICK) {
                if (Objects.equals(sa.original(), sb.original())) {
                    pairs.add(new int[]{a.get(i), b.get(j)});
                    i++;
                    j++;
                } else if (diff <= 0) {
                    i++;
                } else {
                    j++;
                }
            } else if (diff < 0) {
                i++;
            } else {
                j++;
            }
        }
        return pairs;
    }

    /** 组 {@code g1} 的音符是否全部配上组 {@code g2}（被包含）——导出时被包含的组跳过。 */
    public static boolean groupCoveredBy(List<FillSlot> slots, List<Integer> g1, List<Integer> g2,
                                         Map<Long, Long> onsets) {
        return !g1.isEmpty() && pairSlots(slots, g1, g2, onsets).size() == g1.size();
    }

    /**
     * 句内按组收集槽位序（组内保持槽位顺序）；无 groups 时整句一组。
     *
     * <p>组的先后 = <b>工程里的轨序</b>（组里最小的轨号），与 {@link #groupIdsByTrack} 的编号、
     * 页面上声部行的上下顺序同一口径：一句里两个互不包含的声部谁先写进歌词，看谁的轨号小。
     * 不能按「组号首现序」（{@link LinkedHashMap} 的天然顺序）：那是槽位在句内的时间序，
     * 补进来的 br / 延音会让它跳（实测《栖凰》「谯鼓响」那句排成 11 / 7 / 12）。
     */
    public static Map<Integer, List<Integer>> groupIndexes(FillLine line) {
        Map<Integer, List<Integer>> byGroup = new LinkedHashMap<>();
        List<Integer> groups = line.groups();
        for (int si = 0; si < line.slots().size(); si++) {
            int group = groups != null && si < groups.size() ? groups.get(si) : 0;
            byGroup.computeIfAbsent(group, k -> new ArrayList<>()).add(si);
        }
        Map<Integer, Integer> minTrack = new HashMap<>();
        byGroup.forEach((group, indexes) -> minTrack.put(group, indexes.stream()
                .mapToInt(si -> line.slots().get(si).trackIndex()).min().orElse(0)));
        Map<Integer, List<Integer>> ordered = new LinkedHashMap<>();
        byGroup.entrySet().stream()
                .sorted(Comparator.<Map.Entry<Integer, List<Integer>>>comparingInt(
                                e -> minTrack.get(e.getKey()))
                        .thenComparingInt(Map.Entry::getKey))
                .forEach(e -> ordered.put(e.getKey(), e.getValue()));
        return ordered;
    }

    // ==================== tokenize ====================

    /**
     * 把一句文本切分到该句的可填槽位（填词工具设计 6）：按空格 split，
     * 含 ASCII 字母的 token 整体算一个单元，其余（中文）逐字拆。
     *
     * <p>{@code "hello world 天涯"} → {@code [hello][world][天][涯]} = 4 个单元。
     * 用在两处：歌词文本对齐（{@link #textMatch}），以及前端把「一次粘进一格的多字
     * 文本」摊到后面几格（{@code songfill.js} 里有一份同规则的 JS 实现，两处必须一致）。
     */
    public static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> units = new ArrayList<>();
        for (String token : text.trim().split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            if (hasAsciiLetter(token)) {
                units.add(token);
            } else {
                token.codePoints().forEach(cp -> units.add(new String(Character.toChars(cp))));
            }
        }
        return units;
    }

    /**
     * 与 {@link #tokenize} 同规则地切一句，但额外标出「哪个单元之后跟着一个<b>本行内的</b>空格」。
     * 空格本身不参与对齐（不占音符、不占单元），只在拼音模板命中时作为视觉空位透出到页面。
     * {@code gapAfter} 与 {@code units} 一一对应：{@code true} = 这个单元是某个空白段的最后一个
     * 单元，且后面还有同行的另一个空白段（即中间夹了一个空格）。
     */
    private static void tokenizeLine(String text, List<String> units, List<Boolean> gapAfter) {
        if (text == null || text.isBlank()) {
            return;
        }
        String[] tokens = text.trim().split("\\s+");
        for (int t = 0; t < tokens.length; t++) {
            List<String> piece = tokenize(tokens[t]);
            for (int u = 0; u < piece.size(); u++) {
                units.add(piece.get(u));
                gapAfter.add(u == piece.size() - 1 && t < tokens.length - 1);
            }
        }
    }

    /**
     * 切出的单元数。界面上的「已填 M / 需 N」现在是按<b>填了几格</b>算（一格一个单元），
     * 不再是这个 —— 留它是因为单元规则要有个可断言的出口。
     */
    public static int countUnits(String text) {
        return tokenize(text).size();
    }

    /** 含 ASCII 字母的 token 整体算一个槽位（英文单词一个词一个字）。 */
    public static boolean hasAsciiLetter(String token) {
        return token != null && token.codePoints()
                .anyMatch(cp -> (cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z'));
    }

    // ==================== 导出辅助 ====================

    /**
     * 填词回指音符：{@code key(trackIndex, noteIndex) → 填的词}。
     *
     * <p><b>按槽位下标一一对应</b>：{@code filled.get(i).get(k)} 就是 {@code lines.get(i)
     * .slots().get(k)} 这一格的词，空串 / 缺位 = 没填，不进表 —— 导出时回落到原词
     * （{@code -} / {@code br} 也在这层回落）。**不看 {@code slotType}**：填了字的延音
     * 槽位类型虽然是 {@code HANZI}，但没填字的延音也可能有值以外的槽位状态，
     * 一律以「这格有没有值」为准，比按类型分支少一处出错的地方。
     */
    public static Map<Long, String> filledByNote(List<FillLine> lines, List<List<String>> filled) {
        Map<Long, String> map = new HashMap<>();
        if (lines == null) {
            return map;
        }
        for (int i = 0; i < lines.size(); i++) {
            if (filled == null || i >= filled.size()) {
                continue;
            }
            List<String> values = filled.get(i);
            List<FillSlot> slots = lines.get(i).slots();
            for (int k = 0; k < slots.size() && k < values.size(); k++) {
                String value = values.get(k);
                if (StringUtils.isBlank(value)) {
                    continue;
                }
                FillSlot slot = slots.get(k);
                map.put(key(slot.trackIndex(), slot.noteIndex()), value);
            }
        }
        return map;
    }

    /** (轨序号, 轨内音符下标) 的合成键，用于把填的词回指到音符。 */
    public static long key(int trackIndex, int noteIndex) {
        return ((long) trackIndex << 32) | (noteIndex & 0xFFFFFFFFL);
    }

    /**
     * 让「被点开的延音 / 静音占位」与填词内容一致：{@code original == "-"} / {@code "0"} 的槽位，
     * 填了字就是 {@code HANZI}（算进需填字数、原词仍不含它），空着就还原成 {@code DASH} /
     * {@code ZERO}（跳过不填）。
     *
     * <p>两个方向都得修：旧版本前端「点一下先标 HANZI、填不填另说」，留下了空着的 HANZI
     * 延音（「需填」会多算）；而重跑分句后按句序号保留的旧填词可能落到延音格上（那里还是
     * DASH，不修的话界面会跳过这个字、导出却写出去）。
     *
     * <p>不改动的行原样返回（同一引用），方便调用方判断有没有必要重算派生字段。
     * 重建的行透传 {@code groups}（声部组划分不受延音开关影响）。
     */
    public static List<FillLine> syncDashes(List<FillLine> lines, List<List<String>> filled) {
        if (lines == null) {
            return List.of();
        }
        List<FillLine> result = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            FillLine line = lines.get(i);
            List<String> row = filled != null && i < filled.size() && filled.get(i) != null
                    ? filled.get(i) : List.of();
            List<FillSlot> slots = new ArrayList<>(line.slots().size());
            boolean changed = false;
            for (int k = 0; k < line.slots().size(); k++) {
                FillSlot slot = line.slots().get(k);
                // 记号格的开关只认原词：填开了就是普通待填格，空着就还原成它本来的记号
                boolean hasValue = k < row.size() && StringUtils.isNotBlank(row.get(k));
                SlotType want = switch (Objects.toString(slot.original(), "")) {
                    case "-" -> hasValue ? SlotType.HANZI : SlotType.DASH;
                    case "0" -> hasValue ? SlotType.HANZI : SlotType.ZERO;
                    default -> slot.slotType();
                };
                if (slot.slotType() == want) {
                    slots.add(slot);
                } else {
                    slots.add(new FillSlot(slot.trackIndex(), slot.noteIndex(),
                            slot.original(), want, slot.glottal()));
                    changed = true;
                }
            }
            result.add(changed
                    ? recompute(new FillLine(slots, null, 0, line.startOnset(), line.groups())) : line);
        }
        return result;
    }
}
