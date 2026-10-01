package io.github.Nyameph.nyaentworks.song.service;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import io.github.Nyameph.nyaentworks.common.file.GroupFileOps;
import io.github.Nyameph.nyaentworks.common.file.GroupFileOps.FileMove;
import io.github.Nyameph.nyaentworks.common.file.GroupFileOps.GroupPlan;
import io.github.Nyameph.nyaentworks.common.fileop.FileOpRecorder;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpLevel;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpModule;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpSource;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpType;
import io.github.Nyameph.nyaentworks.song.config.SongProperties;
import io.github.Nyameph.nyaentworks.song.service.SongGroupService.SongGroup;
import io.github.Nyameph.nyaentworks.song.util.SongName;
import io.github.Nyameph.nyaentworks.song.util.SongNameParser;
import io.github.Nyameph.nyaentworks.common.media.ScorePartition;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 整组的改评分 / 改名 / 删除（实现说明 6.5）。
 *
 * <p>这里只负责歌曲这一侧的事：<b>把「组 + 目标分区/目标主名」翻译成一张搬动清单</b>
 * （plan），以及搬完之后跟着改库（apply）。真正的搬动、拦不到的检查、中途回滚都在
 * {@link GroupFileOps}（与喊麦共用，理由见那个类的注释）。
 *
 * <p>歌曲独有的两件事留在这里：
 * <ul>
 *   <li><b>版本号语义</b>：{@code #2} 是版本号，改名时后缀保留、批量改名按基础名改
 *       （{@link #versionSuffix} / {@link #baseNameOf}）；回写的是 {@code split("|", 2)} 的 key；
 *   <li><b>归档闸门</b>：待打分区的首次归档要不要先有标签，由
 *       {@code nya-entworks.song.require-tags-before-archive} 决定（出厂 false ＝ 不要求，
 *       2026-09-22 用户定；原先写死要求），见 {@link #requireTagForArchive}。
 * </ul>
 *
 * <p>库那一侧只有设置值要跟着搬（{@link SongSettingService#moveSongGroupPartition}），
 * 顺序仍是<b>磁盘先动、库后动</b>：磁盘失败时库还没改，重来一次就是；反过来的话
 * 库指向一个不存在的分区，而那份默认倍速再也查不到。
 */
@Service
@RequiredArgsConstructor
public class SongArchiveService {

    private final SongGroupService groupService;
    private final SongSettingService settingService;
    private final SongTagService tagService;
    private final SongProperties properties;

    /**
     * @param movedFiles    实际搬动的文件数
     * @param settingMoved  设置行的 key 是否跟着搬了
     */
    public record GroupApplyResult(String action, String mainName, String toPartition,
                                   String toMainName, int movedFiles, boolean settingMoved) {
    }

    /**
     * 批量操作里一个 variant 的定位。{@code partition} 为空表示待打分区。
     * 批量请求体里的 {@code groups[]} 就是它的列表。
     */
    public record GroupRef(String partition, String mainName) {
    }

    // ------------------------------------------------------------------
    // 预演
    // ------------------------------------------------------------------

    /**
     * 改评分的预演：整组搬到另一个分区，文件名不变。
     * <p>源可以是已归档的组（换分区），也可以是待打分区的组（首次归档，
     * {@code fromPartition} 传空）。两者的搬动逻辑完全一样。
     */
    public GroupPlan planScore(String fromPartition, String mainName, int score) {
        SongGroup group = groupService.require(fromPartition, mainName);
        // 待打分区的首次归档：要不要先有标签见 nya-entworks.song.require-tags-before-archive
        // （出厂不拦；改已归档的分这条永远不拦）
        if (StringUtils.isBlank(fromPartition)) {
            String gate = requireTagForArchive(group);
            if (gate != null) {
                return new GroupPlan("SCORE", mainName, null, mainName, List.of(), gate);
            }
        }
        Path targetDir;
        String targetPartition;
        try {
            // 这一档分区没建时 resolveDir 给一个裸 `#9`（不建目录），apply 的
            // GroupFileOps.moveAll 落盘时会 createDirectories 建出来；根目录不在才拦
            targetDir = ScorePartition.resolveDir(groupService.archivedRoot(), score);
            targetPartition = targetDir.getFileName().toString();
        } catch (RuntimeException e) {
            return new GroupPlan("SCORE", mainName, null, mainName, List.of(), e.getMessage());
        }
        if (targetDir.equals(group.dir())) {
            return new GroupPlan("SCORE", mainName, targetPartition, mainName, List.of(),
                    "已经在「" + targetPartition + "」里了，不用搬");
        }
        return new GroupPlan("SCORE", mainName, targetPartition, mainName,
                planMoves(group, targetDir, mainName), null);
    }

    /**
     * 改名的预演：整组改成新主名，留在原分区。
     * <p>「规范化命名」也走这里 —— 它就是把主名换成 {@link SongName#normalizedMainName()}。
     */
    public GroupPlan planRename(String partitionName, String mainName, String newMainName) {
        SongGroup group = groupService.require(partitionName, mainName);
        String target = GroupFileOps.requireMainName(newMainName);
        if (target.equals(mainName)) {
            return new GroupPlan("RENAME", mainName, partitionName, target, List.of(),
                    "新名字与现在的一样，不用改");
        }
        return new GroupPlan("RENAME", mainName, partitionName, target,
                planMoves(group, group.dir(), target), null);
    }

    /**
     * 一次提交的预演：评分（搬分区）与改名（改主名）可以同时做，也可以只做其一。
     * <p>{@code score} 为 {@code null} 表示不改评分、留在原分区；否则搬到该评分分区。
     * {@code newMainName} 与现主名相同表示不改名。两者都不变时报「没有要保存的」。
     * <p>目标目录与目标主名都交给 {@link #planMoves}：只评分时主名不变、只改名时目录不变，
     * 与 {@link #planScore}/{@link #planRename} 各自算出来的一模一样。
     */
    public GroupPlan planEdit(String fromPartition, String mainName,
                              Integer score, String newMainName) {
        SongGroup group = groupService.require(fromPartition, mainName);
        String targetMain = GroupFileOps.requireMainName(newMainName);
        Path targetDir = group.dir();
        String targetPartition = fromPartition;
        if (score != null) {
            // 待打分区的首次归档：同上那道闸门（默认关）
            if (StringUtils.isBlank(fromPartition)) {
                String gate = requireTagForArchive(group);
                if (gate != null) {
                    return new GroupPlan("EDIT", mainName, null, targetMain, List.of(), gate);
                }
            }
            try {
                // 缺档时给裸 `#9`、落盘时建出来（同 planScore）
                targetDir = ScorePartition.resolveDir(groupService.archivedRoot(), score);
                targetPartition = targetDir.getFileName().toString();
            } catch (RuntimeException e) {
                return new GroupPlan("EDIT", mainName, null, targetMain, List.of(), e.getMessage());
            }
        }
        if (targetDir.equals(group.dir()) && targetMain.equals(mainName)) {
            return new GroupPlan("EDIT", mainName, targetPartition, targetMain, List.of(),
                    "评分和名字都没变，没有要保存的");
        }
        return new GroupPlan("EDIT", mainName, targetPartition, targetMain,
                planMoves(group, targetDir, targetMain), null);
    }

    /**
     * 规范化命名的预演：把 {@code 作者 - 原曲名#版本} 补成
     * {@code 作者 - 原曲名（原曲名）#版本}（文档 8.1）。
     */
    public GroupPlan planNormalize(String partitionName, String mainName) {
        SongGroup group = groupService.require(partitionName, mainName);
        SongName name = group.name();
        if (!name.parsed()) {
            return new GroupPlan("RENAME", mainName, partitionName, mainName, List.of(),
                    "文件名解析失败（" + name.parseFailedReason() + "），先手工把分隔符改对");
        }
        if (!name.needsNormalize()) {
            return new GroupPlan("RENAME", mainName, partitionName, mainName, List.of(),
                    "已经是规范形态，不用改");
        }
        return planRename(partitionName, mainName, name.normalizedMainName());
    }

    /** 删除的预演：整组删。列出来是因为一次要送走整组文件，先让人看清是哪几个（会进回收站） */
    public GroupPlan planDelete(String partitionName, String mainName) {
        SongGroup group = groupService.require(partitionName, mainName);
        List<FileMove> moves = GroupFileOps.planDeletes(group.dir(), group.allFiles());
        return new GroupPlan("DELETE", mainName, null, null, moves, null);
    }

    /**
     * 批量一次提交（评分 + 改名）的预演：merged row 的所有 variant 一起动。
     * <p>{@code groups} 是这一行各 variant 的 {@code (分区, 主名)} 列表；{@code score} 为
     * {@code null} 表示不改评分、留在原分区；{@code newBaseName} 为 {@code null}（或与
     * 当前基础名相同）表示不改名。改名时每个 variant 的 {@code #版本号} 后缀保留。
     * <p>磁盘上逐 variant 算 moves，合并成一张清单；库那一侧由
     * {@link SongSettingService#editSongGroupBatch} 一次完成（见下）。
     */
    public GroupPlan planEditBatch(List<GroupRef> groups,
                                   Integer score, String newBaseName) {
        if (groups == null || groups.isEmpty()) {
            return new GroupPlan("EDIT", "", null, null, List.of(), "没有要操作的组");
        }
        String displayMain = groups.get(0).mainName();           // 主 variant 主名（含版本号）
        String currentBase = baseNameOf(displayMain);
        String targetBase = newBaseName == null ? null : GroupFileOps.requireMainName(newBaseName);
        boolean willRename = targetBase != null && !targetBase.equals(currentBase);
        boolean willScore = score != null;
        if (!willScore && !willRename) {
            return new GroupPlan("EDIT", displayMain, null, displayMain, List.of(),
                    "评分和名字都没变，没有要保存的");
        }
        Path targetDir = null;
        String targetPartition = null;
        if (willScore) {
            // 含待打分 variant 的归档：同上那道闸门（整批只改已归档分不拦）
            boolean anyStaging = groups.stream().anyMatch(g -> StringUtils.isBlank(g.partition()));
            if (anyStaging) {
                SongGroup first = groupService.require(groups.get(0).partition(),
                        groups.get(0).mainName());
                String gate = requireTagForArchive(first);
                if (gate != null) {
                    return new GroupPlan("EDIT", displayMain, null, displayMain, List.of(), gate);
                }
            }
            try {
                // 缺档时给裸 `#9`、落盘时建出来（同 planScore）
                targetDir = ScorePartition.resolveDir(groupService.archivedRoot(), score);
                targetPartition = targetDir.getFileName().toString();
            } catch (RuntimeException e) {
                return new GroupPlan("EDIT", displayMain, null, displayMain, List.of(), e.getMessage());
            }
        }
        List<FileMove> moves = new ArrayList<>();
        for (GroupRef ref : groups) {
            SongGroup group = groupService.require(ref.partition(), ref.mainName());
            String targetMain = willRename ? targetBase + versionSuffix(ref.mainName()) : ref.mainName();
            Path dir = willScore ? targetDir : group.dir();
            if (dir.equals(group.dir()) && targetMain.equals(ref.mainName())) {
                continue;   // 这个 variant 已在目标分区且没改名，不用动
            }
            moves.addAll(planMoves(group, dir, targetMain));
        }
        String targetDisplay = willRename ? targetBase + versionSuffix(displayMain) : displayMain;
        return new GroupPlan("EDIT", displayMain, targetPartition, targetDisplay, moves, null);
    }

    /** 批量删除的预演：整行所有 variant 的文件都列出来（一次送走这么多，先看清；会进回收站） */
    public GroupPlan planDeleteBatch(List<GroupRef> groups) {
        if (groups == null || groups.isEmpty()) {
            return new GroupPlan("DELETE", "", null, null, List.of(), "没有要操作的组");
        }
        String displayMain = groups.get(0).mainName();
        List<FileMove> moves = new ArrayList<>();
        for (GroupRef ref : groups) {
            SongGroup group = groupService.require(ref.partition(), ref.mainName());
            moves.addAll(GroupFileOps.planDeletes(group.dir(), group.allFiles()));
        }
        return new GroupPlan("DELETE", displayMain, null, null, moves, null);
    }

    // ------------------------------------------------------------------
    // 执行
    // ------------------------------------------------------------------

    /**
     * 执行改评分。整组搬分区，设置行的 key 跟着换。
     * <p>改动流水：整段包一个 PAGE 批次；搬成功 {@code recordPlan}、
     * 中途失败先记一条 FAILED 再抛（记录是附属品，绝不影响主流程）。
     */
    public GroupApplyResult applyScore(String fromPartition, String mainName, int score) {
        return FileOpRecorder.batch(FileOpModule.SONG, FileOpSource.PAGE, null, () -> {
            GroupPlan plan = planScore(fromPartition, mainName, score);
            GroupFileOps.requireExecutable(plan);
            int moved = moveRecordingFailure(plan, FileOpType.MOVE);
            FileOpRecorder.recordPlan(FileOpModule.SONG, plan, FileOpLevel.GROUP);
            // 磁盘先动、库后动。这一步失败只丢默认倍速，磁盘上的歌是完整的；
            // 库里没有这一条（staging 首归档）时返回 false，交给下次同步补建
            boolean settingMoved = settingService.moveSongGroupPartition(fromPartition, mainName,
                    plan.toPartition(), score);
            return new GroupApplyResult("SCORE", mainName, plan.toPartition(), plan.toMainName(),
                    moved, settingMoved);
        });
    }

    /** 执行改名。批次的口径同 {@link #applyScore} */
    public GroupApplyResult applyRename(String partitionName, String mainName,
                                       String newMainName) {
        return FileOpRecorder.batch(FileOpModule.SONG, FileOpSource.PAGE, null, () -> {
            GroupPlan plan = planRename(partitionName, mainName, newMainName);
            GroupFileOps.requireExecutable(plan);
            int moved = moveRecordingFailure(plan, FileOpType.MOVE);
            FileOpRecorder.recordPlan(FileOpModule.SONG, plan, FileOpLevel.GROUP);
            // song_file 跟着换**只有 main_name**（2026-09-23 起 file_name 换成独立的 suffix 列，
            // 而 GroupFileOps.planMoves 保证改名不动扩展名原文，所以后缀那一列不用碰）、
            // song_group 的解析列跟着同步；库里没有这一条（staging 改名）时返回 false，交给下次同步
            boolean settingMoved = settingService.renameSongGroup(partitionName, mainName,
                    plan.toMainName());
            return new GroupApplyResult("RENAME", mainName, partitionName, plan.toMainName(),
                    moved, settingMoved);
        });
    }

    /** 执行一次提交。评分与改名可同时，磁盘先动、库后动。批次的口径同 {@link #applyScore} */
    public GroupApplyResult applyEdit(String fromPartition, String mainName,
                                      Integer score, String newMainName) {
        return FileOpRecorder.batch(FileOpModule.SONG, FileOpSource.PAGE, null, () -> {
            GroupPlan plan = planEdit(fromPartition, mainName, score, newMainName);
            GroupFileOps.requireExecutable(plan);
            int moved = moveRecordingFailure(plan, FileOpType.MOVE);
            FileOpRecorder.recordPlan(FileOpModule.SONG, plan, FileOpLevel.GROUP);
            boolean settingMoved = settingService.editSongGroup(fromPartition, mainName,
                    plan.toPartition(), score, plan.toMainName());
            return new GroupApplyResult("EDIT", mainName, plan.toPartition(), plan.toMainName(),
                    moved, settingMoved);
        });
    }

    /** 执行规范化命名（走改名，批次由 {@link #applyRename} 出） */
    public GroupApplyResult applyNormalize(String partitionName, String mainName) {
        return FileOpRecorder.batch(FileOpModule.SONG, FileOpSource.PAGE, null, () -> {
            GroupPlan plan = planNormalize(partitionName, mainName);
            GroupFileOps.requireExecutable(plan);
            return applyRename(partitionName, mainName, plan.toMainName());
        });
    }

    /**
     * 批量规范化命名（文档 8.2）。逐组走 {@link #applyNormalize}，一组失败不影响其它组 ——
     * 每组自己是原子的，组之间没有关系。
     *
     * @return 每组的结果，失败的组在 {@code errors} 里
     */
    public record BatchNormalizeResult(int renamed, List<String> errors) {
    }

    public BatchNormalizeResult applyNormalizeBatch(List<String> keys) {
        return applyNormalizeBatch(keys, null);
    }

    /** 带 taskId 的版本（异步任务 handler 传当前任务 id）。整批一个批次号 —— 循环里
     *  逐组调 {@link #applyNormalize}，内层 batch 会自动让位（嵌套外层赢） */
    public BatchNormalizeResult applyNormalizeBatch(List<String> keys, Long taskId) {
        return FileOpRecorder.batch(FileOpModule.SONG, FileOpSource.TASK, taskId, () -> {
            int renamed = 0;
            List<String> errors = new ArrayList<>();
            for (String key : keys) {
                // key 形如 分区|主名，前端从列表里原样带回来
                String[] parts = key.split("\\|", 2);
                if (parts.length < 2) {
                    errors.add(key + "：key 格式不对");
                    continue;
                }
                try {
                    applyNormalize(parts[0], parts[1]);
                    renamed++;
                } catch (RuntimeException e) {
                    errors.add(parts[1] + "：" + e.getMessage());
                }
            }
            return new BatchNormalizeResult(renamed, errors);
        });
    }

    /**
     * 执行删除。<b>文件送进 Windows 回收站</b>（2026-09-30 起；原先是真的删掉），
     * 所以只受理已经预演过、且预演里没有拦截的组。
     * <p>删到一半失败时不回滚（进回收站的那些不捞回来），把没进去的列出来让人知道现状。
     * <p>改动流水的口径同 {@link #applyScore}：失败那条 FAILED 由 {@code deleteRecordingFailure} 记。
     */
    public GroupApplyResult applyDelete(String partitionName, String mainName) {
        return FileOpRecorder.batch(FileOpModule.SONG, FileOpSource.PAGE, null, () -> {
            GroupPlan plan = planDelete(partitionName, mainName);
            // 文件不在了不算拦截 —— 删除的目的就是让它不在，已经不在的跳过即可
            int deleted = deleteRecordingFailure(plan);
            FileOpRecorder.recordPlan(FileOpModule.SONG, plan, FileOpLevel.GROUP);
            // song_group 标 MISSING（保 default_rate）、song_file 行清掉
            boolean settingMoved = settingService.deleteSongGroup(partitionName, mainName);
            return new GroupApplyResult("DELETE", mainName, null, null, deleted, settingMoved);
        });
    }

    /** 执行批量一次提交（评分 + 改名）。磁盘逐 variant 搬，库一次改（磁盘先动、库后动） */
    public GroupApplyResult applyEditBatch(List<GroupRef> groups,
                                           Integer score, String newBaseName) {
        return applyEditBatch(groups, score, newBaseName, null);
    }

    /** 带 taskId 的版本（异步任务 handler 传当前任务 id，记录流水对回任务页那一条） */
    public GroupApplyResult applyEditBatch(List<GroupRef> groups,
                                           Integer score, String newBaseName, Long taskId) {
        return FileOpRecorder.batch(FileOpModule.SONG, FileOpSource.TASK, taskId, () -> {
            GroupPlan plan = planEditBatch(groups, score, newBaseName);
            GroupFileOps.requireExecutable(plan);
            int moved = moveRecordingFailure(plan, FileOpType.MOVE);
            FileOpRecorder.recordPlan(FileOpModule.SONG, plan, FileOpLevel.GROUP);
            // 库：主 variant 定位 song_group 行（所有 variant 共享），改名逐个 variant 更新 song_file
            String currentBase = groups.isEmpty() ? null : baseNameOf(groups.get(0).mainName());
            String targetBase = newBaseName == null ? null : GroupFileOps.requireMainName(newBaseName);
            boolean willRename = targetBase != null && !targetBase.equals(currentBase);
            List<String> mainNames = groups.stream().map(GroupRef::mainName).toList();
            List<String> newMainNames = groups.stream()
                    .map(ref -> willRename ? targetBase + versionSuffix(ref.mainName()) : ref.mainName())
                    .toList();
            boolean settingMoved = settingService.editSongGroupBatch(
                    groups.get(0).partition(), mainNames, newMainNames, plan.toPartition(), score);
            return new GroupApplyResult("EDIT", plan.mainName(), plan.toPartition(),
                    plan.toMainName(), moved, settingMoved);
        });
    }

    /**
     * 执行批量删除。<b>文件进回收站</b>，逐 variant 删文件，删到一半失败不中断，
     * 把没进去的列出来让人知道现状。库只对主 variant 调一次（song_file 按 song_id 全删）。
     */
    public GroupApplyResult applyDeleteBatch(List<GroupRef> groups) {
        return applyDeleteBatch(groups, null);
    }

    /** 带 taskId 的版本（异步任务 handler 传当前任务 id） */
    public GroupApplyResult applyDeleteBatch(List<GroupRef> groups, Long taskId) {
        return FileOpRecorder.batch(FileOpModule.SONG, FileOpSource.TASK, taskId, () -> {
            GroupPlan plan = planDeleteBatch(groups);
            int deleted = deleteRecordingFailure(plan);
            FileOpRecorder.recordPlan(FileOpModule.SONG, plan, FileOpLevel.GROUP);
            GroupRef first = groups.get(0);
            boolean settingMoved = settingService.deleteSongGroup(first.partition(), first.mainName());
            return new GroupApplyResult("DELETE", plan.mainName(), null, null, deleted, settingMoved);
        });
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 搬动 + 失败记流水：moveAll 中途失败（含整批回滚）先记一条 FAILED 再原样往上抛 */
    private static int moveRecordingFailure(GroupPlan plan, FileOpType op) {
        try {
            return GroupFileOps.moveAll(plan.moves());
        } catch (RuntimeException e) {
            FileOpRecorder.recordFailure(FileOpModule.SONG, op, FileOpLevel.GROUP,
                    null, null, e.getMessage());
            throw e;
        }
    }

    /** 删除 + 失败记流水：deleteAll 部分失败（进了回收站的进了、剩下的列在异常里）先记 FAILED 再抛 */
    private static int deleteRecordingFailure(GroupPlan plan) {
        try {
            return GroupFileOps.deleteAll(plan.moves());
        } catch (RuntimeException e) {
            FileOpRecorder.recordFailure(FileOpModule.SONG, FileOpType.DELETE, FileOpLevel.GROUP,
                    null, null, e.getMessage());
            throw e;
        }
    }

    /** 组内每个文件的搬动计划：源目录就是组现在所在的目录 */
    private static List<FileMove> planMoves(SongGroup group, Path targetDir, String targetMainName) {
        return GroupFileOps.planMoves(group.allFiles(), group.dir(), targetDir, targetMainName);
    }

    /**
     * 归档闸门：{@code nya-entworks.song.require-tags-before-archive} 打开时，
     * 合并条目没有标签就拦下（需求「不打标签不能归档」，2026-09-22 起改成配置项、
     * 出厂 false ＝ 不拦）。
     * <p>标签挂在合并条目上（key = mergeKey），待打分时打的标签在归档后
     * （mergeKey 不变）原样保留，所以这里只查一次库即可。
     *
     * @return 拦截原因；可归档（或这一项关着）时为 {@code null}
     */
    private String requireTagForArchive(SongGroup group) {
        if (!properties.isRequireTagsBeforeArchive()) {
            return null;
        }
        String mergeKey = SongNameParser.mergeKey(group.name(), group.mainName());
        return tagService.listTags(mergeKey).isEmpty()
                ? "还没打标签，不能归档。先给这首歌打上标签再打分" : null;
    }

    /** 主名的版本号后缀：第一个 {@code #} 之后整段（如 {@code #2}），无则空串 */
    private static String versionSuffix(String mainName) {
        int hash = mainName.indexOf('#');
        return hash >= 0 ? mainName.substring(hash) : "";
    }

    /** 主名的去版本号基础名：第一个 {@code #} 之前整段 */
    private static String baseNameOf(String mainName) {
        int hash = mainName.indexOf('#');
        return hash >= 0 ? mainName.substring(0, hash) : mainName;
    }
}
