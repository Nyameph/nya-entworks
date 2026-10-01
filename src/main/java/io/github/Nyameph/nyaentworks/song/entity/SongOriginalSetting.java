package io.github.Nyameph.nyaentworks.song.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <p>
 * 原曲名级设置：同一首原曲的所有填词版本共享（实现说明第 4 节）
 * </p>
 *
 * <p>倍速三级回落里的第 3 级（当前 &gt; 组 &gt; 原曲名 &gt; 1.0）。用处是
 * 「某首原曲整体偏慢」的一次性设置，省得逐首歌调。
 *
 * <p>主键是自增代理键（surrogate key），与业务字段无关；唯一键是 {@code (raw_name, artist)}
 * —— 同一首原曲名可被不同作者唱（同名原曲），靠作者区分。旧库主键是归一化原曲名，
 * 那次换主键的迁移是**一次性动作**，已在本机执行完毕（SQL 文件已删）；
 * {@code db/nya_entworks.sql} 里已是目标形态（自增 id + {@code uk_raw_artist}）。
 *
 * @author Nyameph
 */
@Data
@TableName("song_original_setting")
@Schema(name = "SongOriginalSetting", description = "原曲名级设置")
public class SongOriginalSetting implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 自增代理主键，与 raw_name/artist 无业务关联 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "原曲名原文，供展示")
    private String rawName;

    @Schema(description = "歌手（原唱），供展示")
    private String artist;

    @Schema(description = "歌手已手动确认（不再被搜索补全覆盖）")
    private Boolean artistCheck;

    @Schema(description = "默认倍速。null 表示没设过，回落到 1.0")
    private BigDecimal defaultRate;

    @Schema(description = "模板 BPM：优先取 svp 工程的曲速，其次 midi 文件内容里的 tempo，最后 midi 文件名里的 [BPM=NN]；null 表示没提取到")
    private BigDecimal bpm;

    @Schema(description = "原曲文件名（含扩展名），未识别为 null")
    private String originalFileName;

    @Schema(description = "原曲文件已手动确认（不再被扫描/搜索覆盖）")
    private Boolean originalCheck;

    @Schema(description = "样例音频文件名（含扩展名），模板导出的 MixDown / 原曲打样 / 原词打样")
    private String demoFileName;

    @Schema(description = "样例音频歌词文件名（含扩展名），与样例音频除后缀名外同名，未识别为 null")
    private String demoLrcFileName;

    @Schema(description = "伴奏音频文件名（含扩展名），未识别为 null")
    private String accompanimentFileName;

    @Schema(description = "纯人声音频文件名（含扩展名），未识别为 null")
    private String vocalsFileName;

    @Schema(description = "歌词文件名（含扩展名），未识别为 null")
    private String lyricFileName;

    @Schema(description = "歌词文件已手动确认（不再被扫描/搜索覆盖）")
    private Boolean lyricCheck;

    @Schema(description = "mid 文件名（含扩展名），未识别为 null")
    private String midFileName;

    @Schema(description = "svp 模板文件名（含扩展名），未识别为 null")
    private String svpFileName;

    @Schema(description = "svp 模板已手动确认（不再被扫描覆盖）")
    private Boolean svpCheck;

    @Schema(description = "需要手动判断（文件类型/匹配无法准确判定）")
    private Boolean needManualJudge;

    @Schema(description = "原第2层文件夹名（模板原名）")
    private String templateFolderName;

    @Schema(description = "是否有其他文件（已识别六类之外）")
    private Boolean hasOtherFile;

    @Schema(description = "最近一次成功搜索时间（批量搜索据此跳过近期已搜过的原曲）")
    private LocalDateTime lastSearchTime;

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
