package io.github.Nyameph.nyaentworks.shout.config;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.common.media.MediaRootProvider;

import java.util.List;

/**
 * 喊麦模块申报给 {@code MediaStreamService} 的受管根：已归档根 + 待打分根。
 *
 * <p>待打分根不能漏：还没归档的喊麦就是在那里播的（同歌曲侧的立场）。
 */
@Component
@RequiredArgsConstructor
public class ShoutMediaRoots implements MediaRootProvider {

    private final ShoutProperties properties;

    @Override
    public List<String> mediaRoots() {
        return List.of(properties.getArchivedDir(), properties.getStagingDir());
    }
}
