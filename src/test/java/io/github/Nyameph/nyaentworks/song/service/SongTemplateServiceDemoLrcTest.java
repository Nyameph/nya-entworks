package io.github.Nyameph.nyaentworks.song.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import io.github.Nyameph.nyaentworks.song.config.SongProperties;
import io.github.Nyameph.nyaentworks.song.entity.SongOriginalSetting;
import io.github.Nyameph.nyaentworks.song.mapper.SongOriginalSettingMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SongTemplateService#pickDemoLrc} / {@link SongTemplateService#isDemoLyricName} 的纯函数
 * 测试（无 Spring / 无 DB / 不碰磁盘，可直接跑）。
 *
 * <p>回归的是「只留了打样歌词、没留打样音频」的目录（实测《气泡少女》）：老规矩只按「与样例音频
 * 同主名」配样例歌词，这种目录配不出来 → 填词工具退回用原曲歌词当参照 → 那份模板的拼音格既拿不到
 * demo 汉字也不标红，看着就是「模板没去匹配 demo 歌词」。
 */
class SongTemplateServiceDemoLrcTest {

    @Test
    void demoMarkedLyricNameIsRecognised() {
        assertTrue(SongTemplateService.isDemoLyricName("[demo] 气泡少女.lrc"));
        assertTrue(SongTemplateService.isDemoLyricName("真的想不出名儿了_Da Da Da_MixDown.lrc"));
        assertTrue(SongTemplateService.isDemoLyricName("免我蹉跎苦 打样.srt"));
        assertFalse(SongTemplateService.isDemoLyricName("一口甜 - 气泡少女.lrc"));
        assertFalse(SongTemplateService.isDemoLyricName("[人声] 气泡少女.mp3"));
        assertFalse(SongTemplateService.isDemoLyricName(null));
        assertFalse(SongTemplateService.isDemoLyricName("  "));
    }

    /** 报的那个案子：目录里只有打样歌词、没有样例音频（demoBase 为空），也要认出来。 */
    @Test
    void picksDemoLrcWithoutDemoAudio() {
        assertEquals("[demo] 气泡少女.lrc", SongTemplateService.pickDemoLrc(
                List.of("[demo] 气泡少女.lrc", "一口甜 - 气泡少女.lrc"), null));
    }

    /** 老规矩优先：样例音频在时，与它同主名的那份照旧胜出（已入库的样例歌词一个都不换）。 */
    @Test
    void audioPairedLrcWinsOverMarker() {
        assertEquals("[demo] 洛春赋.lrc", SongTemplateService.pickDemoLrc(
                List.of("洛春赋_MixDown.lrc", "[demo] 洛春赋.lrc"), "[demo] 洛春赋"));
    }

    /** 样例音频在、但与打样歌词不同主名（打样音频是别的名字）时，靠文件名标记兜底。 */
    @Test
    void markerRulesBacksUpUnpairedDemoAudio() {
        assertEquals("洛春赋_MixDown.lrc", SongTemplateService.pickDemoLrc(
                List.of("洛春赋_MixDown.lrc", "云汐 - 洛春赋.lrc"), "[demo] 打样混音"));
    }

    /** ①「除后缀名外同名」不限定 {@code .lrc}：与样例音频同名的 {@code .srt} 也认。 */
    @Test
    void audioPairedSrtCountsAsSameName() {
        assertEquals("[demo] 洛春赋.srt", SongTemplateService.pickDemoLrc(
                List.of("洛春赋 打样.lrc", "[demo] 洛春赋.srt"), "[demo] 洛春赋"));
    }

    /**
     * 用户定的口径（2026-09-14）：有样例音频时优先同名、没有同名才按标记；没有样例音频时只按标记。
     * 只有原曲歌词（不带任何标记）时宁可空着 —— 那是原唱的词，不是打样唱的。
     */
    @Test
    void markedLyricsOnly() {
        assertNull(SongTemplateService.pickDemoLrc(List.of("苏打绿 - 小情歌.lrc"), null));
        assertNull(SongTemplateService.pickDemoLrc(List.of("苏打绿 - 小情歌.lrc"), "[demo] 小情歌"));
        assertEquals("[demo] 小情歌.lrc", SongTemplateService.pickDemoLrc(
                List.of("苏打绿 - 小情歌.lrc", "[demo] 小情歌.lrc"), "[demo] 小情歌"));
    }

    /** 真目录形状（实测 16 个）：有样例音频、却没有同名/同标记歌词 → 样例歌词空着，原曲歌词不受影响。 */
    @Test
    void judge_whenOnlyOriginalLyricExists(@TempDir Path dir) throws IOException {
        write(dir, "[demo] 小情歌.mp3", "x");
        write(dir, "苏打绿 - 小情歌.mp3", "x");
        write(dir, "苏打绿 - 小情歌.lrc", "[0:10.00]这是一句测试词\n");
        write(dir, "[工程] 小情歌.svp", "{}");

        SongTemplateService.FileJudgement j = SongTemplateService.judge(collect(dir));

        assertEquals("[demo] 小情歌.mp3", j.demo());
        assertNull(j.demoLrc());
        assertEquals("苏打绿 - 小情歌.lrc", j.lyric());
    }

