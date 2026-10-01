package io.github.Nyameph.nyaentworks.song.task;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.song.fill.corpus.CorpusService;
import io.github.Nyameph.nyaentworks.song.fill.rhyme.RhymeDedupService;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskContext;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskHandler;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnSong;

/**
 * 重扫歌词语料（填词助手设计 §5.5）：删掉重建 {@code lyric_corpus_line} 与 CORPUS 词条。
 * 点分三段 type（同 {@code song.template.scan} 惯例）；与歌曲同步、模板扫描同队列串行
 * —— 这是对的，它本来就该跟扫描错开。预计 765 + 31 组、分钟级，必须走异步任务。
 */
@ConditionalOnSong
@Component
@RequiredArgsConstructor
public class SongCorpusRescanHandler implements AsyncTaskHandler {

    public static final String TYPE = "song.corpus.rescan";

    private final CorpusService corpusService;
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
        return "重扫歌词语料";
    }

    @Override
    public Class<?> paramType() {
        return Void.class;
    }

    @Override
    public Object run(Object params, AsyncTaskContext context) {
        context.message("正在清空旧语料…");
        corpusService.resetForRescan();

        context.message("正在扫组和歌词文件…");
        var groups = corpusService.planGroups();
        int songLines = 0;
        int shoutLines = 0;
        int skippedNoLyric = 0;
        int skippedLong = 0;
        for (int i = 0; i < groups.size(); i++) {
            CorpusService.CorpusGroup group = groups.get(i);
            context.progress(i, groups.size(), "正在处理：" + group.displayName());
            CorpusService.CollectCount collected = corpusService.collectGroup(group);
            skippedLong += collected.skippedLong();
            int lines = collected.lines();
            if (lines == 0) {
                skippedNoLyric++;
            } else if (CorpusService.KIND_SHOUT.equals(group.kind())) {
                shoutLines += lines;
            } else {
                songLines += lines;
            }
        }
        context.progress(groups.size(), groups.size(), "正在回填句尾词进词典…");
        int words = corpusService.rebuildCorpusWords();
        // 末尾自动去重：resetForRescan 先删光 CORPUS 再重建，合并掉的行会被原样灌回来
        // （多字词的轻声对就在其中）。不在这里收拾，新唯一键迟早被撞。
        context.message("正在合并重复词…");
        RhymeDedupService.DedupResult merged = rhymeDedupService.dedup(context);
        context.log("顺带合并重复词：删除 " + merged.deleted() + " 行 / 补齐 "
                + merged.updated() + " 行");
        return new CorpusService.RescanStats(groups.size(), songLines, shoutLines,
                skippedNoLyric, skippedLong, words);
    }

    @Override
    public String summarizeResult(Object result) {
        if (!(result instanceof CorpusService.RescanStats r)) {
            return null;
        }
        return "扫 " + r.scanned() + " 组 / 入库 " + (r.songLines() + r.shoutLines()) + " 句"
                + "（喊麦 " + r.shoutLines() + "）/ 无歌词 " + r.skippedNoLyric()
                + " 组 / 超长丢弃 " + r.skippedLong()
                + " 句 / 语料词条 " + r.corpusWords() + " 条";
    }
}
