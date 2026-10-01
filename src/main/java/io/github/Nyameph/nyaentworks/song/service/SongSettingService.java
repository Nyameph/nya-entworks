package io.github.Nyameph.nyaentworks.song.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import io.github.Nyameph.nyaentworks.common.db.SqlDialect;
import io.github.Nyameph.nyaentworks.common.media.MediaExtensions;
import io.github.Nyameph.nyaentworks.song.consts.SongStatus;
import io.github.Nyameph.nyaentworks.song.entity.SongFile;
import io.github.Nyameph.nyaentworks.song.entity.SongGroup;
import io.github.Nyameph.nyaentworks.song.entity.SongOriginalSetting;
import io.github.Nyameph.nyaentworks.song.mapper.SongFileMapper;
import io.github.Nyameph.nyaentworks.song.mapper.SongGroupMapper;
import io.github.Nyameph.nyaentworks.song.mapper.SongOriginalSettingMapper;
import io.github.Nyameph.nyaentworks.song.util.SongName;
import io.github.Nyameph.nyaentworks.song.util.SongNameParser;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 默认倍速的读写（实现说明 5.9 / 文档 4.12）。
 *
 * <p><b>倍速三级回落</b>：{@code 当前 > 组 > 原曲名 > 1.0}。本类只负责后三级 ——
 * 「当前倍速」活在前端内存里，后端见不到也不该见到。
 *
 * <p><b>存储位置</b>：组级默认倍速落在 {@code song_group.default_rate}（合并条目级），
 * 原曲名级落在 {@code song_original_setting}。两张表都是
 * 「磁盘权威、库是镜像」的归档镜像，由 {@code SongSyncService} 同步；本类读写时按
 * {@code (partition, mergeKey)} 定位 {@code song_group} 行，再取/写对应列。
 * 待打分区的歌不入库，存不了组级倍速（只能存原曲名级）。
 *
 * <p><b>播放中调倍速不写库。</b>写库是独立端点（{@link #saveGroupRate} /
 * {@link #saveOriginalRate}），只有点「存为默认」才调。理由是调倍速是个高频的试探动作
 * （这句听不清降回 1.0、副歌又调上去），每次都存等于把默认值搞成「最后一次随手拨到哪」，
 * 而默认值本该是「这首歌适合的速度」这个判断。
 */
@Service
@RequiredArgsConstructor
public class SongSettingService {

    /** 都没设过时的倍速 */
    public static final BigDecimal DEFAULT_RATE = BigDecimal.ONE.setScale(2, RoundingMode.HALF_UP);

    /** 倍速的取值范围。浏览器 {@code playbackRate} 超出这个区间会静音或抛错 */
    private static final BigDecimal MIN_RATE = new BigDecimal("0.25");
    private static final BigDecimal MAX_RATE = new BigDecimal("4.00");

    private final SongGroupMapper songGroupMapper;
    private final SongFileMapper songFileMapper;
    private final SongOriginalSettingMapper originalMapper;

    /**
     * 倍速取值结果。
     *
     * @param rate       最终倍速
     * @param source     来源，供控制条显示：{@code GROUP} / {@code ORIGINAL} / {@code DEFAULT}
     * @param originalId 命中原曲设置的代理主键；组级命中或没查到原曲时为 {@code null}。
     *                   前端存「原曲名默认倍速」时回传它，取代回传原曲名
     */
    public record RateResolution(BigDecimal rate, String source, Long originalId) {

        /** 页面显示用 */
        public String sourceLabel() {
            return switch (source) {
                case "GROUP" -> "组默认";
                case "ORIGINAL" -> "原曲名默认";
                default -> "默认 1.0";
            };
        }
    }

    /**
     * 一个合并条目的设置值：default_rate 来自 {@code song_group}。
     * 列表页 / 归并页批量装配共用。
     */
    public record GroupSetting(BigDecimal defaultRate) {
    }

    /**
     * 后三级回落取倍速。
     *
     * @param partitionName 待打分区的组传 {@code null} —— 那一层不入库，所以存不了组级倍速，
     *                      只能回落到原曲名级（文档 8.4 末段）
     * @param originalTitle 原曲名；解析不出时传 {@code null}
     * @param originalArtist 原曲作者（原唱）；旧格式文件名为 {@code null}，按同名原曲取第一个
     */
    public RateResolution resolveRate(String partitionName, String mainName,
                                     String originalTitle, String originalArtist) {
        if (StringUtils.isNotBlank(partitionName)) {
            SongGroup sg = findSongGroup(partitionName, mainName);
            if (sg != null && sg.getDefaultRate() != null) {
                return new RateResolution(sg.getDefaultRate(), "GROUP", null);
            }
        }
        SongOriginalSetting original = matchOriginal(allOriginals(), originalTitle, originalArtist);
        if (original != null && original.getDefaultRate() != null) {
            return new RateResolution(original.getDefaultRate(), "ORIGINAL", original.getId());
        }
        return new RateResolution(DEFAULT_RATE, "DEFAULT", original == null ? null : original.getId());
    }

    /**
     * 批量取组的设置，列表页 / 归并页一次查完。
     *
     * @param groups 现扫磁盘得到的组（含待打分区；待打分区跳过）
     * @return key = {@code group.key()}（{@code 分区|主名}）→ 设置值；库里没有的组不出现
     */
    public Map<String, GroupSetting> loadGroupSettings(List<SongGroupService.SongGroup> groups) {
        if (groups == null || groups.isEmpty()) {
            return Map.of();
        }
        // 待打分区的组不入库，没有设置行；归档组收集涉及的分区一次查完
        List<SongGroupService.SongGroup> archived = new ArrayList<>();
        Set<String> partitions = new HashSet<>();
        for (SongGroupService.SongGroup g : groups) {
            if (g.partitionName() != null) {
                archived.add(g);
                partitions.add(g.partitionName());
            }
        }
        if (archived.isEmpty()) {
            return Map.of();
        }

        // 一次全载 song_group：按 partition 过滤，内存按 partition|mergeKey 索引
        List<SongGroup> rows = songGroupMapper.selectList(Wrappers.<SongGroup>lambdaQuery()
                .in(SongGroup::getPartitionName, partitions));
        Map<String, SongGroup> byMergeKey = new HashMap<>();
        for (SongGroup row : rows) {
            byMergeKey.put(row.getPartitionName() + "|" + row.getMergeKey(), row);
        }

        // 组装：mergeKey 用现扫的解析结果算，与 SongSyncService 分桶完全同源
        Map<String, GroupSetting> result = new LinkedHashMap<>();
        for (SongGroupService.SongGroup g : archived) {
            String mergeKey = SongNameParser.mergeKey(g.name(), g.mainName());
            SongGroup row = byMergeKey.get(g.partitionName() + "|" + mergeKey);
            if (row == null) {
                continue; // 镜像还没同步到这一条，按「没设过」处理
            }
            result.put(g.key(), new GroupSetting(row.getDefaultRate()));
        }
        return result;
    }

    /**
     * 全部原曲名级设置，统计页 / 归并页一次查完，之后在内存里用 {@link #matchOriginal} 定位。
     *
     * <p>返回按 {@code id} 升序的<b>列表</b>（不再是按归一化键的 {@code Map}）—— 主键改为
     * 自增代理键后没有业务键可作 {@code Map} key，而「旧格式同名原曲取第一个」又需要一条
     * 稳定的顺序，故用 id 升序让「第一个」=「最早建的那条」。
     */
    public List<SongOriginalSetting> allOriginals() {
        return originalMapper.selectList(Wrappers.<SongOriginalSetting>lambdaQuery()
                .orderByAsc(SongOriginalSetting::getId));
    }

    /**
     * 在设置列表里按 {@code (原曲名, 原曲作者)} 定位一条设置。
     *
     * <p><b>匹配口径</b>（与需求 2 一致）：
     * <ul>
     *   <li>新格式（{@code originalArtist} 非空）：精确匹配 {@code 原曲名 + 原曲作者}，
     *       找不到就返回 {@code null}，<b>不借用其他作者的设置</b>；</li>
     *   <li>旧格式（{@code originalArtist} 为空）：匹配第一个同名原曲（id 最小）。</li>
     * </ul>
     *
     * <p>两端都走 {@link SongNameParser#originalKey} 归一化（NFC + trim + 大写），避免
     * 分解形式假名 / 大小写差异在 Java 内存比较里被当成两个不同的原曲。
     */
    public static SongOriginalSetting matchOriginal(List<SongOriginalSetting> rows,
                                                    String originalTitle, String originalArtist) {
        String titleKey = SongNameParser.originalKey(originalTitle);
        if (StringUtils.isBlank(titleKey)) {
            return null;
        }
        String artistKey = StringUtils.isBlank(originalArtist)
                ? null : SongNameParser.originalKey(originalArtist);
        for (SongOriginalSetting row : rows) {
            if (!titleKey.equals(SongNameParser.originalKey(row.getRawName()))) {
                continue;
            }
            if (artistKey == null) {
                return row;
            }
            if (artistKey.equals(SongNameParser.originalKey(row.getArtist()))) {
                return row;
            }
        }
        return null;
    }

    /**
     * 存组级默认倍速。{@code rate} 为 null 表示清掉（回落到原曲名级）。
     * <p>定位到 {@code song_group} 行写 {@code default_rate}。库里没有这一条
     * （镜像未同步）时报错引导先同步 —— 静默丢掉会让人以为存上了。
     * <p>待打分区的组没有评分分区，存不了 —— 直接报错而不是静默忽略，否则用户点了
     * 「存为默认」却什么也没发生。
     */
    public synchronized void saveGroupRate(String partitionName, String mainName,
                                           BigDecimal rate) {
        if (StringUtils.isBlank(partitionName)) {
            throw new IllegalArgumentException(
                    "待打分区的歌还没有评分分区，存不了组默认倍速。先打分归档，或改成存原曲名的默认倍速");
        }
        if (StringUtils.isBlank(mainName)) {
            throw new IllegalArgumentException("主名不能为空");
        }
        SongGroup sg = findSongGroup(partitionName, mainName);
        if (sg == null) {
            throw new IllegalStateException(
                    "库里还没有这一条（镜像未同步）。先跑一次同步（启动或 POST /api/song/sync）再存默认倍速");
        }
        // 清掉要显式 set(null)：updateById 的 NOT_NULL 策略会跳过 null，变成「接口报成功、值还在」
        songGroupMapper.update(null, Wrappers.<SongGroup>lambdaUpdate()
                .set(SongGroup::getDefaultRate, requireRate(rate))
                .set(SongGroup::getUpdateTime, LocalDateTime.now())
                .eq(SongGroup::getId, sg.getId()));
    }

    /** 存原曲名级默认倍速。{@code rate} 为 null 表示清掉。按代理主键 {@code originalId} 定位 */
    public synchronized void saveOriginalRate(Long originalId, BigDecimal rate) {
        if (originalId == null) {
            throw new IllegalArgumentException("原曲 id 不能为空");
        }
        SongOriginalSetting row = originalMapper.selectById(originalId);
        if (row == null) {
            throw new IllegalStateException("原曲设置不存在（id=" + originalId + "）。刷新列表后再试");
        }
        // 同 saveGroupRate：清掉要显式 set(null)
        originalMapper.update(null, Wrappers.<SongOriginalSetting>lambdaUpdate()
                .set(SongOriginalSetting::getDefaultRate, requireRate(rate))
                .set(SongOriginalSetting::getUpdateTime, LocalDateTime.now())
                .eq(SongOriginalSetting::getId, originalId));
    }

    /**
     * 确保某原曲（名 + 作者）在 {@code song_original_setting} 里有记录 —— 「有已归档歌曲的原曲
     * 都要有一条，没有则补建」（设计概述模块 3「原曲模板」）。已存在则原样返回；不存在则补一条
     * 空设置（仅 {@code rawName} + {@code artist}，倍速与模板字段都为 null）。
     * {@link SongStatService#listOriginals()} 统计页用到它，保证页面上每个有歌的原曲都能配
     * 默认倍速 / 编辑模板。
     *
     * <p>匹配口径同 {@link #matchOriginal}：新格式按 {@code 原曲名 + 原曲作者} 精确找，
     * 旧格式（作者为空）按同名原曲取第一个。
     */
    public synchronized SongOriginalSetting ensureOriginal(String originalTitle, String originalArtist) {
        List<SongOriginalSetting> rows = allOriginals();
        SongOriginalSetting row = matchOriginal(rows, originalTitle, originalArtist);
        if (row != null) {
            return row;
        }
        String title = StringUtils.trimToNull(originalTitle);
        if (title == null) {
            throw new IllegalArgumentException("原曲名不能为空");
        }
        row = new SongOriginalSetting();
        row.setRawName(title);
        row.setArtist(StringUtils.trimToNull(originalArtist));
        originalMapper.insert(row);
        return row;
    }

    /**
     * ②③ 剔除已归档文件的库侧收尾：把这一行标成<b>冗余</b> ——
     * {@code song_id = 0}（不属于任何组；同步按 song_id 逐组 diff，永远碰不到 0，
     * 所以冗余行不会被清掉）、{@code main_name} 换成落进冗余文件夹后的实际名
     * （可能带 {@code _冗余N} 后缀，规避同名与唯一键撞车）。
     *
     * <p>库里没有这一条（未同步）→ 返回 false：磁盘已经动了，行交给同步按现状收敛。
     *
     * @param oldFileName 剔除前的完整文件名（定位行用）
     * @param newFileName 移入冗余文件夹后的完整文件名
     * @return 是否真的改了一行
     */
    public synchronized boolean markFileRedundant(String partitionName, String mainName,
                                                  String oldFileName, String newFileName) {
        SongGroup sg = findSongGroup(partitionName, mainName);
        if (sg == null) {
            return false;
        }
        String oldMain = MediaExtensions.mainName(oldFileName);
        String suffix = MediaExtensions.suffix(oldFileName, oldMain);
        SongFile row = songFileMapper.selectOne(Wrappers.<SongFile>lambdaQuery()
                .eq(SongFile::getSongId, sg.getId())
                .eq(SongFile::getMainName, oldMain)
                .eq(SongFile::getSuffix, suffix)
                .last("limit 1"));
        if (row == null) {
            return false;
        }
        songFileMapper.update(null, Wrappers.<SongFile>lambdaUpdate()
                .set(SongFile::getSongId, 0L)
                .set(SongFile::getMainName, MediaExtensions.mainName(newFileName))
                .set(SongFile::getUpdateTime, LocalDateTime.now())
                .eq(SongFile::getId, row.getId()));
        return true;
    }

    /**
     * ① 添加歌曲从已归档分区选文件时的库侧防双条：这一行已经属于旧组，落盘后要么
     * <b>改挂</b>到目标组（目标组在库里有行）、要么<b>删行</b>交给同步在新位置补建 ——
     * 两种都保证老行不残留，否则同步会把老组标 MISSING、新组插新行，同一文件两条数据。
     *
     * @param fromPartition 源分区（定位旧组）
     * @param fromMainName  源主名（= 旧组的 main_name）
     * @param suffix        该文件的后缀（含点、原文大小写），同一主名多个文件靠它区分
     * @param toPartition   目标分区（未归档为 null/空）
     * @param toMainName    落盘后的新主名（未改名 = 旧主名）
     * @return 是否真的动了一行
     */
    public synchronized boolean relocateArchivedFile(String fromPartition, String fromMainName,
                                                     String suffix, String toPartition,
                                                     String toMainName) {
        SongGroup sg = findSongGroup(fromPartition, fromMainName);
        if (sg == null) {
            return false;
        }
        SongFile row = songFileMapper.selectOne(Wrappers.<SongFile>lambdaQuery()
                .eq(SongFile::getSongId, sg.getId())
                .eq(SongFile::getMainName, fromMainName)
                .eq(SongFile::getSuffix, suffix)
                .last("limit 1"));
        if (row == null) {
            return false;
        }
        SongGroup target = findSongGroup(toPartition, toMainName);
        if (target != null) {
            // 目标组已入库：改挂（排序列交下次同步对齐）
            songFileMapper.update(null, Wrappers.<SongFile>lambdaUpdate()
                    .set(SongFile::getSongId, target.getId())
                    .set(SongFile::getMainName, toMainName)
                    .set(SongFile::getUpdateTime, LocalDateTime.now())
                    .eq(SongFile::getId, row.getId()));
        } else {
            // 目标组没有行（迁回未归档 / 全新归档）：删行，新位置交同步补建
            songFileMapper.deleteById(row.getId());
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 冗余文件（song_id = 0 的行）
    // ------------------------------------------------------------------

    /** 冗余文件的一行 */
    public record RedundantFileRow(long id, String fileName, boolean onDisk) {
    }

    /** 分页结果 */
    public record RedundantPage(long total, int page, int size, List<RedundantFileRow> items) {
    }

    /**
     * 冗余文件分页列表：{@code song_id = 0} 的 {@code song_file} 行。
     * 只读展示（处置去资源管理器，来龙去脉查操作记录页），onDisk = 冗余文件夹里还在不在。
     */
    public RedundantPage listRedundantFiles(int page, int size, java.nio.file.Path redundantDir) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 200);
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SongFile> q =
                Wrappers.<SongFile>lambdaQuery()
                        .eq(SongFile::getSongId, 0L)
                        .orderByDesc(SongFile::getId);
        long total = songFileMapper.selectCount(q);
        long offset = (long) (safePage - 1) * safeSize;
        List<SongFile> rows = songFileMapper.selectList(q.last(SqlDialect.limit(offset, safeSize)));
        List<RedundantFileRow> items = rows.stream()
                .map(r -> new RedundantFileRow(r.getId(), r.fullName(),
                        redundantDir != null
                                && java.nio.file.Files.exists(redundantDir.resolve(r.fullName()))))
                .toList();
        return new RedundantPage(total, safePage, safeSize, items);
    }

    /**
     * 一个既有组的 {@code song_file} 清单，行序 = variant_sort → sort_order → id。
     *
     * <p><b>只用于「画」</b>（③ 修改弹窗的列表与行序）—— 搬动 / 改名的依据一律重新扫磁盘
     * （「画用库、做用盘」）。待打分区（{@code partitionName} 为空）在库里没有行，
     * 返回空列表 —— 这是<b>事实</b>，不是取舍：同步只遍历 {@code listArchived()}。
     * 这个读法的先例是 {@code CorpusService}（拿 {@code song_group.id} 去 {@code song_file}
     * 里取行）。
     */
    public List<SongFile> listGroupFiles(String partitionName, String mainName) {
        SongGroup sg = findSongGroup(partitionName, mainName);
        if (sg == null) {
            return List.of();
        }
        return songFileMapper.selectList(Wrappers.<SongFile>lambdaQuery()
                .eq(SongFile::getSongId, sg.getId())
                .orderByAsc(SongFile::getVariantSort)
                .orderByAsc(SongFile::getSortOrder)
                .orderByAsc(SongFile::getId));
    }

    /**
     * 打分（换分区）后：把 {@code song_group} 行的分区与评分原地改掉，id 稳定，
     * {@code default_rate} 保留；{@code song_file} 的文件名没变、song_id 也没变，无需动。
     *
     * <p>库里无行（待打分区首归档：镜像只归档入库）→ {@code false} 交给同步补建。
     *
     * @return 是否真的改了一行
     */
    public synchronized boolean moveSongGroupPartition(String fromPartition,
                                                       String mainName, String toPartition,
                                                       int score) {
        SongGroup sg = findSongGroup(fromPartition, mainName);
        if (sg == null) {
            return false;
        }
        songGroupMapper.update(null, Wrappers.<SongGroup>lambdaUpdate()
                .set(SongGroup::getPartitionName, toPartition)
                .set(SongGroup::getScore, score)
                .set(SongGroup::getStatus, SongStatus.ACTIVE)
                .set(SongGroup::getUpdateTime, LocalDateTime.now())
                .eq(SongGroup::getId, sg.getId()));
        return true;
    }

    /**
     * 改名后：{@code song_file} 的主名与文件名跟着换；
     * 若改名动了作者/曲名/原曲名（合并键变了），同步 {@code song_group} 的解析列。
     *
     * <p>库里无行（待打分区改名，未入库）→ {@code false} 交给同步。合并键撞上已有条目时
     * 由数据库唯一键 {@code uk_merge} 报错，不静默 —— 低频且显式，让用户看到冲突。
     *
     * @return 是否真的改了一行
     */
    public synchronized boolean renameSongGroup(String partitionName,
                                                String mainName, String newMainName) {
        SongGroup sg = findSongGroup(partitionName, mainName);
        if (sg == null) {
            return false;
        }
        // song_file：**只换 main_name** —— 后缀是独立一列、改名不动它
        // （GroupFileOps.planMoves 保证「扩展名不变，只换目录与主名」）。
        // 2026-09-23 之前这里要自己算「新主名 + 旧后缀」，名字的拼法因此有两份。
        List<SongFile> files = songFileMapper.selectList(Wrappers.<SongFile>lambdaQuery()
                .eq(SongFile::getSongId, sg.getId())
                .eq(SongFile::getMainName, mainName));
        for (SongFile f : files) {
            songFileMapper.update(null, Wrappers.<SongFile>lambdaUpdate()
                    .set(SongFile::getMainName, newMainName)
                    .set(SongFile::getUpdateTime, LocalDateTime.now())
                    .eq(SongFile::getId, f.getId()));
        }
        // song_group：改名若动了作者/曲名/原曲名，合并键变了，同步解析列
        String newMergeKey = mergeKeyOf(newMainName);
        if (!newMergeKey.equals(sg.getMergeKey())) {
            SongName newName = SongNameParser.parse(newMainName);
            songGroupMapper.update(null, Wrappers.<SongGroup>lambdaUpdate()
                    .set(SongGroup::getMergeKey, newMergeKey)
                    .set(SongGroup::getAuthor1, authorAt(newName, 0))
                    .set(SongGroup::getAuthor2, authorAt(newName, 1))
                    .set(SongGroup::getAuthor3, authorAt(newName, 2))
                    .set(SongGroup::getTitle, newName == null ? null : newName.title())
                    .set(SongGroup::getOriginalTitle, newName == null ? null
                            : (newName.originalExplicit() ? newName.originalTitle() : newName.title()))
                    .set(SongGroup::getUpdateTime, LocalDateTime.now())
                    .eq(SongGroup::getId, sg.getId()));
        }
        return true;
    }

    /**
     * 一次提交（评分 + 改名）后：分区/评分列、{@code song_file} 主名/文件名、合并键解析列
     * 一起改，一次定位、一次完成。
     *
     * <p>不能串成「先 {@link #moveSongGroupPartition} 再 {@link #renameSongGroup}」：前者会把
     * 分区从 {@code fromPartition} 改成 {@code toPartition}，后者再按旧分区 + 旧主名定位就查不到
     * 这一行了。所以必须单开一个方法，用 {@code id} 锚定同一行后分列更新。
     *
     * <p>{@code score} 为 {@code null} 表示没改评分，跳过评分列（只改名）；
     * {@code newMainName} 与 {@code mainName} 相同表示没改名，跳过改名部分（只评分）。
     * 库里无行（待打分区首归档）→ {@code false} 交给同步补建。
     */
    public synchronized boolean editSongGroup(String fromPartition,
                                              String mainName, String toPartition,
                                              Integer score, String newMainName) {
        SongGroup sg = findSongGroup(fromPartition, mainName);
        if (sg == null) {
            return false;
        }
        // 改名：song_file **只换 main_name**（后缀是独立一列、改名不动它，理由见 renameSongGroup）
        if (!newMainName.equals(mainName)) {
            List<SongFile> files = songFileMapper.selectList(Wrappers.<SongFile>lambdaQuery()
                    .eq(SongFile::getSongId, sg.getId())
                    .eq(SongFile::getMainName, mainName));
            for (SongFile f : files) {
                songFileMapper.update(null, Wrappers.<SongFile>lambdaUpdate()
                        .set(SongFile::getMainName, newMainName)
                        .set(SongFile::getUpdateTime, LocalDateTime.now())
                        .eq(SongFile::getId, f.getId()));
            }
        }
        // 评分：分区/评分列 + 状态 ACTIVE（default_rate 保留）
        if (score != null) {
            songGroupMapper.update(null, Wrappers.<SongGroup>lambdaUpdate()
                    .set(SongGroup::getPartitionName, toPartition)
                    .set(SongGroup::getScore, score)
                    .set(SongGroup::getStatus, SongStatus.ACTIVE)
                    .set(SongGroup::getUpdateTime, LocalDateTime.now())
                    .eq(SongGroup::getId, sg.getId()));
        }
        // 改名动了作者/曲名/原曲名 → 合并键变了，同步解析列
        if (!newMainName.equals(mainName)) {
            String newMergeKey = mergeKeyOf(newMainName);
            if (!newMergeKey.equals(sg.getMergeKey())) {
                SongName newName = SongNameParser.parse(newMainName);
                songGroupMapper.update(null, Wrappers.<SongGroup>lambdaUpdate()
                        .set(SongGroup::getMergeKey, newMergeKey)
                        .set(SongGroup::getAuthor1, authorAt(newName, 0))
                        .set(SongGroup::getAuthor2, authorAt(newName, 1))
                        .set(SongGroup::getAuthor3, authorAt(newName, 2))
                        .set(SongGroup::getTitle, newName == null ? null : newName.title())
                        .set(SongGroup::getOriginalTitle, newName == null ? null
                                : (newName.originalExplicit() ? newName.originalTitle() : newName.title()))
                        .set(SongGroup::getUpdateTime, LocalDateTime.now())
                        .eq(SongGroup::getId, sg.getId()));
            }
        }
        return true;
    }

    /**
     * 删除后：{@code song_file} 行清掉，{@code song_group} 标 {@code MISSING}（保
     * {@code default_rate} —— 目录可能只是被临时移走，下次同步出现时会被 upsert 回
     * ACTIVE 并接上原来的倍速）。
     *
     * <p>批量删除只需对主 variant 调一次：{@code song_file} 按 {@code song_id} 全删，
     * 本就覆盖同一行（mergeKey 去版本号）的所有 variant。
     *
     * @return 是否真的动了一行
     */
    public synchronized boolean deleteSongGroup(String partitionName, String mainName) {
        SongGroup sg = findSongGroup(partitionName, mainName);
        if (sg == null) {
            return false;
        }
        songFileMapper.delete(Wrappers.<SongFile>lambdaQuery()
                .eq(SongFile::getSongId, sg.getId()));
        songGroupMapper.update(null, Wrappers.<SongGroup>lambdaUpdate()
                .set(SongGroup::getStatus, SongStatus.MISSING)
                .set(SongGroup::getUpdateTime, LocalDateTime.now())
                .eq(SongGroup::getId, sg.getId()));
        return true;
    }

    /**
     * 批量一次提交（评分 + 改名）：merged row 的所有 variant 一起改。
     *
     * <p>所有 variant 共享一个 {@code song_group} 行（mergeKey 去版本号），但
     * {@code song_file} 按 {@code main_name} 区分 —— 所以<b>改名要逐个 variant 更新
     * song_file</b>，而 {@code song_group} 的评分列与 mergeKey 只改一次。不能串成
     * 「循环调 {@link #editSongGroup}」：第一个 variant 改掉 mergeKey/分区后，第二个
     * variant 按旧定位就查不到行。
     *
     * <p>{@code score} 为 {@code null} 表示没改评分（只改名）；{@code newMainNames[i]}
     * 与 {@code mainNames[i]} 相同表示该 variant 没改名。库里无行（待打分区首归档）
     * → {@code false} 交给同步补建。
     *
     * @param fromPartition 主 variant 的旧分区（所有 variant 通常同分区；分散的历史数据
     *                      只按主 variant 定位，其余行交给同步修正）
     * @return 是否真的动了一行
     */
    public synchronized boolean editSongGroupBatch(String fromPartition,
                                                   List<String> mainNames, List<String> newMainNames,
                                                   String toPartition, Integer score) {
        if (mainNames == null || mainNames.isEmpty()) {
            return false;
        }
        SongGroup sg = findSongGroup(fromPartition, mainNames.get(0));
        if (sg == null) {
            return false;
        }
        // 改名：逐个 variant **只换 main_name**（后缀是独立一列、改名不动它，理由见 renameSongGroup）
        for (int i = 0; i < mainNames.size(); i++) {
            String mainName = mainNames.get(i);
            String newMainName = newMainNames.get(i);
            if (newMainName.equals(mainName)) {
                continue;
            }
            List<SongFile> files = songFileMapper.selectList(Wrappers.<SongFile>lambdaQuery()
                    .eq(SongFile::getSongId, sg.getId())
                    .eq(SongFile::getMainName, mainName));
            for (SongFile f : files) {
                songFileMapper.update(null, Wrappers.<SongFile>lambdaUpdate()
                        .set(SongFile::getMainName, newMainName)
                        .set(SongFile::getUpdateTime, LocalDateTime.now())
                        .eq(SongFile::getId, f.getId()));
            }
        }
        // 评分：分区/评分列 + 状态 ACTIVE（default_rate 保留），只改一次
        if (score != null) {
            songGroupMapper.update(null, Wrappers.<SongGroup>lambdaUpdate()
                    .set(SongGroup::getPartitionName, toPartition)
                    .set(SongGroup::getScore, score)
                    .set(SongGroup::getStatus, SongStatus.ACTIVE)
                    .set(SongGroup::getUpdateTime, LocalDateTime.now())
                    .eq(SongGroup::getId, sg.getId()));
        }
        // mergeKey：所有 variant 去版本号后 mergeKey 相同，用第一个 variant 的新名算一次
        if (!newMainNames.get(0).equals(mainNames.get(0))) {
            String newMergeKey = mergeKeyOf(newMainNames.get(0));
            if (!newMergeKey.equals(sg.getMergeKey())) {
                SongName newName = SongNameParser.parse(newMainNames.get(0));
                songGroupMapper.update(null, Wrappers.<SongGroup>lambdaUpdate()
                        .set(SongGroup::getMergeKey, newMergeKey)
                        .set(SongGroup::getAuthor1, authorAt(newName, 0))
                        .set(SongGroup::getAuthor2, authorAt(newName, 1))
                        .set(SongGroup::getAuthor3, authorAt(newName, 2))
                        .set(SongGroup::getTitle, newName == null ? null : newName.title())
                        .set(SongGroup::getOriginalTitle, newName == null ? null
                                : (newName.originalExplicit() ? newName.originalTitle() : newName.title()))
                        .set(SongGroup::getUpdateTime, LocalDateTime.now())
                        .eq(SongGroup::getId, sg.getId()));
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 按唯一键 {@code (partition, mergeKey)} 定位 {@code song_group} 行 */
    private SongGroup findSongGroup(String partitionName, String mainName) {
        if (StringUtils.isBlank(partitionName) || StringUtils.isBlank(mainName)) {
            return null;
        }
        return songGroupMapper.selectOne(Wrappers.<SongGroup>lambdaQuery()
                .eq(SongGroup::getPartitionName, partitionName)
                .eq(SongGroup::getMergeKey, mergeKeyOf(mainName))
                .last("limit 1"));
    }

    /**
     * {@code mainName} → {@code mergeKey}。与 {@code SongSyncService} 分桶口径一致：
     * （都走 {@link SongNameParser#mergeKey}）。口径分裂会让读写对不上同一个合并条目。
     */
    private static String mergeKeyOf(String mainName) {
        return SongNameParser.mergeKey(SongNameParser.parse(mainName), mainName);
    }

    /** 取解析结果的前第 n 个作者（0 起）；未解析或不足返回 null */
    private static String authorAt(SongName name, int index) {
        if (name == null || !name.parsed()) {
            return null;
        }
        List<String> authors = name.artists();
        return authors.size() > index ? authors.get(index) : null;
    }

    /** 倍速两位小数、落在浏览器支持的区间内。null 直接放过（表示清掉） */
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

    /** 常用档位，供页面画按钮 —— 与前端各写一份会不一致 */
    public static List<BigDecimal> presetRates() {
        return List.of(new BigDecimal("1.00"), new BigDecimal("1.25"), new BigDecimal("1.60"));
    }
}
