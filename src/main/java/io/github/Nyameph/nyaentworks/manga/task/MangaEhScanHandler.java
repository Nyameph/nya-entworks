package io.github.Nyameph.nyaentworks.manga.task;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.manga.service.MangaEhScanService;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskContext;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskHandler;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnManga;

/**
 * 单本漫画的 e-hentai 标签扫描。网络 + AI + 2s 限流，分钟级，收益最大的一处异步化。
 */
@ConditionalOnManga
@Component
@RequiredArgsConstructor
public class MangaEhScanHandler implements AsyncTaskHandler {

    public static final String TYPE = "manga.eh-scan";

    private final MangaEhScanService scanService;

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
        return "扫描 e-hentai 标签";
    }

    @Override
    public Class<?> paramType() {
        return MangaEhScanService.ScanParams.class;
    }

    @Override
    public Object run(Object params, AsyncTaskContext context) {
        MangaEhScanService.ScanParams p = (MangaEhScanService.ScanParams) params;
        context.message("正在扫描 e-hentai 标签…");
        return scanService.scan(p.mangaId(), Boolean.TRUE.equals(p.autoApply()), p.galleryUrl());
    }

    @Override
    public String summarizeResult(Object result) {
        MangaEhScanService.ScanResult r = (MangaEhScanService.ScanResult) result;
        if (r.errorMessage() != null) {
            return "扫描失败：" + r.errorMessage();
        }
        return r.gallery() != null ? "命中并保存标签" : "未匹配到条目";
    }
}
