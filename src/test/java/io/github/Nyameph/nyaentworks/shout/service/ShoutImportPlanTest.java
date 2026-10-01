package io.github.Nyameph.nyaentworks.shout.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.Nyameph.nyaentworks.common.file.GroupFileOps.FileMove;
import io.github.Nyameph.nyaentworks.shout.config.ShoutProperties;
import io.github.Nyameph.nyaentworks.shout.controller.ShoutController;
import io.github.Nyameph.nyaentworks.shout.controller.ShoutController.EditFormFile;
import io.github.Nyameph.nyaentworks.shout.controller.ShoutController.EditFormGroup;
import io.github.Nyameph.nyaentworks.shout.controller.ShoutController.EditFormRequest;
import io.github.Nyameph.nyaentworks.shout.controller.ShoutController.ImportFile;
import io.github.Nyameph.nyaentworks.shout.controller.ShoutController.ImportRequest;
import io.github.Nyameph.nyaentworks.shout.service.ShoutArchiveService.GroupApplyResult;
import io.github.Nyameph.nyaentworks.shout.service.ShoutArchiveService.GroupRef;
import io.github.Nyameph.nyaentworks.shout.service.ShoutImportService.ImportPlan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「添加文件 / 列表式修改」的 plan 与 apply（喊麦）口径。
 *
 * <p>{@code @TempDir} 当受管根（不连库、不碰 {@code F:\}）：{@code ShoutGroupService} 是真扫，
 * {@code ShoutStoreService} 是 Mockito 探针 —— 这一套盯的正是「磁盘怎么动 + 库那侧有没有被叫到」，
 * 库真正改对没改对由 {@code shout_group} / {@code shout_file} 的同步逻辑负责。
 *
 * <p>搬动是真搬（{@code GroupFileOps.moveAll}），所以每个用例的临时目录必须是干净的一套。
 */
public class ShoutImportPlanTest {

    @TempDir
    Path root;

    private ShoutProperties properties;
    private ShoutGroupService groupService;
    private ShoutStoreService store;
    private ShoutImportService service;

    @BeforeEach
    public void setUp() throws IOException {
        properties = new ShoutProperties();
        properties.setArchivedDir(root.resolve("成品-喊麦").toString());
        properties.setStagingDir(root.resolve("staging").toString());
        // 两档评分分区：9 分那个用来归档，5 分那个用来验「target 与 score 打架」
        Files.createDirectories(root.resolve("成品-喊麦").resolve("#9超赞"));
        Files.createDirectories(root.resolve("成品-喊麦").resolve("#5可用"));
        Files.createDirectories(root.resolve("staging"));
        groupService = new ShoutGroupService(properties);
        store = mock(ShoutStoreService.class);
        service = new ShoutImportService(groupService, store, properties);
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** 造一个外部文件（源天生在受管根之外），返回真实路径 */
    private Path touch(String relative) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x");
        return file.toRealPath();
    }

    private static List<ImportFile> files(Path... paths) {
        return java.util.Arrays.stream(paths).map(p -> new ImportFile(p.toString())).toList();
    }

    private Path staging() {
        return groupService.stagingRoot();
    }

    private Path archived(String partition) {
        return root.resolve("成品-喊麦").resolve(partition);
    }

    // ------------------------------------------------------------------
    // ① 添加文件：plan 的闸门
    // ------------------------------------------------------------------

    @Test
    public void crossMainNameBlockedWithoutNewName() throws IOException {
        Path a = touch("下载/甲.mp4");
        Path b = touch("下载/乙.lrc");
        ImportPlan plan = service.planImport(new ImportRequest(files(a, b), null, "staging", null, null));
        assertTrue(plan.plan().blocked());
        String reason = plan.plan().firstBlockedReason();
        assertTrue(reason.contains("一次只能处理一组"), reason);
        // 拦下时要告诉人分了哪几组，否则用户不知道留哪个
        assertTrue(reason.contains("甲") && reason.contains("乙"), reason);
    }

