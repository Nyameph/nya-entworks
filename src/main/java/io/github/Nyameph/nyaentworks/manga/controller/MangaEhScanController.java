package io.github.Nyameph.nyaentworks.manga.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.github.Nyameph.nyaentworks.common.ApiResult;
import io.github.Nyameph.nyaentworks.manga.service.MangaEhScanService;
import io.github.Nyameph.nyaentworks.manga.task.MangaEhScanHandler;
import io.github.Nyameph.nyaentworks.task.service.AsyncTaskService;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnManga;

/**
 * 漫画 e-hentai 标签扫描（docs/已完成/eh标签扫描设计.md）。
 * <p>扫描默认只读：只写 {@code manga_eh_scan} 结果表、不落标签关联；「应用」才把建议标签
 * 写进 {@code manga_tag_ref(MANGA_DATA)}。批量扫描与归档自动扫描留到后续（§9）。
 */
@Tag(name = "漫画-eh扫描")
@ConditionalOnManga
@RestController
@RequestMapping("/api/manga")
@RequiredArgsConstructor
public class MangaEhScanController {

    private final MangaEhScanService scanService;
    private final AsyncTaskService taskService;

    public record ScanRequest(Boolean autoApply, String galleryUrl) {
    }

    @Operation(summary = "扫描单本漫画（可选直接贴 gallery 链接跳过搜索；默认只读不落标签）。异步，返回任务 id")
    @PostMapping("/{mangaId}/eh-scan")
    public ApiResult<Long> scan(@PathVariable Long mangaId,
                                @RequestBody(required = false) ScanRequest request) {
        MangaEhScanService.ScanParams params = new MangaEhScanService.ScanParams(
                mangaId,
                request != null && Boolean.TRUE.equals(request.autoApply()),
                request == null ? null : request.galleryUrl());
        return ApiResult.ok(taskService.submit(MangaEhScanHandler.TYPE, params,
                "扫描漫画 #" + mangaId));
    }

    @Operation(summary = "把最近一次扫描的建议标签应用落库（写 manga_tag_ref）")
    @PostMapping("/{mangaId}/eh-scan/apply")
    public ApiResult<Integer> apply(@PathVariable Long mangaId) {
        return ApiResult.ok(scanService.apply(mangaId));
    }

    @Operation(summary = "查该漫画最近一次扫描结果")
    @GetMapping("/{mangaId}/eh-scan")
    public ApiResult<MangaEhScanService.ScanResult> get(@PathVariable Long mangaId) {
        return ApiResult.ok(scanService.get(mangaId));
    }
}
