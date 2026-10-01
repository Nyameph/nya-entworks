package io.github.Nyameph.nyaentworks.manga.task;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.manga.config.MangaProperties;
import io.github.Nyameph.nyaentworks.manga.service.MangaArchiveService;
import io.github.Nyameph.nyaentworks.manga.util.MangaScoreDir;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskContext;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskHandler;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnManga;

/**
 * 从磁盘同步归档目录：扫四个归档根的一级目录，解析
 * {@code [社团 (作者)]【标签】} 后入库。可能扫几十上百个目录，所以走后台任务。
 * <p>幂等：目录没变时重跑只产生「更新」，不重复插入。
 */
@ConditionalOnManga
@Component
@RequiredArgsConstructor
public class MangaArchiveSyncHandler implements AsyncTaskHandler {

    public static final String TYPE = "manga.archive.sync";

    private final MangaArchiveService archiveService;
    private final MangaProperties properties;

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
        return "同步归档目录";
    }

    @Override
    public Class<?> paramType() {
        return Void.class;
    }

    @Override
    public Object run(Object params, AsyncTaskContext context) {
        context.message("正在扫归档根目录…");
        return archiveService.sync(MangaScoreDir.rootScoreMap(properties.getArchiveDir()));
    }

    @Override
    public String summarizeResult(Object result) {
        MangaArchiveService.SyncResult r = (MangaArchiveService.SyncResult) result;
        return "新增 " + r.inserted() + "，更新 " + r.updated() + "，改名 " + r.renamed();
    }
}