    @Test
    public void newNameMergesIntoOneGroup() throws IOException {
        Path a = touch("下载/甲.mp4");
        Path b = touch("下载/乙.lrc");
        ImportPlan plan = service.planImport(
                new ImportRequest(files(a, b), "合", "staging", null, null));
        assertFalse(plan.plan().blocked(), plan.plan().firstBlockedReason());
        assertEquals("SHOUT_IMPORT_STAGING", plan.plan().action());
        assertEquals(2, plan.plan().moves().size());
        assertEquals(staging().resolve("合.mp4").toString(), plan.plan().moves().get(0).toPath());
        assertEquals(staging().resolve("合.lrc").toString(), plan.plan().moves().get(1).toPath());
    }

    @Test
    public void duplicateTargetsBlocked() throws IOException {
        // 两个视频 + 一个新名 = 都会落成 合.mp4。planMoves 只比磁盘现状，查不到「本批撞自己」
        Path a = touch("下载/甲.mp4");
        Path b = touch("下载/乙.mp4");
        ImportPlan plan = service.planImport(
                new ImportRequest(files(a, b), "合", "staging", null, null));
        assertTrue(plan.plan().blocked());
        String reason = plan.plan().firstBlockedReason();
        assertTrue(reason.contains("同一个目标路径"), reason);
        // 文案必须是喊麦能执行的动作：歌曲那条说「给其中一个改个名就能分开」（它有三个框 +
        // 逐文件编号），喊麦没有编号，改名是整组一个全名框 —— 照抄那句等于让人去做做不到的事
        assertFalse(reason.contains("改个名"), reason);
        assertTrue(reason.contains("同一个扩展名"), reason);
    }

    @Test
    public void badNewNameBlocked() throws IOException {
        // 非法主名由 GroupFileOps.requireMainName 一处判，这里只确认它被接住成 blocked
        Path a = touch("下载/甲.mp4");
        ImportPlan plan = service.planImport(
                new ImportRequest(files(a), "合/名", "staging", null, null));
        assertTrue(plan.plan().blocked());
        assertTrue(plan.plan().firstBlockedReason().contains("新名字不行"),
                plan.plan().firstBlockedReason());
    }

    @Test
    public void stagingWithScoreBlocked() throws IOException {
        Path a = touch("下载/甲.mp4");
        ImportPlan plan = service.planImport(new ImportRequest(files(a), null, "staging", 9, null));
        assertTrue(plan.plan().blocked());
        assertTrue(plan.plan().firstBlockedReason().contains("不能带评分"),
                plan.plan().firstBlockedReason());
    }

    @Test
    public void lyricOnlyGroupBlocked() throws IOException {
        Path lrc = touch("下载/甲.lrc");
        // 只有歌词没有媒体：ShoutGroupService 归组时整组跳过（播不了、不该占一行），
        // 所以这种组搬进去就是页面上永远看不见、也归档不了的一个孤儿
        ImportPlan plan = service.planImport(new ImportRequest(files(lrc), null, "staging", null, null));
        assertTrue(plan.plan().blocked());
        assertTrue(plan.plan().firstBlockedReason().contains("只有歌词"),
                plan.plan().firstBlockedReason());
    }

    @Test
    public void lyricWithMediaIsFine() throws IOException {
        // 同一个判据的正面：有音频就放行（歌词跟着一起搬）
        Path mp3 = touch("下载/甲.mp3");
        Path lrc = touch("下载/甲.lrc");
        ImportPlan plan = service.planImport(
                new ImportRequest(files(mp3, lrc), null, "staging", null, null));
        assertFalse(plan.plan().blocked(), plan.plan().firstBlockedReason());
        assertEquals(2, plan.plan().moves().size());
    }

    @Test
    public void applyImportAlsoRejectsLyricOnly() throws IOException {
        // 执行侧也要拦：请求可能在队列里躺过，不能只靠预演那次
        Path lrc = touch("下载/甲.lrc");
        assertThrows(IllegalArgumentException.class, () -> service.applyImport(
                new ImportRequest(files(lrc), null, "staging", null, null)));
    }

