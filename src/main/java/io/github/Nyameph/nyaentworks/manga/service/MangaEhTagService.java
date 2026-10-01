package io.github.Nyameph.nyaentworks.manga.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import io.github.Nyameph.nyaentworks.manga.config.MangaProperties;
import io.github.Nyameph.nyaentworks.manga.entity.MangaTag;
import io.github.Nyameph.nyaentworks.manga.mapper.MangaTagMapper;
import io.github.Nyameph.nyaentworks.manga.util.MangaEhTagFetcher;
import io.github.Nyameph.nyaentworks.manga.util.MangaEhTagFetcher.TagRow;
import io.github.Nyameph.nyaentworks.manga.util.MangaTextUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 e-hentai 直接拉取词典标签、比对入库。
 *
 * <p>取代原先「脚本拉 CSV + 脚本读 CSV 入库」两步（那两个脚本已删，2026-09-15）：
 * {@link #sync} 一次完成拉取、解析、分类、比对、入库，中间不经 CSV —— CSV 是个过路文件，
 * 只会带来「盘上那份和库里那份谁是准的」这种没必要的问题。
 *
 * <p><b>比对与补齐</b>：标签身份 = (namespace, tag_name) —— 按
 * {@code eq(namespace).eq(tag_name)} 精确查，已有则刷新、没有则插入，
 * {@code source} 标 {@code EHENTAI}。不走 {@link MangaTagService#findByName}（那是「词典优先」
 * 语义，会把同名不同 namespace 塌成一条）。
 *
 * <p><b>无法判断大类的标签不自动入库</b>：{@link MangaEhTagFetcher#classify} 兜底分支
 * （female/male 无映射、other 兜底）的标签 {@code confident=false}，收进
 * {@link #sync} 的返回里让用户手动选大类，再经 {@link #applyUndetermined} 落库。
 */
@Service
@RequiredArgsConstructor
public class MangaEhTagService {

    private final MangaTagMapper tagMapper;
    private final MangaProperties props;

    /** 一次同步的结果：拉到的去重条数、确定项的新增/更新、待人工填大类的标签 */
    public record SyncResult(int fetched, int inserted, int updated,
                             List<Undetermined> undetermined) {
    }

    /** 无法判断大类的标签，供前端展示并让用户填大类 */
    public record Undetermined(String namespace, String namespaceZh, String tagName,
                               String enName, String category, String description,
                               String fallbackCategory) {
    }

    /** 用户填好大类后的提交 */
    public record UndeterminedFill(String namespace, String tagName, String enName,
                                   String category, String description, String majorCategory) {
    }

    /**
     * 拉取 + 解析 + 分类 + 比对入库。
     * <p>确定大类的直接 upsert；无法判断的返回给调用方，不入库。
     */
    public SyncResult sync() {
        List<TagRow> rows;
        try {
            rows = MangaEhTagFetcher.fetch(props.getEhTagProxyHost(), props.getEhTagProxyPort());
        } catch (IOException | InterruptedException e) {
            // InterruptedException 只在重试的 Thread.sleep 被打断时发生，这里一起兜成业务异常
            Thread.currentThread().interrupt();
            throw new IllegalStateException("拉取 e-hentai 标签失败：" + e.getMessage(), e);
        }

        // 身份 = (namespace, 中文)：去 emoji 已在 Fetcher 做掉，这里补 NFC；同名不同 namespace 是两条
        Map<String, TagRow> unique = new LinkedHashMap<>();
        for (TagRow row : rows) {
            String name = MangaTextUtil.nfc(row.tagZh());
            if (name.isEmpty()) {
                continue;
            }
            unique.putIfAbsent(row.namespace() + "::" + name, row);
        }

        int inserted = 0;
        int updated = 0;
        List<Undetermined> undetermined = new ArrayList<>();
        for (TagRow row : unique.values()) {
            String name = MangaTextUtil.nfc(row.tagZh());
            if (!row.confident()) {
                undetermined.add(new Undetermined(row.namespace(), row.namespaceZh(), name,
                        row.tagEn(), row.category(), row.description(), row.majorCategory()));
                continue;
            }
            if (upsert(row, name, row.majorCategory())) {
                inserted++;
            } else {
                updated++;
            }
        }
        return new SyncResult(unique.size(), inserted, updated, undetermined);
    }

    /**
     * 把用户填好大类的标签落库（与确定项同一套 upsert）。
     *
     * @return 处理的标签数（新增 + 更新）
     */
    public int applyUndetermined(List<UndeterminedFill> fills) {
        if (fills == null || fills.isEmpty()) {
            return 0;
        }
        int applied = 0;
        for (UndeterminedFill f : fills) {
            String name = MangaTextUtil.nfc(StringUtils.trimToNull(f.tagName()));
            String major = StringUtils.trimToNull(f.majorCategory());
            if (name == null || f.namespace() == null || major == null) {
                throw new IllegalArgumentException("标签名、命名空间、大类都不能为空");
            }
            if (!MangaEhTagFetcher.MAJOR_CATEGORIES.contains(major)) {
                throw new IllegalArgumentException("大类「" + major + "」不在可选范围内");
            }
            TagRow row = new TagRow(major, true, f.namespace(), "", f.enName(), name,
                    f.category(), f.description());
            upsert(row, name, major);
            applied++;
        }
        return applied;
    }

    /** 按 (namespace, tag_name) 精确 upsert，返回 true=新增、false=更新 */
    private boolean upsert(TagRow row, String name, String majorCategory) {
        MangaTag existing = tagMapper.selectOne(Wrappers.<MangaTag>lambdaQuery()
                .eq(MangaTag::getNamespace, row.namespace())
                .eq(MangaTag::getTagName, name));
        if (existing != null) {
            existing.setEnName(nullIfBlank(row.tagEn()));
            existing.setMajorCategory(nullIfBlank(majorCategory));
            existing.setCategory(nullIfBlank(row.category()));
            existing.setDescription(nullIfBlank(row.description()));
            existing.setSource(MangaTag.SOURCE_EHENTAI);
            tagMapper.updateById(existing);
            return false;
        }
        MangaTag tag = new MangaTag();
        tag.setTagName(name);
        tag.setNamespace(row.namespace());
        tag.setEnName(nullIfBlank(row.tagEn()));
        tag.setMajorCategory(nullIfBlank(majorCategory));
        tag.setCategory(nullIfBlank(row.category()));
        tag.setDescription(nullIfBlank(row.description()));
        tag.setSource(MangaTag.SOURCE_EHENTAI);
        tagMapper.insert(tag);
        return true;
    }

    private static String nullIfBlank(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
