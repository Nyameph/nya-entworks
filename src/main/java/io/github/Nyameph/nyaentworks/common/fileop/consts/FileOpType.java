package io.github.Nyameph.nyaentworks.common.fileop.consts;

/**
 * 磁盘改动的<b>机械类型</b>。存 {@code name()}（见 {@code file_op_log.op_type}）。
 *
 * <p>它决定哪一列路径会是空的：{@link #MOVE} 两列都有、{@link #DELETE} 只有改前、
 * {@link #WRITE} 只有改后。页面上「改前 → 改后」那一格就是按它画的。
 *
 * <p><b>刻意没有 {@code MKDIR}</b>：建目录是搬动或写入的副产品（{@code GroupFileOps}
 * 里那条 {@code Files.createDirectories}），单独立一种类型只会把列表淹掉。
 *
 * <p>注意 {@link #WRITE} 不是「编辑了文件内容」而是「凭空多了一个文件」——
 * 本功能记的是归属变化（谁被搬到哪去了），不记文件内容怎么变的。
 */
public enum FileOpType {

    /** 搬动 / 改名：改前改后都有，且是同一份数据 */
    MOVE,

    /** 删除：只有改前路径 */
    DELETE,

    /** 新建落盘：只有改后路径（下载到的原曲、解出来的音频、脚本写的报告） */
    WRITE,
}
