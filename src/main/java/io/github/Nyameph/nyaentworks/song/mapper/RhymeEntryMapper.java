package io.github.Nyameph.nyaentworks.song.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import io.github.Nyameph.nyaentworks.song.entity.RhymeEntry;

import java.util.List;

/** 韵脚词典（{@code rhyme_entry}） */
@Mapper
public interface RhymeEntryMapper extends BaseMapper<RhymeEntry> {

    /**
     * 去重任务的候选行：<b>只取「同一个 text 出现多行」的</b>，不拉全表。
     *
     * <p>全表约 5.8 万行，而真正可能重复的是「一个词多行」的那些（实测 853 个 text / 1791 行），
     * 全表拉回来纯属浪费。子查询套一层派生表：MySQL 的 {@code IN (SELECT ... FROM 同一张表)}
     * 在 SELECT 里是允许的，但套一层才在各类语句里都稳（这个写法将来被挪进 UPDATE 也不会踩坑）。
     */
    @Select("SELECT * FROM rhyme_entry WHERE text IN ("
            + "SELECT text FROM (SELECT text FROM rhyme_entry GROUP BY text HAVING COUNT(*) > 1) t)")
    List<RhymeEntry> selectDuplicateCandidates();

    /**
     * 批量 {@code INSERT IGNORE}：靠 {@code uk_text_pinyin(text, pinyin)} 幂等 ——
     * 种子重跑、词表重导都只补新增，不覆盖已有行（人工添加的行永远优先）。
     * 时间戳不走 {@code FieldFill}（那是 insert/update 填充器的路），直接取当前时间。
     *
     * <p><b>语句在 {@code mapper/RhymeEntryMapper.xml}</b>（不在注解里）：它有一条
     * {@code INSERT OR IGNORE} 的 SQLite 版本，而注解上的语句没有 {@code databaseId} 可言。
     */
    int insertIgnoreBatch(@Param("list") List<RhymeEntry> entries);

    /**
     * 批量 {@code INSERT ... ON DUPLICATE KEY UPDATE}，<b>只给 source=CORPUS 的语料词用</b>。
     *
     * <p><b>为什么不能沿用 {@link #insertIgnoreBatch}</b>：语料词与 XLSX / MANUAL 撞
     * {@code uk_text_pinyin(text, pinyin)} 时 INSERT IGNORE 整行丢掉，于是
     * {@code freq} 永远进不去 —— 而那正是 §4.5 第 2 条要靠来提权的字段（既有的种子 / 词表
     * 导入仍然走 IGNORE，幂等语义不变）。
     *
     * <p><b>SQL 的语义</b>：{@code ON DUPLICATE KEY UPDATE} 里 {@code VALUES(col)} 取的是
     * <b>本次待插入</b>的值，裸 {@code col} 取的是<b>已有行</b>的值（两者不是一回事，所以才要用
     * IF 分开），因此
     * {@code freq = IF(source = 'CORPUS', VALUES(freq), freq)} 读作：撞上的已有行是语料词 →
     * 用新计数覆盖；已有行是 XLSX / MANUAL / MODERN → 把原值写回自己（等于不动）—— 这就是
     * §4.4 第 8 条「人工添加的行永远优先」，也是为什么条件看的是<b>已有行</b>的 source。
     * 其余列一律不赋值：语料侧只提供 freq 一个新信息，撞上人工行时连 update_time 都不许碰。
     *
     * <p>{@code VALUES()} 在 8.0.20 之后是「已废弃但可用」（本机 8.0.45，执行会带一条
     * warning）；要换新写法得改成行别名 {@code ... VALUES (...) AS new ON DUPLICATE KEY
     * UPDATE freq = IF(source = 'CORPUS', new.freq, freq)}，语义一样，但那要求 8.0.19+，
     * 这里沿用 §4.5 第 2 条写定的写法。
     *
     * <p><b>语句在 {@code mapper/RhymeEntryMapper.xml}</b>，两个 {@code databaseId} 各一份：
     * MySQL 那份就是上面这套 {@code ON DUPLICATE KEY}；SQLite 那份是
     * {@code ON CONFLICT(text, pinyin) DO UPDATE}，其中 {@code excluded.} 对应 {@code VALUES()}、
     * 裸列名对应 MySQL 的裸列 —— 两句话语义逐字对齐。那边还有一个前提：建表脚本里必须有
     * 完全同形的 {@code UNIQUE(text, pinyin)}，否则 {@code ON CONFLICT} 匹配不上约束。
     */
    int upsertCorpusBatch(@Param("list") List<RhymeEntry> entries);
}
