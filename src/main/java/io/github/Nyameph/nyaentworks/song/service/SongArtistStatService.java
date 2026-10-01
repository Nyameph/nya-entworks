package io.github.Nyameph.nyaentworks.song.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import io.github.Nyameph.nyaentworks.common.ScoreWeight;
import io.github.Nyameph.nyaentworks.song.service.SongGroupService.SongGroup;
import io.github.Nyameph.nyaentworks.song.util.SongName;
import io.github.Nyameph.nyaentworks.song.util.SongNameParser;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按作者统计（需求变更新增）。
 *
 * <p><b>多作者展开</b>：{@code A & B - 歌名} 在统计时A和B各算一次 ——
 * 这样才能看出单个作者的总产出。归一化比较键复用 {@code originalKey} 的套路
 * （NFC + trim + 大写）。
 *
 * <p><b>已归档与待打分分开计数</b>：同原曲名统计的立场（文档 8.6）。
 */
@Service
@RequiredArgsConstructor
public class SongArtistStatService {

    /** 贝叶斯平均分的最少评分数量（先验样本量）：作者 28% 只有 1 个作品，取 2 让单作品保留 1/3 权重 */
    private static final int BAYESIAN_MIN = 2;

    private final SongGroupService groupService;

    /**
     * 一个作者的统计行。
     *
     * @param artistKey      归一化比较键
     * @param artistName     作者名原文（取第一次遇到的那个写法）
     * @param archivedCount  已归档的歌曲数（多作者歌算多次）
     * @param stagingCount   待打分的歌曲数
     * @param bayesianScore  加权贝叶斯平均分，只计已归档的；一首都没归档时为 {@code null}
     */
    public record ArtistStat(String artistKey,
                             String artistName,
                             int archivedCount,
                             int stagingCount,
                             BigDecimal bayesianScore) {

        public int totalCount() {
            return archivedCount + stagingCount;
        }
    }

    /** 累加用的可变载体 */
    private static final class Accumulator {
        private final String key;
        private final String name;
        private int archived;
        private int staging;
        private double wSum;        // 加权贝叶斯用（Σ 权重）
        private double wScoreSum;   // Σ 权重 × 分数

        private Accumulator(String key, String name) {
            this.key = key;
            this.name = name;
        }
    }

    /**
     * 全部作者的统计。
     */
    public List<ArtistStat> listArtists() {
        Map<String, Accumulator> byKey = new LinkedHashMap<>();
        double globalWSum = 0;
        double globalWScoreSum = 0;

        // 已归档歌曲：按 mergeKey 归并成条目 —— 同一首歌的多个版本（#1 / #2 / 无版本）
        // 在列表里合并成一行，统计也必须一行只算一次，否则同一首歌的作者会被重复计产出。
        // 分数取主 variant（列表也是以它为代表），多作者歌每个作者各算一次。
        for (SongGroup main : mergeMainVariants(groupService.listArchived(null))) {
            List<Accumulator> accs = accumulatorsFor(byKey, main);
            Integer score = main.score();
            double w = score == null ? 0 : ScoreWeight.weight(score);
            for (Accumulator acc : accs) {
                acc.archived++;
                if (score != null) {
                    acc.wSum += w;
                    acc.wScoreSum += w * score;
                }
            }
            // 全局均值按条目（main）算一次，不因多作者展开而重复
            if (score != null) {
                globalWSum += w;
                globalWScoreSum += w * score;
            }
        }

        // 待打分歌曲
        for (SongGroup main : mergeMainVariants(groupService.listStaging())) {
            List<Accumulator> accs = accumulatorsFor(byKey, main);
            for (Accumulator acc : accs) {
                acc.staging++;
            }
        }

        // 全局均值 C = 全部已归档有分条目的加权平均分（每个条目算一次）
        BigDecimal globalAvg = globalWSum == 0 ? null
                : BigDecimal.valueOf(globalWScoreSum)
                .divide(BigDecimal.valueOf(globalWSum), 6, RoundingMode.HALF_UP);

        // 转成统计行
        List<ArtistStat> result = new ArrayList<>(byKey.size());
        for (Accumulator acc : byKey.values()) {
            BigDecimal bayesian = acc.wSum == 0 || globalAvg == null ? null
                    : bayesian(globalAvg, acc.wScoreSum, acc.wSum);
            result.add(new ArtistStat(acc.key, acc.name, acc.archived, acc.staging, bayesian));
        }

        // 歌曲数多的在前 —— 「谁唱得最多」是这一页最想看的
        result.sort(Comparator.comparingInt(ArtistStat::totalCount).reversed()
                .thenComparing(ArtistStat::artistName));
        return result;
    }

    /**
     * 按归并键把组归并成条目，返回每个条目的主 variant。
     * 与列表页 {@code /merged-groups} 同一口径（版本号与文件类型不参与归并）；
     * 解析失败的组各自成键，不会互相合并。
     */
    private static List<SongGroup> mergeMainVariants(List<SongGroup> groups) {
        Map<String, List<SongGroup>> buckets = new LinkedHashMap<>();
        for (SongGroup g : groups) {
            buckets.computeIfAbsent(SongNameParser.mergeKey(g.name(), g.mainName()),
                    k -> new ArrayList<>()).add(g);
        }
        List<SongGroup> mains = new ArrayList<>(buckets.size());
        for (List<SongGroup> bucket : buckets.values()) {
            SongMergeService.sortVariants(bucket);
            mains.add(bucket.get(0));
        }
        return mains;
    }

    /**
     * 取该组对应的累加器列表（多作者展开）。
     *
     * @return 解析失败的组返回空列表（没有作者可归）
     */
    private static List<Accumulator> accumulatorsFor(Map<String, Accumulator> byKey,
                                                      SongGroup group) {
        SongName name = group.name();
        if (name == null || !name.parsed() || name.artists().isEmpty()) {
            return List.of();
        }
        List<Accumulator> result = new ArrayList<>(name.artists().size());
        for (String artist : name.artists()) {
            String key = artistKey(artist);
            Accumulator acc = byKey.computeIfAbsent(key, k -> new Accumulator(k, artist));
            result.add(acc);
        }
        return result;
    }

    /**
     * 作者名归一化比较键：NFC + trim + 大写（复用 {@code originalKey} 的套路）。
     */
    private static String artistKey(String artist) {
        return SongNameParser.originalKey(artist);
    }

    /** 加权贝叶斯平均分 = (C × m + Σw·s) / (m + Σw) */
    private static BigDecimal bayesian(BigDecimal globalAvg, double wScoreSum, double wSum) {
        BigDecimal m = BigDecimal.valueOf(BAYESIAN_MIN);
        return globalAvg.multiply(m).add(BigDecimal.valueOf(wScoreSum))
                .divide(BigDecimal.valueOf(wSum).add(m), 1, RoundingMode.HALF_UP);
    }
}
