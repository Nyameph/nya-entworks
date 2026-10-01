package io.github.Nyameph.nyaentworks.shout.controller;

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
import io.github.Nyameph.nyaentworks.common.media.ScorePartition;
import io.github.Nyameph.nyaentworks.shout.config.ShoutProperties;
import io.github.Nyameph.nyaentworks.shout.service.ShoutArchiveService;
import io.github.Nyameph.nyaentworks.shout.service.ShoutGroupService;
import io.github.Nyameph.nyaentworks.shout.service.ShoutGroupService.ShoutGroup;
import io.github.Nyameph.nyaentworks.shout.service.ShoutImportService;
import io.github.Nyameph.nyaentworks.shout.service.ShoutLyricService;
import io.github.Nyameph.nyaentworks.shout.service.ShoutStoreService;
import io.github.Nyameph.nyaentworks.task.service.AsyncTaskService;
import io.github.Nyameph.nyaentworks.shout.task.ShoutDeleteBatchHandler;
import io.github.Nyameph.nyaentworks.shout.task.ShoutEditBatchHandler;
import io.github.Nyameph.nyaentworks.shout.task.ShoutEditFormHandler;
import io.github.Nyameph.nyaentworks.shout.task.ShoutImportHandler;
import io.github.Nyameph.nyaentworks.shout.task.ShoutSyncHandler;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnShout;

/**
 * 喊麦接口。
 *
 * <p>两个页面（未归档 / 已归档）共用这一套端点，靠 {@code partition} 区分：
 * {@code partition} 为空表示待打分。改评分 / 改名 / 删除都是两步：先 {@code *-plan} 拿到
 * 「组内哪些文件会怎么动」，页面展示给人看过，再真执行 —— 漏搬一个文件，这组就裂成两半
 * 散在两个分区里。
 *
 * <p>比歌曲侧少的端点都是「喊麦不解析文件名」的直接后果：没有规范化命名、没有原曲/作者
 * 统计，列表外也没有别的读库端点。列表与歌曲同款：每次<b>现扫磁盘</b>
 * （{@code /api/song/merged-groups} 也是这样），镜像表（{@code shout_group} /
 * {@code shout_file}）只做归档记录与设置的挂靠点，不参与出列表。
 *
 * <p>写操作只有<b>批量</b>形态（{@code edit-batch*} / {@code delete-batch*}），因为前端的
 * {@code SongActions} 把 {@code execute()} 的返回值当任务 id 交给轮询 —— 单组走同步端点
 * 会直接失败。一行只有一个文件组，所以「批量」在这里通常只传一项。
 */
@Tag(name = "喊麦")
@ConditionalOnShout
@RestController
@RequestMapping("/api/shout")
@RequiredArgsConstructor
public class ShoutController {

    private final ShoutGroupService groupService;
    private final ShoutStoreService storeService;
    private final ShoutArchiveService archiveService;
    private final ShoutImportService importService;
    private final ShoutLyricService lyricService;
    private final AsyncTaskService taskService;
    private final ShoutProperties properties;

    // ------------------------------------------------------------------
    // 视图
    // ------------------------------------------------------------------

    /**
     * 列表里的一行 = 一组文件。
     *
     * <p>字段名与歌曲的 {@code MergedSongRow} 刻意保持一致（{@code variants} 里那几个也一样）：
     * 前端的动作弹窗、播放浮层、标签编辑器是<b>同一份代码</b>，两边形状不同就得抄第二份。
     * 喊麦没有的那些（本就没有作者/曲名/原曲名、没有版本号）填中性值，见 {@link #rowOf}。
     *
     * @param mergeKey     组的主名（喊麦的标签键就是主名），前端定位用
     * @param tags         这组的标签（DB-only，打标签/归档闸门用）
     */
    public record ShoutRow(String mergeKey,
                           List<String> artists,
                           String title,
                           String originalTitle,
                           List<Variant> variants,
                           boolean parsed,
                           String parseFailedReason,
                           boolean needsNormalize,
                           List<String> tags) {
    }

    /**
     * 一行里的文件组。
     *
     * @param mainName         主名（含扩展名外的一切，{@code #} 是普通字符、不是版本号）
     * @param key              {@code "分区|主名"} 用于前端回传定位
     * @param partitionDisplay 分区的显示文本（如 {@code "9 分 超赞"}）
     * @param defaultRate      组默认倍速；没存过为 {@code null}
     */
    public record Variant(String mainName,
                          String key,
                          String root,
                          String partitionName,
                          Integer score,
                          String videoFile,
                          String audioFile,
                          List<String> lyricFiles,
                          String partitionDisplay,
                          BigDecimal defaultRate) {
    }