    @Test
    public void archiveWithoutScoreBlocked() throws IOException {
        Path a = touch("下载/甲.mp4");
        ImportPlan plan = service.planImport(new ImportRequest(files(a), null, "#9超赞", null, null));
        assertTrue(plan.plan().blocked());
        assertTrue(plan.plan().firstBlockedReason().contains("必须给评分"),
                plan.plan().firstBlockedReason());
    }

    @Test
    public void targetAndScoreMustAgree() throws IOException {
        Path a = touch("下载/甲.mp4");
        ImportPlan plan = service.planImport(new ImportRequest(files(a), null, "#9超赞", 5, null));
        assertTrue(plan.plan().blocked());
        assertTrue(plan.plan().firstBlockedReason().contains("不一致"),
                plan.plan().firstBlockedReason());
    }

    @Test
    public void missingPartitionIsBlockedNotCreated() throws IOException {
        // 3 分那档没建。分区目录是手工建的：requireDir 抛、这里接住成 blocked，绝不能顺手建一个
        Path a = touch("下载/甲.mp4");
        ImportPlan plan = service.planImport(new ImportRequest(files(a), null, "#3流畅", 3, null));
        assertTrue(plan.plan().blocked());
        assertTrue(plan.plan().firstBlockedReason().contains("找不到 3 分"),
                plan.plan().firstBlockedReason());
        assertFalse(Files.exists(archived("#3流畅")), "不许自动建分区目录");
    }

    @Test
    public void targetAlreadyExistsBlocked() throws IOException {
        Files.writeString(staging().resolve("甲.mp4"), "旧的那一份");
        Path a = touch("下载/甲.mp4");
        ImportPlan plan = service.planImport(new ImportRequest(files(a), null, "staging", null, null));
        assertTrue(plan.plan().blocked());
        assertTrue(plan.plan().firstBlockedReason().contains("目标已存在同名文件"),
                plan.plan().firstBlockedReason());
    }

    @Test
    public void missingSourceBlocked() throws IOException {
        Path ghost = root.resolve("下载").resolve("没了.mp4");
        ImportPlan plan = service.planImport(
                new ImportRequest(files(ghost), null, "staging", null, null));
        assertTrue(plan.plan().blocked());
        assertTrue(plan.plan().firstBlockedReason().contains("源文件不在了"),
                plan.plan().firstBlockedReason());
    }

    @Test
    public void archiveBlockedWithoutTags() throws IOException {
        properties.setRequireTagsBeforeArchive(true);
        Path a = touch("下载/甲.mp4");
        ImportPlan plan = service.planImport(new ImportRequest(files(a), null, "#9超赞", 9, null));
        assertTrue(plan.plan().blocked());
        assertTrue(plan.plan().firstBlockedReason().contains("还没打标签"),
                plan.plan().firstBlockedReason());

        // 提交里带了空数组 = 「提交之后没标签」，照样拦（不许把已清空的当没动）
        ImportPlan cleared = service.planImport(
                new ImportRequest(files(a), null, "#9超赞", 9, List.of()));
        assertTrue(cleared.plan().blocked());

        // 带了标签就能归档
        ImportPlan ok = service.planImport(
                new ImportRequest(files(a), null, "#9超赞", 9, List.of("标签A")));
        assertFalse(ok.plan().blocked(), ok.plan().firstBlockedReason());
        assertEquals("SHOUT_IMPORT_ARCHIVE", ok.plan().action());
    }

    @Test
    public void tagGateOffByDefault() throws IOException {
        Path a = touch("下载/甲.mp4");
        ImportPlan plan = service.planImport(new ImportRequest(files(a), null, "#9超赞", 9, null));
        assertFalse(plan.plan().blocked(), plan.plan().firstBlockedReason());
    }

    // ------------------------------------------------------------------
    // ① 添加文件：apply
    // ------------------------------------------------------------------

