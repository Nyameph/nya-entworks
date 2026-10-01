package io.github.Nyameph.nyaentworks.song.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.github.Nyameph.nyaentworks.common.ApiResult;
import io.github.Nyameph.nyaentworks.common.lyric.LyricTextReader;
import io.github.Nyameph.nyaentworks.song.service.SongOriginalImportService;
import io.github.Nyameph.nyaentworks.song.service.SongTemplateService;
import io.github.Nyameph.nyaentworks.song.task.SongOriginalImportHandler;
import io.github.Nyameph.nyaentworks.song.task.SongTemplateScanHandler;
import io.github.Nyameph.nyaentworks.song.task.SongTemplateSearchHandler;
import io.github.Nyameph.nyaentworks.task.service.AsyncTaskService;

import java.util.List;
import java.util.Map;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnSong;

/**
 * 模板维护接口（原曲页的功能，文档 5.7）。
 *
 * <p>{@code /scan} 是整页的「扫描模板」按钮；{@code /detail}、{@code /candidates}、
 * {@code /apply} 是原曲每行后「手动编辑模板数据」的展开区，前者取当前七类文件名与
 * 歌手，中者取该原曲模板目录下可选的候选文件，后者提交「按文件名指派」+ 写库。
 * {@code /lyric} 给列表页 / 编辑弹窗查看模板里的歌词。
 */
@Tag(name = "歌曲模板")
@ConditionalOnSong
@RestController
@RequestMapping("/api/song/template")
@RequiredArgsConstructor
public class SongTemplateController {

    private final SongTemplateService templateService;
    private final SongOriginalImportService originalImportService;
    private final AsyncTaskService taskService;

    @Operation(summary = "扫描模板目录，更新 song_original_setting 的文件类型字段")
    @PostMapping("/scan")
    public ApiResult<Long> scan() {
        return ApiResult.ok(taskService.submit(SongTemplateScanHandler.TYPE, null));
    }

    @Operation(summary = "某原曲的模板详情（七类文件名 + 歌手 + 四项确认位 + 解析出的播放路径）")
    @GetMapping("/detail")
    public ApiResult<SongTemplateService.TemplateDetail> detail(@RequestParam Long originalId) {
        return ApiResult.ok(templateService.getDetail(originalId));
    }

    @Operation(summary = "该原曲模板目录下可选的候选文件（文件名，编辑弹窗的下拉据此填选）")
    @GetMapping("/candidates")
    public ApiResult<List<String>> candidates(@RequestParam Long originalId) {
        return ApiResult.ok(templateService.listCandidates(originalId));
    }

    /** 手动提交：originalId 定位原曲，artist 填歌手，files 是「类别 -> 文件名」，
     *  xxxCheck 是「已手动确认」标记（true = 后续扫描 / 搜索不再覆盖） */
    public record ApplyRequest(Long originalId, String artist,
                               Map<String, String> files,
                               boolean artistCheck, boolean originalCheck, boolean lyricCheck,
                               boolean svpCheck) {
    }

    @Operation(summary = "手动把模板目录下的文件按文件名指派到七类并填歌手，写库")
    @PostMapping("/apply")
    public ApiResult<SongTemplateService.ApplyResult> apply(@RequestBody ApplyRequest request) {
        return ApiResult.ok(templateService.applyFiles(request.originalId(),
                request.artist(), request.files(),
                request.artistCheck(), request.originalCheck(), request.lyricCheck(),
                request.svpCheck()));
    }

    /** 移入冗余：originalId 定位原曲，fileName 是原曲目录下的文件名，
     *  type 是它当前归属的类别（用于清空对应库字段） */
    public record RetireFileRequest(Long originalId, String fileName, String type) {
    }

    @Operation(summary = "把原曲目录下的文件移入原曲冗余根并清空对应库字段（磁盘先动、库后动）")
    @PostMapping("/retire-file")
    public ApiResult<String> retireFile(@RequestBody RetireFileRequest request) {
        return ApiResult.ok(templateService.retireFile(request.originalId(),
                request.fileName(), request.type()));
    }

    @Operation(summary = "读某原曲模板里的歌词（编码嗅探在后端做）")
    @GetMapping("/lyric")
    public ApiResult<LyricTextReader.Lyric> lyric(@RequestParam Long originalId,
            @RequestParam(required = false) String kind) {
        return ApiResult.ok(templateService.readLyric(originalId, kind));
    }

    // ==================== 新增原曲 / 修改（从任意路径导入） ====================
    // 三处共用一份计算（SongOriginalImportService#compute）：expand 只读磁盘画预览，
    // plan 出搬动清单给人确认，import 提交成异步任务落盘 + 写库。
    // 源可以是任意盘 / 任意目录（与「添加歌曲」一致），目标路径一律由后端算。

    @Operation(summary = "新增/修改原曲：把选中的文件展开成「旧名 → 新名」的预览（只读磁盘）")
    @PostMapping("/import-expand")
    public ApiResult<SongOriginalImportService.ExpandResult> importExpand(
            @RequestBody SongOriginalImportService.ExpandRequest request) {
        return ApiResult.ok(originalImportService.expand(request));
    }

    @Operation(summary = "新增/修改原曲：出搬动清单（预演，不移文件）")
    @PostMapping("/import-plan")
    public ApiResult<SongOriginalImportService.OriginalImportPlan> importPlan(
            @RequestBody SongOriginalImportService.ImportRequest request) {
        return ApiResult.ok(originalImportService.plan(request));
    }

    @Operation(summary = "新增/修改原曲：提交（落盘 + 写库，跑在后台任务上）")
    @PostMapping("/import")
    public ApiResult<Long> importSubmit(
            @RequestBody SongOriginalImportService.ImportRequest request) {
        String name = request == null || request.rawName() == null ? "" : request.rawName();
        return ApiResult.ok(taskService.submit(SongOriginalImportHandler.TYPE, request,
                (request != null && request.isNew() ? "新增原曲「" : "修改原曲「") + name + "」"));
    }

    /** 单曲「搜资源」请求：原曲名 + 原曲作者定位（旧格式作者为空） */
    public record SearchRequest(String originalTitle, String artist) {
    }

    @Operation(summary = "从网络搜索并补全某原曲的歌手/原曲/歌词（只补缺失项，只认未加密文件）")
    @PostMapping("/search")
    public ApiResult<Long> search(@RequestBody SearchRequest req) {
        return ApiResult.ok(taskService.submit(SongTemplateSearchHandler.TYPE, req,
                "搜索「" + (req == null || req.originalTitle() == null ? "" : req.originalTitle()) + "」"));
    }
}
