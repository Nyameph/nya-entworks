package io.github.Nyameph.nyaentworks.song.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import io.github.Nyameph.nyaentworks.song.entity.SongFile;

/**
 * 合并条目下文件清单 Mapper。
 */
@Mapper
public interface SongFileMapper extends BaseMapper<SongFile> {

}
