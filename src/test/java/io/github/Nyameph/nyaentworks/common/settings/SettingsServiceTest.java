package io.github.Nyameph.nyaentworks.common.settings;

import io.github.Nyameph.nyaentworks.common.ai.AiAvailability;
import io.github.Nyameph.nyaentworks.common.config.LocalAiProperties;
import io.github.Nyameph.nyaentworks.common.config.OnlineAiProperties;
import io.github.Nyameph.nyaentworks.manga.config.MangaProperties;
import io.github.Nyameph.nyaentworks.manga.service.MangaCompressService;
import io.github.Nyameph.nyaentworks.manga.util.MangaEhLocalDb;
import io.github.Nyameph.nyaentworks.shout.config.ShoutProperties;
import io.github.Nyameph.nyaentworks.song.config.SongProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@link SettingsService#view()} 的分流口径 —— 2026-09-25 加，为的是
 * <b>「模块总开关没有删除路径」</b>这一条：它是「模块不会自己从页面上消失」的全部依据，
 * 而它此前<b>没有任何测试盯</b>（{@code SettingsCatalogTest} 只能看到目录里有什么，
 * 看不到 {@code render} 把谁分到哪儿）。
 *
 * <p>钉的是这一条：
 * <b>模块总开关进 {@code modules}（画成各模块大标题那一行的开关），不进任何 {@code groups}
 * 的卡片</b>。理由是页面上唯一会<b>删掉</b>覆盖文件里某个键的入口，是卡片里的「恢复出厂」
 * 按钮（{@code static/js/settings.js} 的 {@code fieldRow}，条件是 {@code overridden}）；
 * 不进卡片就没有那个按钮，UI 也就删不掉 {@code nya-entworks.<模块>.enabled}。
 *
 * <p>2026-09-25 又加了三条，盯的都是「覆盖层里只留偏离出厂值的项」这套口径
 * （用户报的两个问题：「一打开所有项都显示已改」与「改了本地 eh 库、保存后填的内容没了」）：
 * {@code view_showsTheOverlayValue…}（输入框画文件里那一份，不画生效值）、
 * {@code view_keyEqualToFactoryDefaultIsNotMarkedOverridden}（「已改」认的是偏离出厂值）、
 * {@code save_keepsOnlyDeviatingItems}（保存只留偏离出厂值的项，
 * 但模块总开关一个都不许少）。
 *
 * <p>2026-09-25 当天再补两条，盯的是「<b>输入框画的是『重启后会生效的值』</b>」这个定义 ——
 * 覆盖层里没有这一项时它要往下找一层（密钥文件，再没有就是出厂值），而不是把进程里那个
 * 还没重启的旧值画回去：{@code view_keyRemovedFromOverlay…}（用户报「删除本地 eh 库后
 * 保存，删除的内容又恢复了」）与 {@code view_valueFromSecretFileIsShown…}
 * （cookie 从密钥文件搬进覆盖层那个过渡状态，{@code docs/配置页设计.md} §14.1）。
 *
 * <p>为什么这条值得单独钉：那个键删掉 ＝ 该模块在下次启动时**从构建里消失**
 * （配置页连开关带卡片整块不显示、左侧导航没有入口），而那正是打包裁剪用的同一个机制 ——
 * 用户手滑点一下就变成「点没了、还找不回来」。若有人日后把 {@code render} 简化成
 * 「所有项一视同仁地进卡片」，目录侧的三条测试（唯一性 / 恰好一个 / 键名对上）**全都照样绿**。
 *
 * <p>纯单测：三个 Properties 直接 {@code new}，属性源用 {@link MockEnvironment}；
 * 覆盖文件路径改成 {@link TempDir} 下的空路径 ——
 * 否则会读到开发者本机那份 gitignored 的 {@code config/nya-entworks.yaml}。
 *
 * <p>{@code SettingsNotices} 这几条提示在别的用例里也会顺带算一遍（模块开关按属性源里的
 * {@code true} 算，于是不早退），但没人断言它的内容 —— 只有
 * {@code view_ehLocalDbRemoved_noticeAppearsWithoutRestart} 关心。
 */
