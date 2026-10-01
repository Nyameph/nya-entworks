package io.github.Nyameph.nyaentworks.manga;

import org.junit.jupiter.api.Test;
import io.github.Nyameph.nyaentworks.manga.util.MangaEhLocalDb;
import io.github.Nyameph.nyaentworks.manga.util.MangaTitleMatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code MangaEhLocalDb.metaScore} 的纯函数测试：不依赖 Spring、数据库与 eh-gallery.db。
 * <p>要守住的性质有三条：两档评分带<b>不重叠</b>（档位序不被相似度盖过去）、
 * 带内对相似度<b>单调递增</b>（分数是可比的测量值）、且都<b>低于包含档</b> 0.85。
 */
class MangaEhMetaScoreTest {

    private static final double EPS = 1e-9;

    /** 相似度取下限时落在各自带的下沿：META 0.60、STRONG 0.70 */
    @Test
    void floorSimHitsBandBottom() {
        assertEquals(0.60, MangaEhLocalDb.metaScore(MangaEhLocalDb.SIM_MIN_META, false), EPS);
        assertEquals(0.70, MangaEhLocalDb.metaScore(MangaEhLocalDb.SIM_MIN_META, true), EPS);
    }

    /** 相似度趋近包含档（0.85）时趋近带的上界，但取不到 —— 真到 0.85 就记 CONTAINS 档了 */
    @Test
    void nearContainsApproachesCeilingWithoutReaching() {
        double nearTop = MangaTitleMatcher.SCORE_CONTAINS - 1e-6;
        double meta = MangaEhLocalDb.metaScore(nearTop, false);
        double strong = MangaEhLocalDb.metaScore(nearTop, true);
        assertTrue(meta < MangaEhLocalDb.SCORE_LOCAL_META, "META 不该达到上界: " + meta);
        assertTrue(strong < MangaEhLocalDb.SCORE_LOCAL_META_STRONG, "STRONG 不该达到上界: " + strong);
        assertEquals(MangaEhLocalDb.SCORE_LOCAL_META, meta, 1e-5);
        assertEquals(MangaEhLocalDb.SCORE_LOCAL_META_STRONG, strong, 1e-5);
    }

    /** 带内单调递增：这是「分数是测量值而非档位标签」的全部意义所在 */
    @Test
    void monotonicInSimilarity() {
        double prevMeta = -1;
        double prevStrong = -1;
        for (double sim = MangaEhLocalDb.SIM_MIN_META; sim < MangaTitleMatcher.SCORE_CONTAINS; sim += 0.01) {
            double meta = MangaEhLocalDb.metaScore(sim, false);
            double strong = MangaEhLocalDb.metaScore(sim, true);
            assertTrue(meta > prevMeta, "META 应随相似度递增, sim=" + sim);
            assertTrue(strong > prevStrong, "STRONG 应随相似度递增, sim=" + sim);
            prevMeta = meta;
            prevStrong = strong;
        }
    }

    /**
     * 两档评分带不重叠，且整体低于包含档 —— 档位序（EXACT &gt; CONTAINS &gt; STRONG &gt; META）
     * 必须压过带内的相似度差异，否则「标题很像的纯 meta」会排到「展会对上的 strong」前面。
     */
    @Test
    void bandsDoNotOverlapAndStayBelowContains() {
        double metaTop = MangaEhLocalDb.metaScore(MangaTitleMatcher.SCORE_CONTAINS, false);
        double strongBottom = MangaEhLocalDb.metaScore(MangaEhLocalDb.SIM_MIN_META, true);
        assertTrue(metaTop <= strongBottom, "META 带顶不该越过 STRONG 带底: " + metaTop + " vs " + strongBottom);
        double strongTop = MangaEhLocalDb.metaScore(MangaTitleMatcher.SCORE_CONTAINS, true);
        assertTrue(strongTop <= MangaTitleMatcher.SCORE_CONTAINS, "STRONG 带顶不该越过包含档: " + strongTop);
    }

    /** 越界入参被夹住，不算出带外分数（match 的门槛已保证不越界，这里防的是以后别处直接调用） */
    @Test
    void outOfRangeSimIsClamped() {
        assertEquals(0.60, MangaEhLocalDb.metaScore(0.0, false), EPS);
        assertEquals(0.60, MangaEhLocalDb.metaScore(-5, false), EPS);
        assertEquals(MangaEhLocalDb.SCORE_LOCAL_META, MangaEhLocalDb.metaScore(1.0, false), EPS);
        assertEquals(MangaEhLocalDb.SCORE_LOCAL_META_STRONG, MangaEhLocalDb.metaScore(99, true), EPS);
    }
}
