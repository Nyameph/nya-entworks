package io.github.Nyameph.nyaentworks.song.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import io.github.Nyameph.nyaentworks.song.entity.SongLyricFill;

/** 填词项目（{@code song_lyric_fill}） */
@Mapper
public interface SongLyricFillMapper extends BaseMapper<SongLyricFill> {
}
