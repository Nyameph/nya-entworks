package io.github.Nyameph.nyaentworks.song.consts;

/**
 * 归档歌曲合并条目的状态。
 * <p>与漫画 {@code MangaDataStatus.MISSING} 同款立场：磁盘删了<b>只标不删</b>，
 * 为的是保住 {@code default_rate} —— 目录可能只是被临时移走（外挂盘没挂上）。
 * 下次同步同一 {@code (partition, merge_key)} 再出现时 update 回 ACTIVE。
 */
public enum SongStatus {

    /** 磁盘上存在，正常 */
    ACTIVE,

    /** 上次同步时磁盘上已没有对应文件，只标不删 */
    MISSING,
}
