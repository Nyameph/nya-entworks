package io.github.Nyameph.nyaentworks.manga.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import io.github.Nyameph.nyaentworks.manga.consts.MangaTagNamespace;
import io.github.Nyameph.nyaentworks.manga.consts.MangaTagTargetType;
import io.github.Nyameph.nyaentworks.manga.entity.MangaTag;
import io.github.Nyameph.nyaentworks.manga.entity.MangaTagRef;
import io.github.Nyameph.nyaentworks.manga.mapper.MangaTagMapper;
import io.github.Nyameph.nyaentworks.manga.mapper.MangaTagRefMapper;
import io.github.Nyameph.nyaentworks.manga.util.MangaTextUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 标签的读写。标签本身与「挂给谁」分离，故本类不关心目标是归档目录还是单本漫画。
 * <p>标签名按 NFC 归一后存储（见 {@link MangaTextUtil#nfc}），与词典侧同一套规则，
 * 避免同一个假名的两种写法在库里存成两条。
 * <p>标签身份 = (namespace, tag_name)：目录标签的 namespace 为 NULL，e-hentai 词典
 * 标签带 namespace（female/male/…），同名中文但不同 namespace 是两条。
 *
 * <p><b>按名查询一律「词典优先、目录标签兜底」</b>：目录名里的词只要在词典里有对应
 * 中文，就解析成那条词典标签（同名跨多个 namespace 时按
 * {@link MangaTagNamespace} 的优先级挑一条）；词典里没有的（英文词、作品名、
 * 「未收录」这类管理性标记）才落回目录标签，必要时新建。两种标签长期共存。
 *
 * <p>词典侧与目录侧用 {@code namespace IS NOT NULL} / {@code IS NULL} 严格互补来切分，
 * 不用 {@code source} 列 —— 否则一条 namespace 非空但 source 不是 EHENTAI 的行会
 * 两边都落不着，被当成未命中而凭空新建一个目录标签。
 *
 * <p>例外是 {@link #listTargetIds}：它是「打了某标签的目标有哪些」的反查，按名把词典侧
 * 与目录侧的关联<b>并起来</b>，理由见该方法注释。
 */
@Service
@RequiredArgsConstructor
public class MangaTagService {

    private final MangaTagMapper tagMapper;
    private final MangaTagRefMapper refMapper;

    /**
     * 按名取标签，库中没有的当场创建。
     * <p>入参先去空白、NFC 归一、去重（保序）。
     * <p>每个词按「词典优先、目录标签兜底、都没有才新建」三级解析，见类注释。
     * 新建走的仍是目录标签那条路（namespace 留空、{@code source} 靠库默认值 FOLDER），
     * 词典里没有的词才走得到这里。
     *
     * @return 标签名 → 标签，键取标签在库里的真实名字 —— 两个不同的词解析到同一条标签时
     * 靠这个键塌成一条，否则 {@link #replaceRefs} 会拿同一个 tagId 插两条关联撞唯一键
     */
    public synchronized Map<String, MangaTag> resolveOrCreate(Collection<String> tagNames) {
        Set<String> normalized = normalize(tagNames);
        if (normalized.isEmpty()) {
            return Map.of();
        }
        Map<String, MangaTag> dictHits = selectDictByName(normalized);
        Map<String, MangaTag> folderHits = selectFolderByName(normalized);

        Map<String, MangaTag> result = new LinkedHashMap<>();
        for (String name : normalized) {
            String key = MangaTextUtil.normalizeNameKey(name);
            MangaTag hit = dictHits.get(key);
            if (hit == null) {
                hit = folderHits.get(key);
            }
            if (hit == null) {
                hit = new MangaTag();
                hit.setTagName(name.toUpperCase());
                tagMapper.insert(hit);
            }
            result.put(hit.getTagName(), hit);
        }
        return result;
    }

    /**
     * 词典标签（namespace 非空）按名批量查，同名跨多个 namespace 的按优先级只留一条。
     *
     * @return 归一化名 → 标签
     */
    private Map<String, MangaTag> selectDictByName(Set<String> names) {
        return tagMapper.selectList(Wrappers.<MangaTag>lambdaQuery()
                        .isNotNull(MangaTag::getNamespace)
                        .in(MangaTag::getTagName, names))
                .stream()
                .collect(Collectors.groupingBy(t -> MangaTextUtil.normalizeNameKey(t.getTagName())))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> pickDictTag(e.getValue())));
    }

    /**
     * 目录标签（namespace 为 NULL）按名批量查。
     * <p>键统一归一化成大写：库里存的是大写形式，而 MySQL 的 {@code utf8mb4_0900_ai_ci}
     * 大小写不敏感，查得回来的行大小写未必与入参一致，不归一就会取不到。
     *
     * @return 归一化名 → 标签
     */
    private Map<String, MangaTag> selectFolderByName(Set<String> names) {
        return tagMapper.selectList(Wrappers.<MangaTag>lambdaQuery()
                        .isNull(MangaTag::getNamespace)
                        .in(MangaTag::getTagName, names))
                .stream()
                .collect(Collectors.toMap(t -> MangaTextUtil.normalizeNameKey(t.getTagName()),
                        t -> t, (a, b) -> a));
    }

    /**
     * 从一批同名的词典标签里挑优先级最高的那条，见 {@link MangaTagNamespace}。
     * <p>公开且静态是为了能不连库单测：挑哪一条必须是确定的，同一个目录名两次同步
     * 挑出不同标签的话，表现为标签自己会变。
     *
     * @return 优先级最高的一条；入参为空时返回 {@code null}
     */
    public static MangaTag pickDictTag(Collection<MangaTag> candidates) {
        return candidates == null ? null : candidates.stream()
                .min(Comparator.comparingInt(t -> MangaTagNamespace.rank(t.getNamespace())))
                .orElse(null);
    }

    /**
     * 把某个目标的标签整组替换成 {@code tagNames}：先删该目标现有关联，再按新集合插入。
     * <p>标签本身不删 —— 一个标签可能还挂在别的目标上，且重复使用的标签
     * 删了又建会让 id 漂移。
     *
     * @return 实际关联的标签数
     */
    public synchronized int replaceRefs(MangaTagTargetType targetType, Long targetId,
                                        Collection<String> tagNames) {
        deleteRefs(targetType, targetId);

        Map<String, MangaTag> tags = resolveOrCreate(tagNames);
        for (MangaTag tag : tags.values()) {
            MangaTagRef ref = new MangaTagRef();
            ref.setTagId(tag.getId());
            ref.setTargetType(targetType);
            ref.setTargetId(targetId);
            refMapper.insert(ref);
        }
        return tags.size();
    }

    /**
     * 按 (namespace, tag_name) 精确替换某目标的标签关联。
     * <p>与 {@link #replaceRefs} 的「词典优先按名解析」相反：这里 namespace 由入参给出，
     * 同名但不同 namespace 的标签是两条、可以并存。漫画独立标签（MANGA_DATA）走这条路 ——
     * 它在库里能精确区分，不像归档目录名只有一个中文词、必须词典优先挑一条。
     *
     * @return 实际关联的标签数
     */
    public synchronized int replaceRefsExact(MangaTagTargetType targetType, Long targetId,
                                             Collection<TagItem> items) {
        deleteRefs(targetType, targetId);

        Map<String, MangaTag> resolved = new LinkedHashMap<>();
        if (items != null) {
            for (TagItem item : items) {
                String name = MangaTextUtil.nfc(StringUtils.trimToNull(item.tagName()));
                if (name == null) {
                    continue;
                }
                String ns = StringUtils.trimToNull(item.namespace());
                MangaTag tag = resolveExact(ns, name);
                // 去重键按 (namespace, 大写名)，与 resolveOrCreate 的「塌成一条」同思路
                String key = (ns == null ? "" : ns) + " " + name.toUpperCase();
                resolved.put(key, tag);
            }
        }
        for (MangaTag tag : resolved.values()) {
            MangaTagRef ref = new MangaTagRef();
            ref.setTagId(tag.getId());
            ref.setTargetType(targetType);
            ref.setTargetId(targetId);
            refMapper.insert(ref);
        }
        return resolved.size();
    }

    /**
     * 按 (namespace, tag_name) 精确取标签，库中没有的当场新建。
     * <p>namespace 为空即目录标签（无命名空间），新建时沿用大写约定；非空即词典标签，
     * 中文名原样存。不做同名跨 namespace 的择优。
     */
    private MangaTag resolveExact(String namespace, String tagName) {
        String name = MangaTextUtil.nfc(tagName);
        MangaTag existing = tagMapper.selectOne(Wrappers.<MangaTag>lambdaQuery()
                .eq(MangaTag::getTagName, name)
                .eq(namespace != null, MangaTag::getNamespace, namespace)
                .isNull(namespace == null, MangaTag::getNamespace));
        if (existing != null) {
            return existing;
        }
        MangaTag tag = new MangaTag();
        tag.setTagName(name.toUpperCase());
        tag.setNamespace(namespace);
        tagMapper.insert(tag);
        return tag;
    }

    /**
     * 删除某个目标的全部标签关联，标签本身保留（可能还挂在别的目标上）。
     * <p>目标本身被删除时调用，例如归档目录合并后被并掉的那个 unit。
     *
     * @return 删除的关联数
     */
    public synchronized int deleteRefs(MangaTagTargetType targetType, Long targetId) {
        if (targetType == null || targetId == null) {
            throw new IllegalArgumentException("targetType、targetId 不能为空");
        }
        return refMapper.delete(Wrappers.<MangaTagRef>lambdaQuery()
                .eq(MangaTagRef::getTargetType, targetType)
                .eq(MangaTagRef::getTargetId, targetId));
    }

    /** 某个目标的标签名 */
    public List<String> listTagNames(MangaTagTargetType targetType, Long targetId) {
        return listTagNamesBatch(targetType, List.of(targetId))
                .getOrDefault(targetId, List.of());
    }

    /**
     * 批量取标签名，避免逐个目标查一次。
     *
     * @return targetId → 标签名列表；无标签的目标不出现在结果中
     */
    public Map<Long, List<String>> listTagNamesBatch(MangaTagTargetType targetType,
                                                     Collection<Long> targetIds) {
        if (targetType == null || targetIds == null || targetIds.isEmpty()) {
            return Map.of();
        }
        List<MangaTagRef> refs = refMapper.selectList(Wrappers.<MangaTagRef>lambdaQuery()
                .eq(MangaTagRef::getTargetType, targetType)
                .in(MangaTagRef::getTargetId, targetIds));
        if (refs.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> tagNameById = tagMapper.selectList(Wrappers.<MangaTag>lambdaQuery()
                        .in(MangaTag::getId, refs.stream().map(MangaTagRef::getTagId)
                                .collect(Collectors.toSet())))
                .stream()
                .collect(Collectors.toMap(MangaTag::getId, MangaTag::getTagName));

        Map<Long, List<String>> result = new LinkedHashMap<>();
        for (MangaTagRef ref : refs) {
            String name = tagNameById.get(ref.getTagId());
            if (name != null) {
                result.computeIfAbsent(ref.getTargetId(), k -> new ArrayList<>()).add(name);
            }
        }
        result.values().forEach(Collections::sort);
        return result;
    }

    /**
     * 带命名空间的标签，漫画独立标签（MANGA_DATA）的精确身份。
     * <p>namespace 为空 = 目录标签（无命名空间）。与 {@link #listTagNames} 只给名字不同，
     * 这里把 namespace 一并带出，让前端能按 (namespace, name) 精确回显与提交。
     */
    public record TagItem(String namespace, String tagName) {
    }

    /** 某个目标的标签（带命名空间），见 {@link TagItem} */
    public List<TagItem> listTags(MangaTagTargetType targetType, Long targetId) {
        return listTagsBatch(targetType, List.of(targetId)).getOrDefault(targetId, List.of());
    }

    /**
     * 批量取标签（带命名空间），避免逐个目标查一次。
     *
     * @return targetId → 标签列表；无标签的目标不出现在结果中
     */
    public Map<Long, List<TagItem>> listTagsBatch(MangaTagTargetType targetType,
                                                  Collection<Long> targetIds) {
        if (targetType == null || targetIds == null || targetIds.isEmpty()) {
            return Map.of();
        }
        List<MangaTagRef> refs = refMapper.selectList(Wrappers.<MangaTagRef>lambdaQuery()
                .eq(MangaTagRef::getTargetType, targetType)
                .in(MangaTagRef::getTargetId, targetIds));
        if (refs.isEmpty()) {
            return Map.of();
        }
        Map<Long, MangaTag> tagById = tagMapper.selectList(Wrappers.<MangaTag>lambdaQuery()
                        .in(MangaTag::getId, refs.stream().map(MangaTagRef::getTagId)
                                .collect(Collectors.toSet())))
                .stream()
                .collect(Collectors.toMap(MangaTag::getId, t -> t));

        Map<Long, List<TagItem>> result = new LinkedHashMap<>();
        for (MangaTagRef ref : refs) {
            MangaTag tag = tagById.get(ref.getTagId());
            if (tag != null) {
                result.computeIfAbsent(ref.getTargetId(), k -> new ArrayList<>())
                        .add(new TagItem(tag.getNamespace(), tag.getTagName()));
            }
        }
        result.values().forEach(list -> list.sort(
                Comparator.comparing(TagItem::tagName, Comparator.nullsLast(String::compareTo))));
        return result;
    }

    /**
     * 打了某个标签的目标 id。
     *
     * <p>这里<b>不</b>照 {@code resolveOrCreate} 那样「词典优先取一条」，而是把同名的
     * 词典标签与目录标签的关联<b>并起来</b>：本方法是反查，问的是「打了这个名字的
     * 目标有哪些」，而同一个名字在库里可能有两条行（词典一条、历史遗留的目录标签一条），
     * 关联散在两边。
     *
     * <p>只取词典那条会漏：{@code MangaArchiveService.planTagRewrite} 靠本方法算
     * 「改这个标签要动哪些目录」，词典标签在迁移完成前关联为空，清单会算成空，
     * 于是标签改名只改了库、没改目录名，下次同步旧标签原样冒回来 —— 那是最难查的一类现象。
     */
    public List<Long> listTargetIds(MangaTagTargetType targetType, String tagName) {
        String name = MangaTextUtil.nfc(StringUtils.trimToNull(tagName));
        if (targetType == null || name == null) {
            return List.of();
        }
        Set<Long> tagIds = tagMapper.selectList(Wrappers.<MangaTag>lambdaQuery()
                        .eq(MangaTag::getTagName, name))
                .stream()
                .map(MangaTag::getId)
                .collect(Collectors.toSet());
        if (tagIds.isEmpty()) {
            return List.of();
        }
        return refMapper.selectList(Wrappers.<MangaTagRef>lambdaQuery()
                        .eq(MangaTagRef::getTargetType, targetType)
                        .in(MangaTagRef::getTagId, tagIds))
                .stream()
                .map(MangaTagRef::getTargetId)
                .distinct()
                .toList();
    }

    /** 全部标签，按名排序，供管理页展示 */
    public List<MangaTag> listAll() {
        return tagMapper.selectList(Wrappers.<MangaTag>lambdaQuery()
                .orderByAsc(MangaTag::getTagName));
    }

    // ------------------------------------------------------------------
    // 管理页用的增删改。改目录名那一层在 MangaTagAdminService，本类只动库
    // ------------------------------------------------------------------

    /**
     * 一个标签及其被用在哪儿。
     * <p>按 {@code targetType} 分开计数而不是给个总数：归档目录的标签来自目录名、
     * 改动要连带改目录（见 {@code MangaTagAdminService}），漫画的标签只在库里，
     * 两者的处置方式不同，合并成一个数字就分不出来了。
     */
    public record TagUsage(MangaTag tag, long archiveUnitCount, long mangaCount) {

        /** 零引用的标签：{@code replaceRefs} 只删关联不删标签，用久了会攒下一批 */
        public boolean unused() {
            return archiveUnitCount == 0 && mangaCount == 0;
        }
    }

    /** 全部标签及用量，一次查完，不逐个标签查一遍关联表 */
    public List<TagUsage> listUsage() {
        Map<Long, Map<MangaTagTargetType, Long>> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : refMapper.selectMaps(Wrappers.<MangaTagRef>query()
                .select("tag_id", "target_type", "count(*) as c")
                .groupBy("tag_id", "target_type"))) {
            Object tagId = row.get("tag_id");
            Object type = row.get("target_type");
            Object count = row.get("c");
            if (tagId == null || type == null || count == null) {
                continue;
            }
            counts.computeIfAbsent(((Number) tagId).longValue(), k -> new EnumMap<>(MangaTagTargetType.class))
                    .put(MangaTagTargetType.valueOf(type.toString()), ((Number) count).longValue());
        }
        return listAll().stream()
                .map(tag -> {
                    Map<MangaTagTargetType, Long> byType =
                            counts.getOrDefault(tag.getId(), Map.of());
                    return new TagUsage(tag,
                            byType.getOrDefault(MangaTagTargetType.ARCHIVE_UNIT, 0L),
                            byType.getOrDefault(MangaTagTargetType.MANGA_DATA, 0L));
                })
                .toList();
    }

    /**
     * 建一个还没挂给任何人的标签。
     * <p>用处不大 —— 归档目录的标签是扫目录名扫出来的，这里建的标签在被某个目录名
     * 用到之前一直是「未使用」。留这个入口是为了先建后挂时能填命名空间、大类与描述。
     * 大类与描述本是词典标签的字段，目录标签通常留空，这里允许手工补上作归类参考。
     */
    public synchronized MangaTag create(String tagName, String remark, String namespace,
                                        String majorCategory, String description) {
        String name = requireTagName(tagName);
        if (findByName(name) != null) {
            throw new IllegalArgumentException("标签「" + name + "」已存在");
        }
        MangaTag tag = new MangaTag();
        tag.setTagName(name);
        tag.setRemark(StringUtils.trimToNull(remark));
        tag.setNamespace(StringUtils.trimToNull(namespace));
        tag.setMajorCategory(StringUtils.trimToNull(majorCategory));
        tag.setDescription(StringUtils.trimToNull(description));
        tagMapper.insert(tag);
        return tag;
    }

    /**
     * 改标签的元数据（命名空间 / 大类 / 描述 / 备注），不动标签名与来源。
     * <p>来源（{@code source}：FOLDER 目录 / EHENTAI 词典）标识这个标签是怎么来的，
     * 手工改元数据不该把它改掉 —— 否则一次修改就把词典标签「改」成了目录标签，
     * 「清理未使用」按 {@code source} 跳过词典标签的判据也一并失灵。标签名改名走
     * {@link MangaTagAdminService}，因为那要连带改目录名。
     */
    public synchronized void updateMeta(Long tagId, String namespace, String majorCategory,
                                        String description, String remark) {
        MangaTag tag = require(tagId);
        tag.setNamespace(StringUtils.trimToNull(namespace));
        tag.setMajorCategory(StringUtils.trimToNull(majorCategory));
        tag.setDescription(StringUtils.trimToNull(description));
        tag.setRemark(StringUtils.trimToNull(remark));
        tagMapper.updateById(tag);
    }

    /** 只改标签名，不动目录名 —— 单独调它会让库与目录名漂移，见 {@code MangaTagAdminService} */
    public synchronized void renameRow(Long tagId, String tagName) {
        MangaTag tag = require(tagId);
        String name = requireTagName(tagName);
        MangaTag exists = findByName(name);
        if (exists != null && !exists.getId().equals(tagId)) {
            throw new IllegalArgumentException("标签「" + name + "」已存在，这属于合并");
        }
        tag.setTagName(name);
        tagMapper.updateById(tag);
    }

    /**
     * 把 {@code fromTagId} 的关联改指向 {@code toTagId}，目标已有的关联跳过（唯一键）。
     *
     * @return 改指向的关联数
     */
    public synchronized int repointRefs(Long fromTagId, Long toTagId) {
        if (fromTagId == null || toTagId == null || fromTagId.equals(toTagId)) {
            throw new IllegalArgumentException("源标签与目标标签不能相同");
        }
        Set<String> existing = refMapper.selectList(Wrappers.<MangaTagRef>lambdaQuery()
                        .eq(MangaTagRef::getTagId, toTagId))
                .stream()
                .map(r -> r.getTargetType() + "#" + r.getTargetId())
                .collect(Collectors.toSet());
        int moved = 0;
        for (MangaTagRef ref : refMapper.selectList(Wrappers.<MangaTagRef>lambdaQuery()
                .eq(MangaTagRef::getTagId, fromTagId))) {
            if (existing.contains(ref.getTargetType() + "#" + ref.getTargetId())) {
                refMapper.deleteById(ref.getId());
                continue;
            }
            ref.setTagId(toTagId);
            refMapper.updateById(ref);
            moved++;
        }
        return moved;
    }

    /** 删标签本身及其全部关联 */
    public synchronized void deleteTag(Long tagId) {
        MangaTag tag = require(tagId);
        refMapper.delete(Wrappers.<MangaTagRef>lambdaQuery().eq(MangaTagRef::getTagId, tag.getId()));
        tagMapper.deleteById(tag.getId());
    }

    /**
     * 清理零引用的标签。
     * <p>{@link #replaceRefs} 只删关联不删标签（标签可能还挂在别的目标上），
     * 所以改目录名改久了会攒下一批没人用的行。
     * <p>e-hentai 词典导入的标签（{@link MangaTag#SOURCE_EHENTAI}）本来就是零引用的
     * 参考条目，跳过不删 —— 否则点一次「清理未使用」就把整本词典清空了。
     *
     * @return 删掉的标签数
     */
    public synchronized int deleteUnused() {
        List<MangaTag> unused = listUsage().stream()
                .filter(TagUsage::unused)
                .filter(t -> !MangaTag.SOURCE_EHENTAI.equals(t.tag().getSource()))
                .map(TagUsage::tag)
                .toList();
        unused.forEach(tag -> tagMapper.deleteById(tag.getId()));
        return unused.size();
    }

    public MangaTag require(Long tagId) {
        MangaTag tag = tagId == null ? null : tagMapper.selectById(tagId);
        if (tag == null) {
            throw new IllegalArgumentException("标签不存在: " + tagId);
        }
        return tag;
    }

    /**
     * 按名找标签，词典优先、目录标签兜底（见类注释）。
     * <p>注意由此带来的语义变化：词典里有的名字，{@link #create} 会判为「已存在」、
     * {@code MangaTagAdminService.planRename} 会判为「这是合并」。这是有意的 ——
     * 词典里有对应中文的词，不该再单独建一条目录标签。
     */
    public MangaTag findByName(String tagName) {
        String name = MangaTextUtil.nfc(StringUtils.trimToNull(tagName));
        if (name == null) {
            return null;
        }
        MangaTag dict = pickDictTag(tagMapper.selectList(Wrappers.<MangaTag>lambdaQuery()
                .isNotNull(MangaTag::getNamespace)
                .eq(MangaTag::getTagName, name)));
        return dict != null ? dict : tagMapper.selectOne(Wrappers.<MangaTag>lambdaQuery()
                .isNull(MangaTag::getNamespace)
                .eq(MangaTag::getTagName, name));
    }

    /**
     * 标签名要能原样写进目录名的 {@code 【…】} 块里，所以空白与 {@code 【】}
     * 一概不许 —— 前者会被 {@code parseArchiveFolderName} 拆成两个标签，
     * 后者会把标签块提前截断。
     * <p>公开是为了让预演阶段就能校验：等到执行时才报错，用户已经看过一份
     * 拿不合法名字拼出来的目录名清单了。
     */
    public static String requireTagName(String tagName) {
        String name = MangaTextUtil.nfc(StringUtils.trimToNull(tagName));
        if (name == null) {
            throw new IllegalArgumentException("标签名不能为空");
        }
        if (name.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("标签名里不能有空格：目录名的【】块按空白拆标签");
        }
        if (StringUtils.containsAny(name, '【', '】')) {
            throw new IllegalArgumentException("标签名里不能有【】");
        }
        return name;
    }

    /** 去空白 + NFC 归一 + 去重（保序） */
    private static Set<String> normalize(Collection<String> tagNames) {
        if (tagNames == null) {
            return Set.of();
        }
        return tagNames.stream()
                .map(StringUtils::trimToNull)
                .filter(Objects::nonNull)
                .map(MangaTextUtil::nfc)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