    @Test
    void noDemoLyricGivesNull() {
        assertNull(SongTemplateService.pickDemoLrc(List.of("一口甜 - 气泡少女.lrc"), null));
        assertNull(SongTemplateService.pickDemoLrc(List.of(), "[demo] 气泡少女"));
    }

    /** 真目录形状（《气泡少女》）：有打样歌词、没有打样音频 → 样例歌词认得出，原曲歌词不被它顶掉。 */
    @Test
    void judge_whenDemoAudioIsMissing(@TempDir Path dir) throws IOException {
        write(dir, "[demo] 气泡少女.lrc", "[0:20.33]早起要去作报告\n");
        write(dir, "一口甜 - 气泡少女.lrc", "[0:19.44]喜欢看你微笑\n");
        write(dir, "一口甜 - 气泡少女.mp3", "x");
        write(dir, "[人声] 气泡少女.mp3", "x");
        write(dir, "[伴奏] 气泡少女.mp3", "x");
        write(dir, "[工程] 气泡少女.svp", "{}");

        SongTemplateService.FileJudgement j = SongTemplateService.judge(collect(dir));

        assertEquals("[demo] 气泡少女.lrc", j.demoLrc());
        assertEquals("一口甜 - 气泡少女.lrc", j.lyric());
        assertNull(j.demo());
        assertEquals("一口甜 - 气泡少女.mp3", j.original());
        assertEquals("[人声] 气泡少女.mp3", j.vocals());
        assertEquals("[伴奏] 气泡少女.mp3", j.accompaniment());
    }

    /** 有打样音频时走老规矩（与它同主名的 .lrc），结果与加这个兜底之前一致。 */
    @Test
    void judge_whenDemoAudioIsPresent(@TempDir Path dir) throws IOException {
        write(dir, "[demo] 气泡少女.lrc", "[0:20.33]早起要去作报告\n");
        write(dir, "[demo] 气泡少女.mp3", "x");
        write(dir, "一口甜 - 气泡少女.lrc", "[0:19.44]喜欢看你微笑\n");
        write(dir, "一口甜 - 气泡少女.mp3", "x");
        write(dir, "[工程] 气泡少女.svp", "{}");

        SongTemplateService.FileJudgement j = SongTemplateService.judge(collect(dir));

        assertEquals("[demo] 气泡少女.mp3", j.demo());
        assertEquals("[demo] 气泡少女.lrc", j.demoLrc());
        assertEquals("一口甜 - 气泡少女.lrc", j.lyric());
    }

    // ==================== resolveFillLyricPath：样例歌词列取不到时兜一圈 ====================

    private final SongOriginalSettingMapper mapper = mock(SongOriginalSettingMapper.class);

