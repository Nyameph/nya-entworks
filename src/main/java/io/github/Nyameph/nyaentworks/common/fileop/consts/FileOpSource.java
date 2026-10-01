package io.github.Nyameph.nyaentworks.common.fileop.consts;

/**
 * 这次改动是谁发起的。存 {@code name()}（见 {@code file_op_log.source}）。
 *
 * <p>为什么必须是个可见的列：{@code script/} 下那几个运维脚本会真实改盘，而且
 * 常常是「批量、一次性、跑完就忘」—— 事后发现某个目录名字不对时，第一个要回答的
 * 问题就是「这是我点的，还是脚本干的」。
 *
 * <p>取值由<b>最外层</b>决定：同一个 Service 方法被页面和脚本两边调用时
 * （如 {@code SongResourceSearchService}），{@code batch(...)} 包在哪一侧，
 * 记录就是哪一侧的来源。
 */
public enum FileOpSource {

    /** 页面上点了按钮（同步执行） */
    PAGE,

    /** 异步任务框架里的任务（{@code task_id} 同时填上，可对回任务页那一条） */
    TASK,

    /** 运维脚本（{@code src/test/java/.../script} 下手工跑的类） */
    SCRIPT,

    /**
     * 落盘时没有活动的批次上下文 —— 也就是**调用方忘了包 {@code batch(...)}**。
     *
     * <p>有这一项是为了让那种情况<b>可见</b>而不是静默：记录照写（丢记录比标错来源更糟）、
     * 批次号各自新开一个，页面上的「来源」筛出这一档就是「忘了标注的地方」。
     * 它不该出现在正常运行里，出现了就是 bug。
     */
    UNKNOWN,
}
