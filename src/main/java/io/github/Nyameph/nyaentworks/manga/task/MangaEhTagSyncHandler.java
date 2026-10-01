package io.github.Nyameph.nyaentworks.manga.task;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.manga.service.MangaEhTagService;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskContext;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskHandler;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnManga;

/**
 * 从 e-hentai 直接拉取词典标签、比对入库（不经过 CSV）。
 * <p>要访问网络下载 7 个命名空间的标签，十几秒级，所以走后台任务。
 */
@ConditionalOnManga
@Component
@RequiredArgsConstructor
public class MangaEhTagSyncHandler implements AsyncTaskHandler {

    public static final String TYPE = "manga.tag.eh-sync";

    private final MangaEhTagService ehTagService;

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
        return "从 e-hentai 同步标签";
    }

    @Override
    public Class<?> paramType() {
        return Void.class;
    }

    @Override
    public Object run(Object params, AsyncTaskContext context) {
        context.message("正在拉取 e-hentai 标签…");
        return ehTagService.sync();
    }

    @Override
    public String summarizeResult(Object result) {
        MangaEhTagService.SyncResult r = (MangaEhTagService.SyncResult) result;
        return "拉取 " + r.fetched() + "，新增 " + r.inserted() + "，更新 " + r.updated();
    }
}