    @Test
    public void applyMovesRenamesAndWritesTags() throws IOException {
        Path a = touch("下载/甲.mp4");
        Path b = touch("下载/甲.lrc");
        ImportRequest req = new ImportRequest(files(a, b), "合", "staging", null, List.of("标签A"));
        GroupApplyResult result = service.applyImport(req);

        assertEquals(2, result.movedFiles());
        assertTrue(Files.exists(staging().resolve("合.mp4")));
        assertTrue(Files.exists(staging().resolve("合.lrc")));
        assertFalse(Files.exists(a), "源文件是搬走不是拷贝");
        // 标签按落盘后的主名写（喊麦的标签键就是主名）
        verify(store).replaceTags(eq("合"), anyList());
        // ① 库里还没有行，不该去动镜像
        verify(store, never()).editGroup(any(), any(), any(), any(), any());
    }

    @Test
    public void applyWithoutTagsDoesNotTouchTagTable() throws IOException {
        Path a = touch("下载/甲.mp4");
        service.applyImport(new ImportRequest(files(a), null, "staging", null, null));
        // tags == null = 这次不动标签。写空数组才是「清空」
        verify(store, never()).replaceTags(any(), anyList());
    }

    @Test
    public void applyRevalidatesAndThrowsWhenBlocked() throws IOException {
        // 计划做好之后磁盘变了（目标冒出同名文件）：apply 重跑 plan 必须抛，不许静默跳过
        Path a = touch("下载/甲.mp4");
        Files.writeString(staging().resolve("甲.mp4"), "冒出来的");
        assertThrows(IllegalStateException.class,
                () -> service.applyImport(new ImportRequest(files(a), null, "staging", null, null)));
    }

    // ------------------------------------------------------------------
    // ② 列表式修改：定位 + 计划
    // ------------------------------------------------------------------

    /** 在已归档分区里摆一组（甲.mp4 + 甲.lrc） */
    private void archivedGroup() throws IOException {
        Files.writeString(archived("#9超赞").resolve("甲.mp4"), "x");
        Files.writeString(archived("#9超赞").resolve("甲.lrc"), "x");
    }

    private static EditFormRequest editReq(EditFormGroup group, String newMainName, Integer score,
                                           List<String> tags, List<ImportFile> extra) {
        return new EditFormRequest(List.of(group), newMainName, score, tags, extra);
    }

    private static EditFormGroup editGroup(String partition, String mainName,
                                           EditFormFile... files) {
        return new EditFormGroup(partition, mainName, List.of(files));
    }

    @Test
    public void editFormRenamesWholeGroup() throws IOException {
        archivedGroup();
        ImportPlan plan = service.planEditForm(editReq(
                editGroup("#9超赞", "甲", new EditFormFile("甲.mp4", null), new EditFormFile("甲.lrc", null)),
                "乙", null, null, null));
        assertFalse(plan.plan().blocked(), plan.plan().firstBlockedReason());
        assertEquals("EDIT", plan.plan().action());
        assertEquals(2, plan.plan().moves().size());
        assertEquals(archived("#9超赞").resolve("乙.mp4").toString(),
                plan.plan().moves().get(0).toPath());
    }

    @Test
    public void editFormMissingGroupBlocked() throws IOException {
        ImportPlan plan = service.planEditForm(editReq(
                editGroup("#9超赞", "不存在", new EditFormFile("不存在.mp4", null)),
                null, null, null, null));
        assertTrue(plan.plan().blocked());
        assertTrue(plan.plan().firstBlockedReason().contains("已经不在了"),
                plan.plan().firstBlockedReason());
    }

    @Test
    public void editFormMissingFileBlocked() throws IOException {
        archivedGroup();
        // 页面上的清单是旧快照：少搬一个文件正是「组裂开」的成因，一条对不上就整批不执行
        ImportPlan plan = service.planEditForm(editReq(
                editGroup("#9超赞", "甲", new EditFormFile("甲.mp4", null),
                        new EditFormFile("甲.wav", null)),
                null, null, null, null));
        assertTrue(plan.plan().blocked());
        assertTrue(plan.plan().firstBlockedReason().contains("甲.wav"),
                plan.plan().firstBlockedReason());
    }

