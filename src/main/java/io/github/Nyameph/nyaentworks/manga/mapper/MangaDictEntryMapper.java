package io.github.Nyameph.nyaentworks.manga.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import io.github.Nyameph.nyaentworks.manga.entity.MangaDictEntry;

/**
 * 漫画词典条目 Mapper
 */
@Mapper
public interface MangaDictEntryMapper extends BaseMapper<MangaDictEntry> {

}
