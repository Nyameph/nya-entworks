package io.github.Nyameph.nyaentworks.manga.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import io.github.Nyameph.nyaentworks.manga.consts.MangaArchiveNameSource;
import io.github.Nyameph.nyaentworks.manga.consts.MangaArchiveNameType;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 归档社团名/作者名别名，归档匹配的查找表
 * </p>
 * <p>{@code name} 是归一化后的比较键（NFC + trim + 大写），与
 * {@code MangaNameParser.splitGroupArtistToCompare} 产出的键同源；
 * {@code rawName} 保留原文供展示。
 * <p>表上刻意没有 {@code name} 的唯一键 —— 重名是要留存的冲突信息。
 *
 * @author Nyameph
 */
@Data
@TableName("manga_archive_name")
@Schema(name = "MangaArchiveName", description = "归档社团名/作者名别名")
public class MangaArchiveName implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "所属归档目录 id")
    private Long unitId;

    @Schema(description = "归一化比较键：NFC + trim + 大写")
    private String name;

    @Schema(description = "原文")
    private String rawName;

    @Schema(description = "别名种类")
    private MangaArchiveNameType nameType;

    @Schema(description = "别名来源：FOLDER 随目录名重建，MANUAL 由 sync 保留")
    private MangaArchiveNameSource nameSource;

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

}
