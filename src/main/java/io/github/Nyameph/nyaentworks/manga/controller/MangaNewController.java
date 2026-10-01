package io.github.Nyameph.nyaentworks.manga.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.github.Nyameph.nyaentworks.common.ApiResult;
import io.github.Nyameph.nyaentworks.manga.config.MangaProperties;
import io.github.Nyameph.nyaentworks.manga.service.MangaCoverService;
import io.github.Nyameph.nyaentworks.manga.service.MangaNewService;
import io.github.Nyameph.nyaentworks.manga.service.MangaStoreService;
import io.github.Nyameph.nyaentworks.manga.task.MangaCollectionStoreHandler;
import io.github.Nyameph.nyaentworks.manga.task.MangaCompressNormalizeHandler;
import io.github.Nyameph.nyaentworks.manga.task.MangaNormalizeBatchHandler;
import io.github.Nyameph.nyaentworks.manga.task.MangaStoreHandler;
import io.github.Nyameph.nyaentworks.manga.util.MangaScoreDir;
import io.github.Nyameph.nyaentworks.task.service.AsyncTaskService;

import java.util.List;
import java.util.concurrent.TimeUnit;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnManga;

/**
 * 新漫画接口（文档 4.8）。
 *
 * <p><b>新漫画不入库，所以这里的「id」是目录全路径。</b>每个写操作都会校验路径确实
 * 落在配置的新漫画根目录下（见 {@code MangaNewService.requireUnder}）——
 * 拿路径当 id 的代价就是每一个入口都得自己防目录穿越。
 *
 * <p>读操作（扫描）是秒级的同步请求；存储要跑 NConvert 压缩，分钟级，
 * 所以返回任务 id 由前端轮询 {@code /api/tasks/{id}}。任务落库，重启后残留的会
 * 标成中断、可在任务页重跑。
 */
@Tag(name = "漫画-新漫画")
@ConditionalOnManga
@RestController
@RequestMapping("/api/manga/new")
@RequiredArgsConstructor
public class MangaNewController {

    private final MangaNewService newService;
    private final MangaStoreService storeService;
    private final MangaCoverService coverService;
    private final AsyncTaskService taskService;
    private final MangaProperties properties;

    @Operation(summary = "扫描新漫画，含闸门判定与归档匹配。只读，不入库")
    @GetMapping("/scan")
    public ApiResult<MangaNewService.ScanResult> scan() {
        return ApiResult.ok(newService.scan());
    }

    @Operation(summary = "四挡评分，页面画评分按钮用")
    @GetMapping("/scores")
    public ApiResult<int[]> scores() {
        return ApiResult.ok(MangaScoreDir.SCORES);
    }

    // ------------------------------------------------------------------
    // 单本操作
    // ------------------------------------------------------------------

    @Operation(summary = "阅读：调系统默认图片查看器打开封面")
    @PostMapping("/read")
    public ApiResult<Void> read(@RequestParam String folderPath) {
        storeService.openReader(folderPath);
        return ApiResult.ok();
    }

    @Operation(summary = "在系统文件资源管理器里打开漫画目录。归档漫画也能开")
    @PostMapping("/open-folder")
    public ApiResult<Void> openFolder(@RequestParam String folderPath) {
        storeService.openFolder(folderPath);
        return ApiResult.ok();
    }

    public record RenameRequest(String folderPath, String newFolderName) {
    }

    @Operation(summary = "改目录名。只在新漫画根目录内改，不跨目录")
    @PutMapping("/folder-name")
    public ApiResult<String> rename(@RequestBody RenameRequest request) {
        return ApiResult.ok(storeService.rename(request.folderPath(), request.newFolderName()));
    }

    @Operation(summary = "改名预演：新目录名是否匹配规则、有无未识别展会/原作。阅读页实时反馈")
    @GetMapping("/check-name")
    public ApiResult<MangaNewService.NameCheck> checkName(@RequestParam String folderPath,
                                                          @RequestParam String newFolderName) {
        return ApiResult.ok(newService.checkName(folderPath, newFolderName));
    }

    @Operation(summary = "删除漫画目录及其文件。新漫画没入库，故不涉及库")
    @DeleteMapping
    public ApiResult<Void> delete(@RequestParam String folderPath) {
        storeService.delete(folderPath);
        return ApiResult.ok();
    }

    public record ScoreRequest(String folderPath, Integer score) {
    }

    @Operation(summary = "评分：把目录移进对应的 #<评分>-… 分区。score 传空表示撤销评分")
    @PutMapping("/score")
    public ApiResult<String> score(@RequestBody ScoreRequest request) {
        return ApiResult.ok(storeService.score(request.folderPath(), request.score()));
    }

