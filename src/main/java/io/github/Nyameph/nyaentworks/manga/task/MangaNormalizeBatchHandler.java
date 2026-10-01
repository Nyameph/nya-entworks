package io.github.Nyameph.nyaentworks.manga.task;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.manga.service.MangaStoreService;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskContext;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskHandler;

import java.util.List;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnManga;

/**
 * 批量规范化命名：循环改目录名，一本失败不中断整批，返回逐项结果。
 */
@ConditionalOnManga
@Component
@RequiredArgsConstructor
public class MangaNormalizeBatchHandler implements AsyncTaskHandler {

    public static final String TYPE = "manga.new.normalize-batch";

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
        return "批量规范化命名";
    }

    @Override
    public Class<?> paramType() {
        return MangaStoreService.NormalizeBatchParams.class;
    }

    @Override
    public Object run(Object params, AsyncTaskContext context) {
        MangaStoreService.NormalizeBatchParams p = (MangaStoreService.NormalizeBatchParams) params;
        List<String> paths = p.folderPaths();
        context.message("正在规范化 " + (paths == null ? 0 : paths.size()) + " 个目录名…");
        return storeService.normalizeBatch(paths);
    }

    @Override
    public String summarizeResult(Object result) {
        MangaStoreService.NormalizeBatchResult r = (MangaStoreService.NormalizeBatchResult) result;
        return "改 " + r.succeeded() + "/" + r.total() + " 个目录名";
    }
}
