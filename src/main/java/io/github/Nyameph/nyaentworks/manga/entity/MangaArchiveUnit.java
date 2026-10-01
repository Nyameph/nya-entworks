package io.github.Nyameph.nyaentworks.manga.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import io.github.Nyameph.nyaentworks.manga.consts.MangaArchiveUnitStatus;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 漫画归档目录，对应 {@code F:\MangaGroup\<评分分区>} 下的一级子目录
 * </p>
 * <p>目录名形如 {@code [社团 (作者甲、作者乙)]【标签1 标签2】}：社团与作者存在本表，
 * 标签走 {@code manga_tag} / {@code manga_tag_ref}，用于匹配的归一化别名走
 * {@link MangaArchiveName}。
 *
 * @author Nyameph
 */
@Data
@TableName("manga_archive_unit")
@Schema(name = "MangaArchiveUnit", description = "漫画归档目录")
public class MangaArchiveUnit implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "归档目录全路径")
    private String folderPath;

    @Schema(description = "目录名原文")
    private String folderName;

    @Schema(description = "所属归档根，如 F:\\MangaGroup\\9-百读不厌")
    private String rootPath;

    @Schema(description = "评分，由 rootPath 推导：3/5/7/9")
    private Integer score;

    @Schema(description = "社团名原文，[作者] 形态时为空")
    private String groupName;

    @Schema(description = "作者名原文，多作者含 、 分隔符")
    private String artistNames;

    @Schema(description = "相对文件系统的状态")
    private MangaArchiveUnitStatus status;

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

}
