package io.github.Nyameph.nyaentworks.common;

import io.github.Nyameph.nyaentworks.common.config.LocalAiProperties;
import io.github.Nyameph.nyaentworks.common.config.OnlineAiProperties;
import io.github.Nyameph.nyaentworks.common.settings.SettingsCatalog;
import io.github.Nyameph.nyaentworks.common.tool.ExternalCommandProbe;
import io.github.Nyameph.nyaentworks.manga.config.MangaProperties;
import io.github.Nyameph.nyaentworks.manga.service.MangaDictService;
import io.github.Nyameph.nyaentworks.shout.config.ShoutProperties;
import io.github.Nyameph.nyaentworks.song.config.SongProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 环境自检（{@link EnvCheckService}）的模块判据与能力探测自检 —— 《桌面化收尾（2026-09-25 实施）》
 * 阶段 1 / 4.1 的验收（这个类此前<b>零覆盖</b>；见 docs/已完成/桌面壳实施计划.md）。
 *
 * <p>盯的是两件<b>错了很尴尬、而且正好出在发出去的包上</b>的事：
 * <ul>
 *   <li><b>「本次构建里没有的模块」一个检查项都不许出现</b> —— 裁剪切出来的包一打开就报
 *       「未归档根 F:\NetdiskDownload\#待整理散漫 不存在」，等于把自检表废掉（2026-09-18 实测）。
 *       表那一半的口径也在这是钉死的：<b>表跟着模块走</b>，模块不在时它的表连检查一起跳过
 *       （而不是留着「20 张表」的全量检查），否则「只要歌曲」的包里还会显示漫画的 9 张表。</li>
 *   <li><b>「读到 false」不等于「读不到」</b> —— 关掉的模块它的检查组<b>照常出现</b>；
 *       若把「关掉」误判成「不存在」，那个模块就再也开不回来了。判据必须是
 *       {@code <模块>.enabled} 这个键<b>读不读得到</b>（{@code moduleExists}），
 *       不能是 Properties Bean 的 getter（三个字段默认值都是 false，分不出这两种）。</li>
 * </ul>
 *
 * <p>能力探测（4.1）钉三件事：模块不在时连探测项都不出现；探测结果如实落成 OK / WARN；
 * 本机 AI 端点的「没配 / 可达 / 连不上」三种状态分开报。
 *
 * <p>纯单测，照 {@code SettingsCatalogTest} 的构造法：三个 Properties 直接 {@code new}，
 * 属性源用 {@link MockEnvironment}；JdbcTemplate 空着（查询会抛、各检查按「不可用」处理，
 * 断言只看<b>条目出现与否</b>，不连库、不写盘）；词典与外部命令探测用 mock。
 */
public class EnvCheckServiceTest {

    /** 漫画侧的 7 张表 —— 口径是「模块不在 → 表的检查也不在」，一条都不许漏网 */
    private static final List<String> MANGA_TABLES = List.of(
            "manga_dict_entry", "manga_archive_unit", "manga_archive_name",
            "manga_tag", "manga_tag_ref", "manga_data", "manga_eh_scan");

    /**
     * 「本次构建里有这几个模块」的属性源 —— 与 {@code SettingsCatalogTest#envWith} 同一套：
     * 判据只回答「键在不在」，值是多少跟它无关。
     */
    private static Environment envWith(String... modules) {
        MockEnvironment env = new MockEnvironment();
        for (String m : modules) {
            env.withProperty(SettingsCatalog.PREFIX + m + ".enabled", "true");
        }
        return env;
    }

    /**
     * 一个不连库、不真探进程的自检服务。
     *
     * @param aiReachable 本机 AI 端点可达性（真实探测要发 HTTP，测试里替换掉）
     */
    private static EnvCheckService service(Environment env, boolean aiReachable) {
        MangaDictService dictService = mock(MangaDictService.class);
        when(dictService.current()).thenThrow(new RuntimeException("测试不连库"));
        ExternalCommandProbe probe = mock(ExternalCommandProbe.class);
        when(probe.canRun(anyString())).thenReturn(true);
        return new EnvCheckService(new JdbcTemplate(), dictService,
                new MangaProperties(), new SongProperties(), new ShoutProperties(),
                new SettingsCatalog(new MangaProperties(), new SongProperties(), new ShoutProperties(),
                        new LocalAiProperties(), new OnlineAiProperties(), env),
                probe, new LocalAiProperties()) {
            @Override
            protected boolean endpointReachable(String baseUrl) {
                return aiReachable;
            }
        };
    }

    private static Set<String> names(EnvCheckService.EnvReport report) {
        return report.items().stream().map(EnvCheckService.CheckItem::name).collect(Collectors.toSet());
    }

