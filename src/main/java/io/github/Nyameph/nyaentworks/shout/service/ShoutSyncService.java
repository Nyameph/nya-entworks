package io.github.Nyameph.nyaentworks.shout.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.github.Nyameph.nyaentworks.common.file.GroupFileOps;
import io.github.Nyameph.nyaentworks.common.media.MediaExtensions;
import io.github.Nyameph.nyaentworks.shout.consts.ShoutFileType;
import io.github.Nyameph.nyaentworks.shout.consts.ShoutStatus;
import io.github.Nyameph.nyaentworks.shout.entity.ShoutFile;
import io.github.Nyameph.nyaentworks.shout.entity.ShoutGroup;
import io.github.Nyameph.nyaentworks.shout.mapper.ShoutFileMapper;
import io.github.Nyameph.nyaentworks.shout.mapper.ShoutGroupMapper;

import java.io.File;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 归档喊麦同步（磁盘 → 库）。与歌曲的 {@code SongSyncService} 同款立场，只是少了归并。
 *
 * <p><b>磁盘仍是权威，库是镜像</b>：分区目录名编码评分，打分 = 搬文件；这两张表只是把
 * 归档状态固化供查询，改磁盘后跑一次同步、库跟着走；反过来下次同步会把库覆盖回去。
 *
 * <p><b>幂等全量</b>：扫归档根 → 逐组 upsert {@code shout_group} → reconcile
 * {@code shout_file} → 标 MISSING。重复跑结果一致。
 *
 * <p>比歌曲侧少的一步是<b>归并</b>：喊麦主名即身份、不跨主名归并，所以一个
 * {@code ShoutGroup} 直接对应一行，不需要分桶。
 *
 * <p><b>default_rate 只写不覆盖</b>：同步只建新行、维护文件清单，不动这一列 ——
 * 它是用户设的，不该被「磁盘镜像」的同步抹掉。
 *
 * <p><b>只标 MISSING 不删行</b>：磁盘没了标 {@link ShoutStatus#MISSING}，保住
 * {@code default_rate} —— 目录可能只是被临时移走（外挂盘没挂上）。
 */
@Service
@RequiredArgsConstructor
public class ShoutSyncService {

    private static final Logger log = LoggerFactory.getLogger(ShoutSyncService.class);

    /** 键分隔符（U+0001）。分区名与主名里都不会出现，拼出来不会撞 */
    private static final String SEP = Character.toString((char) 1);

    private final ShoutGroupService groupService;
    private final ShoutGroupMapper groupMapper;
    private final ShoutFileMapper fileMapper;

    /** 一次同步的结果，供页面/日志展示 */
    public record SyncResult(int groupsInserted, int groupsUpdated, int groupsMissing,
                             int filesInserted, int filesUpdated, int filesDeleted) {
    }

    /** 累加用的可变载体 */
    private static final class Accumulator {
        int groupsInserted;
        int groupsUpdated;
        int groupsMissing;
        int filesInserted;
        int filesUpdated;
        int filesDeleted;

        SyncResult toResult() {
            return new SyncResult(groupsInserted, groupsUpdated, groupsMissing,
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
            // 否则所有组标 MISSING、default_rate 挂着 MISSING（同 EnvCheck 的拦截立场）
            log.info("归档根不存在，跳过同步：{}", root);
            return acc.toResult();
        }
        List<ShoutGroupService.ShoutGroup> groups = groupService.listArchived(null);

        Set<String> presentKeys = new HashSet<>();
        for (ShoutGroupService.ShoutGroup group : groups) {
            presentKeys.add(key(group.partitionName(), group.mainName()));
            upsertGroup(group, acc);
        }

        markMissing(presentKeys, acc);
        return acc.toResult();
    }

    /** 一组（一个主名）转成一条 {@code shout_group} + 文件清单 */
    private void upsertGroup(ShoutGroupService.ShoutGroup group, Accumulator acc) {
        ShoutGroup row = findGroup(group.partitionName(), group.mainName());
        boolean isNew = row == null;
        if (isNew) {
            row = new ShoutGroup();
            row.setPartitionName(group.partitionName());
            row.setMainName(group.mainName());
            // default_rate 保持 null：它是用户设的，同步只建行、不设值
        }
        row.setScore(group.score());
        row.setStatus(ShoutStatus.ACTIVE);

        if (isNew) {
            groupMapper.insert(row);
            acc.groupsInserted++;
        } else {
            // 定位键（分区 + 主名）不变，只有 score 是分区名的镜像 —— 但改名/换分区走
            // ShoutStoreService，这里是兜底：磁盘上被手工挪过目录时以磁盘为准
            groupMapper.update(null, Wrappers.<ShoutGroup>lambdaUpdate()
                    .set(ShoutGroup::getScore, row.getScore())
                    .set(ShoutGroup::getStatus, ShoutStatus.ACTIVE)
                    .set(ShoutGroup::getUpdateTime, LocalDateTime.now())
                    .eq(ShoutGroup::getId, row.getId()));
            acc.groupsUpdated++;
        }

        reconcileFiles(row, group, acc);
    }

    /** 按 {@code (partition_name, main_name)}（唯一键 uk_main）找已有行 */
    private ShoutGroup findGroup(String partitionName, String mainName) {
        return groupMapper.selectOne(Wrappers.<ShoutGroup>lambdaQuery()
                .eq(ShoutGroup::getPartitionName, partitionName)
                .eq(ShoutGroup::getMainName, mainName)
                .last("limit 1"));
    }

    /**
     * 对每条 shout_group 全量 reconcile shout_file：按<b>完整文件名</b>diff ——
     * 新增 insert、存续 update（类型/顺序）、消失 delete。
     *
     * <p>与歌曲侧同款（见 {@code SongSyncService#reconcileFiles}）：库里的键是
     * {@code (shout_id, main_name, suffix)}、磁盘上的标识是完整文件名，diff 的键取
     * {@link ShoutFile#fullName()} 拼回的那个名字。
     */
    private void reconcileFiles(ShoutGroup row, ShoutGroupService.ShoutGroup group, Accumulator acc) {
        List<ShoutFile> existing = fileMapper.selectList(Wrappers.<ShoutFile>lambdaQuery()
                .eq(ShoutFile::getShoutId, row.getId()));
        Map<String, ShoutFile> byFileName = new HashMap<>();
        for (ShoutFile f : existing) {
            byFileName.put(f.fullName(), f);
        }

        Set<String> seen = new HashSet<>();
        int sortOrder = 0;
        // 顺序（video → audio → 歌词）由 GroupFileOps.entries 定，与 allFiles() 同源
        for (GroupFileOps.GroupEntry entry : GroupFileOps.entries(
                group.videoFile(), group.audioFile(), group.lyricFiles())) {
            upsertFile(row, group, entry.fileName(),
                    switch (entry.kind()) {
                        case VIDEO -> ShoutFileType.VIDEO;
                        case AUDIO -> ShoutFileType.AUDIO;
                        case LYRIC -> ShoutFileType.LYRIC;
                    }, sortOrder++, byFileName, seen, acc);
        }

        // 磁盘上已没有的现存行删掉
        for (ShoutFile f : existing) {
            if (!seen.contains(f.fullName())) {
                fileMapper.deleteById(f.getId());
                acc.filesDeleted++;
            }
        }
    }

    private void upsertFile(ShoutGroup row, ShoutGroupService.ShoutGroup group, String fileName,
                            ShoutFileType type, int sortOrder,
                            Map<String, ShoutFile> byFileName, Set<String> seen, Accumulator acc) {
        seen.add(fileName);
        ShoutFile file = byFileName.get(fileName);
        if (file == null) {
            file = new ShoutFile();
            file.setShoutId(row.getId());
            file.setMainName(group.mainName());
            file.setFileType(type);
            file.setSuffix(MediaExtensions.suffix(fileName, group.mainName()));
            file.setSortOrder(sortOrder);
            fileMapper.insert(file);
            acc.filesInserted++;
        } else {
            boolean changed = false;
            if (!Objects.equals(file.getMainName(), group.mainName())) {
                // 主名与后缀是同一个文件名切出来的两段，改一段必须同步改另一段（理由同歌曲侧）
                file.setMainName(group.mainName());
                file.setSuffix(MediaExtensions.suffix(fileName, group.mainName()));
                changed = true;
            }
            if (file.getFileType() != type) {
                file.setFileType(type);
                changed = true;
            }
            if (!Objects.equals(file.getSortOrder(), sortOrder)) {
                file.setSortOrder(sortOrder);
                changed = true;
            }
            if (changed) {
                fileMapper.updateById(file);
                acc.filesUpdated++;
            }
        }
    }

    /**
     * 标 MISSING：本次扫描没出现的 ACTIVE 行 → MISSING。
     * <p>只标不删，保住 default_rate（同漫画 MISSING 保标签的立场）。
     */
    private void markMissing(Set<String> presentKeys, Accumulator acc) {
        List<ShoutGroup> rows = groupMapper.selectList(Wrappers.<ShoutGroup>lambdaQuery()
                .eq(ShoutGroup::getStatus, ShoutStatus.ACTIVE));
        for (ShoutGroup row : rows) {
            if (!presentKeys.contains(key(row.getPartitionName(), row.getMainName()))) {
                groupMapper.update(null, Wrappers.<ShoutGroup>lambdaUpdate()
                        .set(ShoutGroup::getStatus, ShoutStatus.MISSING)
                        .set(ShoutGroup::getUpdateTime, LocalDateTime.now())
                        .eq(ShoutGroup::getId, row.getId()));
                acc.groupsMissing++;
            }
        }
    }

    private static String key(String partitionName, String mainName) {
        return StringUtils.defaultString(partitionName) + SEP + mainName;
    }
}
