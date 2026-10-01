package io.github.Nyameph.nyaentworks.manga.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.github.Nyameph.nyaentworks.common.ApiResult;
import io.github.Nyameph.nyaentworks.manga.service.MangaPackService;
import io.github.Nyameph.nyaentworks.manga.task.MangaPackCbzHandler;
import io.github.Nyameph.nyaentworks.task.service.AsyncTaskService;

import java.util.List;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnManga;

/**
 * 打包 cbz 接口。未归档页与归档作者页共用：单本传一个路径、批量传一列，
 * 打包要遍历图片并删源目录，分钟级，故统一提交成异步任务（manga 队列）。
 */
@Tag(name = "漫画-打包 cbz")
@ConditionalOnManga
@RestController
@RequestMapping("/api/manga")
@RequiredArgsConstructor
public class MangaPackController {

    private final AsyncTaskService taskService;

    public record PackRequest(List<String> folderPaths) {
    }

    @Operation(summary = "把漫画目录原地打包成 cbz（删源目录），单本或批量。异步，返回任务 id")
    @PostMapping("/pack-cbz")
    public ApiResult<Long> packCbz(@RequestBody PackRequest request) {
        MangaPackService.PackParams params = new MangaPackService.PackParams(
                request == null ? null : request.folderPaths());
        int count = request == null || request.folderPaths() == null ? 0 : request.folderPaths().size();
        return ApiResult.ok(taskService.submit(MangaPackCbzHandler.TYPE, params, "打包 " + count + " 本"));
    }
}
