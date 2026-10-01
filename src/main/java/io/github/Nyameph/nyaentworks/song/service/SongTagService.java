package io.github.Nyameph.nyaentworks.song.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import io.github.Nyameph.nyaentworks.manga.util.MangaTextUtil;
import io.github.Nyameph.nyaentworks.song.entity.SongTag;
import io.github.Nyameph.nyaentworks.song.mapper.SongTagMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 歌曲标签的读写（需求：给歌曲加标签；「归档前是否必须先有标签」2026-09-22 起由
 * {@code nya-entworks.song.require-tags-before-archive} 决定，出厂不强制）。
 *
 * <p>标签挂在合并条目上，key = {@code merge_key}。与漫画的
 * {@code MangaTagService} <b>没有共用代码</b> —— 两模块独立（「模块之间没有调用关系」），
 * 唯一复用是 {@link MangaTextUtil#nfc} 的归一口径，保证标签名的假名写法不劈成两条。
 *
 * <p>标签名冗余存储：删目标标签、加新标签就是删行、插行，没有「改标签名要连带改
 * 目录名」的权威关系（那是漫画的约束，歌曲标签是 DB-only）。
 */
@Service
@RequiredArgsConstructor
public class SongTagService {

    private final SongTagMapper tagMapper;

    /** 某个合并条目的标签名，按名排序 */
    public List<String> listTags(String mergeKey) {
        return listTagsBatch(List.of(mergeKey)).getOrDefault(mergeKey, List.of());
    }

    /**
     * 批量取标签名，避免逐条查一遍。
     *
     * @return mergeKey → 标签名列表；无标签的不出现在结果中
     */
    public Map<String, List<String>> listTagsBatch(Collection<String> mergeKeys) {
        if (mergeKeys == null || mergeKeys.isEmpty()) {
            return Map.of();
        }
        List<SongTag> tags = tagMapper.selectList(Wrappers.<SongTag>lambdaQuery()
                .in(SongTag::getMergeKey, mergeKeys)
                .orderByAsc(SongTag::getTagName));
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (SongTag tag : tags) {
            result.computeIfAbsent(tag.getMergeKey(), k -> new ArrayList<>()).add(tag.getTagName());
        }
        return result;
    }

    /**
     * 把某个合并条目的标签整组替换成 {@code tagNames}：先删现有关联行，再插新集合。
     * <p>传入空集合即清空该条目的标签（等于「不打标签」）。
     *
     * @return 实际写入的标签数
     */
    public int replaceTags(String mergeKey, Collection<String> tagNames) {
        if (StringUtils.isBlank(mergeKey)) {
            throw new IllegalArgumentException("mergeKey 不能为空");
        }
        tagMapper.delete(Wrappers.<SongTag>lambdaQuery()
                .eq(SongTag::getMergeKey, mergeKey));
        List<String> names = normalize(tagNames);
        for (String name : names) {
            SongTag tag = new SongTag();
            tag.setMergeKey(mergeKey);
            tag.setTagName(name);
            tagMapper.insert(tag);
        }
        return names.size();
    }

    /** 全部标签名（去重），按名排序，供打标签时点选 */
    public List<String> listAllTags() {
        return tagMapper.selectList(Wrappers.<SongTag>query()
                        .select("tag_name")
                        .groupBy("tag_name")
                        .orderByAsc("tag_name"))
                .stream()
                .map(SongTag::getTagName)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 校验并归一标签名。
     * <p>歌曲标签是 DB-only，没有漫画「要能写进目录名【】块」的空白/【】限制，
     * 但仍统一走 NFC 归一，避免同一标签的假名两种写法存成两条（唯一键是
     * {@code ai_ci} 归并不了的分解形式）。
     */
    public static String requireTagName(String tagName) {
        String name = MangaTextUtil.nfc(StringUtils.trimToNull(tagName));
        if (name == null) {
            throw new IllegalArgumentException("标签名不能为空");
        }
        return name;
    }

    /** 去空白 + NFC 归一 + 去重（保序，大小写不敏感去重以对齐 DB 的 ai_ci 唯一键） */
    private static List<String> normalize(Collection<String> tagNames) {
        if (tagNames == null) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> result = new ArrayList<>();
        for (String raw : tagNames) {
            String name = StringUtils.trimToNull(raw);
            if (name == null) {
                continue;
            }
            name = MangaTextUtil.nfc(name);
            if (seen.add(name.toLowerCase())) {
                result.add(name);
            }
        }
        Collections.sort(result);
        return result;
    }
}
