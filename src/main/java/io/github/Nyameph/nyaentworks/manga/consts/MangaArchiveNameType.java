package io.github.Nyameph.nyaentworks.manga.consts;

/**
 * 归档别名的种类。
 * <p>归档目录名 {@code [社团 (作者甲、作者乙)]} 会被拆成一个 {@link #GROUP}
 * 与多个 {@link #ARTIST} 别名；{@code [作者]} 形态只产出 {@link #ARTIST}。
 * <p>两者共享同一命名空间做重名检测 —— 原先
 * {@code readArchiveGroupArtists} 里的 {@code allPathMap} 就是这个语义。
 */
public enum MangaArchiveNameType {

    /** 社团名 */
    GROUP,

    /** 作者名 */
    ARTIST,
}
