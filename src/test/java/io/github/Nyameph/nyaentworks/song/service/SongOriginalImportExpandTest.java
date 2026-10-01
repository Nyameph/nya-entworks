package io.github.Nyameph.nyaentworks.song.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import io.github.Nyameph.nyaentworks.common.media.MediaPreviewTokens;
import io.github.Nyameph.nyaentworks.common.media.MediaStreamService;
import io.github.Nyameph.nyaentworks.song.config.SongProperties;
import io.github.Nyameph.nyaentworks.song.entity.SongOriginalSetting;
import io.github.Nyameph.nyaentworks.song.mapper.SongOriginalSettingMapper;
import io.github.Nyameph.nyaentworks.song.service.SongOriginalImportService.ExpandRequest;
import io.github.Nyameph.nyaentworks.song.service.SongOriginalImportService.ExpandResult;
import io.github.Nyameph.nyaentworks.song.service.SongOriginalImportService.PickKind;
import io.github.Nyameph.nyaentworks.song.service.SongOriginalImportService.PickedFile;
import io.github.Nyameph.nyaentworks.song.service.SongOriginalImportService.SvpSeverity;
import io.github.Nyameph.nyaentworks.song.service.SongOriginalImportService.TypeOption;

import java.io.IOException;
import java.math.BigDecimal;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * {@code import-expand} 的行为核对（正文阶段 3）。
 *
 * <p><b>不连库、不碰 {@code F:\}</b>：两个受管根指向 {@code @TempDir} 下的空目录，
 * {@code SongTemplateService} / {@code SongSettingService} 全是 mock，
 * 「库里已有的行」用手搓的实体喂进 {@code allOriginals()}。
 *
 * <p>盯的是三件最容易静默错的事：
 * <ol>
 *   <li><b>名字</b>——预览的名字必须就是落盘的名字（八种拼法逐条钉；大小写那一处
 *       借 {@code .MP3} 钉住「计划与实际同名」）；</li>
 *   <li><b>闸门</b>——类别与扩展名不符必须挡下（挡不住就是静默归错类）；</li>
 *   <li><b>目标根</b>——已有目录不许搬家、全新目录按有没有工程文件定根。</li>
 * </ol>
 */
class SongOriginalImportExpandTest {

    @TempDir
    Path tmp;

    private Path templateRoot;
    private Path onlyRoot;
    private Path outside;

    private SongTemplateService templateService;
    private SongSettingService settingService;
    private SongOriginalImportService service;

    /** 库里已经有的那些「指派」（类别 → 文件名），mock 的 getTypeFile 读它 */
    private final Map<String, String> assigned = new LinkedHashMap<>();

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
        Mockito.when(settingService.allOriginals()).thenReturn(List.of());
        Mockito.when(templateService.getTypeFile(any(), anyString()))
                .thenAnswer(inv -> assigned.get(inv.getArgument(1, String.class)));

