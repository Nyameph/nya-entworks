package io.github.Nyameph.nyaentworks.manga.task;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.manga.service.MangaUnarchivedService;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskContext;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskHandler;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnManga;

/**
 * 手动重扫散漫目录：upsert 行、自动归档能归的、消失的标失踪。
 * <p>只走目录与更新库、不压缩，但可能扫不少目录，所以走后台任务。
 */
@ConditionalOnManga
@Component
@RequiredArgsConstructor
public class MangaUnarchivedScanHandler implements AsyncTaskHandler {

    public static final String TYPE = "manga.unarchived.scan";

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
        return "重扫未归档漫画";
    }

    @Override
    public Class<?> paramType() {
        return Void.class;
    }

    @Override
    public Object run(Object params, AsyncTaskContext context) {
        context.message("正在扫散漫目录…");
        return unarchivedService.scan(context.taskId());
    }

    @Override
    public String summarizeResult(Object result) {
        MangaUnarchivedService.ScanUnarchivedResult r = (MangaUnarchivedService.ScanUnarchivedResult) result;
        return "扫 " + r.scanned() + " 本，新增 " + r.inserted() + "，更新 " + r.updated();
    }
}
