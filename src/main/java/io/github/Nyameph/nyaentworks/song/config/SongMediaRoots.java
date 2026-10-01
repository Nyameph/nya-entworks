package io.github.Nyameph.nyaentworks.song.config;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.common.media.MediaRootProvider;

import java.util.List;

/**
 * 歌曲模块申报给 {@code MediaStreamService} 的受管根：已归档根 + 待打分根 +
 * 模板根 + 仅原曲根（原曲页的模板文件播放要用）。喊麦的两个根由
 * {@link io.github.Nyameph.nyaentworks.shout.config.ShoutMediaRoots} 申报。
 *
 * <p>待打分根不能漏：待打分区的歌就是在那里播的，漏了就「还没归档的歌全都播不了」。
 */
@Component
@RequiredArgsConstructor
public class SongMediaRoots implements MediaRootProvider {

    private final SongProperties properties;

    @Override
    public List<String> mediaRoots() {
        return List.of(
                properties.getSongDir(),
                properties.getStagingDir(),
                properties.getTemplateDir(),
                properties.getOnlyOriginalDir());
    }
}
