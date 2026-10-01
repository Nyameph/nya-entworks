package io.github.Nyameph.nyaentworks.shout.controller;

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
import io.github.Nyameph.nyaentworks.shout.service.ShoutStoreService;

import java.util.List;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnShout;

/**
 * 喊麦标签接口。
 *
 * <p>与歌曲侧形状一致（同一条前端标签编辑器代码），只是 key 换成 {@code mainName}：
 * 喊麦不归并，一行就是一组，主名已经是稳定的键，不必再造一个 {@code mergeKey}。
 *
 * <p>标签跨分区共用，所以待打分时打的标签归档后仍在 —— 归档闸门
 * （{@code ShoutArchiveService.requireTagForArchive}）拦的就是「一组一个标签都没有」。
 */
@Tag(name = "喊麦-标签")
@ConditionalOnShout
@RestController
@RequestMapping("/api/shout/tags")
@RequiredArgsConstructor
public class ShoutTagController {

    private final ShoutStoreService storeService;

    @Operation(summary = "某一组的标签名列表")
    @GetMapping
    public ApiResult<List<String>> list(@RequestParam String mergeKey) {
        return ApiResult.ok(storeService.listTags(mergeKey));
    }

    @Operation(summary = "全部标签名（去重），供打标签时点选")
    @GetMapping("/all")
    public ApiResult<List<String>> all() {
        return ApiResult.ok(storeService.listAllTags());
    }

    /** 字段名沿用歌曲侧的 {@code mergeKey}，前端标签编辑器是同一份代码 */
    public record ReplaceRequest(String mergeKey, List<String> tags) {
    }

    @Operation(summary = "整组替换某一组的标签（传空列表即清空）")
    @PutMapping
    public ApiResult<Integer> replace(@RequestBody ReplaceRequest request) {
        return ApiResult.ok(storeService.replaceTags(
                request == null ? null : request.mergeKey(),
                request == null ? null : request.tags()));
    }
}
