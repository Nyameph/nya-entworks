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
import io.github.Nyameph.nyaentworks.manga.config.MangaProperties;
import io.github.Nyameph.nyaentworks.manga.consts.MangaArchiveUnitStatus;
import io.github.Nyameph.nyaentworks.manga.service.MangaArchiveService;
import io.github.Nyameph.nyaentworks.manga.service.MangaNewService;
import io.github.Nyameph.nyaentworks.manga.task.MangaArchiveScanMangasHandler;
import io.github.Nyameph.nyaentworks.manga.task.MangaArchiveSyncHandler;
import io.github.Nyameph.nyaentworks.manga.util.MangaScoreDir;
import io.github.Nyameph.nyaentworks.task.service.AsyncTaskService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnManga;

/**
 * 归档作者/社团接口（文档 4.4）。
 * <p><b>文件系统是权威</b>：这里没有「改社团名」「改标签」这类接口，
 * 改归档信息只有 {@link #renameFolder} 一条路 —— 改目录名，库跟着走。
 * 反方向不通，库里改了磁盘没改的话，下次同步会原样覆盖回去。
 */
@Tag(name = "漫画-归档")
@ConditionalOnManga
@RestController
@RequestMapping("/api/manga/archive")
@RequiredArgsConstructor
public class MangaArchiveController {

    private final MangaArchiveService archiveService;
    private final AsyncTaskService taskService;
    private final MangaProperties properties;

    /**
     * 一个归档根的状态。
     *
     * @param exists 不在的话（外挂盘没挂上）同步会把该分区下的目录全判成失踪，
     *               所以页面要在同步按钮旁先说清楚
     */
    public record RootView(String rootPath, int score, boolean exists) {
    }

    @Operation(summary = "四个归档根及其评分，含在不在磁盘上")
    @GetMapping("/roots")
    public ApiResult<List<RootView>> roots() {
        // 四挡都要报出来，缺的报 exists=false —— 前端的同步按钮正是拿它当禁用依据：
        // 归档根不在时同步会把该分区下的归档目录全判成失踪，所以宁可禁掉。
        // 缺的那一挡 rootPath 给 null 而不是归档根本身：分区目录名要从磁盘上扫才知道，
        // 拿归档根顶上等于谎报「这一档就是这个路径」。
        String archiveRoot = properties.getArchiveDir();
        Map<Integer, Path> dirs = MangaScoreDir.scoreDirs(archiveRoot);
        List<RootView> list = new ArrayList<>();
        for (int score : MangaScoreDir.SCORES) {
            Path dir = dirs.get(score);
            list.add(dir != null
                    ? new RootView(dir.toString(), score, true)
                    : new RootView(null, score, false));
        }
        return ApiResult.ok(list);
    }

    @Operation(summary = "从磁盘同步，返回新增/更新/改名/合并/失踪与迁移明细。异步，返回任务 id")
    @PostMapping("/sync")
    public ApiResult<Long> sync() {
        return ApiResult.ok(taskService.submit(MangaArchiveSyncHandler.TYPE, null));
    }

    @Operation(summary = "扫描归档目录下的漫画，补齐 manga_data 的 ARCHIVED 层。异步，返回任务 id")
    @PostMapping("/scan-mangas")
    public ApiResult<Long> scanMangas() {
        return ApiResult.ok(taskService.submit(MangaArchiveScanMangasHandler.TYPE, null));
    }

