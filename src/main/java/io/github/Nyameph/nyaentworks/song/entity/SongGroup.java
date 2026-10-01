package io.github.Nyameph.nyaentworks.song.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import io.github.Nyameph.nyaentworks.song.consts.SongStatus;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 归档歌曲合并条目（需求变更：归档歌曲由数据库存储）。
 *
 * <p><b>一条 = 同一分区目录内「作者+曲名+原曲名」相同的一批文件。</b>打分、调速以
 * 这条为单位。版本号与文件类型不在这层 —— 它们是 variant 级身份，落在
 * {@link SongFile}（{@code main_name} 含版本号）。
 *
 * <p><b>磁盘仍是权威，库是镜像</b>（同漫画 {@code manga_data}）：分区目录名编码评分，
 * 打分 = 搬文件；这张表只是把归并结果固化供查询，由 {@code SongSyncService} 同步时
 * 扫磁盘重建。未归档（待打分）的歌不入库，仍每次现扫。
 *
 * <p><b>自增 id 做主键</b>（而非业务键）：打分换分区只 update {@code partition_name}/
 * {@code score} 不换 key，{@code song_file.song_id} 不必跟着换。
 * 归并唯一性由 {@code (partition_name, merge_key)} 保证（唯一键 {@code uk_merge}）。
 *
 * <p><b>解析诊断列走「any」语义</b>（对齐 {@code SongMergeService.buildMergedRow}）：
 * {@code parsed}/{@code needsNormalize}/{@code looseSeparator} = 任一 variant 满足；
 * {@code author1-3}/{@code title}/{@code originalTitle} = 主 variant（排序第一）的值；
 * {@code parseFailedReason} = 全部 variant 都失败时取主 variant 的。
 *
 * @author Nyameph
 */
@Data
@TableName("song_group")
@Schema(name = "SongGroup", description = "归档歌曲合并条目")
public class SongGroup implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 自增主键。全局 {@code id-type: input}，必须显式 AUTO（参照 MangaData） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "评分分区目录名，如 #9超赞")
    private String partitionName;

    @Schema(description = "作者1原文；解析失败为 NULL")
    private String author1;

    @Schema(description = "作者2原文")
    private String author2;

    @Schema(description = "作者3原文。只存前 3 人，多余的作者只在 merge_key 里起作用")
    private String author3;

    @Schema(description = "曲名原文；解析失败为 NULL")
    private String title;

    @Schema(description = "原曲名原文，隐式时=title")
    private String originalTitle;

    @Schema(description = "归一化合并键，与 SongNameParser.mergeKey 同源")
    private String mergeKey;

    /**
     * 任一 variant 解析成功。
     * <p><b>用 Boolean 对象而非 boolean</b>：MyBatis-Plus 默认 NOT_NULL 更新策略下，
     * 原生 {@code boolean} 默认 {@code false} 会进 SET 子句把已有值抹掉
     * （{@code MangaArchiveService.mergeUnit} 踩过同款坑）。
     */
    @Schema(description = "任一 variant 解析成功")
    private Boolean parsed;

    @Schema(description = "全部 variant 都失败时取主 variant 的原因")
    private String parseFailedReason;

    @Schema(description = "任一 variant 需要规范化命名")
    private Boolean needsNormalize;

    @Schema(description = "任一 variant 用了容错分隔符")
    private Boolean looseSeparator;

    @Schema(description = "评分，由 partition_name 推导的镜像")
    private Integer score;

    @Schema(description = "合并条目级默认倍速")
    private BigDecimal defaultRate;

    @Schema(description = "ACTIVE / MISSING")
    private SongStatus status;

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
