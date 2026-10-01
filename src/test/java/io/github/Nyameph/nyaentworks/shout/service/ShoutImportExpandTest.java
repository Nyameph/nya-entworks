package io.github.Nyameph.nyaentworks.shout.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.Nyameph.nyaentworks.shout.config.ShoutProperties;
import io.github.Nyameph.nyaentworks.shout.service.ShoutImportService.ExpandedFile;
import io.github.Nyameph.nyaentworks.shout.service.ShoutImportService.ExpandedGroup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@link ShoutImportService#expand} 的联动展开口径。{@code @TempDir} 当受管根，
 * 不连库、不碰 {@code F:\}。{@code ShoutGroupService} 的扫描是真扫（临时目录），
 * {@code ShoutStoreService} 在 expand 路径上用不到，传 mock。
 */
public class ShoutImportExpandTest {

    @TempDir
    Path root;

    private ShoutProperties properties() throws IOException {
        ShoutProperties properties = new ShoutProperties();
        properties.setArchivedDir(root.resolve("成品-喊麦").toString());
        properties.setStagingDir(root.resolve("staging").toString());
        Files.createDirectories(root.resolve("成品-喊麦").resolve("#9超赞"));
        Files.createDirectories(root.resolve("staging"));
        return properties;
    }

    private ShoutImportService service() throws IOException {
        ShoutProperties properties = properties();
        return new ShoutImportService(new ShoutGroupService(properties),
                mock(ShoutStoreService.class), properties);
    }