    @Test
    public void editFormEmptyFileListBlocked() throws IOException {
        archivedGroup();
        ImportPlan plan = service.planEditForm(
                editReq(editGroup("#9超赞", "甲"), null, null, null, null));
        assertTrue(plan.plan().blocked());
        assertTrue(plan.plan().firstBlockedReason().contains("没有给出要处理的文件"),
                plan.plan().firstBlockedReason());
    }

    @Test
    public void editFormMultiGroupWithRenameBlocked() throws IOException {
        archivedGroup();
        EditFormRequest req = new EditFormRequest(
                List.of(editGroup("#9超赞", "甲", new EditFormFile("甲.mp4", null)),
                        editGroup("#9超赞", "乙", new EditFormFile("乙.mp4", null))),
                "合", null, null, null);
        ImportPlan plan = service.planEditForm(req);
        assertTrue(plan.plan().blocked());
        assertTrue(plan.plan().firstBlockedReason().contains("只有一个文件组"),
                plan.plan().firstBlockedReason());
    }

    @Test
    public void editFormScoreMovesToPartition() throws IOException {
        Files.writeString(staging().resolve("甲.mp4"), "x");
        ImportPlan plan = service.planEditForm(editReq(
                editGroup("", "甲", new EditFormFile("甲.mp4", null)),
                null, 9, null, null));
        assertFalse(plan.plan().blocked(), plan.plan().firstBlockedReason());
        assertEquals(1, plan.plan().moves().size());
        assertEquals(archived("#9超赞").resolve("甲.mp4").toString(),
                plan.plan().moves().get(0).toPath());
    }

    @Test
    public void editFormOnlyTagsHasNoMoves() throws IOException {
        archivedGroup();
        ImportPlan plan = service.planEditForm(editReq(
                editGroup("#9超赞", "甲", new EditFormFile("甲.mp4", null), new EditFormFile("甲.lrc", null)),
                null, null, List.of("标签A"), null));
        assertFalse(plan.plan().blocked(), plan.plan().firstBlockedReason());
        assertTrue(plan.plan().moves().isEmpty(), "位置和名字都没变，不该有任何搬动");
        assertEquals("EDIT", plan.plan().action());
    }

    @Test
    public void editFormExtraFilesAppended() throws IOException {
        Files.writeString(staging().resolve("甲.mp4"), "x");
        Path extra = touch("外部/甲.mp3");
        ImportPlan plan = service.planEditForm(editReq(
                editGroup("", "甲", new EditFormFile("甲.mp4", null)),
                null, null, null, files(extra)));
        assertFalse(plan.plan().blocked(), plan.plan().firstBlockedReason());
        assertEquals(1, plan.plan().moves().size(), "只在盘上的那一个没有动");
        assertEquals(staging().resolve("甲.mp3").toString(), plan.plan().moves().get(0).toPath());
    }

    @Test
    public void editFormExtraFileFollowsTheGroupName() throws IOException {
        Files.writeString(staging().resolve("甲.mp4"), "x");
        // 名字与这一组不同（真实场景：补一个别人给的歌词）
        Path extra = touch("外部/乙.lrc");
        // 补进来的文件**跟着这一组走**：目标名是这一组的主名，不是它自己的。
        // 若让它回落成自己的主名，这次提交就成了「两组」，撞上「一次只能处理一组」——
        // 而用户明明只是往这一组里补个歌词，改名叫什么不该是他要操心的事
        ImportPlan plan = service.planEditForm(editReq(
                editGroup("", "甲", new EditFormFile("甲.mp4", null)),
                null, null, null, files(extra)));
        assertFalse(plan.plan().blocked(), plan.plan().firstBlockedReason());
        assertEquals(1, plan.plan().moves().size(), "只在盘上的那一个没有动");
        assertEquals(staging().resolve("甲.lrc").toString(), plan.plan().moves().get(0).toPath());
    }