public class SettingsServiceTest {

    /** 一份不读本机那两份配置文件的服务（空临时目录 = 两份都不在 = 全部「未覆盖」） */
    private static SettingsService service(Path dir, SettingsCatalog catalog) {
        return service(dir, catalog, new MangaProperties(), mock(MangaEhLocalDb.class));
    }

    /**
     * 同上，但可以指定「运行中的后端」那份 Properties 与真的 {@link MangaEhLocalDb}
     * （它构造时把路径快照走，正是「文件改了、进程里还是旧的」那条链的源头）。
     */
    private static SettingsService service(Path dir, SettingsCatalog catalog,
                                           MangaProperties live, MangaEhLocalDb localDb) {
        SettingsNotices notices = new SettingsNotices(live,
                mock(MangaCompressService.class), localDb, mock(AiAvailability.class));
        MockEnvironment env = new MockEnvironment();
        for (String m : SettingsCatalog.MODULES) {
            env.withProperty(SettingsCatalog.PREFIX + m + ".enabled", "true");
        }
        Path file = dir.resolve("nya-entworks.yaml");
        // 密钥文件也要指走：不指的话读到的是开发者本机那份 gitignored 的
        // config/application-secret.yaml —— 里面真有键时，断言会随本机状态时绿时红
        Path secret = dir.resolve("application-secret.yaml");
        return new SettingsService(catalog, env, notices) {
            @Override
            public Path filePath() {
                return file;
            }

            @Override
            Path secretPath() {
                return secret;
            }
        };
    }

    /**
     * 模块总开关只出现在 {@code modules} 里、一个卡片里都没有 ——
     * 于是页面上画不出「恢复出厂」，UI 删不掉那个键，模块不会自己消失。
     */
    @Test
    public void view_moduleSwitchesAreNotInAnyCard(@TempDir Path dir) {
        SettingsCatalog catalog = new SettingsCatalog(new MangaProperties(), new SongProperties(),
                new ShoutProperties(), new LocalAiProperties(), new OnlineAiProperties(),
                envWithAllModules());
        SettingsService.View view = service(dir, catalog).view();

        // 三个模块各有一条大标题记录，它的 toggle 就是保存时要回传的那个键
        assertEquals(3, view.modules().size(), "模块大标题不是三个：" + view.modules());
        for (SettingsService.ModuleView m : view.modules()) {
            assertEquals(SettingsCatalog.PREFIX + m.id() + ".enabled", m.toggle().key(),
                    "模块「" + m.id() + "」那一行的开关不是它的总开关键");
            assertTrue(m.toggle().moduleSwitch(),
                    "模块「" + m.id() + "」的开关没被标成 moduleSwitch，前端画不对");
        }

        // 反面：卡片里一个模块开关都不许有 —— 有就等于给它配了个「恢复出厂」按钮
        List<String> moduleKeys = List.of(
                SettingsCatalog.PREFIX + "manga.enabled",
                SettingsCatalog.PREFIX + "song.enabled",
                SettingsCatalog.PREFIX + "shout.enabled");
        List<String> cardKeys = new ArrayList<>();
        for (SettingsService.Group g : view.groups()) {
            for (SettingsService.Field f : g.fields()) {
                cardKeys.add(f.key());
                assertFalse(moduleKeys.contains(f.key()),
                        "模块总开关「" + f.key() + "」进了卡片「" + g.title()
                                + "」—— 那张卡片会画出「恢复出厂」，点一下这个模块就从页面上消失了");
            }
        }
        assertFalse(cardKeys.isEmpty(), "一个卡片都没有，这条断言没验到东西");

        // 反向：普通项不许被当成模块总开关提溜出来（提出来就没人画它了）
        for (SettingsService.ModuleView m : view.modules()) {
            assertTrue(m.toggle().moduleSwitch(), "modules 里混进了不是模块开关的项：" + m.toggle().key());
        }
    }

