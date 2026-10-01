package io.github.Nyameph.nyaentworks.manga.consts;

/**
 * 漫画的评分是自己的还是继承来的。
 * <p>需求要求区分「单独打分」与「继承作者层」（{@code 设计概述.md}：
 * 「漫画若未单独打分或打标签，则直接采用作者层的打分和标签，这种需要有标记与单独打分的区分」）。
 * <p>标签没有对应字段，走的是另一套约定：漫画侧没有 {@code MANGA_DATA} 标签关联即视为
 * 继承归档目录的标签，编辑过就算它有自己的。见 {@code 页面设计方案.md} 附三。
 */
public enum MangaScoreSource {

    /** 单独打的分 */
    SELF,

    /**
     * 继承所属归档目录的分。
     * <p>此时 {@code score} 仍冗余存一份便于查询与排序，但以
     * {@code archive_unit_id} 指向的 unit 为准 —— 归档目录换了评分分区，
     * 这一份就过期了。
     */
    INHERIT_ARCHIVE,
}
