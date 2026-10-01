package io.github.Nyameph.nyaentworks.common.fileop.consts;

/**
 * 这条改动流水属于哪个模块。存 {@code name()}（见 {@code file_op_log.module}）。
 *
 * <p>三个值就是项目的三个业务模块，页面上按其筛选。它不代表「谁调用」，只代表
 * 「动的是谁的文件」—— 漫画的运维脚本动的是漫画的盘，所以 {@code module} 是
 * {@code MANGA} 而 {@code source} 是 {@code SCRIPT}，两者正交。
 */
public enum FileOpModule {

    /** 漫画：只记对象是目录的动作（作者目录 / 合集目录 / 单本漫画） */
    MANGA,

    /** 填词歌曲：一组文件（视频 / 音频 / 歌词）的搬动与删除 */
    SONG,

    /** 喊麦：与歌曲同一套原语，只是根目录不同 */
    SHOUT,
}
