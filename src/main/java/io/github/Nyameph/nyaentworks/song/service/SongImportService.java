package io.github.Nyameph.nyaentworks.song.service;

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
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpSource;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpType;
import io.github.Nyameph.nyaentworks.common.media.MediaExtensions;
import io.github.Nyameph.nyaentworks.common.media.ScorePartition;
import io.github.Nyameph.nyaentworks.song.config.SongProperties;
import io.github.Nyameph.nyaentworks.song.controller.SongController;
import io.github.Nyameph.nyaentworks.song.service.SongArchiveService.GroupApplyResult;
import io.github.Nyameph.nyaentworks.song.service.SongArchiveService.GroupRef;
import io.github.Nyameph.nyaentworks.song.service.SongGroupService.SongGroup;
import io.github.Nyameph.nyaentworks.song.util.SongName;
import io.github.Nyameph.nyaentworks.song.util.SongNameParser;
import io.github.Nyameph.nyaentworks.song.util.SongNaming;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「添加文件」弹窗的后端（添加歌曲设计文档）：源路径联动展开（expand）→ 预演（plan）→
 * 执行（apply）。「未归档页的归档弹窗」与「歌曲页的修改弹窗」②③ 也复用同一份 plan / apply
 * 核心（输入先归一成 {@link LandingGroup}，见 {@link #locatedFiles}）。
 *
 * <p>安全边界只有一条：<b>目标路径永远由后端算</b>。前端只传源路径与表单值（源天生在
 * 受管根之外），②③ 连源路径都不传（组由 partition + mainName 唯一确定，后端自己重扫）。
 *
 * <p>文件系统是权威、库是镜像：待打分层不入库每次现扫；{@code song_file} 只覆盖已归档层。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SongImportService {

    private final SongGroupService groupService;
    private final SongSettingService settingService;
    private final SongTagService tagService;
    private final SongProperties properties;

    /** 一行的根分类。TEMPLATE / ONLY_ORIGINAL 要拦；STAGING / OUTSIDE 允许；
     *  PARTITION 也允许（2026-09-23 用户定：添加歌曲可以从已归档分区选文件，
     *  库侧防双条见 {@link #relocateArchivedRows}），前端给行加「已归档」来源徽标 */
    public enum RootKind {TEMPLATE, ONLY_ORIGINAL, PARTITION, STAGING, OUTSIDE}

    /** 联动展开后的一个文件 */
    public record ExpandedFile(String path, String fileName, String mainName, String ext,
                               String role, SongName parsed, RootKind rootKind) {
    }

    /**
     * 一个源目录的展开结果。
     *
     * @param blockedReason 该目录整批被拒的原因（受管根命中）；非空时 files 为空
     * @param skipped       认不出扩展名或不是普通文件的路径（只回报，不进列表）
     */
    public record ExpandedGroup(String sourceDir, List<ExpandedFile> files,
                                List<String> skipped, String blockedReason) {
    }

    // ------------------------------------------------------------------
    // 内部归一形态：① 与 ②③ 都先翻成这一套，plan / apply 只写一份
    // ------------------------------------------------------------------

    /**
     * 一个已经在盘上定位好的源文件。
     *
     * @param source       绝对路径（① 来自请求、②③ 来自重扫）
     * @param version      该文件的编号（可空）；<b>只有勾了改名才生效</b>
     * @param groupKey     ②③ 里该文件所属 variant 的定位键 {@code fromPartition|mainName}；① 用空串
     * @param fromPartition ②③ 的库侧锚点分区（待打分为空串）；① 为 null
     */
    public record LocatedFile(Path source, String fileName, String mainName, String version,
                              String groupKey, String fromPartition) {
    }

    /** 表单值（三框 + 是否改名） */
    public record Form(String artists, String title, String originalTitle, boolean rename) {
    }

    /** 目标：staging 或一个评分分区 */
    public record Target(Path dir, String partition, Integer score) {
    }

    /** 归一后的落盘项 */
    public record Landing(Path source, String fileName, String mainName,
                          Path targetDir, String targetMainName, String groupKey) {
    }

    /**
     * 一个「落盘组」的输入：这批文件 + 共用的表单值 + 它们共同的目标。
     * <p>① 只有一组；②③ 的「不搬评分」情形下<b>每个 variant 各一组</b>（各自留在自己的分区里）。
     */
    public record LandingGroup(List<LocatedFile> files, Form form, Target target) {
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

    /** 归档闸门的拦截语（① 与 ②③ 共用一份，别在四个调用点各写一句） */
    private static final String TAG_GATE_MESSAGE = "还没打标签，不能归档。先给这组打上标签再归档";

    /** 迁移到未归档：目标是 stagingRoot()，没有评分。目录不存在不拦 —— moveAll 会建 */
    private Target stagingTarget() {
        return new Target(groupService.stagingRoot(), null, null);
    }

    /**
     * 归档闸门（① 添加歌曲 与 ②③ 列表式保存共用）。
     * {@code nya-entworks.song.require-tags-before-archive} 打开时（出厂 false ＝ 不拦），
     * 这一组<b>落盘之后</b>一个标签都没有就拦下 —— 「待打分 → 已归档这第一步不打标签不许走」，
     * 与 {@code SongArchiveService#requireTagForArchive} 同一口径、同一个属性。
     *
     * <p><b>为什么新表单两条路要各自实现一遍</b>：那道闸门原先只守在既有组的三处
     * （{@code SongArchiveService} 的 archive / archiveBatch / planEditBatch），而
     * ① 与 ②③ 是 2026-09-23 新开的落盘路，<b>绕过了它</b> —— 页面上拦得住、直接打接口拦不住，
     * 而「认定必须在后端」是全局硬约束（前端那句 pre-check 只是少一趟往返）。
     *
     * <p>与既有那道闸门的差别：那三处的组在库里已有行，直接查 {@code song_tag} 即可；
     * 这里判的是「<b>这次提交之后</b>有没有标签」—— ① 的标签本来就在这次提交里
     * （待打分的组在 {@code song_group} 里没有行），所以提交带了数组就以数组为准
     * （<b>清空数组 = 提交后没标签，照样拦</b>），数组为 {@code null}（这次不动标签）
     * 才回落到库里现存的那份。
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
        // 落盘后的主名拼出的合并键：与 apply 写标签时用的是同一个（标签跟着新名走）
        String targetMain = targetMainOf(group.form(), group.files().get(0));
        String mergeKey = SongNameParser.mergeKey(SongNameParser.parse(targetMain), targetMain);
        return tagService.listTags(mergeKey).isEmpty() ? TAG_GATE_MESSAGE : null;
    }

    /** 归档目标：{@code #<分数><评语>} 分区；目录不在时 blockedReason 是 requireDir 的原文 */
    private TargetOrBlocked archiveTarget(int score) {
        try {
            Path dir = ScorePartition.requireDir(properties.getSongDir(), score);
            return new TargetOrBlocked(
                    new Target(dir, dir.getFileName().toString(), score), null);
        } catch (RuntimeException e) {
            return new TargetOrBlocked(null, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 核心：plan / apply
    // ------------------------------------------------------------------

    /**
     * ① 与 ②③ 共用的预演。<b>一次收一组或多组，产出一份合并的清单</b> ——
     * 批内查重与「一条 blockedReason 整批不执行」都是批级的，apply 也要一次
     * {@code moveAll} 掉所有 moves。
     *
     * @return plan（含 blockedReason 与逐文件的 from → to）与跨卷文件名单
     */
    public ImportPlan plan(List<LandingGroup> groups) {
        return plan(groups, List.of());
    }

    /**
     * 带「剔除 → 冗余」搬动的重载：那些搬动并入同一张清单 —— 确认页看得到
     * 「→ …\冗余\…」、批内查重覆盖它们、apply 一次 moveAll 原子完成。
     */
    public ImportPlan plan(List<LandingGroup> groups, List<FileMove> extraMoves) {
        List<LocatedFile> allFiles = new ArrayList<>();
        for (LandingGroup group : groups == null ? List.<LandingGroup>of() : groups) {
            allFiles.addAll(group.files());
        }
        if (allFiles.isEmpty() && (extraMoves == null || extraMoves.isEmpty())) {
            return ImportPlan.blocked("没有要处理的文件");
        }
        // ②：逐组检查目标分区在不在（staging 不拦：moveAll 的 createDirectories 会建）
        for (LandingGroup group : groups) {
            if (group.target().score() != null && !java.nio.file.Files.isDirectory(group.target().dir())) {
                return ImportPlan.blocked("目标分区目录不在磁盘上："
                        + group.target().dir() + "。分区目录是手工建的，缺了说明盘没挂上或还没建");
            }
        }

        // ③~⑥：逐组逐文件算 Landing（targetMainName）。编号 / 主名校验只在勾了改名时做
        // —— 不勾改名时编号一律忽略，否则「改了编号但没勾改名」会变成静默改名
        List<Landing> landings = new ArrayList<>();
        for (LandingGroup group : groups) {
            Form form = group.form();
            for (LocatedFile file : group.files()) {
                if (form.rename()) {
                    if (StringUtils.isAnyBlank(form.artists(), form.title(), form.originalTitle())) {
                        return ImportPlan.blocked("作者、曲名、原曲名都要填"
                                + "（勾了「按以上信息重排文件名」时三项都不能空）");
                    }
                    try {
                        SongNaming.requireVersion(file.version());
                    } catch (IllegalArgumentException e) {
                        return ImportPlan.blocked("「" + file.fileName() + "」"
                                + e.getMessage());
                    }
                    try {
                        GroupFileOps.requireMainName(
                                targetMainOf(form, file));
                    } catch (IllegalArgumentException e) {
                        return ImportPlan.blocked("「" + file.fileName() + "」拼出的主名不行："
                                + e.getMessage());
                    }
                }
                landings.add(new Landing(file.source(), file.fileName(), file.mainName(),
                        group.target().dir(), targetMainOf(form, file), file.groupKey()));
            }
        }

        // ⑥（②③ 专用）：同 groupKey 的编号必须一致 —— 一次改名把同组文件拆成两组，
        // 拆出来只有歌词的那一半会被扫描直接丢掉。① 的 groupKey 全是空串，不受这条管
        // （① 的列表本来就是用户手选的碎片，逐文件填编号是它的正常用法）
        if (landings.stream().anyMatch(l -> StringUtils.isNotBlank(l.groupKey()))) {
            Map<String, String> versionByKey = new LinkedHashMap<>();
            for (LandingGroup group : groups) {
                for (LocatedFile file : group.files()) {
                    if (StringUtils.isBlank(file.groupKey())) {
                        continue;
                    }
                    String version = StringUtils.defaultString(file.version());
                    String prev = versionByKey.putIfAbsent(file.groupKey(), version);
                    if (prev != null && !prev.equals(version)) {
                        return ImportPlan.blocked("「" + file.fileName()
                                + "」与同一组的其它文件被填成了不同编号，这会把一组拆成两组"
                                + "（拆出来只有歌词的那一半会被扫描丢掉）。同组的编号要一致");
                    }
                }
            }
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

        // ⑦：批内目标路径查重（toLowerCase，Windows 口径）。planMoves 只比磁盘现状，
        // 查不到「本批自己撞自己」—— 两个源文件映射到同一目标时它两条都放行，
        // apply 到第二个才会炸（回滚，最坏是静默覆盖）
        Map<String, FileMove> byTarget = new LinkedHashMap<>();
        for (FileMove move : moves) {
            FileMove prev = byTarget.putIfAbsent(move.toPath().toLowerCase(java.util.Locale.ROOT), move);
            if (prev != null) {
                return ImportPlan.blocked("两个文件会落到同一个目标路径：" + prev.fromPath()
                        + " 与 " + move.fromPath() + " → " + move.toPath()
                        + "。给其中一个填个编号（如 2）就能分开");
            }
        }

        String action = actionOf(groups);
        // 全部文件都被剔除（landings 空）时用第一剔除行当显示名，分区沿用第一组的现状
        String displayMain = allFiles.isEmpty()
                ? extraMoves.get(0).fileName() : allFiles.get(0).mainName();
        String toDisplayMain = landings.isEmpty()
                ? extraMoves.get(0).toFileName() : landings.get(0).targetMainName();
        String toPartition = groups.isEmpty() ? null : groups.get(0).target().partition();
        GroupPlan plan = new GroupPlan(action, displayMain, toPartition, toDisplayMain,
                List.copyOf(moves), null);
        return new ImportPlan(plan, crossVolumeOf(moves));
    }

    /**
     * plan 用的 action：① 走 IMPORT_STAGING / IMPORT_ARCHIVE（FileOpRecorder 的映射表里
     * 分别是「添加歌曲：迁移到未归档」「添加歌曲：归档」）；②③ 是既有组的一次提交，用 EDIT。
     * 区分依据是 groupKey：②③ 每个 variant 都带 {@code 分区|主名}，① 全是空串。
     */
    private static String actionOf(List<LandingGroup> groups) {
        boolean editForm = groups.stream()
                .flatMap(g -> g.files().stream())
                .anyMatch(f -> StringUtils.isNotBlank(f.groupKey()));
        if (editForm) {
            return "EDIT";
        }
        Integer score = groups.get(0).target().score();
        return score != null ? "IMPORT_ARCHIVE" : "IMPORT_STAGING";
    }

    /** 跨卷提示（不是拦截）：搬动允许任意来源之后，跨卷从「不可能」变成日常 */
    private static List<String> crossVolumeOf(List<FileMove> moves) {
        List<String> crossVolume = new ArrayList<>();
        for (FileMove move : moves) {
            if (move.blockedReason() != null) {
                continue;
            }
            try {
                if (!java.nio.file.Files.getFileStore(Path.of(move.fromPath()))
                        .equals(java.nio.file.Files.getFileStore(Path.of(move.toPath())))) {
                    crossVolume.add(move.fileName());
                }
            } catch (IOException e) {
                // 拿不到 FileStore（源已不在等）就不提示 —— planMoves 那边会给 blockedReason
            }
        }
        return List.copyOf(crossVolume);
    }

    /**
     * ① 与 ②③ 共用的执行：搬 → 写标签 → （归档时）回写库。顺序不能反（磁盘先动、库后动）。
     *
     * @param libraryAnchors 库侧锚点，<b>必须在搬动之前从请求里读好传进来</b>：
     *                       搬完旧目录里没有那些文件了，现算会抛「找不到这一组」。
     *                       ① 恒为空列表（新导入的组库里还没有行，入库交给同步补建）
     */
    public GroupApplyResult apply(List<LandingGroup> groups, List<String> tags,
                                  List<GroupRef> libraryAnchors) {
        return apply(groups, tags, libraryAnchors, List.of(), null);
    }

    /**
     * 带剔除的重载。{@code excluded} 的搬动已在 plan 清单里（同一批原子搬），
     * 搬成功后逐行做库侧收尾（song_id=0 + 冗余名），记录按「剔除：移入冗余」单独记 OTHER，
     * 不与常规搬动的 recordPlan 混在一行。{@code prepared} 供 ① 复用已算好的 plan
     * （定位落盘后的新名），为 null 时内部重算。
     */
    public GroupApplyResult apply(List<LandingGroup> groups, List<String> tags,
                                  List<GroupRef> libraryAnchors, List<ExcludedFile> excluded,
                                  ImportPlan prepared) {
        List<FileMove> redundantMoves = redundantMoves(excluded);
        // 重跑 plan 做二次校验（请求可能在队列里躺了一夜）：blocked 就抛，任务失败、
        // 理由给人看，不静默跳过（符合「由人在任务页点重新执行」的既有形态）
        ImportPlan preparedPlan = prepared != null ? prepared : plan(groups, redundantMoves);
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
                FileOpRecorder.recordFailure(FileOpModule.SONG, FileOpType.MOVE,
                        FileOpLevel.GROUP, null, null, e.getMessage());
                throw e;
            }
            // 搬成功才算数；记录是附属品，记不上不影响搬动。
            // 剔除 → 冗余的行单独按 OTHER 记，页面上能与常规搬动分开看
            List<FileMove> normalMoves = plan.moves().stream()
                    .filter(mv -> !redundantFrom.contains(mv.fromPath()))
                    .toList();
            FileOpRecorder.recordPlan(FileOpModule.SONG,
                    new GroupPlan(plan.action(), plan.mainName(), plan.toPartition(),
                            plan.toMainName(), normalMoves, plan.blockedReason()),
                    FileOpLevel.GROUP);
            for (FileMove rm : redundantMoves) {
                FileOpRecorder.recordPath(FileOpModule.SONG, FileOpType.MOVE,
                        FileOpLevel.OTHER, "剔除：移入冗余", rm.fromPath(), rm.toPath());
            }
            // 库侧收尾：冗余行置 song_id=0。行不存在（未同步）只记 warn —— 磁盘已经动了，
            // 行交给同步按现状收敛
            for (ExcludedFile ex : excluded == null ? List.<ExcludedFile>of() : excluded) {
                FileMove rm = plan.moves().stream()
                        .filter(mv -> mv.fromPath().equals(ex.source()))
                        .findFirst().orElse(null);
                if (rm != null && !settingService.markFileRedundant(ex.fromPartition(),
                        ex.mainName(), ex.fileName(), rm.toFileName())) {
                    log.warn("冗余收尾：库里没有「{} / {}」的行（未同步？），磁盘已移入冗余，行交给同步",
                            ex.fromPartition(), ex.mainName());
                }
            }
        }

        // 标签：按落盘后的每个「组」各写一次（一批文件可以落成多组：不同编号 / 不同源目录）。
        // tags 为 null 表示这次不动标签（前端只在变了的时候才送数组）
        if (tags != null) {
            Set<String> doneGroups = new LinkedHashSet<>();
            for (LandingGroup group : groups) {
                for (LocatedFile file : group.files()) {
                    String targetMain = targetMainOf(group.form(), file);
                    if (!doneGroups.add(group.target().dir() + "|" + targetMain)) {
                        continue;
                    }
                    String newMergeKey = SongNameParser.mergeKey(
                            SongNameParser.parse(targetMain), targetMain);
                    tagService.replaceTags(newMergeKey, tags);
                }
            }
        }

        // 库那侧（②③ 才有锚点）。score = null 表示没改评分 —— 方法内部跳过评分列，
        // 但只改名也照样要调它，否则 song_file.main_name 停在旧名上
        boolean settingMoved = false;
        for (GroupRef anchor : libraryAnchors == null ? List.<GroupRef>of() : libraryAnchors) {
            String newMainName = newMainNameOf(groups, anchor);
            Target target = targetOfAnchor(groups, anchor);
            settingMoved = settingService.editSongGroup(anchor.partition(), anchor.mainName(),
                    target.partition(), target.score(), newMainName) || settingMoved;
        }

        return new GroupApplyResult(plan.action(), plan.mainName(), plan.toPartition(),
                plan.toMainName(), moved, settingMoved);
    }



    /**
     * 这个文件落盘后的主名：没勾改名 = 原主名；勾了 = 按表单拼。
     * plan 与 apply 共用 —— 两边各算一遍就会出现「清单上的名字与落盘的名字不一致」
     */
    private static String targetMainOf(Form form, LocatedFile file) {
        if (!form.rename()) {
            return file.mainName();
        }
        return SongNaming.build(form.artists(), form.title(), form.originalTitle(),
                null, file.version());
    }

    /** 锚点对应的落盘主名：它名下的文件要么没改名（原主名）、要么都改成同一个新主名 */
    private static String newMainNameOf(List<LandingGroup> groups, GroupRef anchor) {
        String key = anchor.partition() + "|" + anchor.mainName();
        for (LandingGroup group : groups) {
            for (LocatedFile file : group.files()) {
                if (key.equals(file.groupKey())) {
                    return targetMainOf(group.form(), file);
                }
            }
        }
        return anchor.mainName();
    }

    /** 锚点对应的 Target：它名下文件所在的那个 LandingGroup 的目标 */
    private static Target targetOfAnchor(List<LandingGroup> groups, GroupRef anchor) {
        String key = anchor.partition() + "|" + anchor.mainName();
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
    public ImportPlan planImport(SongController.ImportRequest request) {
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
    public GroupApplyResult applyImport(SongController.ImportRequest request) {
        LandingGroup group = toLandingGroup(request);
        // 归档闸门再算一遍：请求可能在队列里躺了一夜，标签是那次提交带进来的、
        // 拦不住就得先拦（与 apply 内部重跑 plan 做二次校验同一个理由）
        String gate = requireTagForArchive(List.of(group), request.tags());
        if (gate != null) {
            throw new IllegalStateException(gate);
        }
        // 只读预演先算好（apply 内部会再算一遍做二次校验，两份口径同一方法）：
        // relocate 要靠它把「源文件 → 落盘后的新名」对上
        ImportPlan prepared = plan(List.of(group));
        GroupApplyResult result = apply(List.of(group), request.tags(), List.of(),
                List.of(), prepared);
        relocateArchivedRows(group.files(), prepared);
        return result;
    }

    /**
     * ① 从已归档分区选文件时的库侧<b>防双条</b>：这一行还挂在旧组上，落盘后要么
     * <b>改挂</b>到目标组（目标组在库里有行）、要么<b>删行</b>交给同步在新位置补建。
     * 不做这一步的症状：同步会把旧组标 MISSING、新组插新行，同一文件两条数据。
     * 判「源是已归档分区」按目录形态（直接父目录 = songDir 下的 {@code #分数评语} 分区），
     * 待打分 / 外部来源本来就没有库行，天然跳过。
     */
    private void relocateArchivedRows(List<LocatedFile> files, ImportPlan prepared) {
        for (LocatedFile file : files) {
            Path parent = file.source().getParent();
            if (parent == null || ScorePartition.parse(
                    parent.getFileName().toString()) == null) {
                continue;
            }
            // 落盘后的新名：清单里找这条源对应的 move；没进清单（已在目标处）用原文件名
            String newFileName = file.fileName();
            for (FileMove move : prepared.plan().moves()) {
                if (move.fromPath().equals(file.source().toString())) {
                    newFileName = move.toFileName();
                    break;
                }
            }
            String toPartition = prepared.plan().toPartition();
            settingService.relocateArchivedFile(parent.getFileName().toString(),
                    file.mainName(),
                    MediaExtensions.suffix(file.fileName(), file.mainName()),
                    toPartition, MediaExtensions.mainName(newFileName));
        }
    }

    /** 冗余文件分页（song_id = 0 的行），歌曲页列表下方的折叠卡用 */
    public SongSettingService.RedundantPage redundantFiles(int page, int size) {
        return settingService.listRedundantFiles(page, size, redundantDir());
    }

    /** ① 的请求翻译：一个 LandingGroup（一份 Form + 一个 Target） */
    private LandingGroup toLandingGroup(SongController.ImportRequest request) {
        if (request == null || request.files() == null || request.files().isEmpty()) {
            throw new IllegalArgumentException("没有选择要添加的文件");
        }
        List<LocatedFile> files = new ArrayList<>();
        for (SongController.ImportFile file : request.files()) {
            if (file == null || StringUtils.isBlank(file.sourcePath())) {
                throw new IllegalArgumentException("有文件没给源路径");
            }
            Path source = realPath(Path.of(file.sourcePath()));
            String fileName = source.getFileName().toString();
            files.add(new LocatedFile(source, fileName,
                    MediaExtensions.mainName(fileName), file.version(), "", null));
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
                        + "）与 score（" + request.score() + " 分）对应的分区（"
                        + archive.target().partition() + "）不一致 —— 两个字段表达的是同一件事");
            }
            target = archive.target();
        }
        Form form = new Form(request.artists(), request.title(), request.originalTitle(),
                request.rename());
        return new LandingGroup(List.copyOf(files), form, target);
    }

    // ------------------------------------------------------------------
    // ②③ 既有组的列表式改造：库清单 + 输入准备 + 提交口
    // ------------------------------------------------------------------

    /**
     * 一行一个文件的库清单（③ 画列表用）。
     * {@code fileName} = {@code SongFile#fullName()} 拼出的完整文件名 —— 它表示的
     * 一直是「拼好的完整名」，不是某一列。
     */
    public record GroupFileRow(String fileName, String mainName, String fileType,
                               int variantSort, int sortOrder) {
    }

    /** 一个既有组的 {@code song_file} 清单（只读库，画列表用），转调 {@code listGroupFiles} */
    public List<GroupFileRow> groupFiles(String partition, String mainName) {
        return settingService.listGroupFiles(partition, mainName).stream()
                .map(f -> new GroupFileRow(f.fullName(), f.getMainName(),
                        f.getFileType() == null ? null : f.getFileType().name(),
                        f.getVariantSort() == null ? 0 : f.getVariantSort(),
                        f.getSortOrder() == null ? 0 : f.getSortOrder()))
                .toList();
    }

    /** ②③ 的输入准备结果：要么是可用的组，要么是一条整批拦截的原因 */
    private record EditFormInput(List<LandingGroup> groups, List<GroupRef> anchors,
                                 String blockedReason, List<ExcludedFile> excluded) {
    }

    /**
     * ②③ 剔除的已归档文件：提交后移入「冗余」文件夹（song_id=0）。
     * 定位库行靠 (fromPartition, mainName, fileName 的后缀)。
     */
    public record ExcludedFile(String fromPartition, String mainName,
                               String fileName, String source) {
    }

    /** 已归档根下的「冗余」文件夹（剔除文件的去处；不是分区形态，扫描/同步都不碰它） */
    private Path redundantDir() {
        return Path.of(properties.getSongDir(), "冗余");
    }

    /**
     * ②③ 的预演（与 ① 同一份 plan）。
     * <p>源路径由后端定位（组由 partition + mainName 唯一确定），前端只回传
     * 逐文件编号 + 三框 + 改名勾选 + 目标 + 打分 + 标签。
     */
    public ImportPlan planEditForm(SongController.EditFormRequest req) {
        EditFormInput input = locatedFiles(req);
        if (input.blockedReason() != null) {
            return ImportPlan.blocked(input.blockedReason());
        }
        // 归档闸门（与 SongArchiveService 那道同一口径、同一属性）：②③ 这条路
        // 也可能把待打分的组搬进评分分区，而它原先只靠着前端那句 pre-check
        String gate = requireTagForArchive(input.groups(), req.tags());
        if (gate != null) {
            return ImportPlan.blocked(gate);
        }
        ImportPlan plan = plan(input.groups(), redundantMoves(input.excluded()));
        // 「不搬评分」时逐 variant 各自留在自己的分区，plan.toPartition 是第一组的 —— 没关系，
        // 页面只用它显示，apply 的库侧按锚点各自取（targetOfAnchor）
        return plan;
    }

    /**
     * ②③ 的执行（跑在异步任务线程上，handler 里已用 FileOpRecorder.batch 包住）。
     * 锚点在搬动之前从请求里读好 —— 搬完旧目录里没有那些文件了，现算会抛。
     */
    public GroupApplyResult applyEditForm(SongController.EditFormRequest req) {
        EditFormInput input = locatedFiles(req);
        if (input.blockedReason() != null) {
            throw new IllegalStateException(input.blockedReason());
        }
        String gate = requireTagForArchive(input.groups(), req.tags());
        if (gate != null) {
            throw new IllegalStateException(gate);
        }
        return apply(input.groups(), req.tags(), input.anchors(), input.excluded(), null);
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
     * ②③ 的输入准备：逐组定位到盘上的组、逐文件对齐，翻成 {@link LandingGroup}。
     * <p>「源必须在受管根里」不需要额外代码：{@code require} 只在 staging / 评分分区里找，
     * 源出了受管根根本 require 不到，第 1 步已经覆盖。
     */
    private EditFormInput locatedFiles(SongController.EditFormRequest req) {
        if (req == null || req.groups() == null || req.groups().isEmpty()) {
            return new EditFormInput(List.of(), List.of(), "没有要操作的组", List.of());
        }
        Form form = new Form(req.artists(), req.title(), req.originalTitle(), req.rename());
        List<LandingGroup> groups = new ArrayList<>();
        List<GroupRef> anchors = new ArrayList<>();
        List<ExcludedFile> excluded = new ArrayList<>();
        for (SongController.EditFormGroup g : req.groups()) {
            // 用既有的归组口径定位（不自己拼目录路径）。「做」以重扫为准（画用库、做用盘）
            SongGroup group;
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
            for (SongController.EditFormFile f : g.files() == null ? List.<SongController.EditFormFile>of() : g.files()) {
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
                        group.mainName(), f.version(),
                        StringUtils.defaultString(g.partition()) + "|" + g.mainName(),
                        StringUtils.defaultString(g.partition())));
            }
            // 全部剔除 = 解散这一组到冗余，是合法提交（files 空 + excluded 非空照常走）
            if (files.isEmpty() && excludedInGroup == 0) {
                return new EditFormInput(List.of(), List.of(),
                        "「" + g.mainName() + "」没有给出要处理的文件", List.of());
            }
            // 目标：不搬（targetPartition 空 / score null）= 现状分区；搬（改评分）= 新分区
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
            groups.add(new LandingGroup(List.copyOf(files), form, target));
            anchors.add(new GroupRef(g.partition(), g.mainName()));
        }
        // ②③ 添加的外部文件（canAdd 选进来的受管根之外 / 之内的文件）：翻成一个附加组，
        // 共用表单与第一组的目标（不搬分 = 主 variant 现状分区、改分 = 新分区）；
        // groupKey 空串 —— 编号逐文件独立（与 ① 同口径），也不参与「同组编号一致」检查。
        // 库侧锚点不含它们：新文件不属于任何既有组，入库交同步补建
        if (req.extraFiles() != null && !req.extraFiles().isEmpty()) {
            List<LocatedFile> extra = new ArrayList<>();
            for (SongController.ImportFile ef : req.extraFiles()) {
                if (ef == null || StringUtils.isBlank(ef.sourcePath())) {
                    continue;
                }
                Path source = realPath(Path.of(ef.sourcePath()));
                String fileName = source.getFileName().toString();
                extra.add(new LocatedFile(source, fileName,
                        MediaExtensions.mainName(fileName), ef.version(), "", null));
            }
            if (!extra.isEmpty()) {
                groups.add(new LandingGroup(List.copyOf(extra), form, groups.get(0).target()));
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
     * <p>「同名不同后缀自动联动」用的就是后端既有的归组口径（{@link SongGroupService#scanDir}
     * 按 {@code mainName} 归组）—— 自己写一套就是第二份口径。
     *
     * <p><b>先放锚点、再补联动</b>：{@code scanDir} 会丢掉「只有歌词没有媒体」的组，
     * 只用它的结果会让用户选中一个孤儿歌词时列表变空、看起来像点了没反应。
     * 保留锚点 → 它进列表、被「至少一个媒体文件」的表单闸门拦下并给人话理由。
     */
    public List<ExpandedGroup> expand(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return List.of();
        }
        // 逐个 path 定位（真实路径 + 分类），按源目录分组。列表元素是「用户选中的锚点」
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
                        .add("不认识的扩展名，不当歌曲文件：" + fileName);
                continue;
            }
            byDir.computeIfAbsent(path.getParent(), k -> new ArrayList<>())
                    .add(realPath(path));
        }

        List<ExpandedGroup> result = new ArrayList<>();
        for (Map.Entry<Path, List<Path>> entry : byDir.entrySet()) {
            Path sourceDir = entry.getKey();
            List<Path> selected = entry.getValue();

            // 组内任一文件命中受管根（模板 / 仅原曲 / 评分分区）→ 整组拒，理由照给人
            String blocked = null;
            for (CandidateFile candidate : selected.stream().map(CandidateFile::new).toList()) {
                RootKind kind = rootKind(candidate.path());
                if (kind == RootKind.TEMPLATE) {
                    blocked = "这是填词模板目录（模板）里的文件，不是歌曲文件。换一个来源再选";
                    break;
                }
                if (kind == RootKind.ONLY_ORIGINAL) {
                    blocked = "这是原曲库（仅原曲）里的文件，搬走会破功能。换一个来源再选";
                    break;
                }

            }
            if (blocked != null) {
                result.add(new ExpandedGroup(sourceDir.toString(), List.of(),
                        skippedByDir.getOrDefault(sourceDir, List.of()), blocked));
                continue;
            }

            // 先放锚点（用户选中的），再补联动（scanDirectory 里 mainName 相同的那个组）。
            // 联动只在同一个源目录内：跨目录同名多半是不同的东西
            Map<String, ExpandedFile> merged = new LinkedHashMap<>();
            Set<String> anchorMainNames = new LinkedHashSet<>();
            for (Path anchor : selected) {
                merged.put(anchor.toString(), expandedOf(anchor));
                anchorMainNames.add(MediaExtensions.mainName(anchor.getFileName().toString()));
            }
            for (SongGroup group : groupService.scanDirectory(sourceDir)) {
                if (!anchorMainNames.contains(group.mainName())) {
                    continue;   // 同目录的其它组（如 歌#2）不进列表：mainName 含编号，是另一组
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

    /** 展开候选（锚点）的薄封装，仅为可读性 */
    private record CandidateFile(Path path) {
    }

    /** 展开一个文件（rootKind 要读 properties，所以是实例方法） */
    private ExpandedFile expandedOf(Path file) {
        String fileName = file.getFileName().toString();
        String mainName = MediaExtensions.mainName(fileName);
        return new ExpandedFile(file.toString(), fileName, mainName,
                MediaExtensions.extension(fileName), roleOf(fileName),
                SongNameParser.parse(mainName), rootKind(file));
    }

    /** 角色判定与 scanDir 的分派同一口径；闸门靠它判「至少一个媒体文件」 */
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
     * rootKind 判据（只有这一处）。顺序敏感：先判特殊的（模板 / 仅原曲 / 待打分），
     * 再判通用的（评分分区、外部）。只判<b>直接父目录</b>：文件平铺在分区里是既有事实
     * （scanDir 不递归）。
     */
    private RootKind rootKind(Path file) {
        Path parent = file.getParent();
        if (parent == null) {
            return RootKind.OUTSIDE;
        }
        Path dir = normalize(parent);
        if (dir.equals(normalize(Path.of(properties.getTemplateDir())))) {
            return RootKind.TEMPLATE;
        }
        if (dir.equals(normalize(Path.of(properties.getOnlyOriginalDir())))) {
            return RootKind.ONLY_ORIGINAL;
        }
        if (dir.equals(normalize(groupService.stagingRoot()))) {
            return RootKind.STAGING;
        }
        String parentName = parent.getFileName() == null ? "" : parent.getFileName().toString();
        for (var partition : ScorePartition.list(properties.getSongDir())) {
            if (partition.dirName().equals(parentName)) {
                return RootKind.PARTITION;
            }
        }
        return RootKind.OUTSIDE;
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
