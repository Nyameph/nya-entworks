package io.github.Nyameph.nyaentworks.common.file;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.github.Nyameph.nyaentworks.common.file.GroupFileOps.FileMove;
import io.github.Nyameph.nyaentworks.common.file.GroupFileOps.GroupFileKind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 组层面共用原语的单测：{@code entries} / {@code allFiles} / {@code requireLyricFile} /
 * {@code planDeletes} / {@code planMoves}。
 *
 * <p>这几条原先在歌曲与喊麦各有一份手抄实现，合并后成了**唯一**的一份 —— 顺序与错误文案
 * 都被两个模块依赖，所以在这里钉死。
 */
public class GroupFileOpsTest {

    private Path dir;

    @BeforeEach
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("group-file-ops-test");
    }

    @AfterEach
    public void tearDown() throws IOException {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 临时目录清理失败不影响断言
                }
            });
        }
    }

    // ==================== entries / allFiles ====================

    @Test
    public void entries_orderIsVideoAudioThenLyrics() {
        List<GroupFileOps.GroupEntry> entries =
                GroupFileOps.entries("甲.mp4", "甲.mp3", List.of("甲.lrc", "甲.srt"));

        assertEquals(List.of("甲.mp4", "甲.mp3", "甲.lrc", "甲.srt"),
                entries.stream().map(GroupFileOps.GroupEntry::fileName).toList());
        assertEquals(List.of(GroupFileKind.VIDEO, GroupFileKind.AUDIO,
                GroupFileKind.LYRIC, GroupFileKind.LYRIC),
                entries.stream().map(GroupFileOps.GroupEntry::kind).toList());
    }

    @Test
    public void entries_skipsNullVideoAndAudio() {
        // 纯音频组（没有视频）
        assertEquals(List.of("甲.mp3", "甲.lrc"),
                GroupFileOps.allFiles(null, "甲.mp3", List.of("甲.lrc")));
        // 无歌词的视频组（截图/短片）
        assertEquals(List.of("甲.mp4"), GroupFileOps.allFiles("甲.mp4", null, List.of()));
        // 理论上的空组
        assertTrue(GroupFileOps.allFiles(null, null, List.of()).isEmpty());
    }

    @Test
    public void allFiles_matchesEntriesOrder() {
        List<String> lyrics = List.of("甲.lrc", "甲.srt");
        assertEquals(GroupFileOps.entries("甲.mp4", "甲.mp3", lyrics).stream()
                        .map(GroupFileOps.GroupEntry::fileName).toList(),
                GroupFileOps.allFiles("甲.mp4", "甲.mp3", lyrics));
    }

    // ==================== requireLyricFile ====================

    @Test
    public void requireLyricFile_blankTakesFirst() {
        List<String> lyrics = List.of("甲.lrc", "甲.srt");
        assertEquals("甲.lrc", GroupFileOps.requireLyricFile(lyrics, null));
        assertEquals("甲.lrc", GroupFileOps.requireLyricFile(lyrics, "  "));
    }

    @Test
    public void requireLyricFile_exactMatchOnly() {
        List<String> lyrics = List.of("甲.lrc", "甲.srt");
        assertEquals("甲.srt", GroupFileOps.requireLyricFile(lyrics, "甲.srt"));
        // 组外的文件、大小写不符的，一律拒 —— 这正是比对扫描结果（而不是拼路径）的意义
        assertThrows(IllegalArgumentException.class,
                () -> GroupFileOps.requireLyricFile(lyrics, "../甲.lrc"));
        assertThrows(IllegalArgumentException.class,
                () -> GroupFileOps.requireLyricFile(lyrics, "甲.LRC"));
    }

    @Test
    public void requireLyricFile_noLyricsAtAll() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> GroupFileOps.requireLyricFile(List.of(), null));
        assertEquals("这一组没有歌词文件", e.getMessage());
    }

    // ==================== planDeletes ====================

    @Test
    public void planDeletes_existingFilesAreNotBlocked() throws IOException {
        Files.writeString(dir.resolve("甲.mp3"), "x");
        Files.writeString(dir.resolve("甲.lrc"), "y");

        List<FileMove> moves = GroupFileOps.planDeletes(dir, List.of("甲.mp3", "甲.lrc"));

        assertEquals(2, moves.size());
        assertEquals("甲.mp3", moves.get(0).fileName());
        assertEquals(dir.resolve("甲.mp3").toString(), moves.get(0).fromPath());
        // 删除计划没有「目标」：toFileName / toPath 都是 null
        assertNull(moves.get(0).toFileName());
        assertNull(moves.get(0).toPath());
        assertNull(moves.get(0).blockedReason());
        assertNull(moves.get(1).blockedReason());
    }

    @Test
    public void planDeletes_missingFileIsBlockedButStillListed() throws IOException {
        Files.writeString(dir.resolve("甲.mp3"), "x");

        List<FileMove> moves = GroupFileOps.planDeletes(dir, List.of("甲.mp3", "甲.lrc"));

        // 已经不在的照样列出来（否则「删除清单」与库里那一组对不上），但打上拦截
        assertEquals(2, moves.size());
        assertNull(moves.get(0).blockedReason());
        assertNotNull(moves.get(1).blockedReason());
        assertTrue(moves.get(1).blockedReason().contains("文件不在了"));
        assertTrue(moves.get(1).blockedReason().contains("甲.lrc"));
    }

    @Test
    public void planDeletes_emptyWhenNoFiles() {
        assertTrue(GroupFileOps.planDeletes(dir, List.of()).isEmpty());
    }

    // ==================== planMoves ====================

    /**
     * <b>这个类里最要紧的一条。</b>目标名 = 新主名 + <b>原文</b>扩展名 —— 大写 {@code .MP3}
     * 改名后仍是大写 {@code .MP3}。
     *
     * <p>2026-09-23 起这条从「顺手别改小写」升级成了一条不变量：{@code song_file} /
     * {@code shout_file} 不再存完整文件名，只存 {@code main_name} + {@code suffix}
     * （{@link io.github.Nyameph.nyaentworks.common.media.MediaExtensions#suffix} 是唯一口径）。
     * 改名后库里只换 {@code main_name}、后缀那一列照旧，所以磁盘上落成的名字必须仍是
     * {@code 新主名 + 原后缀} —— 一旦这里把小写化的 {@code extension()} 直接拼进去
     * （而不是像实现里那样借它的<b>长度</b>切原文），
     * 磁盘就成了 {@code 新名.mp3} 而库里是 {@code 新名} + {@code .MP3}，
     * 下一轮同步会认为「磁盘上那个文件没入过库」而删掉旧行、插一行新的，
     * {@code id} 与 {@code create_time} 每轮都变，唯一的信号是同步计数不是 0。
     */
    @Test
    public void planMoves_keepsExtensionCaseFromSourceName() throws IOException {
        Files.writeString(dir.resolve("某首歌.MP3"), "x");
        Files.writeString(dir.resolve("某首歌.lrc"), "y");

        List<FileMove> moves = GroupFileOps.planMoves(
                List.of("某首歌.MP3", "某首歌.lrc"), dir, dir, "新名字");

        assertEquals(List.of("新名字.MP3", "新名字.lrc"),
                moves.stream().map(FileMove::toFileName).toList());
        assertNull(moves.get(0).blockedReason());
        assertNull(moves.get(1).blockedReason());
    }

    @Test
    public void planMoves_changesDirAndMainName() throws IOException {
        Path target = Files.createDirectory(dir.resolve("评分区"));
        Files.writeString(dir.resolve("甲.mp4"), "x");

        List<FileMove> moves = GroupFileOps.planMoves(List.of("甲.mp4"), dir, target, "乙");

        assertEquals(1, moves.size());
        assertEquals("甲.mp4", moves.get(0).fileName());
        assertEquals("乙.mp4", moves.get(0).toFileName());
        assertEquals(dir.resolve("甲.mp4").toString(), moves.get(0).fromPath());
        assertEquals(target.resolve("乙.mp4").toString(), moves.get(0).toPath());
        assertNull(moves.get(0).blockedReason());
    }

    @Test
    public void planMoves_missingSourceIsBlockedButStillListed() throws IOException {
        Files.writeString(dir.resolve("甲.mp3"), "x");

        List<FileMove> moves = GroupFileOps.planMoves(
                List.of("甲.mp3", "甲.lrc"), dir, dir, "新名字");

        // 已经不在的照样列出来（否则「改名清单」与库里那一组对不上），但打上拦截
        assertEquals(2, moves.size());
        assertNull(moves.get(0).blockedReason());
        assertNotNull(moves.get(1).blockedReason());
        assertTrue(moves.get(1).blockedReason().contains("文件不在了"));
    }

    /** 不覆盖（文档 8.4）：目标已有同名文件会静默毁掉那一份 */
    @Test
    public void planMoves_existingTargetIsBlocked() throws IOException {
        Files.writeString(dir.resolve("甲.mp3"), "x");
        Files.writeString(dir.resolve("乙.mp3"), "y");

        List<FileMove> moves = GroupFileOps.planMoves(List.of("甲.mp3"), dir, dir, "乙");

        assertNotNull(moves.get(0).blockedReason());
        assertTrue(moves.get(0).blockedReason().contains("目标已存在同名文件"));
    }

    @Test
    public void planMoves_emptyWhenNoFiles() {
        assertTrue(GroupFileOps.planMoves(List.of(), dir, dir, "新名字").isEmpty());
    }
}
