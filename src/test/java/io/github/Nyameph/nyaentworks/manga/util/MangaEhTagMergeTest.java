package io.github.Nyameph.nyaentworks.manga.util;

import org.junit.jupiter.api.Test;
import io.github.Nyameph.nyaentworks.manga.util.MangaEhTagMerge.Candidate;
import io.github.Nyameph.nyaentworks.manga.util.MangaEhTagMerge.Merged;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MangaEhTagMerge#merge} 的纯函数测试：不连 eh、不连库（设计 §6.4 / §11 第 5 条）。
 *
 * <p>要守住的是那条<b>不对称</b>的规则：排序信号只有「相似度加权频次」，
 * 赞同数（weight）<b>只做过滤、不做加权</b>；而过滤本身又只在「已知权重」时生效 ——
 * 拿不到权重（详情页没抓或解析失败）绝不能把标签误删。后者是这套逻辑最容易被
 * 「顺手改成 `weight == null → 剔除`」的地方，所以单独占两条用例。
 *
 * <p>命名空间过滤见 {@link MangaEhTagNamespaceTest}。
 */
public class MangaEhTagMergeTest {

    private static final double EPS = 1e-9;

    private static Candidate c(double sim, String... tags) {
        return new Candidate(sim, List.of(tags), null);
    }

    private static List<String> tagsOf(List<Merged> list) {
        return list.stream().map(Merged::tag).toList();
    }

    /** 总分 = Σ 出现该标签的候选相似度，出现频次另计 */
    @Test
    public void score_isSimilarityWeightedFreq() {
        List<Merged> out = MangaEhTagMerge.merge(
                List.of(c(0.9, "female:a", "female:b"), c(0.5, "female:a")), 0, 10);

        Merged a = out.stream().filter(m -> m.tag().equals("female:a")).findFirst().orElseThrow();
        assertEquals(1.4, a.score(), EPS, "0.9 + 0.5");
        assertEquals(2, a.freq());
    }

    /** 同一标签在多个候选里出现，权重取见过的<b>最大值</b>，不是最后一个 */
    @Test
    public void knownWeight_takesMaxAcrossCandidates() {
        // 两个候选都带这条：权重 5 与 1，门槛 3 —— 取 max 才会留下
        List<Merged> out = MangaEhTagMerge.merge(List.of(
                new Candidate(0.9, List.of("female:a"), Map.of("female:a", 5)),
                new Candidate(0.8, List.of("female:a"), Map.of("female:a", 1))), 3, 10);

        assertEquals(List.of("female:a"), tagsOf(out));
    }

    /** 排序：score 降序 —— 「一个高分候选里的标签」压过「两个中分候选都有的标签」 */
    @Test
    public void sorted_byScoreDesc() {
        List<Merged> out = MangaEhTagMerge.merge(
                List.of(c(0.9, "female:low"), c(0.6, "female:high", "female:high2")), 0, 10);

        assertEquals(0.9, out.get(0).score(), EPS, "low 只出现一次，但那次的相似度是 0.9");
        assertEquals("female:low", out.get(0).tag());
        // high 与 high2 同分同频，按标签名升序
        assertEquals(List.of("female:low", "female:high", "female:high2"), tagsOf(out));
    }

    /** 同分比频次：频次高的在前 */
    @Test
    public void sorted_tieBreaksByFreq() {
        // twice 出现两次（0.3+0.3=0.6），once 一次（0.6）—— 同分，频次高的在前
        List<Merged> out = MangaEhTagMerge.merge(
                List.of(c(0.3, "female:twice"), c(0.3, "female:twice"), c(0.6, "female:once")), 0, 10);

        assertEquals(0.6, out.get(0).score(), EPS);
        assertEquals(0.6, out.get(1).score(), EPS);
        assertEquals(List.of("female:twice", "female:once"), tagsOf(out));
    }

