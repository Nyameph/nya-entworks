package io.github.Nyameph.nyaentworks.manga.task;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.manga.service.MangaStoreService;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskContext;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskHandler;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnManga;

/**
 * 存储整个合集：逐本规范化 → 逐本压缩 → 整体搬进归档根 → 同步 → 入库。
 * <p>重跑是半幂等的：压缩靠断点标记跳过已压部分（见
 * {@code MangaStoreService#STORE_MARKER_FILE}）。
 */
@ConditionalOnManga
@Component
@RequiredArgsConstructor
public class MangaCollectionStoreHandler implements AsyncTaskHandler {

    public static final String TYPE = "manga.new.collection-store";

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
        return "存储合集";
    }

    @Override
    public Class<?> paramType() {
        return MangaStoreService.CollectionStoreParams.class;
    }

    @Override
    public Object run(Object params, AsyncTaskContext context) {
        return storeService.runStoreCollection(
                (MangaStoreService.CollectionStoreParams) params, context);
    }

    @Override
    public String summarizeResult(Object result) {
        MangaStoreService.CollectionStoreResult r = (MangaStoreService.CollectionStoreResult) result;
        int pending = r.pendingConfirmations() == null ? 0 : r.pendingConfirmations().size();
        String s = "存储 " + r.storedMangas() + " 本";
        return pending > 0 ? s + "，" + pending + " 本待确认" : s;
    }
}
