package io.github.Nyameph.nyaentworks.manga.task;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.manga.service.MangaArchiveService;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskContext;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskHandler;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnManga;

/**
 * 扫描归档目录下的漫画，补齐 {@code manga_data} 的 ARCHIVED 层。
 * <p>递归扫各归档目录，可能上千本，所以走后台任务。
 */
@ConditionalOnManga
@Component
@RequiredArgsConstructor
public class MangaArchiveScanMangasHandler implements AsyncTaskHandler {

    public static final String TYPE = "manga.archive.scan-mangas";

    private final MangaArchiveService archiveService;

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
        return "扫描归档漫画";
    }

    @Override
    public Class<?> paramType() {
        return Void.class;
    }

    @Override
    public Object run(Object params, AsyncTaskContext context) {
        context.message("正在递归扫归档目录下的漫画…");
        return archiveService.scanArchivedMangas();
    }

    @Override
    public String summarizeResult(Object result) {
        MangaArchiveService.ScanMangaResult r = (MangaArchiveService.ScanMangaResult) result;
        return "扫 " + r.scanned() + " 本，新增 " + r.inserted() + "，更新 " + r.updated();
    }
}
