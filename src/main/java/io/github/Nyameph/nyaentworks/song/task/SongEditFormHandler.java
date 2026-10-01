package io.github.Nyameph.nyaentworks.song.task;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnSong;
import io.github.Nyameph.nyaentworks.common.fileop.FileOpRecorder;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpModule;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpSource;
import io.github.Nyameph.nyaentworks.song.controller.SongController;
import io.github.Nyameph.nyaentworks.song.service.SongArchiveService;
import io.github.Nyameph.nyaentworks.song.service.SongImportService;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskContext;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskHandler;

/**
 * ②③「列表式保存」的执行（异步）：与 {@code song.edit-batch} 同立场 ——
 * 搬动可能跨卷（一行 ×N 版本跨分区），同步请求会挂在前端等。
 */
@ConditionalOnSong
@Component
@RequiredArgsConstructor
public class SongEditFormHandler implements AsyncTaskHandler {

    public static final String TYPE = "song.edit-form";

    private final SongImportService importService;

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
        return "列表式保存（评分/改名/编号）";
    }

    @Override
    public Class<?> paramType() {
        return SongController.EditFormRequest.class;
    }

    @Override
    public Object run(Object params, AsyncTaskContext context) {
        SongController.EditFormRequest p = (SongController.EditFormRequest) params;
        int n = p.groups() == null ? 0 : p.groups().size();
        context.message("正在列表式保存 " + n + " 组…");
        // 改动流水的批次入口在这里包：apply 内部 recordPlan 的一次动作共用一个批次号
        return FileOpRecorder.batch(FileOpModule.SONG, FileOpSource.TASK, context.taskId(),
                () -> importService.applyEditForm(p));
    }

    @Override
    public String summarizeResult(Object result) {
        SongArchiveService.GroupApplyResult r = (SongArchiveService.GroupApplyResult) result;
        return "列表式保存完成，搬 " + r.movedFiles() + " 个文件";
    }
}