        // 媒体服务用真的（不 mock）：它没有受管根也没有别的依赖，`previewUrl` 于是
        // 只做「是不是音视频 + 在不在盘上 + 签个临时预览令牌」三件事 —— 播放那两列
        // 就能在纯单测里逐字核对，不必为了断言它去搭一个假的 URL 拼装
        service = new SongOriginalImportService(properties, templateService, settingService,
                Mockito.mock(SongOriginalSettingMapper.class),
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

    /** 一个 svp，tracks 里每条给 (音轨名, 引用的文件名) */
    private String svpText(String... trackAndRef) {
        StringBuilder text = new StringBuilder("{\"tracks\":[");
        for (int i = 0; i + 1 < trackAndRef.length; i += 2) {
            if (i > 0) {
                text.append(',');
            }
            text.append("{\"name\":\"").append(trackAndRef[i])
                    .append("\",\"mainRef\":{\"audio\":{\"filename\":\"")
                    .append(trackAndRef[i + 1]).append("\",\"duration\":1.0}}}");
        }
        return text.append("]}").toString();
    }

    private ExpandRequest req(String rawName, String artist, boolean isNew, boolean rename,
                              Map<String, String> types, Path... paths) {
        List<String> list = new ArrayList<>();
        for (Path path : paths) {
            list.add(path.toString());
        }
        return new ExpandRequest(rawName, artist, null, isNew, list, rename, types, null,
                true);
    }

    private Map<String, String> types(String... nameAndType) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < nameAndType.length; i += 2) {
            map.put(nameAndType[i], nameAndType[i + 1]);
        }
        return map;
    }

    private PickedFile pick(ExpandResult result, String fileName) {
        return result.files().stream().filter(f -> f.fileName().equals(fileName))
                .findFirst().orElseThrow(() -> new AssertionError("预览里没有 " + fileName));
    }

    /** 一行已有原曲（编辑那一路用） */
    private SongOriginalSetting row(String rawName, String artist) {
        SongOriginalSetting row = new SongOriginalSetting();
        row.setId(7L);
        row.setRawName(rawName);
        row.setArtist(artist);
        return row;
    }

    // ==================== 目标目录 ====================

    @Test
    void expand_blankRawName_blocked() throws IOException {
        ExpandResult result = service.expand(req("  ", "甲", true, true,
                types("乙.mp3", "original"), put("乙.mp3")));
        assertEquals("原曲名要填", result.blockedReason());
    }

    @Test
    void expand_illegalCharInRawName_blocksAndNamesTheField() throws IOException {
        // 「?」这样的名字在盘上建不出来。预览这一趟就要说清是**哪一格**、哪个字符 ——
        // 页面上那句红字与按钮灰着都靠它（判定在后端，前端不维护第二份字符表）
        ExpandResult result = service.expand(req("乙?", "甲", true, true,
                types("乙.mp3", "original"), put("乙.mp3")));
        assertTrue(result.blockedReason().startsWith("原曲名 ——"), result.blockedReason());
        assertTrue(result.blockedReason().contains("「?」"), result.blockedReason());
        assertNull(result.targetDir(), "名字不合法就不该算目标目录（那是个建不出来的路径）");
    }

    @Test
    void expand_illegalCharInArtist_blocksAndNamesTheField() throws IOException {
        // 歌手也进名字（目录是 原曲名_歌手、original 是 甲 - 乙），所以两格同一个判据
        ExpandResult result = service.expand(req("乙", "甲/乙", true, true,
                types("乙.mp3", "original"), put("乙.mp3")));
        assertTrue(result.blockedReason().startsWith("歌手 ——"), result.blockedReason());
        assertTrue(result.blockedReason().contains("「/」"), result.blockedReason());
    }

    @Test
    void expand_trailingDot_blocked() throws IOException {
        // 结尾的点 Windows 会静默去掉：放过去就是「盘上的名字与库里的名字不一样」
        ExpandResult result = service.expand(req("乙.", "甲", true, true,
                types("乙.mp3", "original"), put("乙.mp3")));
        assertTrue(result.blockedReason().contains("结尾"), result.blockedReason());
    }

    @Test
    void expand_dotsInTheMiddle_areJustCharacters() throws IOException {
        // 判据管的是**结尾**的点，名字中间的省略号是普通字符 —— 钉住这一条：
        // 同样的检查写成 contains("..") 就会误拒（2026-09-24 在漫画那边踩过，
        // 见 MangaFolderNameTest）
        ExpandResult result = service.expand(req("なのに...そして", "甲", true, true,
                types("乙.mp3", "original"), put("乙.mp3")));
        assertNull(result.blockedReason());
        assertEquals("甲 - なのに...そして.mp3", pick(result, "乙.mp3").toFileName());
    }

    @Test
    void expand_newOnlyAudio_landsInOnlyOriginalRoot() throws IOException {
        ExpandResult result = service.expand(req("乙", "甲", true, true,
                types("乙.mp3", "original"), put("乙.mp3")));
        assertNull(result.blockedReason());
        assertEquals(onlyRoot.toRealPath().toString(),
                Path.of(result.targetRoot()).toRealPath().toString());
        assertEquals(onlyRoot.resolve("乙_甲").toString(), result.targetDir());
    }

    @Test
    void expand_newWithProjectFile_landsInTemplateRoot() throws IOException {
        ExpandResult result = service.expand(req("乙", "甲", true, true,
                types("乙.svp", "svp", "乙.mp3", "original"), put("乙.svp"), put("乙.mp3")));
        assertEquals(templateRoot.toRealPath().toString(),
                Path.of(result.targetRoot()).toRealPath().toString());
        assertEquals(templateRoot.resolve("乙_甲").toString(), result.targetDir());
    }

    @Test
    void expand_newBlankArtist_dirIsBareRawName() throws IOException {
        ExpandResult result = service.expand(req("乙", "", true, false,
                types("乙.mp3", "original"), put("乙.mp3")));
        assertEquals(onlyRoot.resolve("乙").toString(), result.targetDir());
    }

    @Test
    void expand_existingDir_isKeptInPlace_neverSplit() throws IOException {
        // 已有目录在「仅原曲根」下，而这次要落一个 svp 进去 —— 目录不搬（裁决 5）
        Path dir = Files.createDirectories(onlyRoot.resolve("乙_甲"));
        Mockito.when(templateService.templateDirsFor("乙", "甲")).thenReturn(List.of(dir));
        ExpandResult result = service.expand(req("乙", "甲", true, true,
                types("乙.svp", "svp"), put("乙.svp")));
        assertEquals(dir.toString(), result.targetDir());
        assertEquals(onlyRoot.toString(), result.targetRoot());
    }

    @Test
    void expand_edit_looksUpDirWithLibraryNameAndArtist() throws IOException {
        // 编辑时表单里的名字可能被改过，找目录要用库里那一行的名字
        Path dir = Files.createDirectories(templateRoot.resolve("乙_老"));
        SongOriginalSetting row = row("乙", "老");
        Mockito.when(settingService.allOriginals()).thenReturn(List.of(row));
        Mockito.when(templateService.templateDirsFor("乙", "老")).thenReturn(List.of(dir));
        ExpandResult result = service.expand(req("乙", "老", false, true,
                types("乙.mp3", "original"), put("乙.mp3")));
        assertEquals(dir.toString(), result.targetDir());
        assertFalse(result.isNew());
    }

    // ==================== 改名 ====================

    @Test
    void expand_rename_eightShapes() throws IOException {
        Map<String, String> types = types(
                "原.wav", "original", "词.lrc", "lyric", "伴.mp3", "accompaniment",
                "人.wav", "vocals", "样.mp3", "demo", "样词.lrc", "demoLrc",
                "曲.mid", "mid", "工.svp", "svp");
        ExpandResult result = service.expand(req("乙", "甲", true, true, types,
                put("原.wav"), put("词.lrc"), put("伴.mp3"), put("人.wav"), put("样.mp3"),
                put("样词.lrc"), put("曲.mid"), put("工.svp")));

        assertEquals("甲 - 乙.wav", pick(result, "原.wav").toFileName());
        assertEquals("甲 - 乙.lrc", pick(result, "词.lrc").toFileName());
        assertEquals("[伴奏] 乙.mp3", pick(result, "伴.mp3").toFileName());
        assertEquals("[人声] 乙.wav", pick(result, "人.wav").toFileName());
        assertEquals("[demo] 乙.mp3", pick(result, "样.mp3").toFileName());
        assertEquals("[demo] 乙.lrc", pick(result, "样词.lrc").toFileName());
        // 曲速五级兜底都没取到 → 全角 ？，它过得了 GroupFileOps#requireMainName
        assertEquals("[BPM=？] 乙.mid", pick(result, "曲.mid").toFileName());
        assertEquals("[工程] 乙.svp", pick(result, "工.svp").toFileName());
        assertTrue(result.renameAllowed());
    }

    @Test
    void expand_renamePreservesExtensionCase() throws IOException {
        // 库里有大写 .MP3；预览名必须与实际落盘的名字逐字一致（planMoves 保留原文大小写）
        ExpandResult result = service.expand(req("乙", "甲", true, true,
                types("乙.MP3", "original"), put("乙.MP3")));
        assertEquals("甲 - 乙.MP3", pick(result, "乙.MP3").toFileName());
    }

    @Test
    void expand_renameOff_keepsEveryName() throws IOException {
        ExpandResult result = service.expand(req("乙", "甲", true, false,
                types("乱七八糟.mp3", "原曲"), put("乱七八糟.mp3")));
        // 类型写错了 → 挡下（上面这条顺便确认闸门对「不认识的类别」也拦）
        assertEquals(PickKind.NEED_TYPE, pick(result, "乱七八糟.mp3").kind());

        ExpandResult off = service.expand(req("乙", "甲", true, false,
                types("乱七八糟.mp3", "accompaniment"), put("乱七八糟.mp3")));
        assertEquals("乱七八糟.mp3", pick(off, "乱七八糟.mp3").toFileName());
    }

    @Test
    void expand_blankArtist_renameNotAllowed() throws IOException {
        ExpandResult result = service.expand(req("乙", "", true, true,
                types("伴.mp3", "accompaniment"), put("伴.mp3")));
        assertFalse(result.renameAllowed(), "没填作者就没法按规则拼名，改名要让前端置灰");
        assertEquals("伴.mp3", pick(result, "伴.mp3").toFileName());
    }

    @Test
    void expand_editWithoutArtistCheck_renameNotAllowed() throws IOException {
        SongOriginalSetting row = row("乙", "甲");
        Mockito.when(settingService.allOriginals()).thenReturn(List.of(row));
        ExpandResult result = service.expand(new ExpandRequest("乙", "甲", null, false,
                List.of(put("伴.mp3").toString()), true, types("伴.mp3", "accompaniment"), null,
                false));
        // 「歌手已确认」没勾 → 改名那一栏要让前端置灰（§0.2 第 7 条：改名只在 artist_check=1 时做）
        assertFalse(result.renameAllowed());
        assertEquals("伴.mp3", pick(result, "伴.mp3").toFileName(),
                "没勾确认就不改名，预览里也得是原样");
    }

    @Test
    void expand_editWithArtistCheck_renamesExistingFilesToo() throws IOException {
        // 裁决 7：编辑那一路的改名会连这条原曲<b>已有的</b>文件一起改，所以预览里
        // 已有的文件必须显示「旧名 → 新名」，不能只把新来的那几个改名
        Path dir = Files.createDirectories(templateRoot.resolve("乙_甲"));
        SongOriginalSetting row = row("乙", "甲");
        assigned.put("accompaniment", "乙伴奏.mp3");
        putIn(dir, "乙伴奏.mp3", "x");
        Mockito.when(settingService.allOriginals()).thenReturn(List.of(row));
        Mockito.when(templateService.templateDirsFor("乙", "甲")).thenReturn(List.of(dir));
        Mockito.when(templateService.resolveFile("乙", "甲", "乙伴奏.mp3"))
                .thenReturn(dir.resolve("乙伴奏.mp3"));

        ExpandResult result = service.expand(new ExpandRequest("乙", "甲", null, false,
                List.of(), true, Map.of(), null, true));
        PickedFile existing = pick(result, "乙伴奏.mp3");
        assertTrue(existing.existing());
        assertEquals("[伴奏] 乙.mp3", existing.toFileName());
        assertEquals(dir.toString(), result.targetDir(), "已有目录不搬");
    }

    // ==================== 闸门 ====================

    @Test
    void expand_extGate_allowsAndRejects() throws IOException {
        Path mp4 = put("乙.mp4");
        Path mp3 = put("乙.mp3");
        Path srt = put("乙.srt");
        Path wav = put("乙.wav");
        Path mid = put("乙.mid");
        Path svp = put("乙.svp");

        // 收：视频只开「原曲」这一处；音频/歌词/mid/svp 各自一类
        assertEquals(PickKind.READY, pick(service.expand(req("乙", "甲", true, true,
                types("乙.mp4", "original"), mp4)), "乙.mp4").kind());
        assertEquals(PickKind.READY, pick(service.expand(req("乙", "甲", true, true,
                types("乙.mp3", "original"), mp3)), "乙.mp3").kind());
        assertEquals(PickKind.READY, pick(service.expand(req("乙", "甲", true, true,
                types("乙.srt", "lyric"), srt)), "乙.srt").kind());
        assertEquals(PickKind.READY, pick(service.expand(req("乙", "甲", true, true,
                types("乙.wav", "vocals"), wav)), "乙.wav").kind());
        assertEquals(PickKind.READY, pick(service.expand(req("乙", "甲", true, true,
                types("乙.mid", "mid"), mid)), "乙.mid").kind());
        assertEquals(PickKind.READY, pick(service.expand(req("乙", "甲", true, true,
                types("乙.svp", "svp"), svp)), "乙.svp").kind());

        // 不收：视频不能当伴奏、mp3 不能当工程、mid 不能当歌词
        assertEquals(PickKind.BAD_EXT, pick(service.expand(req("乙", "甲", true, true,
                types("乙.mp4", "accompaniment"), mp4)), "乙.mp4").kind());
        assertEquals(PickKind.BAD_EXT, pick(service.expand(req("乙", "甲", true, true,
                types("乙.mp3", "svp"), mp3)), "乙.mp3").kind());
        assertEquals(PickKind.BAD_EXT, pick(service.expand(req("乙", "甲", true, true,
                types("乙.mid", "lyric"), mid)), "乙.mid").kind());
    }

    @Test
    void expand_extGate_badExtCarriesHumanReason() throws IOException {
        ExpandResult result = service.expand(req("乙", "甲", true, true,
                types("乙.mp4", "accompaniment"), put("乙.mp4")));
        String reason = pick(result, "乙.mp4").blockedReason();
        assertNotNull(reason);
        assertTrue(reason.contains("伴奏"), reason);
        assertTrue(reason.contains("音频"), reason);
    }

    @Test
    void expand_noTypeGiven_guessableStaysNeedTypeWithSuggestion() throws IOException {
        ExpandResult guessable = service.expand(req("乙", "甲", true, true,
                Map.of(), put("乙.lrc")));
        PickedFile lrc = pick(guessable, "乙.lrc");
        assertEquals(PickKind.NEED_TYPE, lrc.kind());
        assertEquals("lyric", lrc.type(), "猜出来的类别当下拉预选值");
        assertNull(lrc.blockedReason(), "有预选值就不用再解释一句");

        // 猜不出来的（classify 对 .zip 兜底 other）连预选都没有
        PickedFile zip = pick(service.expand(req("乙", "甲", true, true, Map.of(),
                put("乙.zip"))), "乙.zip");
        assertEquals(PickKind.NEED_TYPE, zip.kind());
        assertNull(zip.type());
        assertNotNull(zip.blockedReason());
    }

    @Test
    void expand_fileInsideManagedRoot_rejected() throws IOException {
        // 受管根是「本来就该在库里」的意思：模板根下某个原曲目录里的文件不能再导一次。
        // 这一条比「添加歌曲」严一档（那边只判直接父目录），理由见服务里的注释
        Path inTemplate = putIn(templateRoot.resolve("乙_甲"), "乙.mp3", "x");
        Path inOnly = putIn(onlyRoot.resolve("乙_甲"), "乙.mp3", "x");
        assertEquals(PickKind.IN_MANAGED_ROOT, pick(service.expand(req("乙", "甲", true, true,
                types("乙.mp3", "original"), inTemplate)), "乙.mp3").kind());
        assertEquals(PickKind.IN_MANAGED_ROOT, pick(service.expand(req("乙", "甲", true, true,
                types("乙.mp3", "original"), inOnly)), "乙.mp3").kind());
    }

    @Test
    void expand_fileInsideItsOwnDir_isAssignable_notInManagedRoot() throws IOException {
        // 2026-09-25：「已经在原曲目录里、只是没被指派」的文件要能指派 —— 盘外搬进来那条路
        // 对它没用（不用搬），而扫描没认出来的那几类**没有别的路**能指派。
        // 别的原曲目录 / 根下的散文件照旧拦（那才是真的「再导一次」）
        Path own = Files.createDirectories(templateRoot.resolve("乙_甲"));
        Path inOwn = putIn(own, "伴奏 随便叫的.mp3", "x");
        Path otherDir = putIn(templateRoot.resolve("别的_丙"), "伴奏 随便叫的.mp3", "x");
        Path loose = putIn(templateRoot, "散在根上的.mp3", "x");
        Mockito.when(templateService.templateDirsFor("乙", "甲")).thenReturn(List.of(own));

        PickedFile hit = pick(service.expand(req("乙", "甲", true, true,
                types("伴奏 随便叫的.mp3", "accompaniment"), inOwn)), "伴奏 随便叫的.mp3");
        assertEquals(PickKind.READY, hit.kind(), hit.blockedReason());
        assertEquals("[伴奏] 乙.mp3", hit.toFileName(), "指派的同时顺手改成规范名");

        assertEquals(PickKind.IN_MANAGED_ROOT, pick(service.expand(req("乙", "甲", true, true,
                types("伴奏 随便叫的.mp3", "accompaniment"), otherDir)), "伴奏 随便叫的.mp3").kind());
        assertEquals(PickKind.IN_MANAGED_ROOT, pick(service.expand(req("乙", "甲", true, true,
                types("散在根上的.mp3", "accompaniment"), loose)), "散在根上的.mp3").kind());
    }

    @Test
    void expand_listsAssignableUnassignedFilesInTheOriginalDir() throws IOException {
        Path dir = Files.createDirectories(templateRoot.resolve("乙_甲"));
        Path loose = putIn(dir, "伴奏 随便叫的.mp3", "x");
        Path tucked = putIn(dir.resolve("子目录"), "歌词.lrc", "x");   // 递归找得到（collectFiles 递归）
        Path song = putIn(dir, "乙 - 甲.mp3", "x");
        Path junk = putIn(dir, "封面.png", "x");               // 八类都不收 → 不列
        assigned.put("original", "乙 - 甲.mp3");               // 已指派 → 不列
        SongOriginalSetting row = row("乙", "甲");
        Mockito.when(settingService.allOriginals()).thenReturn(List.of(row));
        Mockito.when(templateService.templateDirsFor("乙", "甲")).thenReturn(List.of(dir));
        Mockito.when(templateService.listFilesOfOriginal(row))
                .thenReturn(List.of(song, loose, tucked, junk));

        ExpandResult result = service.expand(req("乙", "甲", false, true, Map.of()));

        List<String> names = result.candidates().stream()
                .map(SongOriginalImportService.CandidateFile::fileName).toList();
        assertEquals(List.of("伴奏 随便叫的.mp3", "歌词.lrc"), names,
                "候选 = 这个目录里还没被指派、且八类收得下的文件");
        var cand = result.candidates().getFirst();
        assertEquals(loose.toString(), cand.path(), "路径要完整：前端拿它当源路径发回来");
        assertEquals("mp3", cand.ext());
        assertEquals(List.of("original", "accompaniment", "vocals", "demo"), cand.types(),
                "能被指派成哪几类由后端算（前端不写第二份扩展名表）");
        assertEquals(List.of("lyric", "demoLrc"), result.candidates().get(1).types());
    }

    @Test
    void expand_newOriginal_hasNoCandidates() throws IOException {
        // 新增那一路还没有目录，候选恒空 —— 前端据此不画那一列下拉
        assertTrue(service.expand(req("乙", "甲", true, true, Map.of())).candidates().isEmpty());
    }

    @Test
    void expand_missingFile_absent() {
        ExpandResult result = service.expand(req("乙", "甲", true, true,
                types("没这个.mp3", "accompaniment"), outside.resolve("没这个.mp3")));
        assertEquals(PickKind.ABSENT, pick(result, "没这个.mp3").kind());
        assertTrue(pick(result, "没这个.mp3").blockedReason().contains("盘上没有"));
    }

    // ==================== 查重 ====================

    @Test
    void expand_twoFilesSameType_secondIsDuplicate() throws IOException {
        // 2026-09-25 起，这一条的诊断出自「一个类别只能放一个文件」那道闸门（服务里 3.5），
        // 而不是原先的按目标名查重 —— 两个文件的目标名本来就都是「[伴奏] 乙.mp3」，
        // 但先说清「这类已经有一个了」比说「目标名撞了」对人更有用（后者听着像改个名就好）。
        // 目标名查重那一道还在，只是同类同名的情形都被这一道先接住了。
        ExpandResult result = service.expand(req("乙", "甲", true, true,
                types("一.mp3", "accompaniment", "二.mp3", "accompaniment"),
                put("一.mp3"), put("二.mp3")));
        assertEquals(PickKind.READY, pick(result, "一.mp3").kind());
        PickedFile second = pick(result, "二.mp3");
        assertEquals(PickKind.DUPLICATE, second.kind());
        assertTrue(second.blockedReason().contains("一个类别只能放一个文件"),
                second.blockedReason());
        assertTrue(second.blockedReason().contains("一.mp3"), second.blockedReason());
    }

    @Test
    void expand_newFileCollidesWithExistingOne_duplicate() throws IOException {
        Path dir = Files.createDirectories(templateRoot.resolve("乙_甲"));
        SongOriginalSetting row = row("乙", "甲");
        row.setAccompanimentFileName("[伴奏] 乙.mp3");
        putIn(dir, "[伴奏] 乙.mp3", "x");
        assigned.put("accompaniment", "[伴奏] 乙.mp3");
        Mockito.when(settingService.allOriginals()).thenReturn(List.of(row));
        Mockito.when(templateService.templateDirsFor("乙", "甲")).thenReturn(List.of(dir));
        Mockito.when(templateService.resolveFile("乙", "甲", "[伴奏] 乙.mp3"))
                .thenReturn(dir.resolve("[伴奏] 乙.mp3"));

        ExpandResult result = service.expand(req("乙", "甲", false, true,
                types("新伴.mp3", "accompaniment"), put("新伴.mp3")));
        // 库里已指派的那一个占着「伴奏」这个位子 —— 2026-09-25 起由 3.5 那道闸门答这一句
        assertEquals(PickKind.DUPLICATE, pick(result, "新伴.mp3").kind());
        // 已有的那一个照旧 READY（不许因为新来的撞了就把已有的也标坏）
        assertEquals(PickKind.READY, pick(result, "[伴奏] 乙.mp3").kind());
    }

    @Test
    void expand_newDuplicateOfLibraryRow_setsDuplicateReason() throws IOException {
        Mockito.when(settingService.allOriginals()).thenReturn(List.of(row("乙", "甲")));
        ExpandResult result = service.expand(req("乙", "甲", true, true,
                types("乙.mp3", "original"), put("乙.mp3")));
        assertNotNull(result.duplicateReason());
        assertTrue(result.duplicateReason().contains("编辑"), result.duplicateReason());
        assertNull(result.blockedReason(), "查重是单独的字段：预览照样画、只置灰提交");
    }

    @Test
    void expand_blankArtistVsLibraryBlankArtist_isDuplicate() throws IOException {
        // 一空一填也算撞（§0.2 第 6 条：artist 为 NULL 时 MySQL 不做唯一性判断）
        Mockito.when(settingService.allOriginals()).thenReturn(List.of(row("乙", null)));
        ExpandResult result = service.expand(req("乙", "甲", true, true,
                types("乙.mp3", "original"), put("乙.mp3")));
        assertNotNull(result.duplicateReason());
    }

    @Test
    void expand_sameNameDifferentArtist_notDuplicate() throws IOException {
        // 不同歌手有同名歌是常事，不能拦
        Mockito.when(settingService.allOriginals()).thenReturn(List.of(row("乙", "丙")));
        ExpandResult result = service.expand(req("乙", "甲", true, true,
                types("乙.mp3", "original"), put("乙.mp3")));
        assertNull(result.duplicateReason());
    }

    @Test
    void expand_editPath_neverRunsDuplicateCheck() throws IOException {
        SongOriginalSetting row = row("乙", "甲");
        Mockito.when(settingService.allOriginals()).thenReturn(List.of(row));
        Mockito.when(templateService.templateDirsFor("乙", "甲"))
                .thenReturn(List.of(Files.createDirectories(templateRoot.resolve("乙_甲"))));
        ExpandResult result = service.expand(req("乙", "甲", false, true,
                types("乙.mp3", "original"), put("乙.mp3")));
        assertNull(result.duplicateReason(), "编辑的就是那一条，不该说自己是重复的");
    }

    @Test
    void expand_editOriginalIdNotInLibrary_blockedWithHint() throws IOException {
        Mockito.when(settingService.allOriginals()).thenReturn(List.of());
        ExpandResult result = service.expand(req("不存在", "甲", false, true,
                types("乙.mp3", "original"), put("乙.mp3")));
        assertNotNull(result.blockedReason());
        assertTrue(result.blockedReason().contains("新增原曲"), result.blockedReason());
    }

    // ==================== svp 提醒 ====================

    @Test
    void expand_svpRefsRenamedFile_reportsRename() throws IOException {
        Path svp = putIn(outside, "工.svp", svpText("伴奏", "乙伴奏.wav"));
        ExpandResult result = service.expand(req("乙", "甲", true, true,
                types("工.svp", "svp", "乙伴奏.wav", "accompaniment"), svp, put("乙伴奏.wav")));

        assertEquals(1, result.svpWarnings().size());
        assertEquals(SvpSeverity.RENAME, result.svpWarnings().get(0).severity());
        assertEquals("伴奏", result.svpWarnings().get(0).trackName());
        // 消息里要有「改名前 → 改名后」两个名字，人要照着去 SynthV 里找
        assertTrue(result.svpWarnings().get(0).message().contains("乙伴奏.wav"));
        assertTrue(result.svpWarnings().get(0).message().contains("[伴奏] 乙.wav"));
        assertTrue(result.svpWarnings().get(0).message().contains("SynthV"));
    }

    @Test
    void expand_svpRefsFileStayingInPlace_notReported() throws IOException {
        Path dir = Files.createDirectories(templateRoot.resolve("乙_甲"));
        Path svp = Files.writeString(dir.resolve("[工程] 乙.svp"),
                svpText("伴奏", "[伴奏] 乙.mp3"), StandardCharsets.UTF_8);
        putIn(dir, "[伴奏] 乙.mp3", "x");
        SongOriginalSetting row = row("乙", "甲");
        row.setSvpFileName("[工程] 乙.svp");
        Mockito.when(settingService.allOriginals()).thenReturn(List.of(row));
        assigned.put("svp", "[工程] 乙.svp");
        Mockito.when(templateService.templateDirsFor("乙", "甲")).thenReturn(List.of(dir));
        Mockito.when(templateService.resolveFile("乙", "甲", "[工程] 乙.svp")).thenReturn(svp);

        // 编辑但一个文件都不进来、也不改名 → 引用一动不动
        ExpandResult result = service.expand(new ExpandRequest("乙", "甲", null, false,
                List.of(), false, Map.of(), null, true));
        assertTrue(result.svpWarnings().isEmpty(), result.svpWarnings().toString());
    }

    @Test
    void expand_svpAbsoluteRefToMovedFile_reportsAbsolutePath() throws IOException {
        Path target = put("乙伴奏.wav");
        // 引用照抄 toRealPath 的形式：服务里比对用的也是 realPath 过的路径
        // （@TempDir 在 Windows 上可能带 8.3 短名，两种写法不是同一个串）
        Path svp = putIn(outside, "工.svp",
                svpText("伴奏", target.toRealPath().toString().replace('\\', '/')));
        ExpandResult result = service.expand(req("乙", "甲", true, true,
                types("工.svp", "svp", "乙伴奏.wav", "accompaniment"), svp, target));

        assertEquals(1, result.svpWarnings().size());
        assertEquals(SvpSeverity.ABSOLUTE_PATH, result.svpWarnings().get(0).severity());
    }

    @Test
    void expand_svpRefsMissingFile_reportsDangling() throws IOException {
        Path svp = putIn(outside, "工.svp", svpText("伴奏", "早就没了.wav"));
        ExpandResult result = service.expand(req("乙", "甲", true, true,
                types("工.svp", "svp"), svp));
        assertEquals(1, result.svpWarnings().size());
        assertEquals(SvpSeverity.DANGLING, result.svpWarnings().get(0).severity());
    }

    @Test
    void expand_svpBlankOrMissingRef_silent() throws IOException {
        // 实测 152 个 svp 里 43 条引用本来就是空的 —— 报出来只会淹没真提醒（附录 C）
        Path svp = putIn(outside, "工.svp",
                "{\"tracks\":[{\"name\":\"空\"},{\"name\":\"空对象\",\"mainRef\":{\"audio\":{}}},"
                        + "{\"name\":\"空串\",\"mainRef\":{\"audio\":{\"filename\":\"\"}}}]}");
        ExpandResult result = service.expand(req("乙", "甲", true, true,
                types("工.svp", "svp"), svp));
        assertTrue(result.svpWarnings().isEmpty(), result.svpWarnings().toString());
    }

    @Test
    void expand_svpBrokenJson_doesNotBreakExpand() throws IOException {
        Path svp = putIn(outside, "工.svp", "这不是 JSON");
        ExpandResult result = service.expand(req("乙", "甲", true, true,
                types("工.svp", "svp"), svp));
        assertNull(result.blockedReason());
        assertEquals(PickKind.READY, pick(result, "工.svp").kind());
        assertTrue(result.svpWarnings().isEmpty());
    }

    // ==================== 曲速 ====================

    @Test
    void expand_bpmFromSiblingSvp_usedInMidName() throws IOException {
        // 同目录 svp 的 tempo ＞ midi 内容 ＞ 库 ＞ 文件名里的数字
        putIn(outside, "别的原曲.svp", "{\"time\":{\"tempo\":[{\"bpm\":84.000084000084}]}}");
        ExpandResult result = service.expand(req("乙", "甲", true, true,
                types("曲.mid", "mid"), put("曲.mid")));
        assertEquals("[BPM=84] 乙.mid", pick(result, "曲.mid").toFileName());
    }

    @Test
    void expand_bpmFromLibraryRow_usedWhenNoSvp() throws IOException {
        SongOriginalSetting row = row("乙", "甲");
        row.setBpm(new BigDecimal("97.5000"));
        Mockito.when(settingService.allOriginals()).thenReturn(List.of(row));
        Mockito.when(templateService.templateDirsFor("乙", "甲"))
                .thenReturn(List.of(Files.createDirectories(templateRoot.resolve("乙_甲"))));

        ExpandResult result = service.expand(req("乙", "甲", false, true,
                types("新曲.mid", "mid"), put("新曲.mid")));
        // 库里是 97.5000（decimal(10,4)），名字里要写成 97.5
        assertEquals("[BPM=97.5] 乙.mid", pick(result, "新曲.mid").toFileName());
    }

    @Test
    void expand_bpmTypedByUser_winsOverEverything() throws IOException {
        ExpandRequest request = new ExpandRequest("乙", "甲", null, true,
                List.of(put("曲.mid").toString()), true, types("曲.mid", "mid"), "120.50",
                true);
        ExpandResult result = service.expand(request);
        assertEquals("[BPM=120.5] 乙.mid", pick(result, "曲.mid").toFileName());
    }

    @Test
    void expand_bpmTypedNotANumber_blocked() throws IOException {
        ExpandRequest request = new ExpandRequest("乙", "甲", null, true,
                List.of(put("曲.mid").toString()), true, types("曲.mid", "mid"), "很快",
                true);
        ExpandResult result = service.expand(request);
        assertNotNull(result.blockedReason());
        assertTrue(result.blockedReason().contains("数字"), result.blockedReason());
    }

    // ==================== 排序与清册 ====================

    @Test
    void expand_orderIsTypeOrderThenFileName() throws IOException {
        ExpandResult result = service.expand(req("乙", "甲", true, true,
                types("工.svp", "svp", "乙.mp3", "original", "伴.mp3", "accompaniment",
                        "词.lrc", "lyric"),
                put("工.svp"), put("乙.mp3"), put("伴.mp3"), put("词.lrc")));
        List<String> order = result.files().stream().map(PickedFile::fileName).toList();
        assertEquals(List.of("乙.mp3", "词.lrc", "伴.mp3", "工.svp"), order,
                "原曲 → 歌词 → 伴奏 → … → 工程");
    }

    @Test
    void expand_existingFilesRideTheSameList() throws IOException {
        Path dir = Files.createDirectories(templateRoot.resolve("乙_甲"));
        SongOriginalSetting row = row("乙", "甲");
        assigned.put("original", "甲 - 乙.wav");
        putIn(dir, "甲 - 乙.wav", "x");
        Mockito.when(settingService.allOriginals()).thenReturn(List.of(row));
        Mockito.when(templateService.templateDirsFor("乙", "甲")).thenReturn(List.of(dir));
        Mockito.when(templateService.resolveFile("乙", "甲", "甲 - 乙.wav"))
                .thenReturn(dir.resolve("甲 - 乙.wav"));

        ExpandResult result = service.expand(req("乙", "甲", false, true,
                types("伴.mp3", "accompaniment"), put("伴.mp3")));
        PickedFile existing = pick(result, "甲 - 乙.wav");
        assertTrue(existing.existing(), "已有的文件要标出来，前端画成「已有」");
        assertEquals("甲 - 乙.wav", existing.toFileName(), "已经合规则的名字不改");
        assertFalse(pick(result, "伴.mp3").existing());
    }

    @Test
    void expand_existingFileMissingOnDisk_isAbsentNotSilentlyDropped() throws IOException {
        Path dir = Files.createDirectories(templateRoot.resolve("乙_甲"));
        SongOriginalSetting row = row("乙", "甲");
        assigned.put("lyric", "甲 - 乙.lrc");
        Mockito.when(settingService.allOriginals()).thenReturn(List.of(row));
        Mockito.when(templateService.templateDirsFor("乙", "甲")).thenReturn(List.of(dir));
        Mockito.when(templateService.resolveFile("乙", "甲", "甲 - 乙.lrc")).thenReturn(null);

        ExpandResult result = service.expand(new ExpandRequest("乙", "甲", null, false,
                List.of(), true, Map.of(), null, true));
        PickedFile lyric = pick(result, "甲 - 乙.lrc");
        assertEquals(PickKind.ABSENT, lyric.kind());
        assertNotNull(lyric.blockedReason());
        // 盘上没有这个文件就没有歌词随预览下来：那一格的播放键灰着，原因就是上面那一句
        assertNull(lyric.lyricPreview());
    }

    @Test
    void expand_editWithNoNewFiles_listsExistingOnly() throws IOException {
        Path dir = Files.createDirectories(templateRoot.resolve("乙_甲"));
        SongOriginalSetting row = row("乙", "甲");
        assigned.put("lyric", "甲 - 乙.lrc");
        Mockito.when(settingService.allOriginals()).thenReturn(List.of(row));
        Mockito.when(templateService.templateDirsFor("乙", "甲")).thenReturn(List.of(dir));

        ExpandResult result = service.expand(new ExpandRequest("乙", "甲", null, false,
                List.of(), true, Map.of(), null, true));
        assertEquals(1, result.files().size(), "换掉名字的已有文件仍要在清单里（旧名 → 新名要给人看）");
        assertTrue(pick(result, "甲 - 乙.lrc").existing());
    }

    @Test
    void expand_extRuleIsDownloadedOnce_andCoversEightTypes() {
        ExpandResult result = service.expand(new ExpandRequest("乙", "甲", null, true,
                List.of(), true, Map.of(), null, true));
        assertEquals(8, result.types().size());
        assertEquals(8, result.extRule().size());
        // 视频只开「原曲」这一处（裁决 4）
        assertTrue(result.extRule().get("original").contains("mp4"));
        assertTrue(result.extRule().get("original").contains("mp3"));
        assertFalse(result.extRule().get("accompaniment").contains("mp4"));
        assertEquals(List.of("mid"), result.extRule().get("mid"));
        assertEquals(List.of("svp"), result.extRule().get("svp"));
    }

    // ==================== 播放计划（三组、六格能播、两格不播） ====================

    @Test
    void types_playPlanStaysInsideItsGroup_andGroupsAreContiguous() {
        ExpandResult result = service.expand(new ExpandRequest("乙", "甲", null, true,
                List.of(), true, Map.of(), null, true));
        Map<String, TypeOption> byType = new LinkedHashMap<>();
        result.types().forEach(t -> byType.put(t.type(), t));

        // 同组必须挨着：前端只在「说明那一行要换一句」时画它，顺序散开就会把
        // 同一组画成好几段（见 songoriform.js 的 boxesHtml）
        assertEquals(List.of("pair", "pair", "part", "part", "part", "part", "none", "none"),
                result.types().stream().map(TypeOption::group).toList());

        // 每一格的播放计划指向的两格都得**存在、且在同一组里** —— 分组与配法是一起定的，
        // 错配的症状是「点伴奏的播放键听到原曲」，页面上一声不响
        for (TypeOption t : result.types()) {
            if (t.playAudio() == null && t.playLyric() == null) {
                assertEquals("none", t.group(), t.type() + " 没有播放计划，就该在不播的那一组");
                continue;
            }
            assertNotNull(byType.get(t.playAudio()), t.type() + " 的音频格子不存在");
            assertNotNull(byType.get(t.playLyric()), t.type() + " 的歌词格子不存在");
            assertEquals(t.group(), byType.get(t.playAudio()).group(), t.type() + " 的音频跨组了");
            assertEquals(t.group(), byType.get(t.playLyric()).group(), t.type() + " 的歌词跨组了");
        }

        // 口径本身：同组的两格点下去听到的是同一套东西（用户 2026-09-25 的要求）
        assertEquals(byType.get("lyric").playAudio(), byType.get("original").playAudio());
        assertEquals(byType.get("lyric").playLyric(), byType.get("original").playLyric());
        // 样例歌词自己不是音频：它跟着配套的那一份打样放
        assertEquals("demo", byType.get("demoLrc").playAudio());
        assertEquals("demoLrc", byType.get("demo").playLyric());
        assertEquals("demoLrc", byType.get("accompaniment").playLyric());
        assertEquals("demoLrc", byType.get("vocals").playLyric());
        // 组内共用一句说明（改了一句只改一处）
        assertEquals(byType.get("original").groupLabel(), byType.get("lyric").groupLabel());
        assertTrue(byType.get("mid").groupLabel().contains("不播"));
    }

    @Test
    void expand_pickedLyric_carriesParsedLyricForThePairPlayer() throws IOException {
        // 新增那一路还没有 originalId，走不了 /template/lyric —— 歌词随预览一起下来，
        // 「播放原曲 + 歌词」在两个窗口才是同一件事（不然新增窗口那个键只能灰着）
        Path lrc = putIn(outside, "乙.lrc", "[00:01.00]第一句\n[00:03.50]第二句\n");
        ExpandResult result = service.expand(req("乙", "甲", true, false,
                types("乙.mp3", "original", "乙.lrc", "lyric"), put("乙.mp3"), lrc));

        PickedFile lyric = pick(result, "乙.lrc");
        assertNotNull(lyric.lyricPreview());
        assertTrue(lyric.lyricPreview().timed());
        assertEquals(2, lyric.lyricPreview().lines().size());
        assertEquals("第一句", lyric.lyricPreview().lines().getFirst().text());
        // 音频那一格没有歌词可带（它自己不是歌词文件）
        assertNull(pick(result, "乙.mp3").lyricPreview());
    }

    @Test
    void expand_existingLyric_alsoCarriesParsedLyric() throws IOException {
        Path dir = Files.createDirectories(templateRoot.resolve("乙_甲"));
        SongOriginalSetting row = row("乙", "甲");
        assigned.put("lyric", "甲 - 乙.lrc");
        Mockito.when(settingService.allOriginals()).thenReturn(List.of(row));
        Mockito.when(templateService.templateDirsFor("乙", "甲")).thenReturn(List.of(dir));
        Mockito.when(templateService.resolveFile("乙", "甲", "甲 - 乙.lrc"))
                .thenReturn(putIn(dir, "甲 - 乙.lrc", "[00:02.00]库里那一份\n"));

        ExpandResult result = service.expand(new ExpandRequest("乙", "甲", null, false,
                List.of(), true, Map.of(), null, true));
        PickedFile lyric = pick(result, "甲 - 乙.lrc");
        assertTrue(lyric.existing());
        // 编辑那一路的歌词也一样随预览下来：播放键不认 originalId，两个窗口同一条路
        assertNotNull(lyric.lyricPreview());
        assertEquals("库里那一份", lyric.lyricPreview().lines().getFirst().text());
        // 歌词不是媒体文件：它自己没有流地址，播放那一对里它的位置是「歌词」那一半
        assertNull(lyric.playUrl());
    }
}
