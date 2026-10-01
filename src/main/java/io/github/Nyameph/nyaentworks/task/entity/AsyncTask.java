package io.github.Nyameph.nyaentworks.task.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import io.github.Nyameph.nyaentworks.task.consts.AsyncTaskSource;
import io.github.Nyameph.nyaentworks.task.consts.AsyncTaskStatus;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 一条异步任务
 * </p>
 * <p>运行中的进度活在内存热态里（见 {@code AsyncTaskService}），按节流间隔刷到本表；
 * 状态跃迁则无条件落库。所以本表的 {@code progressDone} 最多滞后一个刷新周期。
 *
 * @author Nyameph
 */
@Data
@TableName("async_task")
@Schema(name = "AsyncTask", description = "持久化异步任务")
public class AsyncTask implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 全局 id 策略是 {@code input}（见 application.yaml），这里必须显式声明 AUTO，
     * 不写就不会自增、插入时主键为 null。
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "任务类型，对应 AsyncTaskHandler.type()，重新执行时靠它找回 handler")
    private String taskType;

    @Schema(description = "归属模块 manga / song，决定进哪个执行队列")
    private String module;

    @Schema(description = "展示用任务名")
    private String taskName;

    @Schema(description = "任务状态")
    private AsyncTaskStatus status;

    @Schema(description = "任务参数 JSON，重新执行时反序列化回 handler.paramType()；无参任务为空")
    private String paramsJson;

    @Schema(description = "已完成步数")
    private Integer progressDone;

    @Schema(description = "总步数，未知时为 0")
    private Integer progressTotal;

    @Schema(description = "当前在做什么")
    private String message;

    @Schema(description = "FAILED / INTERRUPTED 时的原因")
    private String error;

    @Schema(description = "DONE 时的结果 JSON")
    private String resultJson;

    @Schema(description = "逐步明细，换行分隔")
    private String logs;

    @Schema(description = "任务来源：正常提交还是重新执行")
    private AsyncTaskSource source;

    @Schema(description = "重新执行自哪个任务 id")
    private Long rerunFromId;

    @Schema(description = "开始执行时间，排队期间为空")
    private LocalDateTime startTime;

    @Schema(description = "进入终态的时间")
    private LocalDateTime endTime;

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

}
