package io.github.Nyameph.nyaentworks.song.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.github.Nyameph.nyaentworks.common.file.GroupFileOps;
import io.github.Nyameph.nyaentworks.common.media.MediaExtensions;
import io.github.Nyameph.nyaentworks.song.consts.SongFileType;
import io.github.Nyameph.nyaentworks.song.consts.SongStatus;
import io.github.Nyameph.nyaentworks.song.entity.SongGroup;
import io.github.Nyameph.nyaentworks.song.entity.SongFile;
import io.github.Nyameph.nyaentworks.song.mapper.SongFileMapper;
import io.github.Nyameph.nyaentworks.song.mapper.SongGroupMapper;
import io.github.Nyameph.nyaentworks.song.util.SongName;
import io.github.Nyameph.nyaentworks.song.util.SongNameParser;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 归档歌曲同步（磁盘 → 库）。需求变更：归档歌曲由数据库存储。
 *
 * <p><b>磁盘仍是权威，库是镜像</b>（同漫画 {@code MangaArchiveService#sync} 的立场）：
 * 分区目录名编码评分，打分 = 搬文件；这张表只是把归并结果固化供查询，改磁盘后跑一次
 * 同步，库跟着走；反过来下次同步会把库覆盖回去。
 *
 * <p><b>幂等全量</b>：扫归档根 → 按 {@code (partitionName, mergeKey)} 归并 →
 * upsert {@code song_group} → reconcile {@code song_file} → 标 MISSING。重复跑结果一致。
 *
 * <p><b>default_rate 只写不覆盖</b>：同步只建新行、维护文件清单，
 * 不动这一列 —— 它是用户设的（默认倍速），不该被「磁盘镜像」的同步抹掉。
 *
 * <p><b>只标 MISSING 不删行</b>：磁盘删了标 {@code SongStatus#MISSING}，保住
 * {@code default_rate} —— 目录可能只是被临时移走（外挂盘没挂上）。
 */
@Service
@RequiredArgsConstructor
public class SongSyncService {

    private static final Logger log = LoggerFactory.getLogger(SongSyncService.class);

    /** 桶键分隔符（U+0001）。分区名与 merge_key 里都不会出现，拼出来不会撞 */
    private static final String SEP = Character.toString((char) 1);

    private final SongGroupService groupService;
    private final SongGroupMapper songGroupMapper;
    private final SongFileMapper songFileMapper;

    /** 一次同步的结果，供页面/日志展示 */
    public record SyncResult(int songsInserted, int songsUpdated, int songsMissing,
                             int filesInserted, int filesUpdated, int filesDeleted) {
    }

    /** 累加用的可变载体 */
    private static final class Accumulator {
        int songsInserted;
        int songsUpdated;
        int songsMissing;
        int filesInserted;
        int filesUpdated;
        int filesDeleted;

        SyncResult toResult() {
            return new SyncResult(songsInserted, songsUpdated, songsMissing,
                    filesInserted, filesUpdated, filesDeleted);
        }
    }

    /**
     * 全量同步。
     * <p>{@code @Transactional + synchronized}：文件操作没有事务，但库这一侧要整批
     * 一致 —— 中途失败回滚，不留「一半新键一半旧键」的镜像。
     */
    @Transactional(rollbackFor = Exception.class)
    public synchronized SyncResult sync() {
        Accumulator acc = new Accumulator();
        String root = groupService.archivedRoot();
        if (!new File(root).isDirectory()) {
            // 归档根不存在（外挂盘没挂上）：整个同步跳过，不判 MISSING ——
            // 否则全分区标 MISSING、default_rate 挂着 MISSING（同 EnvCheck 的拦截立场）
            log.info("归档根不存在，跳过同步：{}", root);
            return acc.toResult();
        }
        List<SongGroupService.SongGroup> groups = groupService.listArchived(null);

        // 按 (partitionName, mergeKey) 分桶：一条 song = 同一分区目录内作者+曲名+原曲名相同的一批
        Map<String, List<SongGroupService.SongGroup>> buckets = new LinkedHashMap<>();
        for (SongGroupService.SongGroup g : groups) {
            String key = StringUtils.defaultString(g.partitionName()) + SEP
                    + SongNameParser.mergeKey(g.name(), g.mainName());
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(g);
        }

        for (List<SongGroupService.SongGroup> bucket : buckets.values()) {
            SongMergeService.sortVariants(bucket);
            upsertSong(bucket, acc);
        }

        markMissing(buckets.keySet(), acc);
        return acc.toResult();
    }

    /**
     * 一个桶（同 partition+mergeKey 的一组 variant）转成一条 {@code song} + 文件清单。
     */
    private void upsertSong(List<SongGroupService.SongGroup> bucket, Accumulator acc) {
        SongGroupService.SongGroup first = bucket.get(0);          // 排序后第一个 = 主 variant
        String partitionName = first.partitionName();
        String mergeKey = SongNameParser.mergeKey(first.name(), first.mainName());

        SongGroup songGroup = findSong(partitionName, mergeKey);
        boolean isNew = songGroup == null;
        if (isNew) {
            songGroup = new SongGroup();
            songGroup.setPartitionName(partitionName);
            songGroup.setMergeKey(mergeKey);
            // default_rate 默认 null：它是用户设的，同步只建行、不设值
        }
        fillParsedFields(songGroup, bucket, first);
        songGroup.setScore(first.score());
        songGroup.setStatus(SongStatus.ACTIVE);

        if (isNew) {
            songGroupMapper.insert(songGroup);
            acc.songsInserted++;
        } else {
            // 已存在的行：全量显式 SET（含 null）—— updateById 的 NOT_NULL 策略会跳过
            // null 字段，于是「解析成功 → 失败」时 author/title 的旧值清不掉
            applySongUpdate(songGroup);
            acc.songsUpdated++;
        }

        reconcileFiles(songGroup, bucket, acc);
    }

    /**
     * 按 {@code (partitionName, mergeKey)}（唯一键 uk_merge）找已有行。
     */
    private SongGroup findSong(String partitionName, String mergeKey) {
        return songGroupMapper.selectOne(Wrappers.<SongGroup>lambdaQuery()
                .eq(SongGroup::getPartitionName, partitionName)
                .eq(SongGroup::getMergeKey, mergeKey)
                .last("limit 1"));
    }

    /**
     * 填充解析事实。诊断列走「any」语义（对齐 {@code SongMergeService.buildMergedRow}）：
     * <ul>
     *   <li>{@code author1-3}/{@code title}/{@code originalTitle} = 主 variant 的解析值
     *       （隐式原曲名填成曲名）；解析失败为 null；
     *   <li>{@code parsed}/{@code needsNormalize}/{@code looseSeparator} = 任一 variant 满足；
     *   <li>{@code parseFailedReason} = 全部 variant 都失败时取主 variant 的。
     * </ul>
     */
    private static void fillParsedFields(SongGroup songGroup, List<SongGroupService.SongGroup> bucket, SongGroupService.SongGroup first) {
        boolean anyParsed = false;
        boolean anyNormalize = false;
        boolean anyLoose = false;
        for (SongGroupService.SongGroup g : bucket) {
            SongName n = g.name();
            if (n != null && n.parsed()) {
                anyParsed = true;
                if (n.needsNormalize()) {
                    anyNormalize = true;
                }
                if (n.looseSeparator()) {
                    anyLoose = true;
                }
            }
        }
        songGroup.setParsed(anyParsed);
        songGroup.setNeedsNormalize(anyNormalize);
        songGroup.setLooseSeparator(anyLoose);

        SongName firstName = first.name();
        if (firstName != null && firstName.parsed()) {
            List<String> authors = firstName.artists();
            songGroup.setAuthor1(authors.size() > 0 ? authors.get(0) : null);
            songGroup.setAuthor2(authors.size() > 1 ? authors.get(1) : null);
            songGroup.setAuthor3(authors.size() > 2 ? authors.get(2) : null);
            songGroup.setTitle(firstName.title());
            // 隐式原曲名统一填成曲名，与 merge_key 的口径一致
            songGroup.setOriginalTitle(firstName.originalExplicit()
                    ? firstName.originalTitle() : firstName.title());
            songGroup.setParseFailedReason(null);
        } else {
            // 解析失败：没有作者/曲名可填
            songGroup.setAuthor1(null);
            songGroup.setAuthor2(null);
            songGroup.setAuthor3(null);
            songGroup.setTitle(null);
            songGroup.setOriginalTitle(null);
            songGroup.setParseFailedReason(firstName == null ? null : firstName.parseFailedReason());
        }
    }

    /** 已存在行的全量更新：显式 SET 每个字段（含 null），避免 NOT_NULL 策略清不掉旧值 */
    private void applySongUpdate(SongGroup songGroup) {
        songGroupMapper.update(null, Wrappers.<SongGroup>lambdaUpdate()
                .set(SongGroup::getAuthor1, songGroup.getAuthor1())
                .set(SongGroup::getAuthor2, songGroup.getAuthor2())
                .set(SongGroup::getAuthor3, songGroup.getAuthor3())
                .set(SongGroup::getTitle, songGroup.getTitle())
                .set(SongGroup::getOriginalTitle, songGroup.getOriginalTitle())
                .set(SongGroup::getMergeKey, songGroup.getMergeKey())
                .set(SongGroup::getParsed, songGroup.getParsed())
                .set(SongGroup::getParseFailedReason, songGroup.getParseFailedReason())
                .set(SongGroup::getNeedsNormalize, songGroup.getNeedsNormalize())
                .set(SongGroup::getLooseSeparator, songGroup.getLooseSeparator())
                .set(SongGroup::getScore, songGroup.getScore())
                .set(SongGroup::getStatus, songGroup.getStatus())
                .set(SongGroup::getUpdateTime, LocalDateTime.now())
                .eq(SongGroup::getId, songGroup.getId()));
    }

    /**
     * 对每条 songGroup 全量 reconcile song_file：按<b>完整文件名</b>diff ——
     * 新增 insert、存续 update（type/排序）、消失 delete。
     *
     * <p>库里的唯一键是 {@code (song_id, main_name, suffix)}、磁盘上的标识是完整文件名，
     * 两者由「{@code main_name + suffix} 恒等于文件名」这条不变量对应起来，所以 diff 的键
     * 取 {@link SongFile#fullName()} 拼回的那个名字。**存储形态变了、身份没变** ——
     * 于是这次列改造的验收判据就是「同步计数 0 insert / 0 update / 0 delete」。
     */
    private void reconcileFiles(SongGroup songGroup, List<SongGroupService.SongGroup> bucket, Accumulator acc) {
        List<SongFile> existing = songFileMapper.selectList(Wrappers.<SongFile>lambdaQuery()
                .eq(SongFile::getSongId, songGroup.getId()));
        Map<String, SongFile> byFileName = new HashMap<>();
        for (SongFile f : existing) {
            byFileName.put(f.fullName(), f);
        }

        Set<String> seen = new HashSet<>();
        int variantSort = 0;
        for (SongGroupService.SongGroup variant : bucket) {
            int sortOrder = 0;
            // 顺序（video → audio → 歌词）由 GroupFileOps.entries 定，与 allFiles() 同源
            for (GroupFileOps.GroupEntry entry : GroupFileOps.entries(
                    variant.videoFile(), variant.audioFile(), variant.lyricFiles())) {
                upsertFile(songGroup, variant, entry.fileName(),
                        switch (entry.kind()) {
                            case VIDEO -> SongFileType.VIDEO;
                            case AUDIO -> SongFileType.AUDIO;
                            case LYRIC -> SongFileType.LYRIC;
                        }, variantSort, sortOrder++, byFileName, seen, acc);
            }
            variantSort++;
        }

        // 磁盘上已没有的现存行删掉
        for (SongFile f : existing) {
            if (!seen.contains(f.fullName())) {
                songFileMapper.deleteById(f.getId());
                acc.filesDeleted++;
            }
        }
    }

    private void upsertFile(SongGroup songGroup, SongGroupService.SongGroup variant, String fileName, SongFileType type,
                            int variantSort, int sortOrder,
                            Map<String, SongFile> byFileName, Set<String> seen, Accumulator acc) {
        seen.add(fileName);
        SongFile row = byFileName.get(fileName);
        if (row == null) {
            row = new SongFile();
            row.setSongId(songGroup.getId());
            row.setMainName(variant.mainName());
            row.setFileType(type);
            row.setSuffix(MediaExtensions.suffix(fileName, variant.mainName()));
            row.setVariantSort(variantSort);
            row.setSortOrder(sortOrder);
            songFileMapper.insert(row);
            acc.filesInserted++;
        } else {
            boolean changed = false;
            if (!Objects.equals(row.getMainName(), variant.mainName())) {
                // 主名与后缀是同一个文件名切出来的两段，改一段必须同步改另一段，
                // 否则「main_name + suffix 等于文件名」这条不变量就断了。
                // 实际走不到这里（主名是文件名的纯函数），但让不变量由构造保证，
                // 比依赖「这段走不到」安全。
                row.setMainName(variant.mainName());
                row.setSuffix(MediaExtensions.suffix(fileName, variant.mainName()));
                changed = true;
            }
            if (row.getFileType() != type) {
                row.setFileType(type);
                changed = true;
            }
            if (!Objects.equals(row.getVariantSort(), variantSort)) {
                row.setVariantSort(variantSort);
                changed = true;
            }
            if (!Objects.equals(row.getSortOrder(), sortOrder)) {
                row.setSortOrder(sortOrder);
                changed = true;
            }
            if (changed) {
                songFileMapper.updateById(row);
                acc.filesUpdated++;
            }
        }
    }

    /**
     * 标 MISSING：本次扫描没出现的 ACTIVE 行 → MISSING。
     * <p>只标不删，保住 default_rate（同漫画 MISSING 保标签的立场）。
     */
    private void markMissing(Set<String> presentKeys, Accumulator acc) {
        List<SongGroup> rows = songGroupMapper.selectList(Wrappers.<SongGroup>lambdaQuery()
                .eq(SongGroup::getStatus, SongStatus.ACTIVE));
        for (SongGroup row : rows) {
            String key = StringUtils.defaultString(row.getPartitionName()) + SEP + row.getMergeKey();
            if (!presentKeys.contains(key)) {
                songGroupMapper.update(null, Wrappers.<SongGroup>lambdaUpdate()
                        .set(SongGroup::getStatus, SongStatus.MISSING)
                        .set(SongGroup::getUpdateTime, LocalDateTime.now())
                        .eq(SongGroup::getId, row.getId()));
                acc.songsMissing++;
            }
        }
    }

}
