package io.github.Nyameph.nyaentworks.manga.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 单本漫画的 e-hentai 标签扫描结果，一张漫画一条最新记录（{@code manga_id} 唯一）。
 * <p>只记「匹配到了谁、多可信、建议了哪些标签」，标签本体不落这张表 —— 统一走
 * {@code manga_tag} + {@code manga_tag_ref(MANGA_DATA)}，避免两套标签存储。
 *
 * @author Nyameph
 */
@Data
@TableName("manga_eh_scan")
@Schema(name = "MangaEhScan", description = "漫画 e-hentai 扫描结果")
public class MangaEhScan implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 扫描状态：SUCCESS / NOT_FOUND / NO_MATCH / FAILED */
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_NOT_FOUND = "NOT_FOUND";
    public static final String STATUS_NO_MATCH = "NO_MATCH";
    public static final String STATUS_FAILED = "FAILED";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "漫画 id（→ manga_data.id）")
    private Long mangaId;

    @Schema(description = "SUCCESS / NOT_FOUND / NO_MATCH / FAILED")
    private String status;

    @Schema(description = "匹配到的 gallery gid")
    private Long galleryGid;

    @Schema(description = "匹配到的 gallery token")
    private String galleryToken;

    @Schema(description = "匹配到的 gallery 标题")
    private String galleryTitle;

    @Schema(description = "匹配到的 gallery 日文标题（title_jpn，可能为空）")
    private String galleryTitleJpn;

    @Schema(description = "匹配方式：MANUAL / TITLE_EXACT / AI / THUMBNAIL / LOCAL_TITLE_EXACT / LOCAL_TITLE_CONTAINS / LOCAL_META_STRONG / LOCAL_META")
    private String matchMethod;

    @Schema(description = "0~1 相似度")
    private Double matchScore;

    @Schema(description = "合并后建议标签数")
    private Integer tagCount;

    @Schema(description = "候选列表 JSON，审计+重跑")
    private String candidatesJson;

    @Schema(description = "合并后的建议标签 JSON [{namespace,tagEn,tagZh}]，apply 读它")
    private String suggestedTagsJson;

    @Schema(description = "FAILED 时的错误信息")
    private String errorMessage;

    @Schema(description = "扫描时间")
    private LocalDateTime scannedAt;
}
