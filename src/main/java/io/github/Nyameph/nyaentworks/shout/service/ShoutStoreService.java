package io.github.Nyameph.nyaentworks.shout.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import io.github.Nyameph.nyaentworks.common.db.SqlDialect;
import io.github.Nyameph.nyaentworks.common.media.MediaExtensions;
import io.github.Nyameph.nyaentworks.manga.util.MangaTextUtil;
import io.github.Nyameph.nyaentworks.shout.consts.ShoutStatus;
import io.github.Nyameph.nyaentworks.shout.entity.ShoutFile;
import io.github.Nyameph.nyaentworks.shout.entity.ShoutGroup;
import io.github.Nyameph.nyaentworks.shout.entity.ShoutTag;
import io.github.Nyameph.nyaentworks.shout.mapper.ShoutFileMapper;
import io.github.Nyameph.nyaentworks.shout.mapper.ShoutGroupMapper;
import io.github.Nyameph.nyaentworks.shout.mapper.ShoutTagMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
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
 * 喊麦的库这一侧：归档镜像（{@code shout_group} / {@code shout_file}）的读与「跟着磁盘改」，
 * 加上设置表标签（{@code shout_tag}）。
 *
 * <p>镜像表由 {@link ShoutSyncService} 全量同步建立与校验；这里负责的是同步之外的那些动作 ——
 * 改名 / 换分区 / 删除之后立刻跟着改，不必等下一次同步。顺序永远是<b>磁盘先动、库后动</b>
 * （编排在 {@link ShoutArchiveService}）。
 *
 * <p>{@code default_rate} 存在 {@code shout_group} 行上（与歌曲同构）：它是这一层唯一
 * 「磁盘表达不出来」的东西，同步只建行、从不覆盖它，删除也只标 MISSING 不删行 —— 都是为它。
 *
 * <p><b>倍速只有一级</b>：组级 → 1.0。歌曲那边有「原曲名级」那一层回落，喊麦不解析文件名、
 * 没有原曲名可言，也就没有那两级。
 *
 * <p><b>播放中调倍速不写库</b>（同歌曲侧）：写库是独立端点，只有点「存为默认」才调 ——
 * 调倍速是高频试探动作，每次都存等于把默认值变成「最后一次随手拨到哪」。
 */
@Service
@RequiredArgsConstructor
public class ShoutStoreService {

    /** 都没设过时的倍速 */
    public static final BigDecimal DEFAULT_RATE = BigDecimal.ONE.setScale(2, RoundingMode.HALF_UP);

    /** 倍速的取值范围。浏览器 {@code playbackRate} 超出这个区间会静音或抛错 */
    private static final BigDecimal MIN_RATE = new BigDecimal("0.25");
    private static final BigDecimal MAX_RATE = new BigDecimal("4.00");

    private final ShoutGroupMapper groupMapper;
    private final ShoutFileMapper fileMapper;
    private final ShoutTagMapper tagMapper;

    // ------------------------------------------------------------------
    // 倍速
    // ------------------------------------------------------------------

    /** 一组现有倍速的键：{@code 分区|主名}（与 {@code ShoutGroup.key()} 同源） */
    public static String rateKey(String partitionName, String mainName) {
        return StringUtils.defaultString(partitionName) + "|" + mainName;
    }

    /**
     * 批量取已归档组的默认倍速，列表页一次查完。
     *
     * @return key = {@code 分区|主名} → 倍速；没存过的组不出现
     */
    public Map<String, BigDecimal> loadRates(Collection<ShoutGroupService.ShoutGroup> groups) {
        if (groups == null || groups.isEmpty()) {
            return Map.of();
        }
        Set<String> mainNames = new LinkedHashSet<>();
        for (ShoutGroupService.ShoutGroup group : groups) {
            if (StringUtils.isNotBlank(group.partitionName())) {
                mainNames.add(group.mainName());
            }
        }
        if (mainNames.isEmpty()) {
            // 待打分区的组没有分区，也就没有键 —— 它们的倍速一律 1.0
            return Map.of();
        }
        List<ShoutGroup> rows = groupMapper.selectList(Wrappers.<ShoutGroup>lambdaQuery()
                .in(ShoutGroup::getMainName, mainNames));
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (ShoutGroup row : rows) {
            if (row.getDefaultRate() != null) {
                result.put(rateKey(row.getPartitionName(), row.getMainName()), row.getDefaultRate());
            }
        }
        return result;
    }

