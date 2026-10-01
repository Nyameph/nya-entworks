package io.github.Nyameph.nyaentworks.task.handler;

/**
 * 任务体拿到的句柄，用来报进度与写明细。
 * <p>三个方法都<b>只改内存</b>，由 {@code AsyncTaskService} 按节流间隔刷库 ——
 * 压缩一本几百页的漫画会逐张调 {@link #progress}，逐次 UPDATE 会把库打爆。
 */
public interface AsyncTaskContext {

    /**
     * 当前任务的 id。改动流水（{@code file_op_log.task_id}）等记录用途要用它把
     * 记录对回任务页那一条。
     *
     * @return 任务 id；不在任务框架里跑时为 {@code null}
     */
    default Long taskId() {
        return null;
    }

    /**
     * 报进度。
     *
     * @param total   总步数；数不出来时传 0，页面画成无百分比的进度条
     * @param message 当前在做什么，为 null 表示不改
     */
    void progress(int done, int total, String message);

    /** 只改「当前在做什么」，不动进度数字 */
    void message(String message);

    /**
     * 写一行明细。失败的文件、跳过的漫画都进这里，跑完能逐条核对。
     * <p>这是原先散在各处的 {@code System.out.println} 的去处 —— 落库之后，
     * 跑完关掉页面也还能翻回来。
     */
    void log(String line);

    /**
     * 执行过程中更新任务参数并<b>立即落库</b>。
     * <p>存储任务会先规范化改名（目录名变了），改名后把新路径写回参数，
     * 任务要是在后面断了、重跑时就能按新路径接着找，而不是拿改名前的老路径
     * 撞「目录不存在」。参数是重跑的关键依据，所以这条不走进度节流，即时写。
     */
    void updateParams(Object params);
}
