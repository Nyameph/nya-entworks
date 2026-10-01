package io.github.Nyameph.nyaentworks.song.fill;

import io.github.Nyameph.nyaentworks.common.pinyin.PinyinUtil;
import io.github.Nyameph.nyaentworks.song.fill.PolyphoneHint.Index;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多音字提醒。纯函数 + 写死字典的校验，不依赖外部环境。
 *
 * <p>{@code suggestDict_*} 把写死的建议字典整个过一遍，保证每个建议字真的是「只有这一个读音、
 * 且读音 = 键」——写字典时手滑塞进多音字 / 放错音节会被立刻揪出来。
 */
public class PolyphoneHintTest {

    // ==================== 写死建议字典的校验 ====================

    @Test
    public void suggestDict_everyCharIsSingleSyllableAndMatchesKey() {
        List<String> problems = new java.util.ArrayList<>();
        for (Map.Entry<String, List<String>> e : PolyphoneHint.SUGGEST.entrySet()) {
            String key = e.getKey();
            for (String ch : e.getValue()) {
                int cp = ch.codePointAt(0);
                // 建议字按「现代标准读音」（kTGHZ2013）校验，与触发集合同源：
                // 建议字必须只有这一个现代读音，且读音 = 键。
                List<String> raw = PinyinUtil.modernPinyin(cp);
                Set<String> syllables = new java.util.LinkedHashSet<>();
                for (String r : raw) {
                    String n = PinyinLyricMatcher.normalize(r);
                    if (!n.isEmpty()) {
                        syllables.add(n);
                    }
                }
                if (!syllables.equals(java.util.Set.of(key))) {
                    problems.add("键 " + key + " 的「" + ch + "」是多音节字（读音 " + raw + "）");
                }
            }
        }
        // 控制台是 GBK、会把断言消息里的中文显示成乱码，故把问题清单额外落一份 UTF-8 文件方便排查。
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of("target/polyphone-problems.txt"),
                    String.join("\n", problems), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException ignored) {
        }
        assertTrue(problems.isEmpty(), problems.size() + " 个问题：" + String.join("；", problems));
    }

    @Test
    public void suggestDict_hasNoEmptyKeysOrValues() {
        for (Map.Entry<String, List<String>> e : PolyphoneHint.SUGGEST.entrySet()) {
            assertFalse(e.getKey().isEmpty());
            assertFalse(e.getValue().isEmpty(), "键 " + e.getKey() + " 的建议字为空");
        }
    }

    // ==================== 多音字索引（readings 正排 + polyphonesOf 倒排）====================

    @Test
    public void index_readings_coverSyllablePolyphones() {
        Index index = PolyphoneHint.index();
        // 「重」zhòng/chóng、「长」cháng/zhǎng、「了」le/liǎo：两个读音都是常用音 → 醒目
        assertTrue(index.readings().get("重").containsAll(List.of("zhong", "chong")));
        assertTrue(index.readings().get("长").containsAll(List.of("chang", "zhang")));
        assertTrue(index.readings().get("了").containsAll(List.of("le", "liao")));
    }

    @Test
    public void index_readings_excludesToneOnlyVariants() {
        Index index = PolyphoneHint.index();
        // 「中」zhōng/zhòng、「好」hǎo/hào 只是声调差异，音节不变 → 连弱提醒都不给
        assertFalse(index.readings().containsKey("中"));
        assertFalse(index.readings().containsKey("好"));
        assertFalse(index.rare().containsKey("中"));
        assertFalse(index.rare().containsKey("好"));
    }

    @Test
    public void index_rare_holdsCharsWithOnlyOneCommonReading() {
        Index index = PolyphoneHint.index();
        // 「他」tā 常用、tuó 是古音 → 只剩 1 个常用读音 → 弱，且值就是那个生僻音
        assertFalse(index.readings().containsKey("他"));
        assertEquals(List.of("tuo"), index.rare().get("他"));
        // 「单」dān 常用，chán/shàn 只活在「单于」和姓氏里 → 弱
        assertFalse(index.readings().containsKey("单"));
        assertTrue(index.rare().get("单").containsAll(List.of("chan", "shan")));
        // 「区」qū 常用、ōu 只在姓氏里 → 弱
        assertTrue(index.rare().containsKey("区"));
    }

    @Test
    public void index_rare_holdsCharsWhoseExtraReadingsAreArchaic() {
        Index index = PolyphoneHint.index();
        // 母 mǔ / wú、市 shì / fú：wú、fú 只存在于合并字典，自动算生僻 → 弱而不是不收
        assertEquals(List.of("wu"), index.rare().get("母"));
        assertEquals(List.of("fu"), index.rare().get("市"));
    }

    @Test
    public void index_readings_keepsCharsDemotedByNothing() {
        // 「行」xíng/háng 两个都是常用音 → 保持醒目
        assertTrue(PolyphoneHint.index().readings().get("行").containsAll(List.of("hang", "xing")));
    }

    @Test
    public void index_readings_includesSupplementaryReading() {
        // 「骑」两本词典都只收 qí，jì 是 reading_common.txt 补录的 → 两个常用音 → 醒目
        List<String> qi = PolyphoneHint.index().readings().get("骑");
        assertNotNull(qi, "「骑」应该因补录的 jì 进入醒目集合");
        assertTrue(qi.containsAll(List.of("qi", "ji")));
    }

    @Test
    public void polyphones_zhong_contains重_butNot中() {
        // 倒排：「重」同时挂在 zhong 与 chong 下
        assertTrue(PolyphoneHint.polyphonesOf("zhong").contains("重"));
        assertFalse(PolyphoneHint.polyphonesOf("zhong").contains("中"));
        assertTrue(PolyphoneHint.polyphonesOf("chong").contains("重"));
    }

    @Test
    public void polyphones_excludesToneOnlyVariants() {
        // 「好」hǎo/hào、「为」wéi/wèi 都只是声调差异，音节不变，不在提醒范围
        assertFalse(PolyphoneHint.polyphonesOf("hao").contains("好"));
        assertFalse(PolyphoneHint.polyphonesOf("wei").contains("为"));
    }

    @Test
    public void polyphones_excludesDemotedAndArchaicOnlyChars() {
        // 这些字都只是「弱」多音字，不该出现在倒排里（倒排是给醒目那批的换字建议用的）
        assertFalse(PolyphoneHint.polyphonesOf("mu").contains("母"));
        assertFalse(PolyphoneHint.polyphonesOf("du").contains("毒"));
        assertFalse(PolyphoneHint.polyphonesOf("ta").contains("他"));
        assertFalse(PolyphoneHint.polyphonesOf("zhan").contains("站"));
        assertFalse(PolyphoneHint.polyphonesOf("dan").contains("单"));
    }

    @Test
    public void polyphones_行spansTwoSyllables() {
        assertTrue(PolyphoneHint.polyphonesOf("xing").contains("行"));
        assertTrue(PolyphoneHint.polyphonesOf("hang").contains("行"));
    }

    // ==================== 分级结果的不变量 ====================

    @Test
    public void classification_neverLeavesACharWithoutCommonReading() {
        // 「降级降过头」是读音表最容易出的错：把某个字唯一还常用的读音也标成生僻，
        // 那个字就落到「总音节 ≥2 但一个常用音都没有」的怪状态 —— 属于多音字、却没有可锚的读音，
        // classify() 会把它两边都不收（不报错，只是悄悄漏掉）。这里把它揪出来。
        //
        // 范围只取**现代字典里有的字**：合并字典里另外那几万个生僻字压根没有现代读音，
        // 落在「常用读音 0 个」是正常的（见 PolyphoneHint#classify 的注释），不是降级降过头。
        List<String> problems = new java.util.ArrayList<>();
        PinyinUtil.forEachModern((codePoint, rawReadings) -> {
            if (PinyinUtil.allSyllables(codePoint).size() >= 2
                    && PinyinUtil.commonSyllables(codePoint).isEmpty()) {
                problems.add(new String(Character.toChars(codePoint)));
            }
        });
        assertTrue(problems.isEmpty(),
                problems.size() + " 个字被降得一个常用读音都不剩：" + String.join("", problems));
    }

    @Test
    public void classification_isPartitionedIntoStrongWeakAndNone() {
        // 三堆互斥且覆盖全：每个多音字要么在 readings（醒目），要么在 rare（弱），不能两边都有、
        // 也不能只有一边缺 —— 前端就是靠这个分流画两种虚线的。
        Index index = PolyphoneHint.index();
        List<String> both = new java.util.ArrayList<>();
        index.readings().keySet().forEach(ch -> {
            if (index.rare().containsKey(ch)) {
                both.add(ch);
            }
        });
        assertTrue(both.isEmpty(), "同时进了醒目与弱两张表：" + String.join("", both));
        // 弱表的值必须非空（空值等于「这是弱多音字但没有生僻音」，自相矛盾）
        List<String> empty = new java.util.ArrayList<>();
        index.rare().forEach((ch, rares) -> {
            if (rares.isEmpty()) {
                empty.add(ch);
            }
        });
        assertTrue(empty.isEmpty(), "弱表里有空读音列表：" + String.join("", empty));
    }

    @Test
    public void classification_supplementReadingsLandInStrong() {
        // reading_common.txt 补录的字必须靠 forEachSupplement 被扫到 —— 落不下就说明
        // 「补音只改了 PinyinUtil、没进多音字索引」，补了等于没补。
        List<String> missing = new java.util.ArrayList<>();
        PinyinUtil.forEachSupplement((codePoint, rawReadings) -> {
            String ch = new String(Character.toChars(codePoint));
            if (PinyinUtil.allSyllables(codePoint).size() >= 2
                    && PinyinUtil.commonSyllables(codePoint).size() >= 2
                    && !PolyphoneHint.index().readings().containsKey(ch)) {
                missing.add(ch);
            }
        });
        assertTrue(missing.isEmpty(), "补录了读音却没进醒目集合：" + String.join("", missing));
    }

    // ==================== 建议换字 ====================

    @Test
    public void suggestOf_returnsEmptyWhenNotCurated() {
        assertTrue(PolyphoneHint.suggestOf("zhong").contains("终"));
        assertTrue(PolyphoneHint.suggestOf("no-such-syllable").isEmpty());
    }

    @Test
    public void index_suggest_isTheCuratedDictItself() {
        // 前端拿 suggest 查建议换字，必须是同一份写死字典
        assertEquals(PolyphoneHint.SUGGEST, PolyphoneHint.index().suggest());
    }
}
