package io.github.Nyameph.nyaentworks.song.fill.rhyme;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 锚解析（{@link RhymeService#anchorOf}）与韵母归一。纯函数，可离线跑。 */
public class RhymeAnchorTest {

    /** 汉字锚（单音字）：心 → 韵身 en（十五痕）。 */
    @Test
    public void anchor_hanzi_singleReading() {
        RhymeService.Anchor a = RhymeService.anchorOf("心", "yun18");
        assertTrue(a.recognized());
        assertEquals(List.of("en"), a.keys());
    }

    /** 汉字锚（多音字）按韵身：行 → xíng(庚 eng) / háng(唐 ang) 两个韵身都算命中。 */
    @Test
    public void anchor_hanzi_polyphone_allBodies() {
        RhymeService.Anchor a = RhymeService.anchorOf("行", "yun18");
        assertTrue(a.recognized());
        assertEquals(2, a.keys().size());
        assertTrue(a.keys().contains("eng"));
        assertTrue(a.keys().contains("ang"));
    }

    /** 汉字锚（多音字）按韵母：率 → lǜ(ü) / shuài(uai)，合并字典里的古音也会多展开（合法）。 */
    @Test
    public void anchor_hanzi_polyphone_finals() {
        RhymeService.Anchor a = RhymeService.anchorOf("率", "finals");
        assertTrue(a.recognized());
        assertTrue(a.keys().size() >= 2);
        assertTrue(a.keys().contains("ü"));
        assertTrue(a.keys().contains("uai"));
        // 常用读音排在前：率 的第一键应是 ü 或 uai 之一，古音垫后
        assertTrue(a.keys().get(0).equals("ü") || a.keys().get(0).equals("uai"));
    }

    /** 舌尖元音：知 的韵母是 -i（五支），与 机 的 i（七齐）不混。
     *  机 在合并字典里另带古音 wèi（uei），按常用度排后面。 */
    @Test
    public void anchor_hanzi_apicalI() {
        assertEquals(List.of("-i"), RhymeService.anchorOf("知", "finals").keys());
        RhymeService.Anchor a = RhymeService.anchorOf("机", "finals");
        assertTrue(a.recognized());
        assertEquals("i", a.keys().get(0), "常用读音在前");
        assertTrue(a.keys().contains("uei"), "古音也展开但不垫前");
        assertEquals(List.of("-i"), RhymeService.anchorOf("知", "yun18").keys());
    }

    /** 韵母锚：ang 直接 parse；v / ui / un 先归一再 parse。 */
    @Test
    public void anchor_finalsString_withNormalization() {
        assertEquals(List.of("ang"), RhymeService.anchorOf("ang", "yun18").keys());
        // v → ü：韵身 ü（十一鱼）
        assertEquals(List.of("ü"), RhymeService.anchorOf("v", "yun18").keys());
        assertEquals(List.of("üe"), RhymeService.anchorOf("ve", "finals").keys());
        // ui → uei（韵身 ei）；iu → iou（韵身 ou）；un → uen（韵身 en）
        assertEquals(List.of("uei"), RhymeService.anchorOf("ui", "finals").keys());
        assertEquals(List.of("iou"), RhymeService.anchorOf("iu", "finals").keys());
        assertEquals(List.of("uen"), RhymeService.anchorOf("un", "finals").keys());
        // van → üan（韵身 an）；带空格也认
        assertEquals(List.of("üan"), RhymeService.anchorOf(" van ", "finals").keys());
    }

    /** 舌尖元音锚写作 -i 时单独放行，不能落进七齐。 */
    @Test
    public void anchor_apicalSpelling() {
        assertEquals(List.of("-i"), RhymeService.anchorOf("-i", "finals").keys());
    }

    /** 十八韵名精确匹配：拿到韵身。 */
    @Test
    public void anchor_yun18Name() {
        RhymeService.Anchor a = RhymeService.anchorOf("十六唐", "yun18");
        assertTrue(a.recognized());
        assertEquals(List.of("ang"), a.keys());
    }

    /** 认不出的锚：空集合 + message，不抛异常。 */
    @Test
    public void anchor_unrecognized() {
        RhymeService.Anchor a = RhymeService.anchorOf("xyzabcd", "yun18");
        assertFalse(a.recognized());
        assertTrue(a.keys().isEmpty());
        assertTrue(a.message().contains("xyzabcd"));
        assertTrue(RhymeService.anchorOf("", "yun18").message().contains("锚"));
        // 多个汉字不是合法锚（锚只吃单字 / 韵母 / 韵部名）
        assertFalse(RhymeService.anchorOf("长江", "yun18").recognized());
    }

    /** parse 返回 null 的读音（哟=yo、嗯=ń 一类）跳过不抛，一个都拆不出就认不出。 */
    @Test
    public void anchor_unparseableReading_noThrow() {
        RhymeService.Anchor a = RhymeService.anchorOf("哟", "yun18");
        assertFalse(a.recognized());
        assertTrue(a.keys().isEmpty());
    }

    /** 粒度缺省按 yun18。 */
    @Test
    public void anchor_granularityDefault() {
        assertEquals(RhymeService.anchorOf("心", null).keys(),
                RhymeService.anchorOf("心", "yun18").keys());
    }

    /** 归一函数本身：表外写法原样返回。 */
    @Test
    public void normalize_passthrough() {
        assertEquals("ang", RhymeService.normalizeFinal("ang"));
        assertEquals("iang", RhymeService.normalizeFinal(" iang "));
        assertEquals("", RhymeService.normalizeFinal(null));
        assertEquals("ü", RhymeService.normalizeFinal("V"));
    }
}
