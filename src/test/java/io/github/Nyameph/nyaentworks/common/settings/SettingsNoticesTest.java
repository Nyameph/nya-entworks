package io.github.Nyameph.nyaentworks.common.settings;

import io.github.Nyameph.nyaentworks.common.ai.AiAvailability;
import io.github.Nyameph.nyaentworks.manga.config.MangaProperties;
import io.github.Nyameph.nyaentworks.manga.service.MangaCompressService;
import io.github.Nyameph.nyaentworks.manga.util.MangaEhLocalDb;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 配置页顶部提示条的判据 —— 2026-09-25 加，钉的是<b>「判『重启后会生效的值』，不判进程里那份快照」</b>。
 *
 * <p>起因是作者报的「eh 库删除依旧没有任何提示，而且需要改成一删除不用重启就有」：
 * 提示原先判 {@link MangaEhLocalDb#available()}（进程里那份<b>构造时</b>快照的路径），
 * 删掉那一项、保存、页面重画之后照旧不出现 —— 要重启后端才轮到它变。
 * 现在判据换成了与输入框同一个值（{@code SettingsService#restartValue} 的
 * 覆盖层 > 密钥文件 > 出厂值），<b>保存完立刻就有说法</b>；
 * 代价是「重启前不算数」，所以「这项还没生效」必须由提示自己说清楚（{@link SettingsNotices} 的
 * {@code staleClause}）。
 *
 * <p>整张卡片都按那一份算：模块开关是否在、三个 AI 开关是否要提示，也走 {@code effective}
 * 那张表 —— 于是「这一页上写的」与「这张卡片说的」永远一致。
 *
 * <p>纯单测：{@code MangaProperties} / {@code MangaCompressService} / {@code MangaEhLocalDb}
 * 都直接 {@code new}（文件用 {@link TempDir} 里造的空文件顶替），不连库、不碰 {@code F:\}。
 */
public class SettingsNoticesTest {

    private static final String EH = SettingsCatalog.PREFIX + "manga.eh-scan.local-db-path";
    private static final String NCONVERT = SettingsCatalog.PREFIX + "manga.nconvert";
    private static final String MANGA_ENABLED = SettingsCatalog.PREFIX + "manga.enabled";
    private static final String MANGA_AI = SettingsCatalog.PREFIX + "manga.eh-scan.ai-enabled";
    private static final String SONG_ENABLED = SettingsCatalog.PREFIX + "song.enabled";
    private static final String AI_BASE_URL = SettingsCatalog.PREFIX + "common.local-ai.base-url";
    private static final String AI_MODEL = SettingsCatalog.PREFIX + "common.local-ai.model";

    /** 提示条；「进程里那台 AI 端点」按「没配」算（只影响 stale 那句），见下面那个重载 */
    private static SettingsNotices notices(MangaProperties live, MangaCompressService compress) {
        return notices(live, compress, false);
    }

    /**
     * @param aiLiveReady 进程里那台 AI 端点配好了没有 —— 只喂 {@code staleClause} 那句
     *                    （「此刻实际上是什么样」是全类唯一读进程值的地方）
     */
    private static SettingsNotices notices(MangaProperties live, MangaCompressService compress,
                                           boolean aiLiveReady) {
        AiAvailability ai = mock(AiAvailability.class);
        when(ai.localReady()).thenReturn(aiLiveReady);
        return new SettingsNotices(live, compress, new MangaEhLocalDb(live), ai);
    }

    /**
     * 删掉本地 eh 库那一项、<b>还没重启</b>：提示立刻就有，并且说清此刻仍在用旧的那份。
     *
     * <p>这就是作者要的那条：一删除、不用重启就看得见。
     */
    @Test
    public void ehLocalDbRemoved_noticeShowsUpRightAway_andSaysItIsNotInEffectYet(@TempDir Path dir)
            throws IOException {
        MangaProperties live = liveProps(dir);
        SettingsNotices notices = notices(live, new MangaCompressService(live));

        SettingsNotices.Notice n = onlyNotice(notices.notices(
                effective(Map.of(EH, "", NCONVERT, live.getNconvert())), Set.of(EH)));
        assertEquals(SettingsNotices.INFO, n.level(), "本地库缺了是 INFO（功能一个没少，只是变慢）");
        assertTrue(n.text().contains("还没重启"),
                "这一项其实还没生效，提示里却没说 —— 读起来像已经删干净了：" + n.text());
        assertTrue(n.text().contains(live.getEhScan().getLocalDbPath()),
                "没说清此刻仍在用哪一份路径：" + n.text());
    }

    /**
     * 同一件事，但这一项<b>本来就是这样</b>（不是刚改的）：提示里不该出现「刚改过」那句 ——
     * 它是在启动时就没配，本来就一直在联网搜索。
     */
    @Test
    public void ehLocalDbMissingSinceStartup_hasNoStaleClause(@TempDir Path dir) throws IOException {
        MangaProperties live = liveProps(dir);
        live.getEhScan().setLocalDbPath("");            // 启动时就没配
        MangaCompressService compress = new MangaCompressService(live);

        SettingsNotices.Notice n = onlyNotice(notices(live, compress).notices(
                effective(Map.of(EH, "", NCONVERT, live.getNconvert())), Set.of()));
        assertFalse(n.text().contains("还没重启"),
                "这一项从来没改过，提示里却说「刚改过、还没重启」：" + n.text());
    }

    /**
     * 反方向：文件里已经<b>配好</b>了一条能用的路径、只是还没重启 —— 提示条不说话。
     *
     * <p>这是刻意留下的不对称：提示条说的是「按这份配置启动会怎样」，重启前那一小段时间的降级
     * 由页面上那句「有 N 项改动还没生效」＋「待重启」徽章负责。两处都在喊同一件事只会吵。
     */
    @Test
    public void pathConfiguredButNotRestarted_yet_noNotice(@TempDir Path dir) throws IOException {
        MangaProperties live = liveProps(dir);
        String good = live.getEhScan().getLocalDbPath();
        Path newDb = Files.createFile(dir.resolve("新库.db"));

        List<SettingsNotices.Notice> list = notices(live, new MangaCompressService(live)).notices(
                effective(Map.of(EH, newDb.toString(), NCONVERT, live.getNconvert())), Set.of(EH));
        assertTrue(list.isEmpty(),
                "重启后会好起来，提示条却说现在还缺着（这条只该由「待重启」徽章负责）：" + list);
        assertTrue(MangaEhLocalDb.availableAt(good), "测试前提不成立：活的那份路径该是可用的");
    }

    /** NConvert 那一半同理：删掉之后立刻出 WARN，并说清此刻归档仍在压缩 */
    @Test
    public void nconvertRemoved_noticeShowsUpRightAway_andSaysStillCompressing(@TempDir Path dir)
            throws IOException {
        MangaProperties live = liveProps(dir);
        SettingsNotices.Notice n = onlyNotice(notices(live, new MangaCompressService(live)).notices(
                effective(Map.of(EH, live.getEhScan().getLocalDbPath(), NCONVERT, "")), Set.of(NCONVERT)));
        assertEquals(SettingsNotices.WARN, n.level(), "NConvert 缺了是 WARN（压缩整个没了）");
        assertTrue(n.text().contains("仍在压缩"), "没说清此刻归档其实还在压缩：" + n.text());
    }

    /**
     * AI 端点那一半也是同一套：在页面上清掉端点、保存完立刻出 WARN。
     *
     * <p>它的判据原先读的是 {@code AiAvailability} 里那份绑好的 {@code LocalAiProperties}，
     * 与路径那两条是同一个毛病；现在改判传进来的那份（{@code AiAvailability#localDenyAt}）。
     */
    @Test
    public void aiEndpointRemoved_noticeShowsUpRightAway_andSaysStillUsingTheOldOne(@TempDir Path dir)
            throws IOException {
        MangaProperties live = liveProps(dir);
        // 进程里那台端点还配着（只是文件里删了）→ stale 那句应当说「还在用旧的」
        SettingsNotices notices = notices(live, new MangaCompressService(live), true);

        SettingsNotices.Notice n = onlyNotice(notices.notices(effective(Map.of(
                EH, live.getEhScan().getLocalDbPath(), NCONVERT, live.getNconvert(),
                AI_BASE_URL, "", AI_MODEL, "")), Set.of(AI_BASE_URL, AI_MODEL)));
        assertEquals(SettingsNotices.WARN, n.level());
        assertTrue(n.title().contains("AI"), "本条该是 AI 那条提示：" + n.title());
        assertTrue(n.text().contains("还没重启") && n.text().contains("还在用旧的那台端点"),
                "没说清此刻三处 AI 其实还在用旧端点：" + n.text());

        // 端点本来就一直没配（不是刚改的）：提示照出，但不带「刚改过」那句
        SettingsNotices.Notice plain = onlyNotice(notices.notices(effective(Map.of(
                EH, live.getEhScan().getLocalDbPath(), NCONVERT, live.getNconvert(),
                AI_BASE_URL, "", AI_MODEL, "")), Set.of()));
        assertFalse(plain.text().contains("还没重启"), "没改过却说「刚改过」：" + plain.text());
    }

    /** 三个 AI 功能各自都没要用的（模块关着 / 开关关着）时，端点缺了也不提示 —— 挂个黄条只是噪音 */
    @Test
    public void aiNotWantedByAnyFeature_noNotice(@TempDir Path dir) throws IOException {
        MangaProperties live = liveProps(dir);
        List<SettingsNotices.Notice> list = notices(live, new MangaCompressService(live)).notices(
                effective(Map.of(EH, live.getEhScan().getLocalDbPath(),
                        NCONVERT, live.getNconvert(),
                        MANGA_ENABLED, "false", SONG_ENABLED, "false",
                        AI_BASE_URL, "", AI_MODEL, "")), Set.of());
        assertTrue(list.isEmpty(), "没有哪个功能要用 AI，却还是挂了端点的提示：" + list);
    }

    /** 两条都配得好好的时候，一条提示都没有（页面顶部那块整段不画） */
    @Test
    public void everythingConfigured_noNotice(@TempDir Path dir) throws IOException {
        MangaProperties live = liveProps(dir);
        List<SettingsNotices.Notice> list = notices(live, new MangaCompressService(live)).notices(
                effective(Map.of(EH, live.getEhScan().getLocalDbPath(),
                        NCONVERT, live.getNconvert())), Set.of());
        assertTrue(list.isEmpty(), "配置齐全却还是挂了提示：" + list);
    }

    /**
     * 模块关着的时候一条都不出（关着的模块不该在页面上留下跟它有关的告警）。
     *
     * <p>判的是<b>文件里那一份</b>：所以刚在页面上关掉、还没重启时就已经不画了 ——
     * 「按这一页上的配置启动」正是这张卡片的题目。
     */
    @Test
    public void moduleTurnedOff_noNotice(@TempDir Path dir) throws IOException {
        MangaProperties live = liveProps(dir);      // 进程里还开着，文件里已经关掉
        List<SettingsNotices.Notice> list = notices(live, new MangaCompressService(live)).notices(
                effective(Map.of(EH, "", NCONVERT, "", MANGA_ENABLED, "false")), Set.of(EH, NCONVERT));
        assertTrue(list.isEmpty(), "漫画模块关着却还在报它的缺项：" + list);
    }

    /** 页面那份 Properties：两条路径的「活着的那份」都指向真实文件 */
    private static MangaProperties liveProps(Path dir) throws IOException {
        MangaProperties p = new MangaProperties();
        p.setEnabled(true);
        p.getEhScan().setLocalDbPath(Files.createFile(dir.resolve("eh-gallery.db")).toString());
        p.setNconvert(Files.createFile(dir.resolve("nconvert.exe")).toString());
        return p;
    }

    /**
     * 全键 → 值的可写副本。先把几道闸门按「开着、配好」填上，再叠上本用例关心的那几项 ——
     * 于是每个用例只需要说自己关心的那件事，不必每处都摆一遍模块开关与 AI 端点。
     */
    private static Map<String, String> effective(Map<String, String> base) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(MANGA_ENABLED, "true");
        m.put(MANGA_AI, "true");
        m.put(SONG_ENABLED, "true");
        m.put(AI_BASE_URL, "http://127.0.0.1:11434");
        m.put(AI_MODEL, "qwen2.5:7b");
        m.putAll(base);
        return m;
    }

    private static SettingsNotices.Notice onlyNotice(List<SettingsNotices.Notice> list) {
        assertEquals(1, list.size(), "本该只有一条提示：" + list);
        return list.get(0);
    }
}
