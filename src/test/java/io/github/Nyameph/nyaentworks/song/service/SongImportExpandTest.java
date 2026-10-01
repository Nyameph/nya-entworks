package io.github.Nyameph.nyaentworks.song.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.Nyameph.nyaentworks.song.config.SongProperties;
import io.github.Nyameph.nyaentworks.song.service.SongImportService.ExpandedFile;
import io.github.Nyameph.nyaentworks.song.service.SongImportService.ExpandedGroup;
import io.github.Nyameph.nyaentworks.song.service.SongImportService.RootKind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SongImportService#expand} 的联动展开口径。{@code @TempDir} 当受管根，
 * 不连库、不碰 {@code F:\}。{@code SongGroupService} 的扫描是真扫（临时目录），
 * {@code SongSettingService} / {@code SongTagService} 在 expand 路径上用不到，传 mock。
 */
public class SongImportExpandTest {

    @TempDir
    Path root;

    private SongImportService service(Path templateDir, Path onlyOriginalDir,
                                      Path songDir, Path stagingRoot) {
        SongProperties properties = new SongProperties();
        properties.setTemplateDir(templateDir.toString());
        properties.setOnlyOriginalDir(onlyOriginalDir.toString());
        properties.setSongDir(songDir.toString());
        properties.setStagingDir(stagingRoot.toString());
        return new SongImportService(new SongGroupService(properties),
                null, null, properties);
    }

    /** 常规布局：归档根 / 模板 / 仅原曲 / 待打分根都在 root 下 */
    private SongImportService service() throws IOException {
        Path songDir = Files.createDirectories(root.resolve("成品-歌曲"));
        Path templateDir = Files.createDirectories(root.resolve("模板"));
        Path onlyOriginal = Files.createDirectories(root.resolve("仅原曲"));
        Path staging = Files.createDirectories(root.resolve("staging").resolve("歌曲"));
        return service(templateDir, onlyOriginal, songDir, staging);
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
    }

    @Test
    public void differentVersionIsAnotherGroup() throws IOException {
        Path dir = Files.createDirectories(root.resolve("下载"));
        touch(dir, "歌.mp4");
        touch(dir, "歌#2.mp4");
        List<ExpandedGroup> groups = service().expand(List.of(dir.resolve("歌.mp4").toString()));
        assertEquals(1, groups.get(0).files().size(), "歌#2 的 mainName 是 歌#2，是另一组，不进列表");
    }

    @Test
    public void unknownExtensionSkipped() throws IOException {
        Path dir = Files.createDirectories(root.resolve("下载"));
        Path song = touch(dir, "歌.mp4");
        Path stats = touch(dir, "#统计.xlsx");
        // 用户手滑把 xlsx 也选进来了：认不出扩展名的进 skipped、不进列表
        List<ExpandedGroup> groups = service().expand(List.of(song.toString(), stats.toString()));
        assertEquals(1, groups.get(0).files().size());
        assertEquals(1, groups.get(0).skipped().size());
    }

    @Test
    public void orphanLyricAnchorKept() throws IOException {
        // scanDir 会丢「只有歌词没有媒体」的组 —— 锚点必须保留，否则列表空得像点了没反应
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
    public void managedRootsBlocked() throws IOException {
        SongImportService svc = service();
        // 用认识的后缀（flac）触发拦截：svp 不在 isKnown 清单里，会在更早一步进 skipped
        Path templateFile = touch(root.resolve("模板"), "原曲名.flac");
        Path onlyOriginalFile = touch(root.resolve("仅原曲"), "原曲名.flac");

        List<ExpandedGroup> groups = svc.expand(List.of(templateFile.toString()));
        assertTrue(groups.getFirst().blockedReason() != null);
        assertTrue(groups.getFirst().files().isEmpty());

        groups = svc.expand(List.of(onlyOriginalFile.toString()));
        assertTrue(groups.getFirst().blockedReason() != null);
    }

    @Test
    public void partitionSourceAllowedWithBadge() throws IOException {
        // 2026-09-23 用户定：添加歌曲可以从已归档分区选文件（库侧防双条在后端 apply），
        // expand 不再拦，行带 PARTITION 根分类供前端画「已归档」徽标
        Path partitionDir = Files.createDirectories(root.resolve("成品-歌曲").resolve("#9超赞"));
        Path partitionFile = touch(partitionDir, "歌.mp4");
        List<ExpandedGroup> groups = service().expand(List.of(partitionFile.toString()));
        assertTrue(groups.getFirst().blockedReason() == null);
        assertEquals(1, groups.getFirst().files().size());
        assertEquals(RootKind.PARTITION, groups.getFirst().files().getFirst().rootKind());
    }

    @Test
    public void stagingIsAllowed() throws IOException {
        // 「源已在未归档文件夹里 → 只改名」这条路：staging 正常展开
        Path staging = root.resolve("staging").resolve("歌曲");
        Path song = touch(staging, "歌.mp4");
        touch(staging, "歌.lrc");
        List<ExpandedGroup> groups = service().expand(List.of(song.toString()));
        assertEquals(2, groups.get(0).files().size());
        assertEquals(RootKind.STAGING, groups.get(0).files().getFirst().rootKind());
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
        Path song = touch(dir, "歌.mp4");
        List<ExpandedGroup> groups = service().expand(List.of(
                song.toString(), song.toString(), song.getParent().resolve(song.getFileName()).toString()));
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
    public void parsedCarriedForPrefill() throws IOException {
        // 前端预填三框靠 parsed（Jackson 不下发方法，只能靠组件）—— 确认它带出去了
        Path dir = Files.createDirectories(root.resolve("下载"));
        Path song = touch(dir, "张三 - 歌（原曲）.mp4");
        List<ExpandedGroup> groups = service().expand(List.of(song.toString()));
        ExpandedFile file = groups.get(0).files().getFirst();
        assertNotNull(file.parsed());
        assertTrue(file.parsed().parsed());
        assertEquals("张三", file.parsed().artists().getFirst());
        assertEquals("歌", file.parsed().title());
        assertEquals("原曲", file.parsed().originalTitle());
    }
}
