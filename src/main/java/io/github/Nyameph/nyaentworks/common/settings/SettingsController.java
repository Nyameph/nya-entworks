package io.github.Nyameph.nyaentworks.common.settings;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.github.Nyameph.nyaentworks.common.ApiResult;

import java.util.Map;

/**
 * 本机路径与常用开关的配置页接口（页面左下角的齿轮）。
 *
 * <p>与项目其余写操作同一条规矩：<b>预演-提交</b>。{@link #plan} 只读不落盘，
 * 把「会改动哪几项」摆给用户看，确认后才 {@link #save}。
 * 与磁盘搬动不同的是，这里的预演只是给人看清单，<b>不走异步任务框架</b> ——
 * 那个框架是为不可回滚的批量操作立的，而一个配置文件随时可以改回来（还留了一份 .bak）。
 */
@Tag(name = "配置")
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService settingsService;
    private final PageGates pageGates;

    @Operation(summary = "全部设置项：现在生效的值、覆盖文件里写没写、改完要不要重启后端")
    @GetMapping
    public ApiResult<SettingsService.View> view() {
        return ApiResult.ok(settingsService.view());
    }

    /**
     * 缺了最低限度运行配置的磁盘路径项，供前端的<b>按页遮罩</b>用（见 {@link PageGates}）。
     *
     * <p>单开一个端点而不是塞进 {@link #view()}：那一份是配置页自己的元数据，
     * 每次打开配置页都要拉全量；而遮罩在<b>每次切页</b>时都要问一句，
     * 两边读的还是<b>不同的值</b>（那边判「重启后会生效的值」，这边判进程此刻的值）——
     * 混在一份里迟早会被判错。
     */
    @Operation(summary = "缺的磁盘路径项：空着、或填了但磁盘上没有，都算缺")
    @GetMapping("/missing-paths")
    public ApiResult<Map<String, PageGates.Gap>> missingPaths() {
        return ApiResult.ok(pageGates.gaps());
    }

    /** 提交的是「键 → 值」。值为 null 表示把这一项从覆盖文件里删掉（恢复出厂） */
    public record SaveRequest(Map<String, String> values) {
    }

    @Operation(summary = "预演：会改动哪几项、有哪些填得不对（只读，不落盘）")
    @PostMapping("/plan")
    public ApiResult<SettingsService.Plan> plan(@RequestBody SaveRequest request) {
        return ApiResult.ok(settingsService.plan(valuesOf(request)));
    }

    @Operation(summary = "保存到 config/nya-entworks.yaml。改完需要重启后端才生效")
    @PutMapping
    public ApiResult<SettingsService.SaveResult> save(@RequestBody SaveRequest request) {
        return ApiResult.ok(settingsService.save(valuesOf(request)));
    }

    private static Map<String, String> valuesOf(SaveRequest request) {
        return request == null || request.values() == null ? Map.of() : request.values();
    }
}
