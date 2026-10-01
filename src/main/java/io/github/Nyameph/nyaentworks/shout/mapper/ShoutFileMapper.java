package io.github.Nyameph.nyaentworks.shout.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import io.github.Nyameph.nyaentworks.shout.entity.ShoutFile;

/**
 * 归档喊麦组文件清单 Mapper。
 * <p>{@code @MapperScan("io.github.Nyameph.nyaentworks.**.mapper")} 已覆盖，{@code @Mapper}
 * 加上是防手滑（与项目内其它 mapper 一致）。
 */
@Mapper
public interface ShoutFileMapper extends BaseMapper<ShoutFile> {

}
