package io.github.Nyameph.nyaentworks.task.consts;

/**
 * 异步任务状态。存 {@code name()}。
 * <p>比原先的内存任务（{@code RUNNING/DONE/FAILED}）多两个：
 * <ul>
 *   <li>{@link #PENDING} —— 每个模块一条单线程队列，排在别人后面的那段时间要能看见，
 *       否则页面上表现为「点了没反应」；</li>
 *   <li>{@link #INTERRUPTED} —— 进程被杀掉时任务停在半途。它不是失败（没人报错，是被打断的），
 *       也不能算完成，必须自成一态，页面才好据此只给这一类显示「重新执行」。</li>
 * </ul>
 */
public enum AsyncTaskStatus {

    /** 已提交，在队列里等前面的任务跑完 */
    PENDING,

    /** 正在执行 */
    RUNNING,

    /** 正常跑完，结果在 {@code result_json} */
    DONE,

    /** 任务体抛了异常，原因在 {@code error} */
    FAILED,

    /** 进程重启时残留的 PENDING / RUNNING。不自动重跑，由人在任务页决定 */
    INTERRUPTED;

    /** 终态：不会再变了，可以从内存热态里摘掉、可以被历史清理删掉 */
    public boolean isFinished() {
        return this == DONE || this == FAILED || this == INTERRUPTED;
    }
}
