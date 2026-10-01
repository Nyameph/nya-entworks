package io.github.Nyameph.nyaentworks.manga.util;

import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 把 {@code .cbz}（图片打包成的 zip）当作漫画单元来读，纯静态、不依赖 Spring。
 *
 * <p><b>为什么单独一个类</b>：整个 manga 模块的公理是「漫画单元 = 含图片的目录」，
 * cbz 是「单个文件、图片在 zip 里」。与其在各服务里散落 {@code if (cbz)}，不如把
 * 「zip 里挑首图 / 列图 / 读某条目 / 数图」这几件事收在这里，上层只在目录分支旁
 * 加一个 cbz 分支即可。图片判定的扩展名与 {@link MangaNameParser} 的
 * {@code SUPPORTED_IMAGE_EXTENSIONS} 一致。
 *
 * <p>条目顺序用 {@link #compareNatural}（数字段按数值比），与阅读页正文图的排序同源
 * —— 所以 cbz 翻页顺序与目录漫画一致。
 */
public final class MangaCbzUtil {

    private MangaCbzUtil() {
    }

    /** 与 {@link MangaNameParser} 的 SUPPORTED_IMAGE_EXTENSIONS 对齐 */
    private static final List<String> IMAGE_EXTENSIONS =
            List.of("jpg", "jpeg", "png", "gif", "bmp", "webp");

    /** 是常规文件且以 {@code .cbz} 结尾（忽略大小写） */
    public static boolean isCbz(Path path) {
        return path != null && Files.isRegularFile(path)
                && StringUtils.endsWithIgnoreCase(path.getFileName().toString(), ".cbz");
    }

    /** 忽略大小写去掉末尾 {@code .cbz}；不是该后缀则原样返回 */
    public static String stripCbz(String name) {
        if (name != null && StringUtils.endsWithIgnoreCase(name, ".cbz")) {
            return name.substring(0, name.length() - ".cbz".length());
        }
        return name;
    }

    /** 目录或 cbz 文件都算「漫画单元存在」，替代散在各处的 {@code Files.isDirectory} 判定 */
    public static boolean unitExists(Path path) {
        return path != null && (Files.isDirectory(path) || isCbz(path));
    }

    /** cbz 里按自然序的首个图片条目名；无图（或读不开）返回 {@code null} */
    public static String firstImageEntry(Path cbz) {
        List<String> entries = listImageEntries(cbz);
        return entries.isEmpty() ? null : entries.getFirst();
    }

    /** cbz 里全部图片条目名，自然序（数字段按数值比，与阅读页正文图排序同源） */
    public static List<String> listImageEntries(Path cbz) {
        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(cbz.toFile())) {
            var it = zip.entries();
            while (it.hasMoreElements()) {
                ZipEntry entry = it.nextElement();
                if (!entry.isDirectory() && isImage(entry.getName())) {
                    names.add(entry.getName());
                }
            }
        } catch (IOException e) {
            // 读不开（损坏/占用）当作没有图片，不抛 —— 与目录里没图同样处理
            return List.of();
        }
        names.sort(MangaCbzUtil::compareNatural);
        return names;
    }

    /**
     * 读 cbz 里某个条目的字节。条目名先做 {@code ..}/越界校验 —— 这个方法的入参最终
     * 来自 HTTP，等同「按名读 zip 内文件」，不校验就是个读任意条目的口子。
     *
     * @return 条目字节；条目不存在或非法时返回 {@code null}
     */
    public static byte[] readEntry(Path cbz, String entryName) {
        if (StringUtils.isBlank(entryName) || entryName.contains("..")) {
            return null;
        }
        try (ZipFile zip = new ZipFile(cbz.toFile())) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null || entry.isDirectory()) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        } catch (IOException e) {
            return null;
        }
    }

    /** cbz 里的图片条目数（不含目录条目） */
    public static int imageEntryCount(Path cbz) {
        return listImageEntries(cbz).size();
    }

    /** cbz 里的全部条目数（含非图片），卡片上「文件数」用 */
    public static int entryCount(Path cbz) {
        int count = 0;
        try (ZipFile zip = new ZipFile(cbz.toFile())) {
            var it = zip.entries();
            while (it.hasMoreElements()) {
                if (!it.nextElement().isDirectory()) {
                    count++;
                }
            }
        } catch (IOException e) {
            return 0;
        }
        return count;
    }

    /**
     * 把一个图片目录打包成 cbz：递归收集所有图片（{@link #isImage} 口径，与读取端一致），
     * 按「相对路径」自然序写入 zip（{@code vol2/003.jpg} 作为条目 {@code vol2/003.jpg}），
     * 子目录结构原样保留。
     *
     * <p>zip 用 {@link ZipEntry#STORED} 不二次压缩 —— 图片本身已压缩，deflate 对整个库
     * 打包是纯浪费时间；STORED 的代价是每个条目要先算好 CRC32 与 size/compressedSize。
     *
     * @return 写入的图片条目数（0 表示目录里没有图片，此时产物是个空 zip）
     */
    public static int packImages(Path dir, Path outCbz) throws IOException {
        List<Path> images = listImageFiles(dir);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(outCbz))) {
            for (Path image : images) {
                byte[] data = Files.readAllBytes(image);
                ZipEntry entry = new ZipEntry(entryNameOf(dir, image));
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(data.length);
                entry.setCompressedSize(data.length);
                CRC32 crc = new CRC32();
                crc.update(data);
                entry.setCrc(crc.getValue());
                zip.putNextEntry(entry);
                zip.write(data);
                zip.closeEntry();
            }
        }
        return images.size();
    }

    /** 递归列出目录里的图片文件，按相对路径自然序排序（与 {@link #listImageEntries} 同源） */
    private static List<Path> listImageFiles(Path dir) throws IOException {
        List<Path> images = new ArrayList<>();
        try (var walk = Files.walk(dir)) {
            walk.filter(p -> Files.isRegularFile(p))
                    .filter(p -> isImage(p.getFileName().toString()))
                    .forEach(images::add);
        }
        images.sort((a, b) -> compareNatural(entryNameOf(dir, a), entryNameOf(dir, b)));
        return images;
    }

    private static String entryNameOf(Path dir, Path image) {
        return dir.relativize(image).toString().replace('\\', '/');
    }

    private static boolean isImage(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        return IMAGE_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /**
     * 自然序比较：数字段按数值比（{@code 2.jpg} 排在 {@code 10.jpg} 前），其余按字符。
     * 数字段去前导零比数值，数值相同再比原串（区分 {@code 002} 与 {@code 2}）。
     *
     * <p><b>唯一实现</b>：目录漫画的正文图排序（{@code MangaCoverService}）也用它 ——
     * 同一个目录打包成 cbz 前后，页序必须一致。
     */
    public static int compareNatural(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int ni = i, nj = j;
                while (ni < a.length() && Character.isDigit(a.charAt(ni))) {
                    ni++;
                }
                while (nj < b.length() && Character.isDigit(b.charAt(nj))) {
                    nj++;
                }
                String da = a.substring(i, ni), db = b.substring(j, nj);
                String na = da.replaceFirst("^0+", ""), nb = db.replaceFirst("^0+", "");
                int cmp = Integer.compare(na.length(), nb.length());
                if (cmp == 0) {
                    cmp = na.compareTo(nb);
                }
                if (cmp == 0) {
                    cmp = da.compareTo(db);
                }
                if (cmp != 0) {
                    return cmp;
                }
                i = ni;
                j = nj;
            } else {
                int cmp = Character.compare(ca, cb);
                if (cmp != 0) {
                    return cmp;
                }
                i++;
                j++;
            }
        }
        return Integer.compare(a.length(), b.length());
    }
}
