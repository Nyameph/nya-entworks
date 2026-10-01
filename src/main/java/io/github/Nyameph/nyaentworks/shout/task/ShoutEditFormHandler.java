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
 * 列表式修改弹窗（既有组的保存）的执行（异步）：补文件 / 改全名 / 改评分 / 剔除 / 写标签。
 * <p>与 {@link ShoutImportHandler} 同一套 plan / apply，只是入口不同（源由后端重扫定位）。
 */
@ConditionalOnShout
@Component
@RequiredArgsConstructor
public class ShoutEditFormHandler implements AsyncTaskHandler {

    public static final String TYPE = "shout.edit-form";

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
        return "喊麦列表式保存";
    }

    @Override
    public Class<?> paramType() {
        return ShoutController.EditFormRequest.class;
    }

    @Override
    public Object run(Object params, AsyncTaskContext context) {
        ShoutController.EditFormRequest p = (ShoutController.EditFormRequest) params;
        int n = p.groups() == null ? 0 : p.groups().size();
        context.message("正在保存 " + n + " 组…");
        return FileOpRecorder.batch(FileOpModule.SHOUT, FileOpSource.TASK, context.taskId(),
                () -> importService.applyEditForm(p));
    }

    @Override
    public String summarizeResult(Object result) {
        ShoutArchiveService.GroupApplyResult r = (ShoutArchiveService.GroupApplyResult) result;
        return "保存完成，搬 " + r.movedFiles() + " 个文件"
                + (r.settingMoved() ? "，镜像行与标签也跟着改了键" : "");
    }
}