    /** 「已覆盖」这一位来自覆盖文件里有没有这个键 —— 空目录下应当全是 false */
    @Test
    public void view_withoutOverlayFile_nothingIsMarkedOverridden(@TempDir Path dir) {
        SettingsService.View view = service(dir, catalog()).view();
        assertFalse(view.fileExists(), "测试用的空目录里不该有覆盖文件");
        assertEquals(0, view.pendingCount(), "没有覆盖文件却算出了「改了还没重启」的项");
        for (SettingsService.Group g : view.groups()) {
            for (SettingsService.Field f : g.fields()) {
                assertFalse(f.overridden(), "没有覆盖文件却标记了「已覆盖」：" + f.key());
            }
        }
    }

    /**
     * 输入框里画的是<b>覆盖文件里那一份</b>，不是「现在生效的那一份」。
     *
     * <p>钉的是 2026-09-25 用户报的第二个问题：「我修改本地 eh 库后点保存，结果我填入的内容没有了」。
     * 因果链：{@code MangaEhLocalDb} 那类 Bean 在<b>构造时</b>就把路径快照走了，所以刚保存的值
     * 还没生效，{@code current()} 仍是旧的空串；输入框若画生效值，保存后 {@code load()} 重画整页
     * 时刚填的路径就<b>跳回空</b>；更要紧的是再点一次保存，{@code collect()} 提交的是页面上那个空串，
     * 于是那条路径被真的从文件里抹掉 —— 静默丢数据。
     */
    @Test
    public void view_showsTheOverlayValue_soAFreshEditDoesNotJumpBack(@TempDir Path dir)
            throws Exception {
        SettingsService svc = service(dir, catalog());
        Files.writeString(svc.filePath(), """
                nya-entworks:
                  manga:
                    archive-dir: 'D:/已归档'
                """);
        SettingsService.Field f = fieldOf(svc.view(), "nya-entworks.manga.archive-dir");
        assertEquals("D:/已归档", f.value(),
                "输入框画的是生效值（还没重启时是旧的）—— 保存后刚填的路径会从页面上跳回去，"
                        + "而再保存一次就把它写没了");
        assertTrue(f.pending(), "文件里的值与生效值不同却没标「待重启」");
        assertTrue(f.overridden(), "文件里写着这一项却没标「已改」");
    }

    /**
     * 覆盖层里<b>没有</b>这一项时，输入框画「重启后会生效的值」（＝出厂值），并且挂上「待重启」。
     *
     * <p>钉的是 2026-09-25 用户报的第一个问题：「删除本地 eh 库配置项后点保存，结果删除的内容又恢复了」。
     * 因果链：删掉那一项落在文件里了（键没了），可<b>进程里</b>那个值还是启动时快照的
     * （{@code MangaEhLocalDb} 构造时读的就是它，扫描照旧走本地库），而 {@code view()} 在
     * 「文件里没有这一项」时画的是 {@code current()} —— 于是旧值被画回输入框，用户以为没删掉；
     * 再点一次保存，{@code collect()} 提交的就是那个旧值，它被真的写回文件（静默复活）。
     *
     * <p>旧口径的第二个后果是不说人话：这一项的 {@code pending} 判的是「文件里那一份 ≠ 生效值」，
     * 键不在文件里时恒为 false —— 页面上既没有「待重启」，也没有顶部那句
     * 「有 N 项改动还没生效」，用户就只剩「删了、又回来了」这一个信号。
     */
    @Test
    public void view_keyRemovedFromOverlay_fallsBackToFactoryAndIsMarkedPending(@TempDir Path dir) {
        // 模拟「进程启动时覆盖层里还写着这一项」：Bean 里那个值就是当时快照下来的，
        // 所以它与出厂值不同、而文件里已经没有它了
        MangaProperties live = new MangaProperties();
        live.getEhScan().setLocalDbPath("data/eh-gallery.db");
        SettingsService svc = service(dir, catalog(live));
        SettingsService.Field f = fieldOf(svc.view(), "nya-entworks.manga.eh-scan.local-db-path");

        assertEquals("", f.value(), "删掉的那一项又把进程里那个旧值画了回来 —— 用户会以为没删掉，"
                + "而且再点一次保存就把它真写回文件（静默复活）");
        assertTrue(f.pending(), "文件里没有、生效值却还是旧的那一份，没标「待重启」——"
                + "页面上就只剩「删了、又回来了」这一个信号");
        assertFalse(f.overridden(), "覆盖层里没有这一项，却标了「已改」");
    }

