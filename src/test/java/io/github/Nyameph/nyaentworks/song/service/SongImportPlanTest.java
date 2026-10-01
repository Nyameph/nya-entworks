package io.github.Nyameph.nyaentworks.song.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.Nyameph.nyaentworks.song.config.SongProperties;
import io.github.Nyameph.nyaentworks.song.controller.SongController;
import io.github.Nyameph.nyaentworks.song.controller.SongController.ImportFile;
import io.github.Nyameph.nyaentworks.song.controller.SongController.ImportRequest;
import io.github.Nyameph.nyaentworks.song.service.SongImportService.Form;
import io.github.Nyameph.nyaentworks.song.service.SongImportService.ImportPlan;
import io.github.Nyameph.nyaentworks.song.service.SongImportService.LandingGroup;
import io.github.Nyameph.nyaentworks.song.service.SongImportService.LocatedFile;
import io.github.Nyameph.nyaentworks.song.service.SongImportService.Target;
import io.github.Nyameph.nyaentworks.song.service.SongArchiveService.GroupRef;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SongImportService} 的 plan / apply 核心（① 与 ②③ 共用那一份）。
 * {@code @TempDir} 当受管根，{@code SongSettingService} / {@code SongTagService} 用
 * Mockito 的 mock —— 后者兼作「库那侧被调了没有」的探针。不连库、不碰 {@code F:\}。
 */
public class SongImportPlanTest {

    @TempDir
    Path root;

    private final SongSettingService settingService = mock(SongSettingService.class);
    private final SongTagService tagService = mock(SongTagService.class);

    private SongProperties properties() throws IOException {
        SongProperties properties = new SongProperties();
        properties.setSongDir(root.resolve("成品-歌曲").toString());
        properties.setStagingDir(root.resolve("staging").resolve("歌曲").toString());
        properties.setTemplateDir(root.resolve("模板").toString());
        properties.setOnlyOriginalDir(root.resolve("仅原曲").toString());
        Files.createDirectories(root.resolve("成品-歌曲").resolve("#9超赞"));
        Files.createDirectories(root.resolve("staging").resolve("歌曲"));
        return properties;
    }

    private SongImportService service() throws IOException {
        SongProperties properties = properties();
        return new SongImportService(new SongGroupService(properties),
                settingService, tagService, properties);
    }

    private Path staging() throws IOException {
        return Files.createDirectories(root.resolve("staging").resolve("歌曲"));
    }

