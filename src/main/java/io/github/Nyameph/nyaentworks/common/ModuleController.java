package io.github.Nyameph.nyaentworks.common;

import io.github.Nyameph.nyaentworks.manga.config.MangaProperties;
import io.github.Nyameph.nyaentworks.shout.config.ShoutProperties;
import io.github.Nyameph.nyaentworks.song.config.SongProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 三个业务模块各自开着没有，供前端把已关闭模块的导航项与路由一起收起来。
 *
 * <p><b>为什么要有这个接口</b>：模块开关关掉后，那一侧的 Controller 根本不注册
 * （{@code @ConditionalOnManga} 等），页面上的入口却还挂着 —— 点进去每个请求都是 404，
 * 看起来像坏掉而不是像「关掉了」。开关是<b>后端</b>的事实（要重启才生效），
 * 所以由这里下发，前端照着画就行（「前端只画不判」）。
 *
 * <p>不走 {@code /api/settings}：那一页要读覆盖文件、摊开 30 项元数据，
 * 只为画个左导航不值当；这三个字段直接取各自 Properties 的开关，是纯内存读。
 *
 * <p>三个 Properties 自己<b>不</b>带条件注解（{@code SettingsCatalog} 与
 * {@code EnvCheckService} 也要读它们），所以这里注入得到，模块关掉也不会启动失败。
 */
@Tag(name = "模块开关")
@RestController
@RequestMapping("/api/modules")
@RequiredArgsConstructor
public class ModuleController {

    private final MangaProperties manga;
    private final SongProperties song;
    private final ShoutProperties shout;

    /** 键是模块 id（与 {@code settings.js} 的大标题、{@code modules.js} 的顶层项同一套），值是开着没有 */
    @Operation(summary = "三个业务模块各自的开关状态（关掉的模块前端不显示入口）")
    @GetMapping
    public ApiResult<Map<String, Boolean>> enabled() {
        Map<String, Boolean> flags = new LinkedHashMap<>();
        flags.put("manga", manga.isEnabled());
        flags.put("song", song.isEnabled());
        flags.put("shout", shout.isEnabled());
        return ApiResult.ok(flags);
    }
}
