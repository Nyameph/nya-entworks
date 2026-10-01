package io.github.Nyameph.nyaentworks.song.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import io.github.Nyameph.nyaentworks.common.lyric.LyricTextReader;
import io.github.Nyameph.nyaentworks.common.media.MediaExtensions;
import io.github.Nyameph.nyaentworks.common.media.MediaStreamService;
import io.github.Nyameph.nyaentworks.song.config.SongProperties;
import io.github.Nyameph.nyaentworks.song.entity.SongOriginalSetting;
import io.github.Nyameph.nyaentworks.song.mapper.SongOriginalSettingMapper;
import io.github.Nyameph.nyaentworks.song.util.SongNameParser;
import io.github.Nyameph.nyaentworks.common.file.RecycleBin;
import io.github.Nyameph.nyaentworks.common.fileop.FileOpRecorder;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpLevel;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpModule;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpSource;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 模板维护（设计概述「原曲模板」/ 页面设计方案 8.6）。
 *
 * <p><b>功能 1</b>：扫描模板根（{@code F:\歌曲\模板}）下的第 1 层目录，按目录名
 * （剥掉「 模板N」后缀）反推原曲，识别目录内文件类型，回写 {@code song_original_setting}。
 * 每类文件只取第一个命中（多模板时「原曲」优先于「原曲 模板2」）。
 *
 * <p><b>功能 2</b>：手动编辑某原曲的模板 —— 数据来源<b>只限于该原曲在模板根下已有的
 * 文件</b>（文件已由整理脚本归位到 {@code 模板\<原曲名>\}），编辑只是把这些文件指派到
 * 七类、并填歌手。这里一行都不动磁盘。
 *
 * <p><b>功能 3</b>：给列表页 / 编辑弹窗提供模板文件的播放与歌词查看（七类里的原曲 /
 * 样例 / 伴奏 / 纯人声 / 歌词），解析出完整路径；媒体走 {@link MediaStreamService}
 * （受管根由 {@code SongMediaRoots} 申报），歌词走 {@link LyricTextReader}（编码嗅探）。
 */
@Service
@RequiredArgsConstructor
public class SongTemplateService {

    private static final Logger log = LoggerFactory.getLogger(SongTemplateService.class);

    /** 七类文件类型标识，顺序与前端模板编辑区的七类下拉一一对应（原 词 伴 声 样 mid svp） */
    public static final List<String> TYPES = List.of(
            "original", "lyric", "accompaniment", "vocals", "demo", "mid", "svp");

    /** 编辑模板时，候选文件只列这些扩展名（其余文件如【模板作者】标记与模板无关） */
    private static final Set<String> CANDIDATE_EXTS = Set.of(
            "mid", "svp", "wav", "mp3", "flac", "m4a", "m4p", "lrc", "srt", "ass", "txt");

    /** 「有模板」的合成工程文件扩展名：目录下出现任一（大小 0 不算）即判「有模板」，
     *  用于「模板 / 仅原曲」分流。与七类 classify 无关，这些扩展名不落库。 */
    public static final Set<String> TEMPLATE_PROJECT_EXTS = Set.of(
            "mid", "midi", "ace", "acep", "acet", "s5p", "svip", "svip3", "svp", "vpr", "vsq", "vsqx");

    private final SongProperties properties;
    private final SongOriginalSettingMapper originalMapper;

    // ==================== 功能 1：扫描模板目录 ====================

    /** 扫描结果 */
    public record ScanResult(int scannedDirs, int upserted, int moved) {
    }

    public ScanResult scanTemplates() {
        return FileOpRecorder.batch(FileOpModule.SONG, FileOpSource.PAGE, null,
                this::scanTemplatesInside);
    }

    private ScanResult scanTemplatesInside() {
        List<Path> roots = sourceRoots();
        if (roots.isEmpty()) {
            throw new IllegalStateException("模板目录与仅原曲目录都不存在");
        }
        int scannedDirs = 0;
        int upserted = 0;
        for (DirGroup g : attributedGroups(scanTemplateDirGroups().values())) {
            upsertSetting(g.title(), g.artist(), mergeJudgements(g.dirs()));
            scannedDirs += g.dirs().size();
            upserted++;
        }
        int moved = relocateMisplacedDirs();
        return new ScanResult(scannedDirs, upserted, moved);
    }

    // ==================== 功能 2：手动编辑模板数据 ====================

    /** 某原曲的模板详情。*Path 是解析出的完整路径，供页面播放/查看；文件不在时为 null */
    public record TemplateDetail(Long originalId, String rawName,
                                 String artist,
                                 String originalFile, String demoFile,
                                 String accompanimentFile, String vocalsFile,
                                 String lyricFile, String midFile, String svpFile,
                                 String originalPath, String demoPath,
                                 String accompanimentPath, String vocalsPath,
                                 String lyricPath,
                                 boolean needManualJudge, boolean hasOtherFile,
                                 String lastSearchTime,
                                 boolean artistCheck, boolean originalCheck, boolean lyricCheck,
                                 boolean svpCheck) {
    }

    /** 提交结果：指派了几个文件、清空了几项 */
    public record ApplyResult(int assignedFiles, int clearedFiles) {
    }

    public TemplateDetail getDetail(Long originalId) {
        SongOriginalSetting s = originalId == null ? null : originalMapper.selectById(originalId);
        String title = s == null ? null : s.getRawName();
        String artist = s == null ? null : s.getArtist();
        return new TemplateDetail(originalId,
                title,
                artist,
                s == null ? null : s.getOriginalFileName(),
                s == null ? null : s.getDemoFileName(),
                s == null ? null : s.getAccompanimentFileName(),
                s == null ? null : s.getVocalsFileName(),
                s == null ? null : s.getLyricFileName(),
                s == null ? null : s.getMidFileName(),
                s == null ? null : s.getSvpFileName(),
                resolvePath(title, artist, s == null ? null : s.getOriginalFileName()),
                resolvePath(title, artist, s == null ? null : s.getDemoFileName()),
                resolvePath(title, artist, s == null ? null : s.getAccompanimentFileName()),
                resolvePath(title, artist, s == null ? null : s.getVocalsFileName()),
                resolvePath(title, artist, s == null ? null : s.getLyricFileName()),
                s != null && Boolean.TRUE.equals(s.getNeedManualJudge()),
                s != null && Boolean.TRUE.equals(s.getHasOtherFile()),
                formatLastSearch(s),
                s != null && Boolean.TRUE.equals(s.getArtistCheck()),
                s != null && Boolean.TRUE.equals(s.getOriginalCheck()),
                s != null && Boolean.TRUE.equals(s.getLyricCheck()),
                s != null && Boolean.TRUE.equals(s.getSvpCheck()));
    }

