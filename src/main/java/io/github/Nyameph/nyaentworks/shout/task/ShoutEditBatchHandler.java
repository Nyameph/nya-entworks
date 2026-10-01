package io.github.Nyameph.nyaentworks.shout.task;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.shout.controller.ShoutController;
import io.github.Nyameph.nyaentworks.shout.service.ShoutArchiveService;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskContext;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskHandler;

import java.util.List;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnShout;

/**
 * 一次提交（评分 + 改名）：整组文件一起搬。
 *
 * <p>名字里的「批量」是接口形状 —— 喊麦一行只有一个文件组，所以 {@code groups} 通常只有
 * 一项。留着这个形状是为了和歌曲侧共用前端的动作弹窗与任务轮询：前端的
 * {@code SongActions} 把执行端点的返回值当任务 id 去轮询，同步端点喂不了这个形状。
 */
@ConditionalOnShout
@Component
@RequiredArgsConstructor
public class ShoutEditBatchHandler implements AsyncTaskHandler {

    public static final String TYPE = "shout.edit-batch";

    private final ShoutArchiveService archiveService;

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
        return "喊麦保存（评分/改名）";
    }

    @Override
    public Class<?> paramType() {
        return ShoutController.EditBatchRequest.class;
    }

    @Override
    public Object run(Object params, AsyncTaskContext context) {
        ShoutController.EditBatchRequest p = (ShoutController.EditBatchRequest) params;
        List<ShoutArchiveService.GroupRef> groups = p.groups() == null ? List.of() : p.groups();
        context.message("正在保存 " + groups.size() + " 组…");
        return archiveService.applyEditBatch(groups, p.score(), p.newBaseName(), context.taskId());
    }

    @Override
    public String summarizeResult(Object result) {
        ShoutArchiveService.GroupApplyResult r = (ShoutArchiveService.GroupApplyResult) result;
        return "保存完成，搬 " + r.movedFiles() + " 个文件"
                + (r.settingMoved() ? "，镜像行与标签也跟着改了键" : "");
    }
}
