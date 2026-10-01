package io.github.Nyameph.nyaentworks.song.entity;

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
 * 韵脚词典的一个词条：按韵部索引的字 / 词（填词助手设计 §4）。
 *
 * <p><b>多音字一音一行</b>：唯一键 {@code uk_text_pinyin(text, pinyin)} 含读音，
 * 所以「按字反查」返回全部读音的行、「按韵查」命中任意一行，候选自带读音。
 * {@code pinyin} 必须 NOT NULL —— MySQL 唯一键里 NULL 不参与去重，允许 NULL 会让
 * 重跑种子插出重复行。
 *
 * <p><b>韵部归属由 {@code PinyinSyllable} 算</b>，不在库里手写映射；查询键是
 * {@code rhyme_body}（韵身，机器口径），{@code yun18} 只是给人看的展示名。
 *
 * <p><b>来源 source</b>：MODERN 种子（现代规范字表全展开，启动后台线程写一次）/
 * XLSX 用户词表导入 / MANUAL 词典页手动添加 / CORPUS 语料句尾词回填。
 * 重扫只删 CORPUS，其余三种永不删。
 *
 * <p>不是磁盘镜像表，没有进 {@code tools/mysql.py} 的 MIRROR_TABLES
 * —— 人工批量 INSERT 是合法需求（MANUAL 来源）。
 */
@Data
@TableName("rhyme_entry")
@Schema(name = "RhymeEntry", description = "韵脚词典词条")
public class RhymeEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 自增代理主键。全局 id-type: input，必须显式 AUTO */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "CHAR 单字 / WORD 词")
    private String entryType;

    @Schema(description = "字或词原文")
    private String text;

    @Schema(description = "带调主读音（多音字一音一行）")
    private String pinyin;

    @Schema(description = "韵母（含介音，舌尖元音写作 -i），如 ang / -i")
    private String finals;

    @Schema(description = "韵身（去介音），18 个值之一。查询的实际键")
    private String rhymeBody;

    @Schema(description = "十八韵名，如 十六唐（与 rhymeBody 一一对应，展示用）")
    private String yun18;

    @Schema(description = "词性，自由文本（用户词表的列头原样）")
    private String wordClass;

    @Schema(description = "MODERN / XLSX / MANUAL / CORPUS")
    private String source;

    @Schema(description = "常用度：1一级/2二级/3三级/0未知（PinyinUtil.tier；排序第一键）")
    private Integer tier;

    @Schema(description = "语料出现次数（只对 source=CORPUS 有意义）")
    private Integer freq;

    @Schema(description = "导入时与尾字读音不符等提示")
    private String note;

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
