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
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpType;
import io.github.Nyameph.nyaentworks.common.lyric.LyricTextReader;
import io.github.Nyameph.nyaentworks.common.media.MediaExtensions;
import io.github.Nyameph.nyaentworks.common.media.MediaStreamService;
import io.github.Nyameph.nyaentworks.song.config.SongProperties;
import io.github.Nyameph.nyaentworks.song.entity.SongOriginalSetting;
import io.github.Nyameph.nyaentworks.song.mapper.SongOriginalSettingMapper;
import io.github.Nyameph.nyaentworks.song.util.SongNameParser;
import io.github.Nyameph.nyaentworks.song.util.SongOriginalNaming;
import io.github.Nyameph.nyaentworks.song.util.SvpAudioRefs;

import java.io.IOException;
import java.math.BigDecimal;
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
 * 「原曲页」的新增 / 修改：从<b>任意路径</b>把文件导进某条原曲的目录，并按规则改名。
 *
 * <p>三个入口一条核心（{@link #compute}）：{@link #expand} 只读磁盘画预览，
 * {@link #plan} 出搬动清单，{@link #apply} 落盘 + 写库。三者必须共用同一份计算 ——
 * 各算一遍就会出现「页面上预览的名字与落盘的名字不一致」。
 *
 * <p><b>与「添加歌曲」相反的两处</b>（别照搬那边的判断）：
 * <ol>
 *   <li>源文件<b>天生在受管根之外</b>，目标路径一律由后端算（{@link #compute}）；</li>
 *   <li>受管根在这里是「这东西本来就该在库里」的意思 —— 命中就<b>拒收</b>
 *       （{@link PickKind#IN_MANAGED_ROOT}），而在添加歌曲那边是「不许动这个目录」。</li>
 * </ol>
 *
 * <p><b>八类文件名的拼法只有一份</b>：{@link SongOriginalNaming}（阶段 1 抽出来的），
 * 本类不自己拼前缀。曲速兜底顺序见 {@link #resolveBpm}。
 *
 * <p><b>零 SQL</b>：{@code song_original_setting} 是设置表（不是磁盘镜像表），
 * 由应用代码读写；本功能不加列、不改结构。
 */
@Service
@RequiredArgsConstructor
public class SongOriginalImportService {

    private final SongProperties properties;
    private final SongTemplateService templateService;
    private final SongSettingService settingService;
    private final SongOriginalSettingMapper originalMapper;
    /** 出「能在浏览器里播的 URL」（含受管根外源文件的临时令牌），见 {@link #playUrlOf} */
    private final MediaStreamService mediaService;

    /** 八类的展示顺序与中文标签（{@code TYPES} 那七个 + 样例歌词）。
     *  <b>七类标识本身不在这里定义</b> —— 那是 {@code SongTemplateService.TYPES} 的事，
     *  这里只多一个 {@code demoLrc}（库里有这一列、盘上有这个文件，只是不在那七个下拉里）。
     *
     *  <p><b>播放计划也在这一份里</b>（{@code group} / {@code playAudio} / {@code playLyric}）：
     *  「哪一格点播放时放什么」是页面上的分组规则，写在前端就是第二份表 ——
     *  同一个「音频 + 歌词」的配法要在新增与编辑两个窗口各写一遍，改一处漏一处。
     *
     * @param group      播放分组（同组的框在页面上挨着、共用一句说明）；{@code none} = 没有播放按钮
     * @param groupLabel 分组那一句话（整句由后端给，前端只画）
     * @param playAudio  这一格点「播放」时放<b>哪一格</b>的音频（类别）；{@code null} = 不放音频
     * @param playLyric  这一格点「播放」时滚<b>哪一格</b>的歌词（类别）；{@code null} = 不带歌词
     */
    public record TypeOption(String type, String label, String group, String groupLabel,
                             String playAudio, String playLyric) {
    }

    /** 三点一起播：原曲音频 + 歌词（原曲 / 歌词两格点哪一格都是这一套） */
    private static final String GROUP_PAIR = "pair";
    /** 点播放听的是这一格自己的音频，带上样例歌词（伴奏 / 人声 / 样例 / 样例歌词） */
    private static final String GROUP_PART = "part";
    /** 没有播放按钮（mid / 工程） */
    private static final String GROUP_NONE = "none";

    private static final String LABEL_PAIR = "播放：原曲 + 歌词";
    private static final String LABEL_PART = "播放：对应音频 + 样例歌词";
    private static final String LABEL_NONE = "不播放";

    /** 顺序即页面上的顺序，且<b>同组必须挨着</b>（前端只在标签变化时画那一行说明） */
    private static final List<TypeOption> TYPE_OPTIONS = List.of(
            new TypeOption("original", "原曲", GROUP_PAIR, LABEL_PAIR, "original", "lyric"),
            new TypeOption("lyric", "歌词", GROUP_PAIR, LABEL_PAIR, "original", "lyric"),
            new TypeOption("accompaniment", "伴奏", GROUP_PART, LABEL_PART, "accompaniment", "demoLrc"),
            new TypeOption("vocals", "人声", GROUP_PART, LABEL_PART, "vocals", "demoLrc"),
            new TypeOption("demo", "样例", GROUP_PART, LABEL_PART, "demo", "demoLrc"),
            // 样例歌词自己不是音频：点它的播放听的是样例音频（配套的那一份打样）
            new TypeOption("demoLrc", "样例歌词", GROUP_PART, LABEL_PART, "demo", "demoLrc"),
            new TypeOption("mid", "mid", GROUP_NONE, LABEL_NONE, null, null),
            new TypeOption("svp", "工程", GROUP_NONE, LABEL_NONE, null, null));

    /** mid / svp 不在 {@code MediaExtensions} 里（它只管歌曲那几种），单独一份小清单 */
    private static final List<String> MID_EXTS = List.of("mid");
    private static final List<String> SVP_EXTS = List.of("svp");

    // ==================== 对外形态 ====================

    /** 一个源文件在本次提交里的类别（用户在下拉里选的，或从已有指派带出来的） */
    public record ImportFile(String path, String type) {
    }

    /** 只读预览的入参 */
    public record ExpandRequest(String rawName, String artist,
                                Long originalId,            // 编辑那一路：那一行的 id（可空）
                                boolean isNew,              // true = 点的是「新增原曲」
                                List<String> paths,         // 用户选的源文件（绝对路径）
                                boolean rename,             // 是否改名（裁决 3：默认 true）
                                Map<String, String> typeByFile,   // 文件名 / 全路径 → 类别
                                String bpm,                 // 可选：用户手填的曲速
                                boolean artistCheck) {      // 编辑那一路的「歌手已确认」
        // 四个确认位里只有 artistCheck 影响预览：没勾就不改名（§0.2 第 7 条），
        // 预览里的名字得跟着变成没改过的样子。另外三位与预览无关，不往这里带
        // —— 请求里放一个不改变任何输出的字段比不放更糟
    }

    /** 提交的入参（plan 与 apply 共用） */
    public record ImportRequest(String rawName, String artist,
                                Long originalId, boolean isNew,
                                List<ImportFile> files,
                                List<String> excludeExisting,   // 已有文件里被 [×] 掉的类别：这次不参与改名
                                boolean rename, String bpm,
                                boolean artistCheck, boolean originalCheck,
                                boolean lyricCheck, boolean svpCheck) {
    }

    /** 一个文件在预览里的状态 */
    public enum PickKind {
        /** 可以落盘 */
        READY,
        /** 认不出是哪一类，用户也没选 */
        NEED_TYPE,
        /** 类别与扩展名不符（如把 .mp4 指成伴奏） */
        BAD_EXT,
        /** 盘上没有这个文件 */
        ABSENT,
        /** 源在模板根 / 仅原曲根里 —— 它本来就在库里，不该走这条路 */
        IN_MANAGED_ROOT,
        /** 与清单里另一个文件的目标名撞了 */
        DUPLICATE
    }

    /**
     * 一行「要落到磁盘上的文件」。
     *
     * @param playUrl            能在页面上播的 URL；{@code null} = 播不了（歌词 / mid / svp、
     *                           文件不在盘上）。**由后端出串，前端不自己拼**：本次新选的源文件
     *                           天生在受管根之外，它的 URL 自带一个临时预览令牌
     *                           （见 {@code MediaStreamService#previewUrl} 与
     *                           {@code MediaPreviewTokens}）
     * @param playBlockedReason  播不了时给人看的一句话；能播时为 {@code null}
     * @param lyricPreview       这一格若是歌词类（歌词 / 样例歌词）<b>且文件在盘上</b>，就是它的
     *                           解析结果（{@link LyricTextReader}，全项目只有这一份解析）；别的
     *                           类别、文件不在、这一格空着都是 {@code null}。
     *                           <p>带上它是为了让「播放」能放「音频 + 歌词」这一对：新增那一路还
     *                           没有 {@code originalId}，走不了 {@code /template/lyric}
     */
    public record PickedFile(String path, String fileName, String ext,
                             String type, PickKind kind,
                             String toFileName, boolean existing, String blockedReason,
                             String playUrl, String playBlockedReason,
                             LyricTextReader.Lyric lyricPreview) {
    }

    /** svp 引用会失效的严重度 */
    public enum SvpSeverity {
        /** 裸文件名命中「本次会改名的文件」：改名后 SynthV 找不到它 */
        RENAME,
        /** 引用的是绝对路径，而那个文件本次会被搬动 / 改名 */
        ABSOLUTE_PATH,
        /** 引用的文件本来就不在（空引用不报，见类注释） */
        DANGLING
    }

    /** 一条 svp 提醒（{@code message} 是给人看的一整句，前端只画不判） */
    public record SvpWarning(SvpSeverity severity, String svpFileName, String trackName,
                             String refFilename, String message) {
    }

    /**
     * 原曲目录里<b>还没被指派给任何一类</b>的文件 —— 编辑页每个文件框那个「从原曲目录选」
     * 下拉的选项。扫描认不出来的伴奏 / 歌词就靠这条路指派（2026-09-25 起，
     * 原先那条「七类下拉」的路并进了统一表单）。
     *
     * @param types 这个扩展名能被指派成的类别（按 {@link #extsOf} 算，<b>前端不写第二份表</b>）；
     *              空的不会出现在这里（八类都不收的 {@code .ace} / {@code .txt} 不列）
     */
    public record CandidateFile(String path, String fileName, String ext, List<String> types) {
    }

    /** 预览结果 */
    public record ExpandResult(String targetRoot, String targetDir, boolean isNew,
                               boolean renameAllowed,
                               List<TypeOption> types,
                               Map<String, List<String>> extRule,
                               List<PickedFile> files,
                               String duplicateReason,
                               List<SvpWarning> svpWarnings,
                               String blockedReason,
                               /** 后端点定/算出的曲速（已格式化）；mid 那个文件名就用它 */
                               String bpm,
                               /** 原曲目录里可指派但还没指派的文件；新增那一路恒为空 */
                               List<CandidateFile> candidates) {
    }

    /** 搬动清单 + 跨卷提示（{@code FileMove} 不能加字段，所以提示挂在外层） */
    public record OriginalImportPlan(GroupPlan plan, List<String> crossVolume,
                                     boolean isNewOriginal, List<SvpWarning> svpWarnings) {

        static OriginalImportPlan blocked(String reason) {
            return new OriginalImportPlan(new GroupPlan("IMPORT_ORIGINAL", "", null, "",
                    List.of(), reason), List.of(), false, List.of());
        }
    }

    /** 落盘结果 */
    public record ImportResult(String rawName, String targetDir, int movedFiles,
                               int renamedFiles, int created, List<SvpWarning> svpWarnings) {
    }

    // ==================== 内部归一形态 ====================

    /** 请求的全量解释（expand / plan / apply 共用一份，免得三处各写一套默认值） */
    private record Intent(String rawName, String artist, Long originalId, boolean isNew,
                          List<ImportFile> files, List<String> excludeExisting,
                          boolean rename, String bpm,
                          boolean artistCheck, boolean originalCheck,
                          boolean lyricCheck, boolean svpCheck) {
    }

    /** 计算过程中的一个文件（类别 / 状态 / 最终名都要来回填） */
    private static final class Slot {

        private final Path path;        // 目标位置的文件全路径；已有文件被删了时为 null
        private final String fileName;  // 现文件名
        private final String type;
        private final boolean existing;
        /** 查重那一遍会把它从 READY 改成 DUPLICATE，所以不是 final */
        private PickKind kind;
        private String blockedReason;
        private String toFileName;

        Slot(Path path, String fileName, String type, boolean existing,
             PickKind kind, String blockedReason) {
            this.path = path;
            this.fileName = fileName;
            this.type = type;
            this.existing = existing;
            this.kind = kind;
            this.blockedReason = blockedReason;
        }
    }

    /** {@link #compute} 的全部产出 */
    private record Computed(String rawName, String artist, SongOriginalSetting row,
                            Path targetRoot, Path targetDir, boolean isNew,
                            boolean renameAllowed, boolean rename, BigDecimal bpm,
                            List<Slot> slots, String duplicateReason,
                            List<SvpWarning> svpWarnings, String blockedReason) {

        static Computed blocked(String reason) {
            return new Computed("", "", null, null, null, false, false, false, null,
                    List.of(), null, List.of(), reason);
        }
    }

    // ==================== 只读预览 ====================

    /** 类别 → 允许的扩展名。<b>本功能的「限制文件类型」只有这一份</b>（裁决 6 的下发源）。
     *  前端拿它做对话框过滤与类别下拉过滤，后端拿它做闸门 —— 两边同一份，不会分叉。 */
    public Map<String, List<String>> extRule() {
        Map<String, List<String>> rule = new LinkedHashMap<>();
        for (TypeOption option : TYPE_OPTIONS) {
            rule.put(option.type(), extsOf(option.type()));
        }
        return rule;
    }

    public List<TypeOption> types() {
        return TYPE_OPTIONS;
    }

    /**
     * 源文件路径 + 目标原曲 + 每个文件的类别 → 一行行的「旧名 → 新名」。<b>只读磁盘。</b>
     *
     * <p>类别可以由前端在 {@code typeByFile} 里给（键是<b>整个路径</b>或<b>文件名</b>，
     * 两种都收）。认不出类别、用户也没给的文件保持 {@code NEED_TYPE}
     * —— 它照样出现在清单里（用户看得见自己选的东西），只是不能提交。
     */
    public ExpandResult expand(ExpandRequest request) {
        Computed c = compute(toIntent(request));
        List<PickedFile> files = new ArrayList<>();
        for (Slot slot : c.slots()) {
            files.add(new PickedFile(pathText(slot.path), slot.fileName, extKey(slot.fileName),
                    slot.type, slot.kind, slot.toFileName, slot.existing, slot.blockedReason,
                    playUrlOf(slot), playBlockedReasonOf(slot), lyricPreviewOf(slot)));
        }
        return new ExpandResult(pathText(c.targetRoot()), pathText(c.targetDir()),
                c.isNew(), c.renameAllowed(), TYPE_OPTIONS, extRule(), List.copyOf(files),
                c.duplicateReason(), c.svpWarnings(), c.blockedReason(),
                c.bpm() == null ? null : SongOriginalNaming.bpmText(c.bpm()),
                candidatesOf(c.row()));
    }

    /**
     * 原曲目录里「能被指派、但还没被指派」的文件（见 {@link CandidateFile}）。
     *
     * <p>用 {@code row} 而不是表单里的名字：用户可能正在改原曲名，而候选文件始终是
     * <b>这一条原曲现在那个目录</b>里的东西 —— 拿新名字去找，改名的同时想指派就一个都列不出来。
     */
    private List<CandidateFile> candidatesOf(SongOriginalSetting row) {
        if (row == null) {
            return List.of();
        }
        List<CandidateFile> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Path file : templateService.listFilesOfOriginal(row)) {
            String name = file.getFileName() == null ? "" : file.getFileName().toString();
            String ext = MediaExtensions.extension(name);
            List<String> types = new ArrayList<>();
            for (TypeOption option : TYPE_OPTIONS) {
                if (extsOf(option.type()).contains(ext)) {
                    types.add(option.type());
                }
            }
            if (types.isEmpty()) {
                continue;   // 八类都不收的扩展名（.ace / .txt…）：指派不了，不列
            }
            if (isAssigned(row, name)) {
                continue;   // 已经是某一格里的文件了（那一格自己有 existing 行）
            }
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                continue;   // 多个模板目录里同名：只列一次
            }
            out.add(new CandidateFile(pathText(file), name, ext, List.copyOf(types)));
        }
        return out;
    }

    /** 这个文件名是不是已经是库里八列之一 —— 是的话它已经在某一格里，不再当候选 */
    private boolean isAssigned(SongOriginalSetting row, String fileName) {
        for (TypeOption option : TYPE_OPTIONS) {
            if (fileName.equalsIgnoreCase(templateService.getTypeFile(row, option.type()))) {
                return true;
            }
        }
        return false;
    }

    /** 这一行能不能在页面上播（每个文件框旁那个 ▶）。源文件在受管根外也照给 —— 后端签令牌 */
    private String playUrlOf(Slot slot) {
        return slot.path == null ? null : mediaService.previewUrl(slot.path.toString());
    }

    /** 播不了的原因（能播时为 {@code null}）。前端只画这一句，不自己判「为什么播不了」 */
    private static String playBlockedReasonOf(Slot slot) {
        if (slot.path == null) {
            return "文件不在盘上了，播不了";
        }
        if (MediaExtensions.isLyric(slot.fileName)) {
            // 歌词格的按钮由 lyricPreview 点亮（它自己不是音频、没有 playUrl），所以这里
            // 正常走不到 —— 走到这儿只剩「文件读不出来」，那句话在 lyricPreview.message 里
            return "这个歌词文件读不出来";
        }
        if (!MediaExtensions.isMedia(slot.fileName)) {
            // mid / svp：它们那一组本来就不画播放按钮，这句只是兜底
            return "这一类（" + MediaExtensions.extension(slot.fileName) + "）页面上播不了";
        }
        return "播不了这个文件";
    }

    /**
     * 歌词格的解析结果，进响应给前端拼「音频 + 歌词」那一对（见 {@link PickedFile#lyricPreview}）。
     * 磁盘上没有了（{@code path == null}）时给 {@code null}，让那一格的播放键按「没东西可放」灰着。
     */
    private static LyricTextReader.Lyric lyricPreviewOf(Slot slot) {
        if (slot.path == null || !MediaExtensions.isLyric(slot.fileName)) {
            return null;
        }
        // 只读一个小文本文件（几 KB），且**只在这一趟 expand 里做**：plan / apply 不走这里
        return LyricTextReader.read(slot.path, slot.fileName);
    }

    // ==================== 预演 ====================

    /** 搬动清单（同步端点用）。整批被拒时 {@code plan.blocked()} 为真。 */
    public OriginalImportPlan plan(ImportRequest request) {
        return planOf(compute(toIntent(request)));
    }

    // ==================== 执行 ====================

    /**
     * 落盘 + 写库。跑在异步任务线程上（{@code SongOriginalImportHandler} 已用
     * {@code FileOpRecorder.batch} 包住）。
     *
     * <p><b>顺序不能反</b>：磁盘先动、库后动。盘上失败时库里那一行还是旧的，下次扫描能纠正；
     * 反过来就是库里指着一个不存在的名字。
     */
    public ImportResult apply(ImportRequest request) {
        Intent intent = toIntent(request);
        Computed c = compute(intent);
        if (c.blockedReason() != null) {
            throw new IllegalStateException(c.blockedReason());
        }
        // 防御性再查一次重：plan 到 apply 之间隔了一段时间（异步任务），别人可能刚建过同一行
        if (c.isNew()) {
            SongOriginalSetting dup = findDuplicate(settingService.allOriginals(),
                    c.rawName(), c.artist());
            if (dup != null) {
                throw new IllegalStateException(duplicateMessage(c.rawName(), c.artist(), dup));
            }
        }
        OriginalImportPlan plan = planOf(c);
        if (plan.plan().blocked()) {
            throw new IllegalStateException(plan.plan().firstBlockedReason());
        }

        List<FileMove> moves = plan.plan().moves();
        int moved = 0;
        if (!moves.isEmpty()) {
            // requireExecutable 对空清单也抛，所以只在真有搬动时才叫它
            GroupFileOps.requireExecutable(plan.plan());
            try {
                moved = GroupFileOps.moveAll(moves);
            } catch (RuntimeException e) {
                FileOpRecorder.recordFailure(FileOpModule.SONG, FileOpType.MOVE,
                        FileOpLevel.OTHER, null, null, e.getMessage());
                throw e;
            }
            // 搬成功才算数；记录是附属品，记不上不影响搬动。
            // <b>逐路径记 recordPath、不记 recordPlan</b>：本功能每个文件的人话动作都不同
            // （已有的只改名 / 新导入 / 新导入并改名），而 recordPlan 一行只有一个 action，
            // 记成一行就看不出哪几个是改名了。「一个被改动的路径一条记录」也要求逐条
            // （doc 附录 B，层级一律 OTHER —— 原曲侧没有分区那一层）。
            for (FileMove move : moves) {
                FileOpRecorder.recordPath(FileOpModule.SONG, FileOpType.MOVE, FileOpLevel.OTHER,
                        actionOf(c, move), move.fromPath(), move.toPath());
            }
        }
        int renamed = (int) moves.stream()
                .filter(m -> !m.fileName().equalsIgnoreCase(m.toFileName()))
                .count();

        // 库后动
        int created = 0;
        if (c.isNew()) {
            insertRow(c, intent);
            created = 1;
        } else {
            updateRow(c, intent);
        }
        // 作者确认为真时把模板文件夹改名（与 applyFiles 同一口径、同一个方法）
        String newArtist = StringUtils.trimToNull(c.artist());
        if (!c.isNew() && intent.artistCheck() && newArtist != null && c.row() != null) {
            templateService.renameTemplateFolderForArtist(
                    StringUtils.defaultIfBlank(c.row().getRawName(), c.rawName()),
                    c.row().getArtist(), newArtist);
        }
        // 编辑后按「有模板 → 模板 / 否则 → 仅原曲」重新对齐目录归属
        templateService.relocateMisplacedDirs();

        return new ImportResult(c.rawName(), pathText(c.targetDir()), moved, renamed,
                created, c.svpWarnings());
    }

    // ==================== 核心计算 ====================

    private Computed compute(Intent in) {
        try {
            return computeInside(in);
        } catch (IllegalArgumentException e) {
            // 拼不出名字（非法字符 / 未知类别）这类「输入的问题」转成人话给确认页，
            // 不让它变成 500 —— 与添加歌曲 planImport 的 catch 同一个立场
            return Computed.blocked(e.getMessage());
        }
    }

    private Computed computeInside(Intent in) {
        String rawName = StringUtils.trimToEmpty(in.rawName());
        String artist = StringUtils.trimToEmpty(in.artist());
        // 原曲名还没填<b>不是</b>「整页不能画」的理由：新增那一路是先选文件、文件名随
        // 原曲名才定，所以这里照常把文件列出来，只在 blockedReason 上挂一句（plan 会拒）
        boolean blankName = StringUtils.isBlank(rawName);
        // 名字的合法性：原曲名与歌手<b>都</b>进文件名（目录是 原曲名_歌手、original 是 甲 - 乙 …），
        // 也进 template 文件夹名与库里的 raw_name，所以两格都要过。**先于目标目录那一步算**
        // —— 名字里有「?」时 resolve 出来的路径建不出来，不能等它抛出来才发现。
        // 不勾改名也要拦：目录名本来就是 原曲名_歌手 拼的（改名只管文件名）
        String nameProblem = badNameReason("原曲名", rawName);
        if (nameProblem == null) {
            nameProblem = badNameReason("歌手", artist);
        }

        // ---- 1. 这是新增还是修改 ----
        boolean isNew = in.isNew();
        SongOriginalSetting row = null;
        if (!isNew) {
            if (in.originalId() != null) {
                row = originalMapper.selectById(in.originalId());
            }
            if (row == null) {
                // 归一比较（NFC + trim + 大写）：与 SongSettingService#matchOriginal 同一口径
                row = SongSettingService.matchOriginal(
                        settingService.allOriginals(), rawName, artist);
            }
            if (row == null) {
                return Computed.blocked("库里找不到「" + display(rawName, artist)
                        + "」这一条原曲 —— 刷新一下原曲页再试；确实是新的就改用「新增原曲」");
            }
        }
        String duplicateReason = null;
        if (isNew) {
            SongOriginalSetting dup = findDuplicate(settingService.allOriginals(), rawName, artist);
            if (dup != null) {
                duplicateReason = duplicateMessage(rawName, artist, dup);
            }
        }
        // 改名要 artist_check = 1（§0.2 第 7 条）。新增时填了作者就算已确认（落库时写 1）；
        // 编辑时看弹窗里那个「已确认」勾选。作者留空 = 不改名（名字里要放作者）。
        boolean renameAllowed = !blankName
                && StringUtils.isNotBlank(artist) && (isNew || in.artistCheck());
        boolean rename = in.rename() && renameAllowed;

        // ---- 目标目录 ----
        // 原曲名还没填时整段不算：目录名要靠它，硬算只会得到一个空名字的路径
        String lookupName = row == null ? rawName
                : StringUtils.defaultIfBlank(row.getRawName(), rawName);
        String lookupArtist = row == null ? artist : row.getArtist();
        List<Path> dirs = blankName ? List.of()
                : templateService.templateDirsFor(lookupName, lookupArtist);
        if (dirs.isEmpty() && !blankName) {
            // 盘上一条目录都没有（目录被手工删了 / 从没建过）：按表单值再找一次
            dirs = templateService.templateDirsFor(rawName, artist);
        }
        String dirName = blankName ? ""
                : (StringUtils.isBlank(artist) ? rawName : rawName + "_" + artist);
        Path targetRoot = dirs.isEmpty() ? null : dirs.getFirst().getParent();
        Path targetDir = dirs.isEmpty() ? null : dirs.getFirst();

        // ---- 2. 已有的八类文件（只有编辑那一路有，裁决 7） ----
        List<Slot> slots = new ArrayList<>();
        if (!isNew && row != null) {
            for (TypeOption option : TYPE_OPTIONS) {
                if (in.excludeExisting().contains(option.type())) {
                    continue;   // [×] 掉的：这次不参与改名（文件留盘上、库里也不动）
                }
                String name = templateService.getTypeFile(row, option.type());
                if (StringUtils.isBlank(name)) {
                    continue;
                }
                Path file = templateService.resolveFile(lookupName, lookupArtist, name);
                slots.add(new Slot(file, name, option.type(), true,
                        file != null ? PickKind.READY : PickKind.ABSENT,
                        file != null ? null
                                : "「" + option.label() + "」指的文件不在盘上了，先重新扫描一次"));
            }
        }

        // ---- 3. 本次导入的逐个过闸 ----
        Set<String> seen = new LinkedHashSet<>();
        for (ImportFile file : in.files()) {
            if (file == null) {
                continue;
            }
            Path raw = parsePath(file.path());
            if (raw == null) {
                slots.add(new Slot(null, StringUtils.defaultString(file.path()), file.type(),
                        false, PickKind.ABSENT, "不是合法的路径"));
                continue;
            }
            String fileName = raw.getFileName() == null ? "" : raw.getFileName().toString();
            if (!Files.isRegularFile(raw)) {
                slots.add(new Slot(null, fileName, file.type(), false,
                        PickKind.ABSENT, "盘上没有这个文件：" + raw));
                continue;
            }
            Path path = realPath(raw);
            if (!seen.add(path.toString().toLowerCase(Locale.ROOT))) {
                continue;   // 同一路径传两次：只算一次
            }
            slots.add(gate(path, fileName, file.type(), dirs));
        }

        // ---- 目标根：全新目录时按「有没有工程文件」定（§0.2 第 5 条） ----
        // 名字不合法时整段不算（同 blankName）：算出来的路径建不出来，而这里只是预览
        if (targetDir == null && !blankName && nameProblem == null) {
            // hasTemplateProject 只管<em>已存在</em>的目录，这里目录还没建，所以把同一份
            // 扩展名清单（TEMPLATE_PROJECT_EXTS，唯一一份）套在即将落进去的文件名上
            boolean hasProject = slots.stream().anyMatch(s -> SongTemplateService.TEMPLATE_PROJECT_EXTS
                    .contains(MediaExtensions.extension(s.fileName)));
            targetRoot = Path.of(hasProject
                    ? properties.getTemplateDir() : properties.getOnlyOriginalDir());
            targetDir = targetRoot.resolve(dirName);
        }

        // ---- 3.5 一个类别只能有一个文件 ----
        // 八个类别各自对应库里的<em>一列</em>（original_file_name / demo_file_name / …），
        // 所以「一个类别两个文件」在数据模型上根本表达不出来：关掉改名时两个文件的目标名
        // 互不相同、批内那条按目标名的查重一声不响（它比的是名字，不是类别），而 updateRow
        // 只会把其中一个写进库 —— 另一个照常搬进原曲目录，用户以为导进去了，下次扫描它只是
        // 个「其他文件」。所以在这里先拦下后来那个（`putIfAbsent` 留下的总是先到的那个，
        // 而已有文件恒排在本次选的之前，于是被留住的正好是库里的真值）。
        Map<String, Slot> byType = new LinkedHashMap<>();
        for (Slot slot : slots) {
            if (slot.kind != PickKind.READY || slot.type == null) {
                continue;   // 已经拦住的行不参与，免得把更具体的原因盖掉（如扩展名不符）
            }
            Slot kept = byType.putIfAbsent(slot.type, slot);
            if (kept == null) {
                continue;
            }
            slot.blockedReason = "「" + labelOf(slot.type) + "」一个类别只能放一个文件："
                    + "这个类别已经有「" + kept.fileName + "」了。去掉这一个，或者先处理原来那个";
            slot.kind = PickKind.DUPLICATE;
        }

        // ---- 4. 算最终名（已有的与本次导入的同一口径） ----
        BigDecimal bpm = resolveBpm(in.bpm(), slots, row, targetDir);
        String bpmText = SongOriginalNaming.bpmText(bpm);
        for (Slot slot : slots) {
            if (slot.kind != PickKind.READY) {
                continue;
            }
            if (rename) {
                // 后缀取<em>原文</em>的大小写，与 GroupFileOps#planMoves 逐字一致：
                // 用 MediaExtensions.extension()（它是小写化的）拼会让 .MP3 预览成 .mp3，
                // 而实际落盘还是 .MP3 —— 预览与实际不一致是最难查的一类问题
                String ext = MediaExtensions.extension(slot.fileName);
                slot.toFileName = SongOriginalNaming.build(slot.type, rawName, artist, bpmText)
                        + "." + slot.fileName.substring(slot.fileName.length() - ext.length());
            } else {
                slot.toFileName = slot.fileName;
            }
            if (rename && nameProblem == null) {
                // 两格的合法性在上游已经判过（badNameReason），拼装本身再验一遍兜底：
                // 万一以后加了新类别 / 新前缀，别等 planMoves 拼出个建不出来的路径。
                // **上游已判出不合法时不重复抛** —— 从这儿抛出去只剩一句没提字段名的话，
                // 而用户要知道的是「原曲名」还是「歌手」那一格
                GroupFileOps.requireMainName(baseOf(slot.toFileName));
            }
        }

        // ---- 批内目标名查重（含已有文件） ----
        Map<String, Slot> byTarget = new LinkedHashMap<>();
        for (Slot slot : slots) {
            if (slot.kind != PickKind.READY) {
                continue;
            }
            Slot prev = byTarget.putIfAbsent(slot.toFileName.toLowerCase(Locale.ROOT), slot);
            if (prev != null) {
                slot.blockedReason = "「" + prev.fileName + "」与「" + slot.fileName
                        + "」会落到同一个目标文件名「" + slot.toFileName
                        + "」—— 一个类别只能有一个文件，把其中一个去掉或换个类别";
                slot.kind = PickKind.DUPLICATE;
            }
        }

        // ---- 5. svp 提醒 ----
        // 目标目录没定（原曲名还没填）时全静音：没有目标名可比，逐条报「引用会失效」是噪声
        List<SvpWarning> warnings = targetDir == null ? List.of()
                : svpWarnings(slots, row, lookupName, lookupArtist, targetDir);

        // ---- 6. 排序：不区分来源，按类别顺序、同类按文件名 ----
        slots.sort(Comparator.comparingInt((Slot s) -> typeOrder(s.type))
                .thenComparing(s -> s.fileName));

        return new Computed(rawName, artist, row, targetRoot, targetDir, isNew,
                renameAllowed, rename, bpm, List.copyOf(slots), duplicateReason,
                warnings, nameProblem != null ? nameProblem
                        : (blankName ? "原曲名要填" : null));
    }

    /**
     * 这一格能不能进文件名（能返回 {@code null}，不能返回一句给用户看的话）。
     *
     * <p><b>判据只有 {@link GroupFileOps#requireMainName} 一处</b> —— 「名字怎么拼」在
     * {@link SongOriginalNaming}，「名字合不合法」也归它（照 {@code SongNaming} 的分工）。
     * 原曲名与歌手都进文件名与目录名（{@code 原曲名_歌手}、{@code 甲 - 乙}），所以两格转调同一份。
     * 空值不算错：原曲名空着由「原曲名要填」那一句去说，歌手空着是合法的（＝不改名）。
     *
     * <p>不自己列一遍非法字符表 —— 两处各写一遍的下场是漏掉 {@code |} 或忘了「结尾的点与空格
     * 会被 Windows 静默去掉」这半条，而症状（盘上的名字与库里的不一致）要到下次扫描才现形。
     *
     * @param label 字段名（「原曲名」/「歌手」），把话说成是<em>哪一格</em>不行
     */
    private static String badNameReason(String label, String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            GroupFileOps.requireMainName(value);
            return null;
        } catch (IllegalArgumentException e) {
            return label + " —— " + e.getMessage();
        }
    }

    /**
     * 一个本次导入的文件的闸门：类别 → 扩展名（唯一一份判据），加受管根拦截。
     * 顺序照正文阶段 3 第 3 步。
     *
     * @param ownDirs 这一条原曲自己的目录（{@code templateDirsFor} 的结果）。它们虽然也在受管
     *                根下，但落在那儿的文件<b>不是「再导一次」</b>，而是「指派 / 顺手改名」——
     *                最常见的来源就是每个文件框那个「从原曲目录选」下拉。所以这里放行，
     *                落盘那一步自然退化成「不搬」（{@code planOf} 会丢掉 from == to 的行）；
     *                目录里认不出的那几类不靠这条路指派就<b>没有别的路</b>了。
     *                其它受管根（别人家的目录、根下的散文件）照旧拦。
     */
    private Slot gate(Path path, String fileName, String givenType, List<Path> ownDirs) {
        String type = StringUtils.trimToNull(givenType);
        if (type == null) {
            // 认不出类别（classify 的兜底是 other）→ 让用户自己选
            String guess = SongTemplateService.guessType(fileName);
            return new Slot(path, fileName, guess, false, PickKind.NEED_TYPE,
                    guess != null ? null : "这一个认不出是哪一类，请在上面选一个类别");
        }
        if (!TYPE_OPTIONS.stream().anyMatch(o -> o.type().equals(type))) {
            return new Slot(path, fileName, null, false, PickKind.NEED_TYPE,
                    "「" + type + "」不是有效的类别，请重新选");
        }
        String ext = MediaExtensions.extension(fileName);
        if (!extsOf(type).contains(ext)) {
            return new Slot(path, fileName, type, false, PickKind.BAD_EXT,
                    "「" + labelOf(type) + "」" + extRuleText(type) + "，这个是 ." + ext);
        }
        if (insideManagedRoot(path) && !insideAnyDir(path.getParent(), ownDirs)) {
            return new Slot(path, fileName, type, false, PickKind.IN_MANAGED_ROOT,
                    "这是模板 / 仅原曲目录里的文件，本来就在库里，不用再导一次");
        }
        return new Slot(path, fileName, type, false, PickKind.READY, null);
    }

    /** {@code dir} 是不是 {@code dirs} 里的某一个（按规范化后的绝对路径比，忽略大小写） */
    private static boolean insideAnyDir(Path dir, List<Path> dirs) {
        if (dir == null || dirs == null) {
            return false;
        }
        String d = realPath(dir).toString().toLowerCase(Locale.ROOT);
        for (Path own : dirs) {
            if (d.equals(realPath(own).toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** 类别 → 允许的扩展名（小写、不含点）。<b>这一张表就是「限制文件类型」的全部依据。</b> */
    private static List<String> extsOf(String type) {
        return switch (type) {
            // 裁决 4：视频只开「原曲」这一处（它是唯一有确认位的音视频槽位）
            case "original" -> concat(MediaExtensions.videoExtensions(),
                    MediaExtensions.audioExtensions());
            case "lyric", "demoLrc" -> MediaExtensions.lyricExtensions();
            case "demo", "accompaniment", "vocals" -> MediaExtensions.audioExtensions();
            case "mid" -> MID_EXTS;
            case "svp" -> SVP_EXTS;
            default -> List.of();
        };
    }

    /** 闸门不通过时那句人话的后半段（与 {@link #extsOf} 一一对应，改一个要改另一个） */
    private static String extRuleText(String type) {
        return switch (type) {
            case "original" -> "只收视频或音频文件";
            case "lyric", "demoLrc" -> "只收 " + String.join(" / ", MediaExtensions.lyricExtensions()) + " 文件";
            case "demo", "accompaniment", "vocals" ->
                    "只收 " + String.join(" / ", MediaExtensions.audioExtensions()) + " 音频文件";
            case "mid" -> "只收 .mid 文件";
            case "svp" -> "只收 .svp 文件";
            default -> "不认识这一类";
        };
    }

    private static String labelOf(String type) {
        return TYPE_OPTIONS.stream().filter(o -> o.type().equals(type))
                .map(TypeOption::label).findFirst().orElse(StringUtils.defaultString(type));
    }

    private static int typeOrder(String type) {
        for (int i = 0; i < TYPE_OPTIONS.size(); i++) {
            if (TYPE_OPTIONS.get(i).type().equals(type)) {
                return i;
            }
        }
        return TYPE_OPTIONS.size();
    }

    // ==================== 曲速 ====================

    /**
     * 曲速的五级兜底（只给 {@code mid} 的名用）：
     * <b>用户手填 ＞ 同目录 svp 的 tempo ＞ midi 内容 ＞ 库里的 bpm ＞ 旧 mid 文件名里的数字</b>，
     * 都没有返回 {@code null}（拼名时写成 {@code [BPM=？]}）。
     *
     * <p>顺序与 {@code script/SongOriginalFileRenameUtil#fillBpm}、以及扫描侧
     * （{@code SongTemplateService} 的 svp ＞ midi 内容 ＞ 文件名）一致 —— 三处不一致的
     * 症状是同一条原曲在页面上显示两个拍速。midi 内容排在库字段之前是刻意的：
     * 库里的值可能是修复前从旧文件名抄来的（全是 {@code [BPM=？]}）。
     */
    private BigDecimal resolveBpm(String given, List<Slot> slots, SongOriginalSetting row,
                                  Path targetDir) {
        Slot mid = slots.stream().filter(s -> "mid".equals(s.type) && s.path != null)
                .findFirst().orElse(null);
        String text = StringUtils.trimToNull(given);
        if (text != null) {
            if (mid == null) {
                return null;   // 这次没有 mid，手填的曲速没有用处（也别为它报错）
            }
            try {
                return new BigDecimal(text).stripTrailingZeros();
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("曲速要填数字，收到的是「" + text + "」");
            }
        }
        if (mid == null) {
            return null;
        }
        // 同目录 svp：mid 现在所在的目录（源目录）与它要落进去的目录都看
        BigDecimal bpm = bpmFromSiblingSvp(mid.path.getParent());
        if (bpm == null && targetDir != null && !targetDir.equals(mid.path.getParent())) {
            bpm = bpmFromSiblingSvp(targetDir);
        }
        if (bpm == null) {
            bpm = SongTemplateService.bpmFromMidi(mid.path);
        }
        if (bpm != null) {
            return bpm;
        }
        if (row != null && row.getBpm() != null) {
            return row.getBpm();
        }
        return SongTemplateService.bpmFromFileName(mid.fileName);
    }

    /** 一个目录里读得出 tempo 的第一个 svp（多模板时取文件名排序第一个）。
     *  口径与 {@code SongOriginalFileRenameUtil#bpmFromSiblingSvp} 一致。 */
    private static BigDecimal bpmFromSiblingSvp(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return null;
        }
        try (var files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(f -> "svp".equals(MediaExtensions.extension(f.getFileName().toString())))
                    .sorted(Comparator.comparing(f -> f.getFileName().toString()))
                    .map(SongTemplateService::bpmFromSvp)
                    .filter(java.util.Objects::nonNull)
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    // ==================== svp 提醒 ====================

    /**
     * 本次会动到的 svp 引用了哪些音频文件。只服务「提醒用户去 SynthV 里改」，
     * <b>不改 svp</b>（裁决 5）。
     *
     * <p>名单 = 已有指派里的 svp（{@code resolveFile} 那条路）+ 本次导入的 svp。
     * 每条 filename 与「<b>本次会被改名或搬动的文件</b>」对照 —— 这个名单必须包含
     * 已有文件（编辑时把已有伴奏改了名，同样会断）。
     *
     * <p><b>空引用不报</b>（{@link SvpAudioRefs} 已经把它们过滤掉了，实测 43 条那种
     * 「本来就是空的」状态报出来只会淹没真提醒，见正文附录 C）；引用指向的文件
     * 本来就找不到则报一条 {@code DANGLING}。
     */
    private List<SvpWarning> svpWarnings(List<Slot> slots, SongOriginalSetting row,
                                         String lookupName, String lookupArtist, Path targetDir) {
        List<SvpWarning> out = new ArrayList<>();
        // 本次会被改名的文件名（旧名 → 新名）与会被搬走的旧绝对路径
        Set<String> renaming = new LinkedHashSet<>();
        Map<String, String> renamedTo = new LinkedHashMap<>();
        Set<String> movingOut = new LinkedHashSet<>();
        Set<String> afterNames = new LinkedHashSet<>();
        for (Slot slot : slots) {
            if (slot.toFileName != null) {
                afterNames.add(slot.toFileName.toLowerCase(Locale.ROOT));
            }
            if (slot.kind != PickKind.READY) {
                continue;
            }
            if (!slot.fileName.equalsIgnoreCase(slot.toFileName)) {
                renaming.add(slot.fileName.toLowerCase(Locale.ROOT));
                renamedTo.put(slot.fileName.toLowerCase(Locale.ROOT), slot.toFileName);
            }
            if (slot.path != null && targetDir != null
                    && !realPath(targetDir.resolve(slot.toFileName)).equals(slot.path)) {
                movingOut.add(slot.path.toString().toLowerCase(Locale.ROOT));
            }
        }

        List<Slot> svps = new ArrayList<>();
        for (Slot slot : slots) {
            // toFileName 非空 = 这个 svp 这次真的会落到目标目录（BAD_EXT / NEED_TYPE 的
            // 连名字都没算，提醒它们只会是噪声，而且消息里那个名字会是 null）
            if ("svp".equals(slot.type) && slot.path != null && slot.toFileName != null) {
                svps.add(slot);
            }
        }
        // 已有 svp 是被 [×] 掉、或库里指派了但盘上找不到时不在这里 —— 前者是用户的选择，
        // 后者连文件都没有，没什么可提醒的
        for (Slot svp : svps) {
            for (SvpAudioRefs.AudioRef ref : SvpAudioRefs.read(svp.path)) {
                String filename = ref.filename();
                boolean isPath = filename.indexOf('/') >= 0 || filename.indexOf('\\') >= 0
                        || filename.indexOf(':') >= 0;
                if (isPath) {
                    Path refPath = parsePath(filename);
                    String key = refPath == null ? "" : refPath.toString().toLowerCase(Locale.ROOT);
                    if (!key.isEmpty() && movingOut.contains(key)) {
                        out.add(warning(SvpSeverity.ABSOLUTE_PATH, svp, ref,
                                "引用的是绝对路径「" + filename
                                        + "」，本次搬动 / 改名会让它失效"));
                    } else if (refPath == null || !Files.isRegularFile(refPath)) {
                        out.add(warning(SvpSeverity.DANGLING, svp, ref,
                                "引用的路径「" + filename + "」已经不在盘上了"));
                    }
                    continue;
                }
                if (renaming.contains(filename.toLowerCase(Locale.ROOT))) {
                    // 两个名字都要给：人要照着<em>新</em>名字去 SynthV 里重新指定，
                    // 只给旧名等于让他自己猜改成了什么
                    out.add(warning(SvpSeverity.RENAME, svp, ref,
                            "引用的「" + filename + "」本次会被改名为「"
                                    + renamedTo.get(filename.toLowerCase(Locale.ROOT)) + "」"));
                } else if (targetDir != null && Files.isRegularFile(targetDir.resolve(filename))) {
                    // 目标目录里就有它、这次也不动它 —— 引用不会断，不报
                } else if (afterNames.contains(filename.toLowerCase(Locale.ROOT))) {
                    // 这次刚落进去的同名文件，引用反而会成立
                } else {
                    out.add(warning(SvpSeverity.DANGLING, svp, ref,
                            "引用的「" + filename + "」不在这个目录里"));
                }
            }
        }
        return List.copyOf(out);
    }

    private static SvpWarning warning(SvpSeverity severity, Slot svp, SvpAudioRefs.AudioRef ref,
                                      String detail) {
        String track = StringUtils.isBlank(ref.trackName()) ? "（未命名音轨）" : ref.trackName();
        return new SvpWarning(severity, svp.fileName, ref.trackName(), ref.filename(),
                "「" + svp.toFileName + "」的「" + track + "」轨" + detail
                        + " —— 请在 SynthV 里重新指定这个音轨的文件，我们不会替你改 svp");
    }

    // ==================== plan ====================

    /**
     * 由 {@link Computed} 出搬动清单。按 <b>(源目录, 目标主名)</b> 分组、每组调一次
     * {@link GroupFileOps#planMoves}（§0.2 第 10 条：它的签名不改、{@code FileMove} 不加字段）。
     *
     * <p>与添加歌曲的两处不同：<b>已有文件也走同一个 plan</b>（它们只是「源目录 == 目标目录」
     * 的那一组）；<b>清单为空不算失败</b> —— 编辑时可能只改了一个标签、一个文件都不动，
     * 那时仍要写库。
     */
    private OriginalImportPlan planOf(Computed c) {
        if (c.blockedReason() != null) {
            return OriginalImportPlan.blocked(c.blockedReason());
        }
        // 查重在标题上就拒（不是等落盘时才发现）：expand 把它单独下发是为了让预览照样能画、
        // 只是把提交按钮置灰；plan 这一步必须真的拦住
        if (c.duplicateReason() != null) {
            return OriginalImportPlan.blocked(c.duplicateReason());
        }
        if (c.slots().isEmpty()) {
            return OriginalImportPlan.blocked("还没有选文件");
        }
        // 有一条不能落就整批不执行（全局硬约束）。这里必须逐条判，不能只看 READY 的那几条
        // —— 否则「一个 .mp4 指成了伴奏」会被静默丢掉，用户以为它导进去了
        for (Slot slot : c.slots()) {
            if (slot.kind != PickKind.READY) {
                return OriginalImportPlan.blocked(notReadyReason(slot));
            }
        }
        List<Slot> ready = c.slots();
        record Pair(Path sourceDir, Path targetDir, String targetMain) {
        }
        Map<Pair, List<Slot>> byPair = new LinkedHashMap<>();
        for (Slot slot : ready) {
            Path sourceDir = slot.path.getParent();
            byPair.computeIfAbsent(new Pair(sourceDir, c.targetDir(), baseOf(slot.toFileName)),
                    k -> new ArrayList<>()).add(slot);
        }
        List<FileMove> moves = new ArrayList<>();
        for (Map.Entry<Pair, List<Slot>> entry : byPair.entrySet()) {
            Pair pair = entry.getKey();
            List<String> names = entry.getValue().stream().map(s -> s.fileName).toList();
            for (FileMove move : GroupFileOps.planMoves(names, pair.sourceDir(),
                    pair.targetDir(), pair.targetMain())) {
                // 「已经在目标目录里、名字也一致」的行不进清单 —— 那是合法输入，不是要搬的
                if (!move.fromPath().equalsIgnoreCase(move.toPath())) {
                    moves.add(move);
                }
            }
        }
        // 批内目标路径查重：planMoves 只比磁盘现状，查不到「本批自己撞自己」。
        // 常规路径上这一关打不到 —— compute 那一步已经按目标名查过重、撞了的会带 DUPLICATE
        // 被上面拦下；留着是给「将来有人绕过 expand 直接调 plan」兜底
        Map<String, FileMove> byTarget = new LinkedHashMap<>();
        for (FileMove move : moves) {
            FileMove prev = byTarget.putIfAbsent(move.toPath().toLowerCase(Locale.ROOT), move);
            if (prev != null) {
                return OriginalImportPlan.blocked("两个文件会落到同一个目标路径："
                        + prev.fromPath() + " 与 " + move.fromPath() + " → " + move.toPath());
            }
        }
        GroupPlan plan = new GroupPlan("IMPORT_ORIGINAL", c.rawName(), null,
                c.targetDir() == null ? "" : c.targetDir().getFileName().toString(),
                List.copyOf(moves), null);
        return new OriginalImportPlan(plan, crossVolumeOf(moves), c.isNew(), c.svpWarnings());
    }

    /** 一条不能落盘的行，给人一句话（{@code blockedReason} 为空的只有「预选值待确认」） */
    private static String notReadyReason(Slot slot) {
        if (slot.blockedReason != null) {
            return slot.blockedReason;
        }
        return "「" + slot.fileName + "」还没确认类别";
    }

    /** 跨卷提示（不是拦截）：源可以是任意盘，跨卷时 Files.move 会退化成拷贝 + 删除 */
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

    /** 记录点的人话（正文阶段 4 第 4 步）：已有文件只改名不搬家也要记，它一样改了磁盘 */
    private static String actionOf(Computed c, FileMove move) {
        for (Slot slot : c.slots()) {
            if (slot.fileName.equals(move.fileName()) && slot.path != null
                    && slot.path.toString().equals(move.fromPath())) {
                if (slot.existing) {
                    return "原曲文件改名";
                }
                return slot.fileName.equalsIgnoreCase(move.toFileName())
                        ? "导入原曲文件" : "导入原曲文件：改名";
            }
        }
        return "导入原曲文件";
    }

    // ==================== 写库 ====================

    /** 库里没有这一条 → insert 一行（§0.2 第 7 条：填了作者就算已确认） */
    private void insertRow(Computed c, Intent in) {
        SongOriginalSetting s = new SongOriginalSetting();
        s.setRawName(c.rawName());
        s.setArtist(StringUtils.trimToNull(c.artist()));
        s.setArtistCheck(StringUtils.isNotBlank(c.artist()));
        s.setOriginalCheck(in.originalCheck());
        s.setLyricCheck(in.lyricCheck());
        boolean hasSvp = false;
        for (Slot slot : c.slots()) {
            if (slot.kind != PickKind.READY) {
                continue;
            }
            templateService.setTypeFile(s, slot.type, slot.toFileName);
            hasSvp = hasSvp || "svp".equals(slot.type);
        }
        // 本次落了 svp 就算「工程已确认」—— 文件是我们自己放进去的，确认位为假会让
        // 下次扫描把 svp_file_name 清掉（§0.2 第 3 条那四个确认位的语义）
        s.setSvpCheck(in.svpCheck() || hasSvp);
        if (c.bpm() != null) {
            s.setBpm(c.bpm());
        }
        originalMapper.insert(s);
    }

    /**
     * 已有这一条 → 显式 set 本次落定的字段。
     *
     * <p>八个 {@code *_file_name} 都要按类别写回<b>最终名</b> —— 漏掉一个，盘上名字变了、
     * 库里还指着旧名，下次扫描就找不到它（正文附录 C 有这一条的症状）。
     * 没落定的类别（这次没有 / 被 [×] 掉了）一个都不 set，也就不会被清空。
     */
    private void updateRow(Computed c, Intent in) {
        var uw = com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<SongOriginalSetting>lambdaUpdate();
        uw.set(SongOriginalSetting::getArtist, StringUtils.trimToNull(c.artist()))
                .set(SongOriginalSetting::getArtistCheck, in.artistCheck())
                .set(SongOriginalSetting::getOriginalCheck, in.originalCheck())
                .set(SongOriginalSetting::getLyricCheck, in.lyricCheck())
                .set(SongOriginalSetting::getSvpCheck, in.svpCheck());
        for (Slot slot : c.slots()) {
            if (slot.kind == PickKind.READY) {
                templateService.setTypeFile(uw, slot.type, slot.toFileName);
            }
        }
        if (c.bpm() != null) {
            uw.set(SongOriginalSetting::getBpm, c.bpm());
        }
        uw.eq(SongOriginalSetting::getId, c.row().getId());
        originalMapper.update(null, uw);
    }

    // ==================== 查重（裁决 9） ====================

    /**
     * 新增时的查重。<b>必须在应用层做</b>：唯一键是 {@code (raw_name, artist)}，而
     * {@code artist} 为 NULL 时 MySQL 不做唯一性判断（NULL ≠ NULL，§0.2 第 6 条）。
     *
     * <p>判据：同名（归一比较）且 ① 两边作者都为空或完全相同 → 撞；
     * ② 一空一填 → <b>也算撞</b>（极可能是同一条，放任会建出重复行）；
     * ③ 两边作者明确不同（{@code 甲 - 乙} vs {@code 丙 - 乙}）→ 不撞（不同歌手有同名歌）。
     */
    private static SongOriginalSetting findDuplicate(List<SongOriginalSetting> rows,
                                                     String rawName, String artist) {
        String titleKey = SongNameParser.originalKey(rawName);
        if (StringUtils.isBlank(titleKey)) {
            return null;
        }
        String artistKey = SongNameParser.originalKey(artist);
        for (SongOriginalSetting row : rows) {
            if (!titleKey.equals(SongNameParser.originalKey(row.getRawName()))) {
                continue;
            }
            String rowArtist = SongNameParser.originalKey(row.getArtist());
            if (rowArtist == null || artistKey == null || artistKey.equals(rowArtist)) {
                return row;
            }
        }
        return null;
    }

    private static String duplicateMessage(String rawName, String artist,
                                           SongOriginalSetting hit) {
        String existing = display(hit.getRawName(), hit.getArtist());
        if (StringUtils.isBlank(artist) || StringUtils.isBlank(hit.getArtist())) {
            return "库里已经有一条「" + existing + "」（作者"
                    + (StringUtils.isBlank(hit.getArtist()) ? "没填" : "是「" + hit.getArtist() + "」")
                    + "），请用「编辑」打开它加文件，别新建一条";
        }
        return "「" + existing + "」已经有一条了，请用「编辑」往那条里加文件";
    }

    // ==================== 请求翻译 ====================

    private Intent toIntent(ExpandRequest r) {
        List<ImportFile> files = new ArrayList<>();
        Map<String, String> byFile = r.typeByFile() == null ? Map.of() : r.typeByFile();
        for (String raw : r.paths() == null ? List.<String>of() : r.paths()) {
            if (StringUtils.isBlank(raw)) {
                continue;
            }
            // 类别两种键都收：整个路径优先，其次文件名（前端画表用的是文件名）
            String type = byFile.get(raw);
            if (type == null) {
                Path p = parsePath(raw);
                if (p != null && p.getFileName() != null) {
                    type = byFile.get(p.getFileName().toString());
                }
            }
            files.add(new ImportFile(raw, type));
        }
        return new Intent(r.rawName(), r.artist(), r.originalId(), r.isNew(), files,
                List.of(), r.rename(), r.bpm(), r.artistCheck(), false, false, false);
    }

    private Intent toIntent(ImportRequest r) {
        return new Intent(r.rawName(), r.artist(), r.originalId(), r.isNew(),
                r.files() == null ? List.of() : r.files(),
                r.excludeExisting() == null ? List.of() : r.excludeExisting(),
                r.rename(), r.bpm(), r.artistCheck(), r.originalCheck(),
                r.lyricCheck(), r.svpCheck());
    }

    // ==================== 小工具 ====================

    /** 受管根 = 模板根 / 仅原曲根。比添加歌曲那条严一档：那边文件平铺在根下，
     *  「直接父目录就是根」等价于「在根里」；原曲侧的文件在 {@code <根>/<原曲目录>/} 这一层，
     *  只判直接父目录会漏掉真正在目录里的那些文件（那正是本条要拦的东西）。 */
    private boolean insideManagedRoot(Path file) {
        return inside(file, Path.of(properties.getTemplateDir()))
                || inside(file, Path.of(properties.getOnlyOriginalDir()));
    }

    private static boolean inside(Path file, Path root) {
        String f = realPath(file).toString().toLowerCase(Locale.ROOT);
        String r = StringUtils.stripEnd(
                realPath(root).toString().toLowerCase(Locale.ROOT), "\\/");
        return f.startsWith(r + "\\") || f.startsWith(r + "/");
    }

    /** 「甲 - 乙」/「乙」这种人话标签 */
    private static String display(String rawName, String artist) {
        return StringUtils.isBlank(artist) ? rawName : rawName + " - " + artist;
    }

    /** 主名（去掉最后一个点之后的部分）。<b>不能用 {@code MediaExtensions.mainName}</b>：
     *  它只剥<em>认识</em>的扩展名，{@code .mid} / {@code .svp} 不在其中、于是原样返回，
     *  而 {@code planMoves} 是「按最后一个点切成主名 + 后缀」重建名字的 —— 两边必须同口径，
     *  否则预览的名字与实际落盘的名字不一致。 */
    private static String baseOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String extKey(String fileName) {
        return MediaExtensions.extension(fileName);
    }

    private static String pathText(Path path) {
        return path == null ? null : path.toString();
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

    /** toRealPath：挡符号链接 / 目录联接，也统一成绝对规范路径 */
    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return List.copyOf(out);
    }
}
