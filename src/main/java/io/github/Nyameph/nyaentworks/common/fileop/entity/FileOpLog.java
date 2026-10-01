package io.github.Nyameph.nyaentworks.common.fileop.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpLevel;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpModule;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpResult;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpSource;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpType;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 一条磁盘改动流水：谁在什么时间，把哪个路径改成了哪个路径
 * </p>
 *
 * <p><b>一条记录 = 一个被改动的路径</b>。同一次用户动作产生的多条记录共用一个
 * {@code batchId}（页面上靠它串起来）；漫画「整体移动一个作者文件夹」在代码里本来就
 * 是一次 {@code Files.move}，所以天然只有一条，不需要任何按目录聚合的逻辑。
 *
 * <p><b>这张表只追加，不修改也不删除</b>（除了页面上的手工清理）。它<b>不是镜像表</b>：
 * 磁盘扫描从不碰它，也没有任何「同步」会来覆盖它 —— 正相反，只有应用能写它。
 *
 * <p>两个刻意不写的东西：
 * <ul>
 *   <li>没有 {@code createTime} / {@code updateTime}，也不需要 {@code @TableField(fill = ...)}：
 *       流水只追加，只需要一个 {@link #opTime}；</li>
 *   <li>没有状态机、没有软删 —— 记完就是历史，历史不改。</li>
 * </ul>
 *
 * <p>写入方（{@code FileOpLogService}）必须把异常吞掉：记录是附属品，磁盘已经动过了，
 * 此时抛异常会让调用方以为操作失败。口径全文见 {@code docs/已完成/文件操作记录设计.md}。
 *
 * @author Nyameph
 */
@Data
@TableName("file_op_log")
@Schema(name = "FileOpLog", description = "文件与目录的改动流水")
public class FileOpLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 全局 id 策略是 {@code input}（见 application.yaml），这里必须显式声明 AUTO，
     * 不写就不会自增、插入时主键为 null。
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "批次号（UUID）：同一次动作的多条记录共用一个")
    private String batchId;

    @Schema(description = "归属模块 MANGA / SONG / SHOUT")
    private FileOpModule module;

    @Schema(description = "机械类型 MOVE / DELETE / WRITE，决定哪一列路径是空的")
    private FileOpType opType;

    @Schema(description = "被改动路径所处的层级 AUTHOR / COLLECTION / SINGLE / GROUP / OTHER")
    private FileOpLevel unitLevel;

    @Schema(description = "人话的原因，如「改评分」「添加歌曲：迁移到未归档」，页面上直接显示")
    private String action;

    @Schema(description = "来源 PAGE 页面 / TASK 异步任务 / SCRIPT 运维脚本")
    private FileOpSource source;

    @Schema(description = "异步任务 id，source = TASK 时填")
    private Long taskId;

    @Schema(description = "改前全路径；WRITE 时为 null")
    private String fromPath;

    @Schema(description = "改后全路径；DELETE 时为 null")
    private String toPath;

    @Schema(description = "落盘成没成：OK / FAILED")
    private FileOpResult result;

    @Schema(description = "失败原因或备注")
    private String detail;

    @Schema(description = "操作时间。不加 create_time 是因为流水只追加，不需要第二个时间")
    private LocalDateTime opTime;
}