    /**
     * 一组当前的倍速：组级 → 1.0。
     *
     * @param partitionName 待打分区的组传 {@code null}：那一层不入库，存不了组级倍速
     */
    public BigDecimal rateOf(String partitionName, String mainName) {
        if (StringUtils.isBlank(partitionName)) {
            return DEFAULT_RATE;
        }
        ShoutGroup row = findGroup(partitionName, mainName);
        return row == null || row.getDefaultRate() == null ? DEFAULT_RATE : row.getDefaultRate();
    }

    /**
     * 存组级默认倍速。{@code rate} 为 null 表示清掉（回落到 1.0）。
     * <p>定位到 {@code shout_group} 行写 {@code default_rate}。库里没有这一条（镜像未同步）
     * 时报错引导先同步 —— 静默丢掉会让人以为存上了。待打分区的组没有分区、不入库，直接报错。
     */
    public synchronized void saveRate(String partitionName, String mainName, BigDecimal rate) {
        if (StringUtils.isBlank(partitionName)) {
            throw new IllegalArgumentException(
                    "待打分区的喊麦还没有评分分区，存不了默认倍速。先打分归档再存");
        }
        if (StringUtils.isBlank(mainName)) {
            throw new IllegalArgumentException("主名不能为空");
        }
        ShoutGroup row = findGroup(partitionName, mainName);
        if (row == null) {
            throw new IllegalStateException(
                    "库里还没有这一组（镜像未同步）。先跑一次同步（启动或 POST /api/shout/sync）再存默认倍速");
        }
        // 清掉要显式 set(null)：updateById 的 NOT_NULL 策略会跳过 null，变成「接口报成功、值还在」
        groupMapper.update(null, Wrappers.<ShoutGroup>lambdaUpdate()
                .set(ShoutGroup::getDefaultRate, requireRate(rate))
                .set(ShoutGroup::getUpdateTime, LocalDateTime.now())
                .eq(ShoutGroup::getId, row.getId()));
    }

    /** 常用档位，供页面画按钮 —— 与前端各写一份会不一致 */
    public static List<BigDecimal> presetRates() {
        return List.of(new BigDecimal("1.00"), new BigDecimal("1.25"), new BigDecimal("1.60"));
    }

    private static BigDecimal requireRate(BigDecimal rate) {
        if (rate == null) {
            return null;
        }
        BigDecimal scaled = rate.setScale(2, RoundingMode.HALF_UP);
        if (scaled.compareTo(MIN_RATE) < 0 || scaled.compareTo(MAX_RATE) > 0) {
            throw new IllegalArgumentException("倍速要在 " + MIN_RATE + " 到 " + MAX_RATE
                    + " 之间，浏览器超出这个范围会静音");
        }
        return scaled;
    }

    // ------------------------------------------------------------------
    // 跟着磁盘改：一次提交（评分 + 改名）、删除
    // ------------------------------------------------------------------

    /**
     * 一次提交（评分 + 改名）后跟着改库：分区/评分列、{@code shout_file} 的主名与文件名、
     * 挂在主名上的标签，用 {@code id} 锚定同一行一次改完。
     *
     * <p><b>不能串成「先改分区再改名」</b>：前者把分区从 {@code fromPartition} 改成
     * {@code toPartition}，后者再按旧分区 + 旧主名定位就查不到这一行了（歌曲侧同款坑）。
     *
     * <p>{@code score} 为 {@code null} 表示没改评分（只改名）；{@code newMainName} 与
     * {@code mainName} 相同表示没改名（只评分）。
     *
     * <p>库里无行（待打分区首归档：镜像只归档入库）→ {@code false} 交给下次同步补建 ——
     * 与歌曲侧行为一致，不在这里补 INSERT（那等于手写镜像表）。
     *
     * @return 是否真的改了一行
     */
    public synchronized boolean editGroup(String fromPartition, String mainName,
                                          String toPartition, Integer score, String newMainName) {
        ShoutGroup row = findGroup(fromPartition, mainName);
        if (row == null) {
            return false;
        }
        boolean renamed = !StringUtils.equals(mainName, newMainName);
        if (renamed) {
            // shout_file：**只换 main_name** —— 后缀是独立一列、改名不动它
            // （GroupFileOps.planMoves 保证「扩展名不变，只换目录与主名」）
            List<ShoutFile> files = fileMapper.selectList(Wrappers.<ShoutFile>lambdaQuery()
                    .eq(ShoutFile::getShoutId, row.getId())
                    .eq(ShoutFile::getMainName, mainName));
            for (ShoutFile f : files) {
                fileMapper.update(null, Wrappers.<ShoutFile>lambdaUpdate()
                        .set(ShoutFile::getMainName, newMainName)
                        .set(ShoutFile::getUpdateTime, LocalDateTime.now())
                        .eq(ShoutFile::getId, f.getId()));
            }
            // 标签挂主名、跨分区共用：按旧主名一次全改
            tagMapper.update(null, Wrappers.<ShoutTag>lambdaUpdate()
                    .set(ShoutTag::getMainName, newMainName)
                    .eq(ShoutTag::getMainName, mainName));
        }
        if (!renamed && score == null) {
            return false;
        }
        LambdaUpdateWrapper<ShoutGroup> update = Wrappers.<ShoutGroup>lambdaUpdate()
                .set(ShoutGroup::getUpdateTime, LocalDateTime.now())
                .eq(ShoutGroup::getId, row.getId());
        if (renamed) {
            update.set(ShoutGroup::getMainName, newMainName);
        }
        if (score != null) {
            update.set(ShoutGroup::getPartitionName, toPartition)
                    .set(ShoutGroup::getScore, score)
                    .set(ShoutGroup::getStatus, ShoutStatus.ACTIVE);
        }
        groupMapper.update(null, update);
        return true;
    }