    private static EnvCheckService.CheckItem byName(EnvCheckService.EnvReport report, String name) {
        return report.items().stream().filter(i -> i.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("没有名为「" + name + "」的检查项：" + names(report)));
    }

    // ==================== 阶段 1：模块判据 ====================

    /**
     * 漫画模块不在本次构建里（{@code nya-entworks.manga.enabled} 读不到）：
     * 漫画侧的检查组<b>一条都不出现</b>，歌曲 / 喊麦 / 通用组照常。
     */
    @Test
    public void moduleAbsent_itsCheckGroupsVanish() {
        EnvCheckService.EnvReport r = service(envWith("song", "shout"), true).check();
        Set<String> names = names(r);

        // 漫画的五个组：归档根 / manga_data 列 / 扫描两根 / NConvert / 词典
        // （归档根不在磁盘时是一条「归档根」、在磁盘时是四条「归档根 N 分」，前缀要一起数）
        assertFalse(names.stream().anyMatch(n -> n.startsWith("归档根")),
                "漫画模块不在构建里，却还有它的归档根检查项");
        // 扫描根那一组只剩合集 / 未归档两根（新漫画根 2026-09-30 已从自检里整条去掉，
        // 与模块在不在无关 —— 它由下面 #newMangaRootIsNoLongerChecked 那条单独钉着）
        for (String gone : List.of("表 manga_data 的列", "新作者合集根",
                "未归档根", "NConvert", "词典", "词典类型分布", "词典正则")) {
            assertFalse(names.contains(gone), "漫画模块不在构建里，却还有它的检查项：「" + gone + "」");
        }
        // 表那一半（口径：表跟着模块走，不是「20 张表全量留着」）
        for (String table : MANGA_TABLES) {
            assertFalse(names.contains("表 " + table),
                    "漫画模块不在构建里，却还在检查它的表（会显示一排不用的表）：" + table);
        }
        // 歌曲 / 喊麦 / 通用照常
        assertTrue(names.contains("表 async_task") && names.contains("表 file_op_log"),
                "通用表（async_task / file_op_log）不属于任何模块，任何构建里都该在");
        assertTrue(names.contains("表 song_group"), "歌曲的表被误伤");
        assertTrue(names.contains("表 shout_group"), "喊麦的表被误伤");
        assertTrue(names.contains("表 song_file 的列") && names.contains("表 shout_file 的列"),
                "suffix 列检查的 song / shout 两半被误伤");
        assertTrue(names.contains("歌曲根") && names.contains("待打分根"), "歌曲根检查被误伤");
        assertTrue(names.contains("喊麦根") && names.contains("喊麦待打分根"), "喊麦根检查被误伤");
        assertTrue(names.contains("表 song_original_setting 的列"), "歌曲的列检查被误伤");
    }

    /** 喊麦模块不在：喊麦那一半消失，歌曲那一半照常（同一段代码里两半分开判据） */
    @Test
    public void shoutAbsent_onlyShoutHalfVanishes() {
        EnvCheckService.EnvReport r = service(envWith("song"), true).check();
        Set<String> names = names(r);
        assertFalse(names.contains("表 shout_file"), "喊麦不在构建里，却还在检查它的表");
        assertFalse(names.contains("表 shout_file 的列"), "喊麦不在构建里，却还在检查它的列");
        assertFalse(names.contains("喊麦根") || names.contains("喊麦待打分根"),
                "喊麦不在构建里，却还在检查它的根");
        assertTrue(names.contains("表 song_file") && names.contains("表 song_file 的列"),
                "喊麦被裁掉时歌曲那一半被误伤");
        assertTrue(names.contains("歌曲根"), "喊麦被裁掉时歌曲的根检查被误伤");
    }

    /**
     * 「读到 false」不等于「读不到」：漫画<b>关着</b>（键在、值 false）时它的检查组
     * <b>照常出现</b> —— 关着的模块正需要自检告诉它「缺什么」，而那个关着的开关
     * 是页面上唯一的自救入口。若把 false 误判成不存在，模块就再也开不回来了。
     */
    @Test
    public void moduleTurnedOff_itsCheckGroupsStay() {
        MockEnvironment env = new MockEnvironment();
        for (String m : SettingsCatalog.MODULES) {
            env.withProperty(SettingsCatalog.PREFIX + m + ".enabled", "false");
        }
        EnvCheckService.EnvReport r = service(env, true).check();
        Set<String> names = names(r);
        assertTrue(names.stream().anyMatch(n -> n.startsWith("归档根")),
                "漫画关着（不是不在），归档根检查不该消失");
        assertTrue(names.contains("NConvert"), "漫画关着（不是不在），NConvert 检查不该消失");
        assertTrue(names.contains("词典") || names.contains("词典类型分布"),
                "漫画关着（不是不在），词典检查不该消失");
        assertTrue(names.contains("表 manga_dict_entry"), "漫画关着（不是不在），它的表检查不该消失");
        assertTrue(names.contains("新作者合集根") && names.contains("未归档根"),
                "漫画关着（不是不在），扫描根检查不该消失");
        // 三个模块都在（哪怕全是 false）：20 张表的检查一张不少
        for (String table : MANGA_TABLES) {
            assertTrue(names.contains("表 " + table), "漫画关着（不是不在），表检查不该消失：" + table);
        }
    }

