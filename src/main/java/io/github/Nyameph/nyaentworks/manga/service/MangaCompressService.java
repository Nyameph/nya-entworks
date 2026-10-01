package io.github.Nyameph.nyaentworks.manga.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.file.FileNameUtil;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import io.github.Nyameph.nyaentworks.manga.config.MangaProperties;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 用 NConvert 压缩漫画目录里的图片。命令固定为需求给的那一条：
 * 长边 2560、仅缩小、转 jpg、删原文件。
 * <p>不可逆，所以调用方必须先过闸门（评分 + 规范化命名，见 {@code MangaNewService}）。
 * <p><b>失败文件单独列出而不中断整批</b>（文档 4.10）：一本漫画里混进一个坏文件
 * 是常事，为它整本不压缩不合理。因此这里逐个文件调用 NConvert，
 * 而不是把整目录一次交给它 —— 批量模式下 NConvert 的退出码只说「有失败」，
 * 说不出是哪个文件失败，而进度反馈也就没了粒度。逐个调用要付每次进程启动的
 * 开销（约 80ms/文件），所以用多线程并行压把它摊掉（见 {@link #compressFolder}）。
 * <p>gif 是动画，压成 jpg 只剩一帧，整本跳过、不计入统计；目录里若混进别的
 * 新图片格式（avif、heic 等），不压、原样保留，但会在结果里单列出来提醒用户。
 */
@Service
@RequiredArgsConstructor
public class MangaCompressService {

    private static final Logger log = LoggerFactory.getLogger(MangaCompressService.class);

    /**
     * NConvert 认得的图片后缀。与 {@code MangaNameParser} 的
     * {@code SUPPORTED_IMAGE_EXTENSIONS} 一致，多了 jpe/jfif 这类别名。
     * <p>不在这个集合里的文件（txt、url、zip 之类）原样留下不动；其中
     * 「内容确实是图片」的（avif、heic 等新格式）会在结果里单列提醒，见
     * {@link #listUnknownImages}。
     */
    static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "jpg", "jpeg", "jpe", "jfif", "png", "gif", "bmp", "webp", "tif", "tiff");

    /**
     * 图片但<b>不压缩</b>的后缀。gif 是动画，压成 jpg 只剩一帧，所以整本跳过、
     * 不计入统计。这类不算「未知图片」—— 它们是明知要跳过的。
     */
    static final Set<String> SKIPPED_IMAGE_EXTENSIONS = Set.of("gif");

    /**
     * 漫画目录里常见的非图片杂项后缀。未知图片检测（{@link #listUnknownImages}）
     * 先按扩展名把它们排除，省得逐字节读文件判断。不在这里的扩展名会读魔数复核。
     */
    static final Set<String> NON_IMAGE_EXTENSIONS = Set.of(
            "txt", "url", "zip", "rar", "7z", "pdf", "epub", "mobi", "db", "db3",
            "sqlite", "ini", "log", "json", "xml", "html", "htm", "nfo", "sfv",
            "md5", "exe", "bat", "cmd", "lnk", "torrent", "ds_store", "desktop");

    private final MangaProperties properties;

    /**
     * 一个文件的压缩结果。
     *
     * @param error 非空表示失败，内容是 NConvert 的输出或异常消息
     */
    public record FileResult(String fileName, long beforeBytes, long afterBytes, String error) {

        public boolean failed() {
            return error != null;
        }
    }

    /**
     * 一个目录的压缩结果。
     *
     * @param filesBefore 压缩前的图片数。转格式会改文件名（png → jpg），
     *                    所以「压缩后的文件数」单独数一遍才准。跳过的 gif 不计入
     * @param unknownImages 目录里「是图片但不在 {@link #IMAGE_EXTENSIONS}」的文件名
     *                      —— 新格式没被压缩、原样保留，供调用方提醒用户
     * @param skipped      NConvert 没配，这一步整个没做。<b>不是失败</b>：一个文件都没动、
     *                     归档照走（见 {@link #compressFolder}）。调用方据此区别对待 ——
     *                     尤其是<b>别把「跳过」当成「已压过」记进断点标记</b>
     * @param skipReason   跳过原因，给人看的一句话；没跳过时为 {@code null}
     */
    public record CompressResult(String folderPath, int filesBefore, int filesAfter,
                                 long bytesBefore, long bytesAfter, List<FileResult> failures,
                                 List<String> unknownImages, boolean skipped, String skipReason) {

        /**
         * NConvert 没配时的空结果：文件数、字节数都原样带出，一个失败也没有。
         * 于是「整本失败」不成立、待确认清单不成立，<b>归档流程原样往下走</b>。
         */
        public static CompressResult skipped(String folderPath, int files, long bytes, String reason) {
            return new CompressResult(folderPath, files, files, bytes, bytes,
                    List.of(), List.of(), true, reason);
        }

        /** 整本都没压成。跳过的<b>不算</b> —— 它压根没试，不是「失败」 */
        public boolean allFailed() {
            return !skipped && filesBefore > 0 && failures.size() == filesBefore;
        }

        /** 成功压缩的文件数，供「成功 X/Y」比例展示 */
        public int succeededCount() {
            return filesBefore - failures.size();
        }

        public boolean hasFailures() {
            return !failures.isEmpty();
        }
    }

    /**
     * NConvert 能不能用。压缩是唯一用得上它的功能，别处不受影响。
     *
     * <p><b>空串与「路径指向的文件不在」都算不能用</b>，两者后果完全一样（归档不压缩），
     * 只在 {@link #unavailableReason()} 里区分说法 —— 空是新机器/刚恢复出厂，
     * 文件不在是路径写错了或盘没挂上，让人一眼知道该去补哪一样。
     */
    public boolean available() {
        return availableAt(properties.getNconvert());
    }

    /**
     * 拿<b>任意一份</b>路径问「这样配能不能用」。{@link #available()} 就是拿进程里那一份来问它。
     *
     * <p>为什么要参数化：配置页顶部的提示条判的是「<b>重启后会生效的值</b>」，
     * 与进程里那一份可能不同（2026-09-25 作者要求「删掉一项之后不用重启就该看到提示」）。
     * 判定口径只有这一处，提示条拿手边那份路径来问同一段代码 —— 否则就成了第二套说法。
     */
    public static boolean availableAt(String path) {
        return StringUtils.isNotBlank(path) && new File(path).isFile();
    }

    /**
     * 没配 NConvert 时给人看的那句话。配置页的提示与任务日志用的是同一句。
     * <p>「没填」与「填了但不在」分开说：前者要人去填，后者要人去改。
     */
    public String unavailableReason() {
        return unavailableReasonFor(properties.getNconvert());
    }

    /** 给定一份路径时的那句话，见 {@link #availableAt} 说明为什么要参数化 */
    public static String unavailableReasonFor(String path) {
        if (StringUtils.isBlank(path)) {
            return "NConvert 没配（配置页「漫画 · 工具与开关」里的 NConvert 可执行文件是空的）";
        }
        return "NConvert 不在：" + path
                + "（配置页「漫画 · 工具与开关」里的 NConvert 可执行文件）";
    }

    public String nconvertPath() {
        return properties.getNconvert();
    }

    /**
     * 压缩一个漫画目录下的图片（含子目录），失败的记入
     * {@link CompressResult#failures()} 但不中断。
     * <p>多线程并行压：每个文件仍是一次 NConvert 进程启动（保留失败明细与进度粒度），
     * 但并发跑，总时长从「80ms × 文件数」降到「80ms × 文件数 / 线程数」。
     *
     * <p><b>没配 NConvert 时整个跳过、不抛异常</b>（见 {@link CompressResult#skipped}）：
     * 压缩是「省点磁盘」的优化，不是归档的前提 —— 为了它把整个归档流程判成失败，
     * 代价（一本也归不了档）远大于收益（图大一点）。所以返回一个空结果让调用方照常往下走，
     * 并在配置页挂上提示（{@code SettingsNotices}）。
     *
     * <p>目录不存在仍然抛：那是调用方的 bug，不是「环境没配好」。跳过的判定放在
     * {@link #listImages} <b>之后</b>，正是为了让这两种情况分开 —— 目录得先是真的。
     *
     * @param progress 每压完一个文件回调一次（已完成数, 总数, 当前文件名），可为 null。
     *                 内存任务的进度就靠它推，见 {@code MangaTaskService}
     */
    public CompressResult compressFolder(Path folder, ProgressListener progress) {
        if (!Files.isDirectory(folder)) {
            throw new IllegalArgumentException("目录不存在：" + folder);
        }

        List<File> images = listImages(folder);
        long bytesBefore = images.stream().mapToLong(File::length).sum();
        if (!available()) {
            log.info("没配 NConvert，跳过压缩：{}（{} 个文件原样保留）", folder, images.size());
            return CompressResult.skipped(folder.toString(), images.size(), bytesBefore,
                    unavailableReason());
        }
        List<FileResult> failures = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger done = new AtomicInteger();
        int total = images.size();

        // 线程数不超过文件数，免得一张图也开一池子线程
        int poolSize = Math.max(1, Math.min(threads(), total));
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        try {
            List<Future<?>> futures = new ArrayList<>(total);
            for (File image : images) {
                futures.add(pool.submit(() -> {
                    FileResult result = compressOne(image);
                    if (result.failed()) {
                        failures.add(result);
                        log.warn("压缩失败 {}：{}", image, result.error());
                    }
                    int n = done.incrementAndGet();
                    if (progress != null) {
                        progress.onProgress(n, total, image.getName());
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("压缩被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw new IllegalStateException("压缩失败：" + cause.getMessage(), cause);
        } finally {
            pool.shutdownNow();
        }

        List<File> after = listImages(folder);
        List<String> unknownImages = listUnknownImages(folder);
        return new CompressResult(folder.toString(), images.size(), after.size(),
                bytesBefore, after.stream().mapToLong(File::length).sum(), failures,
                unknownImages, false, null);
    }

    /** 压缩并发线程数。配置为正数就用配置，否则按 CPU 核数 */
    private int threads() {
        int configured = properties.getCompressThreads();
        if (configured > 0) {
            return configured;
        }
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    /** 压缩进度回调 */
    public interface ProgressListener {
        void onProgress(int done, int total, String currentFile);
    }

    /**
     * 压一个文件。
     * <p>{@code -D}（删原文件）在 jpg → jpg 时表现为「就地覆盖」而不是把结果删掉，
     * 已实测确认；png → jpg 时原文件被删、留下 .jpg。所以同一条命令能同时处理
     * 「已经是 jpg 只需缩小」与「换格式」两种情况。
     * <p><b>不看退出码</b>：NConvert 只要有一个文件失败就返回 127，即便加了
     * {@code -ignore_errors}；而单文件成功时返回 0。这里逐个文件调用本可以用退出码，
     * 但它对「文件没变化」这类情形的语义没有文档保证，所以以「输出里有 Error」
     * 加「目标文件在不在」为准，退出码只作为兜底。
     */
    private FileResult compressOne(File image) {
        long before = image.length();
        String ext = StringUtils.lowerCase(FileNameUtil.extName(image), Locale.ROOT);
        // 目标文件名：NConvert 把后缀换成 .jpg，同名 jpg 则就地覆盖
        File target = "jpg".equals(ext) || "jpeg".equals(ext) || "jpe".equals(ext)
                ? image
                : new File(image.getParentFile(), FileNameUtil.mainName(image) + ".jpg");

        // 关键：不把绝对路径塞进命令行，而是把工作目录切到图片所在目录、只传文件名。
        // NConvert 是 ANSI（GBK）程序，命令行里的绝对路径一旦含非 GBK 字符（韩文/日文）
        // 会被折成 '?' 找不到文件、静默退出码 0；含 '~' 会被当短文件名通配符报
        // Can't open file。目录名（[社团]、#待看、韩文卷名）走 ProcessBuilder.directory()
        // 的 Unicode CWD 通道，不进命令行，绕开这个坑。
        List<String> command = List.of(properties.getNconvert(),
                "-out", "jpeg",
                "-truecolors",              // 展开调色板/灰度/带 alpha 图为 24-bit RGB：
                                            // 8-bit 调色板 PNG 直接写 jpg 会报
                                            // "cannot be written using this format"；对已是
                                            // TrueColor 的图是 no-op（实测输出 md5 不变）
                "-q", "85",
                "-ratio",
                "-rflag", "decr",           // 仅缩小：本来就比 2560 小的不放大
                "-resize", "longest", "2560",
                "-overwrite",
                "-D",                       // 删原文件（jpg→jpg 时为就地覆盖）
                image.getName());

        NConvertOutcome run = runNConvert(command, image.getParentFile());
        if (run.error() != null) {
            return new FileResult(image.getName(), before, 0, cleanError(run.error(), image));
        }
        if (!target.isFile()) {
            return new FileResult(image.getName(), before, 0,
                    cleanError(StringUtils.defaultIfBlank(firstErrorLine(run.output()),
                            "NConvert 没有产出 " + target.getName()
                                    + "（退出码 " + run.exit() + "）"), image));
        }
        String error = cleanError(firstErrorLine(run.output()), image);
        if (error != null) {
            return new FileResult(image.getName(), before, target.length(), error);
        }
        return new FileResult(image.getName(), before, target.length(), null);
    }

    /**
     * 把任意图片转成 jpg 输出到 {@code out}，成功（文件被产出）返回 {@code true}。
     * <p>用途：webp 封面缩略图的兜底 —— {@code ImageIO} 在部分 JDK 上不认 webp，
     * 先经 NConvert 转成 jpg 再由 {@code MangaCoverService} 读回来缩放。
     * 与 {@link #compressOne} 不同，这里<b>不缩小</b>、<b>不删原文件</b>。
     */
    public boolean convertToJpeg(Path in, Path out) {
        if (!available()) {
            return false;
        }
        // 与 compressOne 同理：输入走 CWD + 相对文件名，避免非 GBK 字符被折乱。
        // 输出 -o 给绝对路径 —— 它是 Files.createTempFile 的临时文件，纯 ASCII，安全。
        Path inAbs = in.toAbsolutePath();
        List<String> command = List.of(properties.getNconvert(),
                "-out", "jpeg",
                "-truecolors",              // 同 compressOne：调色板 webp 直接写 jpg 会失败
                "-q", "85",
                "-overwrite",
                "-o", out.toString(),
                inAbs.getFileName().toString());
        NConvertOutcome run = runNConvert(command, inAbs.getParent().toFile());
        return run.error() == null && Files.isRegularFile(out);
    }

    /** NConvert 进程的一次运行。error 非空表示进程级失败（IO/超时/中断） */
    private record NConvertOutcome(String output, int exit, String error) {
    }

    /** 跑一次 NConvert。进程输出用系统默认编码读：Windows 控制台是 GBK，用 UTF-8
     *  会把日文文件名读成乱码，而这段文本要拿去给人看 */
    private NConvertOutcome runNConvert(List<String> command, File workingDirectory) {
        String output;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            if (workingDirectory != null) {
                builder.directory(workingDirectory);
            }
            Process process = builder.start();
            output = new String(process.getInputStream().readAllBytes(),
                    Charset.defaultCharset());
            int timeoutSeconds = properties.getNconvertTimeoutSeconds();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new NConvertOutcome(output, -1,
                        "超过 " + timeoutSeconds + " 秒没完成，已中止");
            }
            return new NConvertOutcome(output, process.exitValue(), null);
        } catch (IOException e) {
            return new NConvertOutcome(null, -1, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new NConvertOutcome(null, -1, "NConvert 被中断");
        }
    }

    /** NConvert 把错误写成「  Error: ...」，取第一条给人看 */
    private static String firstErrorLine(String output) {
        if (StringUtils.isBlank(output)) {
            return null;
        }
        for (String line : output.split("\\R")) {
            if (StringUtils.containsIgnoreCase(line, "error")) {
                return line.trim();
            }
        }
        return null;
    }

    /**
     * 失败消息里的完整路径剥成文件名。失败明细要按「文件夹 + 文件」展示，文件夹
     * 路径由调用方单列一次，逐条错误里再带一遍完整路径既长又重复。
     */
    private static String cleanError(String msg, File image) {
        if (msg == null) {
            return null;
        }
        return msg.replace(image.getAbsolutePath(), image.getName());
    }

    /** 目录下要压缩的图片，含子目录（有些漫画分卷放在子目录里）。跳过的 gif 不在此列 */
    private static List<File> listImages(Path folder) {
        List<File> files = FileUtil.loopFiles(folder.toFile(), file -> {
            String ext = StringUtils.lowerCase(FileNameUtil.extName(file), Locale.ROOT);
            return ext != null && IMAGE_EXTENSIONS.contains(ext)
                    && !SKIPPED_IMAGE_EXTENSIONS.contains(ext);
        });
        return new ArrayList<>(files);
    }

    /**
     * 目录里「是图片但不在 {@link #IMAGE_EXTENSIONS}」的文件名 —— 新图片格式。
     * <p>先按扩展名排除已知图片（含跳过的 gif）与已知非图片杂项，剩下的读文件头
     * 魔数复核：确实是图片才算「未知」，避免把 zip、txt 之类误报成图片。
     * <p>这些文件压缩时不碰、原样保留，但用户该知道它们没被压。
     */
    private static List<String> listUnknownImages(Path folder) {
        List<File> files = FileUtil.loopFiles(folder.toFile(), file -> {
            String ext = StringUtils.lowerCase(FileNameUtil.extName(file), Locale.ROOT);
            if (ext == null || IMAGE_EXTENSIONS.contains(ext)
                    || SKIPPED_IMAGE_EXTENSIONS.contains(ext)
                    || NON_IMAGE_EXTENSIONS.contains(ext)) {
                return false;
            }
            return looksLikeImage(file.toPath());
        });
        List<String> names = new ArrayList<>(files.size());
        for (File file : files) {
            names.add(file.getName());
        }
        return names;
    }

    /** 读文件头判断是不是图片（不认扩展名，只认魔数） */
    private static boolean looksLikeImage(Path file) {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(16);
        } catch (IOException e) {
            return false;
        }
        return isImageSignature(head);
    }

    /** 常见图片魔数：JPEG/PNG/GIF/BMP/TIFF/WebP、ISO BMFF（avif/heic/jp2 等）、JPEG XL */
    private static boolean isImageSignature(byte[] h) {
        if (h.length < 4) {
            return false;
        }
        if (h[0] == (byte) 0xFF && h[1] == (byte) 0xD8 && h[2] == (byte) 0xFF) {
            return true; // JPEG
        }
        if (h[0] == (byte) 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') {
            return true; // PNG
        }
        if (h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8') {
            return true; // GIF
        }
        if (h[0] == 'B' && h[1] == 'M') {
            return true; // BMP
        }
        if ((h[0] == 'I' && h[1] == 'I' && h[2] == 0x2A && h[3] == 0)
                || (h[0] == 'M' && h[1] == 'M' && h[2] == 0 && h[3] == 0x2A)) {
            return true; // TIFF
        }
        if (h.length >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') {
            return true; // WebP
        }
        if (h.length >= 8 && h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') {
            return true; // ISO BMFF：avif/heic/heif/jp2/jxl 容器
        }
        if (h[0] == (byte) 0xFF && h[1] == 0x0A) {
            return true; // JPEG XL 码流
        }
        return false;
    }
}
