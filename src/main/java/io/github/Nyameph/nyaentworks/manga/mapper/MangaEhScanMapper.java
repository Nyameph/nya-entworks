package io.github.Nyameph.nyaentworks.manga.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import io.github.Nyameph.nyaentworks.manga.entity.MangaEhScan;

/**
 * 漫画 e-hentai 扫描结果 Mapper。
 */
@Mapper
public interface MangaEhScanMapper extends BaseMapper<MangaEhScan> {
}
