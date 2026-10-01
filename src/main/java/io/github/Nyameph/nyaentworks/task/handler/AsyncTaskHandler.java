package io.github.Nyameph.nyaentworks.task.handler;

/**
 * 一类异步任务的执行体。各业务模块实现它，Spring 自动收集成注册表。
 *
 * <p><b>实现类放在各自模块的包下</b>（{@code manga/service}、{@code song/service}），
 * 不要放进 {@code task} 包 —— 框架包不 import 任何业务类，
 * 这样 manga 的 handler 与 song 的 handler 谁也看不见谁，符合「两个模块互不调用」的项目定位。
 *
 * <p><b>没有 lambda 提交的口子</b>，要异步就得注册一个 handler。理由是重跑：lambda 提交出来的
 * 任务没有类型也没有参数，重启后只会变成一条带死按钮的 INTERRUPTED 记录。
 *
 * <p>实现时注意两条：
 * <ul>
 *   <li><b>别把整个 {@code run} 包成一个 {@code @Transactional}</b> —— 分钟级事务会长时间
 *       占着连接、锁着行。事务粒度保持「单条 / 单本」，循环里逐条各自提交。</li>
 *   <li><b>不能读请求上下文</b>（{@code RequestContextHolder} 之类）—— 任务跑在后台线程上，
 *       没有 HTTP 请求。要用的东西必须在提交时就进 {@code params}。</li>
 * </ul>
 */
public interface AsyncTaskHandler {

    /**
     * 全局唯一的类型标识，形如 {@code manga.archive.sync}。
     * <p>它会落进 {@code async_task.task_type}，是「重新执行」找回 handler 的唯一依据，
     * 所以<b>改名等于让历史任务重跑不了</b>。重复的 type 在启动时直接抛。
     */
    String type();

    /**
     * 归属模块：{@code manga} / {@code song}。
     * <p>决定进哪条队列。每个模块一条单线程队列 —— 同模块串行（不并发动同一批文件），
     * 跨模块并行（导账单不必排在漫画压缩后面）。
     */
    String module();

    /** 默认任务名。提交时可以传一个更具体的覆盖它（如「存储 3 本新漫画」） */
    String taskName();

    /**
     * 参数类型，重新执行时用它把 {@code params_json} 反序列化回来。
     * <p>无参任务返回 {@code Void.class}。优先直接复用 Controller 里已有的 request record，
     * 不必另造一个参数类。
     */
    Class<?> paramType();

    /**
     * 执行体。返回值会被序列化进 {@code result_json} 交给前端。
     * <p>抛异常即任务失败，原因由框架按 {@code GlobalExceptionHandler} 的同一套规则提取。
     */
    Object run(Object params, AsyncTaskContext context) throws Exception;

    /**
     * 把 {@link #run} 的返回值浓缩成一句人话，任务跑完后写进 {@code message} 列，
     * 让列表一眼看出结果要点（「归档 3 本、1 本待确认」「导入 214 行」）。
     * <p>默认返回 {@code null}，此时框架沿用「完成」。要省事就不用覆盖 —— 列表里
     * 任务跑完仍显示状态徽章「完成」，只是说明列空着。
     */
    default String summarizeResult(Object result) {
        return null;
    }
}
