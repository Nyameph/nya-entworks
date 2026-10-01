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
 * 批量一次提交（评分 + 改名）：整行所有 variant 一起搬，磁盘逐 variant 动。
 * <p>与单组 plan/apply 同立场 —— 先全部检查、再全部移动，漏搬一个文件这首歌就裂成两半。
 */
@ConditionalOnSong
@Component
@RequiredArgsConstructor
public class SongEditBatchHandler implements AsyncTaskHandler {

    public static final String TYPE = "song.edit-batch";

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
        return "批量保存（评分/改名）";
    }

    @Override
    public Class<?> paramType() {
        return SongController.EditBatchRequest.class;
    }

    @Override
    public Object run(Object params, AsyncTaskContext context) {
        SongController.EditBatchRequest p = (SongController.EditBatchRequest) params;
        List<SongArchiveService.GroupRef> groups = p.groups() == null ? List.of() : p.groups();
        context.message("正在批量保存 " + groups.size() + " 组…");
        return archiveService.applyEditBatch(groups, p.score(), p.newBaseName(), context.taskId());
    }

    @Override
    public String summarizeResult(Object result) {
        SongArchiveService.GroupApplyResult r = (SongArchiveService.GroupApplyResult) result;
        return "批量保存，搬 " + r.movedFiles() + " 个文件";
    }
}
