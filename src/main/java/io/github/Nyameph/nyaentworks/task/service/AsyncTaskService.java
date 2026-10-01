package io.github.Nyameph.nyaentworks.task.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import io.github.Nyameph.nyaentworks.task.config.AsyncTaskProperties;
import io.github.Nyameph.nyaentworks.task.consts.AsyncTaskSource;
import io.github.Nyameph.nyaentworks.task.consts.AsyncTaskStatus;
import io.github.Nyameph.nyaentworks.task.entity.AsyncTask;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskContext;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskHandler;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskRegistry;
import io.github.Nyameph.nyaentworks.task.mapper.AsyncTaskMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import tools.jackson.databind.ObjectMapper;

/**
 * 异步任务的提交、执行、查询与重跑。
 *
 * <h2>内存是热态，库是落点</h2>
 * 运行中的任务在内存里维护进度（{@link Hot}），{@code progress()} 只改内存；按
 * {@link AsyncTaskProperties#getFlushIntervalMs()} 节流刷库，<b>状态跃迁则无条件刷</b>。
 * 压缩一本几百页的漫画会逐张报进度，逐次 UPDATE 会把库打爆。代价是任务跑崩时库里
 * 最多滞后一个刷新周期的进度数字 —— 而工作本身的结果在磁盘和库上，丢的只是进度条。
 *
 * <h2>每个模块一条单线程队列</h2>
 * 同模块串行，保住原先「不并发压缩 / 不同时往一个目录归档」的性质；跨模块并行，
 * 让歌曲同步不必排在一次分钟级的漫画压缩后面。
 *
 * <h2>被杀掉的任务不自动重跑</h2>
 * 见 {@link #recoverAndCleanup()}。
 */
