package io.github.Nyameph.nyaentworks.song.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.github.Nyameph.nyaentworks.common.ApiResult;
import io.github.Nyameph.nyaentworks.common.file.GroupFileOps;
import io.github.Nyameph.nyaentworks.common.lyric.LyricTextReader;
import io.github.Nyameph.nyaentworks.song.config.SongProperties;
import io.github.Nyameph.nyaentworks.song.service.SongArchiveService;
import io.github.Nyameph.nyaentworks.song.service.SongArtistStatService;
import io.github.Nyameph.nyaentworks.song.service.SongGroupService;
import io.github.Nyameph.nyaentworks.song.service.SongGroupService.SongGroup;
import io.github.Nyameph.nyaentworks.song.service.SongImportService;
import io.github.Nyameph.nyaentworks.song.service.SongLyricService;
import io.github.Nyameph.nyaentworks.song.service.SongSettingService;
import io.github.Nyameph.nyaentworks.song.service.SongMergeService;
import io.github.Nyameph.nyaentworks.song.service.SongSettingService;
import io.github.Nyameph.nyaentworks.song.service.SongStatService;
import io.github.Nyameph.nyaentworks.song.task.SongDeleteBatchHandler;
import io.github.Nyameph.nyaentworks.song.task.SongEditBatchHandler;
import io.github.Nyameph.nyaentworks.song.task.SongEditFormHandler;
import io.github.Nyameph.nyaentworks.song.task.SongImportHandler;
import io.github.Nyameph.nyaentworks.song.task.SongNormalizeBatchHandler;
import io.github.Nyameph.nyaentworks.song.task.SongSyncHandler;
import io.github.Nyameph.nyaentworks.song.util.SongName;
import io.github.Nyameph.nyaentworks.song.util.SongNameParser;
import io.github.Nyameph.nyaentworks.common.media.ScorePartition;
import io.github.Nyameph.nyaentworks.task.service.AsyncTaskService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnSong;

/**
 * 填词歌曲接口（文档第 8 章）。
 *
 * <p>三个页面（歌曲 / 归档打分 / 填词）共用这一套端点，靠 {@code partition} 区分：
 * {@code partition} 为空表示待打分区。这样分开的只有页面，不是逻辑 ——
 * 改评分、播放、取歌词对各页完全一样。喊麦是另一套端点（{@code /api/shout}）。
 *
 * <p>改评分 / 改名 / 删除都是两步：先 {@code GET .../*-plan} 拿到「组内哪些文件会怎么动」，
 * 页面展示给人看过，再真执行。理由见 {@link SongArchiveService} —— 漏搬一个文件，
 * 这首歌就裂成两半散在两个分区里。
 */
@Tag(name = "歌曲")
@ConditionalOnSong
@RestController
@RequestMapping("/api/song")
@RequiredArgsConstructor
public class SongController {

    private final SongGroupService groupService;
    private final SongMergeService mergeService;
    private final SongSettingService settingService;
    private final SongArchiveService archiveService;
    private final SongImportService importService;
    private final SongLyricService lyricService;
    private final SongStatService statService;
    private final SongArtistStatService artistStatService;
    private final AsyncTaskService taskService;
    private final SongProperties properties;

    @Operation(summary = "可选评分档位（含磁盘上还没建的那几档），按分数降序")
    @GetMapping("/partitions")
    public ApiResult<List<ScorePartition.Row>> partitions() {
        // 形态与喊麦侧同一份（common/media/ScorePartition.Row）—— 两个模块的
        // /partitions 下发的 JSON 一字不差，前端也就没有第二份解析。
        // 补没建的那几档（onDisk=false）是为了打分：选它会在落盘时现建出来（ScorePartition.rows）
        return ApiResult.ok(ScorePartition.rows(groupService.archivedRoot()));
    }

