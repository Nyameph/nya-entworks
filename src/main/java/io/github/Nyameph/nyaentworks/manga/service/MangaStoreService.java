package io.github.Nyameph.nyaentworks.manga.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.github.Nyameph.nyaentworks.manga.config.MangaProperties;
import io.github.Nyameph.nyaentworks.manga.consts.MangaDataFileType;
import io.github.Nyameph.nyaentworks.manga.consts.MangaDataStatus;
import io.github.Nyameph.nyaentworks.manga.consts.MangaScoreSource;
import io.github.Nyameph.nyaentworks.manga.consts.MangaTagTargetType;
import io.github.Nyameph.nyaentworks.manga.entity.MangaData;
import io.github.Nyameph.nyaentworks.manga.entity.MangaEhScan;
import io.github.Nyameph.nyaentworks.manga.mapper.MangaDataMapper;
import io.github.Nyameph.nyaentworks.manga.service.MangaArchiveService.ArchiveMatch;
import io.github.Nyameph.nyaentworks.manga.service.MangaCompressService.CompressResult;
import io.github.Nyameph.nyaentworks.manga.service.MangaNewService.MangaCollection;
import io.github.Nyameph.nyaentworks.manga.service.MangaNewService.NewManga;
import io.github.Nyameph.nyaentworks.manga.util.MangaCbzUtil;
import io.github.Nyameph.nyaentworks.manga.util.MangaFolderName;
import io.github.Nyameph.nyaentworks.manga.util.MangaNameParser;
import io.github.Nyameph.nyaentworks.manga.util.MangaScoreDir;
import io.github.Nyameph.nyaentworks.common.file.RecycleBin;
import io.github.Nyameph.nyaentworks.common.fileop.FileOpRecorder;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpLevel;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpModule;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpSource;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpType;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskContext;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 新漫画的操作与存储（文档 4.8、概述「下载新漫画」/「下载新作者合集」）。
 *
 * <p>存储是这一页唯一不可逆的动作，顺序固定为：
 * <ol>
 *     <li><b>过闸门</b> —— 评分 + 规范化命名（合集另加「无新展会/原作」）。
 *         判据由 {@link MangaNewService} 现扫得出，不信前端传来的；</li>
 *     <li><b>规范化改名</b> —— 先把目录名改成规范名；</li>
 *     <li><b>压缩</b> —— NConvert 删原文件，从这一步起不可回滚。部分文件失败时
 *         不归档，记入待确认清单，前端二次确认后才提交（{@link #confirmStore}）。
 *         <b>没配 NConvert 时整步跳过</b>、原图原样往下走（压缩是优化不是前提，
 *         见 {@code MangaCompressService#compressFolder}）；</li>
 *     <li><b>搬去归档目录或散漫目录</b>；</li>
 *     <li><b>打包成 cbz</b> —— 压缩图片之后，把目录就地打包成同名 {@code .cbz}；
 *         失败降级为目录归档、不阻断（见 {@link #packToCbz}）；</li>
 *     <li><b>落库</b> —— 到这一刻漫画才第一次入库，状态为 ARCHIVED / UNARCHIVED。</li>
 * </ol>
 *
 * <p>磁盘先动、库后动，与 {@link MangaArchiveService#applyEdit} 同一套理由：
 * 库先写完而磁盘失败的话，库里就指着一个不存在的路径。反过来磁盘成功、
 * 库写失败时事务回滚，重扫一次就能按路径把它认回来。
 */
@Service
@RequiredArgsConstructor
public class MangaStoreService {

    private static final Logger log = LoggerFactory.getLogger(MangaStoreService.class);

    /** 合集压缩断点标记文件名，放在合集根目录里。存储成功（整体搬迁）后删除 */
    static final String STORE_MARKER_FILE = ".nya-entworks-store.json";

    /**
     * 标记文件的序列化器。自建本地实例而不注入 Spring 的 bean：Boot 4 默认 JSON 是
     * Jackson 3（tools.jackson.databind），容器里没有 com.fasterxml（Jackson 2）的
     * ObjectMapper bean；这里要的是独立于 HTTP 层的简单序列化，new 一个即可。
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final MangaProperties properties;
    private final MangaNewService newService;
    private final MangaCompressService compressService;
    private final MangaArchiveService archiveService;
    private final MangaDataWriter dataWriter;
    private final MangaEhScanService ehScanService;
    private final MangaPackService packService;

    // ------------------------------------------------------------------
    // 单本操作：阅读 / 改名 / 删除 / 评分 / 规范化命名
    // ------------------------------------------------------------------

    /**
     * 用系统默认图片查看器打开封面所在目录。
     * <p>按概述的结论不引入新依赖，但接口留成「按漫画打开」的形态，
     * 将来换成页面内阅读器时调用方不动。
     */
    public void openReader(String folderPath) {
        Path dir = requireEditable(folderPath);
        // cbz：封面在 zip 里，没法按路径 resolve 出来，直接用系统默认程序打开 cbz 文件本身
        String cover = MangaCbzUtil.isCbz(dir) ? null : MangaNameParser.getFirstImage(dir);
        File target = cover == null ? dir.toFile() : dir.resolve(cover).toFile();
        if (!Desktop.isDesktopSupported()) {
            throw new IllegalStateException("这台机器上打不开系统查看器（Desktop 不支持）");
        }
        try {
            Desktop.getDesktop().open(target);
        } catch (IOException e) {
            throw new IllegalStateException("打开失败：" + e.getMessage(), e);
        }
    }

    /**
     * 在系统文件资源管理器里打开漫画目录（阅读页「打开目录」按钮）。
     * <p>与 {@link #openReader} 不同，这里打开的是目录本身而非封面，且归档漫画也能开
     * —— 阅读页对四类目录都提供这个按钮，所以路径校验用更宽的 {@link #requireOpenedDir}。
     * <p>不走 AWT 的 {@link Desktop}：它在 headless 环境（远程/服务会话）下会误报
     * {@code isDesktopSupported() == false}。这是 Windows 单机，直接用 explorer.exe 最稳，
     * 也正好是「调用系统的文件资源管理器」的字面意思。
     */
    public void openFolder(String folderPath) {
        Path dir = requireOpenedDir(folderPath);
        // cbz 是文件：在资源管理器里定位并选中它（/select），而不是当目录打开
        if (MangaCbzUtil.isCbz(dir)) {
            try {
                new ProcessBuilder("explorer.exe", "/select,", dir.toString()).start();
            } catch (IOException e) {
                throw new IllegalStateException("打开目录失败：" + e.getMessage(), e);
            }
            return;
        }
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("目录不存在：" + dir);
        }
        try {
            new ProcessBuilder("explorer.exe", dir.toString()).start();
        } catch (IOException e) {
            throw new IllegalStateException("打开目录失败：" + e.getMessage(), e);
        }
    }

    /**
     * 改目录名。只在原地改，不跨目录。新漫画、合集里的单本与未归档漫画都走这里。
     * <p>未归档已入库，改名后 {@code manga_data.folder_path} 要跟上 —— 扫描按路径认行，
     * 不同步的话下次重扫会把旧路径判成失踪、新路径当成新入库。
     */
    public String rename(String folderPath, String newFolderName) {
        return FileOpRecorder.batch(FileOpModule.MANGA, FileOpSource.PAGE, null, () -> {
            Path dir = requireEditable(folderPath);
            String name = requireSimpleName(newFolderName);
            // cbz 是文件：改名必须保留 .cbz 后缀，否则改完就不再被识别成漫画单元。
            // 规范化命名传进来的名字已带后缀（见 MangaNameParser.parseDir），手工改名可能漏
            if (MangaCbzUtil.isCbz(dir) && !StringUtils.endsWithIgnoreCase(name, ".cbz")) {
                name = name + ".cbz";
            }
            Path target = dir.resolveSibling(name);
            if (target.equals(dir)) {
                return dir.toString();
            }
            if (Files.exists(target)) {
                throw new IllegalStateException("目标目录已存在：" + target);
            }
            // 判据必须在**搬动之前**取：搬完原路径已经不存在，Files.isDirectory 恒为 false
            // （2026-09-23 修 —— 原先写在 move 之后，这三处记录点一直是死代码）
            boolean isDir = Files.isDirectory(dir);
            try {
                Files.move(dir, target);
            } catch (IOException e) {
                throw new IllegalStateException("改名失败：" + e.getMessage(), e);
            }
            // 目录级动作才记：cbz 按路径本身判（操作前的原路径），不按方法名判
            if (isDir) {
                FileOpRecorder.recordPath(FileOpModule.MANGA, FileOpType.MOVE,
                        FileOpLevel.SINGLE, "单本改名", dir.toString(), target.toString());
            }
            if (underUnarchivedDir(dir)) {
                dataWriter.updateFolderPath(dir.toString(), target.toString());
            }
            return target.toString();
        });
    }

    /**
     * 删除一个漫画目录，连同里面的文件。<b>送进回收站</b>（2026-09-30 起；原先是真的删掉），
     * 删错了可以去资源管理器里右键「还原」。
     * <p>新漫画根与合集根下的漫画没入库，删完即可；未归档的已入库，删完顺带清掉对应行。
     */
    public void delete(String folderPath) {
        FileOpRecorder.batch(FileOpModule.MANGA, FileOpSource.PAGE, null, () -> {
            Path dir = requireEditable(folderPath);
            // 判据在删之前取（同 rename：删完原路径就没了，Files.isDirectory 已经是 false）
            boolean isDir = Files.isDirectory(dir);
            RecycleBin.recycle(dir);
            if (isDir) {
                // cbz 是文件，不算目录级动作（同 rename 的判据）
                FileOpRecorder.recordPath(FileOpModule.MANGA, FileOpType.DELETE,
                        FileOpLevel.SINGLE, "删单本（进回收站）", dir.toString(), null);
            }
            if (underUnarchivedDir(dir)) {
                dataWriter.deleteByFolderPath(dir.toString());
            }
            return null;
        });
    }

    /**
     * 打分：把漫画移进对应的评分分区目录。
     * <p>评分存在目录名里（见 {@link MangaScoreDir}），所以「打分」这个动作
     * 落到磁盘上就是一次移动。已经在目标分区里的直接返回。
     * <p>新漫画与未归档漫画都能打（分区根分别是 {@code #待看} 与 {@code #待整理散漫}）；
     * 未归档已入库，移动后评分与路径都要同步到行上。
     *
     * @param score {@code null} 表示撤销评分，移回根目录
     */
    public String score(String folderPath, Integer score) {
        return FileOpRecorder.batch(FileOpModule.MANGA, FileOpSource.PAGE, null, () -> {
            Path dir = requireEditable(folderPath);
            boolean unarchived = underUnarchivedDir(dir);
            String root = unarchived ? properties.getUnarchivedDir() : properties.getNewDir();
            // 这一档分区没建时 resolveScoreDir 给一个裸 `#9-`（不建目录），
            // 落盘时 createDirectories 建出来；根目录不在才抛
            Path targetParent = score == null
                    ? Paths.get(root)
                    : MangaScoreDir.resolveScoreDir(root, requireValidScore(score));
            Path target = targetParent.resolve(dir.getFileName());
            if (target.equals(dir)) {
                return dir.toString();
            }
            if (Files.exists(target)) {
                throw new IllegalStateException("目标目录已存在：" + target);
            }
            // 判据在搬之前取（同 rename/delete）
            boolean isDir = Files.isDirectory(dir);
            try {
                // score == null（撤销评分）时 targetParent 就是已存在的根，这一句是 no-op
                Files.createDirectories(targetParent);
                Files.move(dir, target);
            } catch (IOException e) {
                throw new IllegalStateException("移动到评分目录失败：" + e.getMessage(), e);
            }
            if (isDir) {
                // cbz 是文件，不算目录级动作
                FileOpRecorder.recordPath(FileOpModule.MANGA, FileOpType.MOVE,
                        FileOpLevel.SINGLE, "单本改评分（换分区）", dir.toString(), target.toString());
            }
            if (unarchived) {
                dataWriter.updateScoreAndPath(dir.toString(), score, target.toString());
            }
            return target.toString();
        });
    }

    /**
     * 规范化命名：把目录名改成解析器拼出来的规范名。
     * <p>等价于 {@code MangaNameParserTestUtil.moveManga} 里 {@code justTest = false}
     * 的那一步，两种失败照原样如实报出而不静默跳过：目标已存在、IO 异常。
     */
    public String normalizeName(String folderPath) {
        Path dir = requireEditable(folderPath);
        NewManga manga = requireScanned(folderPath);
        if (manga.matchedRule() == 0 || StringUtils.isBlank(manga.normalizedName())) {
            throw new IllegalStateException("目录名不规范，拼不出规范化名称："
                    + StringUtils.defaultString(manga.irregularReason()));
        }
        if (!manga.needsRename()) {
            return dir.toString();
        }
        return rename(folderPath, manga.normalizedName());
    }

    /**
     * 一本批量规范化命名的结果。
     *
     * @param fromName 原目录名；失败时也可能为 {@code null}（路径非法取不出文件名）
     * @param toName   改名后的目录名；失败或本来就不用改时为 {@code null}
     * @param error    非空表示这一本没改成，原因直接给人看
     */
    public record NormalizeResult(String folderPath, String fromName, String toName, String error) {
    }

    /** 一次批量规范化命名的汇总 */
    public record NormalizeBatchResult(int total, int succeeded, List<NormalizeResult> results) {
    }

    /** 批量规范化命名的任务参数（异步重跑靠它还原被选中的目录清单） */
    public record NormalizeBatchParams(List<String> folderPaths) {
    }

    /**
     * 批量规范化命名：循环 {@link #normalizeName}，一本失败不中断整批。
     * <p>秒级操作，同步返回。三种结果都如实记下：改成了（{@code toName} 非空且不同于
     * {@code fromName}）、本来就不用改（{@code toName == fromName}）、失败（{@code error}
     * 非空）。判据仍全在 {@link #normalizeName} 里，这里只包一层循环 + 收集结果。
     */
    public NormalizeBatchResult normalizeBatch(List<String> folderPaths) {
        if (folderPaths == null || folderPaths.isEmpty()) {
            throw new IllegalArgumentException("没有选中要规范化的漫画");
        }
        List<NormalizeResult> results = new ArrayList<>();
        int succeeded = 0;
        for (String path : folderPaths) {
            String fromName = fileNameOf(path);
            try {
                String to = normalizeName(path);
                results.add(new NormalizeResult(path, fromName, fileNameOf(to), null));
                succeeded++;
            } catch (Exception e) {
                results.add(new NormalizeResult(path, fromName, null, e.getMessage()));
            }
        }
        return new NormalizeBatchResult(folderPaths.size(), succeeded, results);
    }

    /** 路径的末级目录名；路径非法时返回 {@code null}，不因此中断整批 */
    private static String fileNameOf(String path) {
        if (StringUtils.isBlank(path)) {
            return null;
        }
        try {
            Path name = Paths.get(path).getFileName();
            return name == null ? null : name.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 存储
    // ------------------------------------------------------------------

    /**
     * 一本待存储的漫画及其评分。评分随归档请求传来（打分不再移动目录，
     * 所以不再从扫描出的目录分区读），单本与批量每本各带一个分。
     *
     * @param folderPath 目录全路径。二次确认（{@link #confirmStore}）时是
     *                   prepare 阶段改名+压缩后的目录路径
     */
    public record StoreItem(String folderPath, Integer score) {
    }

    /**
     * 一次存储的结果。
     *
     * @param archived 归档了（进 {@code F:\MangaGroup}）还是落进散漫目录
     */
    public record StoreResult(String fromFolderPath, String toFolderPath, boolean archived,
                              Integer score, Long archiveUnitId, Long mangaId,
                              Boolean tagPullFailed) {
    }

    /**
     * 一本压缩有文件失败、待二次确认的漫画。
     *
     * @param preparedPath 改名+压缩后的目录，确认后由 {@link #commitOne} 从这里归档
     */
    public record PendingArchive(String originalPath, String preparedPath,
                                 String folderName, CompressResult compress, Integer score) {

        public int succeeded() {
            return compress.succeededCount();
        }

        public int total() {
            return compress.filesBefore();
        }
    }

    /** 一次存储任务的结果：归档成功的明细 + 压缩失败待确认的漫画 */
    public record StoreBatchResult(List<StoreResult> results,
                                   List<PendingArchive> pendingConfirmations) {
    }

    /**
     * 存储任务的参数。会被序列化进 {@code async_task.params_json}，
     * 「重新执行」时原样反序列化回来 —— 所以只放能落 JSON 的原始请求值，
     * 不放扫描出来的 {@link NewManga}（那是磁盘现状的快照，重跑时必须重新扫）。
     */
    public record StoreParams(List<StoreItem> items, List<String> tags) {
    }

    /**
     * 校验一次存储请求，产出任务参数。<b>失败当场抛</b>，由调用方在请求线程里拒绝。
     * <p>闸门要在提交时先过一遍，不能存的当场告诉人，而不是让人等任务跑完才看到。
     * 真正执行时 {@link #runStore} 还会再校验一遍（排队期间磁盘可能变了）。
     *
     * @param items 要存储的漫画目录及其评分，逐个处理；一本失败不影响后面的
     * @param tags  单独标签，整批共用。留空则归档落库后从 eh 拉取（并集），拉不到再让人手动补
     */
    public StoreParams prepareStore(List<StoreItem> items, List<String> tags) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("没有选中要存储的漫画");
        }
        List<String> tagNames = cleanTags(tags);
        // 评分从请求参数取（打分不再移动目录），不再看扫描出的目录分区评分
        for (StoreItem item : items) {
            // 存储只受理 #待看 下的：合集是整体存储（整个目录搬进归档根），
            // 逐本存会把合集拆散，那不是需求要的
            requireNewManga(item.folderPath());
            checkStorable(requireScanned(item.folderPath()));
            requireValidScore(item.score());
        }
        return new StoreParams(List.copyOf(items), tagNames);
    }

    /**
     * 执行存储：逐本「规范化改名 → 压缩 → 归档或落散漫 → 入库」。
     * <p>跑在后台任务线程上，进度与明细走 {@code context}。
     * <p><b>每本都重新扫一次</b>而不是用提交时的快照：任务在队列里等的这段时间磁盘可能变了，
     * 重跑时更是隔了很久 —— 拿旧快照去动磁盘就是在赌它没变。
     */
    public StoreBatchResult runStore(StoreParams params, AsyncTaskContext context) {
        // 批次入口包在整个流程外：一次存储任务（哪怕 N 本）共用一个批次号
        return FileOpRecorder.batch(FileOpModule.MANGA, FileOpSource.TASK, context.taskId(),
                () -> runStoreInside(params, context));
    }

    private StoreBatchResult runStoreInside(StoreParams params, AsyncTaskContext context) {
        List<StoreItem> items = new ArrayList<>(params.items());
        List<String> tagNames = cleanTags(params.tags());
        List<StoreResult> results = new ArrayList<>();
        List<PendingArchive> pending = new ArrayList<>();
        int failed = 0;
        for (int i = 0; i < items.size(); i++) {
            StoreItem item = items.get(i);
            int index = i + 1;
            context.progress(i, items.size(),
                    "(" + index + "/" + items.size() + ") " + item.folderPath());
            try {
                requireNewManga(item.folderPath());
                NewManga manga = requireScanned(item.folderPath());
                int score = requireValidScore(item.score());
                checkStorable(manga);
                // 先规范化改名，改完立即把新路径写回参数 —— 后面压缩/搬家要是断了，
                // 重跑时按新路径还能在 #待看 里找到它，而不是拿改名前的老路径撞「不存在」
                context.message("规范化命名 " + manga.folderName());
                Path renamed = renameForStore(manga);
                if (!renamed.toString().equals(item.folderPath())) {
                    items.set(i, new StoreItem(renamed.toString(), score));
                    context.updateParams(new StoreParams(List.copyOf(items), tagNames));
                }
                PreparedManga prepared = prepareOne(renamed, score, context);
                if (prepared.compress().hasFailures()) {
                    // 部分文件压缩失败：跳过归档，等前端二次确认后再提交
                    PendingArchive pendingItem = new PendingArchive(manga.folderPath(),
                            prepared.renamed().toString(),
                            prepared.renamed().getFileName().toString(),
                            prepared.compress(), score);
                    pending.add(pendingItem);
                    context.log("⚠ 待确认：" + pendingItem.folderName() + " —— "
                            + pendingItem.succeeded() + "/" + pendingItem.total()
                            + " 个文件压缩成功，"
                            + pendingItem.compress().failures().size() + " 个失败");
                    continue;
                }
                StoreResult result = commitOne(prepared.manga(), prepared.match(),
                        prepared.renamed(), score, tagNames);
                results.add(result);
                context.log((result.archived() ? "已归档：" : "落散漫：")
                        + result.toFolderPath());
            } catch (Exception e) {
                // 一本失败不中断整批，如实记下来
                failed++;
                context.log("😭 失败：" + item.folderPath() + " —— " + e.getMessage());
                log.warn("存储失败 {}", item.folderPath(), e);
            }
        }
        // 全都失败时抛出去，让任务标成 FAILED 而不是「完成」—— 详情已在上面逐条记进日志。
        // 只要有一本成了，任务就算 DONE（失败的那几本在日志里翻）
        if (failed > 0 && failed == items.size()) {
            throw new IllegalStateException("全部 " + items.size()
                    + " 本都失败了（目录可能已被移走或改名，详情见日志）");
        }
        context.progress(items.size(), items.size(), "完成");
        return new StoreBatchResult(results, pending);
    }

    /**
     * 压缩并规范化命名（合集内单本阅读页的「归档」）。
     * <p>合集是整体存储、整体搬进归档根，所以合集内的单本<b>不搬、不落库</b> ——
     * 这个动作只把这一本压缩掉原图、把目录名改成规范名，为整体存储做好准备。
     */
    /** 压缩并规范化的任务参数 */
    public record CompressNormalizeParams(String folderPath) {
    }

    /** 校验「压缩并规范化」请求，产出任务参数。失败当场抛 */
    public CompressNormalizeParams prepareCompressNormalize(String folderPath) {
        checkCompressNormalizable(folderPath);
        return new CompressNormalizeParams(folderPath);
    }

    /** 提交时与执行时都要过的那道闸门 */
    private NewManga checkCompressNormalizable(String folderPath) {
        Path dir = requireEditable(folderPath);
        if (!underCollectionDir(dir)) {
            throw new IllegalArgumentException("只有合集里的漫画才有「压缩并规范化」："
                    + "单本请在阅读页用「归档」做完整存储");
        }
        NewManga manga = requireScanned(folderPath);
        if (manga.matchedRule() == 0 || StringUtils.isBlank(manga.normalizedName())) {
            throw new IllegalStateException("目录名不规范，拼不出规范化名称："
                    + StringUtils.defaultString(manga.irregularReason()));
        }
        return manga;
    }

    /** 执行「压缩并规范化」。跑在后台任务线程上 */
    public CompressNormalizeResult runCompressNormalize(CompressNormalizeParams params,
                                                        AsyncTaskContext context) {
        return FileOpRecorder.batch(FileOpModule.MANGA, FileOpSource.TASK, context.taskId(),
                () -> runCompressNormalizeInside(params, context));
    }

    private CompressNormalizeResult runCompressNormalizeInside(CompressNormalizeParams params,
                                                               AsyncTaskContext context) {
        String folderPath = params.folderPath();
        // 重新校验：排队/重跑期间目录可能已经被改名或搬走了
        NewManga manga = checkCompressNormalizable(folderPath);
        Path dir = Paths.get(folderPath);

        context.message("压缩 " + manga.folderName());
        CompressResult compress = compressService.compressFolder(dir,
                (done, total, file) -> context.message(
                        "压缩 " + manga.folderName() + " (" + done + "/" + total + ") " + file));
        if (compress.allFailed()) {
            throw new IllegalStateException("整本 " + compress.filesBefore()
                    + " 个文件全部压缩失败，先看看是不是 NConvert 出了问题");
        }
        logCompressNotes(context, compress);
        Path finalDir = dir;
        if (manga.needsRename()) {
            Path target = dir.resolveSibling(manga.normalizedName());
            if (Files.exists(target)) {
                throw new IllegalStateException("规范化改名时目标目录已存在：" + target);
            }
            try {
                Files.move(dir, target);
                FileOpRecorder.recordPath(FileOpModule.MANGA, FileOpType.MOVE,
                        FileOpLevel.SINGLE, "压缩并规范化：改名", dir.toString(), target.toString());
                finalDir = target;
            } catch (IOException e) {
                throw new IllegalStateException("规范化改名失败：" + e.getMessage(), e);
            }
        }
        context.progress(1, 1, "完成");
        context.log("压缩并规范化完成：" + finalDir);
        return new CompressNormalizeResult(folderPath, finalDir.toString(), compress);
    }

    /** 压缩并规范化的结果 */
    public record CompressNormalizeResult(String fromFolderPath, String toFolderPath,
                                          CompressResult compress) {
    }

    /**
     * prepare 阶段的一本结果：改名+压缩后的目录、压缩明细，以及压缩前算好的归档匹配。
     */
    private record PreparedManga(NewManga manga, Path renamed, CompressResult compress,
                                 ArchiveMatch match) {
    }

    /**
     * 规范化改名，返回改名后的目录（无需改名则原样返回）。
     * <p>从 {@link #prepareOne} 里拆出来单独一步：目录名一变，任务参数里的老路径就失效了，
     * 所以这一步必须在「压缩 / 搬家这些耗时且可能中断的活」之前，紧跟着把新路径写回参数
     * （见 {@link #runStore} 里的 {@code context.updateParams}）。
     */
    private Path renameForStore(NewManga manga) {
        Path dir = Paths.get(manga.folderPath());
        if (!manga.needsRename()) {
            return dir;
        }
        Path target = dir.resolveSibling(manga.normalizedName());
        if (Files.exists(target)) {
            throw new IllegalStateException("规范化改名时目标目录已存在：" + target);
        }
        try {
            Files.move(dir, target);
            FileOpRecorder.recordPath(FileOpModule.MANGA, FileOpType.MOVE,
                    FileOpLevel.SINGLE, "存储：规范化改名", dir.toString(), target.toString());
            return target;
        } catch (IOException e) {
            throw new IllegalStateException("规范化改名失败：" + e.getMessage(), e);
        }
    }

    /**
     * 冲突判断 + 压缩（改名已由 {@link #renameForStore} 在 {@link #runStore} 里先做了）。
     * <p>顺序是冲突判断 → 压缩（需求定的：压缩耗时长，冲突判断要放在压缩之前，
     * 免得压缩完才发现「多个映射目录」要人介入，白跑几分钟）。
     * <p>闸门在这里重新算一遍（而不是信提交时那次）：任务排队期间磁盘可能变了，
     * 而这一步之后就不可逆了。
     * <p>整本压缩失败照旧抛异常（那是 NConvert 出了问题，不是几个坏文件），
     * 部分失败不抛，交由 {@link #submitStore} 记入待确认清单。
     */
    private PreparedManga prepareOne(Path dir, int score, AsyncTaskContext context) {
        // 归档匹配提前到压缩前（见类注释的顺序）。改过名所以重扫一次，
        // 拿到改名后的漫画与它的归档匹配
        ResolvedMatch resolved = resolveMatch(dir, score);

        context.message("压缩 " + resolved.manga().folderName());
        CompressResult compress = compressService.compressFolder(dir,
                (done, total, file) -> context.message(
                        "压缩 " + resolved.manga().folderName()
                                + " (" + done + "/" + total + ") " + file));
        if (compress.allFailed()) {
            throw new IllegalStateException("整本 " + compress.filesBefore()
                    + " 个文件全部压缩失败，先看看是不是 NConvert 出了问题");
        }
        logCompressNotes(context, compress);
        return new PreparedManga(resolved.manga(), dir, compress, resolved.match());
    }

    /**
     * 归档一本已改名+压缩好的漫画：搬家 → 落库。
     * <p>改名、冲突判断与压缩都不在这里做 —— 那是 {@link #prepareOne} 的活。
     * 这里只做磁盘就绪之后的「搬 + 落库」，秒级。归档匹配在压缩前已算好，
     * 直接复用，不再重扫。
     */
    private StoreResult commitOne(NewManga manga, ArchiveMatch match, Path preparedDir,
                                  int score, List<String> tags) {
        // 搬到归档目录，或按评分落进散漫目录
        boolean archived = match.archivable();
        // 散漫那一档没建时 resolveScoreDir 给裸 `#9-`（不建目录），这里建出来；
        // 归档分支的目标目录本就存在，这一句是 no-op
        Path targetParent = archived
                ? Paths.get(match.target().folderPath())
                : MangaScoreDir.resolveScoreDir(properties.getUnarchivedDir(), score);
        try {
            Files.createDirectories(targetParent);
        } catch (IOException e) {
            throw new IllegalStateException("建评分分区目录失败：" + targetParent
                    + "（" + e.getMessage() + "）", e);
        }
        Path finalPath = move(preparedDir, targetParent);
        // 目录级动作：一本存储 = 一条 SINGLE（packToCbz 是形态转换，刻意不记）
        FileOpRecorder.recordPath(FileOpModule.MANGA, FileOpType.MOVE, FileOpLevel.SINGLE,
                archived ? "存储：归档" : "存储：落散漫",
                preparedDir.toString(), finalPath.toString());

        // 归档流程新增：压缩图片之后打包成 cbz（失败降级为目录归档，见 packToCbz）
        finalPath = packToCbz(finalPath);

        // 落库。到这一刻才第一次入库
        MangaData saved = saveMangaData(manga, finalPath, score,
                archived ? match.target().unitId() : null,
                archived ? MangaDataStatus.ARCHIVED : MangaDataStatus.UNARCHIVED,
                MangaScoreSource.SELF, tags);

        // 落库后从 eh 拉取标签并并集应用（有标签也拉、取并集）。拉不到且用户也没填标签时
        // 标记 tagPullFailed，前端据此弹窗让用户手动补，不阻断归档本身
        boolean tagPullFailed = pullTags(saved, tags);

        return new StoreResult(manga.folderPath(), finalPath.toString(), archived, score,
                saved.getArchiveUnitId(), saved.getId(), tagPullFailed);
    }

    /**
     * 归档流程的「打包成 cbz」一步：压缩图片之后、落库之前调用，把目录打包成同名 {@code .cbz}。
     * <p><b>失败降级为目录归档，不阻断</b>：写 zip 失败（目录完整）返回原目录；删源目录失败
     * （cbz 已完整生成）返回 cbz 路径、留目录残留让人手动删。两种情况都只记日志，
     * 落库路径随之是 cbz 还是目录，由 {@code MangaDataWriter} 落成对应 {@code file_type}。
     */
    private Path packToCbz(Path dir) {
        Path cbz = dir.resolveSibling(dir.getFileName().toString() + ".cbz");
        try {
            packService.packDir(dir);
            return cbz;
        } catch (Exception e) {
            if (Files.exists(cbz)) {
                // cbz 已生成（删源目录失败）：cbz 完整，当作打包成功，目录残留报错让人收尾
                log.warn("打包 cbz 完成但删源目录失败，残留目录需手动删除：{} —— {}", dir, e.getMessage());
                return cbz;
            }
            log.warn("打包 cbz 失败，降级为目录归档 {}：{}", dir, e.getMessage());
            return dir;
        }
    }

    /**
     * 归档落库后从 eh 拉取标签并并集应用（{@code autoApply=true} 走并集）。只判断是否
     * 需要用户手动补：拉不到（状态非 SUCCESS）且用户也没填标签时返回 true。
     * 拉取失败不抛异常、不阻断归档 —— 漫画已经落库，标签为空即继承归档目录标签。
     */
    private boolean pullTags(MangaData saved, List<String> tags) {
        try {
            MangaEhScanService.ScanResult r = ehScanService.scan(saved, true, null);
            return !MangaEhScan.STATUS_SUCCESS.equals(r.status())
                    && (tags == null || tags.isEmpty());
        } catch (Exception e) {
            log.warn("从 eh 拉取标签失败 {}: {}", saved.getFolderPath(), e.getMessage());
            return tags == null || tags.isEmpty();
        }
    }

    /**
     * 重扫目录并做归档匹配。
     * <p>单本存储的「重扫 + 闸门 + 匹配」都走这里，保证压缩前
     * （{@link #prepareOne}）与二次确认（{@link #confirmStore}）用同一套判据。
     */
    private ResolvedMatch resolveMatch(Path dir, int score) {
        NewManga manga = requireScanned(dir.toString());
        checkStorable(manga);
        ArchiveMatch match = archiveService.matchArchive(manga.groupName(), manga.artist(),
                score, archiveService.loadNameTargets(archiveRootScoreMap()));
        return new ResolvedMatch(manga, match);
    }

    /** 归档匹配的结果：改名后的漫画 + 匹配（含冲突判定），压缩前算好、压缩后复用 */
    private record ResolvedMatch(NewManga manga, ArchiveMatch match) {
    }

    /**
     * 二次确认后归档：把 prepare 阶段压缩失败、用户仍选择归档的漫画提交。
     * <p>只做「匹配 + 搬家 + 落库」，不再改名、不再压缩。秒级，同步返回。
     */
    public StoreBatchResult confirmStore(List<StoreItem> items, List<String> tags) {
        return FileOpRecorder.batch(FileOpModule.MANGA, FileOpSource.PAGE, null,
                () -> confirmStoreInside(items, tags));
    }

    private StoreBatchResult confirmStoreInside(List<StoreItem> items, List<String> tags) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("没有要确认归档的漫画");
        }
        List<String> tagNames = cleanTags(tags);
        List<StoreResult> results = new ArrayList<>();
        for (StoreItem item : items) {
            int score = requireValidScore(item.score());
            Path dir = Paths.get(item.folderPath());
            ResolvedMatch resolved = resolveMatch(dir, score);
            results.add(commitOne(resolved.manga(), resolved.match(), dir, score, tagNames));
        }
        return new StoreBatchResult(results, List.of());
    }

    // ------------------------------------------------------------------
    // 合集存储
    // ------------------------------------------------------------------

    /** 合集压缩断点标记：一个 `compressed` 列表，按 `folderPath` 认已压过的漫画 */
    private record StoreMarker(List<CompressResult> compressed) {
        static StoreMarker empty() {
            return new StoreMarker(List.of());
        }
    }

    /** 读合集根的断点标记；没有或读坏了都返回空（读坏只是下次多压一遍） */
    private StoreMarker readMarker(Path collectionDir) {
        Path marker = collectionDir.resolve(STORE_MARKER_FILE);
        if (!Files.isRegularFile(marker)) {
            return StoreMarker.empty();
        }
        try {
            return OBJECT_MAPPER.readValue(marker.toFile(), StoreMarker.class);
        } catch (IOException e) {
            log.warn("读压缩标记失败，忽略并重新压缩：{}", marker, e);
            return StoreMarker.empty();
        }
    }

    /**
     * 把已压缩明细写回合集根。写失败不影响压缩，代价只是下次重存多压一遍。
     *
     * <p><b>跳过的（没配 NConvert）不写进去</b>：标记的含义是「这本压过了、别再压」，
     * 而跳过的那本一个文件都没动。写进去的话，用户以后配好 NConvert 再重存，
     * 读标记时会看到「已压缩，跳过」，<b>那本就永远压不上了</b> —— 而且毫无提示。
     */
    private void writeMarker(Path collectionDir, List<CompressResult> compressed) {
        List<CompressResult> actuallyCompressed =
                compressed.stream().filter(r -> !r.skipped()).toList();
        try {
            OBJECT_MAPPER.writeValue(collectionDir.resolve(STORE_MARKER_FILE).toFile(),
                    new StoreMarker(actuallyCompressed));
        } catch (IOException e) {
            log.warn("写压缩标记失败：{}", collectionDir.resolve(STORE_MARKER_FILE), e);
        }
    }

    /**
     * 提交合集存储。
     * <p>与单本的差别（来自概述）：整体评分与整体标签，<b>不视为对单本评分</b>，
     * 所以每本落库时 {@code score_source = INHERIT_ARCHIVE}。
     * <p>合集本身是一个 {@code [社团 (作者)]} 目录，存储后它就成了归档目录 ——
     * 所以搬的是整个合集目录（进对应评分的归档根），而不是逐本搬。
     * 与已归档作者重名时不拒绝：改名避开同名后照常入库，成为第二个 unit，
     * 让作者名落在两个归档目录上、由 {@code findConflicts} 列出这条合并冲突，
     * 再走合并冲突页把两边并到一处（见 {@link #moveCollectionIntoArchive}）。
     *
     * @param tags 整体标签，会写进合集目录名的 {@code 【…】} 块 ——
     *             那是归档目录标签的权威来源
     */
    /** 合集存储的任务参数 */
    public record CollectionStoreParams(String folderPath, Integer score, List<String> tags) {
    }

    /** 校验合集存储请求，产出任务参数。失败当场抛 */
    public CollectionStoreParams prepareStoreCollection(String folderPath, Integer score,
                                                        List<String> tags) {
        MangaCollection collection = newService.collection(folderPath);
        if (!collection.storable()) {
            throw new IllegalStateException("这个合集还不能存储："
                    + String.join("；", collection.blockers()));
        }
        // 标签要能原样写进目录名的【】块，校验前置到这里（提交前）而不是落盘时。
        // 要不要非空由 nya-entworks.manga.require-tags-before-archive 决定（出厂不强制），
        // 见 requireTags 的注释 —— 单本那条路完全不看标签
        return new CollectionStoreParams(folderPath, requireValidScore(score), requireTags(tags));
    }

    /**
     * 执行合集存储。跑在后台任务线程上。
     * <p><b>重跑是半幂等的</b>：压缩阶段读 {@link #STORE_MARKER_FILE} 断点标记，
     * 上次压过的按改名后的路径认出来、跳过不重压。所以中断后重跑不会白压一遍，
     * 但已经搬进归档根的合集不会再回来 —— 那种情况重跑会因为找不到源目录而失败。
     */
    public CollectionStoreResult runStoreCollection(CollectionStoreParams params,
                                                     AsyncTaskContext context) {
        // 批次入口包在整个流程外（别包在循环里）：存储一份合集 = 1 个批次
        // = 1 条 COLLECTION + 逐本改名/落库的若干条 SINGLE
        return FileOpRecorder.batch(FileOpModule.MANGA, FileOpSource.TASK, context.taskId(),
                () -> runStoreCollectionInside(params, context));
    }

    private CollectionStoreResult runStoreCollectionInside(CollectionStoreParams params,
                                                           AsyncTaskContext context) {
        // 重新扫：排队/重跑期间合集内容可能变了
        MangaCollection collection = newService.collection(params.folderPath());
        if (!collection.storable()) {
            throw new IllegalStateException("这个合集还不能存储："
                    + String.join("；", collection.blockers()));
        }
        int finalScore = requireValidScore(params.score());
        List<String> tagNames = requireTags(params.tags());
        {
            List<NewManga> mangas = collection.mangas();

            // 1. 逐本规范化改名。合集下的漫画靠父级目录名补全作者，所以父目录
            //    必须最后才动 —— 先改子目录名，父目录到搬迁那一步才改名+搬走
            context.message("规范化命名");
            List<Path> renamedPaths = new ArrayList<>();
            for (NewManga manga : mangas) {
                Path from = Paths.get(manga.folderPath());
                Path to = from;
                if (manga.needsRename()) {
                    to = from.resolveSibling(manga.normalizedName());
                    if (Files.exists(to)) {
                        context.log("😭 跳过改名，目标已存在：" + to);
                        renamedPaths.add(from);
                        continue;
                    }
                    try {
                        Files.move(from, to);
                        FileOpRecorder.recordPath(FileOpModule.MANGA, FileOpType.MOVE,
                                FileOpLevel.SINGLE, "合集存储：规范化改名",
                                from.toString(), to.toString());
                    } catch (IOException e) {
                        context.log("😭 改名失败：" + from + " —— " + e.getMessage());
                        renamedPaths.add(from);
                        continue;
                    }
                }
                renamedPaths.add(to);
            }

            // 2. 逐本压缩，失败的单列不中断。先读断点标记：上次压过的（按改名后的
            //    路径认）跳过不重压、复用它的结果；每压完一本增量写回标记，
            //    这样压到一半任务崩了、或关掉确认框再重存，都不会白压
            Path collectionDir = Paths.get(collection.folderPath());
            Map<String, CompressResult> doneByPath = readMarker(collectionDir).compressed()
                    .stream().collect(Collectors.toMap(CompressResult::folderPath,
                            r -> r, (a, b) -> a));
            List<CompressResult> compressed = new ArrayList<>();
            List<PendingArchive> pending = new ArrayList<>();
            int index = 0;
            for (int i = 0; i < mangas.size(); i++) {
                Path mangaDir = renamedPaths.get(i);
                index++;
                int current = index;
                CompressResult result = doneByPath.get(mangaDir.toString());
                if (result == null) {
                    context.progress(index - 1, mangas.size(),
                            "(" + index + "/" + mangas.size() + ") 压缩 " + mangaDir.getFileName());
                    result = compressService.compressFolder(mangaDir,
                            (done, total, file) -> context.message("(" + current + "/"
                                    + mangas.size() + ") " + mangaDir.getFileName()
                                    + " (" + done + "/" + total + ") " + file));
                    compressed.add(result);
                    writeMarker(collectionDir, compressed);
                } else {
                    compressed.add(result);
                    context.log("（已压缩，跳过）" + mangaDir.getFileName());
                }
                logCompressNotes(context, result);
                if (result.hasFailures()) {
                    PendingArchive item = new PendingArchive(mangas.get(i).folderPath(),
                            mangaDir.toString(), mangaDir.getFileName().toString(), result,
                            finalScore);
                    pending.add(item);
                    context.log("⚠ 待确认：" + item.folderName() + " —— "
                            + item.succeeded() + "/" + item.total()
                            + " 个文件压缩成功，" + result.failures().size() + " 个失败");
                }
            }

            // 3. 有压缩失败就不搬，等前端确认全部后再迁移
            if (!pending.isEmpty()) {
                context.progress(mangas.size(), mangas.size(), "待确认");
                return new CollectionStoreResult(collection.folderPath(), null, finalScore,
                        null, 0, compressed, pending);
            }
            return moveCollectionIntoArchive(collection.folderPath(), finalScore, tagNames,
                    compressed);
        }
    }

    /**
     * 把已改名+压缩好的合集整体搬进归档根，再同步归档库、逐本落库。
     * <p>重扫合集拿到改名后的 mangas（子目录名已变），所以传 folderPath 而不是提交时
     * 的 collection 对象。搬迁成功时 pending 为空、toFolderPath 非空。
     * <p>与已归档作者重名时（目标目录已存在）改名避开同名 —— 见 {@link #uniqueArchiveTarget}。
     */
    private CollectionStoreResult moveCollectionIntoArchive(String folderPath, int score,
                                                            List<String> tags,
                                                            List<CompressResult> compressed) {
        MangaCollection collection = newService.collection(folderPath);
        if (!collection.storable()) {
            throw new IllegalStateException("这个合集还不能存储："
                    + String.join("；", collection.blockers()));
        }
        Path dir = Paths.get(collection.folderPath());
        List<NewManga> mangas = collection.mangas();

        // 标签写进合集目录名，再把整个目录搬进对应评分的归档根。目标已存在时
        // （作者已归档的同名目录）改名避开，作者别名保留，交给合并冲突页去并
        String archivedName = MangaNameParser.withArchiveTags(collection.folderName(), tags);
        Path target = uniqueArchiveTarget(archiveRootOf(score), archivedName);
        Path moved;
        try {
            moved = moveDirectory(dir, target);
        } catch (IOException e) {
            throw new IllegalStateException("搬进归档目录失败：" + dir + " → " + target
                    + "（" + e.getClass().getSimpleName() + "：" + e.getMessage() + "）", e);
        }
        // 整体搬一个合集目录 = 一次 Files.move，天然只记一条 COLLECTION
        FileOpRecorder.recordPath(FileOpModule.MANGA, FileOpType.MOVE,
                FileOpLevel.COLLECTION, "整份合集归档", dir.toString(), moved.toString());

        // 搬迁已成功，断点标记没用了。它随目录一起搬了过来，删掉别混进归档目录
        try {
            Files.deleteIfExists(moved.resolve(STORE_MARKER_FILE));
        } catch (IOException e) {
            log.warn("删除压缩标记失败：{}", moved.resolve(STORE_MARKER_FILE), e);
        }

        // 同步归档库，让这个新目录成为一个 unit（含它的别名与标签）
        archiveService.sync(archiveRootScoreMap());
        Long unitId = archiveService.findUnitIdByFolderPath(moved.toString());

        // 逐本落库。合集是整体评分，故 score_source = INHERIT_ARCHIVE。
        // 目录已随合集整体搬走，按相对路径算出新位置
        int stored = 0;
        for (NewManga manga : mangas) {
            Path now = moved.resolve(dir.relativize(Paths.get(manga.folderPath())));
            if (!MangaCbzUtil.unitExists(now)) {
                log.warn("落库时找不到漫画单元，跳过：{}", now);
                continue;
            }
            // 归档流程新增：压缩图片之后打包成 cbz（失败降级为目录归档）
            Path finalPath = packToCbz(now);
            MangaData saved = saveMangaData(manga, finalPath, score, unitId, MangaDataStatus.ARCHIVED,
                    MangaScoreSource.INHERIT_ARCHIVE, null);
            // 落库后从 eh 拉取标签并并集应用（与单本 commitOne 一致）。合集结果不弹补标签窗，
            // 故忽略 tagPullFailed 返回值，拉取失败只记日志、不阻断归档
            pullTags(saved, null);
            stored++;
        }
        return new CollectionStoreResult(collection.folderPath(), moved.toString(),
                score, unitId, stored, compressed, List.of());
    }

    /**
     * 二次确认后迁移合集：把 prepare 阶段压缩失败、用户确认全部之后才整体搬迁。
     * <p>重扫合集（子目录已改名+压缩）→ 复验可存储 → 整体搬进归档根。秒级，同步返回。
     */
    public CollectionStoreResult confirmStoreCollection(String folderPath, Integer score,
                                                        List<String> tags) {
        return FileOpRecorder.batch(FileOpModule.MANGA, FileOpSource.PAGE, null,
                () -> {
                    int finalScore = requireValidScore(score);
                    List<String> tagNames = requireTags(tags);
                    return moveCollectionIntoArchive(folderPath, finalScore, tagNames, List.of());
                });
    }

    /** 合集存储的结果 */
    public record CollectionStoreResult(String fromFolderPath, String toFolderPath,
                                        int score, Long archiveUnitId, int storedMangas,
                                        List<CompressResult> compress,
                                        List<PendingArchive> pendingConfirmations) {
    }

    // ------------------------------------------------------------------
    // 落库与共用工具
    // ------------------------------------------------------------------

    /**
     * 压缩这一步的两类「说明」，写进任务日志（都不改变压缩与归档流程）：
     * 整步跳过（没配 NConvert），以及目录里不认识的图片格式。
     *
     * <p>跳过<b>必须写出来</b>：任务照常走到「完成」，图却还是原来那么大，
     * 不写一句的话，用户只能从「本次归档怎么没省空间」里自己猜。
     */
    private static void logCompressNotes(AsyncTaskContext context, CompressResult compress) {
        if (compress.skipped()) {
            context.log("⏭ 未压缩（" + compress.skipReason() + "），"
                    + "原图原样归档。配好 NConvert 后重跑归档即可压缩");
        }
        if (!compress.unknownImages().isEmpty()) {
            context.log("⚠ 未识别图片格式（未压缩，原样保留）："
                    + String.join("、", compress.unknownImages()));
        }
    }

    /**
     * 写 {@code manga_data}。
     * <p>按 {@code folder_path} 认行：同一个路径已有行就更新（重跑存储、
     * 或以前扫过的漫画又被处理一次），否则插入。这与
     * {@code MangaArchiveService.sync} 按路径认 unit 是同一套思路。
     *
     * <p>事务标在 {@link MangaDataWriter} 上而不是这里：本类内部调用它，
     * 而自调用会绕过 Spring 的事务代理 —— 那样「插行 + 写标签关联」的原子性会
     * 悄悄失效（同 {@code MangaTagAdminService.applyRewrites} 那个坑）。
     *
     * @param tags 单独标签；为空表示继承归档目录的标签（附三的约定：
     *             漫画侧没有 MANGA_DATA 关联即视为继承）
     */
    private MangaData saveMangaData(NewManga manga, Path folderPath, Integer score,
                                    Long archiveUnitId, MangaDataStatus status,
                                    MangaScoreSource scoreSource, List<String> tags) {
        return dataWriter.save(manga, folderPath, score, archiveUnitId, status,
                scoreSource, tags);
    }

    /**
     * {@code manga_data} 的写入，单独一个 bean 以便事务代理生效。
     * <p>拆出来的理由见 {@link MangaStoreService#saveMangaData}：同类内自调用
     * 拿不到代理，{@code @Transactional} 形同不存在。
     */
    @Service
    @RequiredArgsConstructor
    public static class MangaDataWriter {

        private final MangaDataMapper mangaDataMapper;
        private final MangaTagService tagService;

        @Transactional(rollbackFor = Exception.class)
        public MangaData save(NewManga manga, Path folderPath, Integer score,
                              Long archiveUnitId, MangaDataStatus status,
                              MangaScoreSource scoreSource, List<String> tags) {
            MangaData existing = mangaDataMapper.selectOne(Wrappers.<MangaData>lambdaQuery()
                    .eq(MangaData::getFolderPath, folderPath.toString()));
            MangaData data = existing == null ? new MangaData() : existing;

            data.setFolderPath(folderPath.toString());
            data.setCoverFile(MangaNameParser.getFirstImage(folderPath));
            data.setExhibit(manga.exhibit());
            data.setGroupName(manga.groupName());
            data.setArtist(manga.artist());
            data.setDateTag(manga.dateTag());
            data.setTitle(manga.title());
            data.setParody(manga.parody());
            data.setMagazine(manga.magazine());
            data.setMatchedRule(manga.matchedRule());
            data.setScore(score);
            data.setScoreSource(scoreSource);
            data.setStatus(status);
            data.setArchiveUnitId(archiveUnitId);
            data.setFileType(MangaDataFileType.fromFolderPath(folderPath.toString()));
            data.setFileCount(manga.fileCount());
            data.setImageCount(manga.imageCount());

            if (existing == null) {
                mangaDataMapper.insert(data);
            } else {
                mangaDataMapper.updateById(data);
            }

            // 标签为空时不写关联，那正是「继承归档目录标签」的表达方式；
            // 已有关联的行要清掉，否则改成「继承」这个动作落不下去
            if (tags != null) {
                tagService.replaceRefs(MangaTagTargetType.MANGA_DATA, data.getId(), tags);
            }
            return data;
        }

        /** 已入库的未归档漫画改名后：folder_path 跟上，封面顺带重算（后缀可能随压缩变过） */
        @Transactional(rollbackFor = Exception.class)
        public void updateFolderPath(String oldPath, String newPath) {
            MangaData data = mangaDataMapper.selectOne(Wrappers.<MangaData>lambdaQuery()
                    .eq(MangaData::getFolderPath, oldPath));
            if (data == null) {
                return;
            }
            data.setFolderPath(newPath);
            data.setCoverFile(MangaNameParser.getFirstImage(Paths.get(newPath)));
            data.setFileType(MangaDataFileType.fromFolderPath(newPath));
            mangaDataMapper.updateById(data);
        }

        /** 已入库的未归档漫画改评分（分区间移动）后：score 与 folder_path 一起跟上 */
        @Transactional(rollbackFor = Exception.class)
        public void updateScoreAndPath(String oldPath, Integer score, String newPath) {
            MangaData data = mangaDataMapper.selectOne(Wrappers.<MangaData>lambdaQuery()
                    .eq(MangaData::getFolderPath, oldPath));
            if (data == null) {
                return;
            }
            data.setFolderPath(newPath);
            data.setScore(score);
            data.setCoverFile(MangaNameParser.getFirstImage(Paths.get(newPath)));
            data.setFileType(MangaDataFileType.fromFolderPath(newPath));
            mangaDataMapper.updateById(data);
        }

        /** 已入库的未归档漫画删除后：清掉对应行 */
        @Transactional(rollbackFor = Exception.class)
        public void deleteByFolderPath(String folderPath) {
            mangaDataMapper.delete(Wrappers.<MangaData>lambdaQuery()
                    .eq(MangaData::getFolderPath, folderPath));
        }
    }

    /** 包可见：{@code MangaUnarchivedService} 自动归档时要搬目录，走同一套防覆盖移动 */
    static Path move(Path from, Path targetParent) {
        Path target = targetParent.resolve(from.getFileName());
        if (Files.exists(target)) {
            throw new IllegalStateException("目标目录已存在，先手工处理：" + target);
        }
        try {
            return moveDirectory(from, target);
        } catch (IOException e) {
            throw new IllegalStateException("移动失败：" + from + " → " + target
                    + "（" + e.getClass().getSimpleName() + "：" + e.getMessage() + "）", e);
        }
    }

    /**
     * 移动一个目录。{@link Files#move} 对非空目录的跨卷回退不总是可靠（外挂盘、
     * 网盘 junction 之类），所以这里显式兜底：move 失败且源还在、目标没出现，
     * 就递归复制再删源。复制的中间态若删源失败会回滚掉，不留两份。
     */
    private static Path moveDirectory(Path from, Path target) throws IOException {
        try {
            return Files.move(from, target);
        } catch (IOException moveError) {
            if (Files.exists(target)) {
                throw moveError;
            }
            // cbz 是单个文件：跨卷 move 失败时 copy + delete 兜底（目录才走 copyTree）
            if (Files.isRegularFile(from)) {
                Files.copy(from, target, StandardCopyOption.COPY_ATTRIBUTES);
                Files.delete(from);
                return target;
            }
            if (!Files.isDirectory(from)) {
                throw moveError;
            }
            copyTree(from, target);
            try {
                deleteTree(from);
            } catch (IOException deleteError) {
                // 复制成了但删不掉源（多半是文件被占），回滚已复制内容，别留两份
                try {
                    deleteTree(target);
                } catch (IOException rollbackError) {
                    deleteError.addSuppressed(rollbackError);
                }
                throw deleteError;
            }
            return target;
        }
    }

    /** 递归复制目录树到 target（target 可不存在，逐层建） */
    private static void copyTree(Path from, Path target) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                Files.createDirectories(target.resolve(from.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.copy(file, target.resolve(from.relativize(file)),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** 递归删除目录树（先删文件，再自底向上删空目录） */
    private static void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                    throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 归档根下现有分区目录的「路径 → 评分」表，按磁盘现扫（分区名由磁盘决定，
     * 见 {@link MangaScoreDir}）。归档根本身从配置读，不再写死代码里。
     */
    private Map<String, Integer> archiveRootScoreMap() {
        return MangaScoreDir.rootScoreMap(properties.getArchiveDir());
    }

    private Path archiveRootOf(int score) {
        for (var entry : archiveRootScoreMap().entrySet()) {
            if (entry.getValue() == score) {
                Path root = Paths.get(entry.getKey());
                if (!Files.isDirectory(root)) {
                    throw new IllegalStateException("归档根不在磁盘上：" + root);
                }
                return root;
            }
        }
        throw new IllegalArgumentException("没有 " + score + " 分的归档根");
    }

    /**
     * 归档根下不冲突的目标目录名。
     * <p>合集与已归档作者重名（目标目录已存在）时，在社团位加序号改名避开同名，
     * 作者原样保留 —— 作者别名是 {@code findConflicts} 认这条合并冲突的钥匙，
     * 动了它冲突就列不出来。无社团（{@code [作者]} 形态）时拿作者名顶替社团位，
     * 拼成 {@code [作者 2 (作者)]}，作者同样保留。
     * <p>改名后的目录仍是合法归档形态，sync 会把它建成第二个 unit，
     * 于是作者名落在两个归档目录上，合并冲突页便能并排比对。
     */
    private static Path uniqueArchiveTarget(Path archiveRoot, String archivedName) {
        Path direct = archiveRoot.resolve(archivedName);
        if (!Files.exists(direct)) {
            return direct;
        }
        MangaNameParser.ArchiveFolderInfo info = MangaNameParser.parseArchiveFolderName(archivedName);
        if (info == null || StringUtils.isBlank(info.artistNames())) {
            throw new IllegalStateException("归档目录已存在且解析不出作者，无法改名：" + direct);
        }
        String base = StringUtils.defaultIfBlank(info.groupName(), info.artistNames());
        int i = 2;
        while (true) {
            Path candidate = archiveRoot.resolve(MangaNameParser.buildArchiveFolderName(
                    base + " " + i, info.artistNames(), info.tags()));
            if (!Files.exists(candidate)) {
                return candidate;
            }
            i++;
        }
    }

    /**
     * 从现扫结果里取这一本。不信前端传来的字段 —— 磁盘随时可能变。
     * <p>合集下的漫画也要能取到：改名/删除/规范化这几个操作在合集内页共用同一套
     * 卡片组件，所以两个根目录都得认。
     */
    private NewManga requireScanned(String folderPath) {
        Path dir = requireEditable(folderPath);
        if (underCollectionDir(dir)) {
            return newService.scanCollections().stream()
                    .flatMap(c -> c.mangas().stream())
                    .filter(m -> Paths.get(m.folderPath()).equals(dir))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "这个目录不在合集扫描结果里（可能已被移走，或它不含图片）：" + folderPath));
        }
        return newService.scan().mangas().stream()
                .filter(m -> Paths.get(m.folderPath()).equals(dir))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "这个目录不在新漫画扫描结果里（可能已被移走，或它不含图片）：" + folderPath));
    }

    /**
     * 可就地编辑（阅读/改名/删除/规范化/评分）的目录：新漫画根、合集根或未归档根下面的。
     * <p>存储仍只受理新漫画根下的 —— 合集是整体存储，未归档的重新归档走
     * {@code MangaUnarchivedService.archive}（不压缩），都不走 {@code submitStore}。
     */
    private Path requireEditable(String folderPath) {
        if (StringUtils.isBlank(folderPath)) {
            throw new IllegalArgumentException("目录路径不能为空");
        }
        Path target = Paths.get(folderPath).toAbsolutePath().normalize();
        if (underCollectionDir(target)) {
            return MangaNewService.requireUnder(folderPath, properties.getCollectionDir(), "合集漫画");
        }
        if (underUnarchivedDir(target)) {
            return MangaNewService.requireUnder(folderPath, properties.getUnarchivedDir(), "未归档漫画");
        }
        return requireNewManga(folderPath);
    }

    /**
     * 可在系统文件资源管理器里打开的目录：新漫画根、合集根、未归档根或归档根下的。
     * <p>比 {@link #requireEditable} 宽，多认归档根 —— 阅读页对四类漫画都提供「打开目录」，
     * 归档漫画虽不能就地编辑，但打开所在文件夹是安全的只读动作。
     */
    private Path requireOpenedDir(String folderPath) {
        if (StringUtils.isBlank(folderPath)) {
            throw new IllegalArgumentException("目录路径不能为空");
        }
        Path target = Paths.get(folderPath).toAbsolutePath().normalize();
        if (underCollectionDir(target) || underUnarchivedDir(target)) {
            return target;
        }
        if (StringUtils.isNotBlank(properties.getNewDir())
                && target.startsWith(Paths.get(properties.getNewDir()).toAbsolutePath().normalize())) {
            return target;
        }
        if (underArchiveDir(target)) {
            return target;
        }
        throw new IllegalArgumentException("不在受管的漫画目录下，不给打开：" + folderPath);
    }

    /** 四个评分分区都在归档根下面，所以比归档根本身就够，不必逐分区比 */
    private boolean underArchiveDir(Path path) {
        String root = properties.getArchiveDir();
        return StringUtils.isNotBlank(root)
                && path.startsWith(Paths.get(root).toAbsolutePath().normalize());
    }

    private boolean underCollectionDir(Path path) {
        String root = properties.getCollectionDir();
        return StringUtils.isNotBlank(root)
                && path.startsWith(Paths.get(root).toAbsolutePath().normalize());
    }

    private boolean underUnarchivedDir(Path path) {
        String root = properties.getUnarchivedDir();
        return StringUtils.isNotBlank(root)
                && path.startsWith(Paths.get(root).toAbsolutePath().normalize());
    }

    private Path requireNewManga(String folderPath) {
        return MangaNewService.requireUnder(folderPath, properties.getNewDir(), "新漫画");
    }

    private static String requireSimpleName(String folderName) {
        return MangaFolderName.requireSimple(folderName);
    }

    /**
     * 清理并校验标签名（去空白 + 校验可写进目录名）。允许为空 —— 单本归档不再强制填标签，
     * 留空则归档落库后从 eh 拉取（并集）。合集仍要求非空（标签要写进目录名），见 {@link #requireTags}。
     */
    static List<String> cleanTags(List<String> tags) {
        List<String> tagNames = new ArrayList<>();
        for (String tag : tags == null ? List.<String>of() : tags) {
            if (StringUtils.isNotBlank(tag)) {
                tagNames.add(MangaTagService.requireTagName(tag));
            }
        }
        return tagNames;
    }

    /**
     * 标签校验 + 归档闸门（合集存储用：标签要原样写进目录名的 {@code 【…】} 块）。
     *
     * <p><b>要不要非空由配置决定</b>：{@code nya-entworks.manga.require-tags-before-archive}
     * （{@link MangaProperties#isRequireTagsBeforeArchive()}）打开时才拦；出厂不拦，
     * 标签留空时目录名就是没有 {@code 【】} 块的 {@code [社团 (作者)]} ——
     * 那是合法形态，下次同步照常入库（见 {@link MangaNameParser#withArchiveTags}）。
     *
     * <p>这条闸门只管合集：单本归档本来就不拦（见 {@link #cleanTags}）。
     */
    private List<String> requireTags(List<String> tags) {
        List<String> tagNames = cleanTags(tags);
        if (tagNames.isEmpty() && properties.isRequireTagsBeforeArchive()) {
            throw new IllegalStateException("还没打标签，不能归档。请先给漫画打上标签");
        }
        return tagNames;
    }

    static int requireValidScore(Integer score) {
        if (score == null) {
            throw new IllegalArgumentException("请先评分");
        }
        for (int valid : MangaScoreDir.SCORES) {
            if (valid == score) {
                return score;
            }
        }
        throw new IllegalArgumentException("评分只能是 3/5/7/9，收到 " + score);
    }

    /**
     * 存储闸门（不含评分）：命名规范 + 无未识别展会/原作 + 有图。
     * <p>评分不再看扫描出的目录分区（打分不移动目录），改由归档弹窗随请求传进来，
     * 在 {@link #submitStore} / {@link #confirmStore} 里用 {@link #requireValidScore} 校验。
     * 与 {@link MangaNewService#toNewManga} 里 blockers 的判据同口径，只是不拦「还没评分」。
     */
    private static void checkStorable(NewManga manga) {
        // cbz 不走存储管线（压缩只处理散图，cbz 是打包成品）。所有存储入口都过这道闸门，
        // 在这里挡住即可，前端 storable=false 也已把按钮置灰
        if (MangaCbzUtil.isCbz(Paths.get(manga.folderPath()))) {
            throw new IllegalStateException("cbz 不走存储流程，要归档请手动放进归档目录");
        }
        if (manga.matchedRule() == 0) {
            throw new IllegalStateException("目录名不规范，规范化命名不出结果");
        }
        if (manga.extraExhibit() != null) {
            throw new IllegalStateException("展会「" + manga.extraExhibit() + "」不在词典里");
        }
        if (manga.extraParody() != null) {
            throw new IllegalStateException("原作「" + manga.extraParody() + "」不在词典里");
        }
        if (manga.imageCount() == 0) {
            throw new IllegalStateException("目录里没有图片，没什么可压缩的");
        }
    }
}
