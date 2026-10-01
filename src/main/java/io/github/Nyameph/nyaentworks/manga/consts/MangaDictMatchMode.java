package io.github.Nyameph.nyaentworks.manga.consts;

/**
 * 词条匹配方式。逐条生效，与 {@link MangaDictType} 无关 ——
 * 同一类型下的词条可以各用不同的匹配方式。
 */
public enum MangaDictMatchMode {
    /** 全等，未指定匹配方式时的缺省值 */
    EXACT,
    /** 前缀，对应 {@code StringUtils.startsWithAny} */
    PREFIX,
    /** 后缀，对应 {@code StringUtils.endsWithAny} */
    SUFFIX,
    /** 包含，对应 {@code StringUtils.containsAny} */
    CONTAINS,
    /** 正则，整体 {@code matches} */
    REGEX,
}
