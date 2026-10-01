package io.github.Nyameph.nyaentworks.song.fill.ai;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.github.Nyameph.nyaentworks.common.ApiResult;
import io.github.Nyameph.nyaentworks.song.task.FillAiGenerateHandler;
import io.github.Nyameph.nyaentworks.task.service.AsyncTaskService;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnSong;

/**
 * AI 填词接口（{@code /api/song/fill/{id}/ai/*}，填词助手设计 §6.1 / §6.5）。
 *
 * <p>{@code /status} 给按钮可见性；{@code /plan} 只读本机数据、不调模型，不受在线闸限制；
 * {@code /generate} 在提交任务前过 §7.4 两道闸（在线 / 本地误配都在这里拒绝）；
 * {@code /preview} 取最近一次生成结果（模型正文不落任务表，只在这里回给前端）。
 */
@Tag(name = "AI 填词")
@ConditionalOnSong
@RestController
@RequestMapping("/api/song/fill")
@RequiredArgsConstructor
public class FillAiController {

    private final FillAiService fillAiService;
    private final AsyncTaskService taskService;

    @Operation(summary = "AI 填词开关状态：enabled 决定按钮显隐；useOnline/onlineConfigured 给确认框")
    @GetMapping("/ai/status")
    public ApiResult<FillAiService.Status> status() {
        return ApiResult.ok(fillAiService.status());
    }

    @Operation(summary = "韵脚方案：每句字数 + 句尾默认韵部（可改）；带 theme+useOnline 时附完整 prompt 预览")
    @PostMapping("/ai/plan")
    public ApiResult<FillAiService.Plan> plan(@RequestBody FillAiService.PlanRequest request) {
        return ApiResult.ok(fillAiService.plan(request));
    }

    @Operation(summary = "提交生成任务：本地 Ollama 或在线端点（两道闸在这里拒绝）；返回任务 id")
    @PostMapping("/ai/generate")
    public ApiResult<Long> generate(@RequestBody FillAiService.GenerateParams params) {
        // 提交前先过闸（两道闸的拒绝要在提交时立刻可见，而不是等任务跑起来才失败）
        fillAiService.requireAllowedPublic(Boolean.TRUE.equals(params.useOnline()));
        return ApiResult.ok(taskService.submit(FillAiGenerateHandler.TYPE, params,
                "AI 填词生成（" + StringUtils.defaultString(params.mode(), "full") + "）"));
    }

    @Operation(summary = "最近一次生成的逐句预览（可编辑、带字数/押韵标记）；没有时 success=false")
    @GetMapping("/ai/preview")
    public ApiResult<FillAiService.Preview> preview(@RequestParam Long fillId) {
        FillAiService.Preview preview = fillAiService.preview(fillId);
        if (preview == null) {
            return ApiResult.fail("这一份填词还没有生成结果（先在 AI 填词抽屉里跑一次）");
        }
        return ApiResult.ok(preview);
    }
}
