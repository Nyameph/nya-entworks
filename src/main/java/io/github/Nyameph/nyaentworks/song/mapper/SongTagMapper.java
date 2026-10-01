package io.github.Nyameph.nyaentworks.song.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import io.github.Nyameph.nyaentworks.song.entity.SongTag;

/**
 * 歌曲合并条目标签 Mapper。
 * <p>{@code @MapperScan("io.github.Nyameph.nyaentworks.**.mapper")} 已覆盖，{@code @Mapper}
 * 加上是防手滑（与模块内其它 mapper 一致）。
 */
@Mapper
public interface SongTagMapper extends BaseMapper<SongTag> {

}
