package io.github.Nyameph.nyaentworks.song.fill.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import io.github.Nyameph.nyaentworks.common.ai.AiAvailability;
import io.github.Nyameph.nyaentworks.common.ai.AiChatClient;
import io.github.Nyameph.nyaentworks.common.config.LocalAiProperties;
import io.github.Nyameph.nyaentworks.common.config.OnlineAiProperties;
import io.github.Nyameph.nyaentworks.common.pinyin.PinyinSyllable;
import io.github.Nyameph.nyaentworks.common.pinyin.PinyinUtil;
import io.github.Nyameph.nyaentworks.song.config.SongProperties;
import io.github.Nyameph.nyaentworks.song.entity.LyricCorpusPair;
import io.github.Nyameph.nyaentworks.song.entity.SongLyricFill;
import io.github.Nyameph.nyaentworks.song.fill.LyricFillAligner;
import io.github.Nyameph.nyaentworks.song.fill.LyricFillService;
import io.github.Nyameph.nyaentworks.song.fill.LyricFillStore;
import io.github.Nyameph.nyaentworks.song.fill.LyricTemplate;
import io.github.Nyameph.nyaentworks.song.fill.corpus.CorpusService;
import io.github.Nyameph.nyaentworks.song.fill.rhyme.RhymeService;
import io.github.Nyameph.nyaentworks.song.mapper.SongLyricFillMapper;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 填词：韵脚方案 / 提示词组装 / few-shot 取材 / 校验循环 / 结果预览
 * （填词助手设计 §6）。
 *
 * <p><b>校验循环是机器可验的，不靠模型自觉</b>（§6.3）：先解析（剥围栏、截
 * {@code […]}、句数比对），再逐句校验（字数用 {@link LyricFillAligner#tokenize}、
 * 句尾韵用 {@link PinyinUtil}），不合格的句带着原因重发，重试时温度降 0.2。
 * 2 轮后仍不合格的原样返回并标红，交人工改。
 *
 * <p><b>句子的「原句」「字数」「当前填词」都按导出 lrc 的口径</b>（{@link PlanLine#lrcText()} /
 * {@link PlanLine#lrcCount()} / {@link PlanLine#lrcFilled()}）：一句里有多个音轨时导出会合并成一
 * 行，模型看到的字数必须与最终进格的那一行同口径。口径本身走
 * {@link LyricFillService#lrcTexts(SongLyricFill)}（复用的就是导出那份拼装，不另写一份）。
 *
 * <p><b>数据红线（§9.4）</b>：模型返回的歌词正文<b>不落 async_task</b>
 * （不写 message/logs/result_json），只存进程内预览表、由 {@code /ai/preview} 回给前端；
 * 任务表只记「生成 N 句、重试 X 次、不合格 Y 句」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FillAiService {

    /** 生成模式。 */
    public static final String MODE_FULL = "full";
    public static final String MODE_CONTINUE = "continue";
    public static final String MODE_REFILL = "refill";

    private final SongLyricFillMapper fillMapper;
    private final SongProperties properties;
    private final OnlineAiProperties onlineAi;
    /** 本机端点与模型，与漫画 eh 扫描相似度、韵脚词性共用同一份（见该类注释） */
    private final LocalAiProperties localAi;
    private final CorpusService corpusService;
    /** 「端点/模型配全了没有」的唯一口径（见该类注释）。本机与在线两条路各判各的 */
    private final AiAvailability aiAvailability;
    /** 每句「原词按导出 lrc 口径合并」的文本从这里要（复用 {@code lineText}，不另写一份）。 */
    private final LyricFillService lyricFillService;

    /** 最近一次生成结果（fillId → 预览）。进程内即可：应用重启后草稿本来就要重新生成。 */
    private final ConcurrentHashMap<Long, Preview> previews = new ConcurrentHashMap<>();

    // ==================== 状态与两道闸（§7.4） ====================

    /**
     * 填词页按钮可见性与在线确认框用的状态。
     *
     * @param enabled          AI 填词现在能不能用。<b>总开关开着但端点/模型没配全时也是 false</b> ——
     *                         按钮随之隐藏，不然点下去只会得到一个失败任务
     * @param reason           不能用而被本类关掉的原因（端点/模型没配）；总开关是用户自己关的、
     *                         或一切正常时为 {@code null}。页面拿它替代按钮显示一句说明
     * @param onlineConfigured 在线端点配全没有（只影响界面上那句「将发往 xxx」）
     * @param onlineHost       在线端点的 host，供确认框显示「这句话要发到哪儿」
     */
    public record Status(boolean enabled, boolean useOnline, boolean onlineConfigured,
                         String onlineHost, String reason) {
    }

    public Status status() {
        var cfg = properties.getFillAi();
        boolean useOnline = cfg.isUseOnline();
        // 端点配全没有：走在线判在线那份，走本机判本机那份 —— 两条路用的是两份不同的配置
        String endpoint = useOnline ? aiAvailability.onlineDeny() : aiAvailability.localDeny();
        // 只在用户自己开着 AI 填词时才报「因为端点而关」：他自己关掉的，
        // 页面上再挂一句「端点没配」是噪音
        return new Status(cfg.isEnabled() && endpoint == null, useOnline, onlineEnabled(),
                onlineEnabled() ? hostOf(onlineAi.getBaseUrl()) : null,
                cfg.isEnabled() ? endpoint : null);
    }

    private boolean onlineEnabled() {
        return onlineAi.isEnabled() && StringUtils.isNotBlank(onlineAi.getBaseUrl());
    }

    /** 本机 host 判定（§7.4 第 2 道闸的本机口径）。 */
    static boolean isLocalHost(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                || "::1".equals(host) || "0.0.0.0".equals(host);
    }

    static String hostOf(String baseUrl) {
        try {
            String host = URI.create(baseUrl).getHost();
            return host == null ? null
                    : (host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 两道闸（纯函数，有单测）。返回 {@code null} = 放行，否则 = 给人看的拒绝原因。
     *
     * <ol>
     *   <li>{@code useOnline=true}：要求 common.online-ai.enabled=true 且 base-url 非空，
     *       缺一直接拒绝并说清缺哪个；</li>
     *   <li>{@code useOnline=false}：{@code common.local-ai.base-url} 的 host 不是本机 → 拒绝 ——
     *       防的是「把在线地址填进本地槽位、以为还在本地跑」的误外发。</li>
     * </ol>
     */
    static String onlineGuard(boolean useOnline, String localBaseUrl,
                              boolean onlineEnabled, String onlineBaseUrl) {
        if (useOnline) {
            if (!onlineEnabled) {
                return "在线模式被拒：common.online-ai.enabled=false（配置在 config/application-secret.yaml，"
                        + "与 song.fill-ai.use-online 同时为 true 才走在线）";
            }
            if (StringUtils.isBlank(onlineBaseUrl)) {
                return "在线模式被拒：common.online-ai.base-url 为空（配置在 config/application-secret.yaml）";
            }
            return null;
        }
        String host = hostOf(localBaseUrl);
        if (!isLocalHost(host)) {
            return "common.local-ai.base-url 指向 " + (host == null ? "（解析不出 host）" : host)
                    + "，请改用 common.online-ai，或改回 http://localhost:11434/v1";
        }
        return null;
    }

    /** 生成前的整体闸门：总开关 + 两道闸。拒绝时抛 {@link IllegalArgumentException}。 */
    public void requireAllowedPublic(boolean useOnline) {
        requireAllowed(useOnline);
    }

    private void requireAllowed(boolean useOnline) {
        if (!properties.getFillAi().isEnabled()) {
            throw new IllegalArgumentException("AI 填词未开启（nya-entworks.song.fill-ai.enabled=false）");
        }
        // 端点配全没有<b>放在 onlineGuard 之前</b>：缺 base-url 时 onlineGuard 的本地分支
        // 会给「common.local-ai.base-url 指向 （解析不出 host）」，那句话对「你根本没填」不好懂
        String endpoint = useOnline ? aiAvailability.onlineDeny() : aiAvailability.localDeny();
        if (endpoint != null) {
            throw new IllegalArgumentException(endpoint);
        }
        String deny = onlineGuard(useOnline, localAi.getBaseUrl(),
                onlineAi.isEnabled(), onlineAi.getBaseUrl());
        if (deny != null) {
            throw new IllegalArgumentException(deny);
        }
    }

    // ==================== 韵脚方案（§6.1） ====================

    /**
     * 方案表的一行。
     *
     * <p><b>字数与「原句」都是导出 lrc 的口径</b>（填词助手设计 §6.1）：一句里有多个音轨时，
     * 导出的 lrc 会合并成<b>一行</b>，所以模型看到、校验用的都是 {@code lrcText} /
     * {@code lrcCount}（{@code tokenize(lrcText).size()}）。
     *
     * <p>五个容易混的字段，各是谁的内容、什么口径（<b>别再拿 {@code originalText} 当字数基准</b>）：
     *
     * <table border="1">
     *   <caption>字段口径一览</caption>
     *   <tr><th>字段</th><th>谁的内容</th><th>口径</th></tr>
     *   <tr><td>{@code originalText}</td><td>原词</td>
     *       <td>按可填槽位原值<b>直接拼接</b>（不合并声部、不剔 {@code -}、不补空格），只有展示价值</td></tr>
     *   <tr><td>{@code lrcText}</td><td>原词</td>
     *       <td><b>导出 lrc 口径</b>：声部组去重、括号声部 {@code A（B）}、{@code -} 剔除、句内空位还原空格</td></tr>
     *   <tr><td>{@code lrcFilled}</td><td><b>当前填词</b></td>
     *       <td>同 {@code lrcText} 的导出口径，只是把当前填词当填好的值（整句没填过 = 空串）</td></tr>
     *   <tr><td>{@code needCount}</td><td>该句</td>
     *       <td>全部可填<b>槽位数</b>（含合唱重复组），与歌词文本无关，<b>不是字数基准</b></td></tr>
     *   <tr><td>{@code lrcCount}</td><td>该句</td>
     *       <td>{@code tokenize(lrcText).size()} —— <b>句子的字数基准</b>（模型标注与校验都用它）</td></tr>
     * </table>
     *
     * @param needCount    该句全部可填槽位的个数（含合唱重复组）。<b>不再是字数基准</b>，
     *                     只作为方案表的既有契约保留（前端「字数」列与回传的
     *                     {@link PlanLineInput#needCount()} 还在用它）
     * @param originalText 该句的原词（按可填槽位原值拼），展示用
     * @param lrcText      该句原词<b>按导出 lrc 的口径合并</b>后的文本：声部组去重、
     *                     括号声部写成 {@code A（B）}、{@code -} 剔除、句内空位还原空格
     *                     （走 {@link LyricFillService#lrcTexts(SongLyricFill)}，
     *                     复用的就是导出那份拼装）
     * @param lrcFilled    该句<b>当前填词内容</b>按同一个导出 lrc 口径合并后的文本
     *                     （同一条链、只是把当前填词当 {@code values} 传进去）；
     *                     <b>整句一个字都没填过的句为空串</b>（不是 {@code null} —— 前端拿它当
     *                     输入框默认值，{@code null} 会画成 "null"）
     * @param lrcCount     {@code tokenize(lrcText).size()} —— 句子的字数基准
     */
    public record PlanLine(int index, int needCount, String originalText, String tailOriginal,
                           String rhymeBody, String rhymeLabel, boolean englishTail,
                           int englishWords, String lrcText, String lrcFilled, int lrcCount) {
    }

    /**
     * 在线确认框要的完整 prompt 预览（不是摘要 —— 原词可能含成人内容，用户必须看得见）。
     * {@code user} 覆盖本次全部目标句：多批时按批拼接、批间有分界标记，与真发出去的一致。
     */
    public record PromptPreview(String system, String user) {
    }

    /**
     * 方案。
     *
     * @param mainRhymeBody  主韵（前端「主韵改为」下拉用）= 当前模式下参与生成的句里，
     *                       默认韵身<b>占多数</b>的那个（「不限」的句不参与投票，见
     *                       {@link #mainRhymeBody(List, List)}）；众数不唯一（平票）或一句都
     *                       拆不出韵时 {@code null}（前端据此禁用那个下拉：本曲没有占多数的韵）
     * @param mainRhymeLabel 主韵的尾韵标签（与 {@link PlanLine#rhymeLabel()} 同一写法）
     */
    public record Plan(boolean enabled, boolean useOnline, boolean onlineConfigured,
                       List<PlanLine> lines, PromptPreview promptPreview,
                       String mainRhymeBody, String mainRhymeLabel) {
    }

    /** 方案请求：只带 fillId = 进抽屉时的预生成；带 theme+useOnline = 提交前算在线 prompt 预览。 */
    public record PlanRequest(Long fillId, String mode, String theme, Boolean useOnline,
                              List<PlanLineInput> plan, List<Integer> onlyLines,
                              Integer batchSize) {
    }

    /** 前端改韵后的每行方案（rhymeBody null = 不限）。 */
    public record PlanLineInput(int index, Integer needCount, String rhymeBody) {
    }

    public Plan plan(PlanRequest request) {
        SongLyricFill row = requireFill(request.fillId());
        List<TemplateLine> lines = readLines(row);
        Map<Integer, String> overrides = new LinkedHashMap<>();
        if (request.plan() != null) {
            for (PlanLineInput p : request.plan()) {
                overrides.put(p.index(), StringUtils.trimToNull(p.rhymeBody()));
            }
        }
        List<PlanLine> planLines = new ArrayList<>(lines.size());
        for (TemplateLine line : lines) {
            planLines.add(planLine(line, overrides.get(line.index())));
        }
        // 主韵只看「当前模式下参与生成的句」（refill 只算 onlyLines，continue / full 算全部）
        List<Integer> targets = targetLines(lines.size(),
                StringUtils.defaultIfBlank(request.mode(), MODE_FULL), row, request.onlyLines());
        String mainBody = mainRhymeBody(planLines, targets);
        PromptPreview preview = null;
        if (request.theme() != null && Boolean.TRUE.equals(request.useOnline())) {
            preview = buildPromptPreview(row, lines, request, planLines);
        }
        var cfg = properties.getFillAi();
        return new Plan(cfg.isEnabled(), cfg.isUseOnline(), onlineEnabled(), planLines, preview,
                mainBody, mainBody == null ? null : FillAiPrompts.rhymeLabelOf(mainBody));
    }

    /** 一行方案怎么来：最后一个可填槽位的原词走锚解析（拼音串也吃）；英文 / 拆不出 → 不限。 */
    private static PlanLine planLine(TemplateLine line, String overrideBody) {
        String body = overrideBody != null ? overrideBody
                : defaultBodyOf(line).orElse(null);
        String label = body == null ? null : FillAiPrompts.rhymeLabelOf(body);
        return new PlanLine(line.index(), line.line().needCount(), line.line().originalText(),
                line.tailOriginal(), body, label, line.englishTail(), line.englishWords(),
                line.lrcText(), line.lrcFilled(), line.lrcCount());
    }

    /**
     * 主韵（需求：前端「主韵改为」下拉）= 当前模式下<b>参与生成的句</b>里，默认韵身
     * （{@link PlanLine#rhymeBody()}）出现次数最多的那个。众数唯一才算 —— 平票
     * （两个韵身并列最多）或一句都拆不出韵时返回 {@code null}（前端禁用下拉并提示
     * 「本曲没有占多数的韵」）。判定在后端（前端只画不判，实现说明 §2.8）。
     *
     * <p>「不限」（{@code rhymeBody == null}，英文尾 / 拆不出韵的句）<b>不参与投票</b>：它表达的是
     * 「这一句没有韵」，不是「另一个韵」—— 1 句押 an、9 句不限 → 主韵仍是 an（下拉可用），
     * 判 {@code null} 会让用户看着「明明有一句 an」以为坏了。一句都拆不出韵（全是 null）才没有主韵。
     *
     * @param targets 参与生成的句下标（{@code mode=refill} 只有勾选的那些句）；
     *                {@code null} = 全部句都算（调用方的 {@code targetLines} 从不给 null，
     *                这里只是兜底），空表 = 一句都不算
     */
    static String mainRhymeBody(List<PlanLine> lines, List<Integer> targets) {
        Set<Integer> only = targets == null ? null : new HashSet<>(targets);
        Map<String, Integer> tally = new LinkedHashMap<>();
        for (PlanLine line : lines) {
            if (only != null && !only.contains(line.index())) {
                continue;
            }
            if (line.rhymeBody() != null) {
                tally.merge(line.rhymeBody(), 1, Integer::sum);
            }
        }
        if (tally.isEmpty()) {
            return null;   // 一句都拆不出韵 → 没有占多数的韵
        }
        int max = tally.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<String> top = tally.entrySet().stream()
                .filter(e -> e.getValue() == max)
                .map(Map.Entry::getKey)
                .toList();
        return top.size() == 1 ? top.getFirst() : null;   // 平票 = 没有占多数的韵
    }

    private static Optional<String> defaultBodyOf(TemplateLine line) {
        if (line.tailOriginal() == null || line.englishTail()) {
            return Optional.empty();
        }
        RhymeService.Anchor anchor = RhymeService.anchorOf(line.tailOriginal(), "yun18");
        if (!anchor.recognized() || anchor.keys().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(anchor.keys().get(0)); // 多音取最常用读音的韵身
    }

    private static String yun18NameOf(String body) {
        return PinyinSyllable.table().stream()
                .filter(r -> r.body().equals(body)).findFirst()
                .map(PinyinSyllable.Rhyme::yun18).orElse(body);
    }

    // ==================== 生成（任务体） ====================

    /** 任务参数（§6.5，落 params_json；主题是用户自己写的、可以存，歌词正文不存）。 */
    public record GenerateParams(Long fillId, String mode, String theme,
                                 List<PlanLineInput> plan, List<Integer> onlyLines,
                                 Integer batchSize, Double temperature,
                                 Boolean useFewshot, Boolean useOnline) {
    }

    /** 任务结果（进 result_json 与说明行；不含任何歌词正文）。 */
    public record GenerateStats(int totalLines, int batches, int retries, int invalidLines) {
    }

    public GenerateStats generate(GenerateParams params,
                                  io.github.Nyameph.nyaentworks.task.handler.AsyncTaskContext context) {
        requireAllowed(Boolean.TRUE.equals(params.useOnline()));
        SongLyricFill row = requireFill(params.fillId());
        List<TemplateLine> lines = readLines(row);
        Map<Integer, String> bodies = new LinkedHashMap<>();
        if (params.plan() != null) {
            for (PlanLineInput p : params.plan()) {
                bodies.put(p.index(), StringUtils.trimToNull(p.rhymeBody()));
            }
        } else {
            for (TemplateLine line : lines) {
                bodies.put(line.index(), defaultBodyOf(line).orElse(null));
            }
        }
        List<Integer> targets = targetLines(lines.size(), params.mode(), row, params.onlyLines());
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("没有要生成的句子（续写模式下没有空句？）");
        }
        var cfg = properties.getFillAi();
        int batchSize = clampBatchSize(params.batchSize() != null && params.batchSize() > 0
                ? params.batchSize() : cfg.getBatchSize());
        double temperature = params.temperature() != null ? params.temperature()
                : cfg.getTemperature();
        boolean useOnline = Boolean.TRUE.equals(params.useOnline());
        // 在线模式恒定关闭 few-shot：不看参数、不看配置，硬编码（§9.3）
        boolean useFewshot = !useOnline && !Boolean.FALSE.equals(params.useFewshot());

        AiChatClient client = client(useOnline);
        String system = useOnline ? FillAiPrompts.systemOnline() : FillAiPrompts.systemLocal();

        // 已定稿文本（批与批之间的上文）：不在目标里的句用已填内容，生成过的句用新词
        Map<Integer, String> finalized = initialFinalized(row, lines, targets);

        Map<Integer, String> results = new LinkedHashMap<>();
        int retries = 0;
        int invalid = 0;
        List<List<Integer>> batches = partition(targets, batchSize);
        int done = 0;
        for (int bi = 0; bi < batches.size(); bi++) {
            List<Integer> batch = batches.get(bi);
            String user = buildUserPrompt(params.theme(), batch, lines, bodies, finalized,
                    useFewshot);
            double temp = temperature;
            List<String> answers = null;
            String parseError = "";
            // 第一轮 + 最多 2 轮重试：解析失败 / 句数不符 → 整批重发并附原因（温度降 0.2）
            for (int attempt = 0; attempt <= 2; attempt++) {
                if (attempt == 0) {
                    answers = tryChat(client, system, user, temp);
                } else {
                    parseError = answers == null ? "输出解析不出 JSON 数组"
                            : ("句数应为 " + batch.size() + "，实际收到 " + answers.size());
                    answers = tryChat(client, system, user + "\n\n上一次输出不合格：" + parseError
                            + "。重新输出，只给一个 JSON 数组，元素个数必须是 " + batch.size() + "。", temp);
                    retries++;
                    temp = Math.max(0, temp - 0.2);
                    context.log("第 " + (bi + 1) + " 批整批重试：" + parseError);
                }
                if (answers != null && answers.size() == batch.size()) {
                    break;
                }
                if (attempt == 2) {
                    answers = null; // 2 轮重试后仍解析不出：本批放弃（结果置空，交人处理）
                }
            }
            if (answers == null) {
                for (int index : batch) {
                    results.put(index, "");
                    invalid++;
                }
                done += batch.size();
                context.progress(done, targets.size(), "第 " + (bi + 1) + " 批解析失败，已跳过");
                continue;
            }
            // 逐句校验；不合格的句带原因重发（其余句作为已定稿上下文），最多 2 轮
            for (int round = 0; round < 2; round++) {
                List<Integer> bad = new ArrayList<>();
                for (int b = 0; b < batch.size(); b++) {
                    int index = batch.get(b);
                    LineCheck check = validateLine(answers.get(b),
                            lines.get(index).lrcCount(), bodies.get(index),
                            structuralSlack(lines.get(index).lrcText()));
                    if (!check.ok()) {
                        bad.add(b);
                    }
                }
                if (bad.isEmpty()) {
                    break;
                }
                retries++;
                temp = Math.max(0, temp - 0.2);
                context.log("第 " + (bi + 1) + " 批逐句重试 " + (round + 1) + " 轮：不合格 "
                        + bad.size() + " 句");
                List<String> fixed = tryChat(client, system, buildRetryPrompt(params.theme(),
                        batch, bad, answers, lines, bodies), temp);
                if (fixed != null && fixed.size() == bad.size()) {
                    for (int i = 0; i < bad.size(); i++) {
                        answers.set(bad.get(i), fixed.get(i));
                    }
                }
            }
            // 收尾：最终 answers 逐句定级（合格 / 警告 / 不合格都进结果，交人过目）
            for (int b = 0; b < batch.size(); b++) {
                int index = batch.get(b);
                String text = answers.get(b);
                results.put(index, text);
                finalized.put(index, text);
                LineCheck check = validateLine(text, lines.get(index).lrcCount(),
                        bodies.get(index), structuralSlack(lines.get(index).lrcText()));
                if (!check.ok()) {
                    invalid++;
                }
            }
            done += batch.size();
            context.progress(done, targets.size(),
                    "已生成 " + done + " / " + targets.size() + " 句");
        }

        Preview preview = buildPreview(params.fillId(), targets, lines, bodies, results, useOnline);
        previews.put(params.fillId(), preview);
        return new GenerateStats(targets.size(), batches.size(), retries, invalid);
    }

    /** 调一次模型；拿不到文本给 null（调用方按解析失败处理）。 */
    private List<String> tryChat(AiChatClient client, String system, String user, double temp) {
        Optional<String> reply = client.chat(system, user, temp);
        return reply.map(FillAiService::parseLines).orElse(null);
    }

    // ==================== 目标句 / 上下文 ====================

    /**
     * 要生成的句下标。full = 全部；continue = 保留已填句，从第一个空句起的所有空句
     * （中间夹的已填句当上文）；refill = onlyLines。
     */
    static List<Integer> targetLines(int total, String mode, SongLyricFill row,
                                     List<Integer> onlyLines) {
        if (MODE_REFILL.equals(mode)) {
            return onlyLines == null ? List.of()
                    : onlyLines.stream().filter(i -> i >= 0 && i < total).sorted().toList();
        }
        List<Integer> all = new ArrayList<>(total);
        if (MODE_CONTINUE.equals(mode)) {
            List<LyricTemplate.FillLine> lines = LyricFillStore.readLines(row.getLinesJson());
            List<List<String>> filled = LyricFillStore.readFilled(row.getFilledJson());
            int firstEmpty = -1;
            for (int i = 0; i < total; i++) {
                List<String> values = i < filled.size() ? filled.get(i) : List.of();
                boolean filledRow = i < lines.size() && LyricFillAligner.isLineFilled(lines.get(i), values);
                if (firstEmpty < 0 && !filledRow) {
                    firstEmpty = i;
                }
                if (firstEmpty >= 0 && !filledRow) {
                    all.add(i);
                }
            }
            return all;
        }
        for (int i = 0; i < total; i++) {
            all.add(i);
        }
        return all;
    }

    /**
     * 一句的已填新词（AI 上下文用）：每个声部组取第一个非空值（合唱副本联动后同组同值），
     * 按组拼接、剔 dash 占位。整句没填满返回空串（不猜半句）。
     */
    static String filledTextOf(SongLyricFill row, int index) {
        List<LyricTemplate.FillLine> all = LyricFillStore.readLines(row.getLinesJson());
        if (index >= all.size()) {
            return "";
        }
        LyricTemplate.FillLine line = all.get(index);
        List<List<String>> filled = LyricFillStore.readFilled(row.getFilledJson());
        List<String> values = index < filled.size() ? filled.get(index) : List.of();
        if (!LyricFillAligner.isLineFilled(line, values)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (List<Integer> group : LyricFillAligner.groupIndexes(line).values()) {
            for (int k : group) {
                LyricTemplate.FillSlot slot = line.slots().get(k);
                if (!slot.slotType().fillable() || k >= values.size()
                        || StringUtils.isBlank(values.get(k))) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(values.get(k).replace("-", ""));
                break;
            }
        }
        return sb.toString();
    }

    // ==================== 提示词拼装（§6.2 口径） ====================

    String buildUserPrompt(String theme, List<Integer> batch, List<TemplateLine> lines,
                           Map<Integer, String> bodies, Map<Integer, String> finalized,
                           boolean useFewshot) {
        List<String> sentenceLines = new ArrayList<>(batch.size());
        int n = 1;
        for (int index : batch) {
            TemplateLine line = lines.get(index);
            // 句子表给模型的「原句 + 字数」也是导出 lrc 的口径（多音轨合并成一行后那一份）
            sentenceLines.add(FillAiPrompts.sentenceLine(n++, line.lrcCount(),
                    bodies.get(index), line.lrcText(), line.englishWords()));
        }
        // 续写上文：本批之前已定稿的句（仅续写 / 重填 / 第 2+ 批给）
        List<String> context = new ArrayList<>();
        if (!batch.isEmpty()) {
            for (Map.Entry<Integer, String> e : finalized.entrySet()) {
                if (e.getKey() < batch.get(0) && StringUtils.isNotBlank(e.getValue())) {
                    context.add(e.getValue());
                }
            }
        }
        StringBuilder sb = new StringBuilder(
                FillAiPrompts.userSection(theme, sentenceLines, context));
        if (useFewshot) {
            String block = fewshotBlock(batch, bodies);
            if (!block.isEmpty()) {
                sb.append('\n').append(block);
            }
        }
        return sb.toString();
    }

    /**
     * 逐句重试的 user 段（§6.3 第 3 条）：只把不合格的句连同失败原因重发，<b>本批里已通过校验
     * 的句 + 主题/大纲作为「已定稿上下文」一并注入</b>。
     *
     * <p>为什么要给上下文：重试的还是一整句新词，续写 / 重填模式下它要跟前后句接得上。
     * 只发不合格句时模型看不到邻居，改出来的词风格与语义跟上下文对不上（原先就是这么发的）。
     * 已定稿的句明确标注「不要改、不要重写」，免得模型顺手把它们也写一遍。
     *
     * <p>输出契约不变：只回不合格那 N 句的 JSON 数组，元素个数与顺序跟下面列的 N 句一致。
     *
     * @param bad 不合格句在 {@code batch} 里的<b>位置</b>（下标，不是句号）
     */
    static String buildRetryPrompt(String theme, List<Integer> batch, List<Integer> bad,
                                   List<String> answers, List<TemplateLine> lines,
                                   Map<Integer, String> bodies) {
        StringBuilder sb = new StringBuilder();
        sb.append(FillAiPrompts.themeLine(theme)).append("\n\n");
        List<String> done = new ArrayList<>();
        for (int b = 0; b < batch.size(); b++) {
            // 句号用批内序号（与首次请求的句子表一致），模型才对得上「第几句」
            if (!bad.contains(b) && StringUtils.isNotBlank(answers.get(b))) {
                done.add("第 " + (b + 1) + " 句：" + answers.get(b));
            }
        }
        if (!done.isEmpty()) {
            sb.append("本批已定稿的句（不要改、不要重写，只作为上下文参考）：\n");
            done.forEach(t -> sb.append(t).append('\n'));
            sb.append('\n');
        }
        sb.append("以下 ").append(bad.size())
                .append(" 句不合格，请重写。只输出一个 JSON 数组，含这 ")
                .append(bad.size()).append(" 句、顺序与下面一致：\n");
        int n = 1;
        for (int b : bad) {
            int index = batch.get(b);
            LineCheck why = validateLine(answers.get(b),
                    lines.get(index).lrcCount(), bodies.get(index),
                    structuralSlack(lines.get(index).lrcText()));
            sb.append(n++).append('(')
                    .append(lines.get(index).lrcCount()).append("字, ")
                    .append(FillAiPrompts.rhymeLabelOf(bodies.get(index))).append(")：")
                    .append(lines.get(index).lrcText())
                    .append("\n  上次输出：「").append(answers.get(b))
                    .append("」 不合格原因：").append(why.message()).append('\n');
        }
        return sb.toString();
    }

    /** few-shot 取材：例句取同韵语料（kind=SONG，加权随机），改写示例取配对。 */
    private String fewshotBlock(List<Integer> batch, Map<Integer, String> bodies) {
        var cfg = properties.getFillAi();
        Set<String> bodiesInBatch = new HashSet<>();
        for (int index : batch) {
            if (bodies.get(index) != null) {
                bodiesInBatch.add(bodies.get(index));
            }
        }
        StringBuilder sb = new StringBuilder();
        if (!bodiesInBatch.isEmpty()) {
            List<String> yun18Names = bodiesInBatch.stream().map(FillAiService::yun18NameOf).toList();
            var fewshot = corpusService.searchLines(yun18Names, List.of(), CorpusService.KIND_SONG,
                    null, cfg.getFewshotLines(), true);
            if (!fewshot.isEmpty()) {
                sb.append("押韵参考（同韵部的既有歌词，只学语感，禁止整句照抄）：\n");
                fewshot.forEach(l -> sb.append("- ").append(l.text()).append('\n'));
            }
        }
        List<LyricCorpusPair> pairs = corpusService.randomPairs(cfg.getFewshotPairs());
        if (!pairs.isEmpty()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("改写示例（原词 → 新词，学改写手法，禁止照抄）：\n");
            pairs.forEach(p -> sb.append("- ").append(p.getOriginalText()).append(" → ")
                    .append(p.getFilledText()).append('\n'));
        }
        return sb.toString();
    }

    // ==================== 解析与逐句校验（§6.3，纯函数，有单测） ====================

    /**
     * 模型输出 → 句子数组：剥 {@code ```json} 围栏 → 从第一个 {@code [} 到最后一个 {@code ]}
     * 截取 → 解析；也接受 {@code {"lines":[…]}} 包一层对象的返回。解析不出返回 {@code null}。
     */
    static List<String> parseLines(String modelText) {
        if (modelText == null || modelText.isBlank()) {
            return null;
        }
        String s = modelText.trim()
                .replaceAll("(?s)^```(?:json)?\\s*", "")
                .replaceAll("(?s)\\s*```$", "")
                .trim();
        // {"lines":[…]} 包一层对象的返回（有模型这么干）
        int objStart = s.indexOf('{');
        int arrStart = s.indexOf('[');
        if (objStart >= 0 && s.contains("\"lines\"") && (arrStart < 0 || objStart < arrStart)) {
            try {
                var root = com.alibaba.fastjson2.JSONObject.parseObject(s);
                var arr = root == null ? null : root.getJSONArray("lines");
                if (arr != null) {
                    List<String> out = new ArrayList<>(arr.size());
                    for (Object o : arr) {
                        out.add(String.valueOf(o));
                    }
                    return out;
                }
            } catch (Exception e) {
                return null;
            }
            return null;
        }
        int start = s.indexOf('[');
        int end = s.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            var arr = com.alibaba.fastjson2.JSON.parseArray(s.substring(start, end + 1));
            if (arr == null) {
                return null;
            }
            List<String> out = new ArrayList<>(arr.size());
            for (Object o : arr) {
                out.add(String.valueOf(o));
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 逐句校验结果。{@code ok=false} = 不合格（重写）；{@code ok=true && warning} = 接受但标 ⚠。
     */
    public record LineCheck(boolean ok, boolean warning, String message) {
    }

    private static final LineCheck PASS = new LineCheck(true, false, null);

    /**
     * 该句 {@code lrcText} 里<b>结构括号字符</b>的个数（{@code （）} / {@code ()}）。
     *
     * <p>括号是记谱用的结构符号、<b>不是可填格</b>：导出 lrc 用 {@code A（B）} 表示双声部，
     * 前端 {@code batchUnits} 直接把它们丢掉。所以模型对这句的真实可落字数是
     * {@code lrcCount - structuralSlack} —— 逐句校验的缺口容忍要把这几个字符补回来
     * （见 {@link #validateLine}），{@code lrcText} / {@code lrcCount} 本身<b>不改</b>
     * （它们必须与导出 lrc 一致）。
     */
    static int structuralSlack(String lrcText) {
        int n = 0;
        for (int i = 0; i < (lrcText == null ? 0 : lrcText.length()); i++) {
            char c = lrcText.charAt(i);
            if (c == '（' || c == '）' || c == '(' || c == ')') {
                n++;
            }
        }
        return n;
    }

    /**
     * 字数 ✓/⚠/✗（与 {@link #validateLine} 的缺口容忍同一口径，预览的 {@code lengthMark} 用）。
     * 缺口容忍 = {@code 1 + structuralSlack}，<b>超限那条不放宽</b>。
     */
    static String lengthMark(int units, int needCount, int structuralSlack) {
        if (units == needCount) {
            return "ok";
        }
        return units < needCount && units >= needCount - 1 - structuralSlack ? "warn" : "fail";
    }

    /**
     * 字数：超限 / 少得比容忍度还多 → 不合格；少 {@code 1 + structuralSlack} 字以内接受但打
     * warning（与人工填词软限制同口径：1 = 通用容忍，{@code structuralSlack} = 该句 lrcText 里
     * 不占格的括号字符数）。
     * 句尾韵（方案非「不限」且句尾不是英文）：倒着第一个含汉字的单元，其全部读音的韵身
     * 与方案不相交 → 不合格并附「要押 X，你最后写的是 Y」。
     *
     * @param structuralSlack 该句 {@link #structuralSlack(String)} 的值；非括号句传 0
     */
    static LineCheck validateLine(String text, int needCount, String rhymeBody, int structuralSlack) {
        List<String> units = LyricFillAligner.tokenize(text);
        if (units.isEmpty()) {
            return new LineCheck(false, false, "输出为空");
        }
        boolean lengthWarn = false;
        int shortBy = needCount - units.size();
        if ("fail".equals(lengthMark(units.size(), needCount, structuralSlack))) {
            return shortBy < 0
                    ? new LineCheck(false, false,
                    "字数超限：应为 " + needCount + "，实际 " + units.size())
                    : new LineCheck(false, false,
                    "字数不足：应为 " + needCount + "，实际 " + units.size());
        }
        lengthWarn = shortBy > 0;   // 缺口在容忍度内：接受但 ⚠
        if (rhymeBody == null) {
            return lengthWarn ? new LineCheck(true, true, "少 " + shortBy + " 字") : PASS;
        }
        // 倒着第一个含汉字的单元；英文 / 找不到汉字 → warning 不拦
        Integer tailCp = null;
        for (int k = units.size() - 1; k >= 0; k--) {
            String unit = units.get(k);
            int cp = unit.codePoints().filter(PinyinUtil::isHanzi).findFirst().orElse(-1);
            if (cp >= 0) {
                tailCp = cp;
                break;
            }
            if (LyricFillAligner.hasAsciiLetter(unit)) {
                return new LineCheck(true, true, "句尾是英文，没法判韵");
            }
        }
        if (tailCp == null) {
            return new LineCheck(true, true, "句尾没有汉字，没法判韵");
        }
        Set<String> bodiesOfTail = new HashSet<>();
        for (PinyinSyllable reading : PinyinUtil.readings(tailCp)) {
            bodiesOfTail.add(reading.rhymeBody());
        }
        if (bodiesOfTail.contains(rhymeBody)) {
            return lengthWarn ? new LineCheck(true, true, "少 " + shortBy + " 字") : PASS;
        }
        String tailChar = new String(Character.toChars(tailCp));
        String tailBodies = bodiesOfTail.isEmpty() ? "拆不出韵"
                : String.join("/", bodiesOfTail.stream().map(FillAiService::yun18NameOf).toList());
        return new LineCheck(false, false, "要押" + yun18NameOf(rhymeBody)
                + "，你最后写的是「" + tailChar + "」(" + tailBodies + ")");
    }

    // ==================== 预览（生成结果只回前端，不落任务表） ====================

    /**
     * 预览里的一行：字数 ✓/⚠/✗ 与押韵 ✓/⚠/不限/✗ 都在这里，前端照标。
     *
     * <p>{@code lrcCount} 是<b>导出 lrc 口径</b>的字数（与方案表的 {@link PlanLine#lrcCount()}
     * 同一个值、同一份判定），<b>不是</b>可填槽位数 —— 所以这里也叫 {@code lrcCount}，别再跟
     * 方案表的 {@link PlanLine#needCount()}（槽位数）混成同名不同义。
     */
    public record PreviewLine(int index, String text, int lrcCount, int unitCount,
                              String lengthMark, String rhymeBody, String rhymeMark,
                              String message) {
    }

    public record Preview(Long fillId, int total, List<PreviewLine> lines, boolean useOnline) {
    }

    /** 最近一次生成的结果；没有时 {@code null}（前端提示先跑一次生成）。 */
    public Preview preview(Long fillId) {
        return previews.get(fillId);
    }

    private Preview buildPreview(Long fillId, List<Integer> targets, List<TemplateLine> lines,
                                 Map<Integer, String> bodies, Map<Integer, String> results,
                                 boolean useOnline) {
        List<PreviewLine> out = new ArrayList<>(targets.size());
        for (int index : targets) {
            String text = results.getOrDefault(index, "");
            int need = lines.get(index).lrcCount();   // 字数与判定都用 lrc 口径（同方案表）
            int slack = structuralSlack(lines.get(index).lrcText());   // 括号句：不占格的括号
            int units = LyricFillAligner.countUnits(text);
            // 标记与 validateLine 同一口径（含缺口容忍），否则界面上 ⚠ 而实际通过
            String lengthMark = lengthMark(units, need, slack);
            String body = bodies.get(index);
            LineCheck check = validateLine(text, need, body, slack);
            String rhymeMark;
            if (body == null) {
                rhymeMark = "off";
            } else if (!check.ok()) {
                rhymeMark = "fail";
            } else if (check.warning()) {
                rhymeMark = "warn";
            } else {
                rhymeMark = "ok";
            }
            out.add(new PreviewLine(index, text, need, units, lengthMark, body, rhymeMark,
                    check.message()));
        }
        return new Preview(fillId, out.size(), out, useOnline);
    }

    // ==================== 在线 prompt 预览（提交前确认框用，§6.1） ====================

    /**
     * 在线确认框的 prompt 预览（§6.1）：<b>覆盖本次会发出的全部句子</b>，不是只给首批 ——
     * 外发内容里含模板原词，原词本身可能含成人内容（§9.3 第 2 条），这正是确认框存在的理由；
     * 只给首批时后续批的原词用户根本看不见。
     *
     * <p>逐批拼出各批真实的 user 文本（与 {@link #generate} 同一口径、同一分批），批之间夹一条
     * 分界标记——用户要看得出实际发送形态是「分 K 批、每批一次请求」，而不是一次发一大段。
     */
    private PromptPreview buildPromptPreview(SongLyricFill row, List<TemplateLine> lines,
                                             PlanRequest request, List<PlanLine> planLines) {
        Map<Integer, String> bodies = new LinkedHashMap<>();
        for (PlanLine l : planLines) {
            bodies.put(l.index(), l.rhymeBody());
        }
        List<Integer> targets = targetLines(lines.size(),
                StringUtils.defaultIfBlank(request.mode(), MODE_FULL), row, request.onlyLines());
        // 分批口径必须与 generate 一致：前端带了每批句数就用它，没带回落配置（同样钳区间）
        int batchSize = clampBatchSize(request.batchSize() != null && request.batchSize() > 0
                ? request.batchSize() : properties.getFillAi().getBatchSize());
        List<List<Integer>> batches = partition(targets, batchSize);
        Map<Integer, String> finalized = initialFinalized(row, lines, targets);
        StringBuilder user = new StringBuilder();
        if (batches.size() > 1) {
            user.append("（本次 ").append(targets.size()).append(" 句，每批 ").append(batchSize)
                    .append(" 句、分 ").append(batches.size())
                    .append(" 批发发送；下面「—— 第 N 批 ——」只是分批位置标记，不属于发出的内容）\n\n");
        }
        for (int bi = 0; bi < batches.size(); bi++) {
            if (bi > 0) {
                user.append("\n———— 第 ").append(bi + 1).append(" 批 ————\n");
            }
            // few-shot 恒不注入：在线模式硬编码关闭（§9.2 / §9.3 第 3 条），
            // 这里传字面量 false —— 不读参数、不读配置，这一条不许变成条件判断
            user.append(buildUserPrompt(request.theme(), batches.get(bi), lines, bodies, finalized,
                    false));
        }
        if (batches.isEmpty()) {
            // 没有目标句（如重填模式没勾句）：给一张空表，形状与真发的请求一致
            user.append(buildUserPrompt(request.theme(), List.of(), lines, bodies, finalized, false));
        }
        return new PromptPreview(FillAiPrompts.systemOnline(), user.toString());
    }

    /**
     * 初始已定稿文本：不在目标里的句用已填内容（批与批之间的上文由它起步，之后逐批补生成结果）。
     * {@link #generate} 与在线预览共用，免得预览里的「续写上文」与真发出去的不一样。
     */
    private Map<Integer, String> initialFinalized(SongLyricFill row, List<TemplateLine> lines,
                                                  List<Integer> targets) {
        Map<Integer, String> finalized = new LinkedHashMap<>();
        for (TemplateLine line : lines) {
            if (!targets.contains(line.index())) {
                finalized.put(line.index(), filledTextOf(row, line.index()));
            }
        }
        return finalized;
    }

    // ==================== 杂项 ====================

    private AiChatClient client(boolean useOnline) {
        var cfg = properties.getFillAi();
        Duration timeout = Duration.ofSeconds(Math.max(30, cfg.getTimeoutSeconds()));
        if (useOnline) {
            return new AiChatClient(onlineAi.getBaseUrl(), onlineAi.getApiKey(),
                    onlineAi.getModel(), timeout);
        }
        return new AiChatClient(localAi.getBaseUrl(), localAi.getApiKey(), localAi.getModel(), timeout);
    }

    private static List<List<Integer>> partition(List<Integer> all, int size) {
        List<List<Integer>> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += size) {
            out.add(all.subList(i, Math.min(all.size(), i + size)));
        }
        return out;
    }

    /**
     * 每批句数的硬区间：下界 1（{@link #partition} 是 {@code i += size}，{@code size == 0} 会原地
     * 转不出去 —— 请求侧有 {@code > 0} 兜底，配置侧只能手改 yaml 触发，这里兜住），
     * 上界 64（配置写大了会把整篇塞进一次请求：既超上下文，又让进度与重试粒度失控）。
     */
    static final int MAX_BATCH_SIZE = 64;

    static int clampBatchSize(int size) {
        return Math.min(MAX_BATCH_SIZE, Math.max(1, size));
    }

    /**
     * 一行的派生信息（方案与生成都要用）：句原文 + 尾槽原词 + 是否英文尾 + 英文词数 +
     * <b>lrc 口径的原句 / 当前填词 / 字数</b>（{@code lrcText} / {@code lrcFilled} /
     * {@code lrcCount}）。
     */
    public record TemplateLine(int index, LyricTemplate.FillLine line,
                               String tailOriginal, boolean englishTail, int englishWords,
                               String lrcText, String lrcFilled, int lrcCount) {
    }

    private List<TemplateLine> readLines(SongLyricFill row) {
        List<LyricTemplate.FillLine> lines = LyricFillStore.readLines(row.getLinesJson());
        // 原句 / 当前填词 / 字数按导出 lrc 的口径（一句里多音轨合并成一行）：一次算两份，
        // 提示词与逐句校验用原句那份，方案表的「填词」列用填词那份（同一条拼装）
        LyricFillService.LrcTexts texts = lyricFillService.lrcTexts(row);
        List<TemplateLine> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            LyricTemplate.FillLine line = lines.get(i);
            String tailOriginal = null;
            for (int k = line.slots().size() - 1; k >= 0; k--) {
                LyricTemplate.FillSlot slot = line.slots().get(k);
                if (slot.slotType().fillable()) {
                    tailOriginal = StringUtils.defaultString(slot.original());
                    break;
                }
            }
            String lastUnit = LyricFillAligner.tokenize(StringUtils.defaultString(tailOriginal))
                    .stream().reduce((a, b) -> b).orElse("");
            boolean englishTail = LyricFillAligner.hasAsciiLetter(lastUnit);
            int englishWords = (int) LyricFillAligner.tokenize(line.originalText()).stream()
                    .filter(LyricFillAligner::hasAsciiLetter).count();
            String lrcText = i < texts.original().size() ? texts.original().get(i)
                    : StringUtils.defaultString(line.originalText());
            // 没填过的句是空串（缺位也补空串，别给 null —— 前端当输入框默认值用）
            String lrcFilled = i < texts.filled().size()
                    ? StringUtils.defaultString(texts.filled().get(i)) : "";
            out.add(new TemplateLine(i, line, tailOriginal, englishTail, englishWords,
                    lrcText, lrcFilled, LyricFillAligner.tokenize(lrcText).size()));
        }
        return out;
    }

    private SongLyricFill requireFill(Long fillId) {
        if (fillId == null) {
            throw new IllegalArgumentException("填词项目 id 不能为空");
        }
        SongLyricFill row = fillMapper.selectById(fillId);
        if (row == null) {
            throw new IllegalArgumentException("填词项目不存在（id=" + fillId + "）");
        }
        return row;
    }
}
