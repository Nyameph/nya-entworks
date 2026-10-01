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
 * 删除整组。删是送进回收站，但逐文件送最慢，走后台任务是为了不占着请求线程。
 *
 * <p>库这一侧同歌曲：{@code shout_file} 行清掉，{@code shout_group} 只标 {@code MISSING}
 * （保 {@code default_rate} —— 文件可能只是被临时移走），标签是磁盘表达不出来的设置，保留。
 */
@ConditionalOnShout
@Component
@RequiredArgsConstructor
public class ShoutDeleteBatchHandler implements AsyncTaskHandler {

    public static final String TYPE = "shout.delete-batch";

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
        return "喊麦删除";
    }

    @Override
    public Class<?> paramType() {
        return ShoutController.DeleteBatchRequest.class;
    }

    @Override
    public Object run(Object params, AsyncTaskContext context) {
        ShoutController.DeleteBatchRequest p = (ShoutController.DeleteBatchRequest) params;
        List<ShoutArchiveService.GroupRef> groups = p.groups() == null ? List.of() : p.groups();
        context.message("正在删除 " + groups.size() + " 组…");
        return archiveService.applyDeleteBatch(groups, context.taskId());
    }

    @Override
    public String summarizeResult(Object result) {
        ShoutArchiveService.GroupApplyResult r = (ShoutArchiveService.GroupApplyResult) result;
        return "删除完成，删 " + r.movedFiles() + " 个文件"
                + (r.settingMoved() ? "，镜像行已标 MISSING" : "");
    }
}
