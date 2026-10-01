package io.github.Nyameph.nyaentworks.task.consts;

/**
 * 任务是怎么来的。存 {@code name()}。
 * <p>区分它是为了让任务页能标出「这条是重跑出来的」，配合 {@code rerun_from_id}
 * 一路追回最初那次失败 —— 排查「同一个目录为什么被搬了两次」时要的就是这条链。
 */
public enum AsyncTaskSource {

    /** 页面上正常触发的操作 */
    SUBMIT,

    /** 任务页上点「重新执行」产生的 */
    RERUN,
}
