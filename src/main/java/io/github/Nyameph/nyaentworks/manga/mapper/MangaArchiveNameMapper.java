package io.github.Nyameph.nyaentworks.manga.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import io.github.Nyameph.nyaentworks.manga.entity.MangaArchiveName;

/**
 * 归档社团名/作者名别名 Mapper
 */
@Mapper
public interface MangaArchiveNameMapper extends BaseMapper<MangaArchiveName> {

}
