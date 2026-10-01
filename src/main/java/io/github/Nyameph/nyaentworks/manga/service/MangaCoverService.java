package io.github.Nyameph.nyaentworks.manga.service;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import io.github.Nyameph.nyaentworks.manga.config.MangaProperties;
import io.github.Nyameph.nyaentworks.manga.util.MangaCbzUtil;
import io.github.Nyameph.nyaentworks.manga.util.MangaNameParser;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * 封面图服务（文档 4.8）。
 *
 * <p><b>为什么需要它</b>：页面从 HTTP 提供，浏览器不会读 {@code file://} 下的任意
 * 本地路径，所以封面必须由后端流式返回。这是本机系统里唯一必须绕一下的地方。
 * <p>阅读页的正文图片同样由后端流式返回（{@link #imageFile}）：先按用户要求试过
 * 「直接使用本地路径」——{@link #listImages} 返回绝对路径、前端拼 {@code file:///}
 * URI——但 Chrome/Firefox 会拦 http 页里的 file:// 资源（Not allowed to load local
 * resource），所以回落回字节流。
 *
 * <p><b>为什么要缩略图</b>：列表页一屏几十张原图，一张 500KB 的话光传输就是几十兆。
 * 缩到 320px 宽后一张约 20KB，缓存在临时目录里，可随时清空重建。
 *
 * <p>缓存键是「源文件路径 + 大小 + 修改时间」的哈希：压缩过后文件变了，
 * 键跟着变，不会拿旧缩略图糊弄人。
 */
@Service
@RequiredArgsConstructor
public class MangaCoverService {

    private static final Logger log = LoggerFactory.getLogger(MangaCoverService.class);

    /** 缩略图宽度。卡片按 160px 显示，取两倍以适应高分屏 */
    private static final int THUMB_WIDTH = 320;

    private final MangaProperties properties;
    /** ImageIO 不认的格式（webp）转 jpg 用，见 {@link #makeThumbnail} */
    private final MangaCompressService compressService;

    /**
     * 取某个漫画目录的封面缩略图，没有缓存就现做一张。
     *
     * @param folderPath 漫画目录。必须落在受管的三个根目录之一下面 ——
     *                   否则这个端点就成了「读本机任意文件」的口子
     * @return 缩略图的 jpg 字节；目录里没有图片时返回 {@code null}
     */
    public byte[] thumbnail(String folderPath) {
        Path dir = requireManagedDir(folderPath);
        String coverName = MangaNameParser.getFirstImage(dir);
        if (coverName == null) {
            return null;
        }
        // cbz：封面在 zip 里，走单独的字节流分支（缓存键含 cbz 文件属性 + 条目名）
        if (MangaCbzUtil.isCbz(dir)) {
            return cbzThumbnail(dir, coverName);
        }
        Path cover = dir.resolve(coverName);
        if (!Files.isRegularFile(cover)) {
            return null;
        }

        Path cached = cachePath(cover);
        if (Files.isRegularFile(cached)) {
            try {
                return Files.readAllBytes(cached);
            } catch (IOException e) {
                // 缓存读不出来不是错误，重做一张就是
                log.debug("缩略图缓存读取失败，重做：{}", cached, e);
            }
        }

        byte[] thumb = makeThumbnail(cover);
        if (thumb == null) {
            return null;
        }
        try {
            Files.createDirectories(cached.getParent());
            Files.write(cached, thumb);
        } catch (IOException e) {
            // 写不进缓存也照样把图返回去，只是下次还得重做
            log.debug("缩略图缓存写入失败：{}", cached, e);
        }
        return thumb;
    }

    /**
     * cbz 封面缩略图：从 zip 里读出首图字节，缩成 jpg。
     * <p>缓存键含 cbz 文件的大小与修改时间 + 条目名 —— cbz 换了内容键就变。
     * <p>ImageIO 直接吃字节；不认的格式（webp）先把条目写到临时文件再交 NConvert。
     */
    private byte[] cbzThumbnail(Path cbz, String entryName) {
        Path cached = cbzCachePath(cbz, entryName);
        if (Files.isRegularFile(cached)) {
            try {
                return Files.readAllBytes(cached);
            } catch (IOException e) {
                log.debug("cbz 缩略图缓存读取失败，重做：{}", cached, e);
            }
        }
        byte[] raw = MangaCbzUtil.readEntry(cbz, entryName);
        if (raw == null) {
            return null;
        }
        byte[] thumb = makeThumbnailFromBytes(raw);
        if (thumb == null) {
            return null;
        }
        try {
            Files.createDirectories(cached.getParent());
            Files.write(cached, thumb);
        } catch (IOException e) {
            log.debug("cbz 缩略图缓存写入失败：{}", cached, e);
        }
        return thumb;
    }

    /**
     * 阅读页的正文图片：列出某漫画目录下的全部图片（含子目录分卷），返回<b>绝对路径</b>。
     * <p>顺序按自然序（数字段按数值比，{@code 2.jpg} 排在 {@code 10.jpg} 前），
     * 子目录里的图跟在根目录的图后面 —— {@code vol2} 分卷的页码正常排到末页之后。
     * <p>显示时每张走 {@link #imageFile} 字节流：前端拿到绝对路径，从目录路径里切出
     * 相对路径，再带上 folderPath 请求字节端点。
     */
    public List<String> listImages(String folderPath) {
        Path dir = requireManagedDir(folderPath);
        if (MangaCbzUtil.isCbz(dir)) {
            // cbz：把 zip 里的图片条目拼成 <cbz路径>/<条目名>，前端 relName 切掉前缀后即得条目名
            String base = dir.toAbsolutePath().normalize().toString();
            return MangaCbzUtil.listImageEntries(dir).stream()
                    .map(entry -> base + "/" + entry)
                    .toList();
        }
        try (var stream = Files.walk(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> isImage(p.getFileName().toString()))
                    .map(p -> p.toAbsolutePath().normalize().toString())
                    .sorted(NATURAL)
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("列出图片失败：" + e.getMessage(), e);
        }
    }

    /**
     * 阅读页正文图片的字节流。fileName 相对目录，可含子目录分卷（如 {@code vol2/003.jpg}）。
     * <p>这是 {@link #listImages} 返回绝对路径、前端拼 {@code file:///} URI 被浏览器拦掉后
     * 的回退：http 页里 <img> 引用 file:// 会被 Chrome/Firefox 拦，所以改由后端读盘返回。
     * <p>两个校验缺一不可：fileName 必须是图片扩展名、解析后仍落在目录内 ——
     * 否则这个端点就成了「读本机任意文件」的口子。
     *
     * @return 图片字节；文件不存在时返回 {@code null}（前端显示碎图或占位）
     */
    public byte[] imageFile(String folderPath, String fileName) {
        Path dir = requireManagedDir(folderPath);
        if (StringUtils.isBlank(fileName)) {
            throw new IllegalArgumentException("图片文件名不能为空");
        }
        if (!isImage(fileName)) {
            throw new IllegalArgumentException("不是图片文件，不给读：" + fileName);
        }
        if (MangaCbzUtil.isCbz(dir)) {
            // cbz：fileName 就是 zip 条目名，readEntry 自带 .. 越界防护，读不到返回 null
            return MangaCbzUtil.readEntry(dir, fileName);
        }
        Path file = dir.resolve(fileName).normalize();
        if (!file.startsWith(dir)) {
            throw new IllegalArgumentException("图片路径越界，不给读：" + fileName);
        }
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new IllegalStateException("读图片失败：" + e.getMessage(), e);
        }
    }

    /**
     * 阅读页图片顺序：自然序，数字段按数值比。
     * <p>与 cbz 翻页用同一份实现（{@link MangaCbzUtil#compareNatural}）—— 同一个目录
     * 打包成 cbz 前后，页序必须一致。
     */
    private static final Comparator<String> NATURAL = MangaCbzUtil::compareNatural;

    private static boolean isImage(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        return MangaCompressService.IMAGE_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase());
    }

    /** 清空缓存目录，下次访问会重建 */
    public int clearCache() {
        Path root = Paths.get(properties.getThumbCacheDir());
        if (!Files.isDirectory(root)) {
            return 0;
        }
        int[] count = {0};
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                try {
                    Files.delete(path);
                    count[0]++;
                } catch (IOException e) {
                    log.debug("删缓存失败：{}", path, e);
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("清缓存失败：" + e.getMessage(), e);
        }
        return count[0];
    }

    /**
     * 缩到 {@link #THUMB_WIDTH} 宽的 jpg。
     * <p>优先用 JDK 自带的 ImageIO —— 这里要的是「读一张、缩一张、返回字节」，
     * 起一个进程再读回文件反而更慢，且 NConvert 缺失时缩略图仍该能用。
     * <p>ImageIO 读不出来的格式（webp 在部分 JDK 上就不认）落到
     * {@link #makeThumbnailViaNConvert}：用 NConvert 先转成 jpg 再缩。
     */
    private byte[] makeThumbnail(Path cover) {
        try {
            BufferedImage source = ImageIO.read(cover.toFile());
            if (source == null) {
                // ImageIO 不认这个格式（webp 在部分 JDK 上就不认），不是错误
                return makeThumbnailViaNConvert(cover);
            }
            return resizeToJpeg(source);
        } catch (IOException e) {
            log.debug("生成缩略图失败：{}", cover, e);
            return null;
        }
    }

    /** ImageIO 读不出来的封面（webp 等）用 NConvert 转成 jpg 再缩。不可用或仍失败则放弃 */
    private byte[] makeThumbnailViaNConvert(Path cover) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("manga-cover-", ".jpg");
            if (!compressService.convertToJpeg(cover, tmp)) {
                log.debug("NConvert 转 jpg 失败，无缩略图：{}", cover);
                return null;
            }
            BufferedImage source = ImageIO.read(tmp.toFile());
            return source == null ? null : resizeToJpeg(source);
        } catch (IOException e) {
            log.debug("NConvert 转 jpg 失败：{}", cover, e);
            return null;
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException e) {
                    log.debug("清理临时封面失败：{}", tmp, e);
                }
            }
        }
    }

    /**
     * 从字节缩略（cbz 条目用）。ImageIO 不认的格式（webp）先落临时文件再交 NConvert。
     */
    private byte[] makeThumbnailFromBytes(byte[] raw) {
        try {
            BufferedImage source = ImageIO.read(new java.io.ByteArrayInputStream(raw));
            if (source != null) {
                return resizeToJpeg(source);
            }
        } catch (IOException e) {
            log.debug("从字节生成缩略图失败，转 NConvert 兜底", e);
        }
        // ImageIO 不认（webp 等）：写临时文件走 NConvert
        Path tmp = null;
        try {
            tmp = Files.createTempFile("manga-cbz-cover-", ".img");
            Files.write(tmp, raw);
            return makeThumbnailViaNConvert(tmp);
        } catch (IOException e) {
            log.debug("cbz 封面转 jpg 失败", e);
            return null;
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException e) {
                    log.debug("清理临时 cbz 封面失败：{}", tmp, e);
                }
            }
        }
    }

    /** 把读出来的图缩到 {@link #THUMB_WIDTH} 宽并写成 jpg 字节 */
    private static byte[] resizeToJpeg(BufferedImage source) throws IOException {
        int width = Math.min(THUMB_WIDTH, source.getWidth());
        int height = Math.max(1, source.getHeight() * width / source.getWidth());

        BufferedImage thumb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = thumb.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }

        var out = new java.io.ByteArrayOutputStream();
        ImageIO.write(thumb, "jpg", out);
        return out.toByteArray();
    }

    /** 缓存文件路径：键含大小与修改时间，源图变了就换一份缓存 */
    private Path cachePath(Path cover) {
        long size = 0;
        long modified = 0;
        try {
            size = Files.size(cover);
            modified = Files.getLastModifiedTime(cover).toMillis();
        } catch (IOException e) {
            log.debug("读文件属性失败：{}", cover, e);
        }
        String key = cover.toAbsolutePath() + "|" + size + "|" + modified;
        String hash = sha256(key);
        // 分两级子目录，避免一个目录下堆上万个文件
        return Paths.get(properties.getThumbCacheDir())
                .resolve(hash.substring(0, 2))
                .resolve(hash + ".jpg");
    }

    /** cbz 封面缓存路径：键含 cbz 文件大小、修改时间与条目名，内容变了就换一份 */
    private Path cbzCachePath(Path cbz, String entryName) {
        long size = 0;
        long modified = 0;
        try {
            size = Files.size(cbz);
            modified = Files.getLastModifiedTime(cbz).toMillis();
        } catch (IOException e) {
            log.debug("读 cbz 文件属性失败：{}", cbz, e);
        }
        String key = cbz.toAbsolutePath() + "|" + size + "|" + modified + "|" + entryName;
        String hash = sha256(key);
        return Paths.get(properties.getThumbCacheDir())
                .resolve(hash.substring(0, 2))
                .resolve(hash + ".jpg");
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 路径必须落在受管的根目录下。
     * <p>这个端点拿路径当参数，不校验就等于开放「读任意本地文件」——
     * 虽然是单人本机系统，但页面在 50721 上监听，同机的别的程序也能请求它。
     */
    private Path requireManagedDir(String folderPath) {
        if (StringUtils.isBlank(folderPath)) {
            throw new IllegalArgumentException("目录路径不能为空");
        }
        Path target = Paths.get(folderPath).toAbsolutePath().normalize();
        for (String root : new String[]{properties.getNewDir(), properties.getCollectionDir(),
                properties.getUnarchivedDir()}) {
            if (StringUtils.isNotBlank(root)
                    && target.startsWith(Paths.get(root).toAbsolutePath().normalize())) {
                return target;
            }
        }
        // 四个评分分区都在归档根下面，比归档根本身就够，不必逐分区比
        String archiveRoot = properties.getArchiveDir();
        if (StringUtils.isNotBlank(archiveRoot)
                && target.startsWith(Paths.get(archiveRoot).toAbsolutePath().normalize())) {
            return target;
        }
        throw new IllegalArgumentException("不在受管的漫画目录下，不给读：" + folderPath);
    }
}
