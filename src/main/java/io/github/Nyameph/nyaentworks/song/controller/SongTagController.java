package io.github.Nyameph.nyaentworks.song.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.github.Nyameph.nyaentworks.common.ApiResult;
import io.github.Nyameph.nyaentworks.song.service.SongTagService;

import java.util.List;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnSong;

/**
 * 歌曲标签接口。
 *
 * <p>标签挂在「合并条目」上，key = {@code mergeKey}。前端在归并列表
 * （{@code /merged-groups} / {@code /merged-staging}）里按 {@code mergeKey} 取标签、
 * 打标签；归档打分前要先有标签（闸门在 {@code SongArchiveService}）。
 */
@Tag(name = "歌曲-标签")
@ConditionalOnSong
@RestController
@RequestMapping("/api/song/tags")
@RequiredArgsConstructor
public class SongTagController {

    private final SongTagService tagService;

    @Operation(summary = "某个合并条目的标签名列表")
    @GetMapping
    public ApiResult<List<String>> list(@RequestParam String mergeKey) {
        return ApiResult.ok(tagService.listTags(mergeKey));
    }

    @Operation(summary = "全部标签名（去重），供打标签时点选")
    @GetMapping("/all")
    public ApiResult<List<String>> all() {
        return ApiResult.ok(tagService.listAllTags());
    }

    public record ReplaceRequest(String mergeKey, List<String> tags) {
    }

    @Operation(summary = "整组替换某个合并条目的标签（传空列表即清空）")
    @PutMapping
    public ApiResult<Integer> replace(@RequestBody ReplaceRequest request) {
        return ApiResult.ok(tagService.replaceTags(
                request == null ? null : request.mergeKey(),
                request == null ? null : request.tags()));
    }
}
