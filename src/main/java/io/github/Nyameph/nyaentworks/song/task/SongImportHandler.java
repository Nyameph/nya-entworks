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
 * 「添加文件」弹窗的执行（异步）：搬 + 改名 + 写标签（+ 归档时回写库）。
 * <p>走异步的理由与 {@code song.edit-batch} 相同：<b>跨卷拷贝可能几十秒</b>，
 * 同步请求会挂在前端等。参数落 {@code async_task.params_json}；启动时残留任务置
 * INTERRUPTED、由人在任务页点「重新执行」重跑（apply 里会重跑 plan 二次校验）。
 */
@ConditionalOnSong
@Component
@RequiredArgsConstructor
public class SongImportHandler implements AsyncTaskHandler {

    public static final String TYPE = "song.import";

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
        return "添加歌曲（导入/归档）";
    }

    @Override
    public Class<?> paramType() {
        return SongController.ImportRequest.class;
    }

    @Override
    public Object run(Object params, AsyncTaskContext context) {
        SongController.ImportRequest p = (SongController.ImportRequest) params;
        context.message("正在处理 " + (p.files() == null ? 0 : p.files().size()) + " 个文件…");
        // 改动流水的批次入口在这里包：apply 内部 recordPlan 的一次动作共用一个批次号
        return FileOpRecorder.batch(FileOpModule.SONG, FileOpSource.TASK, context.taskId(),
                () -> importService.applyImport(p));
    }

    @Override
    public String summarizeResult(Object result) {
        SongArchiveService.GroupApplyResult r = (SongArchiveService.GroupApplyResult) result;
        return "导入完成，搬 " + r.movedFiles() + " 个文件";
    }
}