    /**
     * 删组：{@code shout_file} 行清掉，{@code shout_group} 标 {@code MISSING}
     * （保 {@code default_rate} —— 文件可能只是被临时移走，下次同步出现时会被 upsert 回
     * ACTIVE 并接上原来的倍速）。
     * <p>标签是磁盘表达不出来的设置，删文件不影响它，所以保留（同歌曲侧）。
     *
     * @return 是否真的动了一行
     */
    public synchronized boolean deleteGroup(String partitionName, String mainName) {
        ShoutGroup row = findGroup(partitionName, mainName);
        if (row == null) {
            return false;
        }
        fileMapper.delete(Wrappers.<ShoutFile>lambdaQuery()
                .eq(ShoutFile::getShoutId, row.getId()));
        groupMapper.update(null, Wrappers.<ShoutGroup>lambdaUpdate()
                .set(ShoutGroup::getStatus, ShoutStatus.MISSING)
                .set(ShoutGroup::getUpdateTime, LocalDateTime.now())
                .eq(ShoutGroup::getId, row.getId()));
        return true;
    }

    /**
     * 「添加文件」里剔除一个已归档文件的库侧收尾：把这一行标成<b>冗余</b> ——
     * {@code shout_id = 0}（不属于任何组；同步按 {@code shout_id} 逐组 diff，永远碰不到 0，
     * 所以冗余行不会被清掉）、{@code main_name} 换成落进冗余文件夹后的实际名
     * （可能带 {@code _冗余N} 后缀，规避同名与唯一键撞车）。
     *
     * <p>库里没有这一条（未同步）→ 返回 false：磁盘已经动了，行交给同步按现状收敛。
     *
     * <p>与歌曲侧 {@code SongSettingService#markFileRedundant} 逐行同构。
     *
     * @param oldFileName 剔除前的完整文件名（定位行用）
     * @param newFileName 移入冗余文件夹后的完整文件名
     * @return 是否真的改了一行
     */
    public synchronized boolean markFileRedundant(String partitionName, String mainName,
                                                  String oldFileName, String newFileName) {
        ShoutGroup row = findGroup(partitionName, mainName);
        if (row == null) {
            return false;
        }
        String oldMain = MediaExtensions.mainName(oldFileName);
        String suffix = MediaExtensions.suffix(oldFileName, oldMain);
        ShoutFile file = fileMapper.selectOne(Wrappers.<ShoutFile>lambdaQuery()
                .eq(ShoutFile::getShoutId, row.getId())
                .eq(ShoutFile::getMainName, oldMain)
                .eq(ShoutFile::getSuffix, suffix)
                .last("limit 1"));
        if (file == null) {
            return false;
        }
        fileMapper.update(null, Wrappers.<ShoutFile>lambdaUpdate()
                .set(ShoutFile::getShoutId, 0L)
                .set(ShoutFile::getMainName, MediaExtensions.mainName(newFileName))
                .set(ShoutFile::getUpdateTime, LocalDateTime.now())
                .eq(ShoutFile::getId, file.getId()));
        return true;
    }

    // ------------------------------------------------------------------
    // 冗余文件（shout_id = 0 的行）
    // ------------------------------------------------------------------

    /** 冗余文件的一行 */
    public record RedundantFileRow(long id, String fileName, boolean onDisk) {
    }