    @Operation(summary = "规范化命名：把目录名改成解析器拼出的规范名")
    @PostMapping("/normalize-name")
    public ApiResult<String> normalizeName(@RequestParam String folderPath) {
        return ApiResult.ok(storeService.normalizeName(folderPath));
    }

    public record NormalizeBatchRequest(List<String> folderPaths) {
    }

    @Operation(summary = "批量规范化命名：循环改目录名，一本失败不中断，返回逐项结果。异步，返回任务 id")
    @PostMapping("/normalize-batch")
    public ApiResult<Long> normalizeBatch(@RequestBody NormalizeBatchRequest request) {
        MangaStoreService.NormalizeBatchParams params = new MangaStoreService.NormalizeBatchParams(
                request == null ? null : request.folderPaths());
        int count = request == null || request.folderPaths() == null ? 0 : request.folderPaths().size();
        return ApiResult.ok(taskService.submit(MangaNormalizeBatchHandler.TYPE, params,
                "规范化 " + count + " 本目录名"));
    }

    /**
     * @param items 每本漫画目录及其评分（单本 items 就一个元素，批量每本一个）
     * @param tags  单独标签，整批共用；为空**是否**被拦下见
     *              {@code nya-entworks.manga.require-tags-before-archive}（出厂不拦），
     *              闸门在 {@link MangaStoreService#requireTags}
     */
    public record StoreRequest(List<MangaStoreService.StoreItem> items, List<String> tags) {
    }

    @Operation(summary = "存储：规范化改名 → 压缩 → 归档或落散漫 → 入库。返回任务 id")
    @PostMapping("/store")
    public ApiResult<Long> store(@RequestBody StoreRequest request) {
        MangaStoreService.StoreParams params = storeService.prepareStore(
                request == null ? null : request.items(),
                request == null ? null : request.tags());
        return ApiResult.ok(taskService.submit(MangaStoreHandler.TYPE, params,
                "存储 " + params.items().size() + " 本新漫画"));
    }

    /**
     * @param items 待确认归档的漫画：folderPath 是 prepare 阶段改名+压缩后的目录，
     *              score 与提交存储时同一本选的评分
     * @param tags  与提交存储时同一批单独标签
     */
    public record ConfirmStoreRequest(List<MangaStoreService.StoreItem> items, List<String> tags) {
    }

    @Operation(summary = "二次确认后归档：只做匹配+搬家+落库，不再改名压缩。同步")
    @PostMapping("/store/confirm")
    public ApiResult<MangaStoreService.StoreBatchResult> confirmStore(
            @RequestBody ConfirmStoreRequest request) {
        return ApiResult.ok(storeService.confirmStore(
                request == null ? null : request.items(),
                request == null ? null : request.tags()));
    }

    @Operation(summary = "压缩并规范化改名（合集内单本的「归档」）。不搬、不落库，返回任务 id")
    @PostMapping("/compress-normalize")
    public ApiResult<Long> compressNormalize(@RequestParam String folderPath) {
        MangaStoreService.CompressNormalizeParams params =
                storeService.prepareCompressNormalize(folderPath);
        return ApiResult.ok(taskService.submit(MangaCompressNormalizeHandler.TYPE, params,
                "压缩 " + java.nio.file.Paths.get(folderPath).getFileName()));
    }

    // ------------------------------------------------------------------
    // 合集
    // ------------------------------------------------------------------

    /**
     * 合集列表 + 页面要一起知道的那一件事：<b>归档前是否强制打标签</b>。
     *
     * <p>为什么在这一层包一个对象、而不把标志塞进每个 {@code MangaCollection} 里：
     * 它是<b>一份配置</b>，不是某一行的属性 —— 每行带一份会在 JSON 里复制 20 遍，
     * 也会让人以为「这个合集要打标签、那个不用」。原先前端把「不打标签不能归档」
     * 写死在自己的代码里，于是这一项一变成配置，前端就成了唯一还在拦的地方
     * （后端已经放行，页面还拦着）—— 这正是「前端只画不判」要避免的形状。
     *
     * @param collections 合集列表，与从前一样
     * @param requireTagsBeforeArchive 现在生效的口径，认定仍在后端
     *        （{@code MangaStoreService#requireTags}），这里只是告诉页面怎么画提示
     */
    public record CollectionScan(List<MangaNewService.MangaCollection> collections,
                                 boolean requireTagsBeforeArchive) {
    }

    @Operation(summary = "扫描新作者合集")
    @GetMapping("/collections")
    public ApiResult<CollectionScan> collections() {
        return ApiResult.ok(new CollectionScan(newService.scanCollections(),
                properties.isRequireTagsBeforeArchive()));
    }

