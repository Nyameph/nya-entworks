package io.github.Nyameph.nyaentworks.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import io.github.Nyameph.nyaentworks.task.entity.AsyncTask;

/**
 * 异步任务表。
 * <p>{@code @MapperScan("io.github.Nyameph.nyaentworks.**.mapper")} 已经覆盖这个包，
 * {@code @Mapper} 只是防手滑（与 {@code SongGroupMapper} 同）。
 */
@Mapper
public interface AsyncTaskMapper extends BaseMapper<AsyncTask> {
}