    /**
     * 覆盖层里没写、生效值却是这一项时，画<b>密钥文件</b>那一份，而且<b>不算</b>「待重启」。
     *
     * <p>这一层是为「e-hentai 的 cookie 从密钥文件搬进覆盖层」那个过渡状态留的
     * （{@code docs/配置页设计.md} §14.1）：那时覆盖层里还没有这个键，值只可能来自
     * {@code config/application-secret.yaml}。画空 + 挂「待重启」会让「先看一眼值在」
     * 这一步无从下手 —— 而少了这一步，人就会先删密钥文件那一行，cookie 真的丢。
     *
     * <p>反面（同一条测试里钉住）：密钥文件里<b>不是设置项</b>的键，值一个都不许下发 ——
     * 本类只按 {@code specs()} 里的键去查它。
     */
    @Test
    public void view_valueFromSecretFileIsShown_butOtherSecretKeysAreNot(@TempDir Path dir)
            throws Exception {
        String dbPath = "D:/本机快照/eh-gallery.db";
        MangaProperties live = new MangaProperties();
        live.getEhScan().setLocalDbPath(dbPath);          // 生效值就是密钥文件给的
        SettingsService svc = service(dir, catalog(live));
        Files.writeString(dir.resolve("application-secret.yaml"), """
                nya-entworks:
                  manga:
                    eh-scan:
                      local-db-path: '%s'
                  song:
                    netease-cookie: '不该下发的值'
                """.formatted(dbPath));

        SettingsService.View view = svc.view();
        SettingsService.Field f = fieldOf(view, "nya-entworks.manga.eh-scan.local-db-path");
        assertEquals(dbPath, f.value(), "密钥文件里那一份没画出来 —— 「先看一眼值在」这一步会扑空");
        assertFalse(f.pending(), "值与生效值一样却标了「待重启」");
        assertFalse(f.overridden(), "密钥文件里那一项被算成了「已改」（它不在覆盖层里）");
        assertFalse(String.valueOf(view).contains("不该下发的值"),
                "密钥文件里不是设置项的键，值被下发到页面上了");
    }