    @Operation(summary = "单个合集及其下的漫画")
    @GetMapping("/collection")
    public ApiResult<MangaNewService.MangaCollection> collection(@RequestParam String folderPath) {
        return ApiResult.ok(newService.collection(folderPath));
    }

    /**
     * @param score 整体评分，不视为对单本评分（落库时 score_source = INHERIT_ARCHIVE）
     * @param tags  整体标签，会写进合集目录名的 【…】 块；留空允不允许见
     *              {@code nya-entworks.manga.require-tags-before-archive}
     */
    public record CollectionStoreRequest(String folderPath, Integer score, List<String> tags) {
    }

    @Operation(summary = "存储整个合集：逐本规范化 → 逐本压缩 → 整体搬进归档根 → 同步 → 入库")
    @PostMapping("/collection/store")
    public ApiResult<Long> storeCollection(@RequestBody CollectionStoreRequest request) {
        MangaStoreService.CollectionStoreParams params = storeService.prepareStoreCollection(
                request.folderPath(), request.score(), request.tags());
        return ApiResult.ok(taskService.submit(MangaCollectionStoreHandler.TYPE, params,
                "存储合集 " + java.nio.file.Paths.get(request.folderPath())
                        .getFileName()));
    }

    @Operation(summary = "二次确认后迁移合集：整体搬进归档根 → 同步 → 入库。同步")
    @PostMapping("/collection/store/confirm")
    public ApiResult<MangaStoreService.CollectionStoreResult> confirmStoreCollection(
            @RequestBody CollectionStoreRequest request) {
        return ApiResult.ok(storeService.confirmStoreCollection(
                request.folderPath(), request.score(), request.tags()));
    }

    // ------------------------------------------------------------------
    // 封面
    // ------------------------------------------------------------------

    /**
     * 阅读页正文图片列表。
     * <p>返回<b>绝对本地路径</b>。原先是前端拼 {@code file:///} 直接当 {@code <img>} 源
     * （用户要求「直接使用本地路径」），但浏览器拦 http 页里的 file:// 资源，
     * 现在改为逐张走 {@link #image} 字节流 —— 这里仍返回绝对路径，是为了让前端能
     * 从目录路径里切出相对路径（见 {@link MangaCoverService#listImages}）。
     */
    @Operation(summary = "阅读页正文图片：绝对路径列表，前端逐张请求字节端点")
    @GetMapping("/images")
    public ApiResult<List<String>> images(@RequestParam String folderPath) {
        return ApiResult.ok(coverService.listImages(folderPath));
    }

    /**
     * 阅读页正文图片字节流。不走 {@code ApiResult} —— 二进制流，让 {@code <img src>} 直接吃。
     * 文件不存在时返回 404 而不是空 200。
     */
    @Operation(summary = "阅读页正文图片字节流。fileName 相对目录（可含子目录分卷）")
    @GetMapping("/image")
    public ResponseEntity<byte[]> image(@RequestParam String folderPath,
                                        @RequestParam String fileName) {
        byte[] bytes = coverService.imageFile(folderPath, fileName);
        if (bytes == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(imageMediaType(fileName)))
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES))
                .body(bytes);
    }

    /** 与 {@link MangaCompressService#IMAGE_EXTENSIONS} 对齐；走到这里必然是图片，default 兜底 */
    private static String imageMediaType(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String ext = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
        return switch (ext) {
            case "jpg", "jpeg", "jpe", "jfif" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "webp" -> "image/webp";
            case "tif", "tiff" -> "image/tiff";
            default -> "application/octet-stream";
        };
    }

    /**
     * 封面缩略图。
     * <p>不走 {@code ApiResult} —— 这是二进制流，要让 {@code <img src>} 直接吃。
     * 没有封面时返回 404 而不是空 200，好让浏览器显示 alt 而不是一张碎图。
     */
    @Operation(summary = "封面缩略图（320px 宽 jpg，带缓存）")
    @GetMapping("/cover")
    public ResponseEntity<byte[]> cover(@RequestParam String folderPath) {
        byte[] thumb = coverService.thumbnail(folderPath);
        if (thumb == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                // 缓存键含源文件的大小与修改时间，内容变了 URL 不变 ——
                // 所以只让浏览器缓存一小会儿，改过名或压缩过能较快看到新图
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES))
                .body(thumb);
    }

    @Operation(summary = "清空缩略图缓存，返回删掉的文件数")
    @PostMapping("/cover/clear-cache")
    public ApiResult<Integer> clearCoverCache() {
        return ApiResult.ok(coverService.clearCache());
    }
}
