package io.github.Nyameph.nyaentworks.manga.task;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.manga.service.MangaStoreService;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskContext;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskHandler;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnManga;

/**
 * 压缩并规范化命名（合集内单本的「归档」）。不搬、不落库，只为整体存储做好准备。
 */
@ConditionalOnManga
@Component
@RequiredArgsConstructor
public class MangaCompressNormalizeHandler implements AsyncTaskHandler {

    public static final String TYPE = "manga.new.compress-normalize";

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
        return "压缩并规范化";
    }

    @Override
    public Class<?> paramType() {
        return MangaStoreService.CompressNormalizeParams.class;
    }

    @Override
    public Object run(Object params, AsyncTaskContext context) {
        return storeService.runCompressNormalize(
                (MangaStoreService.CompressNormalizeParams) params, context);
    }

    @Override
    public String summarizeResult(Object result) {
        MangaStoreService.CompressNormalizeResult r = (MangaStoreService.CompressNormalizeResult) result;
        return "压缩完成：" + java.nio.file.Path.of(r.toFolderPath()).getFileName();
    }
}
