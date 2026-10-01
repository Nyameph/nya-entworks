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
import io.github.Nyameph.nyaentworks.song.config.SongProperties;
import io.github.Nyameph.nyaentworks.song.entity.SongOriginalSetting;
import io.github.Nyameph.nyaentworks.song.mapper.SongOriginalSettingMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 原曲页那个红色按钮：把原曲目录下的文件<b>移入冗余</b>（2026-09-25 用户定，
 * 原先是 {@code Files.delete} 真删）。
 *
 * <p><b>不连库、不碰 {@code F:\}</b>：三个根都指向 {@link TempDir}，mapper 是 mock，
 * 「库里那一行」用手搓的实体喂进 {@code selectById}。扫描 / 解析路径那些方法是真的
 * —— 这一步本来就该在真目录上验（文件名与目录名的形状就是这个功能的全部输入）。
 *
 * <p>盯的是四件会静默错的事：
 * <ol>
 *   <li>文件真的落在<b>冗余根下与原目录同名的那一层</b>，而不是被删掉、也不是散在根上；</li>
 *   <li>冗余目录里已经有同名文件时<b>不覆盖</b>（覆盖就是「移进去一个、弄丢一个」）；</li>
 *   <li>库里那一列只在「它本来就指着这个文件」时清空，并且确认位一起归零（留着会变成
 *       空悬的确认，这一项以后再也扫描不进来）；</li>
 *   <li>原曲目录被搬空后不留一个空壳 —— 留着下次扫描会把它当成一条没有任何文件的原曲
 *       （<b>这一条会真调一次 {@code RecycleBin}</b>，见下）。</li>
 * </ol>
 *
 * <p><b>第 4 条按不住「不碰本机」</b>（2026-09-30 起）：那个空目录现在是<b>送进 Windows 回收站</b>，
 * 所以**凡是把某个原曲目录里的文件全部搬走的那几条用例**都会真起一次 powershell，
 * 每跑一次全量测试就往开发者的回收站里放一个空的临时目录（无内容、可还原）。
 * 这是**明知而留**的一处破例：它是整套回收站机制唯一的端到端断言，2026-09-30 第一次跑它
 * 就逮到三个「纯单测永远看不见」的错（`DeleteDirectory` 第四个参数该是 `UICancelOption`、
 * 环境变量压根没交给子进程、powershell 的输出不能按 UTF-8 严格读）。
 * 只搬走一部分文件的那几条（目录里还留着 svp 之类）不会走到这一支。
 * 磁盘先动、库后动这条顺序不在这里验（它靠 {@code Files.move} 抛异常时提前 return 保证，
 * 没有可观测的中间态），要现场确认的话见 {@code docs/页面设计方案.md} 原曲页那一节。
 */
class SongTemplateRetireFileTest {

    @TempDir
    Path tmp;

    private Path templateRoot;
    private Path onlyRoot;
    private Path retiredRoot;

    private SongOriginalSettingMapper mapper;
    private SongTemplateService service;

    /** 库里那一行（{@code selectById} 恒返回它） */
    private final SongOriginalSetting row = new SongOriginalSetting();

    /**
     * {@code LambdaUpdateWrapper} 的列名靠 MyBatis-Plus 的 lambda 缓存反查，那个缓存平时
     * 由容器扫描 mapper 时填。这里不连库，得手工装上表信息，否则 {@code set(...)} 会抛
     * 「can not find lambda cache for this entity」（同 {@code SongOriginalImportPlanTest}）。
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
        retiredRoot = Files.createDirectories(tmp.resolve("retired"));

        SongProperties properties = new SongProperties();
        properties.setTemplateDir(templateRoot.toString());
        properties.setOnlyOriginalDir(onlyRoot.toString());
        properties.setOriginalRetiredDir(retiredRoot.toString());

        row.setId(1L);
        row.setRawName("后来");
        row.setArtist("刘若英");

        mapper = Mockito.mock(SongOriginalSettingMapper.class);
        Mockito.when(mapper.selectById(1L)).thenReturn(row);
        service = new SongTemplateService(properties, mapper);
    }

    private void put(Path dir, String name) throws IOException {
        Files.writeString(Files.createDirectories(dir).resolve(name), name,
                StandardCharsets.UTF_8);
    }

    /** 库侧那次清空收到的 set 子句；没清库时返回 null */
    private String capturedSet() {
        ArgumentCaptor<LambdaUpdateWrapper<SongOriginalSetting>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        Mockito.verify(mapper, Mockito.atMostOnce()).update(Mockito.any(), captor.capture());
        var all = captor.getAllValues();
        return all.isEmpty() ? null : all.getFirst().getSqlSet();
    }

