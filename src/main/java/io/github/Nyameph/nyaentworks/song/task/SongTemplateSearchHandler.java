package io.github.Nyameph.nyaentworks.song.task;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.song.controller.SongTemplateController;
import io.github.Nyameph.nyaentworks.song.service.SongResourceSearchService;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskContext;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskHandler;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnSong;
import io.github.Nyameph.nyaentworks.common.fileop.FileOpRecorder;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpModule;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpSource;

/**
 * 从网络搜索并补全某原曲的歌手 / 原曲 / 歌词。要联网 + 各源限流冷却，秒到分钟级，
 * 所以走后台任务。
 */
@ConditionalOnSong
@Component
@RequiredArgsConstructor
public class SongTemplateSearchHandler implements AsyncTaskHandler {

    public static final String TYPE = "song.template.search";

    private final SongResourceSearchService searchService;

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
        return "搜索补全原曲";
    }

    @Override
    public Class<?> paramType() {
        return SongTemplateController.SearchRequest.class;
    }

    @Override
    public Object run(Object params, AsyncTaskContext context) {
        SongTemplateController.SearchRequest p = (SongTemplateController.SearchRequest) params;
        context.message("正在搜「" + p.originalTitle() + "」的资源…");
        // 页面侧的批次（TASK）：落盘记录的 source 由最外层给（脚本侧 fetch() 给 SCRIPT）
        return FileOpRecorder.batch(FileOpModule.SONG, FileOpSource.TASK, context.taskId(),
                () -> searchService.searchOne(p.originalTitle(), p.artist(), false));
    }

    @Override
    public String summarizeResult(Object result) {
        SongResourceSearchService.SearchResult r = (SongResourceSearchService.SearchResult) result;
        return r.message() != null ? r.message() : "搜到「" + r.original() + "」";
    }
}
