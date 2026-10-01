package io.github.Nyameph.nyaentworks.common.file;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.Nyameph.nyaentworks.common.media.MediaExtensions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 整组文件的原子搬动 / 删除原语（实现说明 6.5）。
 *
 * <p><b>整组是这个项目原子性要求最强的地方。</b>一组有 2-3 个文件（视频 / 音频 / 歌词），
 * 漏搬一个，这组就裂成两半散在两个评分分区里，而且下次扫描会把它认成
 * <b>两组各自不完整的</b>—— 症状是「列表里多出一首没有音频的歌」，很难联想到是
 * 上次改评分漏了文件。
 *
 * <p>所以每个操作都是：
 * <ol>
 *   <li><b>plan 只读</b>，列出组内每个文件的新旧路径与拦截原因，供页面审查（文档 4.9）；
 *   <li><b>apply 先全部检查、再全部移动</b>，任一条被拦就整组不动
 *       （同「有一条 blockedReason 就整批不执行」的立场）；
 *   <li>中途失败<b>回滚已移动的部分</b>。这里没有事务可用（是文件操作），
 *       只能记下已移动的 {@code (from, to)} 逐个 move 回去。
 * </ol>
 *
 * <p><b>为什么放在 {@code common}</b>：填词歌曲与喊麦的搬动逻辑逐行同构，而这段是
 * 「会真丢文件」的地方 —— 两份实现的下场是其中一份少了一次回滚。这里只管文件，
 * 一概不碰库：库那侧的设置值由各模块的 service 在磁盘成功<b>之后</b>跟着搬
 * （磁盘先动、库后动）。
 *
 * <p>本类不含任何「主名 / 版本号」语义 —— 版本号是歌曲独有的（{@code #2} 是版本号，
 * 喊麦的 {@code #} 只是普通字符），拼目标主名由调用方负责。
 */
public final class GroupFileOps {

    private static final Logger log = LoggerFactory.getLogger(GroupFileOps.class);

    private GroupFileOps() {
    }

    /**
     * 一个文件的搬动计划。
     *
     * @param fileName      现文件名
     * @param fromPath      现路径
     * @param toFileName    新文件名（改评分时不变，改名时变）
     * @param toPath        新路径
     * @param blockedReason 拦截原因；{@code null} 表示可执行
     */
    public record FileMove(String fileName, String fromPath,
                           String toFileName, String toPath, String blockedReason) {
    }

    /**
     * 整组操作的预演。
     *
     * @param action        SCORE / RENAME / EDIT / DELETE，供页面组织措辞
     * @param mainName      组的主名
     * @param toPartition   目标分区目录名；删除时为 {@code null}
     * @param toMainName    目标主名；改评分时与 {@code mainName} 相同
     * @param moves         组内每个文件的搬动计划
     * @param blockedReason 整组级别的拦截原因（如目标分区不存在）；{@code null} 表示可执行
     */
    public record GroupPlan(String action, String mainName, String toPartition,
                            String toMainName, List<FileMove> moves, String blockedReason) {

        /** 有一条拦不住就整批不执行 —— 漏搬一个文件比不搬更糟 */
        public boolean blocked() {
            return blockedReason != null || moves.stream().anyMatch(m -> m.blockedReason() != null);
        }

        /** 页面上一句话说明为什么不能执行 */
        public String firstBlockedReason() {
            if (blockedReason != null) {
                return blockedReason;
            }
            return moves.stream().map(FileMove::blockedReason)
                    .filter(Objects::nonNull).findFirst().orElse(null);
        }
    }

    /** 组内一个文件的角色：组 = 视频 + 音频 + 歌词（0..n），没有第四种 */
    public enum GroupFileKind {
        VIDEO, AUDIO, LYRIC
    }

    /** 组内一个文件：名字 + 角色 */
    public record GroupEntry(String fileName, GroupFileKind kind) {
    }

    /**
     * 组内文件的<b>有序</b>清单：video → audio → 歌词（歌词已是自然序）。
     *
     * <p><b>这个顺序是契约</b>：镜像表 {@code song_file} / {@code shout_file} 的
     * {@code sort_order} 按它编，删除 / 改评分 / 改名也按它逐个处理。歌曲与喊麦原先各手抄
     * 过一遍，改了一边忘另一边就会让同一组在两个模块里的 sort_order 对不上。
     */
    public static List<GroupEntry> entries(String videoFile, String audioFile,
                                           List<String> lyricFiles) {
        List<GroupEntry> entries = new ArrayList<>();
        if (videoFile != null) {
            entries.add(new GroupEntry(videoFile, GroupFileKind.VIDEO));
        }
        if (audioFile != null) {
            entries.add(new GroupEntry(audioFile, GroupFileKind.AUDIO));
        }
        for (String lyric : lyricFiles) {
            entries.add(new GroupEntry(lyric, GroupFileKind.LYRIC));
        }
        return entries;
    }

    /** 只要文件名的版本，顺序同 {@link #entries} */
    public static List<String> allFiles(String videoFile, String audioFile,
                                        List<String> lyricFiles) {
        List<String> files = new ArrayList<>();
        for (GroupEntry entry : entries(videoFile, audioFile, lyricFiles)) {
            files.add(entry.fileName());
        }
        return files;
    }

    /**
     * 组内的歌词文件名必须是扫出来的那几个之一。
     * <p>比对扫描结果比 {@code normalize() + startsWith} 更严 —— 连组外的同目录文件都读不到。
     *
     * @param lyricFiles 扫描出来的歌词文件（{@code group.lyricFiles()}）
     * @param fileName   请求的文件名；空则取第一个
     */
    public static String requireLyricFile(List<String> lyricFiles, String fileName) {
        if (StringUtils.isBlank(fileName)) {
            // 不指定就取第一个：一组通常只有一个歌词文件
            if (lyricFiles.isEmpty()) {
                throw new IllegalStateException("这一组没有歌词文件");
            }
            return lyricFiles.getFirst();
        }
        for (String candidate : lyricFiles) {
            if (candidate.equals(fileName)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("「" + fileName + "」不是这一组的歌词文件");
    }

    /**
     * 删除的预演：整组的文件列成「只删不移」的计划。
     * <p>删除会<b>进回收站</b>（2026-09-30 起，见 {@link RecycleBin}），但「文件已经不在了」
     * 照样列出来并打上拦截原因 —— 先让人看清这次到底动哪几个，再动手。
     */
    public static List<FileMove> planDeletes(Path dir, List<String> fileNames) {
        List<FileMove> moves = new ArrayList<>();
        for (String fileName : fileNames) {
            Path from = dir.resolve(fileName);
            moves.add(new FileMove(fileName, from.toString(), null, null,
                    Files.isRegularFile(from) ? null : "文件不在了：" + from));
        }
        return moves;
    }

    /**
     * 算组内每个文件的新旧路径。扩展名不变，只换目录与主名。
     *
     * @param fileNames      组内所有文件名（来自扫描结果，不再去磁盘列一遍）
     * @param sourceDir      组当前所在目录
     * @param targetDir      目标目录（改评分时换分区、改名时与 {@code sourceDir} 相同）
     * @param targetMainName 目标主名
     */
    public static List<FileMove> planMoves(List<String> fileNames, Path sourceDir,
                                           Path targetDir, String targetMainName) {
        List<FileMove> moves = new ArrayList<>();
        for (String fileName : fileNames) {
            String extension = MediaExtensions.extension(fileName);
            // 扩展名保留原文（2026-09-23 库里实测 song_file 81 行 / shout_file 2 行是大写 .MP3），
            // 改名不该顺手把它们改小写：那是另一件事，混在一起做会让「改名清单」里出现看不懂的变化。
            // 借 extension() 的**长度**切原文（而不是直接拼它）就是为这个 —— 它是小写化的，拼上去会改掉大小写。
            String rawExtension = fileName.substring(fileName.length() - extension.length());
            String toFileName = targetMainName + "." + rawExtension;
            Path from = sourceDir.resolve(fileName);
            Path to = targetDir.resolve(toFileName);

            String blocked = null;
            if (!Files.isRegularFile(from)) {
                blocked = "源文件不在了：" + from;
            } else if (Files.exists(to)) {
                // 不覆盖（文档 8.4）：覆盖会静默毁掉目标那一份
                blocked = "目标已存在同名文件：" + to;
            }
            moves.add(new FileMove(fileName, from.toString(), toFileName, to.toString(), blocked));
        }
        return moves;
    }

    /**
     * 全部移动。<b>调用前必须已经检查过</b>（{@link #requireExecutable}）——
     * 这个方法只负责搬和回滚。
     *
     * @return 实际搬动的文件数
     */
    public static int moveAll(List<FileMove> moves) {
        List<FileMove> done = new ArrayList<>();
        for (FileMove move : moves) {
            Path from = Path.of(move.fromPath());
            Path to = Path.of(move.toPath());
            try {
                Files.createDirectories(to.getParent());
                Files.move(from, to);
                done.add(move);
            } catch (IOException e) {
                // 中途失败：把已搬的搬回去。留一个裂开的组比报错更糟 ——
                // 下次扫描会把它认成两首不完整的歌
                List<String> rollbackFailed = rollback(done);
                String message = "搬第 " + (done.size() + 1) + " 个文件「" + move.fileName()
                        + "」失败：" + e.getMessage();
                if (rollbackFailed.isEmpty()) {
                    throw new IllegalStateException(message + "。已搬的 " + done.size()
                            + " 个文件都搬回原处了，这一组没有变化");
                }
                // 回滚也失败：这一组现在是裂开的，只能报明细让人手工收拾
                throw new IllegalStateException(message + "。回滚时这些文件搬不回去，"
                        + "请手工把它们移回原分区，否则这一组会被认成两首不完整的歌："
                        + String.join("；", rollbackFailed));
            }
        }
        return done.size();
    }

    /**
     * 删除组内所有文件。<b>送进 Windows 回收站</b>（2026-09-30 起；原先是真的删掉），
     * 删错了可以去资源管理器里右键「还原」，落点还是原路径。仍然只受理已经预演过、
     * 且预演里没有拦截的组。
     * <p>删到一半失败时不回滚（已经进回收站的那些不捞回来），把没进去的列出来让人知道现状。
     * <p>送不进回收站就<b>报错</b>，不退而求其次去永久删除（用户 2026-09-30 裁决）。
     *
     * @return 实际删掉的文件数（调用前就已经不在的也算 —— 那正是删除想要的结果）
     */
    public static int deleteAll(List<FileMove> moves) {
        List<String> paths = new ArrayList<>();
        Map<String, String> nameOfPath = new LinkedHashMap<>();
        for (FileMove move : moves) {
            paths.add(move.fromPath());
            nameOfPath.put(move.fromPath(), move.fileName());
        }
        Map<String, String> failures = RecycleBin.recycleAll(paths);
        if (!failures.isEmpty()) {
            List<String> failed = new ArrayList<>();
            for (Map.Entry<String, String> entry : failures.entrySet()) {
                failed.add(nameOfPath.getOrDefault(entry.getKey(), entry.getKey())
                        + "（" + entry.getValue() + "）");
            }
            throw new IllegalStateException("已删 " + (paths.size() - failures.size())
                    + " 个文件，这些没能移入回收站：" + String.join("、", failed)
                    + "。多半是文件正被播放器占着，关掉播放浮层再试");
        }
        return paths.size();
    }

    /** @return 回滚失败的明细，空表示全滚回去了 */
    private static List<String> rollback(List<FileMove> done) {
        List<String> failed = new ArrayList<>();
        for (FileMove move : done) {
            try {
                Files.move(Path.of(move.toPath()), Path.of(move.fromPath()));
            } catch (IOException e) {
                log.error("回滚失败：{} → {}", move.toPath(), move.fromPath(), e);
                failed.add(move.toPath() + " → " + move.fromPath());
            }
        }
        return failed;
    }

    /** 执行前的最后一道闸门：有拦截或有空清单都不执行 */
    public static void requireExecutable(GroupPlan plan) {
        if (plan.blocked()) {
            throw new IllegalStateException(plan.firstBlockedReason());
        }
        if (plan.moves().isEmpty()) {
            throw new IllegalStateException("没有要搬的文件");
        }
    }

    /**
     * 新主名的校验。
     * <p>Windows 文件名的非法字符一律拒绝 —— 拼出来的名字创建不了文件，
     * 而错误信息（{@code InvalidPathException}）看不出是哪个字符的问题。
     * {@code |} 也拒绝，它是合并键 / 分桶键的分隔符（主名是第三段，带 {@code |} 会拼出歧义）。
     *
     * @return trim 后的主名
     */
    public static String requireMainName(String mainName) {
        String name = StringUtils.trimToNull(mainName);
        if (name == null) {
            throw new IllegalArgumentException("新名字不能为空");
        }
        for (char illegal : new char[]{'\\', '/', ':', '*', '?', '"', '<', '>', '|'}) {
            if (name.indexOf(illegal) >= 0) {
                throw new IllegalArgumentException("名字里不能有「" + illegal + "」：Windows 文件名不允许");
            }
        }
        if (name.endsWith(".") || name.endsWith(" ")) {
            // Windows 会静默去掉结尾的点与空格，于是磁盘上的名字与库里的不一致
            throw new IllegalArgumentException("名字不能以点或空格结尾：Windows 会静默去掉它们");
        }
        return name;
    }
}
