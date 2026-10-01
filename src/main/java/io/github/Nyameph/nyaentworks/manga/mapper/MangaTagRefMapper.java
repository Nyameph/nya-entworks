package io.github.Nyameph.nyaentworks.manga.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import io.github.Nyameph.nyaentworks.manga.entity.MangaTagRef;

/**
 * 标签关联 Mapper
 */
@Mapper
public interface MangaTagRefMapper extends BaseMapper<MangaTagRef> {

}