    @Operation(summary = "根目录与是否存在，页面据此禁用同步类操作")
    @GetMapping("/roots")
    public ApiResult<Map<String, Object>> roots() {
        String archived = groupService.archivedRoot();
        var staging = groupService.stagingRoot();
        return ApiResult.ok(Map.of(
                "archivedRoot", archived,
                "archivedRootExists", java.nio.file.Files.isDirectory(java.nio.file.Path.of(archived)),
                "stagingRoot", staging.toString(),
                "stagingRootExists", java.nio.file.Files.isDirectory(staging),
                // 归档前是否强制打标签：页面用它决定要不要在保存前先拦一道、提示语怎么写。
                // **认定仍在后端**（SongArchiveService#requireTagForArchive），这里只是把口径告诉页面
                "requireTagsBeforeArchive", properties.isRequireTagsBeforeArchive(),
                "presetRates", SongSettingService.presetRates()));
    }

    /**
     * 已归档的组，按作者+曲名+原曲归并（同一首歌的不同版本/文件类型合并成一行）。
     * <p>{@code 卡路里#1.mp4} 与 {@code 卡路里#2.mp3} 显示成一行，播放时可切换 variant。
     *
     * @param partition       只看某个分区，空则全部
     * @param keyword         搜作者 / 曲名 / 原曲名 / 原文件名
     * @param onlyFailed      只看解析失败的
     * @param onlyNormalizable 只看需要补全原曲名的
     */
    @Operation(summary = "已归档的组（归并模式）")
    @GetMapping("/merged-groups")
    public ApiResult<List<SongMergeService.MergedSongRow>> mergedGroups(
            @RequestParam(required = false) String partition,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "false") boolean onlyFailed,
            @RequestParam(defaultValue = "false") boolean onlyNormalizable) {
        List<SongGroup> groups = groupService.listArchived(partition);
        List<SongGroup> filtered = filter(groups, keyword, onlyFailed, onlyNormalizable);
        return ApiResult.ok(mergeService.merge(filtered));
    }

    /**
     * 待打分区的组，按作者+曲名+原曲归并。
     */
    @Operation(summary = "待打分区的组（归并模式）")
    @GetMapping("/merged-staging")
    public ApiResult<List<SongMergeService.MergedSongRow>> mergedStaging(
            @RequestParam(required = false) String keyword) {
        List<SongGroup> groups = groupService.listStaging();
        List<SongGroup> filtered = filter(groups, keyword, false, false);
        return ApiResult.ok(mergeService.merge(filtered));
    }

    // ------------------------------------------------------------------
    // 播放
    // ------------------------------------------------------------------

    /**
     * 播放一组要的全部信息。
     *
     * @param partition 空表示待打分区
     */
    @Operation(summary = "播放一组要的信息：文件路径、倍速（三级回落）、歌词文件清单")
    @GetMapping("/play-info")
    public ApiResult<Map<String, Object>> playInfo(
            @RequestParam(required = false) String partition,
            @RequestParam String mainName) {
        SongGroup group = groupService.require(partition, mainName);
        SongName name = group.name();
        String originalTitle = name != null && name.parsed() ? name.originalTitle() : null;
        String originalArtist = name != null && name.parsed() ? name.originalArtist() : null;
        // 播放路径也要能「存原曲名默认倍速」—— 保证有原曲名的歌都有设置行（id），
        // 否则 originalId 为空、存倍速无从定位（同统计页补建口径）
        if (originalTitle != null) {
            settingService.ensureOriginal(originalTitle, originalArtist);
        }
        SongSettingService.RateResolution rate =
                settingService.resolveRate(partition, mainName, originalTitle, originalArtist);

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("partition", partition);
        result.put("mainName", mainName);
        result.put("playFile", group.playFile());
        result.put("playPath", group.dir().resolve(group.playFile()).toString());
        result.put("hasVideo", group.hasVideo());
        result.put("lyricFiles", group.lyricFiles());
        result.put("rate", rate.rate());
        result.put("rateSource", rate.source());
        result.put("rateSourceLabel", rate.sourceLabel());
        result.put("originalTitle", originalTitle);
        result.put("originalArtist", originalArtist);
        result.put("originalId", rate.originalId());
        result.put("presetRates", SongSettingService.presetRates());
        return ApiResult.ok(result);
    }

    @Operation(summary = "取一个歌词文件。编码嗅探在后端做（GBK 那批前端解不出来）")
    @GetMapping("/lyric")
    public ApiResult<LyricTextReader.Lyric> lyric(
            @RequestParam(required = false) String partition,
            @RequestParam String mainName,
            @RequestParam(required = false) String fileName) {
        return ApiResult.ok(lyricService.read(partition, mainName, fileName));
    }

    public record RateRequest(String partition, String mainName, BigDecimal rate) {
    }

    @Operation(summary = "存组级默认倍速。播放中调倍速不会调这里，只有点「存为默认」才调")
    @PutMapping("/group-rate")
    public ApiResult<Void> saveGroupRate(@RequestBody RateRequest request) {
        settingService.saveGroupRate(request.partition(),
                request.mainName(), request.rate());
        return ApiResult.ok();
    }

    public record OriginalRateRequest(Long originalId, BigDecimal rate) {
    }

    @Operation(summary = "存原曲名级默认倍速，同一首原曲的所有填词版本共享")
    @PutMapping("/original-rate")
    public ApiResult<Void> saveOriginalRate(@RequestBody OriginalRateRequest request) {
        settingService.saveOriginalRate(request.originalId(), request.rate());
        return ApiResult.ok();
    }

    // ------------------------------------------------------------------
    // 改评分 / 改名 / 删除：都是整组，都是 plan + apply
    // ------------------------------------------------------------------

    @Operation(summary = "改评分的预演：组内每个文件会搬到哪")
    @GetMapping("/score-plan")
    public ApiResult<GroupFileOps.GroupPlan> scorePlan(
            @RequestParam(required = false) String partition,
            @RequestParam String mainName,
            @RequestParam int score) {
        return ApiResult.ok(archiveService.planScore(partition, mainName, score));
    }

    public record ScoreRequest(String partition, String mainName, int score) {
    }

    @Operation(summary = "改评分：整组搬到目标分区。分区目录不存在时报错，不自动创建")
    @PostMapping("/score")
    public ApiResult<SongArchiveService.GroupApplyResult> score(@RequestBody ScoreRequest request) {
        return ApiResult.ok(archiveService.applyScore(request.partition(),
                request.mainName(), request.score()));
    }

    @Operation(summary = "改名的预演")
    @GetMapping("/rename-plan")
    public ApiResult<GroupFileOps.GroupPlan> renamePlan(
            @RequestParam(required = false) String partition,
            @RequestParam String mainName,
            @RequestParam String newMainName) {
        return ApiResult.ok(archiveService.planRename(partition, mainName, newMainName));
    }

    public record RenameRequest(String partition, String mainName, String newMainName) {
    }

    @Operation(summary = "改名：整组改，扩展名不变")
    @PutMapping("/rename")
    public ApiResult<SongArchiveService.GroupApplyResult> rename(@RequestBody RenameRequest request) {
        return ApiResult.ok(archiveService.applyRename(request.partition(),
                request.mainName(), request.newMainName()));
    }

    @Operation(summary = "一次提交（评分 + 改名）的预演。score 不传表示不改评分")
    @GetMapping("/edit-plan")
    public ApiResult<GroupFileOps.GroupPlan> editPlan(
            @RequestParam(required = false) String partition,
            @RequestParam String mainName,
            @RequestParam(required = false) Integer score,
            @RequestParam String newMainName) {
        return ApiResult.ok(archiveService.planEdit(partition, mainName,
                score, newMainName));
    }

    public record EditRequest(String partition, String mainName,
                              Integer score, String newMainName) {
    }

    @Operation(summary = "一次提交：评分（可选）与改名（可选）同时做，扩展名不变")
    @PostMapping("/edit")
    public ApiResult<SongArchiveService.GroupApplyResult> edit(@RequestBody EditRequest request) {
        return ApiResult.ok(archiveService.applyEdit(request.partition(),
                request.mainName(), request.score(), request.newMainName()));
    }

    @Operation(summary = "规范化命名的预演：补全原曲名括号")
    @GetMapping("/normalize-plan")
    public ApiResult<GroupFileOps.GroupPlan> normalizePlan(
            @RequestParam(required = false) String partition,
            @RequestParam String mainName) {
        return ApiResult.ok(archiveService.planNormalize(partition, mainName));
    }

    public record NormalizeRequest(String partition, String mainName) {
    }

    @Operation(summary = "规范化命名：把 作者 - 曲名#版本 补成 作者 - 曲名（曲名）#版本")
    @PostMapping("/normalize")
    public ApiResult<SongArchiveService.GroupApplyResult> normalize(
            @RequestBody NormalizeRequest request) {
        return ApiResult.ok(archiveService.applyNormalize(request.partition(),
                request.mainName()));
    }

    public record NormalizeBatchRequest(List<String> keys) {
    }

    @Operation(summary = "批量规范化命名。一组失败不影响其它组")
    @PostMapping("/normalize-batch")
    public ApiResult<Long> normalizeBatch(@RequestBody NormalizeBatchRequest request) {
        int count = request == null || request.keys() == null ? 0 : request.keys().size();
        return ApiResult.ok(taskService.submit(SongNormalizeBatchHandler.TYPE, request,
                "规范化 " + count + " 组命名"));
    }

    @Operation(summary = "删除的预演：会删哪几个文件。删会进回收站，但仍先看清单")
    @GetMapping("/delete-plan")
    public ApiResult<GroupFileOps.GroupPlan> deletePlan(
            @RequestParam(required = false) String partition,
            @RequestParam String mainName) {
        return ApiResult.ok(archiveService.planDelete(partition, mainName));
    }

    @Operation(summary = "删除：整组删")
    @DeleteMapping("/group")
    public ApiResult<SongArchiveService.GroupApplyResult> delete(
            @RequestParam(required = false) String partition,
            @RequestParam String mainName) {
        return ApiResult.ok(archiveService.applyDelete(partition, mainName));
    }

    // ------------------------------------------------------------------
    // 批量操作（merged row 的所有 variant 一起动）
    // ------------------------------------------------------------------

    public record EditBatchRequest(List<SongArchiveService.GroupRef> groups,
                                   Integer score, String newBaseName) {
    }

    public record DeleteBatchRequest(List<SongArchiveService.GroupRef> groups) {
    }

    @Operation(summary = "批量一次提交（评分 + 改名）的预演。groups 是本行各 variant 的 (分区, 主名)")
    @PostMapping("/edit-batch-plan")
    public ApiResult<GroupFileOps.GroupPlan> editBatchPlan(
            @RequestBody EditBatchRequest request) {
        return ApiResult.ok(archiveService.planEditBatch(
                request.groups() == null ? List.of() : request.groups(),
                request.score(), request.newBaseName()));
    }

    @Operation(summary = "批量一次提交：所有 variant 一起评分（可选）+ 改名（可选），保留 #版本号 后缀")
    @PostMapping("/edit-batch")
    public ApiResult<Long> editBatch(@RequestBody EditBatchRequest request) {
        int count = request == null || request.groups() == null ? 0 : request.groups().size();
        return ApiResult.ok(taskService.submit(SongEditBatchHandler.TYPE, request,
                "批量评分/改名 " + count + " 组"));
    }

    @Operation(summary = "批量删除的预演：整行所有 variant 的文件清单")
    @PostMapping("/delete-batch-plan")
    public ApiResult<GroupFileOps.GroupPlan> deleteBatchPlan(
            @RequestBody DeleteBatchRequest request) {
        return ApiResult.ok(archiveService.planDeleteBatch(
                request.groups() == null ? List.of() : request.groups()));
    }

    @Operation(summary = "批量删除：整行所有 variant 一起删")
    @PostMapping("/delete-batch")
    public ApiResult<Long> deleteBatch(@RequestBody DeleteBatchRequest request) {
        int count = request == null || request.groups() == null ? 0 : request.groups().size();
        return ApiResult.ok(taskService.submit(SongDeleteBatchHandler.TYPE, request,
                "批量删除 " + count + " 组"));
    }

    // ------------------------------------------------------------------
    // 添加文件（① 添加文件弹窗）
    // ------------------------------------------------------------------

    public record ExpandRequest(List<String> paths) {
    }

    @Operation(summary = "源路径 → 联动展开后的文件清单（只读磁盘）")
    @PostMapping("/import-expand")
    public ApiResult<List<SongImportService.ExpandedGroup>> importExpand(
            @RequestBody ExpandRequest request) {
        return ApiResult.ok(importService.expand(request == null ? null : request.paths()));
    }

    public record ImportFile(String sourcePath, String version) {
    }

    /**
     * ① 的提交体。plan 与 apply 完全相同（与 {@code edit-batch-plan} / {@code edit-batch} 一致），
     * 这样「重新执行」拿 {@code params_json} 就能重跑。
     *
     * @param target "staging" 或分区目录名（如 "#9超赞"）；与 {@code score} 必须表达同一件事
     * @param score  null = 不打分（迁移到未归档）
     */
    public record ImportRequest(List<ImportFile> files, String artists, String title,
                                String originalTitle, boolean rename,
                                String target, Integer score, List<String> tags) {
    }

    @Operation(summary = "① 的预演：搬动清单 + blockedReason + 跨卷提示")
    @PostMapping("/import-plan")
    public ApiResult<SongImportService.ImportPlan> importPlan(@RequestBody ImportRequest request) {
        return ApiResult.ok(importService.planImport(request));
    }

    @Operation(summary = "① 的执行：搬 + 改名 + 写标签（+ 归档时回写库）。异步任务 song.import")
    @PostMapping("/import")
    public ApiResult<Long> importSongs(@RequestBody ImportRequest request) {
        int count = request == null || request.files() == null ? 0 : request.files().size();
        return ApiResult.ok(taskService.submit(SongImportHandler.TYPE, request,
                "导入 " + count + " 个文件"));
    }

    // ------------------------------------------------------------------
    // ②③ 列表式改造既有组：库清单 + 预演 + 执行
    // ------------------------------------------------------------------

    public record EditFormFile(String fileName, String version, Boolean exclude) {
    }

    public record EditFormGroup(String partition, String mainName, List<EditFormFile> files) {
    }

    /**
     * ②③ 列表式保存的提交体。
     *
     * @param exclude 已归档文件剔除标记（EditFormFile.exclude）：置 true 的行不参与常规搬动，
     *                提交后移入「冗余」文件夹并置 song_id=0
     * @param extraFiles ②③ 里通过「＋ 添加文件」选进来的外部文件（源路径由前端给，
     *                与 ① 同一形态）；共用表单与目标，编号逐文件独立，入库交同步补建
     */
    public record EditFormRequest(List<EditFormGroup> groups, String artists, String title,
                                  String originalTitle, boolean rename,
                                  String targetPartition, Integer score, List<String> tags,
                                  List<SongController.ImportFile> extraFiles) {
    }

    @Operation(summary = "一个既有组的 song_file 清单（只读库，画列表用）")
    @GetMapping("/group-files")
    public ApiResult<List<SongImportService.GroupFileRow>> groupFiles(
            @RequestParam(required = false) String partition,
            @RequestParam String mainName) {
        return ApiResult.ok(importService.groupFiles(partition, mainName));
    }

    @Operation(summary = "②③ 的预演：与 /import-plan 同一套 plan")
    @PostMapping("/edit-form-plan")
    public ApiResult<SongImportService.ImportPlan> editFormPlan(
            @RequestBody EditFormRequest request) {
        return ApiResult.ok(importService.planEditForm(request));
    }

    @Operation(summary = "②③ 的执行：搬 + 改名 + 写标签 + 回写库。异步任务 song.edit-form")
    @PostMapping("/edit-form")
    public ApiResult<Long> editForm(@RequestBody EditFormRequest request) {
        int n = request == null || request.groups() == null ? 0 : request.groups().size();
        return ApiResult.ok(taskService.submit(SongEditFormHandler.TYPE, request,
                "列表式保存 " + n + " 组"));
    }

    @Operation(summary = "冗余文件分页：剔除出组的已归档文件（song_id=0），只读展示")
    @GetMapping("/redundant-files")
    public ApiResult<SongSettingService.RedundantPage> redundantFiles(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResult.ok(importService.redundantFiles(page, size));
    }

    // ------------------------------------------------------------------
    // 按原曲名统计
    // ------------------------------------------------------------------

    @Operation(summary = "按原曲名统计。已归档与待打分分开计数")
    @GetMapping("/originals")
    public ApiResult<List<SongStatService.OriginalStat>> originals() {
        return ApiResult.ok(statService.listOriginals());
    }

    // ------------------------------------------------------------------
    // 按作者统计
    // ------------------------------------------------------------------

    @Operation(summary = "按作者统计。多作者歌曲展开（每个作者各算一次）")
    @GetMapping("/artists")
    public ApiResult<List<SongArtistStatService.ArtistStat>> artists() {
        return ApiResult.ok(artistStatService.listArtists());
    }

    // ------------------------------------------------------------------
    // 同步（磁盘 → 库）
    // ------------------------------------------------------------------

    @Operation(summary = "全量同步：扫磁盘重建归档歌曲库。磁盘是权威，库是镜像，改磁盘后跑这个")
    @PostMapping("/sync")
    public ApiResult<Long> sync() {
        return ApiResult.ok(taskService.submit(SongSyncHandler.TYPE, null));
    }

    // ------------------------------------------------------------------
    // 内部：筛选与行装配
    // ------------------------------------------------------------------

    /**
     * 筛选。放后端而不是前端过滤，是因为「解析失败」「需要规范化」这两个判断
     * 本来就在后端（文档 4.6 前端只画不判），搬到前端等于把解析规则抄一份。
     */
    private static List<SongGroup> filter(List<SongGroup> groups, String keyword,
                                          boolean onlyFailed, boolean onlyNormalizable) {
        String needle = StringUtils.trimToNull(keyword);
        List<SongGroup> result = new ArrayList<>();
        for (SongGroup group : groups) {
            SongName name = group.name();
            if (onlyFailed && (name == null || name.parsed())) {
                continue;
            }
            if (onlyNormalizable && (name == null || !name.needsNormalize())) {
                continue;
            }
            if (needle != null && !matches(group, name, needle)) {
                continue;
            }
            result.add(group);
        }
        return result;
    }

    /** 主名一定比，解析出来的三段有就比 —— 搜「原曲名」时不该漏掉解析失败的那批 */
    private static boolean matches(SongGroup group, SongName name, String needle) {
        if (StringUtils.containsIgnoreCase(group.mainName(), needle)) {
            return true;
        }
        if (name == null || !name.parsed()) {
            return false;
        }
        return StringUtils.containsIgnoreCase(name.artistText(), needle)
                || StringUtils.containsIgnoreCase(name.title(), needle)
                || StringUtils.containsIgnoreCase(name.originalTitle(), needle);
    }

    /** 供页面显示认识的扩展名，与后端各写一份会不一致 */
    @Operation(summary = "认识的扩展名分类")
    @GetMapping("/extensions")
    public ApiResult<Map<String, List<String>>> extensions() {
        return ApiResult.ok(Map.of(
                "video", SongNameParser.videoExtensions(),
                "audio", SongNameParser.audioExtensions(),
                "lyric", SongNameParser.lyricExtensions()));
    }
}