    @Operation(summary = "归档目录列表，可按状态/评分/标签/关键词筛，也可只看重名的")
    @GetMapping("/units")
    public ApiResult<List<MangaArchiveService.UnitView>> units(
            @RequestParam(required = false) MangaArchiveUnitStatus status,
            @RequestParam(required = false) Integer score,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "false") boolean conflictOnly) {
        return ApiResult.ok(archiveService.listUnits(status, score, keyword, tag, conflictOnly));
    }

    @Operation(summary = "一个归档目录下的漫画（合集弹窗）：每本现扫的卡片字段")
    @GetMapping("/units/{id}/mangas")
    public ApiResult<List<MangaNewService.NewManga>> unitMangas(@PathVariable Long id) {
        return ApiResult.ok(archiveService.unitMangas(id));
    }

    @Operation(summary = "一个归档目录的标签（作者相关标签），供编辑弹窗预填继承标签")
    @GetMapping("/units/{id}/tags")
    public ApiResult<List<String>> unitTags(@PathVariable Long id) {
        return ApiResult.ok(archiveService.listTags(id));
    }

    @Operation(summary = "重名冲突：同一个名字落在多个归档目录上")
    @GetMapping("/conflicts")
    public ApiResult<List<MangaArchiveService.NameConflict>> conflicts() {
        return ApiResult.ok(archiveService.findConflicts());
    }

    public record RenameRequest(String newFolderName) {
    }

    @Operation(summary = "只改目录名，其余按新目录名重新解析；原有关联作者不解绑")
    @PutMapping("/units/{id}/folder-name")
    public ApiResult<MangaArchiveService.UnitView> renameFolder(
            @PathVariable Long id, @RequestBody RenameRequest request) {
        return ApiResult.ok(archiveService.renameFolder(id,
                request == null ? null : request.newFolderName()));
    }

    @Operation(summary = "编辑表单的预演：算出会改成什么，只读不落盘")
    @PostMapping("/units/{id}/edit-plan")
    public ApiResult<MangaArchiveService.UnitEditPlan> editPlan(
            @PathVariable Long id,
            @RequestBody MangaArchiveService.UnitEditRequest request) {
        return ApiResult.ok(archiveService.planEdit(id, request));
    }

    @Operation(summary = "提交编辑：目录名/关联作者/标签/评分分区一并落盘")
    @PutMapping("/units/{id}")
    public ApiResult<MangaArchiveService.UnitEditPlan> edit(
            @PathVariable Long id,
            @RequestBody MangaArchiveService.UnitEditRequest request) {
        return ApiResult.ok(archiveService.applyEdit(id, request));
    }

    @Operation(summary = "确认已删除：把失踪目录从库里清掉，连带别名与标签关联")
    @DeleteMapping("/units/{id}")
    public ApiResult<Void> forget(@PathVariable Long id) {
        archiveService.forgetUnit(id);
        return ApiResult.ok();
    }

    public record MangaRenameRequest(String folderPath, String newFolderName) {
    }

    @Operation(summary = "改归档漫画目录名（原地），同步 manga_data.folder_path")
    @PutMapping("/mangas/folder-name")
    public ApiResult<String> renameManga(@RequestBody MangaRenameRequest request) {
        return ApiResult.ok(archiveService.renameManga(
                request == null ? null : request.folderPath(),
                request == null ? null : request.newFolderName()));
    }

    public record MangaScoreRequest(String folderPath, Integer score) {
    }

    @Operation(summary = "给归档漫画单独评分（SELF）；传 null 撤销，回到继承归档目录分")
    @PutMapping("/mangas/score")
    public ApiResult<Void> scoreManga(@RequestBody MangaScoreRequest request) {
        archiveService.scoreManga(request == null ? null : request.folderPath(),
                request == null ? null : request.score());
        return ApiResult.ok();
    }

    @Operation(summary = "删除归档漫画目录，同步删 manga_data 行")
    @DeleteMapping("/mangas")
    public ApiResult<Void> deleteManga(@RequestParam String folderPath) {
        archiveService.deleteManga(folderPath);
        return ApiResult.ok();
    }

    public record MangaMoveRequest(String folderPath, Long toUnitId) {
    }

    @Operation(summary = "把归档漫画移到另一个归档目录，评分继承随动")
    @PostMapping("/mangas/move")
    public ApiResult<String> moveManga(@RequestBody MangaMoveRequest request) {
        return ApiResult.ok(archiveService.moveManga(
                request == null ? null : request.folderPath(),
                request == null ? null : request.toUnitId()));
    }

    @Operation(summary = "合并完成：删掉已清空的归档目录及其库行（目录进回收站，二次确认）")
    @PostMapping("/units/{id}/delete-empty")
    public ApiResult<Void> deleteEmptyUnit(@PathVariable Long id) {
        archiveService.deleteEmptyUnit(id);
        return ApiResult.ok();
    }

    // ------------------------------------------------------------------
    // 合并冲突页：归档目录根下的「其他文件」（文档 4.6）
    //
    // 只做「列出来 + 批量移动」两件事，不做删除 / 改名（2026-09-15 裁决，见 设计概述「合并冲突」）。
    // 这些文件不是漫画、不在库里，所以磁盘动完没有「库跟着走」这一步。
    // ------------------------------------------------------------------

    @Operation(summary = "归档目录根下的其他文件：直接子项里既不是漫画、也不是评分分区的那些")
    @GetMapping("/units/{id}/others")
    public ApiResult<List<MangaArchiveService.OtherEntry>> unitOthers(@PathVariable Long id) {
        return ApiResult.ok(archiveService.unitOthers(id));
    }

    public record OtherMoveRequest(Long fromUnitId, Long toUnitId, List<String> names) {
    }

    @Operation(summary = "其他文件搬动预演：只读，列出每一条的新旧路径与拦截原因")
    @PostMapping("/others/move-plan")
    public ApiResult<MangaArchiveService.OtherMovePlan> planOtherMove(
            @RequestBody OtherMoveRequest request) {
        return ApiResult.ok(archiveService.planOtherMove(
                request == null ? null : request.fromUnitId(),
                request == null ? null : request.toUnitId(),
                request == null ? null : request.names()));
    }

    @Operation(summary = "其他文件批量移动：任一条被拦就整批不搬")
    @PostMapping("/others/move")
    public ApiResult<Integer> moveOthers(@RequestBody OtherMoveRequest request) {
        return ApiResult.ok(archiveService.applyOtherMove(
                request == null ? null : request.fromUnitId(),
                request == null ? null : request.toUnitId(),
                request == null ? null : request.names()));
    }
}
