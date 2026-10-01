package io.github.Nyameph.nyaentworks.manga.consts;

/**
 * 漫画词典类型，只描述「这批词是干什么用的」，不描述怎么匹配。
 * <p>怎么匹配由每条 {@code MangaDictEntry} 自己的
 * {@link MangaDictMatchMode} 与 {@code ignoreCase} 决定：
 * 同一类型下允许混用全等、前后缀、包含、正则。例如 {@link #MODIFIER}
 * 里既有后缀词（{@code 汉化组}）、也有整词（{@code 无修正}）、
 * 也有特征词（{@code 新桥月白}）。
 */
public enum MangaDictType {

    /** 需整体移除的无用标签 */
    USELESS_TAG,

    /** 無修正关键词 */
    UNCENSORED,

    /** 需拆开括号的标签 */
    UNBOXING,

    /**
     * 修饰性标签：命中即从原名中摘出、留待拼装时追加到末尾。
     * <p>合并了原先的 {@code HANHUA_SUFFIX}（后缀）、
     * {@code REVERSE_KEY}（整词）、{@code HANHUA_LIKE}（特征词）三类，
     * 它们的区别本就只是匹配方式，语义完全一致。
     */
    MODIFIER,

    /** 原作名 */
    PARODY,

    /** 展会 */
    EXHIBIT,

    /** 杂志 */
    MAGAZINE,

    /** 取展会/原作时应跳过的括号内容 */
    IGNORE_CONTENT,
}
