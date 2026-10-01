package io.github.Nyameph.nyaentworks.shout.service;

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
import io.github.Nyameph.nyaentworks.common.media.ScorePartition;
import io.github.Nyameph.nyaentworks.shout.config.ShoutProperties;
import io.github.Nyameph.nyaentworks.shout.service.ShoutGroupService.ShoutGroup;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 整组的改评分 / 改名 / 删除（喊麦）。
 *
 * <p>这里只负责喊麦这一侧的事：把「组 + 目标分区/目标主名」翻译成一张搬动清单（plan），
 * 以及搬完之后跟着改库（apply —— 镜像行与标签）。真正的搬动、拦不到的检查、中途回滚都在
 * {@link GroupFileOps}（与歌曲共用，理由见那个类的注释）。
 *
 * <p>比歌曲侧少的东西都是「喊麦不解析文件名」的直接后果：
 * <ul>
 *   <li>没有版本号语义：改名给的就是<b>完整主名</b>，不做 {@code #版本号} 后缀保留；</li>
 *   <li>没有规范化命名、没有原曲名级倍速回落，也没有跨主名归并 ——
 *       所以「批量」在这里只是「一行一组，接口形状与歌曲一致」，不是真的合并多组。</li>
 * </ul>
 *
 * <p>归档闸门与歌曲一致：待打分区的首次归档要不要先有标签，由
 * {@code nya-entworks.shout.require-tags-before-archive} 决定（出厂 false ＝ 不要求，
 * 2026-09-22 用户定；原先写死要求）。
 *
 * <p>顺序仍是<b>磁盘先动、库后动</b>：磁盘失败时库还没改，重来一次就是；反过来的话库指向
 * 一个不存在的分区，而那份默认倍速再也查不到。
 */
@Service
@RequiredArgsConstructor
public class ShoutArchiveService {

    private final ShoutGroupService groupService;
    private final ShoutStoreService storeService;
    private final ShoutProperties properties;

    /**
     * @param movedFiles   实际搬动（删除时为删掉）的文件数
     * @param settingMoved 库这一侧是否真的跟着动了 —— 改名/换分区/删除后镜像行与标签的更新，
     *                     或删除时把镜像行标成 MISSING
     */
    public record GroupApplyResult(String action, String mainName, String toPartition,
                                   String toMainName, int movedFiles, boolean settingMoved) {
    }

    /**
     * 批量操作里一组的定位。{@code partition} 为空表示待打分区。
     * <p>喊麦一行只有一个文件组（不归并），所以请求体里的 {@code groups} 通常只有一项 ——
     * 形状与歌曲一致是为了复用同一套前端动作与异步任务。
     */
    public record GroupRef(String partition, String mainName) {
    }

    /** 喊麦一行就是一组，所以「改名」只受理一组：多组改成同一个名字会互相撞名 */
    private static final String MULTI_RENAME_BLOCKED =
            "喊麦一行只有一个文件组，不能一次把多组改成同一个名字";

    // ------------------------------------------------------------------
    // 一次提交（评分 + 改名）：预演
    // ------------------------------------------------------------------

    /**
     * 一次提交（评分 + 改名）的预演。
     *
     * @param groups     要动的组（喊麦一行一组，多为一项；{@code partition} 为空表示待打分区）
     * @param score      目标评分；{@code null} 表示不改评分、留在原分区
     * @param newMainName 新主名（<b>完整主名，不带扩展名</b>）；与现主名相同表示不改名
     */
    public GroupPlan planEditBatch(List<GroupRef> groups, Integer score, String newMainName) {
        if (groups == null || groups.isEmpty()) {
            return new GroupPlan("EDIT", "", null, null, List.of(), "没有要操作的组");
        }
        String displayMain = groups.get(0).mainName();
        String targetMain = StringUtils.isBlank(newMainName)
                ? displayMain : GroupFileOps.requireMainName(newMainName);
        boolean willRename = !targetMain.equals(displayMain);
        boolean willScore = score != null;
        if (!willScore && !willRename) {
            return new GroupPlan("EDIT", displayMain, null, displayMain, List.of(),
                    "评分和名字都没变，没有要保存的");
        }
        if (willRename && groups.size() > 1) {
            return new GroupPlan("EDIT", displayMain, null, targetMain, List.of(),
                    MULTI_RENAME_BLOCKED);
        }

        Path targetDir = null;
        String targetPartition = null;
        if (willScore) {
            // 待打分区的首次归档：要不要先有标签见 nya-entworks.shout.require-tags-before-archive
            // （出厂不拦；改已归档的分这条永远不拦）
            boolean anyStaging = groups.stream().anyMatch(g -> StringUtils.isBlank(g.partition()));
            if (anyStaging) {
                ShoutGroup group = groupService.require(groups.get(0).partition(),
                        groups.get(0).mainName());
                String gate = requireTagForArchive(group);
                if (gate != null) {
                    return new GroupPlan("EDIT", displayMain, null, targetMain, List.of(), gate);
                }
            }
            try {
                // 这一档分区没建时 resolveDir 给一个裸 `#9`（不建目录），apply 的
                // GroupFileOps.moveAll 落盘时会 createDirectories 建出来（同歌曲侧）
                targetDir = ScorePartition.resolveDir(groupService.archivedRoot(), score);
                targetPartition = targetDir.getFileName().toString();
            } catch (RuntimeException e) {
                return new GroupPlan("EDIT", displayMain, null, targetMain, List.of(), e.getMessage());
            }
        }

        List<FileMove> moves = new ArrayList<>();
        for (GroupRef ref : groups) {
            ShoutGroup group = groupService.require(ref.partition(), ref.mainName());
            Path dir = willScore ? targetDir : group.dir();
            if (dir.equals(group.dir()) && !willRename) {
                continue;   // 已经在目标分区又没改名，不用动
            }
            moves.addAll(GroupFileOps.planMoves(group.allFiles(), group.dir(), dir, targetMain));
        }
        if (moves.isEmpty()) {
            return new GroupPlan("EDIT", displayMain, targetPartition, targetMain, List.of(),
                    "评分和名字都没变，没有要保存的");
        }
        return new GroupPlan("EDIT", displayMain, targetPartition, targetMain, moves, null);
    }

    // ------------------------------------------------------------------
    // 一次提交（评分 + 改名）：执行
    // ------------------------------------------------------------------

    /** 执行一次提交（评分 + 改名）。磁盘先动、库后动 */
    public GroupApplyResult applyEditBatch(List<GroupRef> groups, Integer score,
                                           String newMainName) {
        return applyEditBatch(groups, score, newMainName, null);
    }

    /** 带 taskId 的版本（异步任务 handler 传当前任务 id，改动流水对回任务页那一条） */
    public GroupApplyResult applyEditBatch(List<GroupRef> groups, Integer score,
                                           String newMainName, Long taskId) {
        return FileOpRecorder.batch(FileOpModule.SHOUT, FileOpSource.TASK, taskId, () -> {
            GroupPlan plan = planEditBatch(groups, score, newMainName);
            GroupFileOps.requireExecutable(plan);
            int moved;
            try {
                moved = GroupFileOps.moveAll(plan.moves());
            } catch (RuntimeException e) {
                // 失败（含整批回滚）也如实记一条 FAILED；记录是附属品，绝不影响主流程
                FileOpRecorder.recordFailure(FileOpModule.SHOUT, FileOpType.MOVE,
                        FileOpLevel.GROUP, null, null, e.getMessage());
                throw e;
            }
            FileOpRecorder.recordPlan(FileOpModule.SHOUT, plan, FileOpLevel.GROUP);
            boolean settingMoved = false;
            for (GroupRef ref : groups) {
                String targetPartition = plan.toPartition() == null
                        ? ref.partition() : plan.toPartition();
                // 分区与主名一次改完（用 id 锚定）—— 不能串成「先换分区再改名」：
                // 前者改掉分区后，后者按旧分区定位就查不到这一行了
                boolean changed = storeService.editGroup(ref.partition(), ref.mainName(),
                        targetPartition, score, plan.toMainName());
                settingMoved = settingMoved || changed;
            }
            return new GroupApplyResult("EDIT", plan.mainName(), plan.toPartition(),
                    plan.toMainName(), moved, settingMoved);
        });
    }

    // ------------------------------------------------------------------
    // 删除
    // ------------------------------------------------------------------

    /** 删除的预演：整组删。列出来是因为一次要送走整组文件，先让人看清是哪几个（会进回收站） */
    public GroupPlan planDeleteBatch(List<GroupRef> groups) {
        if (groups == null || groups.isEmpty()) {
            return new GroupPlan("DELETE", "", null, null, List.of(), "没有要操作的组");
        }
        List<FileMove> moves = new ArrayList<>();
        for (GroupRef ref : groups) {
            ShoutGroup group = groupService.require(ref.partition(), ref.mainName());
            moves.addAll(GroupFileOps.planDeletes(group.dir(), group.allFiles()));
        }
        return new GroupPlan("DELETE", groups.get(0).mainName(), null, null, moves, null);
    }

    /**
     * 执行删除。<b>文件送进 Windows 回收站</b>（2026-09-30 起；原先是真的删掉），
     * 所以只受理已经预演过、且预演里没有拦截的组。
     * <p>删到一半失败时不回滚（进回收站的那些不捞回来），把没进去的列出来让人知道现状。
     */
    public GroupApplyResult applyDeleteBatch(List<GroupRef> groups) {
        return applyDeleteBatch(groups, null);
    }

    /** 带 taskId 的版本（异步任务 handler 传当前任务 id） */
    public GroupApplyResult applyDeleteBatch(List<GroupRef> groups, Long taskId) {
        return FileOpRecorder.batch(FileOpModule.SHOUT, FileOpSource.TASK, taskId, () -> {
            GroupPlan plan = planDeleteBatch(groups);
            // 文件不在了不算拦截 —— 删除的目的就是让它不在，已经不在的跳过即可
            int deleted;
            try {
                deleted = GroupFileOps.deleteAll(plan.moves());
            } catch (RuntimeException e) {
                FileOpRecorder.recordFailure(FileOpModule.SHOUT, FileOpType.DELETE,
                        FileOpLevel.GROUP, null, null, e.getMessage());
                throw e;
            }
            FileOpRecorder.recordPlan(FileOpModule.SHOUT, plan, FileOpLevel.GROUP);
            boolean mirrorMarked = false;
            for (GroupRef ref : groups) {
                mirrorMarked = storeService.deleteGroup(ref.partition(), ref.mainName())
                        || mirrorMarked;
            }
            return new GroupApplyResult("DELETE", plan.mainName(), null, null, deleted, mirrorMarked);
        });
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 归档闸门：{@code nya-entworks.shout.require-tags-before-archive} 打开时，
     * 这组没有标签就拦下（需求「不打标签不能归档」，2026-09-22 起改成配置项、
     * 出厂 false ＝ 不拦，与歌曲/漫画同一天）。
     * <p>标签挂在主名上、跨分区共用，所以待打分时打的标签在归档后原样保留，这里只查一次库。
     *
     * @return 拦截原因；可归档（或这一项关着）时为 {@code null}
     */
    private String requireTagForArchive(ShoutGroup group) {
        if (!properties.isRequireTagsBeforeArchive()) {
            return null;
        }
        return storeService.listTags(group.mainName()).isEmpty()
                ? "还没打标签，不能归档。先给它打上标签再打分" : null;
    }
}
