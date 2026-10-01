package io.github.Nyameph.nyaentworks.manga.entity;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import io.github.Nyameph.nyaentworks.manga.consts.MangaDataFileType;
import io.github.Nyameph.nyaentworks.manga.consts.MangaDataStatus;
import io.github.Nyameph.nyaentworks.manga.consts.MangaScoreSource;

/**jishu
 * <p>
 * 漫画资源表
 * </p>
 *
 * @author Nyameph
 * @since 2024-11-23
 */
@Data
@TableName("manga_data")
@Schema(name = "MangaData", description = "漫画资源表")
public class MangaData implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "文件夹地址")
    private String folderPath;

    @Schema(description = "封面文件")
    private String coverFile;

    @Schema(description = "展会")
    private String exhibit;

    @Schema(description = "社团")
    private String groupName;

    @Schema(description = "作者")
    private String artist;

    @Schema(description = "时间标签（规则2，如 2024.05）")
    private String dateTag;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "同人作品")
    private String parody;

    @Schema(description = "杂志")
    private String magazine;

    @Schema(description = "匹配到的命名规则：1 或 2，0 表示未匹配")
    private int matchedRule;

    @Schema(description = "评分 3/5/7/9")
    private Integer score;

    @Schema(description = "评分来源：SELF 单独打分 / INHERIT_ARCHIVE 继承归档目录")
    private MangaScoreSource scoreSource;

    @Schema(description = "状态：ARCHIVED / UNARCHIVED / MISSING。新漫画不入库，故无该状态")
    private MangaDataStatus status;

    @Schema(description = "所属归档目录 id")
    private Long archiveUnitId;

    @Schema(description = "文件形态：FOLDER 目录 / CBZ 压缩包（后续可扩展 pdf 等）")
    private MangaDataFileType fileType;

    @Schema(description = "目录下文件数（含子目录），扫描时算好落库，列表/抽选读库免遍历")
    private Integer fileCount;

    @Schema(description = "其中的图片数")
    private Integer imageCount;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    /* ---------------- 以下为文件名解析过程中的临时字段，不入库 ---------------- */

    @Schema(description = "汉化组等保留标签，规范化命名阶段再补全")
    @TableField(exist = false)
    private String reservedTag;

    @Schema(description = "是否無修正")
    @TableField(exist = false)
    private boolean uncensored;

    @Schema(description = "解析后用于重命名的规范化文件夹名")
    @TableField(exist = false)
    private String normalizedName;

    @Schema(description = "是否从父级获取作者名")
    @TableField(exist = false)
    private boolean groupArtistFromParent = false;

    @Schema(description = "是否从字典中匹配展会")
    @TableField(exist = false)
    private boolean exhibitInDict = false;
    @Schema(description = "是否从字典中匹配原作")
    @TableField(exist = false)
    private boolean parodyInDict = false;

    @Override
    public String toString() {
        return JSON.toJSONString(this, JSONWriter.Feature.PrettyFormat);
    }

}