    private static Path touch(Path dir, String name) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        Files.writeString(file, "x");
        return file;
    }

    private ImportRequest request(Path dir, String artists, String title, String original,
                                  boolean rename, String target, Integer score,
                                  ImportFile... files) {
        return new ImportRequest(List.of(files), artists, title, original, rename,
                target, score, null);
    }

    // ==================== ① 的 plan ====================

    @Test
    public void duplicateTargetsBlockedWithNameBothFiles() throws IOException {
        Path dir = Files.createDirectories(root.resolve("下载"));
        touch(dir, "甲.mp4");
        touch(dir, "乙.mp4");
        ImportPlan plan = service().planImport(request(dir, "张三", "歌", "原曲", true,
                "staging", null,
                new ImportFile(dir.resolve("甲.mp4").toString(), null),
                new ImportFile(dir.resolve("乙.mp4").toString(), null)));
        String reason = plan.plan().firstBlockedReason();
        assertNotNull(reason);
        assertTrue(reason.contains("甲.mp4") && reason.contains("乙.mp4"), "要指名是哪两个文件：" + reason);
        assertTrue(reason.contains("编号"), "建议里要教怎么填：" + reason);
    }

    @Test
    public void versionSeparatesTargets() throws IOException {
        Path dir = Files.createDirectories(root.resolve("下载"));
        touch(dir, "甲.mp4");
        touch(dir, "乙.mp4");
        ImportPlan plan = service().planImport(request(dir, "张三", "歌", "原曲", true,
                "staging", null,
                new ImportFile(dir.resolve("甲.mp4").toString(), null),
                new ImportFile(dir.resolve("乙.mp4").toString(), "2")));
        assertNull(plan.plan().blockedReason());
        assertEquals(2, plan.plan().moves().size());
        assertNotEqualsIgnoreCase(plan.plan().moves().get(0).toPath(),
                plan.plan().moves().get(1).toPath());
    }

    private static void assertNotEqualsIgnoreCase(String a, String b) {
        assertTrue(!a.equalsIgnoreCase(b), "两条目标路径不该相同：" + a + " vs " + b);
    }

    @Test
    public void targetExistsBlocked() throws IOException {
        Path dir = Files.createDirectories(root.resolve("下载"));
        touch(dir, "甲.mp4");
        touch(staging(), "张三 - 歌（原曲）.mp4");
        ImportPlan plan = service().planImport(request(dir, "张三", "歌", "原曲", true,
                "staging", null, new ImportFile(dir.resolve("甲.mp4").toString(), null)));
        assertTrue(plan.plan().blocked(), "目标已存在同名要走 FileMove.blockedReason");
        assertTrue(plan.plan().firstBlockedReason().contains("目标已存在"));
    }

    @Test
    public void missingSourceBlocked() throws IOException {
        Path dir = Files.createDirectories(root.resolve("下载"));
        ImportPlan plan = service().planImport(request(dir, "张三", "歌", "原曲", true,
                "staging", null, new ImportFile(dir.resolve("不在.mp4").toString(), null)));
        assertTrue(plan.plan().blocked());
        assertTrue(plan.plan().firstBlockedReason().contains("源文件不在"));
    }

    @Test
    public void lyricOnlyNotBlockedByPlan() throws IOException {
        // 「至少一个媒体文件」是表单闸门，plan 不做 —— 只有歌词也能排出清单
        Path dir = Files.createDirectories(root.resolve("下载"));
        touch(dir, "甲.lrc");
        ImportPlan plan = service().planImport(request(dir, "张三", "歌", "原曲", true,
                "staging", null, new ImportFile(dir.resolve("甲.lrc").toString(), null)));
        assertNull(plan.plan().blockedReason());
        assertEquals(1, plan.plan().moves().size());
    }

    @Test
    public void versionWithHashBlocked() throws IOException {
        Path dir = Files.createDirectories(root.resolve("下载"));
        touch(dir, "甲.mp4");
        ImportPlan plan = service().planImport(request(dir, "张三", "歌", "原曲", true,
                "staging", null, new ImportFile(dir.resolve("甲.mp4").toString(), "2#幼")));
        String reason = plan.plan().firstBlockedReason();
        assertNotNull(reason);
        assertTrue(reason.contains("甲.mp4"), "要指出是哪个文件：" + reason);
        assertTrue(reason.contains("#"));
    }

    @Test
    public void blankFormBlocked() throws IOException {
        Path dir = Files.createDirectories(root.resolve("下载"));
        touch(dir, "甲.mp4");
        for (int i = 0; i < 3; i++) {
            String artists = i == 0 ? " " : "张三";
            String title = i == 1 ? " " : "歌";
            String original = i == 2 ? " " : "原曲";
            ImportPlan plan = service().planImport(request(dir, artists, title, original, true,
                    "staging", null, new ImportFile(dir.resolve("甲.mp4").toString(), null)));
            assertTrue(plan.plan().firstBlockedReason().contains("作者、曲名、原曲名都要填")
                            || plan.plan().firstBlockedReason().contains("作者 / 曲名 / 原曲名都要填"),
                    plan.plan().firstBlockedReason());
        }
    }

    @Test
    public void missingPartitionBlockedNotThrown() throws IOException {
        Path dir = Files.createDirectories(root.resolve("下载"));
        touch(dir, "甲.mp4");
        // #7分区 没建：blockedReason、不创建目录、不抛
        SongProperties properties = properties();
        SongImportService svc = new SongImportService(new SongGroupService(properties),
                settingService, tagService, properties);
        ImportPlan plan = svc.planImport(request(dir, "张三", "歌", "原曲", true,
                "#7佳作", 7, new ImportFile(dir.resolve("甲.mp4").toString(), null)));
        assertNotNull(plan.plan().firstBlockedReason());
        assertTrue(plan.plan().firstBlockedReason().contains("分区"));
        assertTrue(!Files.exists(root.resolve("成品-歌曲").resolve("#7佳作")), "不许自动创建分区");
    }

    @Test
    public void stagingWithScoreBlocked() throws IOException {
        Path dir = Files.createDirectories(root.resolve("下载"));
        touch(dir, "甲.mp4");
        ImportPlan plan = service().planImport(request(dir, "张三", "歌", "原曲", true,
                "staging", 9, new ImportFile(dir.resolve("甲.mp4").toString(), null)));
        assertNotNull(plan.plan().firstBlockedReason());
        assertTrue(plan.plan().firstBlockedReason().contains("同一件事"));
    }

    @Test
    public void differentVersionsMakeTwoLandingGroups() throws IOException {
        // ① 允许逐文件不同编号：按 (源目录, 目标主名) 分组算出两组目标路径
        Path dir = Files.createDirectories(root.resolve("下载"));
        touch(dir, "甲.mp4");
        touch(dir, "乙.mp4");
        ImportPlan plan = service().planImport(request(dir, "张三", "歌", "原曲", true,
                "staging", null,
                new ImportFile(dir.resolve("甲.mp4").toString(), null),
                new ImportFile(dir.resolve("乙.mp4").toString(), "幼")));
        assertNull(plan.plan().blockedReason());
        assertEquals(2, plan.plan().moves().size());
        assertTrue(plan.plan().moves().get(0).toPath().contains("歌（原曲）.mp4"));
        assertTrue(plan.plan().moves().get(1).toPath().contains("歌（原曲）#幼.mp4"));
    }

    @Test
    public void noMovesIsLegalForApply() throws IOException {
        // 已经在未归档里、名字也一致：合法输入，什么都不搬、也不抛
        Path staging = staging();
        Path song = touch(staging, "张三 - 歌（原曲）.mp4");
        SongImportService svc = service();
        var result = svc.applyImport(request(staging, "张三", "歌", "原曲", true,
                "staging", null, new ImportFile(song.toString(), null)));
        assertEquals(0, result.movedFiles());
        verify(settingService, never()).editSongGroup(any(), any(), any(), any(), any());
    }

    @Test
    public void crossVolumeDetectableButDisabledHere() {
        // 跨卷要用第二个 FileStore 才能构造，CI / 单盘环境做不到。保留用例：
        // crossVolumeOf 在 getFileStore 不同侧时收集文件名（见 crossVolumeOf 的实现）。
        // 真正的跨卷行为由人工回归（U 盘来源）覆盖 —— 添加歌曲设计 §7.2。
    }

    // ==================== apply：搬动 + 标签 + 库 ====================

    @Test
    public void applyMovesRenamesAndWritesTags() throws IOException {
        Path dir = Files.createDirectories(root.resolve("下载"));
        Path song = touch(dir, "甲.mp4");
        SongImportService svc = service();
        var result = svc.applyImport(new ImportRequest(
                List.of(new ImportFile(song.toString(), null)),
                "张三", "歌", "原曲", true, "staging", null, List.of("新标签")));
        assertEquals(1, result.movedFiles());
        assertTrue(Files.exists(staging().resolve("张三 - 歌（原曲）.mp4")));
        assertTrue(!Files.exists(song));
        // 标签按落盘后的主名算 mergeKey 写
        verify(tagService, times(1)).replaceTags(anyString(), eq(List.of("新标签")));
    }

    @Test
    public void applyArchiveDoesNotTouchLibrary() throws IOException {
        // ① 归档：libraryAnchors 恒空 —— 新导入的组库里还没有行，入库交给同步补建
        Path dir = Files.createDirectories(root.resolve("下载"));
        Path song = touch(dir, "甲.mp4");
        SongImportService svc = service();
        var result = svc.applyImport(request(dir, "张三", "歌", "原曲", true,
                "#9超赞", 9, new ImportFile(song.toString(), null)));
        assertEquals(1, result.movedFiles());
        assertTrue(Files.exists(root.resolve("成品-歌曲").resolve("#9超赞")
                .resolve("张三 - 歌（原曲）.mp4")));
        verify(settingService, never()).editSongGroup(any(), any(), any(), any(), any());
    }

    @Test
    public void applyTagsNullSkipsTagWrite() throws IOException {
        Path dir = Files.createDirectories(root.resolve("下载"));
        Path song = touch(dir, "甲.mp4");
        service().applyImport(request(dir, "张三", "歌", "原曲", true,
                "staging", null, new ImportFile(song.toString(), null)));
        verify(tagService, never()).replaceTags(anyString(), any());
    }

    @Test
    public void applyRevalidatesAndThrowsWhenBlocked() throws IOException {
        // 二次校验：源在 plan 之后被删掉，apply 里重跑 plan 会 blocked → 抛，不静默跳过
        Path dir = Files.createDirectories(root.resolve("下载"));
        Path song = touch(dir, "甲.mp4");
        SongImportService svc = service();
        Files.delete(song);
        assertThrows(IllegalStateException.class, () ->
                svc.applyImport(request(dir, "张三", "歌", "原曲", true,
                        "staging", null, new ImportFile(song.toString(), null))));
    }

    // ==================== ②③ 共用核心：editSongGroup 必被调到 ====================

    @Test
    public void editFormCallsEditSongGroupEvenScoreNull() throws IOException {
        // ②③ 只改标签 / 只改名的情形：score == null、moves 空，editSongGroup 仍要调
        SongProperties properties = properties();
        SongImportService svc = new SongImportService(new SongGroupService(properties),
                settingService, tagService, properties);
        Path staging = staging();
        Path song = touch(staging, "张三 - 歌（原曲）.mp4");
        when(settingService.editSongGroup(any(), any(), any(), any(), any())).thenReturn(true);

        LocatedFile file = new LocatedFile(song, song.getFileName().toString(),
                "张三 - 歌（原曲）", null, "|张三 - 歌（原曲）", "");
        Form form = new Form("张三", "歌", "原曲", false);
        Target target = new Target(staging, null, null);
        var result = svc.apply(List.of(new LandingGroup(List.of(file), form, target)),
                null, List.of(new GroupRef("", "张三 - 歌（原曲）")));
        assertEquals(0, result.movedFiles());
        verify(settingService, times(1)).editSongGroup(eq(""), eq("张三 - 歌（原曲）"),
                isNull(), isNull(), eq("张三 - 歌（原曲）"));
    }

    // ==================== ②③ 的 locatedFiles / planEditForm / applyEditForm ====================

    private SongController.EditFormRequest editForm(String partition, String mainName,
                                                    String artists, String title,
                                                    String original, boolean rename,
                                                    Integer score, List<String> tags,
                                                    SongController.EditFormFile... files) {
        return editFormExtra(partition, mainName, artists, title, original, rename,
                score, tags, List.of(), files);
    }

    /** 带 extraFiles（②③ 表单里「＋ 添加文件」选进来的外部文件）的版本 */
    private SongController.EditFormRequest editFormExtra(String partition, String mainName,
                                                         String artists, String title,
                                                         String original, boolean rename,
                                                         Integer score, List<String> tags,
                                                         List<SongController.ImportFile> extraFiles,
                                                         SongController.EditFormFile... files) {
        return new SongController.EditFormRequest(
                List.of(new SongController.EditFormGroup(partition, mainName, List.of(files))),
                artists, title, original, rename, null, score, tags, extraFiles);
    }

    private static SongController.EditFormFile file(String fileName, String version) {
        return new SongController.EditFormFile(fileName, version, null);
    }

    private static SongController.EditFormFile excluded(String fileName) {
        return new SongController.EditFormFile(fileName, null, true);
    }

    @Test
    public void editFormSourceLocatedByBackend() throws IOException {
        // 请求只给 (partition, mainName, [fileName…])，盘上真有一组 → moves 落在该组目录里
        Path partitionDir = Files.createDirectories(root.resolve("成品-歌曲").resolve("#9超赞"));
        Path song = touch(partitionDir, "张三 - 歌（原曲）.mp4");
        var plan = service().planEditForm(editForm("#9超赞", "张三 - 歌（原曲）",
                "张三", "歌", "原曲", true, null, null,
                file("张三 - 歌（原曲）.mp4", "2")));
        assertNull(plan.plan().blockedReason());
        assertEquals(1, plan.plan().moves().size());
        assertTrue(plan.plan().moves().getFirst().fromPath().startsWith(partitionDir.toString()));
        assertTrue(plan.plan().moves().getFirst().toPath().contains("歌（原曲）#2.mp4"));
    }

    @Test
    public void editFormMissingFileOnDiskBlocked() throws IOException {
        Path partitionDir = Files.createDirectories(root.resolve("成品-歌曲").resolve("#9超赞"));
        touch(partitionDir, "张三 - 歌（原曲）.mp4");
        var plan = service().planEditForm(editForm("#9超赞", "张三 - 歌（原曲）",
                "张三", "歌", "原曲", true, null, null,
                file("张三 - 歌（原曲）.srt", null)));
        assertNotNull(plan.plan().firstBlockedReason());
        assertTrue(plan.plan().firstBlockedReason().contains("先重新扫描"));
    }

    @Test
    public void editFormMissingGroupBlocked() throws IOException {
        var plan = service().planEditForm(editForm("#9超赞", "不存在的歌",
                "张三", "歌", "原曲", true, null, null,
                file("x.mp4", null)));
        assertNotNull(plan.plan().firstBlockedReason());
        assertTrue(plan.plan().firstBlockedReason().contains("重新扫描"));
    }

    @Test
    public void editFormOnlyTagsMovesEmptyButLibraryTouched() throws IOException {
        Path staging = staging();
        touch(staging, "张三 - 歌（原曲）.mp4");
        when(settingService.editSongGroup(any(), any(), any(), any(), any())).thenReturn(true);
        var result = service().applyEditForm(editForm("", "张三 - 歌（原曲）",
                "张三", "歌", "原曲", false, null, List.of("标签A"),
                file("张三 - 歌（原曲）.mp4", "9")));
        assertEquals(0, result.movedFiles());
        verify(settingService, times(1)).editSongGroup(any(), any(), any(), any(), any());
        verify(tagService, times(1)).replaceTags(anyString(), eq(List.of("标签A")));
    }

    @Test
    public void editFormRenameWithVersionMoves() throws IOException {
        Path staging = staging();
        touch(staging, "张三 - 歌（原曲）.mp4");
        var result = service().applyEditForm(editForm("", "张三 - 歌（原曲）",
                "李四", "新歌", "新原曲", true, null, null,
                file("张三 - 歌（原曲）.mp4", "幼")));
        assertEquals(1, result.movedFiles());
        assertTrue(Files.exists(staging.resolve("李四 - 新歌（新原曲）#幼.mp4")));
    }

    @Test
    public void editFormNoRenameIgnoresVersion() throws IOException {
        Path staging = staging();
        Path song = touch(staging, "张三 - 歌（原曲）.mp4");
        // rename=false 但请求带了不同编号 → 编号被忽略，targetMainName = 现主名，不产生 moves
        var plan = service().planEditForm(editForm("", "张三 - 歌（原曲）",
                "李四", "新歌", "新原曲", false, null, null,
                file("张三 - 歌（原曲）.mp4", "9")));
        assertEquals(0, plan.plan().moves().size());
        assertTrue(Files.exists(song), "文件没动");
    }

    @Test
    public void editFormInconsistentVersionInSameGroupBlocked() throws IOException {
        Path staging = staging();
        touch(staging, "张三 - 歌（原曲）.mp4");
        touch(staging, "张三 - 歌（原曲）.lrc");
        var plan = service().planEditForm(editForm("", "张三 - 歌（原曲）",
                "李四", "新歌", "新原曲", true, null, null,
                file("张三 - 歌（原曲）.mp4", "2"),
                file("张三 - 歌（原曲）.lrc", "3")));
        assertNotNull(plan.plan().firstBlockedReason());
        assertTrue(plan.plan().firstBlockedReason().contains("不同编号"));
    }

    // ==================== 剔除 → 冗余 / extraFiles / 已归档源防双条 ====================

    @Test
    public void excludeArchivedFileMovesToRedundant() throws IOException {
        Path partitionDir = Files.createDirectories(root.resolve("成品-歌曲").resolve("#9超赞"));
        Path keep = touch(partitionDir, "张三 - 歌（原曲）.mp4");
        Path gone = touch(partitionDir, "张三 - 歌（原曲）.lrc");
        var plan = service().planEditForm(editForm("#9超赞", "张三 - 歌（原曲）",
                "张三", "歌", "原曲", false, null, null,
                file("张三 - 歌（原曲）.mp4", null), excluded("张三 - 歌（原曲）.lrc")));
        assertNull(plan.plan().blockedReason());
        // 剔除行进同一张清单，目标是「冗余」文件夹；未剔除的 mp4 没改名不搬（from==to 不进清单）
        assertEquals(1, plan.plan().moves().size());
        assertTrue(plan.plan().moves().stream().anyMatch(
                m -> m.toPath().contains("冗余") && m.fromPath().endsWith(".lrc")));

        var result = service().applyEditForm(editForm("#9超赞", "张三 - 歌（原曲）",
                "张三", "歌", "原曲", false, null, null,
                file("张三 - 歌（原曲）.mp4", null), excluded("张三 - 歌（原曲）.lrc")));
        assertEquals(1, result.movedFiles());
        assertTrue(Files.exists(root.resolve("成品-歌曲").resolve("冗余")
                .resolve("张三 - 歌（原曲）.lrc")));
        assertTrue(!Files.exists(gone));
        // 库侧收尾被调到：该行置 song_id=0、main_name=冗余里的实际名
        verify(settingService).markFileRedundant(eq("#9超赞"), eq("张三 - 歌（原曲）"),
                eq("张三 - 歌（原曲）.lrc"), eq("张三 - 歌（原曲）.lrc"));
        assertTrue(Files.exists(keep), "未剔除的文件不动");
    }

    @Test
    public void excludeAllFilesOfGroupAllowed() throws IOException {
        Path partitionDir = Files.createDirectories(root.resolve("成品-歌曲").resolve("#9超赞"));
        touch(partitionDir, "张三 - 歌（原曲）.mp4");
        touch(partitionDir, "张三 - 歌（原曲）.lrc");
        var plan = service().planEditForm(editForm("#9超赞", "张三 - 歌（原曲）",
                "张三", "歌", "原曲", false, null, null,
                excluded("张三 - 歌（原曲）.mp4"), excluded("张三 - 歌（原曲）.lrc")));
        assertNull(plan.plan().blockedReason(), "整组全部剔除 = 解散到冗余，是合法提交");
        assertEquals(2, plan.plan().moves().size());
    }

    @Test
    public void extraFilesAppendedToGroup() throws IOException {
        Path partitionDir = Files.createDirectories(root.resolve("成品-歌曲").resolve("#9超赞"));
        touch(partitionDir, "张三 - 歌（原曲）.mp4");
        Path external = touch(Files.createDirectories(root.resolve("下载")), "外部.lrc");
        var plan = service().planEditForm(editFormExtra("#9超赞", "张三 - 歌（原曲）",
                "张三", "歌", "原曲", true, null, null,
                List.of(new SongController.ImportFile(external.toString(), null)),
                file("张三 - 歌（原曲）.mp4", null)));
        assertNull(plan.plan().blockedReason());
        // mp4 改名后与新名一致（from==to 不进清单）；外部 lrc 是真搬动
        assertEquals(1, plan.plan().moves().size(), "既有文件 + 外部文件合并成一份清单");
        assertTrue(plan.plan().moves().stream().anyMatch(
                m -> m.fromPath().equals(external.toString())));
    }

    @Test
    public void archivedSourceTriggersRelocate() throws IOException {
        // ① 从已归档分区选文件：apply 落盘后必须调 relocateArchivedFile 防双条
        Path partitionDir = Files.createDirectories(root.resolve("成品-歌曲").resolve("#9超赞"));
        Path song = touch(partitionDir, "张三 - 旧歌（旧原曲）.mp4");
        when(settingService.relocateArchivedFile(
                any(), any(), any(), any(), any())).thenReturn(true);
        var result = service().applyImport(request(partitionDir, "张三", "歌", "原曲", true,
                "staging", null, new ImportFile(song.toString(), null)));
        assertEquals(1, result.movedFiles());
        assertTrue(Files.exists(staging().resolve("张三 - 歌（原曲）.mp4")));
        verify(settingService).relocateArchivedFile(eq("#9超赞"), eq("张三 - 旧歌（旧原曲）"),
                eq(".mp4"), isNull(), eq("张三 - 歌（原曲）"));
    }

    @Test
    public void externalSourceSkipsRelocate() throws IOException {
        // 外部来源（非分区目录）：不触发 relocate
        Path dir = Files.createDirectories(root.resolve("下载"));
        Path song = touch(dir, "甲.mp4");
        service().applyImport(request(dir, "张三", "歌", "原曲", true,
                "staging", null, new ImportFile(song.toString(), null)));
        verify(settingService, never()).relocateArchivedFile(
                any(), any(), any(), any(), any());
    }

    @Test
    public void editFormMultiVariantMergedIntoOnePlan() throws IOException {
        // 一行 ×N 版本（两个 group 在不同分区）：逐项各自定位，moves 合并成一份
        Path p9 = Files.createDirectories(root.resolve("成品-歌曲").resolve("#9超赞"));
        Path p5 = Files.createDirectories(root.resolve("成品-歌曲").resolve("#5可用"));
        touch(p9, "张三 - 歌（原曲）.mp4");
        touch(p5, "张三 - 歌（原曲）#2.mp4");
        SongController.EditFormRequest req = new SongController.EditFormRequest(List.of(
                new SongController.EditFormGroup("#9超赞", "张三 - 歌（原曲）",
                        List.of(file("张三 - 歌（原曲）.mp4", null))),
                new SongController.EditFormGroup("#5可用", "张三 - 歌（原曲）#2",
                        List.of(file("张三 - 歌（原曲）#2.mp4", null)))),
                "李四", "新歌", "新原曲", true, null, null, null, null);
        var plan = service().planEditForm(req);
        assertNull(plan.plan().blockedReason());
        assertEquals(2, plan.plan().moves().size());
    }
}
