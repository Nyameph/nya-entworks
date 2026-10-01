package io.github.Nyameph.nyaentworks.manga.task;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.manga.service.MangaStoreService;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskContext;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskHandler;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnManga;

/**
 * 存储新漫画：逐本「规范化改名 → 压缩 → 归档或落散漫 → 入库」。
 * <p>压缩是分钟级操作，所以走后台任务而不是同步请求。
 */
@ConditionalOnManga
@Component
@RequiredArgsConstructor
public class MangaStoreHandler implements AsyncTaskHandler {

    public static final String TYPE = "manga.new.store";

    private final MangaStoreService storeService;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String module() {
        return MangaTaskModule.MANGA;
    }

    @Override
    public String taskName() {
        return "存储新漫画";
    }

    @Override
    public Class<?> paramType() {
        return MangaStoreService.StoreParams.class;
    }

    @Override
    public Object run(Object params, AsyncTaskContext context) {
        return storeService.runStore((MangaStoreService.StoreParams) params, context);
    }

    @Override
    public String summarizeResult(Object result) {
        MangaStoreService.StoreBatchResult r = (MangaStoreService.StoreBatchResult) result;
        long archived = r.results().stream().filter(MangaStoreService.StoreResult::archived).count();
        int pending = r.pendingConfirmations() == null ? 0 : r.pendingConfirmations().size();
        StringBuilder sb = new StringBuilder("存储 " + r.results().size() + " 本");
        if (archived > 0) {
            sb.append("（归档 ").append(archived).append(" 本）");
        }
        if (pending > 0) {
            sb.append("，").append(pending).append(" 本待确认");
        }
        return sb.toString();
    }
}
