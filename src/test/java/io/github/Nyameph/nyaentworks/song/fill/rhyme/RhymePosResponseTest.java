package io.github.Nyameph.nyaentworks.song.fill.rhyme;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 词性补全的模型输出解析（{@link RhymePosService#parsePosResponse}）。纯函数，可离线跑。
 *
 * <p>钉四件事：枚举外的值落「其他」、模型漏词没有键、模型多返回的词丢掉、不是 JSON / 带围栏
 * 两种输入各是什么结果 —— 这四种正是模型天天出的花样。
 */
public class RhymePosResponseTest {

    private static final List<String> BATCH = List.of("碎星", "奔跑", "温柔");

    /** 正常 JSON 对象：本批的词逐一对上，值都在枚举里。 */
    @Test
    public void parse_plainObject() {
        Map<String, String> pos = RhymePosService.parsePosResponse(
                "{\"碎星\":\"名词\",\"奔跑\":\"动词\",\"温柔\":\"形容词\"}", BATCH);
        assertEquals(3, pos.size());
        assertEquals("名词", pos.get("碎星"));
        assertEquals("动词", pos.get("奔跑"));
        assertEquals("形容词", pos.get("温柔"));
        assertTrue(RhymeService.WORD_CLASSES.containsAll(pos.values()));
    }

    /** 带 ``` 围栏（模型最常见的包装）：剥掉后照常解析。 */
    @Test
    public void parse_stripsCodeFence() {
        Map<String, String> pos = RhymePosService.parsePosResponse(
                "```json\n{\"碎星\":\"名词\"}\n```", BATCH);
        assertEquals(Map.of("碎星", "名词"), pos);
    }

    /** 对象前后夹了废话（「好的，结果如下：」）：从第一个 { 到最后一个 } 截取。 */
    @Test
    public void parse_surroundingProse() {
        Map<String, String> pos = RhymePosService.parsePosResponse(
                "好的，结果如下：\n{\"奔跑\":\"动词\"}\n以上。", BATCH);
        assertEquals(Map.of("奔跑", "动词"), pos);
    }

    /** 枚举外的值（含旧写法「名词-人」「动作」与自造词性）一律落「其他」，不许脏了 word_class。 */
    @Test
    public void parse_enumOutsideFallsToOther() {
        Map<String, String> pos = RhymePosService.parsePosResponse(
                "{\"碎星\":\"名词-人\",\"奔跑\":\"动名词\",\"温柔\":\"形容词\"}", BATCH);
        assertEquals("其他", pos.get("碎星"));
        assertEquals("其他", pos.get("奔跑"));
        assertEquals("形容词", pos.get("温柔"));
        pos.values().forEach(v -> assertTrue(RhymeService.WORD_CLASSES.contains(v), v));
    }

    /** 值是 null / 空串 / 空白：同样落「其他」（键在就说明模型表了态）。 */
    @Test
    public void parse_blankValueFallsToOther() {
        Map<String, String> pos = RhymePosService.parsePosResponse(
                "{\"碎星\":null,\"奔跑\":\"\",\"温柔\":\"  \"}", BATCH);
        assertEquals("其他", pos.get("碎星"));
        assertEquals("其他", pos.get("奔跑"));
        assertEquals("其他", pos.get("温柔"));
    }

    /** 模型漏返回的词：没有键（调用方跳过不动，行保持 NULL 等下次重跑）。 */
    @Test
    public void parse_missingWordHasNoKey() {
        Map<String, String> pos = RhymePosService.parsePosResponse("{\"碎星\":\"名词\"}", BATCH);
        assertEquals(1, pos.size());
        assertFalse(pos.containsKey("奔跑"));
        assertNull(pos.get("奔跑"));
    }

    /** 模型多返回的词（不在本批里）：直接丢掉，不许进结果。 */
    @Test
    public void parse_extraWordIgnored() {
        Map<String, String> pos = RhymePosService.parsePosResponse(
                "{\"碎星\":\"名词\",\"银河\":\"名词\",\"孤独\":\"形容词\"}", BATCH);
        assertEquals(1, pos.size());
        assertFalse(pos.containsKey("银河"));
        assertFalse(pos.containsKey("孤独"));
    }

    /** 不是 JSON（模型道歉 / 返回纯文本）：空表 —— 调用方按「这一批失败」记数。 */
    @ParameterizedTest
    @ValueSource(strings = {"抱歉，我做不到", "", "   ", "```json\n```", "[]", "[1, 2, 3]",
            "{\"碎星\"", "这里本来是 JSON 的"})
    public void parse_notJsonReturnsEmpty(String modelText) {
        assertTrue(RhymePosService.parsePosResponse(modelText, BATCH).isEmpty(), modelText);
    }

    /** null 输入不抛（AiChatClient 给空串 / 上层没拿到回复时都走这条）。 */
    @Test
    public void parse_nullInputsReturnEmpty() {
        assertTrue(RhymePosService.parsePosResponse(null, BATCH).isEmpty());
        assertTrue(RhymePosService.parsePosResponse("{\"碎星\":\"名词\"}", null).isEmpty());
    }

    /** system 提示词里的枚举与 {@link RhymeService#WORD_CLASSES} 同源，一个不少。 */
    @Test
    public void systemPrompt_listsEveryWordClass() {
        String system = RhymePosService.systemPrompt();
        for (String wordClass : RhymeService.WORD_CLASSES) {
            assertTrue(system.contains(wordClass), wordClass);
        }
        assertTrue(system.contains("JSON"));
    }

    /** user 段一行一个词（词的原文只进请求体，不进日志与任务结果）。 */
    @Test
    public void userPrompt_oneWordPerLine() {
        String user = RhymePosService.buildUserPrompt(BATCH);
        for (String word : BATCH) {
            assertTrue(user.contains(word), word);
        }
        assertEquals(1 + BATCH.size(), user.lines().count());
    }
}