    /**
     * <b>「新漫画根」不再是一条自检项</b>（2026-09-30 去掉，作者要求）。
     *
     * <p>去掉的理由是它那两件事都有别的地方管：根目录缺着时「新漫画」页自己会挂遮罩
     * （{@code PageGates}），缺哪一档分区时打分（{@code MangaStoreService#score}）
     * 会自己把裸 {@code #9-} 建出来 —— 留在自检里只剩一行没人再据此动作的重复。
     *
     * <p>这条测试是**防它被顺手加回来**的：判据是「任何构建里都没有这个名字」，
     * 与模块在不在无关；同时盯着剩下两根还在（别把整组删了）。
     */
    @Test
    public void newMangaRootIsNoLongerChecked() {
        Set<String> names = names(service(
                envWith(SettingsCatalog.MODULES.toArray(new String[0])), true).check());

        assertFalse(names.contains("新漫画根"),
                "「新漫画根」已从自检里去掉（2026-09-30）：缺目录由新漫画页的遮罩管、"
                        + "缺分区由打分自动建 —— 别再把它加回 checkMangaScanRoots");
        assertTrue(names.contains("新作者合集根") && names.contains("未归档根"),
                "合集根 / 未归档根这两条不该跟着一起消失：" + names);
    }

    // ============ 阶段 1 补充：「一个模块都没有」自检（2026-09-25 加）============
    /**
     * 一个模块都没有 → <b>ERROR</b>。这是「三个模块一起消失」唯一能在界面上看见的地方：
     * 那种构建**什么都不报错**（配置页只剩通用项、左侧导航空、接口全不注册），
     * 打开来只像「这个应用就长这样」，而不是「少带了个文件」。
     */
    @Test
    public void noModulesInBuild_isAnError() {
        EnvCheckService.EnvReport r = service(envWith(), true).check();

        assertFalse(names(r).stream().anyMatch(n -> n.startsWith("归档根")),
                "一个模块都没有时，漫画的检查组不该出现");
        EnvCheckService.CheckItem item = byName(r, "模块");
        assertEquals("ERROR", item.level(),
                "一个模块都没有必须报 ERROR —— 报 OK/WARN 的话那种构建看不出哪里不对");
        assertNotNull(item.hint(), "ERROR 没写怎么修");
        assertTrue(item.hint().contains("nya-entworks.example.yaml"),
                "hint 该直接指向那份可拷的底稿（仓库里唯一可信的起点）：" + item.hint());
        assertTrue(r.errors() >= 1, "errors 计数没算上它，左下角红点就不亮");
    }

    /** 有模块时如实列出是哪几个 —— 「只留了歌曲的那份构建」一眼能确认是预期的，不是少了东西 */
    @Test
    public void someModulesInBuild_listsThemAndStaysOk() {
        EnvCheckService.CheckItem item = byName(service(envWith("song", "shout"), true).check(), "模块");
        assertEquals("OK", item.level(), "构建里有模块却挂了彩");
        assertTrue(item.detail().contains("song") && item.detail().contains("shout"),
                "没列出构建里有哪些模块：" + item.detail());
        assertFalse(item.detail().contains("manga"),
                "列上了不在本次构建里的模块：" + item.detail());
    }

    /**
     * 三个都写 {@code false}（模块在、只是关着）<b>不算</b>「一个模块都没有」—— 那是明确的选择，
     * 页面上那三个关着的开关还能把它们开回来。**按 {@code isEnabled()} 实现这条的人会在这里翻车**
     * （三个 {@code enabled} 的 Java 字段默认值都是 false，getter 分不出「关着」与「没有」）。
     */
    @Test
    public void allModulesTurnedOff_isNotTheNoModuleError() {
        MockEnvironment env = new MockEnvironment();
        for (String m : SettingsCatalog.MODULES) {
            env.withProperty(SettingsCatalog.PREFIX + m + ".enabled", "false");
        }
        assertEquals("OK", byName(service(env, true).check(), "模块").level(),
                "「关掉」不是「不存在」：三个都写 false 时这条该是 OK，它们还能被打开");
    }

    // ==================== 4.1：能力探测 ====================

