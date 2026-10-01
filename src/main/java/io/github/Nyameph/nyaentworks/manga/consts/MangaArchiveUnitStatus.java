package io.github.Nyameph.nyaentworks.manga.consts;

/**
 * 归档目录相对文件系统的状态。
 * <p>文件系统是权威，库是镜像。目录消失时标 {@link #MISSING} 而不删行，
 * 是为了保住挂在该 unit 上的 {@code manga_tag_ref}：目录可能只是被临时移走，
 * 删了标签关联就找不回来了。
 */
public enum MangaArchiveUnitStatus {

    /** 最近一次同步时目录存在 */
    ACTIVE,

    /** 最近一次同步时目录已不在扫描结果中 */
    MISSING,
}
