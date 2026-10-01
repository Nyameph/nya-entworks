package io.github.Nyameph.nyaentworks.shout.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import io.github.Nyameph.nyaentworks.common.media.MediaExtensions;
import io.github.Nyameph.nyaentworks.shout.consts.ShoutFileType;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 一个归档喊麦组下的文件清单。键 {@code (shout_id, main_name, suffix)}，一个组多行。
 *
 * <p><b>完整文件名不存列</b>（2026-09-23 起）：存 {@code main_name} 与 {@code suffix}
 * 两段，完整文件名恒等于它们的和 —— 用 {@link #fullName()} 拼，别自己又拼一份。
 * 与歌曲侧同一口径，理由详见 {@code SongFile} 的类注释。一个组同样有 video/audio/歌词
 * 多行，主名相同、只有后缀不同，所以后缀必须进键。
 *
 * <p>不存路径（同歌曲的 {@code song_file}）：路径由 {@code shout_group.partition_name}
 * 加归档根在运行时拼回 —— 库只存磁盘表达不出来的东西，路径是磁盘说了算的。
 *
 * <p>喊麦没有 variant：一个主名一个组，所以只有组内顺序 {@code sort_order}
 * （video=0、audio=1、lyric=2..，与 {@code ShoutGroup.allFiles()} 的顺序一致）。
 */
@Data
@TableName("shout_file")
@Schema(name = "ShoutFile", description = "归档喊麦组下的文件")
public class ShoutFile implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 自增主键。全局 {@code id-type: input}，必须显式 AUTO */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "所属 shout_group.id")
    private Long shoutId;

    @Schema(description = "去扩展名主名")
    private String mainName;

    @Schema(description = "VIDEO / AUDIO / LYRIC")
    private ShoutFileType fileType;

    @Schema(description = "扩展名后缀，含点、保留原文大小写（如 .MP3）")
    private String suffix;

    @Schema(description = "组内顺序：video=0 audio=1 lyric=2..")
    private Integer sortOrder;

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 拼回完整文件名 = {@code mainName + suffix}。**不是数据库列**，由那两列推得。
     *
     * <p>名字刻意不带 {@code get} 前缀 —— 别让它被当成一个可序列化属性。
     */
    public String fullName() {
        return MediaExtensions.fullName(mainName, suffix);
    }
}
