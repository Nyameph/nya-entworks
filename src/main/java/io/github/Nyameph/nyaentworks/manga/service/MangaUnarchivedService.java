package io.github.Nyameph.nyaentworks.manga.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import io.github.Nyameph.nyaentworks.common.ScoreWeight;
import io.github.Nyameph.nyaentworks.common.db.SqlDialect;
import io.github.Nyameph.nyaentworks.manga.config.MangaProperties;
import io.github.Nyameph.nyaentworks.manga.consts.MangaDataFileType;
import io.github.Nyameph.nyaentworks.manga.consts.MangaDataStatus;
import io.github.Nyameph.nyaentworks.manga.consts.MangaScoreSource;
import io.github.Nyameph.nyaentworks.manga.consts.MangaTagTargetType;
import io.github.Nyameph.nyaentworks.manga.entity.MangaData;
import io.github.Nyameph.nyaentworks.manga.entity.MangaEhScan;
import io.github.Nyameph.nyaentworks.manga.mapper.MangaDataMapper;
import io.github.Nyameph.nyaentworks.manga.service.MangaArchiveService.ArchiveMatch;
import io.github.Nyameph.nyaentworks.manga.service.MangaArchiveService.NameTargets;
import io.github.Nyameph.nyaentworks.manga.service.MangaNewService.NewManga;
import io.github.Nyameph.nyaentworks.manga.util.MangaCbzUtil;
import io.github.Nyameph.nyaentworks.manga.util.MangaNameParser;
import io.github.Nyameph.nyaentworks.manga.util.MangaScoreDir;
import io.github.Nyameph.nyaentworks.common.fileop.FileOpRecorder;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpLevel;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpModule;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpSource;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpType;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「未归档」页面（文档 4.9）：散漫目录 {@code F:\MangaIndie} 下的漫画。
 * <p>与新漫画页不同，这一层<b>已经入库</b>（{@code manga_data.status = UNARCHIVED}，
 * 存储那一刻写入，见 {@code MangaStoreService}），所以数据从库里读、分页展示；
 * 磁盘上目录不见了标 {@code MISSING} 而不删行（保评分与标签）。
 * <p>重扫（{@link #scan}）做三件事：按磁盘 upsert 行（封面后缀随压缩变过的顺带修回，
 * 见 {@code MangaDataWriter.save}）、规范化且命中归档作者的<b>自动归档</b>
 * （不压缩，存时已压缩过，只搬目录 + 更新库）、目录消失的标失踪。
 */
@Service
@RequiredArgsConstructor
public class MangaUnarchivedService {

    private static final Logger log = LoggerFactory.getLogger(MangaUnarchivedService.class);

    /** 贝叶斯平均分的最少评分数量（先验样本量）：m 越小单本保留越多原始分，取 2 兼顾平滑与区分度 */
    private static final int BAYESIAN_MIN = 2;

    private final MangaProperties properties;
    private final MangaDataMapper mangaDataMapper;
    private final MangaNewService newService;
    private final MangaDictService dictService;
    private final MangaArchiveService archiveService;
    /** 复用来写行：upsert、封面重算与存储那边是同一套，见 {@link MangaStoreService.MangaDataWriter} */
    private final MangaStoreService.MangaDataWriter dataWriter;
    /** 归档时写单本的独立标签（需求：未归档归档也要求打标签，与新漫画一致） */
    private final MangaTagService tagService;
    /** 归档落库后从 eh 拉取标签（并集） */
    private final MangaEhScanService ehScanService;

    /** 列表里的一行：库里的行 + 磁盘还在时现扫出的卡片字段（不在则为 null） */
    public record UnarchivedRow(MangaData row, NewManga manga, boolean exists) {
    }

    public record UnarchivedPage(long total, int page, int size, List<UnarchivedRow> items) {
    }

    /** 贝叶斯平均分统计里的一行：按作者或社团聚合。count 是本数（含未评分），bayesian 是加权贝叶斯平均分 */
    public record TopEntry(String name, long count, BigDecimal bayesian) {
    }

    public record TopResult(List<TopEntry> artists, List<TopEntry> groups) {
    }

    public record ScanUnarchivedResult(int scanned, int inserted, int updated,
                                       int archived, int missing) {
    }

    /**
     * 批量归档的结果：成功数 + 逐条失败原因 + 归档成功但标签拉取失败的漫画。
     * 一条失败不中断整批，页面把失败路径与原因列给人看，别用「成功 N 本」一句话盖过去。
     */
    public record ArchiveBatchResult(int archived, List<String> errors,
                                     List<ArchiveResult> tagPullFailed) {
    }

    /** 批量重新归档的任务参数（重跑靠它还原被选中的目录清单与整批标签） */
    public record ArchiveBatchParams(List<String> folderPaths, List<String> tags) {
    }

    /** 单本归档的结果：归档成功，但 eh 标签拉取失败（且用户没填标签）时 tagPullFailed 为真 */
    public record ArchiveResult(Long mangaId, String folderPath, String folderName,
                                boolean tagPullFailed) {
    }

    // ------------------------------------------------------------------
    // 列表与统计
    // ------------------------------------------------------------------

    /**
     * 分页列出未归档漫画。关键词对标题/作者/社团/目录全路径做模糊匹配。
     * <p>每行：目录还在磁盘上就现扫一遍产卡片字段（复用 {@link MangaNewService#toNewManga}，
     * 规则/未识别展会/归档匹配与「新漫画」页同一套判定）；目录不在了只给库里的行，
     * 前端据此画成「失踪」卡。
     */
    public UnarchivedPage page(String keyword, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 200);
        String kw = StringUtils.trimToNull(keyword);

        LambdaQueryWrapper<MangaData> q = Wrappers.<MangaData>lambdaQuery()
                .eq(MangaData::getStatus, MangaDataStatus.UNARCHIVED)
                .and(kw != null, w -> w
                        .like(MangaData::getTitle, kw)
                        .or().like(MangaData::getArtist, kw)
                        .or().like(MangaData::getGroupName, kw)
                        .or().like(MangaData::getFolderPath, kw))
                .orderByDesc(MangaData::getUpdateTime)
                .orderByAsc(MangaData::getId);

        // 分页没走 MyBatis-Plus 的分页插件：jsqlparser 模块未引入（interceptor 在那个模块里），
        // 且项目此前没有分页需求，为一行查询引一个新依赖不值当。手动 count + limit，
        // 页码已钳制，offset/size 是整数，无注入面。与 top10 的 .last("limit 10") 同一套路
        long total = mangaDataMapper.selectCount(q);
        long offset = (long) (safePage - 1) * safeSize;
        List<MangaData> records = mangaDataMapper.selectList(
                q.last(SqlDialect.limit(offset, safeSize)));

        MangaNameParser parser = new MangaNameParser(dictService.current());
        NameTargets targets = archiveService.loadNameTargets(
                MangaScoreDir.rootScoreMap(properties.getArchiveDir()));

        List<UnarchivedRow> items = new ArrayList<>();
        List<NewManga> present = new ArrayList<>();
        for (MangaData row : records) {
            Path dir = Paths.get(row.getFolderPath());
            boolean exists = MangaCbzUtil.unitExists(dir);
            NewManga manga = null;
            if (exists) {
                MangaData parsed = parser.parseDir(dir);
                if (parsed != null) {
                    manga = newService.toNewManga(parsed, dir, row.getScore(), null, targets,
                            row.getId(), row.getFileCount(), row.getImageCount());
                }
            }
            if (manga != null) {
                present.add(manga);
            }
            items.add(new UnarchivedRow(row, manga, exists));
        }
        // 回显标签：独立标签优先、父级标签（归档匹配命中的目录）兜底
        List<NewManga> withTags = archiveService.withDisplayTags(present, null);
        int wi = 0;
        for (int i = 0; i < items.size(); i++) {
            UnarchivedRow item = items.get(i);
            if (item.manga() != null) {
                items.set(i, new UnarchivedRow(item.row(), withTags.get(wi++), item.exists()));
            }
        }
        return new UnarchivedPage(total, safePage, safeSize, items);
    }

    /**
     * 加权贝叶斯平均分前 10 的作者与社团（文档 4.9）。
     * <p>加权贝叶斯平均分 = (全局均值 C × m + Σw·s) / (m + Σw)，m 取
     * {@link #BAYESIAN_MIN}，w 是评分权重（低分降权、高分升权，见 {@link ScoreWeight}）。
     * 样本越少越向全局均值靠拢，避免一本 9 分就把某作者顶到前面。
     * 高分映射入低分已不再拦截归档，所以统计里不必再跳过任何行。
     */
    public TopResult top10() {
        BigDecimal globalAvg = globalWeightedAverage();
        return new TopResult(topByField("artist", globalAvg), topByField("group_name", globalAvg));
    }

    private List<TopEntry> topByField(String column, BigDecimal globalAvg) {
        // 拉明细行在 Java 里分组：加权和（Σw·s / Σw）没法用一条 SQL 的 sum/count 表达，
        // 权重是分数的函数。未归档漫画量小（散本），逐行算无压力。
        List<Map<String, Object>> rows = mangaDataMapper.selectMaps(
                Wrappers.<MangaData>query()
                        .select(column + " as name", "score")
                        .eq("status", MangaDataStatus.UNARCHIVED.name())
                        .isNotNull(column));
        Map<String, Accumulator> byName = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object name = row.get("name");
            if (name == null) {
                continue;
            }
            Accumulator acc = byName.computeIfAbsent(name.toString(), k -> new Accumulator());
            acc.count++;
            if (row.get("score") != null) {
                int score = ((Number) row.get("score")).intValue();
                double w = ScoreWeight.weight(score);
                acc.wSum += w;
                acc.wScoreSum += w * score;
            }
        }
        List<TopEntry> result = new ArrayList<>();
        for (Map.Entry<String, Accumulator> e : byName.entrySet()) {
            Accumulator acc = e.getValue();
            BigDecimal bayesian = acc.wSum == 0 || globalAvg == null ? null
                    : bayesian(globalAvg, acc.wScoreSum, acc.wSum);
            result.add(new TopEntry(e.getKey(), acc.count, bayesian));
        }
        // 贝叶斯分降序，没分的排最后；取前 10
        result.sort(Comparator.comparing(TopEntry::bayesian,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return result.size() > 10 ? new ArrayList<>(result.subList(0, 10)) : result;
    }

    /** 全部未归档漫画（有分的）的加权平均分，作为贝叶斯公式的全局均值 C */
    private BigDecimal globalWeightedAverage() {
        List<Map<String, Object>> rows = mangaDataMapper.selectMaps(
                Wrappers.<MangaData>query()
                        .select("score")
                        .eq("status", MangaDataStatus.UNARCHIVED.name())
                        .isNotNull("score"));
        double wSum = 0;
        double wScoreSum = 0;
        for (Map<String, Object> row : rows) {
            int score = ((Number) row.get("score")).intValue();
            double w = ScoreWeight.weight(score);
            wSum += w;
            wScoreSum += w * score;
        }
        if (wSum == 0) {
            return null;
        }
        return BigDecimal.valueOf(wScoreSum)
                .divide(BigDecimal.valueOf(wSum), 6, RoundingMode.HALF_UP);
    }

    /** 加权贝叶斯平均分 = (C × m + Σw·s) / (m + Σw) */
    private static BigDecimal bayesian(BigDecimal globalAvg, double wScoreSum, double wSum) {
        BigDecimal m = BigDecimal.valueOf(BAYESIAN_MIN);
        return globalAvg.multiply(m).add(BigDecimal.valueOf(wScoreSum))
                .divide(BigDecimal.valueOf(wSum).add(m), 1, RoundingMode.HALF_UP);
    }

    /** 按作者/社团名字分组的加权累加 */
    private static final class Accumulator {
        long count;        // 本数（含未评分）
        double wSum;       // Σ 权重（只含有分的）
        double wScoreSum;  // Σ 权重 × 分数
    }

    // ------------------------------------------------------------------
    // 重扫与自动归档
    // ------------------------------------------------------------------

    /**
     * 手动重扫散漫目录：upsert 行、自动归档能归的、消失的标失踪。
     * <p>同步执行（秒级）：只走目录与更新库，不压缩，不会像存储那样拉出分钟级任务。
     */
    public ScanUnarchivedResult scan() {
        return scan(null);
    }

    /** 带 taskId 的版本（异步任务 handler 传当前任务 id）。扫描里的自动归档共用一个批次号 */
    public ScanUnarchivedResult scan(Long taskId) {
        return FileOpRecorder.batch(FileOpModule.MANGA, FileOpSource.TASK, taskId,
                this::scanInside);
    }

    private ScanUnarchivedResult scanInside() {
        String root = properties.getUnarchivedDir();
        MangaNewService.requireDir(root, "未归档根目录");

        MangaNameParser parser = new MangaNameParser(dictService.current());
        NameTargets targets = archiveService.loadNameTargets(
                MangaScoreDir.rootScoreMap(properties.getArchiveDir()));

        int scanned = 0, inserted = 0, updated = 0, archived = 0, missing = 0;

        // 根下直接放的（未评分）与各评分分区里的，同「新漫画」扫描的分区结构
        List<Path> dirs = new ArrayList<>();
        for (File dir : MangaNewService.listSubDirs(root)) {
            if (MangaScoreDir.parseScore(dir.getName()) != null) {
                continue; // 评分分区目录不是漫画
            }
            dirs.add(dir.toPath());
        }
        MangaScoreDir.scoreDirs(root).forEach((score, dir) ->
                MangaNewService.listSubDirs(dir.toString()).forEach(f -> dirs.add(f.toPath())));

        for (Path dir : dirs) {
            MangaData parsed = parser.parseDir(dir);
            if (parsed == null) {
                continue; // 不是漫画形态（合集目录/杂物），跳过
            }
            scanned++;
            Integer score = scoreOf(dir);
            NewManga manga = newService.toNewManga(parsed, dir, score, targets);
            // 规范化 + 有分 + 命中归档 → 自动归档。不压缩：存进散漫时已压缩过
            if (manga.matchedRule() != 0 && manga.score() != null
                    && manga.archiveMatch() != null && manga.archiveMatch().archivable()) {
                archiveOne(manga, targets, manga.score(), null);
                archived++;
                continue;
            }
            boolean existed = mangaDataMapper.selectCount(Wrappers.<MangaData>lambdaQuery()
                    .eq(MangaData::getFolderPath, dir.toString())) > 0;
            dataWriter.save(manga, dir, score, null, MangaDataStatus.UNARCHIVED,
                    MangaScoreSource.SELF, null);
            if (existed) {
                updated++;
            } else {
                inserted++;
            }
        }

        // 库里的 UNARCHIVED 行目录已不在磁盘上 → 标失踪。只标不删，为的是保住评分，
        // 目录可能只是被临时移走（外挂盘没挂上）
        List<MangaData> all = mangaDataMapper.selectList(Wrappers.<MangaData>lambdaQuery()
                .eq(MangaData::getStatus, MangaDataStatus.UNARCHIVED));
        for (MangaData row : all) {
            if (!MangaCbzUtil.unitExists(Paths.get(row.getFolderPath()))) {
                row.setStatus(MangaDataStatus.MISSING);
                mangaDataMapper.updateById(row);
                missing++;
            }
        }

        return new ScanUnarchivedResult(scanned, inserted, updated, archived, missing);
    }

    /**
     * 单本重新归档：把一本未归档漫画搬进命中的归档目录，不压缩。
     * <p>与 {@link #scan} 里的自动归档走同一段逻辑（{@link #archiveOne}），
     * 只是先校验这本确实落在散漫根下。闸门在归档时重新算一遍，不信前端传来的。
     * 评分随请求传入（预填现有分、可改），标签必填（与新漫画一致）。
     */
    public ArchiveResult archive(String folderPath, Integer score, List<String> tags) {
        return FileOpRecorder.batch(FileOpModule.MANGA, FileOpSource.PAGE, null,
                () -> archiveInside(folderPath, score, tags));
    }

    private ArchiveResult archiveInside(String folderPath, Integer score, List<String> tags) {
        MangaNewService.requireUnder(folderPath, properties.getUnarchivedDir(), "未归档漫画");
        Path dir = Paths.get(folderPath);
        MangaNameParser parser = new MangaNameParser(dictService.current());
        MangaData parsed = parser.parseDir(dir);
        if (parsed == null) {
            throw new IllegalArgumentException("这个目录不是漫画形态，无法归档：" + folderPath);
        }
        NameTargets targets = archiveService.loadNameTargets(
                MangaScoreDir.rootScoreMap(properties.getArchiveDir()));
        NewManga manga = newService.toNewManga(parsed, dir, scoreOf(dir), targets);
        int finalScore = MangaStoreService.requireValidScore(score);
        List<String> tagNames = MangaStoreService.cleanTags(tags);
        return archiveOne(manga, targets, finalScore, tagNames);
    }

    /**
     * 批量重新归档：逐本走 {@link #archive}，一本失败记下来继续下一本，不中断整批。
     * <p>同步执行（与单本归档同量级：改名 + 搬家 + 更新库，不压缩）。
     * 评分沿用库里的现分（批量无弹窗不改分），标签整批共用。
     */
    public ArchiveBatchResult archiveBatch(List<String> folderPaths, List<String> tags) {
        return archiveBatch(folderPaths, tags, null);
    }

    /** 带 taskId 的版本（异步任务 handler 传当前任务 id）。整批一个批次号 */
    public ArchiveBatchResult archiveBatch(List<String> folderPaths, List<String> tags,
                                           Long taskId) {
        return FileOpRecorder.batch(FileOpModule.MANGA, FileOpSource.TASK, taskId,
                () -> archiveBatchInside(folderPaths, tags));
    }

    private ArchiveBatchResult archiveBatchInside(List<String> folderPaths, List<String> tags) {
        if (folderPaths == null || folderPaths.isEmpty()) {
            throw new IllegalArgumentException("没有选中要归档的漫画");
        }
        List<String> errors = new ArrayList<>();
        List<ArchiveResult> tagPullFailed = new ArrayList<>();
        int archived = 0;
        for (String path : folderPaths) {
            try {
                MangaData row = mangaDataMapper.selectOne(Wrappers.<MangaData>lambdaQuery()
                        .eq(MangaData::getFolderPath, path));
                Integer score = row != null ? row.getScore() : scoreOf(Paths.get(path));
                ArchiveResult r = archive(path, score, tags);
                archived++;
                if (r.tagPullFailed()) {
                    tagPullFailed.add(r);
                }
            } catch (Exception e) {
                errors.add(path + " —— " + e.getMessage());
            }
        }
        return new ArchiveBatchResult(archived, errors, tagPullFailed);
    }

    /**
     * 归档一本。磁盘先动（改名如需、搬进归档目录），库后动 —— 磁盘失败时库没动过，
     * 下次重扫还能按原路径认回来。评分与独立标签从参数落库（评分已校验，标签可空表示继承）。
     */
    private ArchiveResult archiveOne(NewManga manga, NameTargets targets, int score,
                                 List<String> tagNames) {
        if (manga.matchedRule() == 0 || StringUtils.isBlank(manga.normalizedName())) {
            throw new IllegalStateException("目录名不规范，没法规范化改名再归档："
                    + StringUtils.defaultString(manga.irregularReason()));
        }
        ArchiveMatch match = manga.archiveMatch();
        if (match == null || match.target() == null) {
            throw new IllegalStateException("匹配不到归档目录，无法归档");
        }

        Path dir = Paths.get(manga.folderPath());
        Path renamed = dir;
        if (manga.needsRename()) {
            Path target = dir.resolveSibling(manga.normalizedName());
            if (Files.exists(target)) {
                throw new IllegalStateException("规范化改名时目标目录已存在：" + target);
            }
            try {
                Files.move(dir, target);
                FileOpRecorder.recordPath(FileOpModule.MANGA, FileOpType.MOVE,
                        FileOpLevel.SINGLE, "归档：规范化改名", dir.toString(), target.toString());
                renamed = target;
            } catch (IOException e) {
                throw new IllegalStateException("规范化改名失败：" + e.getMessage(), e);
            }
        }
        Path finalPath = MangaStoreService.move(renamed, Paths.get(match.target().folderPath()));
        // 归档搬运在 MangaStoreService.move（静态工具）里，记录在这层出，别重复
        FileOpRecorder.recordPath(FileOpModule.MANGA, FileOpType.MOVE,
                FileOpLevel.SINGLE, "归档", renamed.toString(), finalPath.toString());

        // 按旧路径认行：改名/搬家后 folder_path 变了，行要跟着走
        MangaData row = mangaDataMapper.selectOne(Wrappers.<MangaData>lambdaQuery()
                .eq(MangaData::getFolderPath, manga.folderPath()));
        if (row == null) {
            row = new MangaData();
        }
        row.setFolderPath(finalPath.toString());
        row.setCoverFile(MangaNameParser.getFirstImage(finalPath));
        row.setFileType(MangaDataFileType.fromFolderPath(finalPath.toString()));
        row.setArchiveUnitId(match.target().unitId());
        row.setStatus(MangaDataStatus.ARCHIVED);
        row.setScore(score);
        row.setScoreSource(MangaScoreSource.SELF);
        // 搬家不压缩，文件数不变，但顺手把现扫的统计值落库（与 cover_file 同款）
        row.setFileCount(manga.fileCount());
        row.setImageCount(manga.imageCount());
        if (row.getId() == null) {
            mangaDataMapper.insert(row);
        } else {
            mangaDataMapper.updateById(row);
        }
        // 独立标签落库；为空表示继承归档目录标签（不写关联，与新漫画存储同一约定）
        if (tagNames != null) {
            tagService.replaceRefs(MangaTagTargetType.MANGA_DATA, row.getId(), tagNames);
        }
        // 落库后从 eh 拉取标签并并集应用（有标签也拉、取并集）；拉不到且没填标签时标记补填
        boolean tagPullFailed = pullTags(row, tagNames);
        return new ArchiveResult(row.getId(), finalPath.toString(),
                finalPath.getFileName().toString(), tagPullFailed);
    }

    /**
     * 归档落库后从 eh 拉取标签并并集应用（{@code autoApply=true} 走并集）。只判断是否
     * 需要用户手动补：拉不到（状态非 SUCCESS）且用户也没填标签时返回 true。
     * 拉取失败不抛异常、不阻断归档 —— 漫画已经落库，标签为空即继承归档目录标签。
     */
    private boolean pullTags(MangaData saved, List<String> tagNames) {
        try {
            MangaEhScanService.ScanResult r = ehScanService.scan(saved, true, null);
            return !MangaEhScan.STATUS_SUCCESS.equals(r.status())
                    && (tagNames == null || tagNames.isEmpty());
        } catch (Exception e) {
            log.warn("从 eh 拉取标签失败 {}: {}", saved.getFolderPath(), e.getMessage());
            return tagNames == null || tagNames.isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /** 目录所在评分分区的评分；直接放在根下的视为未评分 */
    private static Integer scoreOf(Path dir) {
        if (dir.getParent() == null || dir.getParent().getFileName() == null) {
            return null;
        }
        return MangaScoreDir.parseScore(dir.getParent().getFileName().toString());
    }
}
