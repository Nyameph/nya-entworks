package io.github.Nyameph.nyaentworks.manga.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import io.github.Nyameph.nyaentworks.manga.consts.MangaTagTargetType;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 标签关联
 * </p>
 * <p>用 {@code (targetType, targetId)} 而非外键指向目标，因此新增一类可打标签的
 * 实体只需给 {@link MangaTagTargetType} 加枚举值。
 *
 * @author Nyameph
 */
@Data
@TableName("manga_tag_ref")
@Schema(name = "MangaTagRef", description = "标签关联")
public class MangaTagRef implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "标签 id")
    private Long tagId;

    @Schema(description = "关联的实体类型")
    private MangaTagTargetType targetType;

    @Schema(description = "关联的实体 id")
    private Long targetId;

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

}
