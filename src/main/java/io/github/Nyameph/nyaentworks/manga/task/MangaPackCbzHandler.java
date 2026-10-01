package io.github.Nyameph.nyaentworks.manga.task;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.manga.service.MangaPackService;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskContext;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskHandler;

import java.util.List;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnManga;

/**
 * 打包成 cbz：循环把漫画目录打包成同名 cbz 并删源目录，一本失败不中断整批。
 */
@ConditionalOnManga
@Component
@RequiredArgsConstructor
public class MangaPackCbzHandler implements AsyncTaskHandler {

    public static final String TYPE = "manga.pack-cbz";

    private final MangaPackService packService;

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
        return "打包 cbz";
    }

    @Override
    public Class<?> paramType() {
        return MangaPackService.PackParams.class;
    }

    @Override
    public Object run(Object params, AsyncTaskContext context) {
        MangaPackService.PackParams p = (MangaPackService.PackParams) params;
        List<String> paths = p.folderPaths();
        context.message("正在打包 " + (paths == null ? 0 : paths.size()) + " 本…");
        return packService.packBatch(paths, context);
    }

    @Override
    public String summarizeResult(Object result) {
        MangaPackService.PackResult r = (MangaPackService.PackResult) result;
        return "打包 " + r.packed() + "/" + r.total() + " 本";
    }
}
