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
 * 语料：原词 → 新词配对（填词助手设计 §5.3 / §5.6）。
 *
 * <p>挂在 {@code LyricFillService.save} 上的<b>diff 式</b>钩子写入：前端每 5 秒自动保存
 * 都会走到这里，逐句比对只落变化的行 —— 句被填满（每个可填槽位都有值）才收，
 * 没填满 / 被清空的句删除对应行，无变化不落任何语句。
 */
@Data
@TableName("lyric_corpus_pair")
@Schema(name = "LyricCorpusPair", description = "语料：原词→新词配对")
public class LyricCorpusPair implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "song_lyric_fill.id")
    private Long fillId;

    @Schema(description = "句子下标（与 song_lyric_fill.lines_json 同序）")
    private Integer lineIndex;

    @Schema(description = "原词句")
    private String originalText;

    @Schema(description = "新词句（整句填满才收）")
    private String filledText;

    @Schema(description = "新词句尾韵部")
    private String yun18;

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
