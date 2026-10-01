package io.github.Nyameph.nyaentworks.song.task;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.song.fill.ai.FillAiService;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskContext;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskHandler;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnSong;

/**
 * AI 填词生成任务（填词助手设计 §6.5）。type 点分三段；模块键必须用
 * {@link SongTaskModule#SONG_FILL_AI}——同 module 串行，AI 生成分钟级，
 * 挂歌曲队列会把同步 / 扫描全堵住。
 *
 * <p>不做断点续传：失败 / 中断就整篇重跑（结果只进预览，不覆盖已填内容）；
 * 启动时残留任务由框架置 INTERRUPTED，人在任务页点「重新执行」。
 */
@ConditionalOnSong
@Component
@RequiredArgsConstructor
public class FillAiGenerateHandler implements AsyncTaskHandler {

    public static final String TYPE = "song.fill.generate";

    private final FillAiService fillAiService;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String module() {
        return SongTaskModule.SONG_FILL_AI;
    }

    @Override
    public String taskName() {
        return "AI 填词生成";
    }

    @Override
    public Class<?> paramType() {
        return FillAiService.GenerateParams.class;
    }

    @Override
    public Object run(Object params, AsyncTaskContext context) {
        context.message("正在组装提示词…");
        return fillAiService.generate((FillAiService.GenerateParams) params, context);
    }

    @Override
    public String summarizeResult(Object result) {
        if (!(result instanceof FillAiService.GenerateStats s)) {
            return null;
        }
        return "生成 " + s.totalLines() + " 句、" + s.batches() + " 批，重试 " + s.retries()
                + " 次，不合格 " + s.invalidLines() + " 句（去填词页逐句过目后应用）";
    }
}
