package io.github.Nyameph.nyaentworks.song.fill.rhyme;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import io.github.Nyameph.nyaentworks.common.ai.AiAvailability;
import io.github.Nyameph.nyaentworks.common.ai.AiChatClient;
import io.github.Nyameph.nyaentworks.common.config.LocalAiProperties;
import io.github.Nyameph.nyaentworks.song.config.SongProperties;
import io.github.Nyameph.nyaentworks.song.entity.RhymeEntry;
import io.github.Nyameph.nyaentworks.song.mapper.RhymeEntryMapper;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskContext;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 词性补全（本机 Ollama）：把 {@code word_class IS NULL} 的词条逐批交给模型判词性（§4.6 的固定枚举）。
 *
 * <p><b>恒用本机 Ollama，硬编码、不看开关</b>：{@link #client()} 直接拿
 * {@code nya-entworks.common.local-ai.*}（与 AI 填词、漫画相似度同一份端点配置）
 * 拼 {@link AiChatClient}，<b>不看</b>
 * {@code fill-ai.use-online}、也<b>不看</b> {@link io.github.Nyameph.nyaentworks.common.config.OnlineAiProperties}
 * —— 与 {@code FillAiService} 那两道在线闸无关。这是<b>故意的</b>（硬编码，不是漏了）：
 * 要判的词来自语料库句尾词与用户自己的押韵词表（含大量成人词，§9.4 的数据红线），
 * 一旦走在线端点就等于把这些词发给第三方。要改这里，先回答「这些词能不能外发」。
 *
 * <p><b>数据红线（§9.4）</b>：任务明细（{@code context.log} / {@code message}）、
 * {@code result_json} 与说明行里<b>只出现计数</b>，绝不出现词条原文 —— {@link PosResult}
 * 五个字段全是数字。词当然要进 prompt（那是发给本机模型的请求体，{@code AiChatClient}
 * 也不打 prompt 与回复正文）。
 *
 * <p><b>可重复执行</b>：模型漏返回的词保持 NULL 不动，下次再跑还会被扫到；单批失败只记数、
 * 接着跑下一批，不整任务挂掉。游标（{@code id >}）分页，不会因为「有些行判了有些行没判」
 * 而在原处打转。
 */
@Service
@RequiredArgsConstructor
public class RhymePosService {

    /** 一次从库里取多少行待判（游标分页，别把 1.3 万行全载进内存）。 */
    static final int PAGE = 500;

    /** 一次请求送模型多少个词。 */
    static final int BATCH = 50;

    /** 判不出 / 枚举外的落这里（{@link RhymeService#WORD_CLASSES} 的最后一项）。 */
    private static final String OTHER_CLASS = "其他";

    private final RhymeEntryMapper mapper;
    private final SongProperties properties;
    /** 本机端点（{@code common.local-ai.*}）。超时仍取 {@code song.fill-ai.timeout-seconds} */
    private final LocalAiProperties localAi;
    /** 「端点配全了没有」的唯一口径。本功能没有自己的开关，所以端点就是它唯一的闸 */
    private final AiAvailability aiAvailability;

    /** 任务结果（进 result_json 与任务说明行）：<b>只有计数，没有词</b>。 */
    public record PosResult(int total, int judged, int updated, int failed, int batches) {
    }

    /**
     * 跑一轮补全：先归一旧词性，再游标分页逐批判。
     * 判不出的行留着 NULL，下次重跑还会被扫到（可重复执行）。
     *
     * <p>本功能<b>没有自己的开关</b>，端点就是它唯一的闸：没配端点或模型时当场拒绝，
     * 而不是把上万条词一批批发出去换回一堆 400。页面上的「补全词性」按钮同一口径地隐藏
     * （见 {@code /api/ai/status}），这里再拦一道是因为接口能直接被调进来。
     */
    public PosResult tag(AsyncTaskContext context) {
        String deny = aiAvailability.localDeny();
        if (deny != null) {
            throw new IllegalStateException("补全词性要用本机 AI 端点，现在用不了 —— " + deny);
        }
        int fixed = normalizeLegacy();
        context.message("旧词性已归一 " + fixed + " 行（名词-人 / 名词-物 → 名词，动作 → 动词）");
        Long nullCount = mapper.selectCount(Wrappers.<RhymeEntry>lambdaQuery()
                .isNull(RhymeEntry::getWordClass));
        int total = nullCount == null ? 0 : nullCount.intValue();
        AiChatClient client = client();
        int done = 0;
        int judged = 0;
        int updated = 0;
        int failed = 0;
        int batches = 0;
        long cursor = 0L;
        while (true) {
            List<RhymeEntry> rows = mapper.selectList(Wrappers.<RhymeEntry>lambdaQuery()
                    .select(RhymeEntry::getId, RhymeEntry::getText)
                    .isNull(RhymeEntry::getWordClass)
                    .gt(RhymeEntry::getId, cursor)
                    .orderByAsc(RhymeEntry::getId)
                    .last("LIMIT " + PAGE));
            if (rows.isEmpty()) {
                break;
            }
            cursor = rows.getLast().getId();
            for (int from = 0; from < rows.size(); from += BATCH) {
                List<RhymeEntry> sub = rows.subList(from, Math.min(rows.size(), from + BATCH));
                batches++;
                List<String> words = sub.stream().map(RhymeEntry::getText).toList();
                Map<String, String> pos = new LinkedHashMap<>();
                try {
                    Optional<String> reply = client.chat(systemPrompt(), buildUserPrompt(words), 0.0);
                    if (reply.isPresent()) {
                        pos = parsePosResponse(reply.get(), words);
                    }
                } catch (Exception e) {
                    // 只记异常类型：异常 message 里可能带着模型返回的正文（红线）
                    context.log("第 " + batches + " 批异常（" + e.getClass().getSimpleName()
                            + "），已跳过 " + words.size() + " 词");
                }
                if (pos.isEmpty()) {
                    failed++;
                    context.log("第 " + batches + " 批解析失败，已跳过 " + words.size() + " 词");
                } else {
                    for (RhymeEntry row : sub) {
                        String wordClass = pos.get(row.getText());
                        if (wordClass == null) {
                            continue; // 模型漏返回的词：不动，下次重跑还会被扫到
                        }
                        judged++;
                        updated += mapper.update(null, Wrappers.<RhymeEntry>lambdaUpdate()
                                .set(RhymeEntry::getWordClass, wordClass)
                                .eq(RhymeEntry::getId, row.getId()));
                    }
                }
            }
            done += rows.size();
            // done 是「扫描过」的行数；模型漏返回的词仍留 NULL（下次重跑会再扫到），
            // 所以这里不能写成「已判 N」——judged 才是真的写进库的行数，两个数会差。
            context.progress(done, total, "已扫描 " + done + " / " + total
                    + " 词（判定成功 " + judged + " 词）");
        }
        return new PosResult(total, judged, updated, failed, batches);
    }

    /**
     * 旧词性归一：用户 XLSX 词表的列头「名词-人 / 名词-物」→ 名词、「动作」→ 动词
     * （{@link RhymeService#WORD_CLASSES} 的注释写的就是这一条）。
     *
     * <p>放在任务开头、可重复执行（已归一的再跑影响 0 行）。走 {@code Wrappers} 只改
     * {@code word_class} 一列，不手写 SQL、不碰别的列。
     *
     * @return 影响的行数
     */
    int normalizeLegacy() {
        int n = mapper.update(null, Wrappers.<RhymeEntry>lambdaUpdate()
                .set(RhymeEntry::getWordClass, "名词")
                .in(RhymeEntry::getWordClass, "名词-人", "名词-物"));
        n += mapper.update(null, Wrappers.<RhymeEntry>lambdaUpdate()
                .set(RhymeEntry::getWordClass, "动词")
                .eq(RhymeEntry::getWordClass, "动作"));
        return n;
    }

    /** 恒本机 Ollama（硬编码，见类注释的红线）：不复用 {@code FillAiService} 的在线分支。 */
    private AiChatClient client() {
        var cfg = properties.getFillAi();
        Duration timeout = Duration.ofSeconds(Math.max(30, cfg.getTimeoutSeconds()));
        return new AiChatClient(localAi.getBaseUrl(), localAi.getApiKey(), localAi.getModel(), timeout);
    }

    // ==================== prompt 与解析（纯函数，单测钉这里） ====================

    /** system 提示词；词性枚举从 {@link RhymeService#WORD_CLASSES} 拼，两处不许漂移。 */
    static String systemPrompt() {
        return """
                你是中文词性标注器。只按下面的规则输出，不要解释。

                1. 只输出一个 JSON 对象，不要 markdown 围栏、不要注释、不要对象之外的任何字。
                2. 键是给出的词，值是词性，词性只能是下面这几个之一："""
                + String.join("、", RhymeService.WORD_CLASSES) + """
                。
                3. 给出的每个词都要有一条，一一对应；判断不了就用「其他」。""";
    }

    /**
     * user 段：一行一个词。词来自语料 / 用户词表，<b>只进请求体</b>，不进日志与任务结果。
     */
    static String buildUserPrompt(List<String> words) {
        return "请判断下面这些词的词性（一行一个词）：\n" + String.join("\n", words);
    }

    /**
     * 模型输出 → {@code {本批词: 词性}}（纯函数，单测钉这一个）。
     *
     * <p>剥 {@code ```} 围栏 → 从第一个 {@code {} 到最后一个 {@code }} 截取 → 解析：
     * <ul>
     *   <li><b>只保留本批给出的词</b>：模型多返回的词直接丢，漏返回的词没有键（调用方跳过不动）</li>
     *   <li>值不在 {@link RhymeService#WORD_CLASSES} 里（含 null / 空 / 「名词-人」这类旧写法）
     *       一律落「其他」—— 查询侧只认枚举，落个枚举外的值等于这一列又脏回去</li>
     *   <li>解析不出（不是 JSON / 没有对象 / 空文本）返回<b>空表</b>：调用方按「这一批失败」记数</li>
     * </ul>
     */
    static Map<String, String> parsePosResponse(String modelText, List<String> batchWords) {
        Map<String, String> out = new LinkedHashMap<>();
        if (modelText == null || modelText.isBlank() || batchWords == null) {
            return out;
        }
        String s = modelText.trim()
                .replaceAll("(?s)^```(?:json)?\\s*", "")
                .replaceAll("(?s)\\s*```$", "")
                .trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return out;
        }
        JSONObject root;
        try {
            root = JSONObject.parseObject(s.substring(start, end + 1));
        } catch (Exception e) {
            return out;
        }
        if (root == null) {
            return out;
        }
        for (String word : batchWords) {
            if (root.containsKey(word)) {
                out.put(word, normalizePos(root.getString(word)));
            }
        }
        return out;
    }

    /** 词性归一：枚举内的原样返回，枚举外（含 null / 空 / 旧写法）落「其他」。 */
    static String normalizePos(String raw) {
        String value = StringUtils.trimToNull(raw);
        return value != null && RhymeService.WORD_CLASSES.contains(value) ? value : OTHER_CLASS;
    }
}
