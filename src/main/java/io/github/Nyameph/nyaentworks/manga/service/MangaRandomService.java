package io.github.Nyameph.nyaentworks.manga.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import io.github.Nyameph.nyaentworks.common.ScoreWeight;
import io.github.Nyameph.nyaentworks.manga.config.MangaProperties;
import io.github.Nyameph.nyaentworks.manga.consts.MangaDataStatus;
import io.github.Nyameph.nyaentworks.manga.consts.MangaTagTargetType;
import io.github.Nyameph.nyaentworks.manga.entity.MangaData;
import io.github.Nyameph.nyaentworks.manga.mapper.MangaDataMapper;
import io.github.Nyameph.nyaentworks.manga.service.MangaArchiveService.NameTargets;
import io.github.Nyameph.nyaentworks.manga.service.MangaNewService.NewManga;
import io.github.Nyameph.nyaentworks.manga.util.MangaCbzUtil;
import io.github.Nyameph.nyaentworks.manga.util.MangaNameParser;
import io.github.Nyameph.nyaentworks.manga.util.MangaScoreDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 已归档 / 未归档漫画的随机抽选（需求：抽 50 部，按分数加权、高分比例更高，
 * 优先抽没有独立标签的）。
 *
 * <p><b>加权随机</b>复用 {@link ScoreWeight#weight(int)}（低分降权、高分升权），
 * 与统计页同一套口径。无放回抽选，池子里抽完就返回实际抽到的数量。
 *
 * <p><b>「优先无独立标签」是硬优先</b>：先从不带独立标签（{@code MANGA_DATA} 无关联）的
 * 漫画里按分数抽，抽不满 {@code requested} 再用带标签的补足。这样「还没单独打标签的漫画」
 * 有更高的被抽中机会，但不是绝对排除带标签的。
 *
 * <p><b>抽中后现扫</b>：抽出来的漫画要能在网页上读/改（与已归档/未归档页一致），
 * 所以每本走 {@link MangaNewService#toNewManga} 现扫出卡片字段（归档匹配、闸门、封面等）。
 * 只对抽中的 ≤50 本现扫，全量目录存在性检查已经并行，整体仍比现状快。
 */
@Service
@RequiredArgsConstructor
public class MangaRandomService {

    private final MangaDataMapper mangaDataMapper;
    private final MangaTagService tagService;
    private final MangaNewService newService;
    private final MangaArchiveService archiveService;
    private final MangaDictService dictService;
    private final MangaProperties properties;

    /**
     * 抽中的一部漫画。
     *
     * @param manga         现扫出的卡片字段（复用 {@link NewManga}），前端据此复用阅读/修改弹窗
     * @param archiveUnitId 已归档漫画的归属归档目录 id，父级标签用它查；未归档为 {@code null}
     * @param tags          独立标签（{@code MANGA_DATA} 标签），可能为空
     */
    public record RandomManga(NewManga manga, Long archiveUnitId, List<MangaTagService.TagItem> tags) {
    }

    /** 一次抽选的结果 */
    public record RandomDraw(String source, int requested, int drawn,
                             int untaggedCount, int taggedCount,
                             List<RandomManga> mangas) {
    }

    /**
     * 抽选。{@code source} 只能是 {@link MangaDataStatus#ARCHIVED} / {@link MangaDataStatus#UNARCHIVED}。
     * <p>只抽目录还在磁盘上的（抽中了得能打开看）；{@code MISSING} 是另一状态，不会混进来。
     */
    public RandomDraw draw(MangaDataStatus source, int requested) {
        if (source == null
                || (source != MangaDataStatus.ARCHIVED && source != MangaDataStatus.UNARCHIVED)) {
            throw new IllegalArgumentException("抽选来源只能是 ARCHIVED / UNARCHIVED");
        }
        int n = Math.max(1, requested);

        List<MangaData> rows = mangaDataMapper.selectList(Wrappers.<MangaData>lambdaQuery()
                .eq(MangaData::getStatus, source)
                .orderByAsc(MangaData::getId));
        List<MangaData> pool = existingDirs(rows);
        if (pool.isEmpty()) {
            return new RandomDraw(source.name(), n, 0, 0, 0, List.of());
        }

        Map<Long, List<MangaTagService.TagItem>> tagsByManga = tagService.listTagsBatch(
                MangaTagTargetType.MANGA_DATA,
                pool.stream().map(MangaData::getId).toList());
        List<MangaData> untagged = new ArrayList<>();
        List<MangaData> tagged = new ArrayList<>();
        for (MangaData row : pool) {
            if (tagsByManga.containsKey(row.getId())) {
                tagged.add(row);
            } else {
                untagged.add(row);
            }
        }

        Random rng = new Random();
        List<MangaData> drawn = new ArrayList<>();
        drawn.addAll(weightedSample(untagged, n, rng));
        drawn.addAll(weightedSample(tagged, n - drawn.size(), rng));
        // 展示顺序随机，别一眼看出「无标签的全在前」
        Collections.shuffle(drawn, rng);

        // 抽中后现扫：复用到阅读/修改弹窗要的卡片字段。词典快照与归档查找表全程一份
        MangaNameParser parser = new MangaNameParser(dictService.current());
        NameTargets targets = archiveService.loadNameTargets(
                MangaScoreDir.rootScoreMap(properties.getArchiveDir()));

        List<RandomManga> result = new ArrayList<>();
        List<NewManga> present = new ArrayList<>();
        for (MangaData row : drawn) {
            Path dir = Paths.get(row.getFolderPath());
            MangaData parsed = parser.parseDir(dir);
            if (parsed == null) {
                // 防御：库里是漫画，但现扫认不出（目录变了）就跳过，不塞进结果
                continue;
            }
            NewManga manga;
            Long archiveUnitId;
            if (source == MangaDataStatus.ARCHIVED) {
                // 与已归档页一致：评分来源取自库里那行（SELF / INHERIT_ARCHIVE）
                manga = newService.toNewManga(parsed, dir, row.getScore(), row.getScoreSource(),
                        targets, row.getId(), row.getFileCount(), row.getImageCount());
                archiveUnitId = row.getArchiveUnitId();
            } else {
                // 与未归档页一致：还没落到「继承」那层，评分来源传 null
                manga = newService.toNewManga(parsed, dir, row.getScore(), null, targets,
                        row.getId(), row.getFileCount(), row.getImageCount());
                archiveUnitId = null;
            }
            present.add(manga);
            result.add(new RandomManga(manga, archiveUnitId,
                    tagsByManga.getOrDefault(row.getId(), List.of())));
        }
        // 回显标签注入卡片字段（独立优先、父级兜底），编辑用的 RandomManga.tags 不变
        List<NewManga> withTags = archiveService.withDisplayTags(present, null);
        for (int i = 0; i < result.size(); i++) {
            RandomManga rm = result.get(i);
            result.set(i, new RandomManga(withTags.get(i), rm.archiveUnitId(), rm.tags()));
        }
        return new RandomDraw(source.name(), n, result.size(), untagged.size(), tagged.size(),
                result);
    }

    /**
     * 保序过滤出目录还在磁盘上的行。
     * <p>逐个 {@link Files#isDirectory} 在本地盘还行，外挂盘/网络盘一次 stat 就要几十毫秒，
     * 上千本串下来是抽选的主要耗时。这里用虚拟线程并发 stat、按原顺序收集 ——
     * 虚拟线程在阻塞 I/O 上几乎零开销，是抽选提速的大头。
     */
    private static List<MangaData> existingDirs(List<MangaData> rows) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> futures = new ArrayList<>(rows.size());
            for (MangaData row : rows) {
                String path = row.getFolderPath();
                futures.add(executor.submit(
                        () -> path != null && MangaCbzUtil.unitExists(Paths.get(path))));
            }
            List<MangaData> pool = new ArrayList<>(rows.size());
            for (int i = 0; i < rows.size(); i++) {
                if (Boolean.TRUE.equals(futures.get(i).get())) {
                    pool.add(rows.get(i));
                }
            }
            return pool;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("目录存在性检查被中断", e);
        } catch (ExecutionException e) {
            // isDirectory 不抛检查异常，这里只兜底防御
            throw new IllegalStateException("目录存在性检查失败", e);
        }
    }

    /**
     * 按分数加权、无放回抽 {@code k} 部；{@code k} 超过池大小时抽满为止。
     * <p>权重只算一遍（原来的实现每轮对每个元素算两遍 {@code weightOf}），
     * 抽中用「交换到末尾」做 O(1) 移除（原 {@code ArrayList.remove} 是 O(n) 搬移）。
     * 总代价 O(n·k)，池子最多几千、k 最多几十，抽选本身在微秒级。
     */
    private static List<MangaData> weightedSample(List<MangaData> pool, int k, Random rng) {
        List<MangaData> items = new ArrayList<>(pool);
        double[] weights = new double[items.size()];
        for (int i = 0; i < items.size(); i++) {
            weights[i] = weightOf(items.get(i).getScore());
        }
        List<MangaData> result = new ArrayList<>();
        int end = items.size();
        while (result.size() < k && end > 0) {
            double total = 0;
            for (int i = 0; i < end; i++) {
                total += weights[i];
            }
            double r = rng.nextDouble() * total;
            double acc = 0;
            int chosen = end - 1;
            for (int i = 0; i < end; i++) {
                acc += weights[i];
                if (r < acc) {
                    chosen = i;
                    break;
                }
            }
            result.add(items.get(chosen));
            end--;
            items.set(chosen, items.get(end));
            weights[chosen] = weights[end];
        }
        return result;
    }

    /** 评分权重；没评分的按中性 1.0 处理（与 {@link ScoreWeight} 读侧兜底一致） */
    private static double weightOf(Integer score) {
        return score == null ? 1.0 : ScoreWeight.weight(score);
    }
}
