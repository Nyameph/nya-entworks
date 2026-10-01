package io.github.Nyameph.nyaentworks.song.task;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.song.fill.rhyme.RhymeDedupService;
import io.github.Nyameph.nyaentworks.song.fill.rhyme.RhymeWordlistService;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskContext;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskHandler;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnSong;

/**
 * 导入开源词表（{@code rhyme_entry} 的 {@code source=OPEN} 词条，填词助手设计 §12）。
 *
 * <p>挂在 {@link SongTaskModule#SONG} 队列：这是<b>纯 DB 写</b>，与同队列的
 * {@code SongRhymeSeedHandler}（重新生成单字词典）、{@code SongCorpusRescanHandler}（重扫语料）
 * 同类 —— 都往 {@code rhyme_entry} 写。{@link SongTaskModule#SONG_FILL_AI} 是留给本机 7b
 * 生成那种分钟级 AI 调用的，导入不该占它。
 *
 * <p>无参任务（{@code paramType = Void.class}）：读哪个文件由
 * {@link RhymeWordlistService#WORDLIST} 写死，不是运行时参数。重跑（任务页「重新执行」）
 * 因此不需要 {@code params_json} —— 也正因如此，重跑只补新增（{@code INSERT IGNORE} 幂等）。
 */
@ConditionalOnSong
@Component
@RequiredArgsConstructor
public class RhymeWordlistHandler implements AsyncTaskHandler {

    public static final String TYPE = "song.rhyme.import-open";

    private final RhymeWordlistService wordlistService;
    private final RhymeDedupService rhymeDedupService;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String module() {
        return SongTaskModule.SONG;
    }

    @Override
    public String taskName() {
        return "导入开源词表";
    }

    @Override
    public Class<?> paramType() {
        return Void.class;
    }

    @Override
    public Object run(Object params, AsyncTaskContext context) throws Exception {
        context.message("正在读词表…");
        RhymeWordlistService.ImportResult result = wordlistService.importOpen(context);
        // 末尾自动去重：重导会把合并掉的 OPEN 轻声行灌回来（词表里本来就写着那些读音）
        context.message("正在合并重复词…");
        RhymeDedupService.DedupResult merged = rhymeDedupService.dedup(context);
        context.log("顺带合并重复词：删除 " + merged.deleted() + " 行 / 补齐 "
                + merged.updated() + " 行");
        return result;
    }

    @Override
    public String summarizeResult(Object result) {
        if (!(result instanceof RhymeWordlistService.ImportResult r)) {
            return null;
        }
        return "扫描 " + r.total() + " 行 / 写入 " + r.inserted() + " 行 / 已有跳过 "
                + r.skipped() + " 行 / 解析失败 " + r.failed() + " 行（共 " + r.batches() + " 批）";
    }
}
