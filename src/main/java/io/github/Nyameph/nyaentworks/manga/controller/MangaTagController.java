package io.github.Nyameph.nyaentworks.manga.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.github.Nyameph.nyaentworks.common.ApiResult;
import io.github.Nyameph.nyaentworks.manga.consts.MangaArchiveUnitStatus;
import io.github.Nyameph.nyaentworks.manga.consts.MangaTagTargetType;
import io.github.Nyameph.nyaentworks.manga.entity.MangaTag;
import io.github.Nyameph.nyaentworks.manga.service.MangaArchiveService;
import io.github.Nyameph.nyaentworks.manga.service.MangaEhTagService;
import io.github.Nyameph.nyaentworks.manga.service.MangaTagAdminService;
import io.github.Nyameph.nyaentworks.manga.service.MangaTagService;
import io.github.Nyameph.nyaentworks.manga.task.MangaEhTagSyncHandler;
import io.github.Nyameph.nyaentworks.manga.task.MangaTagDeleteHandler;
import io.github.Nyameph.nyaentworks.manga.task.MangaTagMergeHandler;
import io.github.Nyameph.nyaentworks.manga.task.MangaTagRenameHandler;
import io.github.Nyameph.nyaentworks.task.service.AsyncTaskService;

import java.util.List;
import java.util.Map;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnManga;

/**
 * 标签接口（文档 4.5）。
 * <p>重命名、合并、删除都是两步：先 {@code GET .../plan} 拿到「会改哪些目录名」，
 * 页面展示给人看过，再 POST/PUT/DELETE 真执行。理由见
 * {@link MangaTagAdminService} —— 标签的权威在目录名里，改标签就是改目录名，
 * 而目录移动不可回滚。
 */
@Tag(name = "漫画-标签")
@ConditionalOnManga
@RestController
@RequestMapping("/api/manga/tags")
@RequiredArgsConstructor
public class MangaTagController {

    private final MangaTagService tagService;
    private final MangaTagAdminService tagAdminService;
    private final MangaArchiveService archiveService;
    private final MangaEhTagService ehTagService;
    private final AsyncTaskService taskService;

    @Operation(summary = "全部标签及用量，按归档目录/漫画分别计数")
    @GetMapping
    public ApiResult<List<MangaTagService.TagUsage>> list() {
        return ApiResult.ok(tagService.listUsage());
    }

    @Operation(summary = "按标签反查：打了它的归档目录")
    @GetMapping("/{id}/units")
    public ApiResult<List<MangaArchiveService.UnitView>> units(@PathVariable Long id) {
        MangaTag tag = tagService.require(id);
        return ApiResult.ok(archiveService.listUnits(
                (MangaArchiveUnitStatus) null, null, null, tag.getTagName(), false));
    }

    // ------------------------------------------------------------------
    // 单本漫画（MANGA_DATA）的独立标签：读 / 写 / 批量读 / 一键继承父级
    // ------------------------------------------------------------------

    @Operation(summary = "单本漫画的独立标签（带命名空间）")
    @GetMapping("/manga/{mangaId}")
    public ApiResult<List<MangaTagService.TagItem>> mangaTags(@PathVariable Long mangaId) {
        return ApiResult.ok(tagService.listTags(MangaTagTargetType.MANGA_DATA, mangaId));
    }

    public record IdListRequest(List<Long> ids) {
    }

    @Operation(summary = "批量取漫画的独立标签（带命名空间），返回 mangaId → 标签列表")
    @PostMapping("/manga/batch")
    public ApiResult<Map<Long, List<MangaTagService.TagItem>>> mangaTagsBatch(
            @RequestBody IdListRequest request) {
        return ApiResult.ok(tagService.listTagsBatch(MangaTagTargetType.MANGA_DATA,
                request == null ? List.of() : request.ids()));
    }

    public record MangaTagsRequest(List<MangaTagService.TagItem> tags) {
    }

    @Operation(summary = "整组替换单本漫画的独立标签（带命名空间；传空列表即清空）")
    @PutMapping("/manga/{mangaId}")
    public ApiResult<Integer> replaceMangaTags(@PathVariable Long mangaId,
                                               @RequestBody MangaTagsRequest request) {
        return ApiResult.ok(tagService.replaceRefsExact(MangaTagTargetType.MANGA_DATA, mangaId,
                request == null ? null : request.tags()));
    }

    public record CreateRequest(String tagName, String remark, String namespace,
                                String majorCategory, String description) {
    }

    @Operation(summary = "新建标签。在被某个目录名用到之前它一直是「未使用」")
    @PostMapping
    public ApiResult<MangaTag> create(@RequestBody CreateRequest request) {
        return ApiResult.ok(tagService.create(
                request == null ? null : request.tagName(),
                request == null ? null : request.remark(),
                request == null ? null : request.namespace(),
                request == null ? null : request.majorCategory(),
                request == null ? null : request.description()));
    }

