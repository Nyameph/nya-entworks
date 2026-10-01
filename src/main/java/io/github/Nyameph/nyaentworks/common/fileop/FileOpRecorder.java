package io.github.Nyameph.nyaentworks.common.fileop;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.common.file.GroupFileOps.GroupPlan;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpLevel;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpModule;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpResult;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpSource;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpType;
import io.github.Nyameph.nyaentworks.common.fileop.service.FileOpLogService;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 业务代码记流水的<b>唯一入口</b>：一次用户动作包一层 {@link #batch}，真正落盘的那一层
 * 调 {@link #recordPlan} / {@link #recordPath} / {@link #recordFailure}。
 *
 * <p><b>全静态 + Spring 注入两条路</b>：调用方里有纯静态工具（{@code GroupFileOps} 的
 * 使用者）与没有 Spring 上下文的运维脚本（{@code script/} 下两个纯类），所以入口必须是
 * 静态的；Spring 那一侧由 {@link Binder} 在启动时把 {@link FileOpLogService} 塞进
 * 静态引用。没接上时（理论上只在纯单测里）{@code recordXxx} 直接返回 +
 * {@code log.warn}，不抛 —— 记不上不能让功能看起来坏了。
 *
 * <p>三条铁律（全文见 {@code docs/已完成/文件操作记录设计.md} §0.2）：
 * <ol>
 *   <li>记录点挂在<b>真正落盘的那一层</b>，一个动作只记一次；上层只负责圈出
 *       「这是一次什么动作」（{@code batch(...)}）；</li>
 *   <li>记录是附属品：写入侧自己 try/catch，绝不往上抛；</li>
 *   <li>批次用 {@code ThreadLocal}；已有活动批次时不新开、不改上下文 —— 所以
 *       {@code applyNormalizeBatch} 循环调 {@code applyNormalize} 只产生一个批次，
 *       而单独走 {@code /normalize} 时那次 {@code batch} 就是它自己的，两种情况都对。</li>
 * </ol>
 *
 * <p><b>ThreadLocal 的边界要心里有数</b>：它不跟 {@code new Thread} / 线程池走。
 * 全项目唯一的多线程落盘是漫画压缩（{@code MangaCompressService} 逐文件并行），
 * 而它本来就不记，所以当前不受影响；将来要记任何子线程里的落盘，
 * 必须把批次对象显式传进去。
 */
public final class FileOpRecorder {

    private FileOpRecorder() {
    }

    /** Spring 侧的接线（见类注释）。单独一个类，避免让记录器自己变成 Bean */
    @Component
    @RequiredArgsConstructor
    public static class Binder {

        private final FileOpLogService service;

        @PostConstruct
        public void bind() {
            FileOpRecorder.service = service;
        }
    }

    /**
     * 一次用户动作 = 一个批次。已有活动批次时<b>不新开、不改上下文、直接执行 body</b>
     * （嵌套规则：外层赢）。结束时在 {@code finally} 里清掉 ThreadLocal —— 不清会污染
     * 同线程的下一次操作（异步任务的执行线程是复用的）。
     */
    public static <T> T batch(FileOpModule module, FileOpSource source, Long taskId,
                              Supplier<T> body) {
        if (CURRENT.get() != null) {
            return body.get();
        }
        CURRENT.set(new Batch(UUID.randomUUID().toString(), module, source, taskId));
        try {
            return body.get();
        } finally {
            CURRENT.remove();
        }
    }

    /**
     * 一次落盘：把这一组文件的搬动 / 删除记成 N 条（一个路径一条）。
     * <p>调用点在 {@code moveAll} / {@code deleteAll} <b>成功之后</b>。
     * {@code plan} 已经把信息带齐了，调用方不该再自己算任何东西。
     */
    public static void recordPlan(FileOpModule module, GroupPlan plan, FileOpLevel level) {
        if (service == null || plan == null) {
            return;
        }
        Batch current = CURRENT.get();
        if (current != null && current.module != module) {
            // 一致性检查：module 显式传就是为了这一刻 —— 嵌套两侧的 module 不一致时
            // 以传进来的为准（它描述的是「动的是谁的文件」），但要让人看见
            log.warn("recordPlan 的 module（{}）与活动批次（{}）不一致，以传进来的为准",
                    module, current.module);
        }
        MappedAction mapped = mapAction(plan.action());
        for (var move : plan.moves()) {
            if (move.blockedReason() != null) {
                continue;   // 那一条根本没执行，不许记
            }
            if (move.toPath() == null) {
                insert(module, FileOpType.DELETE, level, mapped.action(),
                        move.fromPath(), null, FileOpResult.OK, null, current);
            } else {
                // 未知 action 的 op 类型按「有改后路径 = 搬动」判（映射表里有的按映射表）
                FileOpType op = mapped.op() == null ? FileOpType.MOVE : mapped.op();
                insert(module, op, level, mapped.action(),
                        move.fromPath(), move.toPath(), FileOpResult.OK, null, current);
            }
        }
    }

    /**
     * 一次落盘：一个路径。漫画的目录级动作、模板目录、下载落地的文件都用它。
     */
    public static void recordPath(FileOpModule module, FileOpType op, FileOpLevel level,
                                  String action, String fromPath, String toPath) {
        if (service == null) {
            return;
        }
        insert(module, op, level, action, fromPath, toPath, FileOpResult.OK, null, CURRENT.get());
    }

    /**
     * 失败：抛之前先记一条 FAILED 再往上抛（抛是调用方的事，本方法只记）。
     * 「整批回滚」的情形也给 FAILED 记一条 —— 那些路径确实动过又回来了，如实。
     */
    public static void recordFailure(FileOpModule module, FileOpType op, FileOpLevel level,
                                     String fromPath, String toPath, String reason) {
        if (service == null) {
            return;
        }
        insert(module, op, level, null, fromPath, toPath, FileOpResult.FAILED,
                StringUtils.abbreviate(reason, 500), CURRENT.get());
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static volatile FileOpLogService service;

    private static final ThreadLocal<Batch> CURRENT = new ThreadLocal<>();

    /** 批次上下文：一次用户动作的身份。嵌套时外层的赢（见 {@link #batch}） */
    private record Batch(String batchId, FileOpModule module, FileOpSource source, Long taskId) {
    }

    /** {@code plan.action()} 的映射结果 */
    private record MappedAction(FileOpType op, String action) {
    }

    /**
     * {@code plan.action()} → ({@code op_type}, 人话 {@code action}) 的映射表，
     * <b>全项目唯一一份</b>。映射表里没有的值：按 {@code toPath} 是否为 null 判类型、
     * 人话原样显示 + {@code log.warn} 一次 —— 不许抛（理由同「记录是附属品」）。
     */
    private static final Map<String, MappedAction> ACTION_MAP = Map.of(
            "SCORE", new MappedAction(FileOpType.MOVE, "改评分（换分区）"),
            "RENAME", new MappedAction(FileOpType.MOVE, "改名"),
            "EDIT", new MappedAction(FileOpType.MOVE, "改评分 + 改名"),
            // 2026-09-30 起「删」＝送进 Windows 回收站；文案里带出来，免得看流水的人
            // 以为文件已经没了（类型列仍是「删除」：script/ 那几处确实是永久删，
            // 这一列因此不能跟着改成「进回收站」）
            "DELETE", new MappedAction(FileOpType.DELETE, "删除（进回收站）"),
            // 添加歌曲（docs/已完成/添加歌曲设计.md 阶段 3）的三个值：同为整组搬动
            "IMPORT_STAGING", new MappedAction(FileOpType.MOVE, "添加歌曲：迁移到未归档"),
            "IMPORT_ARCHIVE", new MappedAction(FileOpType.MOVE, "添加歌曲：归档"),
            // 添加喊麦（同一个功能的简化版）：文案带模块名，共用会写出「添加歌曲」的错话
            "SHOUT_IMPORT_STAGING", new MappedAction(FileOpType.MOVE, "添加喊麦：迁移到未归档"),
            "SHOUT_IMPORT_ARCHIVE", new MappedAction(FileOpType.MOVE, "添加喊麦：归档"));

    private static MappedAction mapAction(String action) {
        MappedAction mapped = ACTION_MAP.get(action);
        if (mapped == null) {
            log.warn("GroupPlan 出现了映射表里没有的 action（{}），原样显示", action);
            return new MappedAction(null, action);
        }
        return mapped;
    }

    /** 统一的落库：批次缺失时照写（source=UNKNOWN + warn），丢记录比标错来源更糟 */
    private static void insert(FileOpModule module, FileOpType op, FileOpLevel level,
                               String action, String fromPath, String toPath,
                               FileOpResult result, String detail, Batch current) {
        String batchId;
        FileOpSource source;
        Long taskId;
        if (current != null) {
            batchId = current.batchId();
            source = current.source();
            taskId = current.taskId();
        } else {
            // 有人忘了包 batch(...)：UNKNOWN 在页面上可筛，那种情况必须看得见
            batchId = UUID.randomUUID().toString();
            source = FileOpSource.UNKNOWN;
            taskId = null;
            log.warn("改动流水没有批次上下文（module={} action={} path={}），已按 UNKNOWN 记录",
                    module, action, fromPath != null ? fromPath : toPath);
        }
        try {
            service.insert(module, batchId, op, level, action, source, taskId,
                    fromPath, toPath, result, detail);
        } catch (Exception e) {
            // 纵深防御：FileOpLogService 自己会吞，但别让「理论上不会抛」变成「抛了没人接」
            log.warn("改动流水记录器自身抛了异常（module={} path={}）", module,
                    fromPath != null ? fromPath : toPath, e);
        }
    }

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(FileOpRecorder.class);

    /** 供单测注入假件用（Mockito 塞一个 mock 的 FileOpLogService；传 null 清掉） */
    public static void bindForTest(FileOpLogService testService) {
        service = testService;
    }
}
