package io.github.Nyameph.nyaentworks.shout.task;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.shout.service.ShoutSyncService;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskContext;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskHandler;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnShout;

/**
 * 从磁盘全量同步归档喊麦库（{@code shout_group} / {@code shout_file}）。
 * <p>与歌曲的 {@code SongSyncHandler} 同款：扫的是归档根，几十组、百来个文件，
 * 走后台任务；不解析文件名、不归并，所以比歌曲那次快得多。
 * <p>幂等：磁盘没变时重跑只产生「更新」，不重复插入。
 */
@ConditionalOnShout
@Component
@RequiredArgsConstructor
public class ShoutSyncHandler implements AsyncTaskHandler {

    public static final String TYPE = "shout.sync";

    private final ShoutSyncService syncService;

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
        return "同步归档喊麦";
    }

    @Override
    public Class<?> paramType() {
        return Void.class;
    }

    @Override
    public Object run(Object params, AsyncTaskContext context) {
        context.message("正在扫归档喊麦目录…");
        return syncService.sync();
    }

    @Override
    public String summarizeResult(Object result) {
        ShoutSyncService.SyncResult r = (ShoutSyncService.SyncResult) result;
        return "新增 " + r.groupsInserted() + "，更新 " + r.groupsUpdated()
                + "，失踪 " + r.groupsMissing();
    }
}