    /**
     * 装配一行。
     * <p>{@code parsed} 恒为 {@code false}：喊麦不解析文件名，「曲名」就是主名本身 ——
     * 播放浮层据此直接显示主名，不去拼「作者 － 曲名（原曲）」那一行。
     */
    private static ShoutRow rowOf(ShoutGroup group, String partitionDisplay,
                                  BigDecimal defaultRate, List<String> tags) {
        return new ShoutRow(group.mainName(), List.of(), group.mainName(), null,
                List.of(new Variant(group.mainName(), group.key(), group.root(),
                        group.partitionName(), group.score(), group.videoFile(), group.audioFile(),
                        group.lyricFiles(), partitionDisplay, defaultRate)),
                false, null, false, tags);
    }

    // ------------------------------------------------------------------
    // 根目录 / 分区 / 列表
    // ------------------------------------------------------------------

    @Operation(summary = "根目录与是否存在，页面据此禁用操作")
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
                // **认定仍在后端**（ShoutArchiveService#requireTagForArchive），这里只是把口径告诉页面
                "requireTagsBeforeArchive", properties.isRequireTagsBeforeArchive(),
                "presetRates", ShoutStoreService.presetRates()));
    }

    @Operation(summary = "可选评分档位（含磁盘上还没建的那几档），按分数降序")
    @GetMapping("/partitions")
    public ApiResult<List<ScorePartition.Row>> partitions() {
        // 与歌曲侧同一份形态（common/media/ScorePartition.Row）。这里原先手工拼 Map，
        // 下发的 JSON 虽然一样，但两边的 /partitions 是两套写法 —— 2026-09-29 收成一份。
        // 补没建的那几档（onDisk=false）是为了打分：选它会在落盘时现建出来（ScorePartition.rows）
        return ApiResult.ok(ScorePartition.rows(groupService.archivedRoot()));
    }

    /**
     * 已归档的喊麦，一行一组。
     *
     * @param partition 只看某个分区，空则全部
     * @param keyword   搜主名
     */
    @Operation(summary = "已归档的喊麦。每行是一组文件（视频/音频/歌词），不是一个文件")
    @GetMapping("/groups")
    public ApiResult<List<ShoutRow>> groups(@RequestParam(required = false) String partition,
                                            @RequestParam(required = false) String keyword) {
        List<ShoutGroup> groups = filter(groupService.listArchived(partition), keyword);
        Map<String, BigDecimal> rates = storeService.loadRates(groups);
        Map<String, List<String>> tags = storeService.listTagsBatch(
                groups.stream().map(ShoutGroup::mainName).toList());
        return ApiResult.ok(toRows(groups, rates, tags));
    }

    /**
     * 待打分区的喊麦，归并形态的同一套字段。
     * <p>待打分区的组不入库，所以倍速恒为 {@code null}（页面显示 1.0），标签照常读得到 ——
     * 标签是按主名存的，与归档与否无关。
     */
    @Operation(summary = "待打分区的喊麦（#已压缩歌曲 下的 喊麦/）")
    @GetMapping("/merged-staging")
    public ApiResult<List<ShoutRow>> mergedStaging(@RequestParam(required = false) String keyword) {
        List<ShoutGroup> groups = filter(groupService.listStaging(), keyword);
        Map<String, List<String>> tags = storeService.listTagsBatch(
                groups.stream().map(ShoutGroup::mainName).toList());
        return ApiResult.ok(toRows(groups, Map.of(), tags));
    }

    private static List<ShoutGroup> filter(List<ShoutGroup> groups, String keyword) {
        String needle = StringUtils.trimToNull(keyword);
        if (needle == null) {
            return groups;
        }
        List<ShoutGroup> result = new ArrayList<>();
        for (ShoutGroup group : groups) {
            if (StringUtils.containsIgnoreCase(group.mainName(), needle)) {
                result.add(group);
            }
        }
        return result;
    }

    private static List<ShoutRow> toRows(List<ShoutGroup> groups,
                                         Map<String, BigDecimal> rates,
                                         Map<String, List<String>> tags) {
        List<ShoutRow> rows = new ArrayList<>(groups.size());
        for (ShoutGroup group : groups) {
            ScorePartition.Partition partition = group.partitionName() == null ? null
                    : ScorePartition.parse(group.partitionName());
            rows.add(rowOf(group,
                    partition == null ? "待打分" : partition.display(),
                    rates.get(group.key()),
                    tags.getOrDefault(group.mainName(), List.of())));
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // 同步（磁盘 → 库）
    // ------------------------------------------------------------------

    /**
     * 全量同步：扫归档根重建 {@code shout_group} / {@code shout_file}。
     * <p>同歌曲侧：这是<b>异步任务</b>，返回的是任务 id 不是同步结果 —— 扫盘 + 上千次写库
     * 不该占着请求线程。启动时 {@code ShoutSyncOnStartup} 会自动提交一次，这个端点用来手动补跑。
     */
    @Operation(summary = "全量同步：扫磁盘重建归档喊麦镜像。磁盘是权威，库是镜像，改磁盘后跑这个")
    @PostMapping("/sync")
    public ApiResult<Long> sync() {
        return ApiResult.ok(taskService.submit(ShoutSyncHandler.TYPE, null));
    }

    // ------------------------------------------------------------------
    // 播放 / 歌词 / 倍速
    // ------------------------------------------------------------------

    /**
     * 播放一组要的全部信息。
     *
     * @param partition 空表示待打分区
     */
    @Operation(summary = "播放一组要的信息：文件路径、倍速（组级 → 1.0）、歌词文件清单")
    @GetMapping("/play-info")
    public ApiResult<Map<String, Object>> playInfo(
            @RequestParam(required = false) String partition,
            @RequestParam String mainName) {
        ShoutGroup group = groupService.require(partition, mainName);
        BigDecimal rate = storeService.rateOf(partition, mainName);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("partition", partition);
        result.put("mainName", mainName);
        result.put("playFile", group.playFile());
        result.put("playPath", group.dir().resolve(group.playFile()).toString());
        result.put("hasVideo", group.hasVideo());
        result.put("lyricFiles", group.lyricFiles());
        result.put("rate", rate);
        // 喊麦没有原曲名那一级回落，所以来源只有「组默认」与「默认 1.0」两种
        boolean groupRate = partition != null && rate.compareTo(ShoutStoreService.DEFAULT_RATE) != 0;
        result.put("rateSource", groupRate ? "GROUP" : "DEFAULT");
        result.put("rateSourceLabel", groupRate ? "组默认" : "默认 1.0");
        result.put("originalTitle", null);
        result.put("originalArtist", null);
        result.put("originalId", null);
        result.put("presetRates", ShoutStoreService.presetRates());
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
        storeService.saveRate(request.partition(), request.mainName(), request.rate());
        return ApiResult.ok();
    }

    // ------------------------------------------------------------------
    // 改评分 / 改名 / 删除：都是整组，都是 plan + apply
    // ------------------------------------------------------------------

    public record EditBatchRequest(List<ShoutArchiveService.GroupRef> groups,
                                   Integer score, String newBaseName) {
    }

    public record DeleteBatchRequest(List<ShoutArchiveService.GroupRef> groups) {
    }

    @Operation(summary = "一次提交（评分 + 改名）的预演。groups 是这一行的文件组，通常只有一项")
    @PostMapping("/edit-batch-plan")
    public ApiResult<GroupFileOps.GroupPlan> editBatchPlan(@RequestBody EditBatchRequest request) {
        return ApiResult.ok(archiveService.planEditBatch(
                request.groups() == null ? List.of() : request.groups(),
                request.score(), request.newBaseName()));
    }

    @Operation(summary = "一次提交：评分（可选）与改名（可选）同时做，扩展名不变")
    @PostMapping("/edit-batch")
    public ApiResult<Long> editBatch(@RequestBody EditBatchRequest request) {
        int count = request == null || request.groups() == null ? 0 : request.groups().size();
        return ApiResult.ok(taskService.submit(ShoutEditBatchHandler.TYPE, request,
                "喊麦批量保存 " + count + " 组"));
    }

    @Operation(summary = "删除的预演：会删哪几个文件。删会进回收站，但仍先看清单")
    @PostMapping("/delete-batch-plan")
    public ApiResult<GroupFileOps.GroupPlan> deleteBatchPlan(
            @RequestBody DeleteBatchRequest request) {
        return ApiResult.ok(archiveService.planDeleteBatch(
                request.groups() == null ? List.of() : request.groups()));
    }

    @Operation(summary = "删除：整组删")
    @DeleteMapping("/delete-batch")
    public ApiResult<Long> deleteBatch(@RequestBody DeleteBatchRequest request) {
        int count = request == null || request.groups() == null ? 0 : request.groups().size();
        return ApiResult.ok(taskService.submit(ShoutDeleteBatchHandler.TYPE, request,
                "喊麦批量删除 " + count + " 组"));
    }

    // ------------------------------------------------------------------
    // 添加文件（① 添加文件弹窗）—— 歌曲侧那套的简化版
    // ------------------------------------------------------------------

    public record ExpandRequest(List<String> paths) {
    }

    @Operation(summary = "源路径 → 联动展开后的文件清单（只读磁盘）。源在受管根里则整组拒")
    @PostMapping("/import-expand")
    public ApiResult<List<ShoutImportService.ExpandedGroup>> importExpand(
            @RequestBody ExpandRequest request) {
        return ApiResult.ok(importService.expand(request == null ? null : request.paths()));
    }

    /** 一个要添加的源文件。喊麦不解析文件名，所以没有歌曲侧那个 {@code version} */
    public record ImportFile(String sourcePath) {
    }

    /**
     * ① 的提交体。plan 与 apply 完全相同（与 {@code edit-batch-plan} / {@code edit-batch} 一致），
     * 这样「重新执行」拿 {@code params_json} 就能重跑。
     *
     * @param newMainName 新全名（可选）；空 = 保持原文件名。<b>喊麦改的是完整主名</b>，
     *                    没有 {@code #版本号} 语义
     * @param target       "staging" 或分区目录名（如 {@code "#9超赞"}）；与 {@code score} 必须表达同一件事
     * @param score        null = 不打分（迁移到未归档）
     */
    public record ImportRequest(List<ImportFile> files, String newMainName, String target,
                                Integer score, List<String> tags) {
    }

    @Operation(summary = "① 的预演：搬动清单 + blockedReason + 跨卷提示")
    @PostMapping("/import-plan")
    public ApiResult<ShoutImportService.ImportPlan> importPlan(@RequestBody ImportRequest request) {
        return ApiResult.ok(importService.planImport(request));
    }

    @Operation(summary = "① 的执行：搬 + 改名 + 写标签。异步任务 shout.import")
    @PostMapping("/import")
    public ApiResult<Long> importShouts(@RequestBody ImportRequest request) {
        int count = request == null || request.files() == null ? 0 : request.files().size();
        return ApiResult.ok(taskService.submit(ShoutImportHandler.TYPE, request,
                "导入 " + count + " 个文件"));
    }

    // ------------------------------------------------------------------
    // ② 列表式修改既有组：预演 + 执行（文件清单由页面从列表行拿，不另开读库端点）
    // ------------------------------------------------------------------

    /** 一个既有文件；{@code exclude} = 剔除（提交后移入「冗余」文件夹） */
    public record EditFormFile(String fileName, Boolean exclude) {
    }

    public record EditFormGroup(String partition, String mainName, List<EditFormFile> files) {
    }

    /**
     * ② 列表式保存的提交体。
     *
     * @param newMainName 新全名（可选）；空 = 保持原名
     * @param score       null = 不改评分、留在原分区；给了 = 搬到对应的评分分区（归档闸门在这条路上）
     * @param tags        这次提交之后的标签集合；{@code null} = 这次不动标签
     * @param extraFiles  ② 里通过「＋ 添加文件」选进来的外部文件，共用新全名与目标
     */
    public record EditFormRequest(List<EditFormGroup> groups, String newMainName, Integer score,
                                  List<String> tags, List<ImportFile> extraFiles) {
    }

    @Operation(summary = "② 的预演：与 /import-plan 同一套 plan")
    @PostMapping("/edit-form-plan")
    public ApiResult<ShoutImportService.ImportPlan> editFormPlan(
            @RequestBody EditFormRequest request) {
        return ApiResult.ok(importService.planEditForm(request));
    }

    @Operation(summary = "② 的执行：补文件 / 改全名 / 改评分 / 剔除 / 写标签。异步任务 shout.edit-form")
    @PostMapping("/edit-form")
    public ApiResult<Long> editForm(@RequestBody EditFormRequest request) {
        int n = request == null || request.groups() == null ? 0 : request.groups().size();
        return ApiResult.ok(taskService.submit(ShoutEditFormHandler.TYPE, request,
                "列表式保存 " + n + " 组"));
    }

    @Operation(summary = "冗余文件分页：被剔除出组的文件（shout_id=0），只读展示")
    @GetMapping("/redundant-files")
    public ApiResult<ShoutStoreService.RedundantPage> redundantFiles(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResult.ok(storeService.listRedundantFiles(page, size,
                importService.redundantDir()));
    }
}
