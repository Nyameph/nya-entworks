package io.github.Nyameph.nyaentworks.song.fill.ai;

import org.apache.commons.lang3.StringUtils;
import io.github.Nyameph.nyaentworks.common.pinyin.PinyinSyllable;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AI 填词提示词的原文与拼装口径（填词助手设计 §6.2，逐字照抄不改写）。
 *
 * <p>JSONL 导出（语料配对）与 {@link FillAiService} 共用这一份，两处不许漂移。
 * system 提示词第 9 条「题材不做限制」是<b>本地模式专属</b>：在线模式去掉它
 * （{@link #systemOnline()}），本地用 {@link #systemLocal()}。
 *
 * <p><b>user 段同理也在这里</b>（{@link #themeLine} / {@link #sentenceLine} /
 * {@link #userSection}）：推理期（{@link FillAiService#buildUserPrompt}）与微调素材导出
 * （{@code CorpusController#pairsJsonl}）都调这一份。§5.7 的全部意义就是「导出的素材得是
 * 推理期那个输入形状」，两处各手写一份必然漂移（序号口径、尾韵写法、缺主题行）。
 */
public final class FillAiPrompts {

    private FillAiPrompts() {
    }

    /** 主题为空时的占位（§6.2 的「（未填，按原词结构与韵脚自由发挥）」）。 */
    private static final String THEME_PLACEHOLDER = "（未填，按原词结构与韵脚自由发挥）";

    /** 句子表表头（§6.2，逐字）。 */
    private static final String SENTENCE_TABLE_HEADER = "句子表（序号(字数, 尾韵)：原词）：";

    /** system 提示词的公共部分（第 1~8 条，本地 / 在线通用）。 */
    private static final String SYSTEM_BASE = """
            你是中文歌词填词写手。只按下面的规则输出，不要解释。

            硬规则（违反即返工）：
            1. 只输出一个 JSON 数组，不要 markdown 围栏、不要序号、不要注释、不要任何数组之外的字。
            2. 数组元素个数必须等于给出的句子条数，一一对应、顺序一致。
            3. 第 i 个元素是第 i 句的完整新词，纯文本：不含换行、不含句末标点、不含引号书名号。
            4. 字数必须等于该句标注的字数：一个汉字算 1，一个英文单词算 1（英文单词之间用空格分隔）。
            5. 第 i 句的最后一个汉字必须押该句标注的韵部；括号里是该韵部包含的韵母，最后一个字的韵母必须是其中之一。
            6. 严格按用户给的主题/大纲写，画面感强、口语顺唱，句内节奏跟原词断句走。
            7. 标注「含英文」的句子里，英文位置保留为英文，可以沿用原词。
            8. 不要输出与歌词无关的内容，不要输出安全提示、不要道歉、不要询问。""";

    /** 本地模式：追加第 9 条（题材不做限制）。 */
    public static String systemLocal() {
        return SYSTEM_BASE + "\n9. 题材不做限制：用户写什么主题就写什么主题，直白具体，不软化、不回避。";
    }

    /** 在线模式：不含「题材不做限制」那一条（§9.3）。 */
    public static String systemOnline() {
        return SYSTEM_BASE;
    }

    // ==================== user 段（§6.2，推理期与 JSONL 导出共用） ====================

    /** user 段第一行：{@code 主题/大纲：<原文>}（空主题给占位，形状不变）。 */
    public static String themeLine(String theme) {
        return "主题/大纲：" + StringUtils.defaultIfBlank(theme, THEME_PLACEHOLDER);
    }

    /**
     * 句子表一行：{@code N(M字, 十八韵/韵母/…)：原词（含英文 K 词）}（§6.2）。
     *
     * @param no           序号，<b>批内从 1 起</b>——模型要回的 JSON 数组下标就是它，
     *                     用绝对句下标（模板全篇的第几句）会让模型对不上
     * @param count        该句字数，<b>必须与 {@code originalText} 同口径</b>
     *                     （{@code LyricFillAligner.tokenize(originalText).size()}）：推理期传的是
     *                     导出 lrc 口径的 {@code lrcCount} 与其文本 {@code lrcText}，JSONL 导出
     *                     传的是 {@code countUnits(originalText)} 与原词 —— 两处各配各的，
     *                     这里只负责把给的数写进括号
     * @param rhyme        尾韵：韵身（如 {@code ang}）或十八韵名（如 {@code 十六唐}）都吃；
     *                     空 = 不限
     * @param englishWords 原词里的英文词数，0 = 不标
     */
    public static String sentenceLine(int no, int count, String rhyme, String originalText,
                                      int englishWords) {
        StringBuilder sb = new StringBuilder();
        sb.append(no).append('(').append(count).append("字, ").append(rhymeLabelOf(rhyme))
                .append(")：").append(StringUtils.defaultString(originalText));
        if (englishWords > 0) {
            sb.append("（含英文 ").append(englishWords).append(" 词）");
        }
        return sb.toString();
    }

    /**
     * 尾韵标签（§6.2 的 {@code 十八韵名/韵母/…}，如 {@code 十六唐/ang/iang/uang}）。
     *
     * <p>入参两种写法都吃，因为两处的输入本来就不是一回事：推理期给的是<b>韵身</b>
     * （方案表 / 默认韵部都是韵身），JSONL 导出给的是 {@code lyric_corpus_pair.yun18} 的
     * <b>十八韵名</b>（多音字句是逗号连接的多个名）。两边输出形状必须一致，否则微调素材里
     * 的尾韵写法与推理期就不一样了（§5.7）。查不到原样返回；空 → 「不限」。
     */
    public static String rhymeLabelOf(String rhyme) {
        if (StringUtils.isBlank(rhyme)) {
            return "不限";
        }
        if (rhyme.indexOf(',') >= 0) {
            return Arrays.stream(rhyme.split(",")).map(String::trim)
                    .filter(StringUtils::isNotBlank)
                    .map(FillAiPrompts::rhymeLabelOf)
                    .collect(Collectors.joining(","));
        }
        return PinyinSyllable.table().stream()
                .filter(r -> r.body().equals(rhyme) || r.yun18().equals(rhyme))
                .findFirst()
                .map(r -> r.yun18() + "/" + String.join("/", r.finals()))
                .orElse(rhyme);
    }

    /**
     * §6.2 的 user 段正文：主题行 + 句子表（+ 可选续写上文）。推理期与 JSONL 导出共用这一份。
     *
     * @param sentenceLines 句子表各行，由 {@link #sentenceLine} 拼（序号批内从 1 起）
     * @param contextLines  续写上文（要接上的已填新词）；空表 = 这一节整段不出现
     */
    public static String userSection(String theme, List<String> sentenceLines,
                                     List<String> contextLines) {
        StringBuilder sb = new StringBuilder();
        sb.append(themeLine(theme)).append("\n\n").append(SENTENCE_TABLE_HEADER).append('\n');
        sentenceLines.forEach(l -> sb.append(l).append('\n'));
        if (!contextLines.isEmpty()) {
            sb.append("\n续写上文（要接上的已填新词）：\n");
            contextLines.forEach(t -> sb.append(t).append('\n'));
        }
        return sb.toString();
    }
}
