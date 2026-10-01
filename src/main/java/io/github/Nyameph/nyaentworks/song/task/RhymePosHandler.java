package io.github.Nyameph.nyaentworks.song.task;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.song.fill.rhyme.RhymePosService;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskContext;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskHandler;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnSong;

/**
 * 补全韵脚词典的词性（{@code song.rhyme.pos}）：{@code word_class} 为空的词条逐批交给本机
 * Ollama 判词性（用户需求：「当前大量数据的词性缺失，需要调用 ollama 来判断」）。
 *
 * <p>模块键必须用 {@link SongTaskModule#SONG_FILL_AI}：AI 调用是分钟级，挂
 * {@link SongTaskModule#SONG} 会把同步 / 扫描 / 编辑全堵在后面（同 {@code FillAiGenerateHandler}）。
 *
 * <p><b>只走本机模型</b>（{@code RhymePosService} 里硬编码，不看 use-online / OnlineAiProperties）：
 * 词条来自语料与用户词表，含成人词，外发等于违反 §9.4 的数据红线。
 *
 * <p>说明行与 {@code result_json} 里只有计数 —— {@link RhymePosService.PosResult} 全是数字，
 * 任务页不会出现任何词条原文。
 */
@ConditionalOnSong
@Component
@RequiredArgsConstructor
public class RhymePosHandler implements AsyncTaskHandler {

    public static final String TYPE = "song.rhyme.pos";

    private final RhymePosService rhymePosService;

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
        return "补全词性";
    }

    @Override
    public Class<?> paramType() {
        return Void.class;
    }

    @Override
    public Object run(Object params, AsyncTaskContext context) {
        context.message("正在扫待判词条…");
        return rhymePosService.tag(context);
    }

    @Override
    public String summarizeResult(Object result) {
        if (!(result instanceof RhymePosService.PosResult r)) {
            return null;
        }
        return "待判 " + r.total() + " 词 / 判定 " + r.judged() + " 词 / 写入 " + r.updated()
                + " 行 / 失败 " + r.failed() + " 批（共 " + r.batches() + " 批）";
    }
}
