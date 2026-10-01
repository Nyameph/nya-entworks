package io.github.Nyameph.nyaentworks.manga.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.github.Nyameph.nyaentworks.common.ApiResult;
import io.github.Nyameph.nyaentworks.manga.service.MangaStoreService;
import io.github.Nyameph.nyaentworks.manga.service.MangaUnarchivedService;
import io.github.Nyameph.nyaentworks.manga.task.MangaUnarchivedArchiveBatchHandler;
import io.github.Nyameph.nyaentworks.manga.task.MangaUnarchivedScanHandler;
import io.github.Nyameph.nyaentworks.task.service.AsyncTaskService;

import java.util.List;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnManga;

/**
 * 未归档漫画接口（文档 4.9）。
 *
 * <p>这一层已入库（{@code manga_data.status = UNARCHIVED}），所以「id」是库里
 * 行的目录全路径，写操作校验路径确实落在散漫根下（复用
 * {@code MangaStoreService.rename/score/delete}，它们已认得未归档根）。
 * <p>封面与正文图复用新漫画的 {@code /api/manga/new/cover}、{@code /image}——
 * 那两个端点的受管根里本来就含未归档根。
 */
@Tag(name = "漫画-未归档")
@ConditionalOnManga
@RestController
@RequestMapping("/api/manga/unarchived")
@RequiredArgsConstructor
public class MangaUnarchivedController {

    private final MangaUnarchivedService unarchivedService;
    private final MangaStoreService storeService;
    private final AsyncTaskService taskService;

    @Operation(summary = "分页列出未归档漫画，可搜标题/作者/社团/目录路径")
    @GetMapping
    public ApiResult<MangaUnarchivedService.UnarchivedPage> page(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResult.ok(unarchivedService.page(keyword, page, size));
    }

    @Operation(summary = "加权贝叶斯平均分前 10 的作者与社团")
    @GetMapping("/top")
    public ApiResult<MangaUnarchivedService.TopResult> top() {
        return ApiResult.ok(unarchivedService.top10());
    }

    @Operation(summary = "手动重扫散漫目录：upsert、自动归档能归的、消失的标失踪。异步，返回任务 id")
    @PostMapping("/scan")
    public ApiResult<Long> scan() {
        return ApiResult.ok(taskService.submit(MangaUnarchivedScanHandler.TYPE, null));
    }

    public record ArchiveRequest(String folderPath, Integer score, List<String> tags) {
    }

    @Operation(summary = "单本重新归档：搬进命中的归档目录，不压缩，落库（含评分与独立标签）")
    @PostMapping("/archive")
    public ApiResult<MangaUnarchivedService.ArchiveResult> archive(
            @RequestBody ArchiveRequest request) {
        return ApiResult.ok(unarchivedService.archive(
                request.folderPath(), request.score(), request.tags()));
    }

    public record ArchiveBatchRequest(List<String> folderPaths, List<String> tags) {
    }

    @Operation(summary = "批量重新归档：逐本搬进命中的归档目录，一条失败不中断整批，返回成功数与失败原因。异步，返回任务 id")
    @PostMapping("/archive-batch")
    public ApiResult<Long> archiveBatch(@RequestBody ArchiveBatchRequest request) {
        MangaUnarchivedService.ArchiveBatchParams params =
                new MangaUnarchivedService.ArchiveBatchParams(
                        request == null ? null : request.folderPaths(),
                        request == null ? null : request.tags());
        int count = request == null || request.folderPaths() == null ? 0 : request.folderPaths().size();
        return ApiResult.ok(taskService.submit(MangaUnarchivedArchiveBatchHandler.TYPE, params,
                "归档 " + count + " 本"));
    }

    public record RenameRequest(String folderPath, String newFolderName) {
    }

    @Operation(summary = "改目录名（未归档根内），同步 manga_data.folder_path")
    @PutMapping("/folder-name")
    public ApiResult<String> rename(@RequestBody RenameRequest request) {
        return ApiResult.ok(storeService.rename(request.folderPath(), request.newFolderName()));
    }

    public record ScoreRequest(String folderPath, Integer score) {
    }

    @Operation(summary = "评分：在散漫的评分分区间移动，同步 manga_data.score 与 folder_path")
    @PutMapping("/score")
    public ApiResult<String> score(@RequestBody ScoreRequest request) {
        return ApiResult.ok(storeService.score(request.folderPath(), request.score()));
    }

    @Operation(summary = "删除漫画目录，同步删 manga_data 行")
    @DeleteMapping
    public ApiResult<Void> delete(@RequestParam String folderPath) {
        storeService.delete(folderPath);
        return ApiResult.ok();
    }
}
