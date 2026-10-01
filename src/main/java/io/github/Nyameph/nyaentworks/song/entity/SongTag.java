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
 * 歌曲合并条目的标签（需求：给歌曲加标签；「归档前是否必须先有标签」2026-09-22 起由
 * {@code nya-entworks.song.require-tags-before-archive} 决定，出厂不强制）。
 *
 * <p>标签挂到「合并条目」上，key = {@code merge_key}（{@code song_group.merge_key}），{@code merge_key}
 * 与 {@code SongNameParser.mergeKey} / {@code song_group.merge_key} 同源。与漫画不同，
 * 歌曲标签是 DB-only：歌曲是平铺文件、没有目录容器，标签在磁盘上表达不出来。
 *
 * <p>标签名<b>冗余存储</b>（不像漫画的 {@code manga_tag} + {@code manga_tag_ref}
 * 两表）：歌曲标签没有「改标签要连带改目录名」那层权威关系，内联字符串即可。
 */
@Data
@TableName("song_tag")
@Schema(name = "SongTag", description = "歌曲合并条目标签")
public class SongTag implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 自增主键。全局 {@code id-type: input}，必须显式 AUTO */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "归一化合并键，与 SongNameParser.mergeKey 同源")
    private String mergeKey;

    @Schema(description = "标签名，NFC 归一后存储")
    private String tagName;

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
