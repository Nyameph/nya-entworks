package io.github.Nyameph.nyaentworks.script;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import io.github.Nyameph.nyaentworks.song.entity.SongOriginalSetting;
import io.github.Nyameph.nyaentworks.song.mapper.SongOriginalSettingMapper;
import io.github.Nyameph.nyaentworks.song.service.SongTemplateService;
import io.github.Nyameph.nyaentworks.song.util.SongNameParser;
import io.github.Nyameph.nyaentworks.song.util.SongOriginalNaming;
import io.github.Nyameph.nyaentworks.common.fileop.FileOpRecorder;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpLevel;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpModule;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpSource;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpType;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 「原曲文件统一命名」一次性脚本：把 {@code song_original_setting} 里
 * {@code artist_check=1} 的原曲，其模板目录下的八类文件按统一规则重命名，并回写库。
 *
 * <p><b>命名规则</b>（{@code artist} / {@code raw_name} 取库里的原文，扩展名沿用旧文件）：
 * <ul>
 *   <li>{@code original_file_name}：{@code artist - raw_name}</li>
 *   <li>{@code lyric_file_name}：{@code artist - raw_name}</li>
 *   <li>{@code demo_file_name}：{@code [demo] raw_name}（<b>不带作者</b>）</li>
 *   <li>{@code demo_lrc_file_name}：{@code [demo] raw_name}（与 {@code demo} 同主名，只在扩展名上区分）</li>
 *   <li>{@code accompaniment_file_name}：{@code [伴奏] raw_name}</li>
 *   <li>{@code vocals_file_name}：{@code [人声] raw_name}</li>
 *   <li>{@code mid_file_name}：{@code [BPM=曲速] raw_name} —— 曲速的取值优先级是
 *       <b>同目录 svp 工程里的 tempo ＞ midi 内容里的 tempo ＞ 库里的 bpm 字段 ＞ 旧文件名里的数字</b>，
 *       四者都没有就写 {@code [BPM=？]}</li>
 *   <li>{@code svp_file_name}：{@code [工程] raw_name}</li>
 * </ul>
 * <p><b>这套形状的唯一一份</b>在 {@link io.github.Nyameph.nyaentworks.song.util.SongOriginalNaming}
 * （本类只负责「扫哪些行、算哪个曲速、怎么落盘」，拼法转调它）—— 与「新增原曲 / 修改」
 * 页面入口共用。另：本类只在 {@code artist_check=1} 的行上跑，所以作者非空是前置；
 * {@code SongOriginalNaming} 那边作者为空时是「只有 raw_name」，不留孤零零的 {@code " - "}。
 *
 * <p>重名（两个文件算出同一个目标名，或目标名已存在且不是本批次要改走的源）会<b>报错</b>，
 * 冲突的文件跳过、其余照改（每条改名相互独立，跳过冲突不破坏其余数据）。磁盘先动、库后动：
 * 先逐个 {@code Files.move}，全改完再统一写库。
 *
 * <p><b>用法</b>：先看 {@link #renameFiles()} 里的 {@code justTest}（默认 {@code true}
 * 只打印预演），核对报告无误后置 {@code false} 重跑。
 * {@code ./mvnw test -Dtest=SongOriginalFileRenameUtil#renameFiles}
 */
@SpringBootTest
public class SongOriginalFileRenameUtil {

    @Autowired
    private SongOriginalSettingMapper originalSettingMapper;

    /** 模板根（八类文件都在其下的「原曲名」/「原曲名_作者」目录里） */
    private static final String TEMPLATE_DIR = "F:\\歌曲\\模板";

    /** 仅原曲根：只有原曲音频 + 歌词、没有合成工程文件的原曲目录也在这里 */
    private static final String ONLY_ORIGINAL_DIR = "F:\\歌曲\\仅原曲";

    /** 八类文件类型标识（demoLrc 与 demo 同主名、只在扩展名上区分，需单独一项） */
    private static final List<String> TYPES = List.of(
            "original", "demo", "demoLrc", "accompaniment", "vocals", "lyric", "mid", "svp");

    /** 旧文件名里的曲速：优先 {@code [BPM=NNN]} 前缀，否则取末尾完整数字（含小数）。
     *  第二分支用非贪婪 {@code .*?}，否则会把末尾多位数字吃剩一位。 */
    private static final Pattern TRAILING_NUMBER = Pattern.compile(
            "^(?:\\[BPM=(\\d+(?:\\.\\d+)?)\\].*|.*?(\\d+(?:\\.\\d+)?))$");

    /** 一条改名计划 */
    private record Plan(SongOriginalSetting setting, String type,
                        Path source, Path target, String newName) {
    }

    /** 模板根第 1 层目录（按 (原曲名, 原曲作者) 归组用） */
    private record DirEntry(String titleKey, String artistKey, Path dir) {
    }

    @Test
    public void renameFiles() throws IOException {
        // 是否真正改名 + 写库。默认 true 只打印预演；置 false 执行（不可回滚）
        final boolean justTest = true;

        //Path report = Path.of(justTest? "F:\\歌曲\\原曲文件重命名预演.txt" : "F:\\歌曲\\原曲文件重命名执行.txt");
        //System.setOut(new PrintStream(Files.newOutputStream(report), true, StandardCharsets.UTF_8));

        List<DirEntry> entries = new ArrayList<>();
        for (String rootStr : List.of(TEMPLATE_DIR, ONLY_ORIGINAL_DIR)) {
            Path root = Path.of(rootStr);
            if (Files.isDirectory(root)) {
                entries.addAll(scanTopDirs(root));
            } else {
                System.out.println("😭 目录不存在：" + rootStr);
            }
        }
        System.out.println("扫描到目录：" + entries.size() + " 个");

        List<SongOriginalSetting> targets = originalSettingMapper.selectList(null).stream()
                .filter(s -> Boolean.TRUE.equals(s.getArtistCheck()))
                .sorted(Comparator.comparing(SongOriginalSetting::getId))
                .collect(Collectors.toList());
        System.out.println("=1==================================");
        System.out.println("artist_check=1 的原曲：" + targets.size() + " 行");

        // 1) 先只算计划，不碰磁盘不碰库
        List<Plan> plans = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        // 曲速被补上的原曲：mid 文件本来就是目标名、不改名时也要把新曲速写回库
        Map<Long, SongOriginalSetting> bpmTouched = new LinkedHashMap<>();
        for (SongOriginalSetting s : targets) {
            String artist = StringUtils.trimToNull(s.getArtist());
            String rawName = StringUtils.trimToNull(s.getRawName());
            if (artist == null || rawName == null) {
                problems.add("跳过（作者或原曲名为空）：id=" + s.getId());
                continue;
            }
            for (String type : TYPES) {
                String oldName = getFileName(s, type);
                if (oldName == null) {
                    continue;
                }
                Path file = resolveFile(entries, rawName, artist, oldName);
                if (file == null) {
                    problems.add("跳过（文件缺失）[" + type + "]：" + rawName + " / " + oldName);
                    continue;
                }
                // 以磁盘实际文件名为准（库是镜像，可能和磁盘只差大小写）：扩展名、
                // mid 曲速、「是否已是目标名」都取磁盘上的实际名，而非库里的 oldName。
                String actualName = file.getFileName().toString();
                // 曲速要先定：newBaseName 的 mid 分支读的就是 s.getBpm()。
                // 库里的值可能是修复前从旧文件名抄来的，同目录 svp 里的 tempo 更权威，压过它。
                if ("mid".equals(type) && fillBpm(s, file)) {
                    bpmTouched.putIfAbsent(s.getId(), s);
                }
                String newName = newBaseName(s, type, artist, rawName, actualName) + suffixOf(actualName);
                if (newName.equals(actualName)) {
                    continue; // 磁盘文件已是目标名
                }
                plans.add(new Plan(s, type, file, file.getParent().resolve(newName), newName));
            }
        }

        // 2) 重名检测：两个源映射到同一目标，或目标已存在且不是本批次要改走的源。
        //    冲突的目标跳过、其余照改（每条改名相互独立，跳过冲突不破坏其余数据）。
        Set<Path> blockedTargets = new HashSet<>();
        List<String> conflicts = new ArrayList<>();
        Map<Path, Plan> byTarget = new LinkedHashMap<>();
        for (Plan p : plans) {
            Plan prev = byTarget.putIfAbsent(p.target(), p);
            if (prev != null) {
                blockedTargets.add(p.target());
                conflicts.add("重名冲突：[" + prev.type() + "] " + prev.source()
                        + " 与 [" + p.type() + "] " + p.source()
                        + " 都要改成 " + p.target().getFileName());
            }
        }
        // 目标「已存在」要显式按大小写不敏感比较：Windows 的 Files.exists 大小写不敏感，
        // 只差大小写的目标（其实就是源文件自身）也会返回 true，不能误判成「目标已存在」。
        Set<String> sourceNames = plans.stream()
                .map(p -> p.source().toString().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        for (Plan p : plans) {
            String targetName = p.target().toString().toLowerCase(Locale.ROOT);
            if (Files.exists(p.target()) && !sourceNames.contains(targetName)) {
                blockedTargets.add(p.target());
                conflicts.add("目标已存在（非本批次源）：" + p.source() + " → " + p.target());
            }
        }

        // 3) 打印
        printPlans(plans);
        printLines("跳过", problems);
        printLines("重名/冲突", conflicts);
        printLines("补曲速（随本次一起写库）", bpmTouched.values().stream()
                .map(s -> label(s) + "  bpm = " + s.getBpm().stripTrailingZeros().toPlainString())
                .collect(Collectors.toList()));

        if (justTest) {
            System.out.println("=4==================================");
            System.out.println("justTest=true，未改动任何文件与库。核对无误后置 justTest=false 重跑。");
            return;
        }
        System.out.println("=4==================================");
        System.out.println("justTest=false，开始改名与写库（" + blockedTargets.size()
                + " 个冲突目标跳过）");
        apply(plans, blockedTargets, bpmTouched);
    }

    // ---- 目录扫描与文件定位 ----

    /** 扫模板根第 1 层目录，解析出 (原曲名, 原曲作者) 归组键 */
    private List<DirEntry> scanTopDirs(Path root) {
        List<DirEntry> entries = new ArrayList<>();
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
                entries.add(new DirEntry(keyOf(title), artist == null ? null : keyOf(artist), dir));
            }
        } catch (IOException e) {
            System.out.println("😭 扫描模板目录失败：" + e);
        }
        return entries;
    }

    /** 该原曲名 + 作者可用的模板目录：作者精确命中的在前，无作者的裸名目录兜底在后；
     *  带<b>别的</b>作者的目录跳过（同名真歧义，防张冠李戴） */
    private static List<Path> dirsFor(List<DirEntry> entries, String title, String artist) {
        String titleKey = keyOf(title);
        String artistKey = StringUtils.isBlank(artist) ? null : keyOf(artist);
        List<Path> authored = new ArrayList<>();
        List<Path> plain = new ArrayList<>();
        for (DirEntry e : entries) {
            if (!titleKey.equals(e.titleKey())) {
                continue;
            }
            if (artistKey == null) {
                authored.add(e.dir());
            } else if (artistKey.equals(e.artistKey())) {
                authored.add(e.dir());
            } else if (e.artistKey() == null) {
                plain.add(e.dir());
            }
        }
        authored.addAll(plain);
        return authored;
    }

    /** 在该原曲的模板目录里按文件名找完整路径（多模板目录逐个找，第一个命中） */
    private static Path resolveFile(List<DirEntry> entries, String title, String artist,
                                    String fileName) {
        if (StringUtils.isBlank(fileName)) {
            return null;
        }
        for (Path dir : dirsFor(entries, title, artist)) {
            Path hit = findInDir(dir, fileName);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /** 递归找目录下第一个同名普通文件。Windows 文件系统大小写不敏感，故按
     *  {@code equalsIgnoreCase} 找，避免库里的文件名与磁盘只差大小写时误判「文件缺失」。 */
    private static Path findInDir(Path dir, String fileName) {
        try (var stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().equalsIgnoreCase(fileName))
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** 剥掉目录名尾部的「 模板N」后缀（多模板时目录名是「原曲 模板2」） */
    private static String baseOriginalName(String dirName) {
        return dirName == null ? "" : dirName.replaceFirst(" 模板\\d+$", "").trim();
    }

    /** 归一化键（同 {@link SongNameParser#originalKey}，空白兜底成空串） */
    private static String keyOf(String s) {
        return StringUtils.defaultString(SongNameParser.originalKey(s));
    }

    // ---- 命名 ----

    /** 按类别算出新主名（不含扩展名）。mid 的曲速取库里的 bpm —— 调用方已用
     *  {@link #fillBpm} 按「svp ＞ 库 ＞ 旧文件名」定过一遍，这里只管读结果；
     *  都没有就退回旧文件名里的数字，再没有写 {@code ？}。 */
    /**
     * 拼法只有一份，在 {@link SongOriginalNaming}；本类只负责「曲速取哪个值」这一件本类才有的事
     * （{@code mid} 的曲速：库里的 bpm 优先，库里没有才回落到旧文件名里的数字；同目录 svp 与
     * midi 内容那两级已由 {@code fillBpm} 在调用本方法<b>之前</b>写回 {@code setting.getBpm()}）。
     */
    private static String newBaseName(SongOriginalSetting setting, String type, String artist,
                                      String rawName, String oldFileName) {
        String bpm = "mid".equals(type)
                ? (setting.getBpm() != null
                        ? SongOriginalNaming.bpmText(setting.getBpm())
                        : extractTempo(oldFileName))
                : null;
        return SongOriginalNaming.build(type, rawName, artist, bpm);
    }

    /** 定下这首原曲的曲速，返回值表示「库里的值是否被改动了」。优先级：
     *  同目录 svp 工程里的 tempo ＞ <b>midi 内容里的 tempo</b> ＞ 库里的 bpm 字段 ＞ 旧 mid 文件名里的数字。
     *  <p>与运行时扫描（{@link SongTemplateService}）同一口径 —— 那边也是
     *  「svp 工程 ＞ midi 内容 ＞ midi 文件名」，列表侧算出的曲速得跟扫描一致，
     *  否则同一条原曲在改名前后会显示两个拍速。midi 内容必须排在<b>库字段之前</b>，理由同下。
     *  <p>svp 压倒库字段是刻意的：库里的值可能是修复前从旧文件名抄来的（全是 {@code [BPM=？]}），
     *  而工程文件里写着真正的拍速。先解析再算新名，所以这里必须发生在 {@code newBaseName} 之前。 */
    private static boolean fillBpm(SongOriginalSetting s, Path midFile) {
        java.math.BigDecimal bpm = bpmFromSiblingSvp(midFile.getParent());
        if (bpm == null) {
            // 大量文件名写着 [BPM=？]、或末尾数字是误命中（远走高飞2.mid → 2），
            // 而 midi 字节里多数有真正的 tempo —— 名次排在「库字段」之前由它来纠正。
            bpm = SongTemplateService.bpmFromMidi(midFile);
        }
        if (bpm == null) {
            if (s.getBpm() != null) {
                return false; // 库里已有值，且 svp / midi 内容都读不出更新的 —— 维持不动
            }
            String tempo = extractTempo(midFile.getFileName().toString());
            bpm = tempo == null ? null : new java.math.BigDecimal(tempo);
        }
        // 按数值比，不能用 equals：解析出来的值被 stripTrailingZeros 成 1.2E+2（标度 -1），
        // 库列是 decimal(10,4) 读回来是 120.0000（标度 4），数值相同却 equals=false，
        // 会把每一行都当「曲速变了」写一遍、预演报告也全是噪声。
        if (bpm == null || (s.getBpm() != null && bpm.compareTo(s.getBpm()) == 0)) {
            return false;
        }
        s.setBpm(bpm);
        return true;
    }

    /** 同目录 svp 工程里的曲速（口径与扫描一致，见 {@link SongTemplateService#bpmFromSvp}）：
     *  一个目录里可能有多个工程（多模板），取文件名排序第一个读得出 tempo 的；
     *  没有 svp / 都读不出返回 null。 */
    private static java.math.BigDecimal bpmFromSiblingSvp(Path dir) {
        if (dir == null) {
            return null;
        }
        try (var files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".svp"))
                    .sorted(Comparator.comparing(f -> f.getFileName().toString()))
                    .map(SongTemplateService::bpmFromSvp)
                    .filter(java.util.Objects::nonNull)
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** 旧文件名里的曲速（优先 {@code [BPM=NNN]} 前缀，否则末尾数字），没有返回 null */
    private static String extractTempo(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        Matcher m = TRAILING_NUMBER.matcher(base.trim());
        if (!m.find()) {
            return null;
        }
        return m.group(1) != null ? m.group(1) : m.group(2);
    }

    /** 扩展名（含点，保留原大小写）；无扩展名返回空串 */
    private static String suffixOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot);
    }

    // ---- 读 / 写 八类文件字段 ----

    private static String getFileName(SongOriginalSetting s, String type) {
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

    private static void setFileName(SongOriginalSetting s, String type, String fileName) {
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

    // ---- 打印 ----

    private static String label(SongOriginalSetting s) {
        String raw = StringUtils.trimToNull(s.getRawName());
        String artist = StringUtils.trimToNull(s.getArtist());
        return (raw == null ? String.valueOf(s.getId()) : raw)
                + (artist == null ? "" : "（" + artist + "）");
    }

    private void printPlans(List<Plan> plans) {
        System.out.println("=2==================================");
        System.out.println("待改名：" + plans.size() + " 个文件");
        for (Plan p : plans) {
            System.out.println("  " + label(p.setting()) + "  [" + p.type() + "]  "
                    + p.source().getFileName() + "  →  " + p.newName());
        }
    }

    private void printLines(String title, List<String> lines) {
        System.out.println("=3==================================");
        System.out.println(title + "：" + lines.size());
        for (String s : lines) {
            System.out.println("  " + s);
        }
    }

    // ---- 执行 ----

    /** 改名。Windows 文件系统大小写不敏感，只差大小写的目标会被 {@code Files.move} 误判成
     *  「目标已存在」而抛 {@link java.nio.file.FileAlreadyExistsException}，所以先改到
     *  临时名、再改到目标名（两步走）。 */
    private static void moveFile(Path source, Path target) throws IOException {
        if (source.toString().equalsIgnoreCase(target.toString())
                && !source.toString().equals(target.toString())) {
            Path temp = source.resolveSibling(source.getFileName().toString() + ".case-rename-tmp");
            Files.move(source, temp);
            try {
                Files.move(temp, target);
            } catch (IOException e) {
                Files.move(temp, source); // 回滚，尽量恢复原状
                throw e;
            }
        } else {
            Files.move(source, target);
        }
    }

    /** 磁盘先动：逐个 {@code moveFile}（冲突目标跳过），全改完再统一写库（库是镜像，跟着磁盘走）。
     *  {@code bpmTouched} 里的行即使没有文件要改名也要写库 —— 曲速是新解析出来的，
     *  不写回去下次扫描前它还是空的。 */
    private void apply(List<Plan> plans, Set<Path> blockedTargets,
                       Map<Long, SongOriginalSetting> bpmTouched) {
        // 脚本侧批次：本工具自有 moveFile（不过 GroupFileOps），记录在这层出
        FileOpRecorder.batch(FileOpModule.SONG, FileOpSource.SCRIPT, null,
                () -> {
                    applyInside(plans, blockedTargets, bpmTouched);
                    return null;
                });
    }

    private void applyInside(List<Plan> plans, Set<Path> blockedTargets,
                             Map<Long, SongOriginalSetting> bpmTouched) {
        Map<Long, SongOriginalSetting> toSave = new LinkedHashMap<>(bpmTouched);
        int moved = 0;
        int skipped = 0;
        for (Plan p : plans) {
            if (blockedTargets.contains(p.target())) {
                System.out.println("  ⏭ 跳过（冲突）：" + p.source().getFileName() + " → " + p.newName());
                skipped++;
                continue;
            }
            try {
                moveFile(p.source(), p.target());
                FileOpRecorder.recordPath(FileOpModule.SONG, FileOpType.MOVE,
                        FileOpLevel.OTHER, "原曲文件重命名",
                        p.source().toString(), p.target().toString());
                System.out.println("  已改名：" + p.source().getFileName() + " → "
                        + p.newName() + "  [" + p.type() + "]");
                moved++;
                SongOriginalSetting s = toSave.computeIfAbsent(p.setting().getId(), k -> p.setting());
                setFileName(s, p.type(), p.newName());
            } catch (IOException e) {
                System.out.println("😭 改名失败：" + p.source() + " → " + p.target()
                        + "（" + e.getMessage() + "）");
            }
        }
        for (SongOriginalSetting s : toSave.values()) {
            originalSettingMapper.updateById(s);
        }
        System.out.println("改名 " + moved + " 个文件，跳过 " + skipped + " 个冲突，写库 "
                + toSave.size() + " 行。");
    }
}
