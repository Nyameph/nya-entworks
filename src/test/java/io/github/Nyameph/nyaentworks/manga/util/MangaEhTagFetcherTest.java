package io.github.Nyameph.nyaentworks.manga.util;

import org.junit.jupiter.api.Test;
import io.github.Nyameph.nyaentworks.manga.util.MangaEhTagFetcher.Classified;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MangaEhTagFetcher#classify} 的语义大类与确定性判定，纯函数不连库。
 *
 * <p>重点是「无法判断大类」的两条兜底分支：female/male 没命中映射、other 落默认，
 * 都得标 {@code confident=false} 让前端提示用户手动填；其余都得是确定的。
 */
public class MangaEhTagFetcherTest {

    @Test
    public void female_categoryMapHit() {
        Classified c = MangaEhTagFetcher.classify("female", "身体", "some tag");
        assertEquals(MangaEhTagFetcher.CAT_CHARACTER, c.majorCategory());
        assertTrue(c.confident());
    }

    @Test
    public void female_tagOverrideHit() {
        // crying 是「头部 > 眼睛」里的行为标签，覆盖表判成玩法
        Classified c = MangaEhTagFetcher.classify("female", "头部 > 眼睛", "crying");
        assertEquals(MangaEhTagFetcher.CAT_PLAY, c.majorCategory());
        assertTrue(c.confident());
    }

    @Test
    public void female_clothing() {
        Classified c = MangaEhTagFetcher.classify("male", "服装", "dress");
        assertEquals(MangaEhTagFetcher.CAT_CLOTHING, c.majorCategory());
        assertTrue(c.confident());
    }

    /** 兜底分支：female/male 的 category 不在映射表、tag 也不在覆盖表 → 不确定 */
    @Test
    public void female_unknownCategory_isUnconfident() {
        Classified c = MangaEhTagFetcher.classify("female", "某个没收录的分类", "whatever");
        assertEquals(MangaEhTagFetcher.CAT_CHARACTER, c.majorCategory());
        assertFalse(c.confident());
    }

    @Test
    public void location_language_reclass_areConfident() {
        assertEquals(MangaEhTagFetcher.CAT_LOCATION,
                MangaEhTagFetcher.classify("location", "", "japan").majorCategory());
        assertEquals(MangaEhTagFetcher.CAT_LANGUAGE,
                MangaEhTagFetcher.classify("language", "", "japanese").majorCategory());
        assertEquals(MangaEhTagFetcher.CAT_WORK_TYPE,
                MangaEhTagFetcher.classify("reclass", "", "cosplay").majorCategory());
        assertTrue(MangaEhTagFetcher.classify("location", "", "japan").confident());
    }

    @Test
    public void mixed_relation_vs_play() {
        Classified rel = MangaEhTagFetcher.classify("mixed", "年龄", "kodomo");
        assertEquals(MangaEhTagFetcher.CAT_RELATION, rel.majorCategory());
        assertTrue(rel.confident());
        Classified play = MangaEhTagFetcher.classify("mixed", "别的", "x");
        assertEquals(MangaEhTagFetcher.CAT_PLAY, play.majorCategory());
        assertTrue(play.confident());
    }

    @Test
    public void other_known_vs_unknown() {
        Classified tool = MangaEhTagFetcher.classify("other", "工具", "dildo");
        assertEquals(MangaEhTagFetcher.CAT_PLAY, tool.majorCategory());
        assertTrue(tool.confident());
        // other 兜底：作品类型，但分不出来 → 不确定
        Classified unknown = MangaEhTagFetcher.classify("other", "没见过的分类", "x");
        assertEquals(MangaEhTagFetcher.CAT_WORK_TYPE, unknown.majorCategory());
        assertFalse(unknown.confident());
    }
}