    /** 分页结果 */
    public record RedundantPage(long total, int page, int size, List<RedundantFileRow> items) {
    }

    /**
     * 冗余文件分页列表：{@code shout_id = 0} 的 {@code shout_file} 行。
     * 只读展示（处置去资源管理器，来龙去脉查操作记录页），onDisk = 冗余文件夹里还在不在。
     * <p>与歌曲侧 {@code SongSettingService#listRedundantFiles} 逐行同构。
     */
    public RedundantPage listRedundantFiles(int page, int size, java.nio.file.Path redundantDir) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 200);
        var q = Wrappers.<ShoutFile>lambdaQuery()
                .eq(ShoutFile::getShoutId, 0L)
                .orderByDesc(ShoutFile::getId);
        long total = fileMapper.selectCount(q);
        long offset = (long) (safePage - 1) * safeSize;
        List<ShoutFile> rows = fileMapper.selectList(q.last(SqlDialect.limit(offset, safeSize)));
        List<RedundantFileRow> items = rows.stream()
                .map(r -> new RedundantFileRow(r.getId(), r.fullName(),
                        redundantDir != null
                                && java.nio.file.Files.exists(redundantDir.resolve(r.fullName()))))
                .toList();
        return new RedundantPage(total, safePage, safeSize, items);
    }

    /** 按唯一键 {@code (分区, 主名)} 定位镜像行 */
    private ShoutGroup findGroup(String partitionName, String mainName) {
        if (StringUtils.isBlank(partitionName) || StringUtils.isBlank(mainName)) {
            return null;
        }
        return groupMapper.selectOne(Wrappers.<ShoutGroup>lambdaQuery()
                .eq(ShoutGroup::getPartitionName, partitionName)
                .eq(ShoutGroup::getMainName, mainName)
                .last("limit 1"));
    }

    // ------------------------------------------------------------------
    // 标签
    // ------------------------------------------------------------------

    /** 某一组的标签名，按名排序 */
    public List<String> listTags(String mainName) {
        return listTagsBatch(List.of(mainName)).getOrDefault(mainName, List.of());
    }

    /**
     * 批量取标签名，避免逐条查一遍。
     *
     * @return 主名 → 标签名列表；没有标签的组不出现在结果中
     */
    public Map<String, List<String>> listTagsBatch(Collection<String> mainNames) {
        if (mainNames == null || mainNames.isEmpty()) {
            return Map.of();
        }
        List<ShoutTag> tags = tagMapper.selectList(Wrappers.<ShoutTag>lambdaQuery()
                .in(ShoutTag::getMainName, mainNames)
                .orderByAsc(ShoutTag::getTagName));
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (ShoutTag tag : tags) {
            result.computeIfAbsent(tag.getMainName(), k -> new ArrayList<>()).add(tag.getTagName());
        }
        return result;
    }

    /**
     * 把一组的标签整组替换成 {@code tagNames}：先删现有行，再插新集合。
     * <p>传入空集合即清空（等于「不打标签」）。归档前**要不要**先有标签由
     * {@code nya-entworks.shout.require-tags-before-archive} 决定（出厂不拦），
     * 闸门在 {@link ShoutArchiveService}。
     *
     * @return 实际写入的标签数
     */
    public synchronized int replaceTags(String mainName, Collection<String> tagNames) {
        if (StringUtils.isBlank(mainName)) {
            throw new IllegalArgumentException("主名不能为空");
        }
        tagMapper.delete(Wrappers.<ShoutTag>lambdaQuery().eq(ShoutTag::getMainName, mainName));
        List<String> names = normalizeTags(tagNames);
        for (String name : names) {
            ShoutTag tag = new ShoutTag();
            tag.setMainName(mainName);
            tag.setTagName(name);
            tagMapper.insert(tag);
        }
        return names.size();
    }

    /** 全部标签名（去重），按名排序，供打标签时点选 */
    public List<String> listAllTags() {
        return tagMapper.selectList(Wrappers.<ShoutTag>query()
                        .select("tag_name")
                        .groupBy("tag_name")
                        .orderByAsc("tag_name"))
                .stream()
                .map(ShoutTag::getTagName)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 归一标签名。
     * <p>喊麦标签是 DB-only，没有漫画「要能写进目录名」的限制，但仍统一走
     * {@link MangaTextUtil#nfc} 归一 —— 假名的分解形式会被唯一键的 {@code ai_ci}
     * 当成另一条，存下去就是重复标签。
     */
    private static List<String> normalizeTags(Collection<String> tagNames) {
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
