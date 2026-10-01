package io.github.Nyameph.nyaentworks.manga.entity;

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
 * 漫画标签，跨实体共用
 * </p>
 * <p>本表只存标签本身，挂到谁身上由 {@link MangaTagRef} 决定。
 *
 * @author Nyameph
 */
@Data
@TableName("manga_tag")
@Schema(name = "MangaTag", description = "漫画标签")
public class MangaTag implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 标签来源：目录名扫出（目录名是权威，随目录名重建） */
    public static final String SOURCE_FOLDER = "FOLDER";
    /** 标签来源：e-hentai 词典导入（纯参考，不随目录名重建） */
    public static final String SOURCE_EHENTAI = "EHENTAI";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "标签名")
    private String tagName;

    @Schema(description = "e-hentai 命名空间（female/male/…）；目录标签为 NULL")
    private String namespace;

    @Schema(description = "英文原标签（e-hentai tag_en）")
    private String enName;

    @Schema(description = "语义大类")
    private String majorCategory;

    @Schema(description = "细分类（e-hentai category 原文）")
    private String category;

    @Schema(description = "描述（EhTagTranslation）")
    private String description;

    @Schema(description = "来源：FOLDER 目录名扫出 / EHENTAI 词典导入")
    private String source;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "是否不计入归档作者「高频标签」统计：1=不计入（归档作者页统计前排除）")
    private Integer countIgnore;

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

}
