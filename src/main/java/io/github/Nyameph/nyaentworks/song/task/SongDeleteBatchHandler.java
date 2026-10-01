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
 * 批量删除：整行所有 variant 一起删，删到一半失败不回滚，把删不掉的列出来。
 * <p>删是送进回收站，但整行可能有很多文件、回收站这一步最慢（每个文件一次系统调用），
 * 走后台任务是为了不占着请求线程逐文件送。
 */
@ConditionalOnSong
@Component
@RequiredArgsConstructor
public class SongDeleteBatchHandler implements AsyncTaskHandler {

    public static final String TYPE = "song.delete-batch";

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
        return "批量删除";
    }

    @Override
    public Class<?> paramType() {
        return SongController.DeleteBatchRequest.class;
    }

    @Override
    public Object run(Object params, AsyncTaskContext context) {
        SongController.DeleteBatchRequest p = (SongController.DeleteBatchRequest) params;
        List<SongArchiveService.GroupRef> groups = p.groups() == null ? List.of() : p.groups();
        context.message("正在删除 " + groups.size() + " 组…");
        return archiveService.applyDeleteBatch(groups, context.taskId());
    }

    @Override
    public String summarizeResult(Object result) {
        SongArchiveService.GroupApplyResult r = (SongArchiveService.GroupApplyResult) result;
        return "批量删除，删 " + r.movedFiles() + " 个文件";
    }
}
