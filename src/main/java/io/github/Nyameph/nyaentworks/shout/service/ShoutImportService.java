package io.github.Nyameph.nyaentworks.shout.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import io.github.Nyameph.nyaentworks.common.file.GroupFileOps;
import io.github.Nyameph.nyaentworks.common.file.GroupFileOps.FileMove;
import io.github.Nyameph.nyaentworks.common.file.GroupFileOps.GroupPlan;
import io.github.Nyameph.nyaentworks.common.fileop.FileOpRecorder;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpLevel;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpModule;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpType;
import io.github.Nyameph.nyaentworks.common.media.MediaExtensions;
import io.github.Nyameph.nyaentworks.common.media.ScorePartition;
import io.github.Nyameph.nyaentworks.shout.config.ShoutProperties;
import io.github.Nyameph.nyaentworks.shout.controller.ShoutController;
import io.github.Nyameph.nyaentworks.shout.service.ShoutArchiveService.GroupApplyResult;
import io.github.Nyameph.nyaentworks.shout.service.ShoutArchiveService.GroupRef;
import io.github.Nyameph.nyaentworks.shout.service.ShoutGroupService.ShoutGroup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 「添加文件」弹窗的后端（喊麦版）：源路径联动展开（expand）→ 预演（plan）→ 执行（apply）。
 * 「已归档页的修改弹窗」也复用同一份 plan / apply 核心（输入先归一成 {@link LandingGroup}）。
 *
 * <p><b>它是歌曲侧 {@code SongImportService} 的简化版</b>，砍掉的东西全是「喊麦不解析文件名」
 * 的直接后果：没有作者 / 曲名 / 原曲名三框、没有逐文件编号（{@code #} 只是普通字符）、
 * 没有「按表单重排文件名」（改名 = 一个可选的全名单框）、没有原曲层、没有
 * 「从已归档分区选文件」那条路（见下）。
 *
 * <p><b>与歌曲侧的两处口径差异</b>：
 * <ol>
 *   <li><b>源一律拒受管根</b>：待打分文件夹与已归档分区里的文件整批拒。
 *       歌曲允许从分区选（靠 {@code relocateArchivedRows} 防双条），喊麦不给这条路 ——
 *       库里本来就有这一组的行，用户想看/想改就直接在列表上行内改，不必「先选出来再加一遍」。
 *       少一条路 = 少一套防双条逻辑。</li>
 *   <li><b>一次只处理一组</b>：选中的文件（或「既有 + 补进来的」）落盘后的主名只许有一个。
 *       跨主名又没填新全名时拦下 —— 喊麦一行就是一组，「一次加进两组」在列表上看不出是一件事。
 *       填了新全名 = 明确要把它们合成一组，合法。</li>
 * </ol>
 *
 * <p>安全边界只有一条：<b>目标路径永远由后端算</b>。前端只传源路径与表单值（源天生在
 * 受管根之外）；修改那一路连源路径都不传（组由 partition + mainName 唯一确定，后端重扫）。
 *
 * <p>文件系统是权威、库是镜像：待打分层不入库每次现扫；{@code shout_file} 只覆盖已归档层。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShoutImportService {

    private final ShoutGroupService groupService;
    private final ShoutStoreService storeService;
    private final ShoutProperties properties;

    /** 联动展开后的一个文件 */
    public record ExpandedFile(String path, String fileName, String mainName, String ext,
                               String role) {
    }

    /**
     * 一个源目录的展开结果。
     *
     * @param blockedReason 该目录整批被拒的原因（源在受管根里）；非空时 files 为空
     * @param skipped       认不出扩展名或不是普通文件的路径（只回报，不进列表）
     */
    public record ExpandedGroup(String sourceDir, List<ExpandedFile> files,
                                List<String> skipped, String blockedReason) {
    }

    // ------------------------------------------------------------------
    // 内部归一形态：① 与 ② 都先翻成这一套，plan / apply 只写一份
    // ------------------------------------------------------------------

    /**
     * 一个已经在盘上定位好的源文件。
     *
     * @param source        绝对路径（① 来自请求、② 来自重扫）
     * @param groupKey      ② 里该文件所属组的定位键 {@code fromPartition|mainName}；① 用空串
     * @param fromPartition ② 的库侧锚点分区（待打分为空串）；① 为 null
     */
    public record LocatedFile(Path source, String fileName, String mainName,
                              String groupKey, String fromPartition) {
    }

    /** 目标：staging 或一个评分分区 */
    public record Target(Path dir, String partition, Integer score) {
    }

    /** 归一后的落盘项 */
    public record Landing(Path source, String fileName, String mainName,
                          Path targetDir, String targetMainName, String groupKey) {
    }

    /**
     * 一个「落盘组」的输入：这批文件 + 共用的新全名（空 = 不改名）+ 它们共同的目标。
     * <p>① 只有一组；② 每个 variant 各一组（各自留在自己的分区里）。
     */
    public record LandingGroup(List<LocatedFile> files, String newMainName, Target target) {
    }

    /** plan 的结果：搬动清单 + 跨卷提示（{@code FileMove} 不能加字段，所以提示挂在外层） */
    public record ImportPlan(GroupPlan plan, List<String> crossVolume) {

        public static ImportPlan blocked(String reason) {
            return new ImportPlan(new GroupPlan("IMPORT", "", null, null, List.of(), reason),
                    List.of());
        }
    }

    /** 归档目标工厂的返回：分区目录不存在时不抛、转成 blockedReason（不自动创建） */
    private record TargetOrBlocked(Target target, String blockedReason) {
    }

    /** 归档闸门的拦截语（① 与 ② 共用一份） */
    private static final String TAG_GATE_MESSAGE = "还没打标签，不能归档。先给它打上标签再打分";

    /** 迁移到未归档：目标是 stagingRoot()，没有评分。目录不存在不拦 —— moveAll 会建 */
    private Target stagingTarget() {
        return new Target(groupService.stagingRoot(), null, null);
    }

    /**
     * 归档闸门（① 与 ② 共用）。{@code nya-entworks.shout.require-tags-before-archive}
     * 打开时（出厂 false ＝ 不拦），这一组<b>落盘之后</b>一个标签都没有就拦下。
     *
     * <p>与 {@code ShoutArchiveService#requireTagForArchive} 同一口径、同一个属性；差别只在
     * 判的时点：那一道的组在库里已有行、直接查库即可，这里判的是「<b>这次提交之后</b>有没有
     * 标签」—— 提交带了数组就以数组为准（<b>清空数组 = 提交后没标签，照样拦</b>），
     * 数组为 {@code null}（这次不动标签）才回落到库里现存的那份。
     *
     * @param tags 这次提交带的标签；{@code null} = 这次不动标签
     * @return 拦截原因；可归档（或这一项关着、或目标不是评分分区）时为 {@code null}
     */
    private String requireTagForArchive(List<LandingGroup> groups, List<String> tags) {
        if (!properties.isRequireTagsBeforeArchive() || groups == null || groups.isEmpty()) {
            return null;
        }
        LandingGroup group = groups.get(0);
        // 只拦「搬到评分分区」这种归档：staging 目标没有评分，迁移到未归档不拦
        if (group.target().score() == null || group.files().isEmpty()) {
            return null;
        }
        if (tags != null) {
            return tags.isEmpty() ? TAG_GATE_MESSAGE : null;
        }
        String targetMain = targetMainOf(group, group.files().get(0));
        return storeService.listTags(targetMain).isEmpty() ? TAG_GATE_MESSAGE : null;
    }

    /** 归档目标：{@code #<分数><评语>} 分区；目录不在时 blockedReason 是 requireDir 的原文 */
    private TargetOrBlocked archiveTarget(int score) {
        try {
            Path dir = ScorePartition.requireDir(groupService.archivedRoot(), score);
            return new TargetOrBlocked(
                    new Target(dir, dir.getFileName().toString(), score), null);
        } catch (RuntimeException e) {
            return new TargetOrBlocked(null, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 核心：plan / apply
    // ------------------------------------------------------------------

    /** ① 的预演 */
    public ImportPlan plan(List<LandingGroup> groups) {
        return plan(groups, List.of(), false);
    }

    /**
     * 带「剔除 → 冗余」搬动的重载：那些搬动并入同一张清单 —— 确认页看得到
     * 「→ …\冗余\…」、批内查重覆盖它们、apply 一次 moveAll 原子完成。
     *
     * @param editForm ②（列表式修改）传 true —— 决定 action 记成 EDIT 而不是添加
     */
    private ImportPlan plan(List<LandingGroup> groups, List<FileMove> extraMoves,
                            boolean editForm) {
        List<LandingGroup> safeGroups = groups == null ? List.of() : groups;
        List<LocatedFile> allFiles = new ArrayList<>();
        for (LandingGroup group : safeGroups) {
            allFiles.addAll(group.files());
        }
        if (allFiles.isEmpty() && (extraMoves == null || extraMoves.isEmpty())) {
            return ImportPlan.blocked("没有要处理的文件");
        }
        // 目标分区必须在盘上（staging 不拦：moveAll 的 createDirectories 会建）
        for (LandingGroup group : safeGroups) {
            if (group.target().score() != null && !Files.isDirectory(group.target().dir())) {
                return ImportPlan.blocked("目标分区目录不在磁盘上："
                        + group.target().dir() + "。分区目录是手工建的，缺了说明盘没挂上或还没建");
            }
        }

        // 逐文件算 Landing。新全名只在填了的时候校验一次
        String batchNewMain = newMainNameOf(safeGroups);
        if (StringUtils.isNotBlank(batchNewMain)) {
            try {
                GroupFileOps.requireMainName(batchNewMain);
            } catch (IllegalArgumentException e) {
                return ImportPlan.blocked("新名字不行：" + e.getMessage());
            }
        }
        List<Landing> landings = new ArrayList<>();
        for (LandingGroup group : safeGroups) {
            for (LocatedFile file : group.files()) {
                landings.add(new Landing(file.source(), file.fileName(), file.mainName(),
                        group.target().dir(), targetMainOf(group, file), file.groupKey()));
            }
        }

        // 「一次只处理一组」：落盘后的主名只许有一个。填了新全名时天然只有一个；
        // 没填就各自保持原名，跨主名 = 这一次要加进两组，拦下并告诉人分了哪几组
        Set<String> targetMains = new LinkedHashSet<>();
        for (Landing landing : landings) {
            targetMains.add(landing.targetMainName());
        }
        if (targetMains.size() > 1) {
            List<String> shown = targetMains.stream().limit(3).toList();
            return ImportPlan.blocked("一次只能处理一组：这次的文件属于 " + targetMains.size()
                    + " 组（" + String.join("、", shown)
                    + (targetMains.size() > shown.size() ? " 等" : "")
                    + "）。要么只留一组的文件，要么在最上面的名字框填一个新名把它们合成一组");
        }

        // 按 (源目录, 目标目录, 目标主名) 分组，每组调一次 planMoves。
        // 落盘后的「组」正是这个三元组定义的（scanDir 的归组口径 = 目录 + mainName）
        record Pair(Path sourceDir, Path targetDir, String targetMainName) {
        }
        Map<Pair, List<Landing>> byPair = new LinkedHashMap<>();
        for (Landing landing : landings) {
            byPair.computeIfAbsent(new Pair(landing.source().getParent(),
                    landing.targetDir(), landing.targetMainName()), k -> new ArrayList<>())
                    .add(landing);
        }
        List<FileMove> moves = new ArrayList<>();
        if (extraMoves != null && !extraMoves.isEmpty()) {
            moves.addAll(extraMoves);
        }
        for (Map.Entry<Pair, List<Landing>> entry : byPair.entrySet()) {
            Pair pair = entry.getKey();
            List<String> fileNames = entry.getValue().stream().map(Landing::fileName).toList();
            for (FileMove move : GroupFileOps.planMoves(fileNames, pair.sourceDir(),
                    pair.targetDir(), pair.targetMainName)) {
                // 「已经在目标目录里、名字也一致」的行不进清单 —— 那是合法输入，不是要搬的
                if (!move.fromPath().equalsIgnoreCase(move.toPath())) {
                    moves.add(move);
                }
            }
        }

        // 批内目标路径查重（toLowerCase，Windows 口径）。planMoves 只比磁盘现状，
        // 查不到「本批自己撞自己」
        Map<String, FileMove> byTarget = new LinkedHashMap<>();
        for (FileMove move : moves) {
            FileMove prev = byTarget.putIfAbsent(move.toPath().toLowerCase(Locale.ROOT), move);
            if (prev != null) {
                // 文案与歌曲侧那条刻意不同：歌曲有「逐文件编号」能把同名的分开，
                // 喊麦没有（改名是整组一个全名框），所以不能让人去「改其中一个的名」
                return ImportPlan.blocked("两个文件会落到同一个目标路径：" + prev.fromPath()
                        + " 与 " + move.fromPath() + " → " + move.toPath()
                        + "。喊麦一组里同一个扩展名只能有一个文件 —— "
                        + "去掉多出来的那个，或者分成两次做");
            }
        }

        String action = actionOf(safeGroups, editForm);
        // 全部文件都被剔除（landings 空）时用第一剔除行当显示名
        String displayMain = allFiles.isEmpty()
                ? extraMoves.get(0).fileName() : allFiles.get(0).mainName();
        String toDisplayMain = landings.isEmpty()
                ? extraMoves.get(0).toFileName() : landings.get(0).targetMainName();
        String toPartition = safeGroups.isEmpty() ? null : safeGroups.get(0).target().partition();
        GroupPlan plan = new GroupPlan(action, displayMain, toPartition, toDisplayMain,
                List.copyOf(moves), null);
        return new ImportPlan(plan, crossVolumeOf(moves));
    }

    /**
     * plan 用的 action：① 走 SHOUT_IMPORT_STAGING / SHOUT_IMPORT_ARCHIVE
     * （FileOpRecorder 的映射表里分别是「添加喊麦：迁移到未归档」「添加喊麦：归档」）；
     * ② 是既有组的一次提交，用 EDIT。
     *
     * <p>用显式标志而不是「有没有 groupKey」判：整组全剔除时 files 是空的，
     * 那种提交仍然是「改」而不是「添加」
     */
    private static String actionOf(List<LandingGroup> groups, boolean editForm) {
        if (editForm) {
            return "EDIT";
        }
        Integer score = groups.isEmpty() ? null : groups.get(0).target().score();
        return score != null ? "SHOUT_IMPORT_ARCHIVE" : "SHOUT_IMPORT_STAGING";
    }

    /** 跨卷提示（不是拦截）：搬动允许任意来源之后，跨卷从「不可能」变成日常 */
    private static List<String> crossVolumeOf(List<FileMove> moves) {
        List<String> crossVolume = new ArrayList<>();
        for (FileMove move : moves) {
            if (move.blockedReason() != null) {
                continue;
            }
            try {
                if (!Files.getFileStore(Path.of(move.fromPath()))
                        .equals(Files.getFileStore(Path.of(move.toPath())))) {
                    crossVolume.add(move.fileName());
                }
            } catch (IOException e) {
                // 拿不到 FileStore（源已不在等）就不提示 —— planMoves 那边会给 blockedReason
            }
        }
        return List.copyOf(crossVolume);
    }

    /**
     * ① 与 ② 共用的执行：搬 → 写标签 → （② 时）回写库。顺序不能反（磁盘先动、库后动）。
     *
     * @param libraryAnchors 库侧锚点，<b>必须在搬动之前从请求里读好传进来</b>：
     *                       搬完旧目录里没有那些文件了，现算会抛「找不到这一组」。
     *                       ① 恒为空列表（新导入的组库里还没有行，入库交给同步补建）
     */
    public GroupApplyResult apply(List<LandingGroup> groups, List<String> tags,
                                  List<GroupRef> libraryAnchors) {
        return apply(groups, tags, libraryAnchors, List.of(), null, false);
    }

    /**
     * 带剔除与复用 plan 的重载。{@code excluded} 的搬动已在 plan 清单里（同一批原子搬），
     * 搬成功后逐行做库侧收尾（{@code shout_id=0} + 冗余名），记录按「剔除：移入冗余」
     * 单独记 OTHER。{@code prepared} 供 ① 复用已算好的 plan，为 null 时内部重算。
     */
    public GroupApplyResult apply(List<LandingGroup> groups, List<String> tags,
                                  List<GroupRef> libraryAnchors, List<ExcludedFile> excluded,
                                  ImportPlan prepared, boolean editForm) {
        List<FileMove> redundantMoves = redundantMoves(excluded);
        // 重跑 plan 做二次校验（请求可能在队列里躺了一夜）：blocked 就抛，任务失败、
        // 理由给人看，不静默跳过（符合「由人在任务页点重新执行」的既有形态）
        ImportPlan preparedPlan = prepared != null
                ? prepared : plan(groups, redundantMoves, editForm);
        GroupPlan plan = preparedPlan.plan();
        if (plan.blocked()) {
            throw new IllegalStateException(plan.firstBlockedReason());
        }

        Set<String> redundantFrom = new LinkedHashSet<>();
        for (FileMove rm : redundantMoves) {
            redundantFrom.add(rm.fromPath());
        }

        // 搬。moves 为空是合法输入（已经在未归档里、名字也一致）：什么都不搬，
        // 只走标签与库那侧 —— 这一句判空不能省（requireExecutable 对空清单也抛）
        int moved = 0;
        if (!plan.moves().isEmpty()) {
            GroupFileOps.requireExecutable(plan);
            try {
                moved = GroupFileOps.moveAll(plan.moves());
            } catch (RuntimeException e) {
                FileOpRecorder.recordFailure(FileOpModule.SHOUT, FileOpType.MOVE,
                        FileOpLevel.GROUP, null, null, e.getMessage());
                throw e;
            }
            // 搬成功才算数；记录是附属品，记不上不影响搬动。
            // 剔除 → 冗余的行单独按 OTHER 记，页面上能与常规搬动分开看
            List<FileMove> normalMoves = plan.moves().stream()
                    .filter(mv -> !redundantFrom.contains(mv.fromPath()))
                    .toList();
            FileOpRecorder.recordPlan(FileOpModule.SHOUT,
                    new GroupPlan(plan.action(), plan.mainName(), plan.toPartition(),
                            plan.toMainName(), normalMoves, plan.blockedReason()),
                    FileOpLevel.GROUP);
            for (FileMove rm : redundantMoves) {
                FileOpRecorder.recordPath(FileOpModule.SHOUT, FileOpType.MOVE,
                        FileOpLevel.OTHER, "剔除：移入冗余", rm.fromPath(), rm.toPath());
            }
            // 库侧收尾：冗余行置 shout_id=0。行不存在（未同步）只记 warn —— 磁盘已经动了，
            // 行交给同步按现状收敛
            for (ExcludedFile ex : excluded == null ? List.<ExcludedFile>of() : excluded) {
                FileMove rm = plan.moves().stream()
                        .filter(mv -> mv.fromPath().equals(ex.source()))
                        .findFirst().orElse(null);
                if (rm != null && !storeService.markFileRedundant(ex.fromPartition(),
                        ex.mainName(), ex.fileName(), rm.toFileName())) {
                    log.warn("冗余收尾：库里没有「{} / {}」的行（未同步？），磁盘已移入冗余，行交给同步",
                            ex.fromPartition(), ex.mainName());
                }
            }
        }

        // 标签：按落盘后的主名各写一次。喊麦的标签键就是主名（没有歌曲那个合并键）。
        // tags 为 null 表示这次不动标签（前端只在变了的时候才送数组）
        if (tags != null) {
            Set<String> doneGroups = new LinkedHashSet<>();
            for (LandingGroup group : groups) {
                for (LocatedFile file : group.files()) {
                    String targetMain = targetMainOf(group, file);
                    if (!doneGroups.add(group.target().dir() + "|" + targetMain)) {
                        continue;
                    }
                    storeService.replaceTags(targetMain, tags);
                }
            }
        }

        // 库那侧（② 才有锚点）。score = null 表示没改评分 —— 方法内部跳过评分列，
        // 但只改名也照样要调它，否则 shout_file.main_name 停在旧名上
        boolean settingMoved = false;
        for (GroupRef anchor : libraryAnchors == null ? List.<GroupRef>of() : libraryAnchors) {
            String newMainName = newMainNameOf(groups, anchor);
            Target target = targetOfAnchor(groups, anchor);
            settingMoved = storeService.editGroup(anchor.partition(), anchor.mainName(),
                    target.partition(), target.score(), newMainName) || settingMoved;
        }

        return new GroupApplyResult(plan.action(), plan.mainName(), plan.toPartition(),
                plan.toMainName(), moved, settingMoved);
    }

    /**
     * 这个文件落盘后的主名：没填新全名 = 原主名；填了 = 那个名字（全部文件共用）。
     * plan 与 apply 共用 —— 两边各算一遍就会出现「清单上的名字与落盘的名字不一致」
     */
    private static String targetMainOf(LandingGroup group, LocatedFile file) {
        return StringUtils.isBlank(group.newMainName())
                ? file.mainName() : group.newMainName();
    }

    /** 整批共用的新全名（都填的一样；① 与 ② 都只有一份）。全空时返回 null */
    private static String newMainNameOf(List<LandingGroup> groups) {
        for (LandingGroup group : groups) {
            if (StringUtils.isNotBlank(group.newMainName())) {
                return group.newMainName();
            }
        }
        return null;
    }

    /** 锚点对应的落盘主名：它名下的文件要么没改名（原主名）、要么都改成同一个新主名 */
    private static String newMainNameOf(List<LandingGroup> groups, GroupRef anchor) {
        String key = StringUtils.defaultString(anchor.partition()) + "|" + anchor.mainName();
        for (LandingGroup group : groups) {
            for (LocatedFile file : group.files()) {
                if (key.equals(file.groupKey())) {
                    return targetMainOf(group, file);
                }
            }
        }
        return anchor.mainName();
    }

    /** 锚点对应的 Target：它名下文件所在的那个 LandingGroup 的目标 */
    private static Target targetOfAnchor(List<LandingGroup> groups, GroupRef anchor) {
        String key = StringUtils.defaultString(anchor.partition()) + "|" + anchor.mainName();
        for (LandingGroup group : groups) {
            for (LocatedFile file : group.files()) {
                if (key.equals(file.groupKey())) {
                    return group.target();
                }
            }
        }
        // 找不到（锚点没有对应文件）：不搬不评分，留在现状分区
        return new Target(null, anchor.partition(), null);
    }

    // ------------------------------------------------------------------
    // ① 添加文件弹窗：请求 → LandingGroup → plan / apply
    // ------------------------------------------------------------------

    /** ① 的预演。请求翻译出错（target 与 score 打架等）转成 blocked，让人在确认页看到 */
    public ImportPlan planImport(ShoutController.ImportRequest request) {
        try {
            LandingGroup group = toLandingGroup(request);
            String gate = requireTagForArchive(List.of(group), request.tags());
            if (gate != null) {
                return ImportPlan.blocked(gate);
            }
            return plan(List.of(group));
        } catch (IllegalArgumentException e) {
            return ImportPlan.blocked(e.getMessage());
        }
    }

    /** ① 的执行（跑在异步任务线程上，handler 里已用 FileOpRecorder.batch 包住） */
    public GroupApplyResult applyImport(ShoutController.ImportRequest request) {
        LandingGroup group = toLandingGroup(request);
        // 归档闸门再算一遍：请求可能在队列里躺了一夜，标签是那次提交带进来的
        String gate = requireTagForArchive(List.of(group), request.tags());
        if (gate != null) {
            throw new IllegalStateException(gate);
        }
        // 只读预演先算好（apply 内部会再算一遍做二次校验，两份口径同一方法）
        ImportPlan prepared = plan(List.of(group));
        return apply(List.of(group), request.tags(), List.of(), List.of(), prepared, false);
    }

    /** ① 的请求翻译：一个 LandingGroup（一份新全名 + 一个 Target） */
    private LandingGroup toLandingGroup(ShoutController.ImportRequest request) {
        if (request == null || request.files() == null || request.files().isEmpty()) {
            throw new IllegalArgumentException("没有选择要添加的文件");
        }
        List<LocatedFile> files = new ArrayList<>();
        for (ShoutController.ImportFile file : request.files()) {
            if (file == null || StringUtils.isBlank(file.sourcePath())) {
                throw new IllegalArgumentException("有文件没给源路径");
            }
            Path source = realPath(Path.of(file.sourcePath()));
            String fileName = source.getFileName().toString();
            files.add(new LocatedFile(source, fileName,
                    MediaExtensions.mainName(fileName), "", null));
        }
        // 「只有歌词没有媒体」的组不能进 —— ShoutGroupService 归组时把它整组跳过
        // （播不了、也不该占一行），放进来就是搬进一个页面上永远看不见、也归档不了的角落。
        // 这里用与那一处**同一个判据**（isVideo / isAudio），不自己按 role 再写一份
        if (files.stream().noneMatch(f ->
                MediaExtensions.isVideo(f.fileName()) || MediaExtensions.isAudio(f.fileName()))) {
            throw new IllegalArgumentException(
                    "这批文件只有歌词、没有音频或视频，搬过去不会出现在列表里（那一层只认能播的组）—— "
                            + "把音频或视频一起选上");
        }
        Target target;
        if ("staging".equals(request.target())) {
            if (request.score() != null) {
                throw new IllegalArgumentException(
                        "目标是未归档文件夹时不能带评分 —— 两个字段表达的是同一件事，别让它们打架");
            }
            target = stagingTarget();
        } else {
            if (request.score() == null) {
                throw new IllegalArgumentException(
                        "目标是评分分区时必须给评分（target=" + request.target() + "）");
            }
            TargetOrBlocked archive = archiveTarget(request.score());
            if (archive.blockedReason() != null) {
                throw new IllegalArgumentException(archive.blockedReason());
            }
            if (!archive.target().partition().equals(request.target())) {
                throw new IllegalArgumentException("target（" + request.target()
                        + "）与 score（" + request.score() + "）对应的分区（"
                        + archive.target().partition() + "）不一致 —— 两个字段表达的是同一件事");
            }
            target = archive.target();
        }
        return new LandingGroup(List.copyOf(files), request.newMainName(), target);
    }

    // ------------------------------------------------------------------
    // ② 既有组的列表式改造：输入准备 + 提交口
    // ------------------------------------------------------------------

    /** ② 的输入准备结果：要么是可用的组，要么是一条整批拦截的原因 */
    private record EditFormInput(List<LandingGroup> groups, List<GroupRef> anchors,
                                 String blockedReason, List<ExcludedFile> excluded) {
    }

    /**
     * ② 剔除的已归档文件：提交后移入「冗余」文件夹（{@code shout_id=0}）。
     * 定位库行靠 (fromPartition, mainName, fileName 的后缀)。
     */
    public record ExcludedFile(String fromPartition, String mainName,
                               String fileName, String source) {
    }

    /** 已归档根下的「冗余」文件夹（剔除文件的去处；不是分区形态，扫描/同步都不碰它） */
    public Path redundantDir() {
        return Path.of(groupService.archivedRoot(), "冗余");
    }

    /**
     * ② 的预演（与 ① 同一份 plan）。
     * <p>源路径由后端定位（组由 partition + mainName 唯一确定），前端只回传
     * 逐文件剔除勾选 + 新全名 + 打分 + 标签 + 补进来的外部文件。
     */
    public ImportPlan planEditForm(ShoutController.EditFormRequest req) {
        EditFormInput input = locatedFiles(req);
        if (input.blockedReason() != null) {
            return ImportPlan.blocked(input.blockedReason());
        }
        // 归档闸门（与 ShoutArchiveService 那道同一口径、同一属性）：② 这条路
        // 也可能把待打分的组搬进评分分区
        String gate = requireTagForArchive(input.groups(), req.tags());
        if (gate != null) {
            return ImportPlan.blocked(gate);
        }
        return plan(input.groups(), redundantMoves(input.excluded()), true);
    }

    /**
     * ② 的执行（跑在异步任务线程上，handler 里已用 FileOpRecorder.batch 包住）。
     * 锚点在搬动之前从请求里读好 —— 搬完旧目录里没有那些文件了，现算会抛。
     */
    public GroupApplyResult applyEditForm(ShoutController.EditFormRequest req) {
        EditFormInput input = locatedFiles(req);
        if (input.blockedReason() != null) {
            throw new IllegalStateException(input.blockedReason());
        }
        String gate = requireTagForArchive(input.groups(), req.tags());
        if (gate != null) {
            throw new IllegalStateException(gate);
        }
        return apply(input.groups(), req.tags(), input.anchors(), input.excluded(), null, true);
    }

    /**
     * 剔除文件的搬动计划：组目录 → 冗余目录，<b>同名自动加 {@code _冗余N}</b>
     * （磁盘防覆盖、唯一键防撞车一起解决）。源不在了照样进清单打上拦截 ——
     * 它与常规搬动同批原子执行，plan.blocked() 会拦下整批。
     */
    private List<FileMove> redundantMoves(List<ExcludedFile> excluded) {
        if (excluded == null || excluded.isEmpty()) {
            return List.of();
        }
        Path dir = redundantDir();
        List<FileMove> moves = new ArrayList<>();
        for (ExcludedFile ex : excluded) {
            Path from = Path.of(ex.source());
            String base = MediaExtensions.mainName(ex.fileName());
            String suffix = MediaExtensions.suffix(ex.fileName(), base);
            Path to = dir.resolve(ex.fileName());
            int n = 2;
            while (Files.exists(to)) {
                to = dir.resolve(base + "_冗余" + n + suffix);
                n++;
            }
            moves.add(new FileMove(ex.fileName(), from.toString(), to.getFileName().toString(),
                    to.toString(), Files.isRegularFile(from) ? null : "源文件不在了：" + from));
        }
        return List.copyOf(moves);
    }

    /**
     * ② 的输入准备：逐组定位到盘上的组、逐文件对齐，翻成 {@link LandingGroup}。
     * <p>「源必须在受管根里」不需要额外代码：{@code require} 只在 staging / 评分分区里找。
     */
    private EditFormInput locatedFiles(ShoutController.EditFormRequest req) {
        if (req == null || req.groups() == null || req.groups().isEmpty()) {
            return new EditFormInput(List.of(), List.of(), "没有要操作的组", List.of());
        }
        // 喊麦一行只有一个文件组：多组 + 改名 = 把几组并成一组，那是另一件事
        if (req.groups().size() > 1 && StringUtils.isNotBlank(req.newMainName())) {
            return new EditFormInput(List.of(), List.of(),
                    "喊麦一行只有一个文件组，不能一次把多组改成同一个名字", List.of());
        }
        String newMainName = req.newMainName();
        List<LandingGroup> groups = new ArrayList<>();
        List<GroupRef> anchors = new ArrayList<>();
        List<ExcludedFile> excluded = new ArrayList<>();
        for (ShoutController.EditFormGroup g : req.groups()) {
            // 用既有的归组口径定位（不自己拼目录路径）。「做」以重扫为准（画用库、做用盘）
            ShoutGroup group;
            try {
                group = groupService.require(g.partition(), g.mainName());
            } catch (RuntimeException e) {
                return new EditFormInput(List.of(), List.of(),
                        "这组在磁盘上已经不在了，先点「重新扫描」同步一次（"
                                + StringUtils.defaultIfBlank(g.partition(), "待打分")
                                + " / " + g.mainName() + "）", List.of());
            }
            // 逐文件对齐：找不到就整批不执行 —— 漏搬正是「组裂开」的成因
            List<LocatedFile> files = new ArrayList<>();
            int excludedInGroup = 0;
            for (ShoutController.EditFormFile f
                    : g.files() == null ? List.<ShoutController.EditFormFile>of() : g.files()) {
                if (f == null || !group.allFiles().contains(f.fileName())) {
                    String missing = f == null ? "（空）" : f.fileName();
                    return new EditFormInput(List.of(), List.of(),
                            "「" + missing + "」在磁盘上已经不在这一组里了，先重新扫描"
                                    + "（" + StringUtils.defaultIfBlank(g.partition(), "待打分")
                                    + " / " + g.mainName() + "）", List.of());
                }
                // exclude = 剔除已归档文件：不进常规搬动清单，单独收进冗余清单
                if (Boolean.TRUE.equals(f.exclude()) && StringUtils.isNotBlank(g.partition())) {
                    excluded.add(new ExcludedFile(StringUtils.defaultString(g.partition()),
                            g.mainName(), f.fileName(),
                            group.dir().resolve(f.fileName()).toString()));
                    excludedInGroup++;
                    continue;
                }
                files.add(new LocatedFile(group.dir().resolve(f.fileName()), f.fileName(),
                        group.mainName(),
                        StringUtils.defaultString(g.partition()) + "|" + g.mainName(),
                        StringUtils.defaultString(g.partition())));
            }
            // 全部剔除 = 解散这一组到冗余，是合法提交（files 空 + excluded 非空照常走）
            if (files.isEmpty() && excludedInGroup == 0) {
                return new EditFormInput(List.of(), List.of(),
                        "「" + g.mainName() + "」没有给出要处理的文件", List.of());
            }
            // 目标：不搬（score null）= 现状分区；搬（改评分）= 新分区
            Target target;
            if (req.score() == null) {
                target = new Target(group.dir(), group.partitionName(), null);
            } else {
                TargetOrBlocked archive = archiveTarget(req.score());
                if (archive.blockedReason() != null) {
                    return new EditFormInput(List.of(), List.of(), archive.blockedReason(), List.of());
                }
                target = archive.target();
            }
            groups.add(new LandingGroup(List.copyOf(files), newMainName, target));
            anchors.add(new GroupRef(g.partition(), g.mainName()));
        }
        // ② 补进来的外部文件（canAdd 选进来的受管根之外的文件）：翻成一个附加组，
        // 共用新全名与第一组的目标。库侧锚点不含它们：新文件不属于任何既有组，入库交同步补建
        if (req.extraFiles() != null && !req.extraFiles().isEmpty()) {
            List<LocatedFile> extra = new ArrayList<>();
            for (ShoutController.ImportFile ef : req.extraFiles()) {
                if (ef == null || StringUtils.isBlank(ef.sourcePath())) {
                    continue;
                }
                Path source = realPath(Path.of(ef.sourcePath()));
                String fileName = source.getFileName().toString();
                extra.add(new LocatedFile(source, fileName,
                        MediaExtensions.mainName(fileName), "", null));
            }
            if (!extra.isEmpty()) {
                // 补进来的文件**跟着这一组走**：目标主名取「新全名，没有就取这组现在的名」。
                // 不能让它们回落成各自的主名 —— 那样「补一个 lrc」会被当成「两组」，
                // 撞上「一次只能处理一组」那道闸门（喊麦没有三框，主名不是手填的）
                String extraMain = StringUtils.isNotBlank(newMainName)
                        ? newMainName
                        : anchors.get(0).mainName();
                groups.add(new LandingGroup(List.copyOf(extra), extraMain, groups.get(0).target()));
            }
        }
        return new EditFormInput(List.copyOf(groups), List.copyOf(anchors), null,
                List.copyOf(excluded));
    }

    // ------------------------------------------------------------------
    // ① 联动展开
    // ------------------------------------------------------------------

    /**
     * 源路径 → 联动展开后的文件清单。只读磁盘。
     *
     * <p>「同名不同后缀自动联动」用的就是后端既有的归组口径
     * （{@link ShoutGroupService#scanDirectory} 按 {@code mainName} 归组）。
     *
     * <p><b>先放锚点、再补联动</b>：{@code groupFiles} 会丢掉「只有歌词没有媒体」的组，
     * 只用它的结果会让用户选中一个孤儿歌词时列表变空、看起来像点了没反应。
     * 保留锚点 → 它进列表、被「至少一个媒体文件」的表单闸门拦下并给人话理由。
     */
    public List<ExpandedGroup> expand(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return List.of();
        }
        // 逐个 path 定位（真实路径 + 分类），按源目录分组
        Map<Path, List<Path>> byDir = new LinkedHashMap<>();
        Map<Path, List<String>> skippedByDir = new LinkedHashMap<>();
        for (String raw : paths) {
            Path path = parsePath(raw);
            if (path == null) {
                skippedByDir.computeIfAbsent(parentOf(raw), k -> new ArrayList<>())
                        .add("不是合法路径：" + raw);
                continue;
            }
            if (!Files.isRegularFile(path)) {
                skippedByDir.computeIfAbsent(path.getParent(), k -> new ArrayList<>())
                        .add("不是文件（或已不在）：" + path);
                continue;
            }
            String fileName = path.getFileName().toString();
            if (!MediaExtensions.isKnown(fileName)) {
                skippedByDir.computeIfAbsent(path.getParent(), k -> new ArrayList<>())
                        .add("不认识的扩展名，不当喊麦文件：" + fileName);
                continue;
            }
            byDir.computeIfAbsent(path.getParent(), k -> new ArrayList<>())
                    .add(realPath(path));
        }

        List<ExpandedGroup> result = new ArrayList<>();
        for (Map.Entry<Path, List<Path>> entry : byDir.entrySet()) {
            Path sourceDir = entry.getKey();
            List<Path> selected = entry.getValue();

            // 组内任一文件命中受管根（待打分根 / 评分分区）→ 整组拒，理由照给人
            String blocked = null;
            for (Path anchor : selected) {
                blocked = managedRootReason(anchor);
                if (blocked != null) {
                    break;
                }
            }
            if (blocked != null) {
                result.add(new ExpandedGroup(sourceDir.toString(), List.of(),
                        skippedByDir.getOrDefault(sourceDir, List.of()), blocked));
                continue;
            }

            // 先放锚点（用户选中的），再补联动（同 mainName 的那个组）。
            // 联动只在同一个源目录内：跨目录同名多半是不同的东西
            Map<String, ExpandedFile> merged = new LinkedHashMap<>();
            Set<String> anchorMainNames = new LinkedHashSet<>();
            for (Path anchor : selected) {
                merged.put(anchor.toString(), expandedOf(anchor));
                anchorMainNames.add(MediaExtensions.mainName(anchor.getFileName().toString()));
            }
            for (ShoutGroup group : groupService.scanDirectory(sourceDir)) {
                if (!anchorMainNames.contains(group.mainName())) {
                    continue;   // 同目录的其它组不进列表：mainName 不同就是另一组
                }
                for (String fileName : group.allFiles()) {
                    Path file = sourceDir.resolve(fileName);
                    merged.putIfAbsent(file.toString(), expandedOf(file));
                }
            }

            // 排序：video → audio → 歌词（与 GroupFileOps.entries 同一口径）
            List<ExpandedFile> files = new ArrayList<>(merged.values());
            files.sort(Comparator.comparingInt(f -> roleOrder(f.role())));
            result.add(new ExpandedGroup(sourceDir.toString(), List.copyOf(files),
                    skippedByDir.getOrDefault(sourceDir, List.of()), null));
        }
        // 只有 skipped、没有可展开文件的目录也回报（用户看得见自己选的东西被丢了）
        for (Map.Entry<Path, List<String>> entry : skippedByDir.entrySet()) {
            if (!byDir.containsKey(entry.getKey())) {
                result.add(new ExpandedGroup(entry.getKey().toString(), List.of(),
                        List.copyOf(entry.getValue()), null));
            }
        }
        return result;
    }

    /** 展开一个文件 */
    private ExpandedFile expandedOf(Path file) {
        String fileName = file.getFileName().toString();
        return new ExpandedFile(file.toString(), fileName,
                MediaExtensions.mainName(fileName), MediaExtensions.extension(fileName),
                roleOf(fileName));
    }

    /** 角色判定与 groupFiles 的分派同一口径；闸门靠它判「至少一个媒体文件」 */
    private static String roleOf(String fileName) {
        if (MediaExtensions.isVideo(fileName)) {
            return "VIDEO";
        }
        if (MediaExtensions.isAudio(fileName)) {
            return "AUDIO";
        }
        return "LYRIC";
    }

    private static int roleOrder(String role) {
        return switch (role) {
            case "VIDEO" -> 0;
            case "AUDIO" -> 1;
            default -> 2;
        };
    }

    /**
     * 源在受管根里就拒（只有这一处判据）。
     * <p>只判<b>直接父目录</b>：文件平铺在分区里是既有事实（扫描不递归）。
     *
     * @return 拒绝原因；不受管（外部路径）时为 {@code null}
     */
    private String managedRootReason(Path file) {
        Path parent = file.getParent();
        if (parent == null) {
            return null;
        }
        Path dir = normalize(parent);
        if (dir.equals(normalize(groupService.stagingRoot()))) {
            return "这是待打分文件夹里的喊麦，助手本来就管着它。要改就在列表那一行上点「归档」/「修改」";
        }
        String parentName = parent.getFileName() == null ? "" : parent.getFileName().toString();
        for (ScorePartition.Partition partition : ScorePartition.list(groupService.archivedRoot())) {
            if (partition.dirName().equals(parentName)) {
                return "这是已归档分区「" + parentName + "」里的喊麦，助手本来就管着它。"
                        + "要改就在列表那一行上点「修改」";
            }
        }
        return null;
    }

    /** 规范绝对路径（toRealPath 不行时退 toAbsolutePath().normalize()，目录不存在也能比） */
    private static Path normalize(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    /** toRealPath：挡符号链接 / 目录联接，也统一成绝对规范路径 */
    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static Path parsePath(String raw) {
        String value = StringUtils.trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return Path.of(value);
        } catch (Exception e) {
            return null;
        }
    }

    /** 报 skipped 用的目录：解析不出就归到字面原文（结果里仍可见） */
    private static Path parentOf(String raw) {
        try {
            Path p = Path.of(StringUtils.trimToEmpty(raw));
            return p.getParent() == null ? p : p.getParent();
        } catch (Exception e) {
            return Path.of(StringUtils.defaultString(raw, "?"));
        }
    }
}
