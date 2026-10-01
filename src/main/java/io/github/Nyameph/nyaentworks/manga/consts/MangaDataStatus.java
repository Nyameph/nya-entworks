package io.github.Nyameph.nyaentworks.manga.consts;

/**
 * 单本漫画的状态，对应文档 4.1 的状态机。
 * <p><b>「新漫画」不在这个枚举里</b>，因为新漫画不入库：{@code F:\NetdiskDownload\#待看}
 * 与 {@code #待看合集} 下的目录每次都现扫，目录名、评分、标签在那个阶段还在变，
 * 存一份就要维护一致性。落库发生在「存储」那一刻 —— 那时漫画已经压缩过、
 * 名字定下来了，才成为 {@link #ARCHIVED} 或 {@link #UNARCHIVED}。
 */
public enum MangaDataStatus {

    /** 存进了归档目录，{@code archive_unit_id} 指向它 */
    ARCHIVED,

    /** 存进了 {@code #待整理散漫} 下的评分目录，等作者攒够本数再建归档目录 */
    UNARCHIVED,

    /**
     * 上次扫描时目录已不在磁盘上。
     * <p>与归档目录的 {@code MISSING} 同样只标不删，为的是保住评分与标签 ——
     * 目录可能只是被临时移走（外挂盘没挂上）。
     */
    MISSING,
}