    private static Path touch(Path dir, String name) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        Files.writeString(file, "x");
        return file;
    }

    @Test
    public void linksSameMainNameFiles() throws IOException {
        Path dir = Files.createDirectories(root.resolve("下载"));
        touch(dir, "歌.mp4");
        touch(dir, "歌.mp3");
        touch(dir, "歌.lrc");
        List<ExpandedGroup> groups = service().expand(List.of(dir.resolve("歌.mp4").toString()));
        assertEquals(1, groups.size());
        assertEquals(3, groups.get(0).files().size(), "同 mainName 的 isKnown 文件都应联动进来");
        assertTrue(groups.get(0).skipped().isEmpty());
        assertNull(groups.get(0).blockedReason());
    }

    @Test
    public void hashIsJustACharacter() throws IOException {
        // 喊麦不解析文件名：歌#2 的 mainName 就是「歌#2」，是另一组，不进「歌」的列表。
        // （歌曲那边它是版本号；这里只是恰好同样「不进列表」—— 语义不同，行为一致）
        Path dir = Files.createDirectories(root.resolve("下载"));
        touch(dir, "歌.mp4");
        touch(dir, "歌#2.mp4");
        List<ExpandedGroup> groups = service().expand(List.of(dir.resolve("歌.mp4").toString()));
        assertEquals(1, groups.get(0).files().size(), "歌#2 是另一组");
    }

    @Test
    public void unknownExtensionSkipped() throws IOException {
        Path dir = Files.createDirectories(root.resolve("下载"));
        Path shout = touch(dir, "歌.mp4");
        Path stats = touch(dir, "#统计.xlsx");
        // 用户手滑把 xlsx 也选进来了：认不出扩展名的进 skipped、不进列表
        List<ExpandedGroup> groups = service().expand(List.of(shout.toString(), stats.toString()));
        assertEquals(1, groups.get(0).files().size());
        assertEquals(1, groups.get(0).skipped().size());
    }

    @Test
    public void orphanLyricAnchorKept() throws IOException {
        // groupFiles 会丢「只有歌词没有媒体」的组 —— 锚点必须保留，
        // 否则列表空得像点了没反应（由「至少一个媒体文件」的表单闸门给人话理由）
        Path dir = Files.createDirectories(root.resolve("下载"));
        Path lyric = touch(dir, "歌.lrc");
        List<ExpandedGroup> groups = service().expand(List.of(lyric.toString()));
        assertEquals(1, groups.get(0).files().size());
        assertEquals("LYRIC", groups.get(0).files().getFirst().role());
    }

    @Test
    public void sameNameInDifferentDirsAreTwoGroups() throws IOException {
        Path dirA = Files.createDirectories(root.resolve("A"));
        Path dirB = Files.createDirectories(root.resolve("B"));
        touch(dirA, "歌.mp4");
        touch(dirB, "歌.mp4");
        List<ExpandedGroup> groups = service().expand(List.of(
                dirA.resolve("歌.mp4").toString(), dirB.resolve("歌.mp4").toString()));
        assertEquals(2, groups.size(), "联动不跨目录");
    }

    @Test
    public void stagingSourceBlocked() throws IOException {
        // 源在待打分根里：整组拒（助手本来就管着它，去列表行上改）
        Path staging = root.resolve("staging");
        Path shout = touch(staging, "歌.mp4");
        touch(staging, "歌.lrc");
        List<ExpandedGroup> groups = service().expand(List.of(shout.toString()));
        assertNotNull(groups.getFirst().blockedReason());
        assertTrue(groups.getFirst().files().isEmpty());
        assertEquals(1, groups.size());
    }

    @Test
    public void archivedPartitionSourceBlocked() throws IOException {
        // 源在已归档分区里：同样拒 —— 喊麦不给「从库里选文件再加一遍」这条路
        Path partition = root.resolve("成品-喊麦").resolve("#9超赞");
        Path shout = touch(partition, "歌.mp4");
        List<ExpandedGroup> groups = service().expand(List.of(shout.toString()));
        assertNotNull(groups.getFirst().blockedReason());
        assertTrue(groups.getFirst().blockedReason().contains("#9超赞"), groups.getFirst().blockedReason());
        assertTrue(groups.getFirst().files().isEmpty());
    }

    @Test
    public void redundantDirIsNotAManagedRoot() throws IOException {
        // 冗余文件夹不是分区形态 —— 从那里选文件不该被拦（它就是给用户捞回来的地方）
        Path redundant = Files.createDirectories(root.resolve("成品-喊麦").resolve("冗余"));
        Path shout = touch(redundant, "歌.mp4");
        List<ExpandedGroup> groups = service().expand(List.of(shout.toString()));
        assertNull(groups.getFirst().blockedReason());
        assertEquals(1, groups.getFirst().files().size());
    }

    @Test
    public void missingPathGoesToSkipped() throws IOException {
        List<ExpandedGroup> groups = service().expand(
                List.of(root.resolve("不存在").resolve("歌.mp4").toString()));
        assertEquals(1, groups.size());
        assertTrue(groups.get(0).files().isEmpty());
        assertEquals(1, groups.get(0).skipped().size());
    }

    @Test
    public void duplicateInputDeduped() throws IOException {
        Path dir = Files.createDirectories(root.resolve("下载"));
        Path shout = touch(dir, "歌.mp4");
        List<ExpandedGroup> groups = service().expand(List.of(
                shout.toString(), shout.toString(), shout.getParent().resolve(shout.getFileName()).toString()));
        assertEquals(1, groups.get(0).files().size(), "同一绝对路径重复传只出现一次");
    }

    @Test
    public void videoBeforeAudioBeforeLyric() throws IOException {
        Path dir = Files.createDirectories(root.resolve("下载"));
        touch(dir, "歌.lrc");
        touch(dir, "歌.srt");
        touch(dir, "歌.mp4");
        touch(dir, "歌.mp3");
        List<ExpandedGroup> groups = service().expand(List.of(dir.resolve("歌.mp4").toString()));
        List<ExpandedFile> files = groups.get(0).files();
        assertEquals(4, files.size());
        assertEquals("VIDEO", files.get(0).role());
        assertEquals("AUDIO", files.get(1).role());
        assertEquals("LYRIC", files.get(2).role());
        assertEquals("LYRIC", files.get(3).role());
    }

    @Test
    public void mainNameIsTheWholeStem() throws IOException {
        // 喊麦的「身份」就是去扩展名的整段，没有作者/曲名/原曲名的拆解
        Path dir = Files.createDirectories(root.resolve("下载"));
        Path shout = touch(dir, "张三 - 歌（原曲）.mp4");
        List<ExpandedGroup> groups = service().expand(List.of(shout.toString()));
        assertEquals("张三 - 歌（原曲）", groups.get(0).files().getFirst().mainName());
    }
}
