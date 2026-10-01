package io.github.Nyameph.nyaentworks.song.task;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.song.controller.SongController;
import io.github.Nyameph.nyaentworks.song.service.SongArchiveService;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskContext;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskHandler;

import java.util.List;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnSong;

/**
 * 批量规范化命名：循环改名，一组失败不影响其它组，返回逐项结果。
 */
@ConditionalOnSong
@Component
@RequiredArgsConstructor
public class SongNormalizeBatchHandler implements AsyncTaskHandler {

    public static final String TYPE = "song.normalize-batch";

    private final SongArchiveService archiveService;

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
        return "批量规范化命名";
    }

    @Override
    public Class<?> paramType() {
        return SongController.NormalizeBatchRequest.class;
    }

    @Override
    public Object run(Object params, AsyncTaskContext context) {
        SongController.NormalizeBatchRequest p = (SongController.NormalizeBatchRequest) params;
        List<String> keys = p.keys() == null ? List.of() : p.keys();
        context.message("正在规范化 " + keys.size() + " 组命名…");
        return archiveService.applyNormalizeBatch(keys, context.taskId());
    }

    @Override
    public String summarizeResult(Object result) {
        SongArchiveService.BatchNormalizeResult r = (SongArchiveService.BatchNormalizeResult) result;
        String s = "改名 " + r.renamed();
        return (r.errors() == null || r.errors().isEmpty()) ? s : s + "，" + r.errors().size() + " 个失败";
    }
}