    /** 最近搜索时间格式化成 {@code yyyy-MM-dd HH:mm} 给编辑弹窗展示；没搜过返回 null */
    private static String formatLastSearch(SongOriginalSetting s) {
        LocalDateTime t = s == null ? null : s.getLastSearchTime();
        if (t == null) {
            return null;
        }
        return t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    /** 候选文件：该原曲在模板根下已有的文件（文件名，去重排序），编辑弹窗的下拉据此填选 */
    public List<String> listCandidates(Long originalId) {
        SongOriginalSetting s = originalId == null ? null : originalMapper.selectById(originalId);
        if (s == null) {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (Path dir : templateDirsFor(s.getRawName(), s.getArtist())) {
            for (Path file : collectFiles(dir)) {
                String name = file.getFileName().toString();
                if (CANDIDATE_EXTS.contains(SongNameParser.extension(name))) {
                    names.add(name);
                }
            }
        }
        return names.stream().sorted().collect(Collectors.toList());
    }

    /**
     * 该原曲所有模板目录下的文件（递归、绝对路径、按名去重后按名排序）。
     *
     * <p>给原曲表单每个文件框的「从原曲目录选」下拉用：目录里躺着扫描没认出来的伴奏 / 歌词时，
     * 用户得能把它指派给一类 —— **那条路不能只剩「从盘外搬进来」一种**（盘外的文件要搬家，
     * 已经在原曲目录里的只需要改库那一列，最多顺手改个名）。
     *
     * <p>这里不判类别、也不剔掉已指派的（那份表在 {@code SongOriginalImportService}，
     * 类别与扩展名的对应只有一份）：本方法只回答「盘上有哪些文件」。
     */
    List<Path> listFilesOfOriginal(SongOriginalSetting s) {
        if (s == null || StringUtils.isBlank(s.getRawName())) {
            return List.of();
        }
        Map<String, Path> byName = new LinkedHashMap<>();
        for (Path dir : templateDirsFor(s.getRawName(), s.getArtist())) {
            for (Path file : collectFiles(dir)) {
                byName.putIfAbsent(file.getFileName().toString().toLowerCase(Locale.ROOT), file);
            }
        }
        return List.copyOf(byName.values());
    }

    /** 手动编辑：把文件（按文件名，已在模板目录里）指派到七类 + 填歌手，写库。
     *  {@code xxxCheck} 是「已手动确认」标记：true 时对应字段后续扫描 / 搜索不再覆盖。 */
    public ApplyResult applyFiles(Long originalId, String artist,
                                  Map<String, String> files,
                                  boolean artistCheck, boolean originalCheck, boolean lyricCheck,
                                  boolean svpCheck) {
        return FileOpRecorder.batch(FileOpModule.SONG, FileOpSource.PAGE, null,
                () -> applyFilesInside(originalId, artist, files,
                        artistCheck, originalCheck, lyricCheck, svpCheck));
    }

    private ApplyResult applyFilesInside(Long originalId, String artist,
                                         Map<String, String> files,
                                         boolean artistCheck, boolean originalCheck,
                                         boolean lyricCheck, boolean svpCheck) {
        if (originalId == null) {
            throw new IllegalArgumentException("原曲 id 不能为空");
        }
        SongOriginalSetting setting = originalMapper.selectById(originalId);
        if (setting == null) {
            throw new IllegalArgumentException("原曲设置不存在（id=" + originalId + "）");
        }
        String title = setting.getRawName();
        if (StringUtils.isBlank(title)) {
            throw new IllegalArgumentException("原曲名不能为空");
        }

        // 校验类别合法、同一文件不能同时归给两类。value 为空串 = 清空这一项，
        // value 为 null = 不动（兼容旧调用）；前端全量提交，空串即「清空」
        Map<String, String> typeByFile = new LinkedHashMap<>();
        List<String> clears = new ArrayList<>();
        for (Map.Entry<String, String> e : files.entrySet()) {
            if (!TYPES.contains(e.getKey())) {
                throw new IllegalArgumentException("不认识的文件类别：" + e.getKey());
            }
            String name = e.getValue() == null ? null : e.getValue().trim();
            if (name == null) {
                continue;
            }
            if (name.isEmpty()) {
                clears.add(e.getKey());
                continue;
            }
            String prev = typeByFile.putIfAbsent(name, e.getKey());
            if (prev != null) {
                throw new IllegalArgumentException("同一个文件不能同时归给「" + prev
                        + "」和「" + e.getKey() + "」：" + name);
            }
        }

        // 数据来源限定：文件必须在该原曲的模板目录下，任一不在就整批不动（清空无需文件存在）。
        // 用「更新前」的作者定位目录 —— 重命名（若触发）发生在写库之后，此刻文件还在旧目录里。
        String oldArtist = setting.getArtist();
        for (String name : typeByFile.keySet()) {
            if (resolveFile(title, oldArtist, name) == null) {
                throw new IllegalArgumentException(
                        "「" + name + "」不在模板目录的「" + title + "」文件夹下");
            }
        }

        // 写库：显式 set（允许 null），作者 / 三项确认标记 / 七类文件一次更新到位
        var uw = Wrappers.<SongOriginalSetting>lambdaUpdate();
        uw.set(SongOriginalSetting::getArtist, StringUtils.trimToNull(artist))
                .set(SongOriginalSetting::getArtistCheck, artistCheck)
                .set(SongOriginalSetting::getOriginalCheck, originalCheck)
                .set(SongOriginalSetting::getLyricCheck, lyricCheck)
                .set(SongOriginalSetting::getSvpCheck, svpCheck);
        for (Map.Entry<String, String> e : typeByFile.entrySet()) {
            setTypeFile(uw, e.getValue(), e.getKey());
        }
        for (String type : clears) {
            setTypeFileNull(uw, type);
        }
        uw.eq(SongOriginalSetting::getId, originalId);
        originalMapper.update(null, uw);

        // 需求：修改原曲数据且 artist_check=1 时，把模板文件夹重命名为「原曲名_原曲作者」
        String newArtist = StringUtils.trimToNull(artist);
        if (artistCheck && newArtist != null) {
            renameTemplateFolderForArtist(title, oldArtist, newArtist);
        }
        // 编辑后按「有模板 → 模板 / 否则 → 仅原曲」重新对齐目录归属（磁盘上的工程文件可能已变）
        relocateMisplacedDirs();
        return new ApplyResult(typeByFile.size(), clears.size());
    }

    /**
     * 把原曲目录下的某个文件<b>移入原曲冗余根</b>，并清空对应库字段（原曲页那个红色按钮）。
     *
     * <p><b>不是删除</b>（2026-09-25 用户定：删除改成「移到冗余目录」，按钮也跟着改名）：
     * 文件完整地搬进 {@code nya-entworks.song.original-retired-dir}（出厂空串 ＝ 没配，
     * 那种情况下这个按钮直接拒绝执行并说清去哪填 —— 宁可不动，也不搬到一个说不清的地方）
     * 下与原曲目录<b>同名</b>的子目录里，同名目标自动加 {@code _冗余N}。所以「移错了」
     * 是可以去资源管理器捞回来的，这也是它不再叫「删除」的理由。搬动记进
     * {@code file_op_log}（逐路径一条，{@code unit_level = OTHER}），查得到落点。
     *
     * <p>磁盘先动、库后动：搬成功才清库字段（该类别当前指向这个文件时）——
     * 候选里未被指派的文件只搬盘、不动库。确认位随文件一起归零：文件不在原曲目录里了，
     * 确认就没了依据，留着会变成「空悬的确认」，让这一项以后再也扫描不进来。
     *
     * @param type 八类之一（含 {@code demoLrc} —— 它不在 {@link #TYPES} 那七个里，但库里有列）
     */
    public String retireFile(Long originalId, String fileName, String type) {
        return FileOpRecorder.batch(FileOpModule.SONG, FileOpSource.PAGE, null,
                () -> retireFileInside(originalId, fileName, type));
    }

    private String retireFileInside(Long originalId, String fileName, String type) {
        if (originalId == null) {
            throw new IllegalArgumentException("原曲 id 不能为空");
        }
        String name = StringUtils.trimToNull(fileName);
        if (name == null) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        if (!TYPES.contains(type) && !"demoLrc".equals(type)) {
            throw new IllegalArgumentException("不认识的文件类别：" + type);
        }
        SongOriginalSetting setting = originalMapper.selectById(originalId);
        if (setting == null) {
            throw new IllegalArgumentException("原曲设置不存在（id=" + originalId + "）");
        }
        String title = setting.getRawName();

        // 冗余根没配就**拒绝执行**：出厂值是空串（2026-09-30 改成「空 = 没配」，与其余几项
        // 同口径），空串 Path.of("") 解析出来是个空路径，拼出来会落到**进程工作目录**下 ——
        // 文件看着搬走了、其实撒在一个谁也不知道的地方。宁可不做。
        if (StringUtils.isBlank(properties.getOriginalRetiredDir())) {
            throw new IllegalStateException(
                    "没配原曲冗余根 —— 去左下角「系统配置」的「歌曲 · 基础」里填「原曲冗余根」，"
                            + "重启后端再试（这个按钮是把文件搬进那个目录，不是删除）");
        }

        // 安全：文件必须在该原曲的原曲目录下（resolveFile 只在模板根 / 仅原曲根里找）
        Path file = resolveFile(title, setting.getArtist(), name);
        if (file == null) {
            throw new IllegalArgumentException(
                    "「" + name + "」不在原曲目录的「" + title + "」文件夹下");
        }

        // 落到「冗余根\<原地那一层文件夹名>\<原名>」：用磁盘上真实的那一层目录名，
        // 而不是拿 原曲名_歌手 重拼 —— 多模板目录叫「原曲 模板2」，重拼会把它并进主目录
        Path sourceDir = file.getParent();
        Path retiredRoot = Path.of(properties.getOriginalRetiredDir());
        Path retiredDir = sourceDir == null || sourceDir.getFileName() == null
                ? retiredRoot : retiredRoot.resolve(sourceDir.getFileName().toString());
        Path target = uniqueTarget(retiredDir, name);

        // 磁盘先动：搬成功再动库。Files.move 跨卷时会自己退化成「拷贝 + 删源」
        try {
            Files.createDirectories(retiredDir);
            Files.move(file, target);
            FileOpRecorder.recordPath(FileOpModule.SONG, FileOpType.MOVE,
                    FileOpLevel.OTHER, "原曲：移入冗余", file.toString(), target.toString());
        } catch (IOException e) {
            throw new IllegalStateException("移入冗余失败：" + e.getMessage(), e);
        }

        // 原曲目录空了就顺手删掉：留着一个空目录，下次扫描会把它当成一条没有任何
        // 文件的原曲，而 relocateMisplacedDirs 还会因为「没有工程文件」把它搬去仅原曲根
        deleteIfEmpty(sourceDir);

        // 库后动：该类别当前指向这个文件才清空（候选未指派文件只搬盘）
        if (name.equals(getTypeFile(setting, type))) {
            var uw = Wrappers.<SongOriginalSetting>lambdaUpdate();
            setTypeFileNull(uw, type);
            clearCheck(uw, type);
            uw.eq(SongOriginalSetting::getId, originalId);
            originalMapper.update(null, uw);
        }
        // 移走文件后按「有模板 → 模板 / 否则 → 仅原曲」重新对齐目录归属（搬走最后一个工程文件会降级）
        relocateMisplacedDirs();
        return "已把「" + name + "」移入冗余：" + target;
    }

    /** 冗余目录里不重名的目标：{@code 甲.mp3} 已存在时依次试 {@code 甲_冗余1.mp3} */
    private static Path uniqueTarget(Path dir, String fileName) {
        Path target = dir.resolve(fileName);
        if (!Files.exists(target)) {
            return target;
        }
        String base = MediaExtensions.mainName(fileName);
        String suffix = MediaExtensions.suffix(fileName, base);
        for (int n = 1; n < 1000; n++) {
            target = dir.resolve(base + "_冗余" + n + suffix);
            if (!Files.exists(target)) {
                return target;
            }
        }
        throw new IllegalStateException("冗余目录里同名文件太多了：" + fileName);
    }

    /** 空目录就删掉（<b>进回收站</b>），非空不动；送不进去只记一句 —— 这只是收尾，不该让主操作失败 */
    private static void deleteIfEmpty(Path dir) {
        if (dir == null) {
            return;
        }
        try (var files = Files.list(dir)) {
            if (files.findAny().isPresent()) {
                return;
            }
        } catch (IOException e) {
            return;
        }
        try {
            RecycleBin.recycle(dir);
            // 目录真的没了也记一条：它跟「文件搬走」是两处改动（一个路径一条记录），
            // 而「原曲根下少了一个目录」正是用户在资源管理器里看得见、又想查的那件事
            FileOpRecorder.recordPath(FileOpModule.SONG, FileOpType.DELETE, FileOpLevel.OTHER,
                    "原曲：搬空后删掉空目录（进回收站）", dir.toString(), null);
        } catch (RuntimeException e) {
            log.warn("原曲目录空了但没能送进回收站：{}", dir, e);
        }
    }

    // ==================== 功能 3：播放与歌词查看 ====================

    /** 读某原曲模板里的歌词（编码嗅探 + 解析）。没有歌词/文件不在时返回带 message 的 Lyric。
     *  <p>{@code kind} 是播放类别：样例 / 伴奏 / 纯人声 用「样例音频歌词」（与样例音频除后缀名
     *  外同名）；其余（原曲 / 查看歌词）用原曲歌词文件。 */
    public LyricTextReader.Lyric readLyric(Long originalId, String kind) {
        SongOriginalSetting s = originalId == null ? null : originalMapper.selectById(originalId);
        boolean useDemoLrc = "demo".equals(kind) || "accompaniment".equals(kind) || "vocals".equals(kind);
        String name = useDemoLrc
                ? (s == null ? null : s.getDemoLrcFileName())
                : (s == null ? null : s.getLyricFileName());
        if (StringUtils.isBlank(name)) {
            return new LyricTextReader.Lyric(null, null, false, false, List.of(),
                    useDemoLrc ? "这一原曲没有样例音频歌词" : "这一原曲没有配歌词文件");
        }
        Path file = resolveFile(s.getRawName(), s.getArtist(), name);
        if (file == null) {
            return new LyricTextReader.Lyric(name, MediaExtensions.extension(name),
                    false, false, List.of(), "歌词文件不在了：" + name + "。刷新一下列表");
        }
        return LyricTextReader.read(file, name);
    }

    /** 某原曲的 svp 模板完整路径；没指派或文件已不在时返回 null（填词工具用）。 */
    public Path resolveSvpPath(Long originalId) {
        SongOriginalSetting s = originalId == null ? null : originalMapper.selectById(originalId);
        return s == null ? null : resolveFile(s.getRawName(), s.getArtist(), s.getSvpFileName());
    }

    /**
     * 填词分句用的歌词文件（填词工具用）：<b>样例音频歌词优先</b>，其次原曲歌词。
     *
     * <p>样例音频是模板导出的「原词打样」，它的歌词与原曲歌词同源但通常已去掉
     * 词/曲/编曲等元信息行，拿来对齐 note 更干净；没有样例歌词才退回原曲歌词。
     * 两个都没有时返回 null，分句回落到 {@code br} + 大空隙。
     *
     * <p><b>样例歌词列取不到时兜一圈</b>（{@link #scanFillLyric}）：库里的
     * {@code demo_lrc_file_name} 是扫描入库时写下的，目录里后来放进去的样例歌词、以及
     * 那一列落地前入库的老行（实测 {@code song_original_setting.id=316}《学猫叫》、另有
     * 小酒窝 / 夜探 / 折枝花满衣 / 小情歌 / 银钗响五条「记着样例音频、样例歌词列为空」）
     * 都不会有它 —— 于是一整份「原词打样」明明躺在盘上，分句却拿着<b>原曲歌词</b>去对齐
     * （《学猫叫》两版歌词不同，走法匹配率 12%、句界全切错）。
     */
    public Path resolveFillLyricPath(Long originalId) {
        SongOriginalSetting s = originalId == null ? null : originalMapper.selectById(originalId);
        if (s == null) {
            return null;
        }
        Path demo = resolveFile(s.getRawName(), s.getArtist(), s.getDemoLrcFileName());
        if (demo != null) {
            return demo;
        }
        return scanFillLyric(s);
    }

    /**
     * 样例歌词列取不到时的兜底：扫一遍模板目录，找到遗漏的样例歌词就<b>写回库</b>
     * （只动歌词两列，见 {@link #healLyricNames}），顺带把原曲歌词也在这一次扫描里解析出来
     * —— 别为它再扫第二遍。
     */
    private Path scanFillLyric(SongOriginalSetting s) {
        List<Path> dirs = templateDirsFor(s.getRawName(), s.getArtist());
        if (dirs.isEmpty()) {
            return null;
        }
        Path demo = healLyricNames(s, mergeJudgements(dirs), dirs);
        return demo != null ? demo : locateIn(dirs, s.getLyricFileName());
    }

    /**
     * 把扫描判定出的歌词文件名补回库，返回补记后能解析到的样例歌词路径（没有可补的返回 null）。
     *
     * <p>两条死规矩：
     * <ul>
     *   <li><b>只补不清</b>：判定没找到就什么都不做 —— 清空 / 取消确认位是同步扫描
     *       （{@link #upsertSetting}）的活，读路径不越权。否则外挂盘没挂上时打开一次填词页，
     *       就会把用户确认过的歌词文件名当场抹掉。</li>
     *   <li><b>不碰别的列</b>：bpm、其余六类文件、三个确认位都不动（只有原曲歌词这一列在
     *       写新值时把自己的空悬确认位归零，与 {@code upsertSetting} 同一口径）。</li>
     * </ul>
     */
    private Path healLyricNames(SongOriginalSetting s, FileJudgement j, List<Path> dirs) {
        Path demo = locateIn(dirs, j.demoLrc());
        boolean changed = false;
        var uw = Wrappers.<SongOriginalSetting>lambdaUpdate();
        // 样例歌词列没有确认位，扫描认定就是权威（upsertSetting 里也是无条件 set）
        if (demo != null && !j.demoLrc().equals(s.getDemoLrcFileName())) {
            uw.set(SongOriginalSetting::getDemoLrcFileName, j.demoLrc());
            changed = true;
        }
        // 原曲歌词同 upsertSetting：已确认的不覆盖，写识别结果时把空悬的确认位归零
        if (!confirmsValue(s.getLyricCheck(), s.getLyricFileName())
                && StringUtils.isNotBlank(j.lyric())
                && !j.lyric().equals(s.getLyricFileName())
                && locateIn(dirs, j.lyric()) != null) {
            uw.set(SongOriginalSetting::getLyricFileName, j.lyric())
                    .set(SongOriginalSetting::getLyricCheck, false);
            changed = true;
        }
        if (!changed) {
            return demo;
        }
        uw.eq(SongOriginalSetting::getId, s.getId());
        originalMapper.update(null, uw);
        log.info("填词参照歌词兜底补记：{}（id={}）样例歌词={} 原曲歌词={}",
                s.getRawName(), s.getId(), j.demoLrc(), j.lyric());
        return demo;
    }

    // ==================== 内部：扫描与路径解析 ====================

    /** 一个「原曲名 + 原曲作者」的模板目录组：目录名「原曲名」或「原曲名_作者」，
     *  多模板时再带「 模板N」后缀。{@code key} 是 title|artist 的归一化归组键 */
    private record DirGroup(String key, String title, String artist, List<Path> dirs) {
    }

    /** 原曲来源根 = 模板 + 仅原曲；只列存在的那个（缺一个不阻碍另一个） */
    private List<Path> sourceRoots() {
        List<Path> roots = new ArrayList<>();
        for (String root : new String[]{properties.getTemplateDir(), properties.getOnlyOriginalDir()}) {
            if (StringUtils.isNotBlank(root)) {
                Path p = Path.of(root);
                if (Files.isDirectory(p) && !roots.contains(p)) {
                    roots.add(p);
                }
            }
        }
        return roots;
    }

    /** 递归判断目录下是否有「有模板」的工程文件（大小为 0 的空文件不计，同 judge 口径） */
    public static boolean hasTemplateProject(Path dir) {
        try (var stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(f -> !isEmptyFile(f))
                    .anyMatch(f -> TEMPLATE_PROJECT_EXTS.contains(
                            SongNameParser.extension(f.getFileName().toString())));
        } catch (IOException e) {
            return false;
        }
    }

    /** 扫模板根 + 仅原曲根第 1 层目录，按 (原曲名, 原曲作者) 归组。每次现扫 */
    private Map<String, DirGroup> scanTemplateDirGroups() {
        Map<String, DirGroup> byKey = new LinkedHashMap<>();
        for (Path root : sourceRoots()) {
            try (var dirs = Files.list(root)) {
                for (Path dir : dirs.sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .collect(Collectors.toList())) {
                    if (!Files.isDirectory(dir)) {
                        continue;
                    }
                    String base = baseOriginalName(dir.getFileName().toString());
                    String[] ta = SongNameParser.splitOriginal(base);
                    String title = StringUtils.trimToNull(ta[0]);
                    if (title == null) {
                        continue;
                    }
                    String artist = StringUtils.trimToNull(ta[1]);
                    String key = dirKey(title, artist);
                    DirGroup g = byKey.computeIfAbsent(key,
                            k -> new DirGroup(k, title, artist, new ArrayList<>()));
                    g.dirs().add(dir);
                }
            } catch (IOException e) {
                throw new IllegalStateException("扫描模板目录失败：" + e.getMessage(), e);
            }
        }
        return byKey;
    }

    /**
     * 按「有模板 → 模板，否则 → 仅原曲」把放错根的第 1 层目录搬回正确的根，返回搬移数。
     * 目标同名已存在则跳过并告警（同名真歧义，交给人）；仅原曲根不存在时自动创建。
     */
    int relocateMisplacedDirs() {
        Path templateRoot = Path.of(properties.getTemplateDir());
        Path onlyRoot = Path.of(properties.getOnlyOriginalDir());
        int moved = 0;
        for (Path dir : listTopDirs(templateRoot)) {
            if (!hasTemplateProject(dir)) {
                moved += moveTopDir(dir, onlyRoot);
            }
        }
        for (Path dir : listTopDirs(onlyRoot)) {
            if (hasTemplateProject(dir)) {
                moved += moveTopDir(dir, templateRoot);
            }
        }
        return moved;
    }

    /** 根下第 1 层目录（按名排序，跳过文件） */
    private List<Path> listTopDirs(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var dirs = Files.list(root)) {
            return dirs.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("遍历目录失败：{}", root, e);
            return List.of();
        }
    }

    /** 搬一个目录到目标根；目标同名已存在则跳过，失败只告警不抛。返回 0/1 */
    private int moveTopDir(Path dir, Path targetRoot) {
        Path target = targetRoot.resolve(dir.getFileName());
        try {
            if (Files.exists(target)) {
                log.warn("目标已存在，跳过搬移：{} → {}", dir, target);
                return 0;
            }
            if (!Files.isDirectory(targetRoot)) {
                Files.createDirectories(targetRoot);
            }
            Files.move(dir, target);
            FileOpRecorder.recordPath(FileOpModule.SONG, FileOpType.MOVE,
                    FileOpLevel.OTHER, "搬顶层模板目录", dir.toString(), target.toString());
            log.info("已搬移：{} → {}", dir, target);
            return 1;
        } catch (IOException e) {
            log.warn("搬移失败：{} → {}（{}）", dir, target, e.getMessage());
            return 0;
        }
    }

    /**
     * 把「无作者的裸名目录」归并到同名的「带作者」组：裸名目录「曲名」对应「曲名_作者」，
     * 不另立一条只有曲名没有作者的数据。同名有多个作者时裸名目录归属模糊，保留独立
     * （交给 {@link #findSetting} 的「同名唯一才借用」判断）。带作者组在前、裸名组在后，
     * 与 {@link #templateDirsFor} 的「精确命中排前、兜底裸名排后」一致。
     */
    private List<DirGroup> attributedGroups(Collection<DirGroup> groups) {
        Map<String, List<DirGroup>> byTitle = new LinkedHashMap<>();
        for (DirGroup g : groups) {
            byTitle.computeIfAbsent(StringUtils.defaultString(SongNameParser.originalKey(g.title())),
                    k -> new ArrayList<>()).add(g);
        }
        List<DirGroup> out = new ArrayList<>();
        for (List<DirGroup> same : byTitle.values()) {
            DirGroup plain = null;
            List<DirGroup> authored = new ArrayList<>();
            for (DirGroup g : same) {
                if (g.artist() == null) {
                    plain = g;
                } else {
                    authored.add(g);
                }
            }
            out.addAll(authored);
            if (plain == null) {
                continue;
            }
            if (authored.size() == 1) {
                authored.get(0).dirs().addAll(plain.dirs()); // 唯一作者：裸名目录并进去
            } else {
                out.add(plain); // 无带作者目录（保持裸名）或多作者（归属模糊）
            }
        }
        return out;
    }

    /** (原曲名, 原曲作者) 的归组键：{@code originalKey(名)|originalKey(作者)}，作者为空后半段为空 */
    private static String dirKey(String title, String artist) {
        String t = StringUtils.defaultString(SongNameParser.originalKey(title));
        String a = artist == null ? "" : StringUtils.defaultString(SongNameParser.originalKey(artist));
        return t + "|" + a;
    }

    /** 某原曲在模板根下的目录列表；作者为空时同名原曲的目录（含带作者的）都算进来。
     *  <p>作者非空时：先收「作者精确命中」的目录（如 {@code 曲名_某作者}），再兜底收
     *  <b>无作者</b>的裸名目录（如 {@code 曲名}，对应 artistCheck=false 未改名、但库已记了
     * 作者的合法状态）；只跳过「带<b>别的</b>作者」的目录（同名真歧义，防张冠李戴）。 */
    List<Path> templateDirsFor(String title, String artist) {
        if (StringUtils.isBlank(title)) {
            return List.of();
        }
        String titleKey = SongNameParser.originalKey(title);
        String artistKey = StringUtils.isBlank(artist) ? null : SongNameParser.originalKey(artist);
        List<Path> authored = new ArrayList<>();
        List<Path> plain = new ArrayList<>();
        for (DirGroup g : scanTemplateDirGroups().values()) {
            if (!titleKey.equals(SongNameParser.originalKey(g.title()))) {
                continue;
            }
            if (artistKey == null) {
                // 作者未知：不分作者、同名目录（含带作者的）全都可用
                authored.addAll(g.dirs());
                continue;
            }
            String gk = SongNameParser.originalKey(g.artist());
            if (artistKey.equals(gk)) {
                authored.addAll(g.dirs());
            } else if (gk == null) {
                plain.addAll(g.dirs());   // 裸名目录：无作者角标，作兜底
            }
            // 其余：目录带的是别的作者 —— 同名真歧义，跳过，避免取错文件
        }
        authored.addAll(plain);   // 更精确的（带作者命中）排前，兜底的裸名排后
        return authored;
    }

    /** 在模板目录下按文件名找完整路径（同一原曲多个模板目录逐个找，第一个命中） */
    Path resolveFile(String title, String artist, String fileName) {
        return locateIn(templateDirsFor(title, artist), fileName);
    }

    /** 在一组已经扫好的目录里按文件名找完整路径，第一个命中；名字为空返回 null。 */
    private Path locateIn(List<Path> dirs, String fileName) {
        if (StringUtils.isBlank(fileName)) {
            return null;
        }
        for (Path dir : dirs) {
            Path hit = findInDir(dir, fileName);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private Path findInDir(Path dir, String fileName) {
        for (Path file : collectFiles(dir)) {
            if (file.getFileName().toString().equals(fileName)) {
                return file;
            }
        }
        return null;
    }

    /** 同 {@link #resolveFile}，只是把结果转成字符串（供页面拼媒体流地址 / 判断有无） */
    private String resolvePath(String title, String artist, String fileName) {
        Path file = resolveFile(title, artist, fileName);
        return file == null ? null : file.toString();
    }

    // ==================== 内部：文件类型判定 ====================

    /** 单文件判定结果：归类到哪一类、判定是否可靠 */
    private record Cls(String type, boolean certain) {
    }

    /**
     * 一个模板目录的判定汇总：七类各取第一个命中的文件名。
     *
     * <p>bpm 三级兜底：svp 工程曲速（工程内权威）→ midi 文件内容里的 tempo → midi 文件名。
     * midi 文件名放最后，是因为它两个方向都不可靠 —— {@code [BPM=？]} 这种没写数字的占多数，
     * 而「末尾数字」那条兜底会把 {@code 远走高飞2.mid} 读成 2。
     */
    record FileJudgement(String original, String demo, String demoLrc, String accompaniment,
                         String vocals, String lyric, String mid, String svp,
                         boolean hasOther, boolean needManual, java.math.BigDecimal bpm) {
    }

    /** 剥掉目录名尾部的「 模板N」后缀（多模板时目录名是「原曲 模板2」），取回原曲名 */
    private static String baseOriginalName(String dirName) {
        if (dirName == null) {
            return "";
        }
        return dirName.replaceFirst(" 模板\\d+$", "").trim();
    }

    private List<Path> collectFiles(Path dir) {
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile).forEach(files::add);
        } catch (IOException e) {
            log.warn("遍历目录失败：{}", dir, e);
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return files;
    }

    /** 合并同一原曲多个模板目录的判定：每类只取第一个非空命中 */
    private FileJudgement mergeJudgements(List<Path> dirs) {
        String original = null, demo = null, demoLrc = null, accomp = null, vocals = null, lyric = null,
                mid = null, svp = null;
        java.math.BigDecimal bpm = null;
        boolean hasOther = false, needManual = false;
        for (Path dir : dirs) {
            FileJudgement j = judge(collectFiles(dir));
            if (original == null) {
                original = j.original();
            }
            if (demo == null) {
                demo = j.demo();
            }
            if (demoLrc == null) {
                demoLrc = j.demoLrc();
            }
            if (accomp == null) {
                accomp = j.accompaniment();
            }
            if (vocals == null) {
                vocals = j.vocals();
            }
            if (lyric == null) {
                lyric = j.lyric();
            }
            if (mid == null) {
                mid = j.mid();
            }
            if (svp == null) {
                svp = j.svp();
            }
            if (bpm == null) {
                bpm = j.bpm();
            }
            hasOther = hasOther || j.hasOther();
            needManual = needManual || j.needManual();
        }
        return new FileJudgement(original, demo, demoLrc, accomp, vocals, lyric, mid, svp,
                hasOther, needManual, bpm);
    }

    /** 判定单个模板目录里的文件。纯函数（只看这份文件清单），包私有是为了能直接喂临时目录测。 */
    static FileJudgement judge(List<Path> files) {
        String original = null, demo = null, accomp = null, vocals = null, lyric = null,
                mid = null, svp = null;
        java.math.BigDecimal bpm = null;
        Path svpFile = null, midFile = null;
        boolean hasOther = false, needManual = false;
        String demoBase = null; // 样例音频的去后缀主名，用来配「样例音频歌词」
        List<String> lyricNames = new ArrayList<>(); // 按序收集的 .lrc/.srt 文件名
        for (Path file : files) {
            if (isEmptyFile(file)) {
                continue; // 大小为 0 的文件（下载失败 / 占位）不参与判定，也不计入「其他文件」
            }
            String name = file.getFileName().toString();
            Cls cls = classify(name);
            if (!cls.certain()) {
                needManual = true;
            }
            switch (cls.type()) {
                case "original" -> { if (original == null) original = name; }
                case "demo" -> {
                    if (demo == null) {
                        demo = name;
                        demoBase = SongNameParser.mainName(name);
                    }
                }
                case "accompaniment" -> { if (accomp == null) accomp = name; }
                case "vocals" -> { if (vocals == null) vocals = name; }
                case "lyric" -> lyricNames.add(name);
                case "mid" -> {
                    if (mid == null) {
                        mid = name;
                        midFile = file;
                    }
                }
                case "svp" -> {
                    if (svp == null) {
                        svp = name;
                        svpFile = file;
                    }
                }
                default -> hasOther = true;
            }
        }
        // 曲速：svp 工程里的 tempo 优先（工程内权威），其次 midi **内容**里的 tempo（大量
        // 文件名写着 [BPM=？]、以及「末尾数字」误命中的，都靠它），最后才退回 midi 文件名
        bpm = bpmFromSvp(svpFile);
        if (bpm == null && midFile != null) {
            bpm = bpmFromMidi(midFile);
            if (bpm == null) {
                bpm = bpmFromFileName(midFile.getFileName().toString());
            }
        }

        // 样例歌词：它本身是 .lrc、会被 classify 成 lyric，所以要单独摘出来，别占用原曲歌词
        // （lyric）的名额 —— 见 pickDemoLrc
        String demoLrc = pickDemoLrc(lyricNames, demoBase);
        // 原曲歌词 = 第一个不是样例歌词的 .lrc/.srt
        for (String c : lyricNames) {
            if (!c.equals(demoLrc)) {
                lyric = c;
                break;
            }
        }
        return new FileJudgement(original, demo, demoLrc, accomp, vocals, lyric, mid, svp,
                hasOther, needManual, bpm);
    }

    /**
     * svp 工程里的曲速：{@code time.tempo[0].bpm}（SynthV 工程的权威曲速）；读不出返回 null。
     *
     * <p>只取第一段曲速（`time.tempo[0]`）：多段变奏的工程（实测库里 5 首）拍速会变，
     * 而小节号 / 秒换算只有一个 bpm 可用，取首段。
     *
     * <p>SynthV 写出的 svp 是「单行 JSON + 一个尾部 NUL 填充字节」，不是合法 JSON 结尾，
     * fastjson2 会抛「额外数据」，所以要把尾部那个字节掐掉。截取时<b>要带上结尾的 {@code }}}
     * </b>（{@code lastIndexOf('}') + 1}）—— 早先少了这个 1，连结尾的 {@code }} 一起切掉，
     * 于是永远解析失败、bpm 全部退化成 midi 文件名里的值。
     *
     * <p>公开是给规范化命名脚本（`SongOriginalFileRenameUtil`）用的：它只认磁盘上的文件，
     * 库里还没扫出 bpm 时得自己读同目录的 svp，口径必须和扫描一致。
     */
    public static java.math.BigDecimal bpmFromSvp(Path svpFile) {
        if (svpFile == null) {
            return null;
        }
        try {
            String text = java.nio.file.Files.readString(svpFile,
                    java.nio.charset.StandardCharsets.UTF_8);
            com.alibaba.fastjson2.JSONObject root = com.alibaba.fastjson2.JSON.parseObject(
                    text.endsWith("\u0000") || text.codePointAt(text.length() - 1) == 0
                            ? text.substring(0, text.lastIndexOf('}') + 1) : text);
            com.alibaba.fastjson2.JSONArray tempos = root.getJSONObject("time") == null
                    ? null : root.getJSONObject("time").getJSONArray("tempo");
            if (tempos == null || tempos.isEmpty()) {
                return null;
            }
            java.math.BigDecimal bpm = tempos.getJSONObject(0).getBigDecimal("bpm");
            // SynthV 的曲速是浮点（相对误差约 4e-6），整数拍速常写成 84.000084000084、
            // 262.001；抹到 2 位小数正好收掉这层噪声（84.000084 → 84，262.001 → 262），
            // 又留得住 87.5 / 143.75 这种真的带小数的拍速。列是 decimal(10,4)，抹到 2 位是取值口径。
            return bpm == null ? null
                    : bpm.setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros();
        } catch (Exception e) {
            return null;   // svp 读不了 / 不是合法 JSON：曲速这一项放弃，别让扫描失败
        }
    }

    /**
     * midi 文件**内容**里的曲速：第一个 tempo 元事件（{@code FF 51 03 tttttt}，微秒 / 四分音符）
     * 换算成 BPM；没有 tempo 事件 / 不是 MIDI / 读不出来都返回 null。
     *
     * <p>为什么读内容而不只信文件名（{@link #bpmFromFileName}）：实测模板目录里 138 个 mid，
     * 文件名叫 {@code [BPM=？]} 的有约 40 个（作者不知道拍速），**内容里都有真值**；而「文件名
     * 末尾数字」那条兜底会误命中（{@code 远走高飞2.mid} → 2）。剩下 2 个内容里压根没有 tempo
     * 事件（{@code 耍把戏} 的两轨），只能各自退到文件名。所以优先级是
     * **svp 工程 ＞ midi 内容 ＞ midi 文件名**，见 {@link #judge}。
     *
     * <p>只取第一个（多段曲速的工程拍速会变，而小节号 / 秒换算只有一个 bpm 可用，与
     * {@link #bpmFromSvp} 取 {@code tempo[0]} 同口径）。小数抹到 2 位：MIDI 存的是整数微秒，
     * 130.5 这类拍速本来就是 60e6/459770 = 130.5001…，抹完正好还原（列是 {@code decimal(10,4)}，
     * 存进去是 130.5000，抹 2 位只是取值的口径）。
     */
    public static java.math.BigDecimal bpmFromMidi(Path midFile) {
        if (midFile == null) {
            return null;
        }
        try {
            return firstTempoBpm(java.nio.file.Files.readAllBytes(midFile));
        } catch (Exception e) {
            return null;   // 读不了 / 不是 MIDI：曲速这一项放弃，别让扫描失败
        }
    }

    /** midi 字节里的第一个 tempo（{@link #bpmFromMidi} 的实现；单独一层便于直接喂坏字节） */
    private static java.math.BigDecimal firstTempoBpm(byte[] data) {
        if (data == null || data.length < HEADER_BYTES) {
            return null;
        }
        Midi midi = new Midi(data);
        if (!"MThd".equals(midi.tag())) {
            return null;
        }
        // 头部剩下的是格式 / 轨数 / 分度（曲速只看 tempo，用不上），按声明长度跳过
        midi.skip(midi.u32());
        while (midi.remaining() >= 8) {
            String tag = midi.tag();
            long declared = midi.u32();
            if (declared < 0) {
                return null;                      // 长度字段读不全 = 文件截断
            }
            int end = (int) Math.min(data.length, midi.at + declared);
            if ("MTrk".equals(tag)) {
                java.math.BigDecimal bpm = tempoInTrack(midi, end);
                if (bpm != null) {
                    return bpm;
                }
            }
            midi.at = end;
        }
        return null;
    }

    /**
     * 一轨里找第一个 tempo 元事件。**按事件结构逐个走**（变长量 delta + 状态字节 + 数据），
     * 不做 {@code FF 51 03} 裸扫 —— 裸扫会把 SysEx 数据里的同样字节当成 tempo。
     * 结构读不通（截断 / 出现文件里不该有的系统消息）就放弃这一轨，返回 null。
     */
    private static java.math.BigDecimal tempoInTrack(Midi midi, int end) {
        midi.at = Math.min(midi.at, end);
        int status = 0;
        while (midi.at < end) {
            if (midi.vlq() < 0) {
                return null;                      // delta 读不全 = 结构不可信
            }
            int first = midi.u8();
            if (first < 0) {
                return null;
            }
            if (first < 0x80) {
                if (status == 0) {
                    return null;                  // running status 但还没有可继承的状态
                }
                midi.at--;                        // 这一字节是数据，不是状态
            } else {
                status = first;
            }
            if (status == 0xFF) {
                int type = midi.u8();
                int size = midi.vlq();
                if (size < 0) {
                    return null;
                }
                if (type == 0x51 && size == 3) {
                    int micros = (midi.u8() << 16) | (midi.u8() << 8) | midi.u8();
                    return bpmOfMicros(micros);
                }
                midi.skip(size);
            } else if (status == 0xF0 || status == 0xF7) {
                midi.skip(midi.vlq());            // SysEx / 转义：整段跳过
            } else if (status >= 0xF0) {
                return null;                      // F1~FE 是实时 / 系统专用消息，文件里不该有
            } else if ((status & 0xF0) == 0xC0 || (status & 0xF0) == 0xD0) {
                midi.skip(1);                     // program change / channel pressure
            } else {
                midi.skip(2);                     // note on/off、aftertouch、control、pitch bend
            }
        }
        return null;
    }

    /** 微秒 / 四分音符 → BPM，抹到 2 位小数（与 {@link #bpmFromSvp} 同口径）；非正返回 null */
    private static java.math.BigDecimal bpmOfMicros(int micros) {
        return micros <= 0 ? null
                : java.math.BigDecimal.valueOf(60_000_000L)
                        .divide(java.math.BigDecimal.valueOf(micros), 2,
                                java.math.RoundingMode.HALF_UP)
                        .stripTrailingZeros();
    }

    /** MIDI 头部固定长度：{@code 'MThd'} + 长度字段(4) + 格式 / 轨数 / 分度(6) */
    private static final int HEADER_BYTES = 14;

    /** MIDI 字节游标：读越界一律返回 -1（调用方据此判「结构不可信」），游标不越出数组 */
    private static final class Midi {
        private final byte[] data;
        private int at;

        Midi(byte[] data) {
            this.data = data;
        }

        int remaining() {
            return data.length - at;
        }

        /** 读 4 字节标签（{@code MThd} / {@code MTrk}）；不够 4 字节返回空串、游标不动 */
        String tag() {
            if (remaining() < 4) {
                return "";
            }
            String tag = new String(data, at, 4, java.nio.charset.StandardCharsets.US_ASCII);
            at += 4;
            return tag;
        }

        int u8() {
            return at < data.length ? data[at++] & 0xFF : -1;
        }

        long u32() {
            long value = 0;
            for (int i = 0; i < 4; i++) {
                int b = u8();
                if (b < 0) {
                    return -1;
                }
                value = value << 8 | b;
            }
            return value;
        }

        /** 变长量：每字节低 7 位有效、最高位 = 还有后续，最多 4 字节；读不全返回 -1 */
        int vlq() {
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int b = u8();
                if (b < 0) {
                    return -1;
                }
                value = value << 7 | b & 0x7F;
                if ((b & 0x80) == 0) {
                    return value;
                }
            }
            return -1;
        }

        void skip(long count) {
            at = count < 0 ? data.length : (int) Math.min(data.length, at + count);
        }
    }

    /**
     * midi 文件名里的曲速：优先 {@code [BPM=NN]}，否则末尾完整数字（含小数）；没有返回 null。
     *
     * <p>三级兜底里最不可靠的一级（{@code [BPM=？]} 写不出数字、末尾数字会把
     * {@code 远走高飞2.mid} 读成 2），只在前两级都读不到时才用。public 是给
     * {@code SongMidiBpmUtil} 这类排查脚本直接调。
     */
    public static java.math.BigDecimal bpmFromFileName(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(?:\\[BPM=(\\d+(?:\\.\\d+)?)\\].*|.*?(\\d+(?:\\.\\d+)?))$")
                .matcher(base.trim());
        if (!m.find()) {
            return null;
        }
        String num = m.group(1) != null ? m.group(1) : m.group(2);
        return new java.math.BigDecimal(num).stripTrailingZeros();
    }

    /** 大小为 0 的文件（下载失败 / 占位的空文件）不参与类型判定，也不计入「其他文件」 */
    private static boolean isEmptyFile(Path file) {
        try {
            return Files.size(file) == 0;
        } catch (IOException e) {
            return false; // 读不到大小按非空处理，别因 IO 异常漏判
        }
    }

    /** 按扩展名 + 文件名特征归类（搬迁自已删的一次性脚本 {@code SongTemplateArrangeUtil}，
     *  脚本 2026-09-15 清理时删掉，逻辑留在这里） */
    /**
     * {@link #classify} 的字符串外壳：按扩展名 + 文件名特征猜一个类别标识。
     *
     * <p>返回的是 {@code TYPES} 里的七个之一，<b>猜不出来时返回 {@code null}</b>（{@code classify}
     * 里的 {@code "other"}）。给「原曲导入」用：那边让用户自己指派类别，只在用户没指派、又没别的
     * 线索时才拿这个当预选值 —— 猜错等于静默把文件归错类（前端把它当预选，用户能看到），
     * 所以宁可返回 {@code null} 让前端把下拉留空。
     *
     * <p>{@code classify} 认得 {@code .srt} → {@code lyric}，{@code TYPES} 里也有 {@code lyric}。
     * 但它认不出的（{@code .txt} / {@code .mp4}）一律 {@code other} → 这里转成 {@code null}。
     */
    static String guessType(String fileName) {
        Cls cls = classify(fileName);
        return TYPES.contains(cls.type()) ? cls.type() : null;
    }

    private static Cls classify(String fileName) {
        String ext = SongNameParser.extension(fileName);
        String lower = fileName.toLowerCase(Locale.ROOT);
        switch (ext) {
            case "svp":
                return new Cls("svp", true);
            case "mid":
                return new Cls("mid", true);
            case "lrc":
            case "srt":
                return new Cls("lyric", true);
            case "wav":
            case "mp3":
            case "flac":
            case "m4a":
            case "m4p":
                return classifyAudio(lower);
            default:
                return new Cls("other", true);
        }
    }

    /** 音频文件按文件名特征区分原曲 / 样例 / 伴奏 / 纯人声 */
    private static Cls classifyAudio(String lower) {
        if (lower.contains("vocals") || lower.contains("人声") || lower.contains("干声")
                || lower.contains("纯人声")) {
            return new Cls("vocals", true);
        }
        if (lower.contains("伴奏")) {
            return new Cls("accompaniment", true);
        }
        if (lower.contains("music") || lower.contains("bgm") || lower.contains("instrumental")) {
            return new Cls("accompaniment", true);
        }
        // 样例音频：DAW 导出的 MixDown（忽略大小写）或「原曲打样 / 原词打样」
        if (lower.contains("mixdown") || lower.contains("原曲打样") || lower.contains("原词打样")) {
            return new Cls("demo", true);
        }
        if (lower.contains("打样") || lower.contains("demo") || lower.contains("混音")) {
            return new Cls("demo", false);
        }
        if (lower.contains(" - ")) {
            return new Cls("original", true);
        }
        return new Cls("original", false);
    }

    /**
     * 挑出「样例歌词」（DAW 打样时唱的那份词），挑不到返回 {@code null}。
     *
     * <p>两级：① 与<b>样例音频</b>除后缀名外同名的歌词（{@code [demo] xxx.mp3} → {@code [demo] xxx.lrc}
     * 或 {@code .srt}，老规矩）→ ② 文件名自己带样例标记的歌词（{@code [demo] xxx.lrc} /
     * {@code xxx_MixDown.lrc}，判据与 {@link #classifyAudio} 同一份标记表）。顺序这么排是
     * <b>只做加法</b>：第①级认得出来的还是它，第②级只在前者挑不到时兜底，已入库的那批样例歌词
     * 一个都不会换。
     *
     * <p><b>第②级不看样例音频在不在</b>：有样例音频时是「优先同名、没有同名再按标记」，没有样例
     * 音频时（{@code demoBase == null}）直接走标记 —— 也就是「没有样例音频也能拿到样例歌词」。
     * 目录里只有原曲歌词（{@code 作者 - 曲名.lrc}，不带任何标记）时不认，宁可空着，因为那是原曲
     * 原唱的词、不是打样唱的（见 {@code docs/填词工具设计.md} §13 第 54、63 条）。
     *
     * <p>第②级不能省：第①级要求「样例音频也在」，可实测有目录只留了打样歌词、没留打样音频
     * （《气泡少女》只有 {@code [demo] 气泡少女.lrc}，音频是 {@code [人声]} / {@code [伴奏]} 两条）。
     * 两级都没有时 {@code demoLrc} 为空，填词工具就退回用原曲歌词当参照 —— 可《气泡少女》的原曲
     * 歌词前 5 行是「作曲/作词/编曲…」名单，自动对齐被这几行带偏（offset 2.97 s）、对齐失败，
     * 那份模板的拼音格既拿不到 demo 汉字也不标红，看着就是「模板没去匹配 demo 歌词」。
     *
     * <p>样例歌词为什么该排在原曲歌词之前：它时间轴更贴工程（打样音频导出来的），而原曲歌词常有
     * 名单行 / 整段重复，会带偏分句与对齐（见 {@code LyricFillService#resolveFillLyricPath}）。
     */
    static String pickDemoLrc(List<String> lyricNames, String demoBase) {
        if (demoBase != null) {
            for (String c : lyricNames) {
                // 「与样例音频除后缀名外同名」就认 —— 不再限定 .lrc（lyricNames 本来只有
                // .lrc/.srt 两种，同名的 .srt 以前要等 ② 才兜得住，而 ② 还要求它自带标记）
                if (demoBase.equals(SongNameParser.mainName(c))) {
                    return c;
                }
            }
        }
        for (String c : lyricNames) {
            if (isDemoLyricName(c)) {
                return c;
            }
        }
        return null;
    }

    /** 文件名自己带样例标记的歌词（{@code 打样} / {@code demo} / {@code MixDown} / {@code 混音}）。
     *  标记表复用 {@link #classifyAudio}，只此一份。 */
    static boolean isDemoLyricName(String fileName) {
        return "demo".equals(classifyAudio(StringUtils.defaultString(fileName)
                .toLowerCase(Locale.ROOT)).type());
    }

    private void upsertSetting(String rawName, String artist, FileJudgement j) {
        SongOriginalSetting existing = findSetting(rawName, artist);
        if (existing == null) {
            SongOriginalSetting s = new SongOriginalSetting();
            s.setRawName(rawName);
            s.setArtist(artist);
            applyJudgement(s, j);
            s.setBpm(j.bpm());
            originalMapper.insert(s);
            return;
        }
        // 曲速跟着磁盘刷新（svp / midi 文件变了曲速就变）；这次没提取到则保留库里的旧值
        var uw = Wrappers.<SongOriginalSetting>lambdaUpdate();
        if (j.bpm() != null) {
            uw.set(SongOriginalSetting::getBpm, j.bpm());
        }
        // 目录名带「_作者」时才更新作者，且已手动确认的作者不覆盖
        if (artist != null && !Boolean.TRUE.equals(existing.getArtistCheck())) {
            uw.set(SongOriginalSetting::getArtist, artist);
        }
        // 已确认（check=1）的原曲文件扫描不覆盖，但只检测它指向的文件是否还在：
        // 文件已被删 → 确认失效，check 置回 0 并清空字段，让下次扫描 / 搜索能重新识别。
        if (confirmsValue(existing.getOriginalCheck(), existing.getOriginalFileName())) {
            if (resolveFile(existing.getRawName(), existing.getArtist(),
                    existing.getOriginalFileName()) == null) {
                uw.set(SongOriginalSetting::getOriginalCheck, false)
                        .set(SongOriginalSetting::getOriginalFileName, null);
            }
        } else {
            uw.set(SongOriginalSetting::getOriginalFileName, j.original());
            if (j.original() != null) {
                uw.set(SongOriginalSetting::getOriginalCheck, false);   // 扫描到了真文件，空悬的确认位归零
            }
        }
        uw.set(SongOriginalSetting::getDemoFileName, j.demo())
                .set(SongOriginalSetting::getDemoLrcFileName, j.demoLrc())
                .set(SongOriginalSetting::getAccompanimentFileName, j.accompaniment())
                .set(SongOriginalSetting::getVocalsFileName, j.vocals());
        // 已确认（check=1）的歌词文件同上：文件不在就取消确认、清空字段
        if (confirmsValue(existing.getLyricCheck(), existing.getLyricFileName())) {
            if (resolveFile(existing.getRawName(), existing.getArtist(),
                    existing.getLyricFileName()) == null) {
                uw.set(SongOriginalSetting::getLyricCheck, false)
                        .set(SongOriginalSetting::getLyricFileName, null);
            }
        } else {
            uw.set(SongOriginalSetting::getLyricFileName, j.lyric());
            if (j.lyric() != null) {
                uw.set(SongOriginalSetting::getLyricCheck, false);   // 扫描到了真文件，空悬的确认位归零
            }
        }
        uw.set(SongOriginalSetting::getMidFileName, j.mid())
                .set(SongOriginalSetting::getHasOtherFile, j.hasOther())
                .set(SongOriginalSetting::getNeedManualJudge, j.needManual());
        // 已确认（check=1）的 svp 模板同上：文件不在就取消确认、清空字段
        if (confirmsValue(existing.getSvpCheck(), existing.getSvpFileName())) {
            if (resolveFile(existing.getRawName(), existing.getArtist(),
                    existing.getSvpFileName()) == null) {
                uw.set(SongOriginalSetting::getSvpCheck, false)
                        .set(SongOriginalSetting::getSvpFileName, null);
            }
        } else {
            uw.set(SongOriginalSetting::getSvpFileName, j.svp());
            if (j.svp() != null) {
                uw.set(SongOriginalSetting::getSvpCheck, false);   // 扫描到了真文件，空悬的确认位归零
            }
        }
        uw.eq(SongOriginalSetting::getId, existing.getId());
        originalMapper.update(null, uw);
    }

    /** 确认位是否真的锁着一个「有内容」的字段 —— 只有这时扫描才不覆盖它。
     *
     *  <p>{@code check=1} 但字段为空是<b>没有可保护的确认</b>：它不是「确认过了」，只是编辑
     *  弹窗里「清空下拉 + 勾已确认」凑出来的状态（或删文件后残留的）。不这么判的后果是这种行
     *  会被「已确认 → 不覆盖」和「文件为空 → 无从检测文件是否还在」两头夹死 —— 磁盘上明明有
     *  {@code [工程] xxx.svp}，也永远进不了库，表现就是「扫描漏了文件」。
     *
     *  <p>所以字段为空且这次扫描<b>确实识别到了文件</b>时，写识别结果并把确认位置回 0（页面
     *  随即按「未确认」的警示色提示人工确认）；扫描什么都没识别到则原样留着确认位 —— 那是
     *  「这一项本来就没有、别去网上补」的有意留空，搜资源那边要靠它挡网络补全
     *  （见 docs/实现说明.md §4）。
     *
     *  <p>只用于原曲 / 歌词 / svp 三个<b>文件</b>字段；歌手位不走这里 —— 歌手只能靠搜资源
     *  补全，「有意留空」在那边是常态。 */
    static boolean confirmsValue(Boolean check, String value) {
        return Boolean.TRUE.equals(check) && StringUtils.isNotBlank(value);
    }

    /** 按 (raw_name, artist) 找设置行；artist 为空按 IS NULL 找，找不到时若同名只有唯一一条
     *  （带作者）则借用它 —— 裸名目录「曲名」对应库里的「曲名_作者」，不另建只有曲名没有
     *  作者的行。同名多作者不借用（归属模糊，避免张冠李戴）。 */
    private SongOriginalSetting findSetting(String rawName, String artist) {
        if (StringUtils.isBlank(rawName)) {
            return null;
        }
        SongOriginalSetting hit = originalMapper.selectOne(Wrappers.<SongOriginalSetting>lambdaQuery()
                .eq(SongOriginalSetting::getRawName, rawName)
                .eq(StringUtils.isNotBlank(artist), SongOriginalSetting::getArtist, artist)
                .isNull(StringUtils.isBlank(artist), SongOriginalSetting::getArtist)
                .last("LIMIT 1"));
        if (hit != null || StringUtils.isNotBlank(artist)) {
            return hit;
        }
        List<SongOriginalSetting> sameName = originalMapper.selectList(
                Wrappers.<SongOriginalSetting>lambdaQuery()
                        .eq(SongOriginalSetting::getRawName, rawName));
        return sameName.size() == 1 ? sameName.get(0) : null;
    }

    /** 需求：确认作者（artist_check=1）时，把该原曲的模板文件夹重命名为「原曲名_原曲作者」。
     *  只改「属于这个设置」的目录 —— 标题匹配且当前作者 == 旧作者；带别的作者的同名目录不碰。
     *  磁盘先动，失败抛异常（此时库已写好新作者，需人工对齐）。 */
    void renameTemplateFolderForArtist(String rawName, String oldArtist, String newArtist) {
        Path root = Path.of(properties.getTemplateDir());
        if (!Files.isDirectory(root)) {
            return;
        }
        String titleKey = SongNameParser.originalKey(rawName);
        String oldArtistKey = StringUtils.isBlank(oldArtist)
                ? null : SongNameParser.originalKey(oldArtist);
        String wantBase = rawName.trim() + "_" + newArtist.trim();
        // 先收集「标题命中」的目录，按作者口径分成两档：精确命中旧作者的 优先；
        // 无作者的裸名目录 兜底（对应库已记作者、目录未改名的状态）。带别的作者的跳过。
        List<Path> exact = new ArrayList<>();
        List<Path> plain = new ArrayList<>();
        try (var dirs = Files.list(root)) {
            for (Path dir : dirs.sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .collect(Collectors.toList())) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                String dirName = dir.getFileName().toString();
                String base = baseOriginalName(dirName);
                String[] ta = SongNameParser.splitOriginal(base);
                if (!titleKey.equals(SongNameParser.originalKey(ta[0]))) {
                    continue;
                }
                if (oldArtistKey == null) {
                    if (ta[1] != null) {
                        continue; // 带别的作者的目录不属于这个设置
                    }
                    plain.add(dir);
                } else {
                    String gk = SongNameParser.originalKey(ta[1]);
                    if (oldArtistKey.equals(gk)) {
                        exact.add(dir);
                    } else if (gk == null) {
                        plain.add(dir); // 裸名目录：作兜底
                    }
                    // 其余：带的是别的作者的同名目录 —— 同名真歧义，跳过
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("扫描模板目录失败：" + e.getMessage(), e);
        }
        List<Path> sources = exact.isEmpty() ? plain : exact;
        for (Path dir : sources) {
            String dirName = dir.getFileName().toString();
            String base = baseOriginalName(dirName);
            String suffix = dirName.substring(base.length()); // "" 或 " 模板N"
            Path target = root.resolve(wantBase + suffix);
            if (target.equals(dir)) {
                continue; // 已经是对的名字
            }
            try {
                Files.move(dir, target);
                FileOpRecorder.recordPath(FileOpModule.SONG, FileOpType.MOVE,
                        FileOpLevel.OTHER, "模板目录改名", dir.toString(), target.toString());
            } catch (IOException e) {
                throw new IllegalStateException("重命名模板文件夹失败：" + dirName + " → " + wantBase + suffix, e);
            }
        }
    }

    private void applyJudgement(SongOriginalSetting s, FileJudgement j) {
        s.setOriginalFileName(j.original());
        s.setDemoFileName(j.demo());
        s.setDemoLrcFileName(j.demoLrc());
        s.setAccompanimentFileName(j.accompaniment());
        s.setVocalsFileName(j.vocals());
        s.setLyricFileName(j.lyric());
        s.setMidFileName(j.mid());
        s.setSvpFileName(j.svp());
        s.setHasOtherFile(j.hasOther());
        s.setNeedManualJudge(j.needManual());
    }

    /**
     * 把某类别的文件名写到实体上（insert 用）。
     *
     * <p>{@code demoLrc} 也收（{@code TYPES} 里没有它，但库里有 {@code demo_lrc_file_name}
     * 列、盘上有这个文件）：原曲导入（{@code SongOriginalImportService}）按八类写库，
     * 而既有调用点都先过 {@code TYPES.contains} 那道闸，传不进 {@code demoLrc}。
     */
    void setTypeFile(SongOriginalSetting s, String type, String fileName) {
        switch (type) {
            case "original" -> s.setOriginalFileName(fileName);
            case "demo" -> s.setDemoFileName(fileName);
            case "demoLrc" -> s.setDemoLrcFileName(fileName);
            case "accompaniment" -> s.setAccompanimentFileName(fileName);
            case "vocals" -> s.setVocalsFileName(fileName);
            case "lyric" -> s.setLyricFileName(fileName);
            case "mid" -> s.setMidFileName(fileName);
            case "svp" -> s.setSvpFileName(fileName);
            default -> throw new IllegalArgumentException("不认识的文件类别：" + type);
        }
    }

    /** 同 {@link #setTypeFile}，写到 lambdaUpdate 包装器上（允许 set null 的批量更新） */
    void setTypeFile(LambdaUpdateWrapper<SongOriginalSetting> uw, String type, String fileName) {
        switch (type) {
            case "original" -> uw.set(SongOriginalSetting::getOriginalFileName, fileName);
            case "demo" -> uw.set(SongOriginalSetting::getDemoFileName, fileName);
            case "demoLrc" -> uw.set(SongOriginalSetting::getDemoLrcFileName, fileName);
            case "accompaniment" -> uw.set(SongOriginalSetting::getAccompanimentFileName, fileName);
            case "vocals" -> uw.set(SongOriginalSetting::getVocalsFileName, fileName);
            case "lyric" -> uw.set(SongOriginalSetting::getLyricFileName, fileName);
            case "mid" -> uw.set(SongOriginalSetting::getMidFileName, fileName);
            case "svp" -> uw.set(SongOriginalSetting::getSvpFileName, fileName);
            default -> throw new IllegalArgumentException("不认识的文件类别：" + type);
        }
    }

    /** 读某类别当前指派到的文件名（删除文件时判断它是不是正被指派到这一类） */
    String getTypeFile(SongOriginalSetting s, String type) {
        return switch (type) {
            case "original" -> s.getOriginalFileName();
            case "demo" -> s.getDemoFileName();
            case "demoLrc" -> s.getDemoLrcFileName();
            case "accompaniment" -> s.getAccompanimentFileName();
            case "vocals" -> s.getVocalsFileName();
            case "lyric" -> s.getLyricFileName();
            case "mid" -> s.getMidFileName();
            case "svp" -> s.getSvpFileName();
            default -> throw new IllegalArgumentException("不认识的文件类别：" + type);
        };
    }

    /** 显式 set null 清空：{@code updateById} 不更新 null 字段，清空须用 lambdaUpdate */
    void setTypeFileNull(LambdaUpdateWrapper<SongOriginalSetting> uw, String type) {
        switch (type) {
            case "original" -> uw.set(SongOriginalSetting::getOriginalFileName, null);
            case "demo" -> uw.set(SongOriginalSetting::getDemoFileName, null);
            case "demoLrc" -> uw.set(SongOriginalSetting::getDemoLrcFileName, null);
            case "accompaniment" -> uw.set(SongOriginalSetting::getAccompanimentFileName, null);
            case "vocals" -> uw.set(SongOriginalSetting::getVocalsFileName, null);
            case "lyric" -> uw.set(SongOriginalSetting::getLyricFileName, null);
            case "mid" -> uw.set(SongOriginalSetting::getMidFileName, null);
            case "svp" -> uw.set(SongOriginalSetting::getSvpFileName, null);
            default -> throw new IllegalArgumentException("不认识的文件类别：" + type);
        }
    }

    /** 归零某类别的确认位。只有原曲 / 歌词 / svp 三类有确认位（其余四类的判定不受确认影响，
     *  是 no-op）—— 与 {@link #confirmsValue} 只管这三个文件字段同一口径。 */
    private void clearCheck(LambdaUpdateWrapper<SongOriginalSetting> uw, String type) {
        switch (type) {
            case "original" -> uw.set(SongOriginalSetting::getOriginalCheck, false);
            case "lyric" -> uw.set(SongOriginalSetting::getLyricCheck, false);
            case "svp" -> uw.set(SongOriginalSetting::getSvpCheck, false);
            default -> {
            }
        }
    }
}
