/**
 * 异步任务的通用轮询与渲染。
 *
 * 后端约定：耗时端点现在返回 task id（不再是业务结果），前端轮询 GET /api/tasks/{id}
 * 直到终态，再从 task.result 里拿业务结果接着走。任务落库、跨会话保留，所以
 * 「刷新会丢进度」这句话已经不成立了。
 *
 * manganew.js 原先的 pollTask / renderTask 就是这一套，抽到这里供三个模块共用。
 */
const TaskPoll = (() => {

    const TERMINAL = new Set(['DONE', 'FAILED', 'INTERRUPTED']);

    /**
     * 轮询一个任务直到终态。
     *
     * opts:
     *   pollMs   轮询间隔，默认 1000
     *   onTick(task)      每次拉到的任务快照（含 RUNNING 那几次）
     *   onResult(result)  终态为 DONE 时调用；返回 true 表示已处理（比如弹了二次确认），
     *                     跳过默认的「处理完成」提示
     *   onFail(error)     终态为 FAILED / INTERRUPTED 时调用
     *
     * 返回一个 stop()，页面切走时调用可停掉轮询。
     */
    function wait(taskId, opts = {}) {
        const {pollMs = 1000, onTick, onResult, onFail} = opts;

        let stopped = false;
        let timer = null;

        const tick = async () => {
            let task;
            try {
                task = await Api.get('/api/tasks/' + taskId);
            } catch (e) {
                if (onTick) {
                    onTick({status: 'FAILED', error: e.message});
                }
                stop();
                if (onFail) {
                    onFail(e.message);
                }
                return;
            }
            if (onTick) {
                onTick(task);
            }
            if (!TERMINAL.has(task.status)) {
                return;
            }
            stop();
            if (task.status === 'DONE') {
                const handled = onResult ? (await onResult(task.result) === true) : false;
                if (!handled) {
                    UI.ok('处理完成');
                }
            } else if (onFail) {
                onFail(task.error || ('任务未完成：' + task.status));
            }
        };

        const stop = () => {
            stopped = true;
            if (timer) {
                clearInterval(timer);
                timer = null;
            }
        };

        tick();
        timer = setInterval(() => {
            if (stopped) {
                stop();
            } else {
                tick();
            }
        }, pollMs);
        return stop;
    }

    /**
     * 提交 + 轮询一体。submitFn 应返回 taskId（通常是 Api.post 的返回值）。
     * 其余 opts 同 {@link #wait}。
     */
    async function submitAndWait(submitFn, opts = {}) {
        const taskId = await submitFn();
        return wait(taskId, opts);
    }

    /**
     * 渲染任务卡（进度条 + 消息 + 日志），复用于各页面的内嵌进度区。
     */
    function renderTask(task) {
        const pct = task.total ? Math.round(task.done * 100 / task.total) : 0;
        const statusText = {
            PENDING: '排队中', RUNNING: '进行中', DONE: '已完成',
            FAILED: '失败', INTERRUPTED: '已中断'
        }[task.status] || task.status;
        let html = '<div class="card"><h2>' + UI.esc(task.taskName || task.name)
            + ' <span class="muted small">' + UI.esc(statusText) + '</span></h2>'
            + '<div class="progress"><div style="width:' + pct + '%"></div></div>'
            + '<p class="small muted">' + UI.esc(task.message || '')
            + (task.total ? '（' + task.done + '/' + task.total + '）' : '') + '</p>';
        if (task.error) {
            html += '<div class="hint hint-err">' + UI.esc(task.error) + '</div>';
        }
        if (task.logs && task.logs.length) {
            html += '<div class="task-logs">' + task.logs.map(UI.esc).join('\n') + '</div>';
        }
        return html + '</div>';
    }

    return {wait, submitAndWait, renderTask};
})();