    @Test
    public void editFormExtraFileAlsoFollowsTheNewName() throws IOException {
        Files.writeString(staging().resolve("甲.mp4"), "x");
        Path extra = touch("外部/乙.lrc");
        // 改了全名时，补进来的也跟着新名 —— 一次提交里只有一个主名
        ImportPlan plan = service.planEditForm(editReq(
                editGroup("", "甲", new EditFormFile("甲.mp4", null)),
                "新名", null, null, files(extra)));
        assertFalse(plan.plan().blocked(), plan.plan().firstBlockedReason());
        // 顺序取决于分组（两组不同源目录），这里只钉「都落到新名上」
        assertEquals(2, plan.plan().moves().size());
        assertTrue(plan.plan().moves().stream()
                .anyMatch(m -> m.toPath().equals(staging().resolve("新名.lrc").toString())));
        assertTrue(plan.plan().moves().stream()
                .anyMatch(m -> m.toPath().equals(staging().resolve("新名.mp4").toString())));
    }

    // ------------------------------------------------------------------
    // ② 列表式修改：剔除与库侧回写
    // ------------------------------------------------------------------

    @Test
    public void editFormExcludeMovesToRedundant() throws IOException {
        archivedGroup();
        when(store.markFileRedundant(any(), any(), any(), any())).thenReturn(true);
        EditFormGroup group = editGroup("#9超赞", "甲",
                new EditFormFile("甲.mp4", null), new EditFormFile("甲.lrc", Boolean.TRUE));
        ImportPlan plan = service.planEditForm(editReq(group, null, null, null, null));
        assertFalse(plan.plan().blocked(), plan.plan().firstBlockedReason());
        assertEquals(1, plan.plan().moves().size());
        FileMove move = plan.plan().moves().get(0);
        assertEquals("甲.lrc", move.fileName());
        assertEquals(service.redundantDir().resolve("甲.lrc").toString(), move.toPath());

        GroupApplyResult result = service.applyEditForm(editReq(group, null, null, null, null));
        assertEquals(1, result.movedFiles());
        assertTrue(Files.exists(service.redundantDir().resolve("甲.lrc")));
        assertFalse(Files.exists(archived("#9超赞").resolve("甲.lrc")));
        // 留下的那个文件一个都没动
        assertTrue(Files.exists(archived("#9超赞").resolve("甲.mp4")));
        // 库那侧：剔除行标成冗余（shout_id = 0）
        verify(store).markFileRedundant(eq("#9超赞"), eq("甲"), eq("甲.lrc"), eq("甲.lrc"));
    }

    @Test
    public void excludeAllFilesIsAllowed() throws IOException {
        // 把一组全剔除 = 解散到冗余，是合法提交（不是「什么都没给」）
        archivedGroup();
        when(store.markFileRedundant(any(), any(), any(), any())).thenReturn(true);
        EditFormGroup group = editGroup("#9超赞", "甲",
                new EditFormFile("甲.mp4", Boolean.TRUE), new EditFormFile("甲.lrc", Boolean.TRUE));
        ImportPlan plan = service.planEditForm(editReq(group, null, null, null, null));
        assertFalse(plan.plan().blocked(), plan.plan().firstBlockedReason());
        assertEquals("EDIT", plan.plan().action());
        assertEquals(2, plan.plan().moves().size());
    }

    @Test
    public void editFormNeverExcludesInStaging() throws IOException {
        // 剔除只对已归档文件（移送冗余根）；待打分区的勾选不生效，文件照常留在原处
        Files.writeString(staging().resolve("甲.mp4"), "x");
        Files.writeString(staging().resolve("甲.lrc"), "x");
        EditFormGroup group = editGroup("", "甲",
                new EditFormFile("甲.mp4", null), new EditFormFile("甲.lrc", Boolean.TRUE));
        ImportPlan plan = service.planEditForm(editReq(group, null, null, null, null));
        assertFalse(plan.plan().blocked(), plan.plan().firstBlockedReason());
        assertTrue(plan.plan().moves().isEmpty(), "待打分区没有冗余根那一说，不该有搬动");
        verify(store, never()).markFileRedundant(any(), any(), any(), any());
    }

