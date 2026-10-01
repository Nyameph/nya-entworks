package io.github.Nyameph.nyaentworks.manga.consts;

import java.util.List;

/**
 * e-hentai 标签的命名空间，以及同一个中文名落在多个命名空间时的取舍顺序。
 *
 * <p>不做成枚举：{@code manga_tag.namespace} 存的是词典给什么就是什么的自由字符串，
 * 换一版词典就可能冒出个新的，用有序 List 兜底比枚举 {@code valueOf} 当场抛异常耐变。
 *
 * <p><b>为什么需要优先级</b>：归档目录名的 {@code 【…】} 块里只有一个中文词，而词典里
 * 同一个中文可能分属多个命名空间（{@code female 失明} 与 {@code male 失明} 是两条）。
 * 解析目录名时必须确定性地挑一条，否则同一个目录名两次同步可能挂到不同标签上，
 * 表现为标签自己会变——那是最难查的一类现象。
 *
 * <p>实测 {@code data/ehentai-tags.csv}（约 1370 条）里的同名碰撞几乎只发生在
 * female/male 之间，个别带 mixed，只有 {@code Cosplay} 一条跨到 reclass。所以下面这个
 * 顺序真正起作用的只有前三项，其余是兜底占位：
 * <ul>
 *   <li>{@code female} 排头 —— 它是 EhTagTranslation 的默认命名空间，中文译名最全，
 *       个人归档的目录标签绝大多数是女性向读法；
 *   <li>{@code male} 次之；{@code mixed}（混合性别群像）比单一性别读法偏门，放第三；
 *   <li>{@code other}（全彩/无修正…）、{@code location}、{@code language} 是媒介与元信息，
 *       不跟内容标签争；
 *   <li>{@code reclass}（同人志/画师CG…）最元，垫底 —— 正好让 {@code Cosplay} 撞车时 female 赢。
 * </ul>
 */
public final class MangaTagNamespace {

    private MangaTagNamespace() {
    }

    /** 优先级从高到低。不在表里的未知命名空间一律排最后，见 {@link #rank} */
    public static final List<String> PRIORITY = List.of(
            "female", "male", "mixed", "other", "location", "language", "reclass");

    /**
     * 命名空间的优先级序号，越小越优先。
     * <p>未知命名空间（含 {@code null}）返回 {@link Integer#MAX_VALUE}：排到最后，但仍是个
     * 确定的值 —— 不会因为数据库这次返回的顺序不同，就在两次同步之间挑出不同的标签。
     */
    public static int rank(String namespace) {
        int index = namespace == null ? -1 : PRIORITY.indexOf(namespace);
        return index < 0 ? Integer.MAX_VALUE : index;
    }
}
