package io.github.Nyameph.nyaentworks.common.fileop.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import io.github.Nyameph.nyaentworks.common.db.SqlDialect;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpLevel;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpModule;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpResult;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpSource;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpType;
import io.github.Nyameph.nyaentworks.common.fileop.entity.FileOpLog;
import io.github.Nyameph.nyaentworks.common.fileop.mapper.FileOpLogMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 改动流水的写入与查询。
 *
 * <p><b>记录是附属品</b>：文件已经搬完了还抛异常，会让调用方（和页面）以为失败、
 * 用户可能重跑一遍 —— 那才是真的坏事。所以 {@link #insert} 整段 try/catch、
 * {@code log.warn} 之后就地返回，绝不往上抛（先例：{@code AsyncTaskService#flush}）。
 *
 * <p><b>永远不参与别人的事务</b>：多个调用方带 {@code @Transactional}（如
 * {@code MangaArchiveService#applyEdit / moveManga / deleteEmptyUnit}），而「磁盘先动、
 * 库后动」—— 事务回滚会把记录一起滚掉，<b>而磁盘已经改了</b>，那正是最需要这条记录的时候。
 *
 * <p>所以 {@link #insert} 上的传播是 {@code REQUIRES_NEW}：<b>挂起</b>外层事务、自己开一个
 * 独立事务并立即提交。两个方向的错都堵住了 ——
 * <ul>
 *   <li>默认传播（{@code REQUIRED}）会<b>加入</b>外层事务，外层回滚时记录跟着消失（原来的 bug）；
 *   <li>干脆不加注解就更糟：外层已有事务时照样加入，两者等价（2026-09-23 修，见
 *       {@code docs/已完成/文件操作记录设计.md} §0.2 第 3 条）。
 * </ul>
 * <b>别把这一行当成「多余的注解」删掉</b>：它看着像默认值，实际上是那段语义的全部实现 ——
 * 删掉它，落在 {@code @Transactional} 方法里的记录点会静默退回「跟主操作一起回滚」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileOpLogService {

    private static final DateTimeFormatter TIME_TEXT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final FileOpLogMapper mapper;

    /**
     * 单条写入。参数是流水的一行（见 {@code file_op_log} 的 13 列口径），缺省项由调用方
     * （{@code FileOpRecorder}）补齐 —— 本方法只管落库与吞异常。
     *
     * <p>{@code REQUIRES_NEW} 是<b>口径的一部分</b>，不是可选项（理由见类注释）。
     * 调用方是 {@code FileOpRecorder} 持有的 Spring Bean，走的是代理，注解生效。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(FileOpModule module, String batchId, FileOpType op, FileOpLevel level,
                       String action, FileOpSource source, Long taskId,
                       String fromPath, String toPath, FileOpResult result, String detail) {
        try {
            FileOpLog row = new FileOpLog();
            row.setBatchId(batchId);
            row.setModule(module);
            row.setOpType(op);
            row.setUnitLevel(level);
            row.setAction(action);
            row.setSource(source);
            row.setTaskId(taskId);
            row.setFromPath(fromPath);
            row.setToPath(toPath);
            row.setResult(result == null ? FileOpResult.OK : result);
            row.setDetail(detail);
            row.setOpTime(LocalDateTime.now());
            mapper.insert(row);
        } catch (Exception e) {
            // 库没建表 / MySQL 挂了的正确症状是「操作记录页空」，不是「搬文件报错」
            log.warn("改动流水写入失败：{} {} {} → {}", module, op, fromPath, toPath, e);
        }
    }

    // ------------------------------------------------------------------
    // 查询（阶段 6）
    // ------------------------------------------------------------------

    /** 分页结果，与词典页同构 */
    public record LogPage(long total, int page, int size, List<Row> items) {
    }

    /**
     * 一行。<b>枚举的显示文字由后端下发</b> —— 「前端只画不判」是全局硬约束。
     */
    public record Row(long id, String batchId, String timeText, String moduleText, String opText,
                      String levelText, String action, String sourceText, Long taskId,
                      String fromPath, String toPath, String resultText, String detail) {
    }

    /**
     * 分页查询。排序按自增 id 倒序（与 op_time 同序，理由同 {@code AsyncTaskService.list}）。
     * 筛选条件一个一个 {@code if (x != null)} 拼 —— {@code .in(...)} 传空集合会渲染成
     * {@code IN ()} 直接报错；本方法只收单值参数，不会踩那个坑，但口径保持一致。
     */
    public LogPage page(FileOpModule module, FileOpType op, FileOpLevel level,
                        FileOpSource source, String batchId, String keyword,
                        LocalDateTime from, LocalDateTime to, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 200);
        // batchId 先处理：条件参数是急切求值的，.eq(false, col, batchId.trim()) 照样会在
        // batchId 为 null 时 NPE（冒烟测试抓到的）
        String cleanBatch = StringUtils.trimToNull(batchId);
        LambdaQueryWrapper<FileOpLog> q = Wrappers.<FileOpLog>lambdaQuery()
                .eq(module != null, FileOpLog::getModule, module)
                .eq(op != null, FileOpLog::getOpType, op)
                .eq(level != null, FileOpLog::getUnitLevel, level)
                .eq(source != null, FileOpLog::getSource, source)
                .eq(cleanBatch != null, FileOpLog::getBatchId, cleanBatch);
        if (StringUtils.isNotBlank(keyword)) {
            String needle = keyword.trim();
            q.and(w -> w.like(FileOpLog::getFromPath, needle)
                    .or()
                    .like(FileOpLog::getToPath, needle));
        }
        if (from != null) {
            q.ge(FileOpLog::getOpTime, from);
        }
        if (to != null) {
            q.le(FileOpLog::getOpTime, to);
        }
        long total = mapper.selectCount(q);
        long offset = (long) (safePage - 1) * safeSize;
        List<Row> items = mapper.selectList(q.orderByDesc(FileOpLog::getId)
                        .last(SqlDialect.limit(offset, safeSize))).stream()
                .map(FileOpLogService::toRow)
                .toList();
        return new LogPage(total, safePage, safeSize, items);
    }

    private static Row toRow(FileOpLog row) {
        return new Row(row.getId(), row.getBatchId(),
                row.getOpTime() == null ? "" : TIME_TEXT.format(row.getOpTime()),
                moduleText(row.getModule()), opText(row.getOpType()), levelText(row.getUnitLevel()),
                row.getAction(), sourceText(row.getSource()), row.getTaskId(),
                row.getFromPath(), row.getToPath(), resultText(row.getResult()), row.getDetail());
    }

    /** 清理 N 天前的记录。同步执行（一条 DELETE 而已）；由应用执行，不经 tools/mysql.py */
    public int cleanup(int days) {
        int safeDays = Math.max(7, days);   // 下限 7：别让人手滑删掉一周内的
        return mapper.delete(Wrappers.<FileOpLog>lambdaQuery()
                .lt(FileOpLog::getOpTime, LocalDateTime.now().minusDays(safeDays)));
    }

    // ------------------------------------------------------------------
    // 显示文字（唯一一份，前端只画不判）
    // ------------------------------------------------------------------

    private static String moduleText(FileOpModule module) {
        if (module == null) {
            return "";
        }
        return switch (module) {
            case MANGA -> "漫画";
            case SONG -> "歌曲";
            case SHOUT -> "喊麦";
        };
    }

    private static String opText(FileOpType op) {
        if (op == null) {
            return "";
        }
        return switch (op) {
            case MOVE -> "移动";
            case DELETE -> "删除";
            case WRITE -> "写入";
        };
    }

    private static String levelText(FileOpLevel level) {
        if (level == null) {
            return "";
        }
        return switch (level) {
            case AUTHOR -> "作者目录";
            case COLLECTION -> "合集目录";
            case SINGLE -> "单本";
            case GROUP -> "一组";
            case OTHER -> "其它";
        };
    }

    private static String sourceText(FileOpSource source) {
        if (source == null) {
            return "";
        }
        return switch (source) {
            case PAGE -> "页面";
            case TASK -> "任务";
            case SCRIPT -> "脚本";
            case UNKNOWN -> "未标注";
        };
    }

    private static String resultText(FileOpResult result) {
        if (result == null) {
            return "";
        }
        return switch (result) {
            case OK -> "成功";
            case FAILED -> "失败";
        };
    }
}