    @Test
    public void editFormTagsOrRenameStillTouchesLibrary() throws IOException {
        // 不搬文件也要回写库：shout_file.main_name 与标签都挂在主名上
        archivedGroup();
        when(store.editGroup(any(), any(), any(), any(), any())).thenReturn(true);
        EditFormGroup group = editGroup("#9超赞", "甲",
                new EditFormFile("甲.mp4", null), new EditFormFile("甲.lrc", null));
        GroupApplyResult result = service.applyEditForm(
                editReq(group, null, null, List.of("标签A"), null));
        assertEquals(0, result.movedFiles());
        assertTrue(result.settingMoved());
        verify(store).replaceTags(eq("甲"), anyList());
        verify(store).editGroup(eq("#9超赞"), eq("甲"), eq("#9超赞"), eq(null), eq("甲"));
    }

    @Test
    public void applyEditFormThrowsWhenGroupGone() throws IOException {
        // 提交排队后磁盘变了：执行时必须再验一遍并抛出（任务失败、理由给人看）
        assertThrows(IllegalStateException.class, () -> service.applyEditForm(editReq(
                editGroup("#9超赞", "不存在", new EditFormFile("不存在.mp4", null)),
                null, null, null, null)));
    }

    @Test
    public void anchorIsReadBeforeMoving() throws IOException {
        // 改评分时锚点必须用搬动前的 (旧分区, 旧主名) 定位 —— 搬完旧目录里就没有那些文件了
        Files.writeString(staging().resolve("甲.mp4"), "x");
        when(store.editGroup(any(), any(), any(), any(), any())).thenReturn(true);
        service.applyEditForm(editReq(
                editGroup("", "甲", new EditFormFile("甲.mp4", null)),
                null, 9, null, null));
        verify(store).editGroup(eq(""), eq("甲"), eq("#9超赞"), eq(9), eq("甲"));
    }

    @Test
    public void archiveGateAlsoCoversEditForm() throws IOException {
        properties.setRequireTagsBeforeArchive(true);
        Files.writeString(staging().resolve("甲.mp4"), "x");
        ImportPlan plan = service.planEditForm(editReq(
                editGroup("", "甲", new EditFormFile("甲.mp4", null)),
                null, 9, null, null));
        assertTrue(plan.plan().blocked());
        assertTrue(plan.plan().firstBlockedReason().contains("还没打标签"),
                plan.plan().firstBlockedReason());
    }

    @Test
    public void redundantDirIsDerivedFromArchivedRoot() {
        // 冗余根是派生的 <归档根>\冗余，不是配置项（不新增配置、不动配置页）
        assertNotNull(service.redundantDir());
        assertEquals(root.resolve("成品-喊麦").resolve("冗余"), service.redundantDir());
    }

    @Test
    public void reImportDoesNotTouchLibrary() throws IOException {
        // ① 新导入的组库里还没有行：锚点为空，入库交同步补建（不在这里手写镜像表）
        Path a = touch("下载/甲.mp4");
        service.applyImport(new ImportRequest(files(a), null, "staging", null, null));
        verify(store, never()).editGroup(any(), any(), any(), any(), any());
        verify(store, never()).markFileRedundant(any(), any(), any(), any());
    }

    @Test
    public void groupRefShapeMatchesArchiveService() {
        // 复用 ShoutArchiveService.GroupRef 而不是另立一份 —— 形状变了这里先红
        GroupRef ref = new GroupRef("#9超赞", "甲");
        assertEquals("#9超赞", ref.partition());
        assertEquals("甲", ref.mainName());
    }
}
