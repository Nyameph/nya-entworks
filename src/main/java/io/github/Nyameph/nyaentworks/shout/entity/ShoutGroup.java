package io.github.Nyameph.nyaentworks.shout.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import io.github.Nyameph.nyaentworks.shout.consts.ShoutStatus;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 归档喊麦组（磁盘的镜像）。一条 = 一个主名组，键是 {@code (partition_name, main_name)}。
 *
 * <p>喊麦<b>不解析文件名</b>，所以这张表比歌曲的 {@code song_group} 少了一整批列：
 * 没有作者 / 曲名 / 原曲名（解析产物），没有 {@code parsed} / {@code parse_failed_reason}
 * （没有解析这件事），没有 {@code needs_normalize} / {@code loose_separator}（不做规范化命名）。
 * 归并键就是主名本身 —— 喊麦不跨主名归并，主名里的 {@code #} 是普通字符。
 *
 * <p>{@code default_rate} 是这张表里唯一「磁盘表达不出来」的东西，由
 * {@link io.github.Nyameph.nyaentworks.shout.service.ShoutStoreService} 读写；
 * 同步只建行、从不覆盖它。
 */
@Data
@TableName("shout_group")
@Schema(name = "ShoutGroup", description = "归档喊麦组")
public class ShoutGroup implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 自增主键。全局 {@code id-type: input}，必须显式 AUTO */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "评分分区目录名，如 #9超赞")
    private String partitionName;

    @Schema(description = "去扩展名主名，组的身份")
    private String mainName;

    @Schema(description = "评分，由 partition_name 推导的镜像")
    private Integer score;

    @Schema(description = "组级默认倍速；没存过为 null")
    private BigDecimal defaultRate;

    @Schema(description = "ACTIVE / MISSING")
    private ShoutStatus status;

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
