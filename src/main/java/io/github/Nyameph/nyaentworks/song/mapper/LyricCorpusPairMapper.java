package io.github.Nyameph.nyaentworks.song.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import io.github.Nyameph.nyaentworks.song.entity.LyricCorpusPair;

/** 语料配对（{@code lyric_corpus_pair}）。写入走保存钩子的 diff（upsert / 删行）。 */
@Mapper
public interface LyricCorpusPairMapper extends BaseMapper<LyricCorpusPair> {
}
