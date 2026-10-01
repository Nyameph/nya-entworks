package io.github.Nyameph.nyaentworks.manga.util;

import org.junit.jupiter.api.Test;
import io.github.Nyameph.nyaentworks.manga.util.MangaEhTagMerge.Candidate;
import io.github.Nyameph.nyaentworks.manga.util.MangaEhTagMerge.Merged;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MangaEhTagMerge} 的<b>命名空间过滤</b>（设计 §6.5 / §11 第 5 条），纯函数不连库。
 *
 * <p>只放行内容类 6 个命名空间，其余一律丢弃。这不是洁癖：eh 的 namespace 全集远大于本项目的
 * 6 个内容类，其中 {@code artist} / {@code group} / {@code parody} / {@code character}
 * 在本项目里是<b>目录名的权威</b>（见 {@code docs/实现指南} 的「文件系统是权威」）——
 * 让 eh 的这几类标签流进来，等于用外部数据去改目录名已经定下的信息，两边会互相打架。
 * {@code language} 则是元信息（语言不是内容标签），也不在这 6 个里。
 *
 * <p><b>与 {@link io.github.Nyameph.nyaentworks.manga.MangaTagNamespaceTest} 不是一回事</b>：
 * 那边管的是「同一个<b>中文名</b>落在多个命名空间时挑哪一条」（词典侧的确定性）；
 * 这里是「一个 eh 标签的命名空间<b>要不要被处理</b>」。类名只差一个 Eh，别混。
 */
public class MangaEhTagNamespaceTest {

    /** 过一遍合并（同一候选、同分同频，所以顺带按标签名排了序）后留下的标签集 */
    private static Set<String> keptTags(String... tags) {
        return MangaEhTagMerge.merge(List.of(new Candidate(0.9, List.of(tags), null)), 0, 10)
                .stream().map(Merged::tag)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    /** 内容类 6 个全部放行 */
    @Test
    public void keeps_allContentNamespaces() {
        assertEquals(Set.of("female:a", "male:b", "mixed:c", "other:d", "location:e", "reclass:f"),
                keptTags("female:a", "male:b", "mixed:c", "other:d", "location:e", "reclass:f"));
    }

    /** {@code language} 不是内容标签，丢弃 —— 语言由别的字段表达 */
    @Test
    public void drops_language() {
        assertEquals(Set.of("female:keep"), keptTags("language:chinese", "female:keep"));
    }

    /** 目录名已定下这四类，eh 的对应标签丢弃，免得两边打架 */
    @Test
    public void drops_namespacesOwnedByFolderName() {
        assertEquals(Set.of("female:keep"),
                keptTags("artist:pandaboy", "group:xxx", "parody:yyy", "character:zzz",
                        "female:keep"));
    }

    /** 没有冒号的裸串（不是 {@code ns:tag}）丢弃，不能当默认命名空间收下 */
    @Test
    public void drops_tagWithoutNamespace() {
        assertTrue(keptTags("nocolon").isEmpty());
    }

    /** 冒号在开头（{@code :xxx}）也算切不出命名空间，丢弃 */
    @Test
    public void drops_tagWithLeadingColon() {
        assertTrue(keptTags(":xxx").isEmpty());
    }

    /** 命名空间大小写敏感 —— eh 的一律小写，靠精确匹配就够，不去猜 {@code Female:} 是不是 female */
    @Test
    public void namespaceMatch_isCaseSensitive() {
        assertTrue(keptTags("Female:a").isEmpty());
    }

    /**
     * 常量的内容本身就是规格：一旦有人把 {@code language} 或 {@code artist} 加进
     * {@link MangaEhTagFetcher#CONTENT_NAMESPACES}，上面的过滤会静默放宽，
     * 所以这里把「正好这 6 个」钉住。
     */
    @Test
    public void contentNamespaces_isExactlyTheSix() {
        Set<String> ns = MangaEhTagFetcher.CONTENT_NAMESPACES;
        assertEquals(Set.of("female", "male", "mixed", "other", "location", "reclass"), ns);
        assertEquals(6, ns.size());
        assertFalse(ns.contains("language"), "语言是元信息，不是内容标签");
        assertFalse(ns.contains("artist"), "artist 由目录名权威");
    }
}
