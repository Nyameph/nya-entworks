package io.github.Nyameph.nyaentworks.shout.task;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnShout;
import io.github.Nyameph.nyaentworks.common.fileop.FileOpRecorder;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpModule;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpSource;
import io.github.Nyameph.nyaentworks.shout.controller.ShoutController;
import io.github.Nyameph.nyaentworks.shout.service.ShoutArchiveService;
import io.github.Nyameph.nyaentworks.shout.service.ShoutImportService;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskContext;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskHandler;

/**
 * 「添加文件」弹窗的执行（异步）：搬 + 改名 + 写标签。
 * <p>走异步的理由与 {@code shout.edit-batch} 相同：<b>跨卷拷贝可能几十秒</b>，
 * 同步请求会挂在前端等。参数落 {@code async_task.params_json}；启动时残留任务置
 * INTERRUPTED、由人在任务页点「重新执行」重跑（apply 里会重跑 plan 二次校验）。
 */
@ConditionalOnShout
@Component
@RequiredArgsConstructor
public class ShoutImportHandler implements AsyncTaskHandler {

    public static final String TYPE = "shout.import";

    private final ShoutImportService importService;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String module() {
        return ShoutTaskModule.SHOUT;
    }

    @Override
    public String taskName() {
        return "添加喊麦（导入/归档）";
    }

    @Override
    public Class<?> paramType() {
        return ShoutController.ImportRequest.class;
    }

    @Override
    public Object run(Object params, AsyncTaskContext context) {
        ShoutController.ImportRequest p = (ShoutController.ImportRequest) params;
        context.message("正在处理 " + (p.files() == null ? 0 : p.files().size()) + " 个文件…");
        // 改动流水的批次入口在这里包：apply 内部 recordPlan 的一次动作共用一个批次号
        return FileOpRecorder.batch(FileOpModule.SHOUT, FileOpSource.TASK, context.taskId(),
                () -> importService.applyImport(p));
    }

    @Override
    public String summarizeResult(Object result) {
        ShoutArchiveService.GroupApplyResult r = (ShoutArchiveService.GroupApplyResult) result;
        return "导入完成，搬 " + r.movedFiles() + " 个文件";
    }
}