    /**
     * 删掉本地 eh 库那一项之后，<b>不用重启</b>就该有那条 INFO 提示。
     *
     * <p>钉的是 2026-09-25 作者提的第三条：「eh 库删除依旧没有任何提示」——
     * 提示原先判的是 {@link MangaEhLocalDb#available()}，也就是<b>进程里</b>那份路径快照，
     * 而它要重启才会变；于是「删了、保存、页面重画」之后提示照旧不出现。
     * 现在它判的是「重启后会生效的值」（与输入框同一个阶梯），保存完立刻就有说法；
     * 「这一项还没生效」由提示里那句「⚠️ 这一项刚改过、还没重启」补上。
     */
    @Test
    public void view_ehLocalDbRemoved_noticeAppearsWithoutRestart(@TempDir Path dir) throws Exception {
        // 模拟「删除之前启动的那个进程」：Bean 里那条路径当时是真能用的
        Path db = Files.createFile(dir.resolve("eh-gallery.db"));
        MangaProperties live = new MangaProperties();
        live.setEnabled(true);
        live.getEhScan().setLocalDbPath(db.toString());
        MangaEhLocalDb localDb = new MangaEhLocalDb(live);   // 构造时快照，与生产同一个来路
        assertTrue(localDb.available(), "测试前提不成立：这份路径本来就该是可用的");

        // 覆盖层里没有这一项（＝页面上把它删了、保存了），进程却还没重启
        SettingsService svc = service(dir, catalog(live), live, localDb);
        // 模块总开关是「这个构建里有没有这个模块」的唯一来路（application.yaml 里有意识不留一份），
        // 所以那一行得在这里 —— 提示条的闸门也按它算（`docs/配置页设计.md` §12、§16.3）
        Files.writeString(svc.filePath(), """
                nya-entworks:
                  manga:
                    enabled: true
                """);
        SettingsService.Field f = fieldOf(svc.view(), "nya-entworks.manga.eh-scan.local-db-path");
        assertEquals("", f.value(), "删除没反映到输入框上");
        assertTrue(f.pending(), "删掉这一项之后没标「待重启」");

        SettingsNotices.Notice notice = noticeTitled(svc.view(), "没配本地 eh 库");
        assertTrue(notice.text().contains("还没重启"),
                "提示里没说清「这一项其实还没生效、现在仍在用本地库」：" + notice.text());
        assertTrue(notice.text().contains(db.toString()),
                "提示没说出此刻仍在用的是哪一份路径：" + notice.text());
    }

    /** 配置页顶部那条提示（按标题找），找不到就让测试失败 */
    private static SettingsNotices.Notice noticeTitled(SettingsService.View view, String titlePart) {
        for (SettingsNotices.Notice n : view.notices()) {
            if (n.title().contains(titlePart)) {
                return n;
            }
        }
        throw new AssertionError("提示条里没有「" + titlePart + "」那一条，只有：" + view.notices());
    }

    /**
     * <b>「已改」＝ 文件里写着、而且值偏离了出厂值</b>，不是「文件里有这个键」。
     *
     * <p>老版本每次保存都把整份快照写进文件（每个键都写着，值却大多一个没改），
     * 于是配置页一打开满屏「已改」+满屏「恢复出厂」按钮（用户 2026-09-25 报的第一个问题）。
     * 值等于出厂值的条目<b>不算改过</b>，保存时也会被顺手清掉（见
     * {@link #save_keepsOnlyDeviatingItems}），两处同一个口径。
     */
    @Test
    public void view_keyEqualToFactoryDefaultIsNotMarkedOverridden(@TempDir Path dir)
            throws Exception {
        // 出厂值不写死在测试里：问一个没绑过属性源的 Bean 要，它给的就是出厂值
        // （写死 'F:/MangaGroup' 会输给反斜杠那版，于是这条测试自己就成了假阳性）
        String factoryArchiveDir = new MangaProperties().getArchiveDir();
        SettingsService svc = service(dir, catalog());
        Files.writeString(svc.filePath(), "nya-entworks:\n  manga:\n"
                + "    archive-dir: '" + factoryArchiveDir + "'\n"
                + "    nconvert: 'C:/tools/nconvert.exe'\n");
        assertFalse(fieldOf(svc.view(), "nya-entworks.manga.archive-dir").overridden(),
                "值就等于出厂值的条目被标成了「已改」—— 页面上会满屏都是这两个字");
        assertTrue(fieldOf(svc.view(), "nya-entworks.manga.nconvert").overridden(),
                "真改过的项反而没标「已改」");
    }

