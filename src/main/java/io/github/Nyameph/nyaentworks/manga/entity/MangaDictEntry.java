package io.github.Nyameph.nyaentworks.manga.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import io.github.Nyameph.nyaentworks.manga.consts.MangaDictMatchMode;
import io.github.Nyameph.nyaentworks.manga.consts.MangaDictType;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 漫画名称解析词典条目
 * </p>
 *
 * @author Nyameph
 */
@Data
@TableName("manga_dict_entry")
@Schema(name = "MangaDictEntry", description = "漫画名称解析词典条目")
public class MangaDictEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "词典类型，只表示用途，不含匹配语义")
    private MangaDictType dictType;

    @Schema(description = "匹配方式，为空时按 EXACT 处理")
    private MangaDictMatchMode matchMode;

    @Schema(description = "是否忽略大小写，为空时按 true 处理")
    private Boolean ignoreCase;

    @Schema(description = "词条内容，matchMode 为 REGEX 时是正则表达式")
    private String dictValue;

    @Schema(description = "备注，可记录来源文件夹")
    private String remark;

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 实际生效的匹配方式。手工建的行常常只填 dictValue，
     * 这里统一兜底成 {@link MangaDictMatchMode#EXACT}。
     */
    public MangaDictMatchMode effectiveMatchMode() {
        return matchMode != null ? matchMode : MangaDictMatchMode.EXACT;
    }

    /** 实际生效的大小写策略，未填按忽略大小写处理 */
    public boolean effectiveIgnoreCase() {
        return !Boolean.FALSE.equals(ignoreCase);
    }

}