    /**
     * {@code LambdaUpdateWrapper} 的列名靠 MyBatis-Plus 的 lambda 缓存反查（同
     * {@code RhymeWordClassTest}）：不连库就得手工把表信息装进去，否则 {@code set(…)}
     * 会抛「can not find lambda cache for this entity」。
     */
    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                SongOriginalSetting.class);
    }

    private SongTemplateService service(Path templateRoot) {
        SongProperties props = new SongProperties();
        props.setTemplateDir(templateRoot.toString());
        // 仅原曲根指到一个不存在的目录：别让测试去摸本机的 F:\
        props.setOnlyOriginalDir(templateRoot.resolve("仅原曲").toString());
        return new SongTemplateService(props, mapper);
    }

    private static SongOriginalSetting setting(Long id, String rawName) {
        SongOriginalSetting s = new SongOriginalSetting();
        s.setId(id);
        s.setRawName(rawName);
        return s;
    }

    /** 原曲目录（扫描只看第 1 层目录的名字） */
    private static Path originalDir(Path root, String name) throws IOException {
        return Files.createDirectories(root.resolve(name));
    }

    /**
     * 报的那个案子（{@code song_original_setting.id=316}《学猫叫》）：样例歌词躺在盘上、
     * {@code demo_lrc_file_name} 却是 NULL（那一列落地前入库的老行）—— 取参照歌词时要
     * 兜一圈扫出来，并<b>写回库</b>，下一次打开就不必再扫。
     */
    @Test
    void healFillLyric_writesDiscoveredDemoLrc(@TempDir Path root) throws IOException {
        Path songDir = originalDir(root, "学猫叫");
        write(songDir, "[demo] 学猫叫.lrc", "[0:10.00]握腰穿你的外套\n");
        write(songDir, "[工程] 学猫叫.svp", "{}");
        when(mapper.selectById(1L)).thenReturn(setting(1L, "学猫叫"));

        Path hit = service(root).resolveFillLyricPath(1L);

        assertEquals(songDir.resolve("[demo] 学猫叫.lrc"), hit);
        LambdaUpdateWrapper<SongOriginalSetting> uw = captureUpdate();
        assertTrue(uw.getSqlSet().contains("demo_lrc_file_name"), uw.getSqlSet());
        assertTrue(uw.getParamNameValuePairs().containsValue("[demo] 学猫叫.lrc"));
        // 盘上没有原曲歌词 → 那一列一个字节都不许动（「只补不清」的另一半）
        assertFalse(uw.getSqlSet().contains("lyric_file_name"), uw.getSqlSet());
        assertFalse(uw.getSqlSet().contains("lyric_check"), uw.getSqlSet());
    }

    /** 库里那列空悬（写着一个盘上早已不存在的名字）→ 换成扫出来的那个。 */
    @Test
    void healFillLyric_replacesStaleDemoLrcName(@TempDir Path root) throws IOException {
        Path songDir = originalDir(root, "学猫叫");
        write(songDir, "[demo] 学猫叫.lrc", "[0:10.00]握腰穿你的外套\n");
        SongOriginalSetting s = setting(1L, "学猫叫");
        s.setDemoLrcFileName("[demo] 学猫叫改.lrc");
        when(mapper.selectById(1L)).thenReturn(s);

        Path hit = service(root).resolveFillLyricPath(1L);

        assertEquals(songDir.resolve("[demo] 学猫叫.lrc"), hit);
        assertTrue(captureUpdate().getParamNameValuePairs().containsValue("[demo] 学猫叫.lrc"));
    }

    /** 原曲歌词那列也一并补（同一次扫描，不扫第二遍），并把空悬的确认位归零。 */
    @Test
    void healFillLyric_writesOriginalLyricWhenNoConfirmBit(@TempDir Path root) throws IOException {
        Path songDir = originalDir(root, "学猫叫");
        write(songDir, "[demo] 学猫叫.lrc", "[0:10.00]握腰穿你的外套\n");
        write(songDir, "学猫叫 - 某人.lrc", "[0:09.00]我要穿你的外套\n");
        SongOriginalSetting s = setting(1L, "学猫叫");
        s.setLyricCheck(false);
        when(mapper.selectById(1L)).thenReturn(s);

        assertEquals(songDir.resolve("[demo] 学猫叫.lrc"), service(root).resolveFillLyricPath(1L));

        LambdaUpdateWrapper<SongOriginalSetting> uw = captureUpdate();
        assertTrue(uw.getSqlSet().contains("lyric_file_name"), uw.getSqlSet());
        assertTrue(uw.getParamNameValuePairs().containsValue("学猫叫 - 某人.lrc"));
        assertTrue(uw.getSqlSet().contains("lyric_check"), uw.getSqlSet());
    }

    /**
     * <b>已确认的原曲歌词不覆盖</b>（{@code lyric_check=1}）：那是用户手动指派的，
     * 读路径不越权；即使盘上那个名字已经不在，也只由同步扫描去清（见 healLyricNames 的 javadoc）。
     */
    @Test
    void healFillLyric_keepsConfirmedOriginalLyric(@TempDir Path root) throws IOException {
        Path songDir = originalDir(root, "学猫叫");
        write(songDir, "[demo] 学猫叫.lrc", "[0:10.00]握腰穿你的外套\n");
        write(songDir, "学猫叫 - 某人.lrc", "[0:09.00]我要穿你的外套\n");
        SongOriginalSetting s = setting(1L, "学猫叫");
        s.setLyricCheck(true);
        s.setLyricFileName("老歌词.lrc");
        when(mapper.selectById(1L)).thenReturn(s);

        assertEquals(songDir.resolve("[demo] 学猫叫.lrc"), service(root).resolveFillLyricPath(1L));

        LambdaUpdateWrapper<SongOriginalSetting> uw = captureUpdate();
        assertTrue(uw.getSqlSet().contains("demo_lrc_file_name"), uw.getSqlSet());
        assertFalse(uw.getSqlSet().contains("lyric_file_name"), uw.getSqlSet());
    }

    /**
     * <b>只补不清</b>：目录里一个歌词都没有（外挂盘没挂上 / 目录还没整理）时什么都不写 ——
     * 打开一次填词页就把用户确认过的文件名抹掉，是最不能接受的那种「修」。
     */
    @Test
    void healFillLyric_writesNothingWhenNothingFound(@TempDir Path root) throws IOException {
        Path songDir = originalDir(root, "学猫叫");
        write(songDir, "[工程] 学猫叫.svp", "{}");
        SongOriginalSetting s = setting(1L, "学猫叫");
        s.setDemoLrcFileName("[demo] 学猫叫.lrc");
        s.setLyricFileName("一口甜 - 学猫叫.lrc");
        when(mapper.selectById(1L)).thenReturn(s);

        assertNull(service(root).resolveFillLyricPath(1L));

        verify(mapper, never()).update(any(), any());
    }

    private LambdaUpdateWrapper<SongOriginalSetting> captureUpdate() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<SongOriginalSetting>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        return captor.getValue();
    }

    private static void write(Path dir, String name, String body) throws IOException {
        Files.writeString(dir.resolve(name), body, StandardCharsets.UTF_8);
    }

    /** 与 {@code SongTemplateService.collectFiles} 同序（文件名排序）——judge 的「第一个命中」靠它 */
    private static List<Path> collect(Path dir) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile).forEach(files::add);
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return files;
    }
}
