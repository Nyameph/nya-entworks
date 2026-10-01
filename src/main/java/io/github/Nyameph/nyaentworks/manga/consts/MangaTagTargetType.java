package io.github.Nyameph.nyaentworks.manga.consts;

/**
 * 标签可以挂到哪类实体上。
 * <p>{@code manga_tag_ref} 用 {@code (target_type, target_id)} 而非外键指向目标，
 * 因此新增一类可打标签的实体只需在这里加枚举值，不必建表。
 */
public enum MangaTagTargetType {

    /** 归档目录，即 {@code manga_archive_unit} */
    ARCHIVE_UNIT,

    /** 单本漫画，即 {@code manga_data} */
    MANGA_DATA,
}
