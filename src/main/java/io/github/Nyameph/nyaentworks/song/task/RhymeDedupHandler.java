package io.github.Nyameph.nyaentworks.song.task;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.song.fill.rhyme.RhymeDedupService;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskContext;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskHandler;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnSong;

/**
 * 合并重复词（{@code song.rhyme.dedup}，填词助手设计 §4.4）：把「同一个词、同一个音」的
 * 重复行并成一行。没有它，唯一键 {@code uk_text_pinyin(text, pinyin)} 加不上（存量重复会
 * 让 {@code ADD UNIQUE} 直接失败），加上了也会被生产任务灌回来。
 *
 * <p>挂 {@link SongTaskModule#SONG}：这是<b>纯 DB 写</b>，与同队列的
 * {@code SongRhymeSeedHandler} / {@code RhymeWordlistHandler} / {@code SongCorpusRescanHandler}
 * 同类 —— 那三个任务末尾都会自动调一次 {@link RhymeDedupService}，所以这条队列里的顺序
 * 就是「先生产、后收拾」。{@link SongTaskModule#SONG_FILL_AI} 是留给分钟级 AI 调用的。
 *
 * <p>无参任务（{@code paramType = Void.class}）：口径全在 service 里写死，重跑不需要
 * {@code params_json}。规则全从组内现算，所以是<b>幂等</b>的：第二次执行应显示 0 处修改。
 *
 * <p><b>数据红线（§9.4）</b>：说明行与 {@code result_json} 里只有计数 ——
 * {@link RhymeDedupService.DedupResult} 全是数字，任务页不会出现任何词条原文。
 */
@ConditionalOnSong
@Component
@RequiredArgsConstructor
public class RhymeDedupHandler implements AsyncTaskHandler {

    public static final String TYPE = "song.rhyme.dedup";

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
        return "合并重复词";
    }

    @Override
    public Class<?> paramType() {
        return Void.class;
    }

    @Override
    public Object run(Object params, AsyncTaskContext context) {
        return rhymeDedupService.dedup(context);
    }

    @Override
    public String summarizeResult(Object result) {
        if (!(result instanceof RhymeDedupService.DedupResult r)) {
            return null;
        }
        return "查 " + r.scanned() + " 行 / 合并掉 " + r.deleted() + " 行 / 补齐字段 "
                + r.updated() + " 行 / 修正类型 " + r.entryTypeFixed() + " 行"
                + (r.failed() > 0 ? " / 读音拆不出跳过 " + r.failed() + " 行" : "")
                + "（第二次执行应显示 0 处修改）";
    }
}
