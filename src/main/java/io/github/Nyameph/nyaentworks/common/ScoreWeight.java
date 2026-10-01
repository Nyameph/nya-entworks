package io.github.Nyameph.nyaentworks.common;

/**
 * 评分 → 贝叶斯统计权重的映射（漫画与歌曲共用同一套语义）。
 *
 * <p>权重以 5 分为基准 1.0：低分降权、高分升权，让统计页的排名更突出佳作、
 * 不被几首 1 分/3 分的歌（或漫画）拉垮。漫画评分档位是 {@code 3/5/7/9}（无 1 分），
 * 歌曲是 {@code 1/3/5/7/9}，两者共用此表。
 *
 * <p>权重值都取 0.25 的整数倍，{@code double} 能精确表示，累加后再用
 * {@code BigDecimal} 收敛到 1 位小数，不会引入浮点误差。意外分数按 1.0 中性处理，
 * 避免一条脏数据打挂整个统计页（评分档位在写入时已被 {@code MangaScoreDir} /
 * {@code ScorePartition} 校验过，这里只是读侧兜底）。
 */
public final class ScoreWeight {

    private ScoreWeight() {
    }

    /** 分数对应的统计权重；未知分数按 1.0 中性处理 */
    public static double weight(int score) {
        return switch (score) {
            case 1 -> 0.5;
            case 3 -> 0.75;
            case 5 -> 1.0;
            case 7 -> 1.5;
            case 9 -> 2.0;
            default -> 1.0;
        };
    }
}
