package io.github.Nyameph.nyaentworks.shout.entity;

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
 * 喊麦标签（需求：给喊麦加标签；「归档前是否必须先有标签」2026-09-22 起由
 * {@code nya-entworks.shout.require-tags-before-archive} 决定，出厂不强制）。
 *
 * <p>键是 {@code main_name} —— 标签跟着组走，<b>跨分区共用</b>：待打分时打的标签，
 * 归档（搬进分区）之后仍然认得，因为主名没变。改名会连带改这一列，删组才会删行。
 *
 * <p>与漫画的 {@code manga_tag} + {@code manga_tag_ref} 两表不同，这里标签名<b>冗余存储</b>：
 * 喊麦标签没有「改标签要连带改目录名」的权威关系，它是 DB-only 的。
 */
@Data
@TableName("shout_tag")
@Schema(name = "ShoutTag", description = "喊麦标签")
public class ShoutTag implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 自增主键。全局 {@code id-type: input}，必须显式 AUTO */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "去扩展名后的主名")
    private String mainName;

    @Schema(description = "标签名，NFC 归一后存储")
    private String tagName;

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
