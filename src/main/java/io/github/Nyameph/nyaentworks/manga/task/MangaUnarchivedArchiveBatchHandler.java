package io.github.Nyameph.nyaentworks.manga.task;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.manga.service.MangaUnarchivedService;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskContext;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskHandler;

import java.util.List;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnManga;

/**
 * 批量重新归档：逐本搬进命中的归档目录，一条失败不中断整批，返回成功数与失败原因。
 */
@ConditionalOnManga
@Component
@RequiredArgsConstructor
public class MangaUnarchivedArchiveBatchHandler implements AsyncTaskHandler {

    public static final String TYPE = "manga.unarchived.archive-batch";

    private final MangaUnarchivedService unarchivedService;

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
        return "批量重新归档";
    }

    @Override
    public Class<?> paramType() {
        return MangaUnarchivedService.ArchiveBatchParams.class;
    }

    @Override
    public Object run(Object params, AsyncTaskContext context) {
        MangaUnarchivedService.ArchiveBatchParams p =
                (MangaUnarchivedService.ArchiveBatchParams) params;
        List<String> paths = p.folderPaths();
        context.message("正在归档 " + (paths == null ? 0 : paths.size()) + " 本…");
        return unarchivedService.archiveBatch(paths, p.tags(), context.taskId());
    }

    @Override
    public String summarizeResult(Object result) {
        MangaUnarchivedService.ArchiveBatchResult r = (MangaUnarchivedService.ArchiveBatchResult) result;
        String s = "归档 " + r.archived() + " 本";
        return (r.errors() == null || r.errors().isEmpty()) ? s : s + "，" + r.errors().size() + " 个失败";
    }
}
