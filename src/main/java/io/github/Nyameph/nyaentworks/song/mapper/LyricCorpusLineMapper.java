package io.github.Nyameph.nyaentworks.song.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Mapper;
import io.github.Nyameph.nyaentworks.song.entity.LyricCorpusLine;

import java.util.List;

/** 语料句（{@code lyric_corpus_line}）。重扫 = 全表删掉重建，写入用整批 INSERT。 */
@Mapper
public interface LyricCorpusLineMapper extends BaseMapper<LyricCorpusLine> {

    /**
     * 整批插入（重扫先 DELETE 全表，无需幂等）。
     *
     * <p>时间戳由<b>调用方在 Java 里填好</b>（{@code CorpusService#collectGroup}），
     * 不取 SQL 里的「当前时间」：{@code CURRENT_TIMESTAMP} 虽然两个库都认，但含义不同 ——
     * SQLite 返回 UTC、MySQL 返回会话本地时间，同一张表会混出两种时刻。这里本来就是个
     * 自定义 {@code @Insert}，MetaObjectHandler 的自动填充够不着它，索性显式传值 ——
     * 与其余各表由 MetaObjectHandler 用 Java 时间填充的口径也就一致了。
     */
    @Insert("<script>INSERT INTO lyric_corpus_line"
            + " (group_id, kind, group_key, file_name, original_title, score, line_index,"
            + "  text, tail_char, tail_pinyin, finals, yun18, create_time, update_time) VALUES"
            + "<foreach collection='list' item='e' separator=','>"
            + " (#{e.groupId}, #{e.kind}, #{e.groupKey}, #{e.fileName}, #{e.originalTitle},"
            + "  #{e.score}, #{e.lineIndex}, #{e.text}, #{e.tailChar}, #{e.tailPinyin},"
            + "  #{e.finals}, #{e.yun18}, #{e.createTime}, #{e.updateTime})"
            + "</foreach></script>")
    int insertBatch(@Param("list") List<LyricCorpusLine> lines);
}
