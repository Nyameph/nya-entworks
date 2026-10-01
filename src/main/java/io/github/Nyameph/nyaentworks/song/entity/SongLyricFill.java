package io.github.Nyameph.nyaentworks.song.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 填词项目：一个 svp 模板的一次填词工作（实现规格见 {@code docs/填词工具设计.md}）
 * </p>
 *
 * <p>{@code original_id} 指向 {@code song_original_setting.id}，<b>多对一</b> ——
 * 同一首原曲可以反复填多个版本，每填一次存一行。{@code svp_path} 只是打开时的快照，
 * 不再是唯一键：命中即直读已固化的骨架，不再解析 svp；只有点「重新解析」才重读。
 * {@code notes_json} 存全部歌唱轨、{@code track_indices} 记这次勾了哪几轨、
 * {@code lines_json} 是勾选轨合并后的分句，所以改勾选不用重新解析 svp，
 * 只用 {@code notes_json} 重跑一次分句。
 *
 * @author Nyameph
 */
@Data
@TableName("song_lyric_fill")
@Schema(name = "SongLyricFill", description = "填词项目")
public class SongLyricFill implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 自增代理主键。全局 id-type: input，必须显式 AUTO（参照 MangaData） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "所属原曲 id（song_original_setting.id），多对一")
    private Long originalId;

    @Schema(description = "填词名，空则按序号显示「填词 N」")
    private String name;

    @Schema(description = "svp 模板完整路径（打开时的快照，不唯一）")
    private String svpPath;

    @Schema(description = "原曲名原文，供展示")
    private String originalName;

    @Schema(description = "勾选的音轨序号（逗号分隔），空表示全歌唱轨")
    private String trackIndices;

    @Schema(description = "音轨 × 音符骨架 JSON（不含音高）")
    private String notesJson;

    @Schema(description = "合并时间线分句 JSON")
    private String linesJson;

    @Schema(description = "每句新词 JSON（字符串数组，长度 = 句数）")
    private String filledJson;

    /** 每句的视觉空位（句内空格）JSON，与 {@code lines_json} 同形状；<b>NULL = 从没在页面上
     *  编辑过</b>，一切照旧按 {@code LyricFillAligner#gaps} 现算。单开一列而不是塞进
     *  {@code lines_json}：保存路径上 recompute / syncDashes / syncGroupCopies 都会重建或合并
     *  行，把空位放进 {@link io.github.Nyameph.nyaentworks.song.fill.LyricTemplate.FillLine} 就得让每一处
     *  都记得搬它（漏一处 = 空格静默移位）；单开一列 + 形状校验的失败方式是「对不上就退回现算」，
     *  不会错位。 */
    @Schema(description = "每句视觉空位 JSON（布尔数组，与 lines_json 同形状）；null = 未编辑过")
    private String gapsJson;

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