    /** 歌曲模块不在时，python / curl 的探测项也不出现（它们只服务歌曲的网易云链路） */
    @Test
    public void capabilities_songAbsent_noProbeItems() {
        EnvCheckService.EnvReport r = service(envWith("shout"), true).check();
        Set<String> names = names(r);
        assertFalse(names.contains("Python（ncm 解密）"), "歌曲不在构建里，却还在探 python");
        assertFalse(names.contains("curl"), "歌曲不在构建里，却还在探 curl");
        // AI 端点的消费方是漫画与歌曲，两个都不在时也不探
        assertFalse(names.contains("本机 AI 端点"), "漫画歌曲都不在构建里，却还在探 AI 端点");
        assertTrue(names.contains("喊麦根"), "喊麦的常规检查不该被能力探测的判据误伤");
    }

    /** 探测结果如实落成 OK / WARN —— 探到什么报什么，不许反过来粉饰 */
    @Test
    public void capabilities_probeResultBecomesOkOrWarn() {
        EnvCheckService.EnvReport ok = service(envWith("song"), true).check();
        assertEquals("OK", byName(ok, "Python（ncm 解密）").level(), "探测可用却报了不是 OK");
        assertEquals("OK", byName(ok, "curl").level(), "探测可用却报了不是 OK");

        // 不可用的探测：单独换一个只会说「不」的 probe
        MangaDictService dictService = mock(MangaDictService.class);
        when(dictService.current()).thenThrow(new RuntimeException("测试不连库"));
        ExternalCommandProbe deadProbe = mock(ExternalCommandProbe.class);
        when(deadProbe.canRun(anyString())).thenReturn(false);
        EnvCheckService dead = new EnvCheckService(new JdbcTemplate(), dictService,
                new MangaProperties(), new SongProperties(), new ShoutProperties(),
                new SettingsCatalog(new MangaProperties(), new SongProperties(), new ShoutProperties(),
                        new LocalAiProperties(), new OnlineAiProperties(), envWith("song")),
                deadProbe, new LocalAiProperties());
        EnvCheckService.EnvReport bad = dead.check();
        assertEquals("WARN", byName(bad, "Python（ncm 解密）").level(),
                "python 起不来必须报出来（4.2 之前是什么都不报，直到下载失败）");
        assertNotNull(byName(bad, "Python（ncm 解密）").hint(), "WARN 没写怎么修");
        assertEquals("WARN", byName(bad, "curl").level(), "curl 起不来必须报出来（4.3 方案 b）");
    }

    /** 本机 AI 端点的三种状态分开报：没配（合法）、配了且可达、配了但连不上 */
    @Test
    public void capabilities_localAiThreeStates() {
        // 没配（出厂值就是空串）：OK —— 前端已把 AI 按钮收起，这不是故障
        EnvCheckService.EnvReport unset = service(envWith("song"), true).check();
        assertEquals("OK", byName(unset, "本机 AI 端点").level(), "没配端点是合法状态，不该挂彩");

        // 配了且可达：OK
        LocalAiProperties up = new LocalAiProperties();
        up.setBaseUrl("http://localhost:11434/v1");
        up.setModel("qwen2.5:7b");
        EnvCheckService.EnvReport ok = serviceWithLocalAi(envWith("song"), up, true).check();
        assertEquals("OK", byName(ok, "本机 AI 端点").level());
        assertTrue(byName(ok, "本机 AI 端点").detail().contains("qwen2.5:7b"),
                "可达时 detail 该带上端点指向哪儿（模型名），方便确认连的是哪台");

        // 配了但连不上：WARN（不是 ERROR —— 端点起来了就恢复，常驻红点只会让人无视红点）
        EnvCheckService.EnvReport down = serviceWithLocalAi(envWith("song"), up, false).check();
        assertEquals("WARN", byName(down, "本机 AI 端点").level(),
                "配了但连不上要报出来（否则 AI 填词失败时没人知道为什么）");
        assertNotNull(byName(down, "本机 AI 端点").hint());
    }

    private static EnvCheckService serviceWithLocalAi(Environment env, LocalAiProperties localAi,
                                                      boolean reachable) {
        MangaDictService dictService = mock(MangaDictService.class);
        when(dictService.current()).thenThrow(new RuntimeException("测试不连库"));
        ExternalCommandProbe probe = mock(ExternalCommandProbe.class);
        when(probe.canRun(anyString())).thenReturn(true);
        return new EnvCheckService(new JdbcTemplate(), dictService,
                new MangaProperties(), new SongProperties(), new ShoutProperties(),
                new SettingsCatalog(new MangaProperties(), new SongProperties(), new ShoutProperties(),
                        new LocalAiProperties(), new OnlineAiProperties(), env),
                probe, localAi) {
            @Override
            protected boolean endpointReachable(String baseUrl) {
                return reachable;
            }
        };
    }
}