    /** <b>权重不参与排序</b>：赞同数再高也不会把低相似度的标签抬上去 */
    @Test
    public void weight_doesNotAffectScore() {
        List<Merged> out = MangaEhTagMerge.merge(List.of(
                new Candidate(0.2, List.of("female:popular"), Map.of("female:popular", 999)),
                new Candidate(0.9, List.of("female:rare"), Map.of("female:rare", 0))), 0, 10);

        assertEquals(List.of("female:rare", "female:popular"), tagsOf(out));
    }

    /** 已知且低于门槛 → 剔除 */
    @Test
    public void filter_dropsKnownLowWeight() {
        List<Merged> out = MangaEhTagMerge.merge(List.of(
                new Candidate(0.9, List.of("female:ok", "female:bad"),
                        Map.of("female:ok", 10, "female:bad", 2))), 3, 10);

        assertEquals(List.of("female:ok"), tagsOf(out));
    }

    /** 恰好等于门槛 → 保留（判据是 {@code < weightMin}，不是 {@code <=}） */
    @Test
    public void filter_keepsWeightEqualToMin() {
        List<Merged> out = MangaEhTagMerge.merge(List.of(
                new Candidate(0.9, List.of("female:edge"), Map.of("female:edge", 3))), 3, 10);

        assertEquals(List.of("female:edge"), tagsOf(out));
    }

    /** 门槛为 0（默认）时，权重 0 的标签也留下 —— 「没有踩」不等于「被踩到底」 */
    @Test
    public void filter_zeroMinKeepsZeroWeight() {
        List<Merged> out = MangaEhTagMerge.merge(List.of(
                new Candidate(0.9, List.of("female:zero"), Map.of("female:zero", 0))), 0, 10);

        assertEquals(List.of("female:zero"), tagsOf(out));
    }

    /** 整个 weights 为 null（详情页没抓）→ 跳过过滤、全部保留 */
    @Test
    public void filter_skippedWhenWeightsMissingEntirely() {
        List<Merged> out = MangaEhTagMerge.merge(
                List.of(c(0.9, "female:a", "female:b")), 100, 10);

        assertEquals(List.of("female:a", "female:b"), tagsOf(out));
    }

    /** weights 存在但没覆盖这条标签 → 同样当「未知」保留，别按「缺项 = 0 分」剔除 */
    @Test
    public void filter_skippedForTagAbsentFromWeights() {
        List<Merged> out = MangaEhTagMerge.merge(List.of(
                new Candidate(0.9, List.of("female:a", "female:b"),
                        Map.of("female:a", 10))), 3, 10);

        assertTrue(tagsOf(out).contains("female:b"), "没抓到权重的标签不能被误删");
    }

    /** 候选的 tags 为 null（接口没给标签）不炸、也不产出标签 */
    @Test
    public void merge_toleratesNullTagList() {
        List<Merged> out = MangaEhTagMerge.merge(
                List.of(new Candidate(0.9, null, null), c(0.8, "female:a")), 0, 10);

        assertEquals(List.of("female:a"), tagsOf(out));
    }

    /** 截断到 maxTags，且截的是排序后的尾巴 */
    @Test
    public void merge_truncatesToMaxTags() {
        List<Merged> out = MangaEhTagMerge.merge(
                List.of(c(0.9, "female:a", "female:b", "female:c")), 0, 2);

        assertEquals(2, out.size());
        assertEquals(List.of("female:a", "female:b"), tagsOf(out));
    }

    /** 没有候选 / 标签全被权重过滤掉 → 空表，不是 null */
    @Test
    public void merge_emptyInputGivesEmptyList() {
        assertTrue(MangaEhTagMerge.merge(List.of(), 0, 10).isEmpty());
        assertTrue(MangaEhTagMerge.merge(List.of(
                new Candidate(0.9, List.of("female:a"), Map.of("female:a", 1))), 99, 10).isEmpty());
    }
}
