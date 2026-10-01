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
 * 语料：规范化后的一句歌词（填词助手设计 §5.3）。
 *
 * <p>由 {@code song.corpus.rescan} 任务<b>删掉重建</b>（不是磁盘镜像、不增量累加）：
 * 组降分 / 被删 / 歌词被换都会在下次重扫时自然清干净。采集范围：SONG 取
 * {@code song_group}（ACTIVE 且评分达阈值），SHOUT 从成品-喊麦磁盘现扫
 * （喊麦句只进词典、不进 few-shot）。
 *
 * <p><b>句尾韵部是「全部读音」</b>：多音字的每个读音韵母去重后逗号连接存
 * {@code finals} / {@code yun18}（按韵检索走 FIND_IN_SET）；{@code tail_pinyin}
 * 只存最常用的那一个读音。
 */
@Data
@TableName("lyric_corpus_line")
@Schema(name = "LyricCorpusLine", description = "语料：规范化后的歌词句")
public class LyricCorpusLine implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "song_group.id（= song_file.song_id）；kind=SHOUT 时为合成负数")
    private Long groupId;

    @Schema(description = "SONG 填词歌曲 / SHOUT 喊麦。few-shot 只读 SONG")
    private String kind;

    @Schema(description = "喊麦组的稳定标识（分区|主名）；kind=SONG 时为 null")
    private String groupKey;

    @Schema(description = "来源歌词文件名（一组可能有多个版本，各采一份）")
    private String fileName;

    @Schema(description = "原曲名快照（song_group.original_title）")
    private String originalTitle;

    @Schema(description = "采集时的组评分快照（few-shot 加权随机的权重）")
    private Integer score;

    @Schema(description = "清洗后的行序，0 起")
    private Integer lineIndex;

    @Schema(description = "清洗后的整句")
    private String text;

    @Schema(description = "句尾汉字；英文句尾为空")
    private String tailChar;

    @Schema(description = "句尾字带调读音（多音取最常用那个）")
    private String tailPinyin;

    @Schema(description = "句尾字全部读音的韵母，逗号分隔")
    private String finals;

    @Schema(description = "句尾字全部读音的十八韵名，逗号分隔")
    private String yun18;

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
