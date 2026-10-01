package io.github.Nyameph.nyaentworks.song.fill.ai;

import org.junit.jupiter.api.Test;
import io.github.Nyameph.nyaentworks.song.fill.LyricTemplate;
import io.github.Nyameph.nyaentworks.song.fill.SlotType;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 模型输出解析与逐句校验（填词助手设计 §6.3 七类构造输入）。纯函数，可离线跑。 */
public class FillAiResponseTest {

    // ==================== 解析 ====================

    @Test
    public void parse_plainArray() {
        assertEquals(List.of("夜色温柔", "像水缓缓流淌"),
                FillAiService.parseLines("[\"夜色温柔\",\"像水缓缓流淌\"]"));
    }

    /** 剥 markdown 围栏。 */
    @Test
    public void parse_stripsCodeFence() {
        String s = "```json\n[\"新词一\",\"新词二\"]\n```";
        assertEquals(List.of("新词一", "新词二"), FillAiService.parseLines(s));
        // 模型爱在前后加话：从第一个 [ 到最后一个 ] 截出来
        String noisy = "好的，以下是结果：[\"新词一\",\"新词二\"] 希望有帮助！";
        assertEquals(List.of("新词一", "新词二"), FillAiService.parseLines(noisy));
    }

    /** {"lines":[…]} 包一层对象的返回（有模型这么干）。 */
    @Test
    public void parse_wrappedObject() {
        assertEquals(List.of("新词一", "新词二"),
                FillAiService.parseLines("{\"lines\":[\"新词一\",\"新词二\"]}"));
    }

    @Test
    public void parse_garbageReturnsNull() {
        assertNull(FillAiService.parseLines(null));
        assertNull(FillAiService.parseLines(""));
        assertNull(FillAiService.parseLines("我不会输出 JSON"));
        assertNull(FillAiService.parseLines("[\"只有开头没有结尾"));
    }

    // ==================== 字数校验（英文整词一格的契约在这里钉住） ====================

    private static final int N = 7; // 春风又绿江南岸 = 7 字

    @Test
    public void validate_lengthExactPasses() {
        assertTrue(FillAiService.validateLine("春风又绿江南岸", N, null, 0).ok());
    }

    @Test
    public void validate_tooLongFails_tooShortBy2Fails_shortBy1Warns() {
        assertFalse(FillAiService.validateLine("春风又绿江南岸边", N, null, 0).ok()); // 8 字超限
        assertFalse(FillAiService.validateLine("春风又绿江", N, null, 0).ok());       // 5 字少 2
        FillAiService.LineCheck warn = FillAiService.validateLine("春风又绿江南", N, null, 0); // 6 字少 1
        assertTrue(warn.ok());
        assertTrue(warn.warning());
    }

    /** 英文整词一格：一个英文单词算 1（tokenize 口径），不逐字母拆。 */
    @Test
    public void validate_englishWordCountsAsOne() {
        // 4 汉字 + 2 英文词 = 6 单元 = N-1 → 接受但警告
        FillAiService.LineCheck check = FillAiService.validateLine("跟我一起 move on", N, null, 0);
        assertTrue(check.ok());
        assertTrue(check.warning());
        assertTrue(FillAiService.validateLine("跟我一起 move on 走", N, null, 0).ok());
    }

    // ==================== 句尾韵校验 ====================

    /** 尾字押韵：岸(an) 押十四寒(an) → 过；心(in/en) 押 an → 不过，附可读原因。 */
    @Test
    public void validate_rhymeBodyMatch() {
        assertTrue(FillAiService.validateLine("春风又绿江南岸", N + 1, "an", 0).ok());
        FillAiService.LineCheck bad = FillAiService.validateLine("春风又绿江南心", N + 1, "an", 0);
        assertFalse(bad.ok());
        assertTrue(bad.message().contains("要押"), bad.message());
        assertTrue(bad.message().contains("心"), bad.message());
    }

    /** 多音字任一读音命中即算押：行(xíng eng / háng ang) 押 ang → 过。 */
    @Test
    public void validate_polyphoneAnyReadingCounts() {
        assertTrue(FillAiService.validateLine("我在这一行", 5, "ang", 0).ok());
    }

    /** 方案不限(null)不判韵；句尾英文给 warning 不拦。 */
    @Test
    public void validate_offRhymeAndEnglishTail() {
        assertTrue(FillAiService.validateLine("hello world 天涯", 5, null, 0).ok()); // 4 单元 = 5-1 → 警告放行
        FillAiService.LineCheck eng = FillAiService.validateLine("跟我一起 move on", N, "ang", 0);
        assertTrue(eng.ok());
        assertTrue(eng.warning());
        assertTrue(eng.message().contains("英文"));
    }

    @Test
    public void validate_emptyOutputFails() {
        assertFalse(FillAiService.validateLine("", N, null, 0).ok());
        assertFalse(FillAiService.validateLine("。", N, null, 0).ok());
    }

    // ==================== 逐句重试的提示词（§6.3 第 3 条） ====================

    /**
     * 一句的模板：重试提示词只吃 {@code lrcCount} / {@code lrcText}（导出 lrc 口径），
     * 其余随便给 —— 单轨单声部时 lrc 口径与「全部槽位」同值，所以这里两者给一样的。
     */
    private static FillAiService.TemplateLine line(int index, String originalText, int needCount) {
        LyricTemplate.FillLine fillLine = new LyricTemplate.FillLine(
                List.of(new LyricTemplate.FillSlot(0, index, originalText, SlotType.HANZI)),
                originalText, needCount, 0L);
        return new FillAiService.TemplateLine(index, fillLine, originalText, false, 0,
                originalText, "", needCount);
    }

