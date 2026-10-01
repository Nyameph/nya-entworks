package io.github.Nyameph.nyaentworks.manga;

import org.junit.jupiter.api.Test;
import io.github.Nyameph.nyaentworks.manga.consts.MangaTagNamespace;
import io.github.Nyameph.nyaentworks.manga.entity.MangaTag;
import io.github.Nyameph.nyaentworks.manga.service.MangaTagService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同一个中文名落在多个 e-hentai 命名空间时挑哪一条，见 {@link MangaTagNamespace}。
 *
 * <p>这件事必须是确定的：目录名的 {@code 【…】} 块里只有一个中文词，两次同步若挑出
 * 不同的标签，表现就是「标签自己会变」。所以这里重点验的是<b>与查询返回顺序无关</b>。
 *
 * <p>纯单元测试，不连库。
 */
public class MangaTagNamespaceTest {

    /** 优先级表里的命名空间按声明顺序递增 */
    @Test
    public void rank_followsDeclaredOrder() {
        for (int i = 1; i < MangaTagNamespace.PRIORITY.size(); i++) {
            String prev = MangaTagNamespace.PRIORITY.get(i - 1);
            String cur = MangaTagNamespace.PRIORITY.get(i);
            assertTrue(MangaTagNamespace.rank(prev) < MangaTagNamespace.rank(cur),
                    prev + " 应优先于 " + cur);
        }
    }

    /** female 是默认命名空间，排在最前 */
    @Test
    public void rank_femaleFirst() {
        assertEquals(0, MangaTagNamespace.rank("female"));
        assertTrue(MangaTagNamespace.rank("female") < MangaTagNamespace.rank("male"));
        assertTrue(MangaTagNamespace.rank("male") < MangaTagNamespace.rank("mixed"));
    }

    /** 未知命名空间与 null 排最后，但仍是确定值 —— 不随查询顺序漂移 */
    @Test
    public void rank_unknownLast() {
        assertEquals(Integer.MAX_VALUE, MangaTagNamespace.rank("brandNewNamespace"));
        assertEquals(Integer.MAX_VALUE, MangaTagNamespace.rank(null));
        assertTrue(MangaTagNamespace.rank("reclass") < MangaTagNamespace.rank("brandNewNamespace"));
    }

    /** 同名中文最常见的碰撞：female / male，取 female */
    @Test
    public void pickDictTag_femaleBeatsMale() {
        MangaTag female = tag("失明", "female");
        MangaTag male = tag("失明", "male");
        assertSame(female, MangaTagService.pickDictTag(List.of(female, male)));
    }

    /** 结果与入参顺序无关：数据库返回顺序变了也得挑出同一条 */
    @Test
    public void pickDictTag_orderIndependent() {
        MangaTag female = tag("失明", "female");
        MangaTag male = tag("失明", "male");
        assertSame(female, MangaTagService.pickDictTag(List.of(female, male)));
        assertSame(female, MangaTagService.pickDictTag(List.of(male, female)));
    }

    /** 词典里唯一跨到 reclass 的 Cosplay：三条候选里仍取 female */
    @Test
    public void pickDictTag_cosplayAcrossThreeNamespaces() {
        MangaTag female = tag("Cosplay", "female");
        MangaTag male = tag("Cosplay", "male");
        MangaTag reclass = tag("Cosplay", "reclass");
        assertSame(female, MangaTagService.pickDictTag(List.of(reclass, male, female)));
    }

    /** 已知命名空间永远赢未知命名空间 */
    @Test
    public void pickDictTag_knownBeatsUnknown() {
        MangaTag known = tag("某标签", "reclass");
        MangaTag unknown = tag("某标签", "brandNewNamespace");
        assertSame(known, MangaTagService.pickDictTag(List.of(unknown, known)));
    }

    /** 只有一条候选时原样返回 */
    @Test
    public void pickDictTag_single() {
        MangaTag only = tag("巨乳", "female");
        assertSame(only, MangaTagService.pickDictTag(List.of(only)));
    }

    /** 空集合与 null 都返回 null，交给调用方走「目录标签兜底」那条路 */
    @Test
    public void pickDictTag_emptyOrNull() {
        assertNull(MangaTagService.pickDictTag(List.of()));
        assertNull(MangaTagService.pickDictTag(null));
    }

    private static MangaTag tag(String tagName, String namespace) {
        MangaTag tag = new MangaTag();
        tag.setTagName(tagName);
        tag.setNamespace(namespace);
        tag.setSource(MangaTag.SOURCE_EHENTAI);
        return tag;
    }
}
