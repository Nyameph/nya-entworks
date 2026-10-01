package io.github.Nyameph.nyaentworks.manga;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.github.Nyameph.nyaentworks.manga.util.MangaCbzUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MangaCbzUtil} 的纯函数验证：现搭一个临时 cbz（zip），不连库、不依赖本机漫画目录。
 *
 * <p>覆盖的点：识别 cbz、去后缀、单元存在判定、首图/列图的自然序（{@code 2 < 10}）、
 * 按条目名读字节、以及 {@code ..} 越界防护——最后一条是安全边界，漏了这个端点就成了
 * 「按名读任意 zip 内文件」的口子。
 */
public class MangaCbzUtilTest {

    private Path dir;

    @BeforeEach
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("cbz-test-");
    }

    @AfterEach
    public void tearDown() throws IOException {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // 清理失败不影响断言，临时目录早晚被系统回收
                        }
                    });
        }
    }

    /** 造一个 cbz：entries 是「条目名 → 字节」的交替列表，按给定顺序写入（不重排） */
    private Path makeCbz(String fileName, Object... entries) throws IOException {
        Path cbz = dir.resolve(fileName);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(cbz))) {
            for (int i = 0; i < entries.length; i += 2) {
                zip.putNextEntry(new ZipEntry((String) entries[i]));
                byte[] data = (byte[]) entries[i + 1];
                if (data != null) {
                    zip.write(data);
                }
                zip.closeEntry();
            }
        }
        return cbz;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void isCbz_recognizesExtensionIgnoreCase() throws IOException {
        Path cbz = makeCbz("book.cbz", "1.jpg", bytes("a"));
        Path upper = makeCbz("BOOK2.CBZ", "1.jpg", bytes("a"));
        assertTrue(MangaCbzUtil.isCbz(cbz));
        assertTrue(MangaCbzUtil.isCbz(upper));
        // 目录不是 cbz；不存在的路径也不是
        assertFalse(MangaCbzUtil.isCbz(dir));
        assertFalse(MangaCbzUtil.isCbz(dir.resolve("nope.cbz")));
    }

    @Test
    public void stripCbz_removesSuffixIgnoreCase() {
        assertEquals("[社团] 标题", MangaCbzUtil.stripCbz("[社团] 标题.cbz"));
        assertEquals("[社团] 标题", MangaCbzUtil.stripCbz("[社团] 标题.CBZ"));
        assertEquals("无后缀", MangaCbzUtil.stripCbz("无后缀"));
    }

    @Test
    public void unitExists_dirOrCbz() throws IOException {
        Path cbz = makeCbz("book.cbz", "1.jpg", bytes("a"));
        assertTrue(MangaCbzUtil.unitExists(dir));
        assertTrue(MangaCbzUtil.unitExists(cbz));
        assertFalse(MangaCbzUtil.unitExists(dir.resolve("missing")));
    }

    @Test
    public void listImageEntries_naturalOrderAndImageOnly() throws IOException {
        // 乱序写入 + 混入非图片条目与目录条目，期望：只留图片、按自然序（2 在 10 前）
        Path cbz = makeCbz("book.cbz",
                "10.jpg", bytes("j10"),
                "2.jpg", bytes("j2"),
                "1.jpg", bytes("j1"),
                "info.txt", bytes("meta"),
                "sub/", null);
        List<String> imgs = MangaCbzUtil.listImageEntries(cbz);
        assertEquals(List.of("1.jpg", "2.jpg", "10.jpg"), imgs);
        assertEquals("1.jpg", MangaCbzUtil.firstImageEntry(cbz));
        assertEquals(3, MangaCbzUtil.imageEntryCount(cbz));
        // entryCount 含非图片文件条目，但不含目录条目：1/2/10.jpg + info.txt = 4
        assertEquals(4, MangaCbzUtil.entryCount(cbz));
    }

    @Test
    public void readEntry_returnsBytes() throws IOException {
        Path cbz = makeCbz("book.cbz", "001.jpg", bytes("hello"));
        assertArrayEquals(bytes("hello"), MangaCbzUtil.readEntry(cbz, "001.jpg"));
    }

    @Test
    public void readEntry_rejectsTraversalAndMissing() throws IOException {
        Path cbz = makeCbz("book.cbz", "1.jpg", bytes("x"));
        // .. 越界一律挡掉（返回 null，不抛）
        assertNull(MangaCbzUtil.readEntry(cbz, "../secret.txt"));
        // 不存在的条目、空条目名
        assertNull(MangaCbzUtil.readEntry(cbz, "nope.jpg"));
        assertNull(MangaCbzUtil.readEntry(cbz, ""));
    }

    @Test
    public void emptyOrBrokenCbz_treatedAsNoImages() throws IOException {
        Path empty = makeCbz("empty.cbz", "readme.txt", bytes("no images"));
        assertTrue(MangaCbzUtil.listImageEntries(empty).isEmpty());
        assertNull(MangaCbzUtil.firstImageEntry(empty));
        assertEquals(0, MangaCbzUtil.imageEntryCount(empty));

        // 不是合法 zip：读不开当作没有图片，不抛
        Path broken = dir.resolve("broken.cbz");
        Files.write(broken, bytes("not a zip"));
        assertTrue(MangaCbzUtil.listImageEntries(broken).isEmpty());
        assertNull(MangaCbzUtil.readEntry(broken, "1.jpg"));
    }

    @Test
    public void packImages_roundTrip_naturalOrderSubdirAndSkipNonImage() throws IOException {
        Path src = dir.resolve("manga");
        Files.createDirectories(src.resolve("vol2"));
        Files.write(src.resolve("10.jpg"), bytes("j10"));
        Files.write(src.resolve("2.jpg"), bytes("j2"));
        Files.write(src.resolve("1.jpg"), bytes("j1"));
        Files.write(src.resolve("info.txt"), bytes("meta"));
        Files.write(src.resolve("vol2").resolve("003.png"), bytes("p3"));

        Path out = dir.resolve("manga.cbz");
        int count = MangaCbzUtil.packImages(src, out);

        assertEquals(4, count);
        // 只留图片、非图片跳过、子目录条目保留、自然序（2 在 10 前）
        assertEquals(List.of("1.jpg", "2.jpg", "10.jpg", "vol2/003.png"),
                MangaCbzUtil.listImageEntries(out));
        // 读回字节一致（STORED 不二次压缩，逐字节还原）
        assertArrayEquals(bytes("j10"), MangaCbzUtil.readEntry(out, "10.jpg"));
        assertArrayEquals(bytes("p3"), MangaCbzUtil.readEntry(out, "vol2/003.png"));
    }

    @Test
    public void packImages_noImages_returnsZero() throws IOException {
        Path src = dir.resolve("empty");
        Files.createDirectories(src);
        Files.write(src.resolve("readme.txt"), bytes("no images"));

        Path out = dir.resolve("empty.cbz");
        assertEquals(0, MangaCbzUtil.packImages(src, out));
        assertTrue(MangaCbzUtil.listImageEntries(out).isEmpty());
    }

    /**
     * 直接钉住自然序比较本身 —— 阅读页的正文图排序（{@code MangaCoverService}）与 cbz 翻页
     * 共用这一份实现，同一个目录打包前后页序必须一致，所以两边的口径都在这里定死。
     */
    @Test
    public void compareNatural_byNumericSegmentThenChars() {
        // 数字段按数值比，不是按字符串
        assertTrue(MangaCbzUtil.compareNatural("2.jpg", "10.jpg") < 0);
        assertTrue(MangaCbzUtil.compareNatural("10.jpg", "2.jpg") > 0);
        assertEquals(0, MangaCbzUtil.compareNatural("7.jpg", "7.jpg"));

        // 去前导零比数值，数值相同再比原串（002 与 2 分得出先后，且稳定）
        assertTrue(MangaCbzUtil.compareNatural("2.jpg", "002.jpg") > 0);
        assertTrue(MangaCbzUtil.compareNatural("002.jpg", "0002.jpg") > 0);

        // 非数字段按字符比
        assertTrue(MangaCbzUtil.compareNatural("a.jpg", "b.jpg") < 0);
        assertTrue(MangaCbzUtil.compareNatural("vol2/3.jpg", "vol10/1.jpg") < 0);

        // 前缀相同则短的在前
        assertTrue(MangaCbzUtil.compareNatural("1.jpg", "1.jpg.bak") < 0);
    }
}