    /**
     * 重试请求里必须带「本批已定稿的句」与主题/大纲（§6.3 第 3 条：只重发不合格的句，
     * 其余句作为已定稿上下文）——不然重写的句接不上前后文。
     */
    @Test
    public void retryPrompt_carriesFinalizedContextAndTheme() {
        List<FillAiService.TemplateLine> lines = List.of(
                line(0, "春风又绿江南岸", 7),
                line(1, "夜半钟声到客船", 7),
                line(2, "孤帆远影碧空尽", 7));
        List<Integer> batch = List.of(0, 1, 2);
        List<String> answers = List.of("夜色温柔漫上岸", "钟声敲在旧船板", "孤帆走远");
        List<Integer> bad = List.of(2); // 第 3 句 4 字 < 7-1 → 不合格
        Map<Integer, String> bodies = Map.of(0, "an", 1, "an", 2, "en");

        String prompt = FillAiService.buildRetryPrompt("都市夜店邂逅，后半段失恋", batch, bad,
                answers, lines, bodies);

        // 主题/大纲原样注入（§6.2 的 user 段风格）
        assertTrue(prompt.contains("主题/大纲：都市夜店邂逅，后半段失恋"), prompt);
        // 本批已通过校验的句按批内序号列出，并明说不要改
        assertTrue(prompt.contains("不要改、不要重写"), prompt);
        assertTrue(prompt.contains("第 1 句：夜色温柔漫上岸"), prompt);
        assertTrue(prompt.contains("第 2 句：钟声敲在旧船板"), prompt);
        // 不合格的那 N 句：契约不变 —— 编号仍从 1 起、只列不合格的句、带原词与失败原因
        int cut = prompt.indexOf("以下 1 句不合格");
        assertTrue(cut >= 0, prompt);
        String rewritePart = prompt.substring(cut);
        // 尾韵标签走共用口径（十八韵名/韵母）；韵母表顺序由 Map 迭代顺序定，不钉死
        assertTrue(rewritePart.contains("1(7字, 十五痕/"), rewritePart);
        assertTrue(rewritePart.contains("孤帆远影碧空尽"), rewritePart);
        assertTrue(rewritePart.contains("上次输出：「孤帆走远」"), rewritePart);
        assertTrue(rewritePart.contains("不合格原因：字数不足"), rewritePart);
        assertFalse(rewritePart.contains("2("), rewritePart);
        // 已定稿的句只作上下文，不许混进「要重写」那一段
        assertFalse(rewritePart.contains("夜色温柔漫上岸"), rewritePart);
        assertFalse(rewritePart.contains("钟声敲在旧船板"), rewritePart);
    }

    /** 全部不合格时没有已定稿上下文，主题行仍在（形状不随内容变形）。 */
    @Test
    public void retryPrompt_noContextWhenNothingPassed() {
        List<FillAiService.TemplateLine> lines = List.of(line(0, "春风又绿江南岸", 7));
        String prompt = FillAiService.buildRetryPrompt("", List.of(0), List.of(0),
                List.of("短"), lines, Map.of(0, "an"));
        assertTrue(prompt.contains("主题/大纲：（未填，按原词结构与韵脚自由发挥）"), prompt);
        assertFalse(prompt.contains("已定稿"), prompt);
    }

    // ==================== user 段形状（推理期与 JSONL 素材共用，§5.7 要求同 §6.2） ====================

    /**
     * 句子表 + 主题行是共用拼装：JSONL 导出与推理期的形状必须一致（§5.7），
     * 且尾韵两种入参（韵身 / 十八韵名）给同一个标签 —— 导出给的是 yun18 名，推理期给韵身。
     */
    @Test
    public void userSection_shapeIsSharedAndRhymeLabelAcceptsBothForms() {
        String label = FillAiPrompts.rhymeLabelOf("ang");
        // JSONL 导出的那一行：序号批内从 1 起（不是库里的绝对句下标）+ 十八韵名入参
        assertEquals(label, FillAiPrompts.rhymeLabelOf("十六唐"));
        String user = FillAiPrompts.userSection(null,
                List.of(FillAiPrompts.sentenceLine(1, 6, "十六唐", "春风又绿江南岸", 0)), List.of());
        assertEquals("主题/大纲：（未填，按原词结构与韵脚自由发挥）\n\n"
                + "句子表（序号(字数, 尾韵)：原词）：\n"
                + "1(6字, " + label + ")：春风又绿江南岸\n", user);
        // 多音字的 yun18 是逗号连接的多个名：逐个展开成同一形状
        assertEquals(label + "," + FillAiPrompts.rhymeLabelOf("an"),
                FillAiPrompts.rhymeLabelOf("十六唐,an"));
        // 「含英文 K 词」的标注（与推理期同一处拼装）
        assertTrue(FillAiPrompts.sentenceLine(2, 6, null, "跟我一起 move on", 2)
                .contains("（含英文 2 词）"));
    }

    // ==================== 每批句数的区间（partition 的 i += size） ====================

    /** batch-size=0 会让 partition 原地转不出去；配置值取用时钳到 [1, 64]。 */
    @Test
    public void clampBatchSize_keepsLoopProgressing() {
        assertEquals(1, FillAiService.clampBatchSize(0));
        assertEquals(1, FillAiService.clampBatchSize(-8));
        assertEquals(8, FillAiService.clampBatchSize(8));
        assertEquals(FillAiService.MAX_BATCH_SIZE, FillAiService.clampBatchSize(10000));
    }
}
