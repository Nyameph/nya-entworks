package io.github.Nyameph.nyaentworks.common.fileop.consts;

/**
 * 这次落盘成没成。存 {@code name()}（见 {@code file_op_log.result}）。
 *
 * <p><b>失败也要记</b>：用户来翻记录的场景里，「我点了改名怎么没生效」和
 * 「这个文件被搬哪去了」是同一类问题。只记成功会把前者变成没有线索 ——
 * 而删除失败（文件被播放器占用）恰恰是最常见的一种。
 *
 * <p>失败时原因写 {@code file_op_log.detail}。注意 {@link #FAILED} 的两层含义：
 * 单条失败（这一条没搬成）与整批回滚（{@code GroupFileOps.moveAll} 中途失败会把
 * 已搬的搬回去）—— 回滚掉的那些会各留一条 {@code FAILED}，因为磁盘上它们确实动过又回来了。
 */
public enum FileOpResult {

    /** 落盘成功 */
    OK,

    /** 落盘失败或被回滚，原因在 {@code detail} */
    FAILED,
}