@Service
@RequiredArgsConstructor
public class AsyncTaskService {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskService.class);

    private final AsyncTaskMapper taskMapper;
    private final AsyncTaskRegistry registry;
    private final AsyncTaskProperties properties;

    /**
     * 注入 Spring 自动配置的 Jackson 3 ObjectMapper，<b>不要自建</b>。
     * <p>{@code resultJson} 存进去再读出来要原样交给前端，而 HTTP 层就是这个 mapper
     * 序列化的 —— 两端同一个实例，往返才不走样。另见 {@code MangaStoreService} 的注释：
     * Boot 4 的默认 JSON 是 Jackson 3（{@code tools.jackson}），容器里<b>没有</b>
     * Jackson 2（{@code com.fasterxml}）的 ObjectMapper bean。
     */
    private final ObjectMapper objectMapper;

    /** 活跃任务（PENDING / RUNNING）的内存热态，终态即摘除 */
    private final Map<Long, Hot> hotTasks = new ConcurrentHashMap<>();

    /** module → 单线程队列，懒创建 */
    private final Map<String, ExecutorService> queues = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // 对外视图
    // ------------------------------------------------------------------

    /**
     * 一条任务的快照，前端轮询拿到的就是它。
     *
     * @param result DONE 时的结果。<b>读成通用 Map / List 树，不还原成具体类型</b> ——
     *               泛型擦除会让「反序列化回 record」在嵌套集合上翻车，而前端只是按名字
     *               读几个字段，通用树完全够用
     */
    public record TaskView(Long id, String taskType, String module, String taskName,
                           AsyncTaskStatus status, int done, int total,
                           String message, String error, Object result, List<String> logs,
                           AsyncTaskSource source, Long rerunFromId, boolean rerunnable,
                           LocalDateTime startTime, LocalDateTime endTime,
                           LocalDateTime createTime) {
    }

    /** 某个模块的队列状况，给页面提示「还有几个排在前面」 */
    public record ModuleStat(String module, long pending, long running) {
    }

    // ------------------------------------------------------------------
    // 提交
    // ------------------------------------------------------------------

    /** 用 handler 声明的默认任务名提交 */
    public Long submit(String type, Object params) {
        return submit(type, params, null);
    }

    /**
     * 提交一个任务。
     *
     * @param taskName 覆盖 handler 的默认任务名，为空则用默认的。传具体一点的更有用
     *                 （「存储 3 本新漫画」比「存储新漫画」好认）
     * @return 任务 id，前端拿它轮询
     */
    public Long submit(String type, Object params, String taskName) {
        return submit(type, params, taskName, AsyncTaskSource.SUBMIT, null);
    }

    private Long submit(String type, Object params, String taskName,
                        AsyncTaskSource source, Long rerunFromId) {
        AsyncTaskHandler handler = registry.require(type);
        if (params != null && !handler.paramType().isInstance(params)) {
            throw new IllegalArgumentException("任务 " + type + " 的参数类型不对：期望 "
                    + handler.paramType().getSimpleName()
                    + "，实际 " + params.getClass().getSimpleName());
        }

        AsyncTask row = new AsyncTask();
        row.setTaskType(type);
        row.setModule(handler.module());
        row.setTaskName(StringUtils.defaultIfBlank(taskName, handler.taskName()));
        row.setStatus(AsyncTaskStatus.PENDING);
        row.setParamsJson(params == null ? null : objectMapper.writeValueAsString(params));
        row.setProgressDone(0);
        row.setProgressTotal(0);
        row.setMessage("排队中…");
        row.setSource(source);
        row.setRerunFromId(rerunFromId);
        taskMapper.insert(row);

        Long id = row.getId();
        Hot hot = new Hot(row);
        hotTasks.put(id, hot);
        queue(handler.module()).submit(() -> execute(id, hot, handler, params));
        log.info("任务已提交 #{} {} ({})", id, row.getTaskName(), type);
        return id;
    }

    /** 每个模块一条单线程队列。守护线程：正常退出时不拦着 JVM，残留任务靠启动恢复兜住 */
    private ExecutorService queue(String module) {
        return queues.computeIfAbsent(module, m -> Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "task-" + m);
            thread.setDaemon(true);
            return thread;
        }));
    }

    // ------------------------------------------------------------------
    // 执行
    // ------------------------------------------------------------------

    private void execute(Long id, Hot hot, AsyncTaskHandler handler, Object params) {
        hot.status = AsyncTaskStatus.RUNNING;
        hot.message = "准备中…";
        hot.startTime = LocalDateTime.now();
        flush(hot, true);
        try {
            Object result = handler.run(params, hot);
            hot.status = AsyncTaskStatus.DONE;
            hot.message = StringUtils.defaultIfBlank(handler.summarizeResult(result), "完成");
            hot.resultJson = result == null ? null : objectMapper.writeValueAsString(result);
        } catch (Exception | LinkageError e) {
            log.error("任务失败 #{} {}", id, hot.taskName, e);
            hot.status = AsyncTaskStatus.FAILED;
            hot.message = "失败";
            hot.error = describe(e);
        } finally {
            hot.endTime = LocalDateTime.now();
            flush(hot, true);
            // 终态了，热态没有存在意义，查询走库
            hotTasks.remove(id);
        }
    }

    /**
     * 按 {@code GlobalExceptionHandler} 的同一套规则提取原因。
     * <p>后台线程抛的异常<b>不会</b>进 {@code @RestControllerAdvice}，所以那套「参数与状态
     * 问题原样透出、其余带上类名」的规则必须在这里再写一遍 —— 否则页面上看到的是堆栈类名。
     */
    private static String describe(Throwable e) {
        String message = StringUtils.defaultIfBlank(e.getMessage(), e.toString());
        if (e instanceof IllegalArgumentException || e instanceof IllegalStateException) {
            return message;
        }
        return e.getClass().getSimpleName() + ": " + message;
    }

    // ------------------------------------------------------------------
    // 刷库
    // ------------------------------------------------------------------

    /**
     * 把热态写回库。
     *
     * @param force true 表示状态跃迁，无条件写；false 则按节流间隔判，没到就跳过
     */
    private void flush(Hot hot, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - hot.lastFlushAt < properties.getFlushIntervalMs()) {
            return;
        }
        hot.lastFlushAt = now;
        try {
            AsyncTask row = new AsyncTask();
            row.setId(hot.id);
            row.setStatus(hot.status);
            row.setProgressDone(hot.done);
            row.setProgressTotal(hot.total);
            row.setMessage(StringUtils.abbreviate(hot.message, 500));
            row.setStartTime(hot.startTime);
            if (force) {
                // 日志整段落库只在跃迁点做：它是个不断变长的 MEDIUMTEXT，
                // 每两秒重写一遍纯属浪费
                row.setLogs(hot.joinLogs());
                row.setError(StringUtils.abbreviate(hot.error, 2000));
                row.setResultJson(hot.resultJson);
                row.setEndTime(hot.endTime);
            }
            taskMapper.updateById(row);
        } catch (Exception e) {
            // 刷库失败不能把任务本身带崩 —— 进度是附属品，工作还得往下跑
            log.warn("任务 #{} 进度刷库失败", hot.id, e);
        }
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** 单条任务。活跃的读内存（进度实时），其余读库 */
    public TaskView get(Long id) {
        Hot hot = hotTasks.get(id);
        if (hot != null) {
            return hot.view();
        }
        AsyncTask row = taskMapper.selectById(id);
        if (row == null) {
            throw new IllegalArgumentException("任务不存在：" + id + "。它可能已被历史清理删掉");
        }
        return toView(row);
    }

    /**
     * 任务列表，新的在前。
     * <p>一次 DB 查询取最近若干条，再用内存热态<b>覆盖</b>其中活跃行的进度 ——
     * 既拿到持久化的历史，又拿到亚秒级的实时进度，不用维护两套拼装逻辑。
     */
    public List<TaskView> list(String module, AsyncTaskStatus status) {
        List<AsyncTask> rows = taskMapper.selectList(Wrappers.<AsyncTask>lambdaQuery()
                .eq(StringUtils.isNotBlank(module), AsyncTask::getModule, module)
                .eq(status != null, AsyncTask::getStatus, status)
                .orderByDesc(AsyncTask::getId)
                .last("limit " + properties.getListLimit()));
        List<TaskView> views = new ArrayList<>(rows.size());
        for (AsyncTask row : rows) {
            Hot hot = hotTasks.get(row.getId());
            views.add(hot != null ? hot.view() : toView(row));
        }
        return views;
    }

    /** 按模块的排队 / 运行条数。库里查而不是数热态：热态只有本进程的，库是全的 */
    public List<ModuleStat> summary() {
        List<AsyncTask> rows = taskMapper.selectList(Wrappers.<AsyncTask>lambdaQuery()
                .select(AsyncTask::getModule, AsyncTask::getStatus)
                .in(AsyncTask::getStatus, AsyncTaskStatus.PENDING, AsyncTaskStatus.RUNNING));
        Map<String, long[]> counts = new LinkedHashMap<>();
        for (AsyncTask row : rows) {
            long[] pair = counts.computeIfAbsent(row.getModule(), k -> new long[2]);
            if (row.getStatus() == AsyncTaskStatus.PENDING) {
                pair[0]++;
            } else {
                pair[1]++;
            }
        }
        return counts.entrySet().stream()
                .map(e -> new ModuleStat(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .toList();
    }

    private TaskView toView(AsyncTask row) {
        Object result = null;
        if (StringUtils.isNotBlank(row.getResultJson())) {
            try {
                result = objectMapper.readValue(row.getResultJson(), Object.class);
            } catch (Exception e) {
                log.warn("任务 #{} 的结果 JSON 读不回来", row.getId(), e);
            }
        }
        List<String> logs = StringUtils.isBlank(row.getLogs())
                ? List.of() : List.of(row.getLogs().split("\n"));
        return new TaskView(row.getId(), row.getTaskType(), row.getModule(), row.getTaskName(),
                row.getStatus(), orZero(row.getProgressDone()), orZero(row.getProgressTotal()),
                row.getMessage(), row.getError(), result, logs,
                row.getSource(), row.getRerunFromId(), rerunnable(row),
                row.getStartTime(), row.getEndTime(), row.getCreateTime());
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 能不能重跑。
     * <p>只有终态才给按钮（跑着的重跑没有意义），且这类任务的 handler 还得在
     * —— 旧版本留下的 type 现在可能已经没有实现了。
     */
    private boolean rerunnable(AsyncTask row) {
        if (row.getStatus() == null || !row.getStatus().isFinished()) {
            return false;
        }
        try {
            registry.require(row.getTaskType());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 重跑
    // ------------------------------------------------------------------

    /**
     * 重新执行一条任务：<b>复用原行</b>，把它重置成 PENDING 再投进队列，不另起一行。
     * <p>这样任务列表里永远是同一条，这次跑成 DONE 就是解决了、又 FAILED 就是没解决，
     * 一眼能看出结果，也不会因为反复点「重新执行」攒出一串任务。
     * <p>「从头再跑」而不是断点续传：任务体里那些耗时的改名/压缩/搬家会按最新参数
     * 重新过一遍（参数可能在执行中被 {@code updateParams} 更新过）。
     *
     * @return 原任务的 id（没有变化）
     */
    public Long rerun(Long id) {
        AsyncTask row = taskMapper.selectById(id);
        if (row == null) {
            throw new IllegalArgumentException("任务不存在：" + id + "。它可能已被历史清理删掉");
        }
        if (row.getStatus() != null && !row.getStatus().isFinished()) {
            throw new IllegalStateException("任务 #" + id + " 还没结束（"
                    + row.getStatus() + "），不用重新执行");
        }
        AsyncTaskHandler handler = registry.require(row.getTaskType());

        Object params = null;
        if (StringUtils.isNotBlank(row.getParamsJson())) {
            try {
                params = objectMapper.readValue(row.getParamsJson(), handler.paramType());
            } catch (Exception e) {
                // 枚举加了新值、参数 record 改了组件，旧 JSON 就读不回来了。
                // 说清楚是「参数失效」而不是抛一个 Jackson 的内部异常
                throw new IllegalArgumentException("任务 #" + id + " 的参数已失效，无法重新执行："
                        + e.getMessage() + "。请回到原来的页面重新操作一次", e);
            }
        }

        // 原子重置：只在仍是终态时把它翻回 PENDING。并发连点时只有一条 UPDATE 能命中，
        // 另一条 affected=0，据此报「已经在重跑」——这是防重复提交的权威兜底。
        // LambdaUpdateWrapper 的 set 能写 null（生成 SET xx = NULL），
        // 用来清掉上次的结果/错误/日志/起止时间
        int updated = taskMapper.update(null, Wrappers.<AsyncTask>lambdaUpdate()
                .eq(AsyncTask::getId, id)
                .in(AsyncTask::getStatus, AsyncTaskStatus.DONE,
                        AsyncTaskStatus.FAILED, AsyncTaskStatus.INTERRUPTED)
                .set(AsyncTask::getStatus, AsyncTaskStatus.PENDING)
                .set(AsyncTask::getSource, AsyncTaskSource.RERUN)
                .set(AsyncTask::getProgressDone, 0)
                .set(AsyncTask::getProgressTotal, 0)
                .set(AsyncTask::getMessage, "排队中…")
                .set(AsyncTask::getError, null)
                .set(AsyncTask::getResultJson, null)
                .set(AsyncTask::getLogs, null)
                .set(AsyncTask::getStartTime, null)
                .set(AsyncTask::getEndTime, null)
                .set(AsyncTask::getUpdateTime, LocalDateTime.now()));
        if (updated == 0) {
            throw new IllegalStateException("任务 #" + id + " 已经在重新执行，别重复点");
        }

        // 热态用重置后的行。params 已在上面反序列化（含执行中 updateParams 过的最新值）
        AsyncTask reset = new AsyncTask();
        reset.setId(id);
        reset.setTaskType(row.getTaskType());
        reset.setModule(row.getModule());
        reset.setTaskName(row.getTaskName());
        reset.setSource(AsyncTaskSource.RERUN);
        reset.setCreateTime(row.getCreateTime());
        Hot hot = new Hot(reset);
        hotTasks.put(id, hot);
        final Object runParams = params;
        queue(handler.module()).submit(() -> execute(id, hot, handler, runParams));
        log.info("任务已重跑 #{} {} ({})", id, row.getTaskName(), row.getTaskType());
        return id;
    }

    // ------------------------------------------------------------------
    // 删除
    // ------------------------------------------------------------------

    /**
     * 删除一条任务记录。只允许删终态（DONE / FAILED / INTERRUPTED），
     * 运行中或排队的任务还在内存热态和队列里，删了库行会让进度刷库扑空、记录凭空消失。
     *
     * @return 删除的条数（0 或 1）
     */
    public int delete(Long id) {
        AsyncTask row = taskMapper.selectById(id);
        if (row == null) {
            throw new IllegalArgumentException("任务不存在：" + id + "。它可能已被历史清理删掉");
        }
        if (row.getStatus() != null && !row.getStatus().isFinished()) {
            throw new IllegalStateException("任务 #" + id + " 还在执行（" + row.getStatus()
                    + "），不能删除，等它跑完再说");
        }
        return taskMapper.deleteById(id);
    }

    /**
     * 批量删除所有「已完成」（DONE）的任务。FAILED / INTERRUPTED 是重跑候选，留着。
     *
     * @return 删除的条数
     */
    public int deleteCompleted() {
        return taskMapper.delete(Wrappers.<AsyncTask>lambdaQuery()
                .eq(AsyncTask::getStatus, AsyncTaskStatus.DONE));
    }

    // ------------------------------------------------------------------
    // 启动恢复与历史清理
    // ------------------------------------------------------------------

    /**
     * 启动时调用：把上次残留的任务标成中断，再清一遍历史。
     *
     * <p><b>不自动重跑。</b>这些任务大多在动磁盘（NConvert 压缩、{@code Files.move} 搬目录、
     * 改归档目录名），进程被杀时停在哪一步无从得知，自动重跑可能在半成品状态上二次搬运。
     * 所以只标记，要不要重来由人在任务页上决定 —— 与「有一条 blockedReason 就整批不执行」
     * 是同一个立场。
     *
     * @return 被标成中断的条数
     */
    public int recoverAndCleanup() {
        int interrupted = taskMapper.update(null, Wrappers.<AsyncTask>lambdaUpdate()
                .set(AsyncTask::getStatus, AsyncTaskStatus.INTERRUPTED)
                .set(AsyncTask::getError, "进程重启，任务中断。确认磁盘状态后可点「重新执行」从头再跑一遍")
                .set(AsyncTask::getEndTime, LocalDateTime.now())
                // LambdaUpdateWrapper 的 set 不触发 MetaObjectHandler 的自动填充，
                // 不显式写的话 update_time 会停在插入那一刻
                .set(AsyncTask::getUpdateTime, LocalDateTime.now())
                .in(AsyncTask::getStatus, AsyncTaskStatus.PENDING, AsyncTaskStatus.RUNNING));
        if (interrupted > 0) {
            log.warn("上次退出时有 {} 个任务没跑完，已标记为中断（不自动重跑，请在任务页确认）", interrupted);
        }
        int cleaned = cleanup();
        if (cleaned > 0) {
            log.info("清理历史任务 {} 条", cleaned);
        }
        return interrupted;
    }

    /**
     * 清理历史。<b>时间与数量两个维度同时满足才删</b>：
     * 只按时间会让一天跑几百个任务的日子撑爆表，只按数量会在闲置一个月后把还想看的记录挤掉。
     */
    private int cleanup() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime doneBefore = now.minusDays(properties.getKeepDoneDays());
        LocalDateTime interruptedBefore = now.minusDays(properties.getKeepInterruptedDays());

        // 数量维度：终态行里第 keepMaxRows 新的那条的 id，比它旧的才够格被删
        List<AsyncTask> keep = taskMapper.selectList(Wrappers.<AsyncTask>lambdaQuery()
                .select(AsyncTask::getId)
                .in(AsyncTask::getStatus, AsyncTaskStatus.DONE,
                        AsyncTaskStatus.FAILED, AsyncTaskStatus.INTERRUPTED)
                .orderByDesc(AsyncTask::getId)
                .last("limit " + properties.getKeepMaxRows()));
        if (keep.size() < properties.getKeepMaxRows()) {
            // 总量还没到上限，两个维度不可能同时满足，不用删
            return 0;
        }
        Long oldestKeptId = keep.get(keep.size() - 1).getId();

        return taskMapper.delete(Wrappers.<AsyncTask>lambdaQuery()
                .lt(AsyncTask::getId, oldestKeptId)
                .and(w -> w
                        .nested(n -> n.in(AsyncTask::getStatus,
                                        AsyncTaskStatus.DONE, AsyncTaskStatus.FAILED)
                                .lt(AsyncTask::getCreateTime, doneBefore))
                        .or(o -> o.eq(AsyncTask::getStatus, AsyncTaskStatus.INTERRUPTED)
                                .lt(AsyncTask::getCreateTime, interruptedBefore))));
    }

    // ------------------------------------------------------------------
    // 内存热态
    // ------------------------------------------------------------------

    /**
     * 运行中任务的内存状态，同时充当交给任务体的 {@link AsyncTaskContext}。
     * <p>字段被后台线程改、被轮询线程读，所以是 volatile；{@code logs} 是普通 ArrayList，
     * 靠方法上的 synchronized 护住。
     */
    private final class Hot implements AsyncTaskContext {

        /** 提交时就定下、之后不会变的那些字段，带着走是为了让热态的视图也是完整的 */
        private final Long id;
        private final String taskType;
        private final String module;
        private final String taskName;
        private final AsyncTaskSource source;
        private final Long rerunFromId;
        private final LocalDateTime createTime;

        private final List<String> logs = new ArrayList<>();

        private volatile AsyncTaskStatus status = AsyncTaskStatus.PENDING;
        private volatile int done;
        private volatile int total;
        private volatile String message = "排队中…";
        private volatile String error;
        private volatile String resultJson;
        private volatile LocalDateTime startTime;
        private volatile LocalDateTime endTime;
        private volatile long lastFlushAt;
        /** 日志超出上限后只记条数，不再往 list 里塞 */
        private volatile int droppedLogs;

        Hot(AsyncTask row) {
            this.id = row.getId();
            this.taskType = row.getTaskType();
            this.module = row.getModule();
            this.taskName = row.getTaskName();
            this.source = row.getSource();
            this.rerunFromId = row.getRerunFromId();
            this.createTime = row.getCreateTime();
        }

        @Override
        public Long taskId() {
            return id;
        }

        @Override
        public void progress(int done, int total, String message) {
            this.done = done;
            this.total = total;
            if (message != null) {
                this.message = message;
            }
            flush(this, false);
        }

        @Override
        public void message(String message) {
            this.message = message;
            flush(this, false);
        }

        @Override
        public synchronized void log(String line) {
            if (logs.size() >= properties.getMaxLogLines()) {
                // 掐中间：开头是参数与总数，结尾是失败明细，中间是流水账。
                // 直接停止记录会把最有用的失败明细丢掉，所以是丢中间不是丢末尾
                logs.remove(logs.size() / 2);
                droppedLogs++;
            }
            logs.add(line);
        }

        @Override
        public void updateParams(Object params) {
            try {
                AsyncTask update = new AsyncTask();
                update.setId(id);
                update.setParamsJson(params == null ? null : objectMapper.writeValueAsString(params));
                update.setUpdateTime(LocalDateTime.now());
                taskMapper.updateById(update);
            } catch (Exception e) {
                // 参数刷库失败不打断任务本身 —— 代价是断了之后重跑仍用旧参数，可接受
                log.warn("任务 #{} 更新参数落库失败", id, e);
            }
        }

        private synchronized List<String> snapshotLogs() {
            if (droppedLogs == 0) {
                return List.copyOf(logs);
            }
            List<String> copy = new ArrayList<>(logs);
            copy.add(logs.size() / 2, "…（省略中间 " + droppedLogs + " 行）");
            return copy;
        }

        private String joinLogs() {
            List<String> snapshot = snapshotLogs();
            return snapshot.isEmpty() ? null : String.join("\n", snapshot);
        }

        /**
         * 热态快照。{@code result} 恒为 null —— 有结果就意味着已经终态，
         * 而终态的那一刻热态就被摘掉了，查询会走库那条路。
         */
        private TaskView view() {
            return new TaskView(id, taskType, module, taskName, status, done, total,
                    message, error, null, snapshotLogs(),
                    source, rerunFromId, false, startTime, endTime, createTime);
        }
    }
}
