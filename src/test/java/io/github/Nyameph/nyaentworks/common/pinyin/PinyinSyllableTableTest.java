package io.github.Nyameph.nyaentworks.common.pinyin;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link PinyinSyllable#table()} 的十八韵聚合。纯函数，可离线跑。 */
public class PinyinSyllableTableTest {

    /** 18 条、按十八韵序号排。 */
    @Test
    public void table_has18_inOrder() {
        List<PinyinSyllable.Rhyme> table = PinyinSyllable.table();
        assertEquals(18, table.size());
        for (int i = 0; i < table.size(); i++) {
            assertEquals(i + 1, numeralOf(table.get(i).yun18()), "第 " + i + " 条应是序号 " + (i + 1));
        }
    }

    /** 韵身 ↔ 十八韵 双射：18 条的 body 互不相同，且 body → yun18 与 YUN18 的映射一致。 */
    @Test
    public void table_bodyYun18Bijection() {
        List<PinyinSyllable.Rhyme> table = PinyinSyllable.table();
        long distinct = table.stream().map(PinyinSyllable.Rhyme::body).distinct().count();
        assertEquals(18, distinct);
        for (PinyinSyllable.Rhyme rhyme : table) {
            PinyinSyllable probe = PinyinSyllable.parse(probePinyinOf(rhyme.body()));
            assertTrue(probe != null && rhyme.body().equals(probe.rhymeBody()),
                    "韵身 " + rhyme.body() + " 应能从探针音节走回自己");
        }
    }

    /** 每条的 finals 清单完整：任一韵母 parse 回来的韵身都落在本条，且跨条不重复。
     *  RHYME_BODY 共 41 键（含 iou/iu、uei/ui 缩写并列），全部要落到 18 条里。 */
    @Test
    public void table_finalsPartition() {
        List<PinyinSyllable.Rhyme> table = PinyinSyllable.table();
        long finalsTotal = table.stream().mapToLong(r -> r.finals().size()).sum();
        assertEquals(41, finalsTotal, "韵母总数应是 RHYME_BODY 的 41 个键");
        for (PinyinSyllable.Rhyme rhyme : table) {
            for (String fin : rhyme.finals()) {
                PinyinSyllable probe = PinyinSyllable.parse(probePinyinOf(fin));
                assertTrue(probe != null, "韵母 " + fin + " 应能 parse");
                assertEquals(rhyme.body(), probe.rhymeBody(),
                        "韵母 " + fin + " 的韵身应落在 " + rhyme.body());
            }
        }
    }

    /** 已知条目抽查：十六唐含 ang/iang/uang，五支的 finals 是 -i，一麻含 a/ia/ua。 */
    @Test
    public void table_knownEntries() {
        List<PinyinSyllable.Rhyme> table = PinyinSyllable.table();
        PinyinSyllable.Rhyme tang = table.stream()
                .filter(r -> r.yun18().equals("十六唐")).findFirst().orElseThrow();
        assertEquals(3, tang.finals().size());
        assertTrue(tang.finals().containsAll(List.of("ang", "iang", "uang")));
        assertEquals("江阳", tang.zhe13());
        assertEquals("十唐", tang.yun14());
        PinyinSyllable.Rhyme zhi = table.stream()
                .filter(r -> r.yun18().equals("五支")).findFirst().orElseThrow();
        assertEquals(List.of("-i"), zhi.finals());
        PinyinSyllable.Rhyme ma = table.stream()
                .filter(r -> r.yun18().equals("一麻")).findFirst().orElseThrow();
        assertEquals(3, ma.finals().size());
        assertTrue(ma.finals().containsAll(List.of("a", "ia", "ua")));
    }

    /** 各韵身找一个代表音节（parse 后韵身 = 自己）。 */
    private static String probePinyinOf(String bodyOrFinal) {
        return switch (bodyOrFinal) {
            case "a" -> "ā";
            case "o" -> "ō";
            case "e" -> "è";
            case "ê" -> "üè";
            case "i" -> "jī";
            case "-i" -> "zhī";
            case "u" -> "wū";
            case "ü" -> "yú";
            case "er" -> "ér";
            case "ai" -> "āi";
            case "ei" -> "ēi";
            case "ao" -> "āo";
            case "ou" -> "ōu";
            case "an" -> "ān";
            case "en" -> "ēn";
            case "ang" -> "āng";
            case "eng" -> "ēng";
            case "ong" -> "ōng";
            default -> bodyOrFinal; // 传进来的本来就是韵母：直接试它自己
        };
    }

    /** 十八韵名开头的汉字数字 → 序号（一麻=1 … 十八东=18）。 */
    private static int numeralOf(String yun18) {
        return switch (yun18) {
            case "一麻" -> 1;
            case "二波" -> 2;
            case "三歌" -> 3;
            case "四皆" -> 4;
            case "五支" -> 5;
            case "六儿" -> 6;
            case "七齐" -> 7;
            case "八微" -> 8;
            case "九开" -> 9;
            case "十姑" -> 10;
            case "十一鱼" -> 11;
            case "十二侯" -> 12;
            case "十三豪" -> 13;
            case "十四寒" -> 14;
            case "十五痕" -> 15;
            case "十六唐" -> 16;
            case "十七庚" -> 17;
            case "十八东" -> 18;
            default -> -1;
        };
    }
}
