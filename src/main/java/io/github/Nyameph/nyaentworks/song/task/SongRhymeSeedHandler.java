package io.github.Nyameph.nyaentworks.song.task;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.song.fill.rhyme.RhymeDedupService;
import io.github.Nyameph.nyaentworks.song.fill.rhyme.RhymeService;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskContext;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskHandler;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnSong;

/**
 * 重新生成单字词典（词典页「管理」页签的按钮，填词助手设计 §4.3）。
 *
 * <p>与启动种子（{@code RhymeSeedRunner}）共用同一个 service 方法，但带 {@code force=true}：
 * 已有种子也重跑一遍 —— INSERT IGNORE 靠唯一键幂等，字表更新后重跑只补新增，不会重复。
 */
@ConditionalOnSong
@Component
@RequiredArgsConstructor
public class SongRhymeSeedHandler implements AsyncTaskHandler {

    public static final String TYPE = "song.rhyme.seed";

    private final RhymeService rhymeService;
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
        return "重新生成单字词典";
    }

    @Override
    public Class<?> paramType() {
        return Void.class;
    }

    @Override
    public Object run(Object params, AsyncTaskContext context) {
        context.message("正在展开现代规范字表…");
        RhymeService.SeedResult result = rhymeService.seedModern(true);
        // 末尾自动去重：force=true 会把合并掉的 MODERN 行重新灌回来（它已经不在表里，
        // INSERT IGNORE 拦不住），所以种子跑完必须收拾一次，否则新唯一键迟早被撞
        context.message("正在合并重复词…");
        RhymeDedupService.DedupResult merged = rhymeDedupService.dedup(context);
        context.log("顺带合并重复词：删除 " + merged.deleted() + " 行 / 补齐 "
                + merged.updated() + " 行");
        return result;
    }

    @Override
    public String summarizeResult(Object result) {
        if (!(result instanceof RhymeService.SeedResult r)) {
            return null;
        }
        return "写入 " + r.inserted() + " 行（展开 " + r.built() + " 行，重复忽略 "
                + (r.built() - r.inserted()) + " 行）";
    }
}
