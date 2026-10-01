package io.github.Nyameph.nyaentworks.manga.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多个候选 gallery 的标签合并，纯静态、不连库。
 * <p><b>排序信号只有「相似度加权频次」</b>：{@code score(tag) = Σ_{i: tag∈tags_i} sim_i}。
 * <b>赞同数（weight）只做过滤、不做加权</b>：已知且 {@code weight < weightMin} 的标签剔除；
 * 拿不到权重（详情页没抓）时跳过过滤、保留该标签，绝不因「没抓到权重」误删。
 * <p>只保留内容类 6 个命名空间（见 {@link MangaEhTagFetcher#CONTENT_NAMESPACES}，不含 language），
 * 与目录名权威（artist/group/parody/character）不打架。
 */
public final class MangaEhTagMerge {

    private MangaEhTagMerge() {
    }

    /** 合并输入：一个候选画廊的相似度、标签集与可选权重（权重为 null 表示未抓） */
    public record Candidate(double similarity, List<String> tags, Map<String, Integer> weights) {
    }

    /** 合并输出：一条标签的 eh 原文 "namespace:tag_en"、相似度加权频次与出现频次 */
    public record Merged(String tag, double score, int freq) {
    }

    /**
     * 合并 Top-K 候选的标签。
     *
     * @param candidates 已按相似度排好序、已截断到 Top-K 的候选
     * @param weightMin  赞同数门槛：已知且 < weightMin 的标签剔除
     * @param maxTags    最多保留的标签数
     * @return 按 score 降序、freq 降序、标签名升序，截断到 maxTags
     */
    public static List<Merged> merge(List<Candidate> candidates, int weightMin, int maxTags) {
        Map<String, Score> acc = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            if (c.tags() == null) {
                continue;
            }
            for (String tag : c.tags()) {
                if (!isContentNamespace(nsOf(tag))) {
                    continue;
                }
                Score s = acc.computeIfAbsent(tag, k -> new Score());
                s.score += c.similarity();
                s.freq++;
                Integer w = c.weights() == null ? null : c.weights().get(tag);
                if (w != null && (s.knownWeight == null || w > s.knownWeight)) {
                    s.knownWeight = w;
                }
            }
        }
        List<Merged> kept = new ArrayList<>();
        for (Map.Entry<String, Score> e : acc.entrySet()) {
            Score s = e.getValue();
            // 赞同数过滤：已知且过低才剔除；未知（没抓到权重）不误删
            if (s.knownWeight != null && s.knownWeight < weightMin) {
                continue;
            }
            kept.add(new Merged(e.getKey(), s.score, s.freq));
        }
        kept.sort(Comparator.comparingDouble(Merged::score).reversed()
                .thenComparing(Comparator.comparingInt(Merged::freq).reversed())
                .thenComparing(Merged::tag));
        return kept.size() <= maxTags ? kept : kept.subList(0, maxTags);
    }

    private static String nsOf(String tag) {
        int i = tag.indexOf(':');
        return i <= 0 ? "" : tag.substring(0, i);
    }

    private static boolean isContentNamespace(String ns) {
        return MangaEhTagFetcher.CONTENT_NAMESPACES.contains(ns);
    }

    private static final class Score {
        double score;
        int freq;
        Integer knownWeight;
    }
}
