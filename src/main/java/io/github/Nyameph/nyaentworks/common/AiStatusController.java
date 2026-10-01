package io.github.Nyameph.nyaentworks.common;

import io.github.Nyameph.nyaentworks.common.ai.AiAvailability;
import io.github.Nyameph.nyaentworks.common.config.LocalAiProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本机 AI 端点配全了没有，供前端把用不上的 AI 按钮收起来。
 *
 * <p>与 {@code /api/modules} 同一个道理：<b>可用性是后端的事实</b>（配置在启动时绑定，
 * 改完要重启），前端只照着画。韵脚词典页的「补全词性」按钮就靠它 ——
 * 没配端点时那个按钮点下去只会得到一个失败任务，不如不画。
 *
 * <p>AI 填词不需要这个接口：它的 {@code /api/song/fill/ai/status} 已经把可用性
 * 连同「走本地还是在线」一起下发了（{@code FillAiService.Status}）。
 */
@Tag(name = "AI 可用性")
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiStatusController {

    private final AiAvailability aiAvailability;
    private final LocalAiProperties localAi;

    /**
     * @param ready    本机端点（{@code common.local-ai} 的 base-url 与 model）配全了没有
     * @param baseUrl  当前端点，给页面显示「你指的是哪儿」
     * @param model    当前模型，同上
     * @param reason   没配全时说清缺哪一项；配全了为 {@code null}
     */
    public record AiStatus(boolean ready, String baseUrl, String model, String reason) {
    }

    @Operation(summary = "本机 AI 端点/模型配全没有，没配全的原因是什么")
    @GetMapping("/status")
    public ApiResult<AiStatus> status() {
        return ApiResult.ok(new AiStatus(aiAvailability.localReady(),
                localAi.getBaseUrl(), localAi.getModel(), aiAvailability.localDeny()));
    }
}