    @Test
    void retire_movesIntoRetiredFolderNamedAfterTheOriginalDir() throws IOException {
        Path dir = templateRoot.resolve("后来_刘若英");
        put(dir, "后来 - 刘若英.mp3");
        put(dir, "后来.svp");                 // 有工程文件：这一条留在模板根（relocate 不动它）
        row.setOriginalFileName("后来 - 刘若英.mp3");
        row.setOriginalCheck(true);

        String message = service.retireFile(1L, "后来 - 刘若英.mp3", "original");

        Path moved = retiredRoot.resolve("后来_刘若英").resolve("后来 - 刘若英.mp3");
        assertTrue(Files.isRegularFile(moved), "文件要落在 冗余根/原目录名/ 下：" + moved);
        assertFalse(Files.exists(dir.resolve("后来 - 刘若英.mp3")), "原曲目录里不该还留着它");
        assertTrue(Files.isRegularFile(dir.resolve("后来.svp")), "别的文件一个都不动");
        assertTrue(message.contains(moved.toString()), "提示要给出落点，人才找得回来：" + message);
        assertEquals("后来 - 刘若英.mp3", row.getOriginalFileName(),
                "清库走的是 update(实体, lambdaUpdate)，不许就地改这个实体再 updateById"
                        + "—— 后者不更新 null 字段，正是「清了却清不掉」的老毛病");
    }

    @Test
    void retire_clearsTheLibraryFieldAndItsCheckBit() throws IOException {
        Path dir = templateRoot.resolve("后来_刘若英");
        put(dir, "后来 - 刘若英.mp3");
        put(dir, "后来.svp");
        row.setOriginalFileName("后来 - 刘若英.mp3");
        row.setOriginalCheck(true);

        service.retireFile(1L, "后来 - 刘若英.mp3", "original");

        String sql = capturedSet();
        assertTrue(sql != null && sql.contains("original_file_name"), String.valueOf(sql));
        assertTrue(sql.contains("original_check"),
                "确认位要跟着归零，否则这一项以后再也扫描不进来：" + sql);
    }

    @Test
    void retire_candidateNotAssignedInLibrary_onlyTheDiskMoves() throws IOException {
        Path dir = onlyRoot.resolve("后来_刘若英");
        put(dir, "后来 - 刘若英.mp3");
        put(dir, "[伴奏] 后来.mp3");
        row.setOriginalFileName("后来 - 刘若英.mp3");   // 库里指的是另一个文件

        service.retireFile(1L, "[伴奏] 后来.mp3", "accompaniment");

        assertTrue(Files.isRegularFile(retiredRoot.resolve("后来_刘若英").resolve("[伴奏] 后来.mp3")));
        Mockito.verify(mapper, Mockito.never()).update(Mockito.any(), Mockito.any());
    }

    @Test
    void retire_sameNameAlreadyInRetiredFolder_neverOverwrites() throws IOException {
        Path dir = onlyRoot.resolve("后来_刘若英");
        put(dir, "后来 - 刘若英.mp3");
        put(retiredRoot.resolve("后来_刘若英"), "后来 - 刘若英.mp3");

        service.retireFile(1L, "后来 - 刘若英.mp3", "original");

        Path target = retiredRoot.resolve("后来_刘若英");
        assertTrue(Files.isRegularFile(target.resolve("后来 - 刘若英.mp3")),
                "先前那个要原样留着");
        assertTrue(Files.isRegularFile(target.resolve("后来 - 刘若英_冗余1.mp3")),
                "新来的加 _冗余1，绝不覆盖");
    }

    @Test
    void retire_lastFile_removesTheEmptyOriginalFolder() throws IOException {
        Path dir = onlyRoot.resolve("后来_刘若英");
        put(dir, "后来 - 刘若英.mp3");

        service.retireFile(1L, "后来 - 刘若英.mp3", "original");

        assertFalse(Files.exists(dir),
                "搬空之后不留空壳：留着下次扫描会把它当成一条没有任何文件的原曲");
    }

    @Test
    void retire_demoLrc_isAKnownType() throws IOException {
        // demoLrc 不在 SongTemplateService.TYPES 那七个里，但库里有列、盘上有文件
        Path dir = onlyRoot.resolve("后来_刘若英");
        put(dir, "[demo] 后来.lrc");

        service.retireFile(1L, "[demo] 后来.lrc", "demoLrc");

        assertTrue(Files.isRegularFile(
                retiredRoot.resolve("后来_刘若英").resolve("[demo] 后来.lrc")));
    }

    @Test
    void retire_unknownTypeOrFileOutsideTheDir_refuses() throws IOException {
        Path dir = onlyRoot.resolve("后来_刘若英");
        put(dir, "后来 - 刘若英.mp3");
        put(onlyRoot.resolve("别人的目录"), "别的.mp3");

        assertThrows(IllegalArgumentException.class,
                () -> service.retireFile(1L, "后来 - 刘若英.mp3", "不认识的一类"));
        assertThrows(IllegalArgumentException.class,
                () -> service.retireFile(1L, "别的.mp3", "original"),
                "只有该原曲目录下的文件能移 —— 否则这就是一个「把任意文件搬走」的接口");
        assertThrows(IllegalArgumentException.class,
                () -> service.retireFile(null, "后来 - 刘若英.mp3", "original"));
        assertTrue(Files.isRegularFile(dir.resolve("后来 - 刘若英.mp3")), "拒了就不许动盘");
    }
}