    public record MetaRequest(String namespace, String majorCategory,
                              String description, String remark) {
    }

    @Operation(summary = "改标签元数据（命名空间/大类/描述/备注），不动标签名与来源")
    @PutMapping("/{id}/meta")
    public ApiResult<Void> updateMeta(@PathVariable Long id, @RequestBody MetaRequest request) {
        tagService.updateMeta(id,
                request == null ? null : request.namespace(),
                request == null ? null : request.majorCategory(),
                request == null ? null : request.description(),
                request == null ? null : request.remark());
        return ApiResult.ok();
    }

    @Operation(summary = "重命名的预演：会改哪些目录名")
    @GetMapping("/{id}/rename-plan")
    public ApiResult<MangaTagAdminService.TagPlan> renamePlan(@PathVariable Long id,
                                                              @RequestParam String newTagName) {
        return ApiResult.ok(tagAdminService.planRename(id, newTagName));
    }

    @Operation(summary = "合并的预演")
    @GetMapping("/{id}/merge-plan")
    public ApiResult<MangaTagAdminService.TagPlan> mergePlan(@PathVariable Long id,
                                                             @RequestParam Long targetTagId) {
        return ApiResult.ok(tagAdminService.planMerge(id, targetTagId));
    }

    @Operation(summary = "删除的预演：目录名里的该标签会被去掉")
    @GetMapping("/{id}/delete-plan")
    public ApiResult<MangaTagAdminService.TagPlan> deletePlan(@PathVariable Long id) {
        return ApiResult.ok(tagAdminService.planDelete(id));
    }

    public record RenameRequest(String newTagName) {
    }

    @Operation(summary = "重命名，连带改归档目录名。新名已存在时按合并处理。异步，返回任务 id")
    @PutMapping("/{id}")
    public ApiResult<Long> rename(@PathVariable Long id, @RequestBody RenameRequest request) {
        MangaTagAdminService.RenameParams params = new MangaTagAdminService.RenameParams(
                id, request == null ? null : request.newTagName());
        String newName = request == null ? null : request.newTagName();
        String name = (newName == null || newName.isBlank()) ? null : "重命名标签「" + newName + "」";
        return ApiResult.ok(taskService.submit(MangaTagRenameHandler.TYPE, params, name));
    }

    public record MergeRequest(Long targetTagId) {
    }

    @Operation(summary = "合并到另一个标签，连带改归档目录名。异步，返回任务 id")
    @PostMapping("/{id}/merge")
    public ApiResult<Long> merge(@PathVariable Long id, @RequestBody MergeRequest request) {
        MangaTagAdminService.MergeParams params = new MangaTagAdminService.MergeParams(
                id, request == null ? null : request.targetTagId());
        return ApiResult.ok(taskService.submit(MangaTagMergeHandler.TYPE, params,
                "合并标签 #" + id + " → #"
                        + (request == null ? null : request.targetTagId())));
    }

    @Operation(summary = "删除标签，连带从归档目录名里去掉。归档目录与漫画本身不动。异步，返回任务 id")
    @DeleteMapping("/{id}")
    public ApiResult<Long> delete(@PathVariable Long id) {
        MangaTagAdminService.DeleteParams params = new MangaTagAdminService.DeleteParams(id);
        return ApiResult.ok(taskService.submit(MangaTagDeleteHandler.TYPE, params,
                "删除标签 #" + id));
    }

    @Operation(summary = "清理零引用的标签")
    @PostMapping("/cleanup-unused")
    public ApiResult<Integer> cleanupUnused() {
        return ApiResult.ok(tagService.deleteUnused());
    }

    // ------------------------------------------------------------------
    // 从 e-hentai 拉取词典标签入库并比对（不经过 CSV）
    // ------------------------------------------------------------------

    @Operation(summary = "拉取 e-hentai 标签入库并比对；无法判断大类的返回让前端手动填。异步，返回任务 id")
    @PostMapping("/eh-sync")
    public ApiResult<Long> ehSync() {
        return ApiResult.ok(taskService.submit(MangaEhTagSyncHandler.TYPE, null));
    }

    public record UndeterminedListRequest(List<MangaEhTagService.UndeterminedFill> fills) {
    }

    @Operation(summary = "提交手动填好大类的标签入库")
    @PostMapping("/eh-sync/undetermined")
    public ApiResult<Integer> ehSyncUndetermined(@RequestBody UndeterminedListRequest request) {
        return ApiResult.ok(ehTagService.applyUndetermined(
                request == null ? List.of() : request.fills()));
    }
}
