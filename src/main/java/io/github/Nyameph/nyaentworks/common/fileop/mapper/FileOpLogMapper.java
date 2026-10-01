package io.github.Nyameph.nyaentworks.common.fileop.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import io.github.Nyameph.nyaentworks.common.fileop.entity.FileOpLog;

/**
 * 改动流水表。只用到 {@code insert} 与分页查询两条路径
 * （分页不用插件，手写 count + LIMIT，样板见 {@code MangaDictService#page}）。
 * <p>{@code @MapperScan("io.github.Nyameph.nyaentworks.**.mapper")} 已经覆盖这个包，
 * {@code @Mapper} 只是防手滑（与 {@code AsyncTaskMapper} 同）。
 */
@Mapper
public interface FileOpLogMapper extends BaseMapper<FileOpLog> {
}
