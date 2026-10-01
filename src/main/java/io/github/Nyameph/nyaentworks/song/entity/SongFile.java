package io.github.Nyameph.nyaentworks.song.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import io.github.Nyameph.nyaentworks.common.media.MediaExtensions;
import io.github.Nyameph.nyaentworks.song.consts.SongFileType;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 归档歌曲合并条目下的文件清单。
 *
 * <p><b>一个 variant = 同一 {@code main_name} 的多行</b>（VIDEO/AUDIO/LYRIC 各一行）。
 * 播放时切换 variant 按 {@code main_name} 分组即可；{@code playFile = video ?: audio}、
 * {@code hasVideo}、{@code lyricFiles} 都由 {@code fileType} 推得，无需额外字段。
 *
 * <p><b>{@code variant_sort}</b> 在同步时按「有视频 &gt; 版本号降序 &gt; 主名字典序」
 * 固化成排名，同 variant 各行写同值；读路径 {@code ORDER BY variant_sort, sort_order}
 * 即得确定顺序，不再重跑 comparator（避免读路径另写一份）。
 *
 * <p><b>完整文件名不存列</b>（2026-09-23 起）：存 {@code main_name} 与 {@code suffix}
 * 两段，完整文件名恒等于它们的和 —— 用 {@link #fullName()} 拼，别自己又拼一份。
 * 原先那一份冗余的 {@code file_name} 列已由唯一键 {@code uk_file(song_id, main_name, suffix)}
 * 取代：同一 variant 的 video/audio/歌词三行主名相同、只有后缀不同，所以后缀必须进键。
 *
 * @author Nyameph
 */
@Data
@TableName("song_file")
@Schema(name = "SongFile", description = "归档歌曲合并条目下的文件清单")
public class SongFile implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 自增主键。全局 {@code id-type: input}，必须显式 AUTO */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "所属 song.id")
    private Long songId;

    @Schema(description = "去扩展名主名，含版本号，variant 身份")
    private String mainName;

    @Schema(description = "VIDEO / AUDIO / LYRIC")
    private SongFileType fileType;

    @Schema(description = "扩展名后缀，含点、保留原文大小写（如 .MP3）")
    private String suffix;

    @Schema(description = "跨 variant 排序（同步时固化），同 variant 各行同值")
    private Integer variantSort;

    @Schema(description = "variant 内顺序：video=0 audio=1 lyric=2..")
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
