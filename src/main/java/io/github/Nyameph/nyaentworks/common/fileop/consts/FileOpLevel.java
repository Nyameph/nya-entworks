package io.github.Nyameph.nyaentworks.common.fileop.consts;

/**
 * 被改动的那个路径在磁盘上处于哪一层。存 {@code name()}（见 {@code file_op_log.unit_level}）。
 *
 * <p>这一列是「只看大事」的开关：漫画整体移动一个作者文件夹只有一条
 * {@link #AUTHOR}，而批量存储 30 本新漫画会有 30 条 {@link #SINGLE}，
 * 页面上筛掉 {@code SINGLE} 就只剩「结构性的改动」。
 *
 * <p><b>为什么漫画要标层级、歌曲只要 {@link #GROUP}</b>：歌曲的一组文件本来就是
 * 一个整体（{@code mainName} 那一组），没有更大的层级；漫画则至少有「单本 /
 * 合集 / 作者」三层，不标就分不出「这本书被改名了」和「整个作者的目录被改名了」。
 *
 * <p>{@link #OTHER} 是兜底，<b>它应该一直很少</b>；每出现一处都要能说出理由
 * （模板目录、下载落地的原曲、清出来的残留空目录都属于这一类）。
 */
public enum FileOpLevel {

    /** 漫画的归档作者目录（一次 {@code Files.move} 动整棵树） */
    AUTHOR,

    /** 漫画的合辑目录（整份合集归档） */
    COLLECTION,

    /** 单本漫画（目录或 cbz） */
    SINGLE,

    /** 歌曲 / 喊麦的一组文件（一个 {@code mainName} 的全部版本） */
    GROUP,

    /** 其它：配置、模板目录、下载落地的原曲、残留空目录等 */
    OTHER,
}
