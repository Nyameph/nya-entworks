package io.github.Nyameph.nyaentworks.manga.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.github.Nyameph.nyaentworks.common.ApiResult;
import io.github.Nyameph.nyaentworks.manga.consts.MangaDataStatus;
import io.github.Nyameph.nyaentworks.manga.service.MangaRandomService;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnManga;

/**
 * 漫画随机抽选接口（需求：已归档 / 未归档漫画按分数加权抽 50 部，优先无独立标签）。
 */
@Tag(name = "漫画-随机抽选")
@ConditionalOnManga
@RestController
@RequestMapping("/api/manga/random")
@RequiredArgsConstructor
public class MangaRandomController {

    private final MangaRandomService randomService;

    @Operation(summary = "按分数加权抽选，优先无独立标签。source = ARCHIVED / UNARCHIVED")
    @GetMapping
    public ApiResult<MangaRandomService.RandomDraw> draw(
            @RequestParam MangaDataStatus source,
            @RequestParam(defaultValue = "50") int count) {
        return ApiResult.ok(randomService.draw(source, count));
    }
}
