package io.github.Nyameph.nyaentworks.song.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import io.github.Nyameph.nyaentworks.common.file.GroupFileOps.FileMove;
import io.github.Nyameph.nyaentworks.common.media.MediaPreviewTokens;
import io.github.Nyameph.nyaentworks.common.media.MediaStreamService;
import io.github.Nyameph.nyaentworks.song.config.SongProperties;
import io.github.Nyameph.nyaentworks.song.entity.SongOriginalSetting;
import io.github.Nyameph.nyaentworks.song.mapper.SongOriginalSettingMapper;
import io.github.Nyameph.nyaentworks.song.service.SongOriginalImportService.ExpandRequest;
import io.github.Nyameph.nyaentworks.song.service.SongOriginalImportService.ExpandResult;
import io.github.Nyameph.nyaentworks.song.service.SongOriginalImportService.ImportFile;
import io.github.Nyameph.nyaentworks.song.service.SongOriginalImportService.ImportRequest;
import io.github.Nyameph.nyaentworks.song.service.SongOriginalImportService.ImportResult;
import io.github.Nyameph.nyaentworks.song.service.SongOriginalImportService.OriginalImportPlan;
import io.github.Nyameph.nyaentworks.song.service.SongOriginalImportService.PickedFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * {@code import-plan} 与 {@code import}（apply）的行为核对（正文阶段 4）。
 *
 * <p>与 {@code SongOriginalImportExpandTest} 同一套夹具（{@code @TempDir} + mock，
 * 不连库、不碰 {@code F:\}）。这一份盯的是「预览 → 清单 → 落盘 → 写库」这条链上
 * <b>三处会静默错</b>的地方：
 * <ol>
 *   <li><b>预览的名字与清单里的名字必须逐字相同</b>（{@link #plan_previewNamesMatchPlanToFileNames}）
 *       —— 不同就出现「页面上说改成 A、盘上变成 B」；</li>
 *   <li><b>写库写的是最终名，不是旧名</b>（{@code apply} 那两个）
 *       —— 写旧名的症状是「盘上名字变了，下次扫描找不到它」；</li>
 *   <li><b>有一条不能落就整批不执行</b>（{@link #plan_badExtRow_blocksWholeBatch}）
 *       —— 只看 READY 那几条会把不合格的文件静默丢掉。</li>
 * </ol>
 */
class SongOriginalImportPlanTest {

    @TempDir
    Path tmp;

    private Path templateRoot;
    private Path onlyRoot;
    private Path outside;

    private SongTemplateService templateService;
    private SongSettingService settingService;
    private SongOriginalSettingMapper mapper;
    private SongOriginalImportService service;

    private final Map<String, String> assigned = new LinkedHashMap<>();
    /** setTypeFile 的两个重载收到的 (类别=文件名) —— 两路写库都从这里看 */
    private final List<String> written = new ArrayList<>();

    /**
     * {@code LambdaUpdateWrapper} 的列名靠 MyBatis-Plus 的 lambda 缓存反查，那个缓存平时
     * 由容器扫描 mapper 时填。这里不连库，得手工装上表信息，否则 {@code set(...)} 会抛
     * 「can not find lambda cache for this entity」（同样做法的还有 {@code RhymeWordClassTest}）。
     */
    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                SongOriginalSetting.class);
    }

    @BeforeEach
    void setUp() throws IOException {
        templateRoot = Files.createDirectories(tmp.resolve("template"));
        onlyRoot = Files.createDirectories(tmp.resolve("only"));
        outside = Files.createDirectories(tmp.resolve("outside"));

        SongProperties properties = new SongProperties();
        properties.setTemplateDir(templateRoot.toString());
        properties.setOnlyOriginalDir(onlyRoot.toString());

        templateService = Mockito.mock(SongTemplateService.class);
        settingService = Mockito.mock(SongSettingService.class);
        mapper = Mockito.mock(SongOriginalSettingMapper.class);
        Mockito.when(settingService.allOriginals()).thenReturn(List.of());
        Mockito.when(templateService.getTypeFile(any(), anyString()))
                .thenAnswer(inv -> assigned.get(inv.getArgument(1, String.class)));
        Mockito.doAnswer(inv -> {
            written.add(inv.getArgument(1, String.class) + "=" + inv.getArgument(2, String.class));
            return null;
        }).when(templateService).setTypeFile(any(SongOriginalSetting.class), anyString(), anyString());
        Mockito.doAnswer(inv -> {
            written.add(inv.getArgument(1, String.class) + "=" + inv.getArgument(2, String.class));
            return null;
        }).when(templateService)
                .setTypeFile(any(LambdaUpdateWrapper.class), anyString(), anyString());

        service = new SongOriginalImportService(properties, templateService, settingService, mapper,
                new MediaStreamService(List.of(), new MediaPreviewTokens()));
    }

    // ==================== 夹具 ====================

    private Path put(String name) throws IOException {
        return Files.writeString(outside.resolve(name), "x", StandardCharsets.UTF_8);
    }

    private Path putIn(Path dir, String name, String text) throws IOException {
        return Files.writeString(Files.createDirectories(dir).resolve(name), text,
                StandardCharsets.UTF_8);
    }

    private ImportRequest newRequest(String rawName, String artist, List<ImportFile> files) {
        return new ImportRequest(rawName, artist, null, true, files, List.of(), true, null,
                true, true, false, false);
    }

    private ImportRequest editRequest(String rawName, String artist, List<ImportFile> files) {
        return new ImportRequest(rawName, artist, null, false, files, List.of(), true, null,
                true, true, false, false);
    }

    private ExpandRequest expandRequest(String rawName, String artist, List<ImportFile> files) {
        Map<String, String> types = new LinkedHashMap<>();
        List<String> paths = new ArrayList<>();
        for (ImportFile file : files) {
            paths.add(file.path());
            types.put(Path.of(file.path()).getFileName().toString(), file.type());
        }
        return new ExpandRequest(rawName, artist, null, true, paths, true, types, null, true);
    }

    private SongOriginalSetting row(String rawName, String artist) {
        SongOriginalSetting row = new SongOriginalSetting();
        row.setId(7L);
        row.setRawName(rawName);
        row.setArtist(artist);
        return row;
    }

    private FileMove move(OriginalImportPlan plan, String fileName) {
        return plan.plan().moves().stream().filter(m -> m.fileName().equals(fileName))
                .findFirst().orElseThrow(() -> new AssertionError("清单里没有 " + fileName));
    }

    // ==================== 清单 ====================

    @Test
    void plan_previewNamesMatchPlanToFileNames() throws IOException {
        // 这条是整个功能的命门：预览的名字与实际落盘的名字必须逐字一致
        List<ImportFile> files = List.of(
                new ImportFile(put("原.wav").toString(), "original"),
                new ImportFile(put("伴.MP3").toString(), "accompaniment"),
                new ImportFile(put("曲.mid").toString(), "mid"),
                new ImportFile(put("工.svp").toString(), "svp"));
        ExpandResult preview = service.expand(expandRequest("乙", "甲", files));
        OriginalImportPlan plan = service.plan(newRequest("乙", "甲", files));

        assertFalse(plan.plan().blocked(), plan.plan().firstBlockedReason());
        assertEquals(4, plan.plan().moves().size());
        for (PickedFile picked : preview.files()) {
            assertEquals(picked.toFileName(), move(plan, picked.fileName()).toFileName(),
                    picked.fileName() + " 的预览名与清单名不一致");
        }
        assertEquals("甲 - 乙.wav", move(plan, "原.wav").toFileName());
        assertEquals("[伴奏] 乙.MP3", move(plan, "伴.MP3").toFileName(),
                "后缀大小写照原样");
        assertEquals("[BPM=？] 乙.mid", move(plan, "曲.mid").toFileName());
    }

    @Test
    void plan_sourcesInDifferentDirs_getTheirOwnMoves() throws IOException {
        // 分组（源目录, 目标主名）错了的症状：from 指向另一个目录 → planMoves 判「源文件不在了」
        Path second = Files.createDirectories(tmp.resolve("outside2"));
        Path other = Files.writeString(second.resolve("词.lrc"), "x", StandardCharsets.UTF_8);
        List<ImportFile> files = List.of(
                new ImportFile(put("伴.mp3").toString(), "accompaniment"),
                new ImportFile(other.toString(), "lyric"));

        OriginalImportPlan plan = service.plan(newRequest("乙", "甲", files));
        assertFalse(plan.plan().blocked(), plan.plan().firstBlockedReason());
        assertEquals(2, plan.plan().moves().size());
        assertEquals(outside.toRealPath().toString(),
                Path.of(move(plan, "伴.mp3").fromPath()).toRealPath().getParent().toString());
        assertEquals(second.toRealPath().toString(),
                Path.of(move(plan, "词.lrc").fromPath()).toRealPath().getParent().toString());
        // 两个文件来自不同组，却都落到同一个目标目录
        assertEquals(onlyRoot.resolve("乙_甲").toString(),
                Path.of(move(plan, "词.lrc").toPath()).getParent().toString());
    }

    @Test
    void plan_existingFileAlreadyInPlace_isNotAMove() throws IOException {
        // 编辑那一路把已指派、名字也合规的文件也带进清单 —— 它没有要搬的，进不了 moves。
        // 这不是失败：只改一个确认位、一个文件都不动，照样要落库
        Path dir = Files.createDirectories(templateRoot.resolve("乙_甲"));
        SongOriginalSetting row = row("乙", "甲");
        assigned.put("accompaniment", "[伴奏] 乙.mp3");
        putIn(dir, "[伴奏] 乙.mp3", "x");
        Mockito.when(settingService.allOriginals()).thenReturn(List.of(row));
        Mockito.when(templateService.templateDirsFor("乙", "甲")).thenReturn(List.of(dir));
        Mockito.when(templateService.resolveFile("乙", "甲", "[伴奏] 乙.mp3"))
                .thenReturn(dir.resolve("[伴奏] 乙.mp3"));

        OriginalImportPlan plan = service.plan(editRequest("乙", "甲", List.of()));
        assertFalse(plan.plan().blocked(), plan.plan().firstBlockedReason());
        assertTrue(plan.plan().moves().isEmpty(), plan.plan().moves().toString());
        assertFalse(plan.isNewOriginal());
    }

    @Test
    void plan_badExtRow_blocksWholeBatch() throws IOException {
        // 一个不合格的文件不能让它自己静默消失、其余的照落
        List<ImportFile> files = List.of(
                new ImportFile(put("伴.mp3").toString(), "accompaniment"),
                new ImportFile(put("乙.mp4").toString(), "accompaniment"));
        OriginalImportPlan plan = service.plan(newRequest("乙", "甲", files));
        assertTrue(plan.plan().blocked());
        assertTrue(plan.plan().firstBlockedReason().contains("伴奏"),
                plan.plan().firstBlockedReason());
        assertFalse(Files.exists(onlyRoot.resolve("乙_甲")), "拦下了就不该建目录");
    }

    @Test
    void plan_assignFileAlreadyInItsOwnDir_renamesInPlace() throws IOException {
        // 2026-09-25：把「已经在原曲目录里、只是没被指派」的文件指派给一类。
        // 它不用搬家，勾着改名时也只是在同一层里改个名（from 与 to 同一父目录）
        Path dir = Files.createDirectories(templateRoot.resolve("乙_甲"));
        Path loose = putIn(dir, "伴奏 随便叫的.mp3", "x");
        Mockito.when(templateService.templateDirsFor("乙", "甲")).thenReturn(List.of(dir));

        OriginalImportPlan plan = service.plan(newRequest("乙", "甲",
                List.of(new ImportFile(loose.toString(), "accompaniment"))));

        assertFalse(plan.plan().blocked(), plan.plan().firstBlockedReason());
        FileMove only = move(plan, "伴奏 随便叫的.mp3");
        assertEquals("[伴奏] 乙.mp3", only.toFileName());
        assertEquals(dir.toString(), Path.of(only.toPath()).getParent().toString(),
                "就地改名：不许把它挪到别的目录去");
    }

    @Test
    void apply_assignFileInPlaceWithRenameOff_onlyWritesTheLibrary() throws IOException {
        // 不勾改名时这条路上一个文件都不该动 —— planMoves 会把「源与目标同一个路径」
        // 那一行标成「目标已存在同名文件」（那正是它自己），全靠 planOf 把 from == to
        // 的行丢掉。丢不掉的症状是「只想改库，却报目标已存在、整批拒跑」
        Path dir = Files.createDirectories(templateRoot.resolve("乙_甲"));
        Path loose = putIn(dir, "伴奏 随便叫的.mp3", "x");
        SongOriginalSetting row = row("乙", "甲");
        Mockito.when(settingService.allOriginals()).thenReturn(List.of(row));
        Mockito.when(templateService.templateDirsFor("乙", "甲")).thenReturn(List.of(dir));

        ImportRequest request = new ImportRequest("乙", "甲", 7L, false,
                List.of(new ImportFile(loose.toString(), "accompaniment")), List.of(),
                false, null, true, true, false, false);
        OriginalImportPlan plan = service.plan(request);
        assertFalse(plan.plan().blocked(), plan.plan().firstBlockedReason());
        assertTrue(plan.plan().moves().isEmpty(), plan.plan().moves().toString());

        ImportResult result = service.apply(request);
        assertEquals(0, result.movedFiles());
        assertTrue(Files.exists(loose), "盘上一个文件都不许动");
        assertEquals(List.of("accompaniment=伴奏 随便叫的.mp3"), written,
                "写库写的是当前这个文件名（没改名）");
    }

    @Test
    void plan_illegalCharInRawName_blocksWholeBatch() throws IOException {
        // 名字里有建不出来的字符 → 整批不执行（与「有一条 blockedReason 就整批不执行」同一口径）。
        // 原因里要写清是哪一格：「乙?」这个名字在盘上根本建不出来
        List<ImportFile> files = List.of(
                new ImportFile(put("伴.mp3").toString(), "accompaniment"));
        OriginalImportPlan plan = service.plan(newRequest("乙?", "甲", files));

        assertTrue(plan.plan().blocked());
        assertTrue(plan.plan().firstBlockedReason().startsWith("原曲名 ——"),
                plan.plan().firstBlockedReason());
        try (var left = Files.list(onlyRoot)) {
            assertEquals(0, left.count(), "拦下了就一个目录都不该建");
        }
    }

    @Test
    void plan_illegalCharInArtist_blocksWithArtistNamed() throws IOException {
        List<ImportFile> files = List.of(
                new ImportFile(put("伴.mp3").toString(), "accompaniment"));
        OriginalImportPlan plan = service.plan(newRequest("乙", "甲:乙", files));

        assertTrue(plan.plan().blocked());
        assertTrue(plan.plan().firstBlockedReason().startsWith("歌手 ——"),
                plan.plan().firstBlockedReason());
    }

    @Test
    void plan_noTypeRow_blocksWithChooseHint() throws IOException {
        List<ImportFile> files = List.of(new ImportFile(put("乙.zip").toString(), null));
        OriginalImportPlan plan = service.plan(newRequest("乙", "甲", files));
        assertTrue(plan.plan().blocked());
        assertNotNull(plan.plan().firstBlockedReason());
    }

    @Test
    void plan_noFiles_blocked() {
        OriginalImportPlan plan = service.plan(newRequest("乙", "甲", List.of()));
        assertTrue(plan.plan().blocked());
        assertEquals("还没有选文件", plan.plan().firstBlockedReason());
    }

    @Test
    void plan_duplicateOfLibraryRow_blocked() throws IOException {
        Mockito.when(settingService.allOriginals()).thenReturn(List.of(row("乙", "甲")));
        List<ImportFile> files = List.of(
                new ImportFile(put("乙.mp3").toString(), "original"));
        OriginalImportPlan plan = service.plan(newRequest("乙", "甲", files));
        assertTrue(plan.plan().blocked());
        assertTrue(plan.plan().firstBlockedReason().contains("编辑"),
                plan.plan().firstBlockedReason());
    }

    @Test
    void plan_svpWarningsAreCarriedToConfirmPage() throws IOException {
        Path svp = putIn(outside, "工.svp",
                "{\"tracks\":[{\"name\":\"伴奏\",\"mainRef\":{\"audio\":"
                        + "{\"filename\":\"乙伴奏.wav\"}}}]}");
        List<ImportFile> files = List.of(
                new ImportFile(svp.toString(), "svp"),
                new ImportFile(put("乙伴奏.wav").toString(), "accompaniment"));
        OriginalImportPlan plan = service.plan(newRequest("乙", "甲", files));
        assertEquals(1, plan.svpWarnings().size(), "确认页上要看到这条提醒");
        assertTrue(plan.svpWarnings().get(0).message().contains("[伴奏] 乙.wav"));
    }

    // ==================== 落盘 + 写库 ====================

    @Test
    void apply_new_movesFilesAndInsertsRowWithFinalNames() throws IOException {
        List<ImportFile> files = List.of(
                new ImportFile(put("伴.mp3").toString(), "accompaniment"),
                new ImportFile(put("工.svp").toString(), "svp"));
        ImportResult result = service.apply(newRequest("乙", "甲", files));

        // 有工程文件 → 落模板根（而这两个文件来自同一个源目录，所以只搬不跨目录）
        Path dir = templateRoot.resolve("乙_甲");
        assertTrue(Files.exists(dir.resolve("[伴奏] 乙.mp3")), "盘上要有改名后的文件");
        assertTrue(Files.exists(dir.resolve("[工程] 乙.svp")));
        assertFalse(Files.exists(outside.resolve("伴.mp3")), "源文件要搬走");
        assertEquals(2, result.movedFiles());
        assertEquals(2, result.renamedFiles());
        assertEquals(1, result.created());
        try (var left = Files.list(outside)) {
            assertEquals(0, left.count(), "源目录应当空了");
        }
    }

    @Test
    void apply_new_insertRowCarriesChecksAndEightNames() throws IOException {
        List<ImportFile> files = List.of(
                new ImportFile(put("伴.mp3").toString(), "accompaniment"),
                new ImportFile(put("工.svp").toString(), "svp"));
        service.apply(newRequest("乙", "甲", files));

        ArgumentCaptor<SongOriginalSetting> captor =
                ArgumentCaptor.forClass(SongOriginalSetting.class);
        Mockito.verify(mapper).insert(captor.capture());
        SongOriginalSetting saved = captor.getValue();
        assertEquals("乙", saved.getRawName());
        assertEquals("甲", saved.getArtist());
        assertTrue(saved.getArtistCheck(), "填了作者就算已确认（§0.2 第 7 条）");
        assertEquals(List.of("accompaniment=[伴奏] 乙.mp3", "svp=[工程] 乙.svp"), written,
                "八个字段写的是<最终名>，一个不漏");
    }

    @Test
    void apply_edit_writesFinalNameBackToLibrary() throws IOException {
        // 盘上把已有伴奏改了名，库里也必须跟着改 —— 不改的症状是「下次扫描找不到它」
        Path dir = Files.createDirectories(templateRoot.resolve("乙_甲"));
        SongOriginalSetting row = row("乙", "甲");
        assigned.put("accompaniment", "乙伴奏.mp3");
        putIn(dir, "乙伴奏.mp3", "x");
        Mockito.when(settingService.allOriginals()).thenReturn(List.of(row));
        Mockito.when(templateService.templateDirsFor("乙", "甲")).thenReturn(List.of(dir));
        Mockito.when(templateService.resolveFile("乙", "甲", "乙伴奏.mp3"))
                .thenReturn(dir.resolve("乙伴奏.mp3"));

        service.apply(editRequest("乙", "甲", List.of()));

        assertTrue(Files.exists(dir.resolve("[伴奏] 乙.mp3")), "已有文件也要被改名");
        ArgumentCaptor<LambdaUpdateWrapper<SongOriginalSetting>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        Mockito.verify(mapper).update(any(), captor.capture());
        LambdaUpdateWrapper<SongOriginalSetting> wrapper = captor.getValue();
        assertTrue(wrapper.getSqlSet().contains("artist_check"), wrapper.getSqlSet());
        assertTrue(wrapper.getSqlSet().contains("svp_check"), wrapper.getSqlSet());
        assertEquals(List.of("accompaniment=[伴奏] 乙.mp3"), written);
        Mockito.verify(mapper, Mockito.never()).insert(any(SongOriginalSetting.class));
    }

    @Test
    void apply_edit_keepsConfirmBitsFromRequest() throws IOException {
        Path dir = Files.createDirectories(templateRoot.resolve("乙_甲"));
        SongOriginalSetting row = row("乙", "甲");
        // 一条已指派且名字已合规的文件：清单里没有要搬的，但库侧的确认位仍要写下去
        assigned.put("accompaniment", "[伴奏] 乙.mp3");
        putIn(dir, "[伴奏] 乙.mp3", "x");
        Mockito.when(settingService.allOriginals()).thenReturn(List.of(row));
        Mockito.when(templateService.templateDirsFor("乙", "甲")).thenReturn(List.of(dir));
        Mockito.when(templateService.resolveFile("乙", "甲", "[伴奏] 乙.mp3"))
                .thenReturn(dir.resolve("[伴奏] 乙.mp3"));
        service.apply(new ImportRequest("乙", "甲", null, false, List.of(), List.of(),
                true, null, false, true, true, false));

        ArgumentCaptor<LambdaUpdateWrapper<SongOriginalSetting>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        Mockito.verify(mapper).update(any(), captor.capture());
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(true),
                "四项确认位照请求写（这里 artistCheck=false 也要写下去）");
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(false),
                captor.getValue().getParamNameValuePairs().toString());
    }

    @Test
    void apply_newDuplicateOfLibraryRow_refusesWithoutTouchingDisk() throws IOException {
        // plan 到 apply 之间可能隔了一夜（异步任务）：库里刚多出来的那一条必须当场拦住
        Mockito.when(settingService.allOriginals()).thenReturn(List.of(row("乙", "甲")));
        Path source = put("乙.mp3");
        assertThrows(IllegalStateException.class, () -> service.apply(newRequest("乙", "甲",
                List.of(new ImportFile(source.toString(), "original")))));

        assertTrue(Files.exists(source), "拦下了就一个文件都不能搬");
        Mockito.verify(mapper, Mockito.never()).insert(any(SongOriginalSetting.class));
    }

    @Test
    void apply_blockedPlanRefuses() throws IOException {
        List<ImportFile> files = List.of(
                new ImportFile(put("乙.mp4").toString(), "accompaniment"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.apply(newRequest("乙", "甲", files)));
        assertTrue(e.getMessage().contains("伴奏"), e.getMessage());
        assertTrue(Files.exists(outside.resolve("乙.mp4")), "什么都没搬");
    }

    @Test
    void apply_illegalCharInArtist_refusesWithoutTouchingDisk() throws IOException {
        // 歌手格里的「:」也是同一处判据拦的：一个文件都不搬、库里一行都不写。
        // 这一条挡在**执行那一刻**，所以就算按钮那边被绕过也安全
        Path source = put("伴.mp3");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.apply(newRequest("乙", "甲:乙",
                        List.of(new ImportFile(source.toString(), "accompaniment")))));

        assertTrue(e.getMessage().startsWith("歌手 ——"), e.getMessage());
        assertTrue(Files.exists(source), "拦下了就一个文件都不能搬");
        Mockito.verify(mapper, Mockito.never()).insert(any(SongOriginalSetting.class));
    }

    @Test
    void apply_labelsOnlyEdit_writesLibraryWithoutMovingAnything() throws IOException {
        // 编辑时只勾了一个确认位、一个文件都不动：moves 为空不是失败
        Path dir = Files.createDirectories(templateRoot.resolve("乙_甲"));
        SongOriginalSetting row = row("乙", "甲");
        assigned.put("accompaniment", "[伴奏] 乙.mp3");
        putIn(dir, "[伴奏] 乙.mp3", "x");
        Mockito.when(settingService.allOriginals()).thenReturn(List.of(row));
        Mockito.when(templateService.templateDirsFor("乙", "甲")).thenReturn(List.of(dir));
        Mockito.when(templateService.resolveFile("乙", "甲", "[伴奏] 乙.mp3"))
                .thenReturn(dir.resolve("[伴奏] 乙.mp3"));

        ImportResult result = service.apply(editRequest("乙", "甲", List.of()));
        assertEquals(0, result.movedFiles());
        assertEquals(0, result.created());
        Mockito.verify(mapper).update(any(), any());
    }
}