    /**
     * 保存后文件里<b>只留偏离出厂值的项</b>，而模块总开关一个都不许少。
     *
     * <p>提交的值按页面上的实际做法取：{@code view()} 画什么就提交什么（含模块开关那一行）。
     * 于是这条测试同时钉住两件事：
     * <ul>
     *   <li>老版本攒下的「整份快照」会被顺手清掉，且 {@code cleaned} 与 {@code changed}
     *       分得清 —— 后者是真正动了生效值的项，一次「只清库存」的保存该报 0；</li>
     *   <li><b>模块总开关绝不因为「值等于出厂值」被删掉</b>：它写的是 {@code false}
     *       （等于 Java 字段默认值），可它的<b>键在不在</b>就是「这个构建里有没有这个模块」——
     *       删掉就等于把那个模块从页面上整个抹掉，连关着的开关一起（唯一的自救入口）。</li>
     * </ul>
     */
    @Test
    public void save_keepsOnlyDeviatingItems(@TempDir Path dir) throws Exception {
        SettingsCatalog catalog = catalog();
        SettingsService svc = service(dir, catalog);
        // 一份「老版本攒下的快照」：三项都写着，其中归档根那一项的值就等于出厂值
        Files.writeString(svc.filePath(), "nya-entworks:\n  manga:\n"
                + "    enabled: false\n"
                + "    archive-dir: '" + new MangaProperties().getArchiveDir() + "'\n"
                + "    nconvert: 'C:/tools/nconvert.exe'\n"
                + "  song:\n    enabled: false\n"
                + "  shout:\n    enabled: false\n");
        Map<String, String> values = submittedValues(svc.view());
        SettingsService.SaveResult result = svc.save(values);

        String text = Files.readString(svc.filePath());
        assertEquals(0, result.changed(), "一次只清库存的保存报了「改了 N 项」：" + text);
        assertEquals(1, result.cleaned(), "等于出厂值的归档根没被清掉：" + text);
        assertFalse(text.contains("archive-dir"),
                "值等于出厂值的条目还留在文件里 —— 页面上它会一直挂着「已改」：" + text);
        assertTrue(text.contains("nconvert"), "偏离出厂值的项被误删了：" + text);
        assertEquals(3, text.split("enabled:", -1).length - 1,
                "模块总开关少了 —— 那个模块下次启动就从页面上整个消失（裁剪的介质就是这几个键）：" + text);
    }

    /** 页面提交的东西：{@code view()} 画什么就提交什么（模块开关那一行也在里面） */
    private static Map<String, String> submittedValues(SettingsService.View view) {
        Map<String, String> values = new LinkedHashMap<>();
        view.modules().forEach(m -> values.put(m.toggle().key(), m.toggle().value()));
        view.groups().forEach(g -> g.fields().forEach(f -> values.put(f.key(), f.value())));
        return values;
    }

    private static SettingsService.Field fieldOf(SettingsService.View view, String key) {
        for (SettingsService.Group g : view.groups()) {
            for (SettingsService.Field f : g.fields()) {
                if (f.key().equals(key)) {
                    return f;
                }
            }
        }
        for (SettingsService.ModuleView m : view.modules()) {
            if (m.toggle().key().equals(key)) {
                return m.toggle();
            }
        }
        throw new AssertionError("目录里没有这一项：" + key);
    }

    /** 三个模块都直接 {@code new}（值全是 Java 字段默认值，也就是出厂值） */
    private static SettingsCatalog catalog() {
        return catalog(new MangaProperties());
    }

    /**
     * 用一份给定的漫画 Properties 造目录。传一份「值已经与出厂值不同」的进来，
     * 就等于模拟<b>运行中的后端</b>（Bean 里是启动时绑定的那个值）—— 那正是
     * 配置页上「文件改了、还没重启」这类状态的全部来路。
     */
    private static SettingsCatalog catalog(MangaProperties manga) {
        return new SettingsCatalog(manga, new SongProperties(),
                new ShoutProperties(), new LocalAiProperties(), new OnlineAiProperties(),
                envWithAllModules());
    }

    private static MockEnvironment envWithAllModules() {
        MockEnvironment env = new MockEnvironment();
        for (String m : SettingsCatalog.MODULES) {
            env.withProperty(SettingsCatalog.PREFIX + m + ".enabled", "true");
        }
        return env;
    }
}
