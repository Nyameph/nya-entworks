package io.github.Nyameph.nyaentworks.common.settings;

import io.github.Nyameph.nyaentworks.common.config.LocalAiProperties;
import io.github.Nyameph.nyaentworks.common.config.OnlineAiProperties;
import io.github.Nyameph.nyaentworks.manga.config.MangaProperties;
import io.github.Nyameph.nyaentworks.shout.config.ShoutProperties;
import io.github.Nyameph.nyaentworks.song.config.SongProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置页元数据目录（{@link SettingsCatalog}）的自检。
 *
 * <p>盯的是几件<b>错了不报、只静默</b>的事：
 * <ul>
 *   <li><b>每一项的键都得带 {@code nya-entworks.} 前缀</b> —— 全键是这一页的唯一口径：
 *       接口下发的 {@code key}、前端回传的 {@code key}、覆盖文件里读回来的键，三处必须是同一个串。
 *       少一个前缀，页面照画、保存照存，但「这一项改过没有」「改了还没重启」永远判成没有
 *       （2026-09-17 实测踩到：{@code overridden} 全 false、预演永远「没有要改的项」、
 *       存下去的值与读回来的值对不上）。</li>
 *   <li><b>项不许重复</b> —— 重复的键在页面上是两行同一个 {@code data-key}，
 *       收集提交值时后一个盖掉前一个，改哪一行都一样。</li>
 *   <li><b>类型得是前端认识的那几个</b> —— 前端按类型决定画输入框还是复选框，
 *       冒出第六种类型会静默退化成文本框。</li>
 *   <li><b>密钥只放行一个键</b> —— {@code netease-cookie} 与两个 {@code api-key} 在同一目录下的
 *       {@code application-secret.yaml} 里，收进本页就是「改了不生效」；
 *       何况这一页与 {@code EnvController} 一样无鉴权，明文回显白白扩大暴露面。
 *       <b>例外只有 {@code manga.eh-scan.cookie}</b>（2026-09-25 拍板口径 a：它必须与
 *       站点根成对换），由 {@code specs_onlyTheEhCookieIsEditable} 精确钉住这一个键。
 *       其余密钥的状态另走 {@link SettingsCatalog#secretStatus()} 一条只读的路，
 *       与 {@code specs()} 一个键都不许重合（{@code secretStatus_neverOverlapsSpecs}）。</li>
 *   <li><b>每一项的当前值都读得出来</b> —— 值来自各 Properties Bean 的 getter，
 *       嵌套对象没在字段声明处初始化就会在页面加载时 NPE。</li>
 *   <li><b>「本次构建里没有的模块」整块不出现</b>，而「关着的模块」必须原样出现 ——
 *       前者是打包裁剪的依据（删掉配置段＝藏起模块），后者是页面上唯一的自救入口
 *       （关掉之后还得能打开）。两者的分界只有一条：{@code <模块>.enabled} 这个键
 *       读不读得到，见 {@code specs_hidesModulesWhoseEnabledKeyIsAbsent} 与
 *       {@code specs_aModuleTurnedOffIsStillInTheCatalog}。</li>
 * </ul>
 *
 * <p>纯单测：三个 Properties 都直接 {@code new}（嵌套对象是在字段声明处就 new 好的），
 * 属性源用一个只装着 {@code *.enabled} 三行的 {@link MockEnvironment}，
 * 不连库、不碰 {@code F:\}。
 */
public class SettingsCatalogTest {

    /** 前端按这几个值决定画什么控件，多一个少一个都要改前端 */
    private static final List<String> KNOWN_TYPES = List.of(
            SettingsCatalog.PATH_DIR, SettingsCatalog.PATH_FILE,
            SettingsCatalog.TEXT, SettingsCatalog.INT, SettingsCatalog.BOOL);

    /**
     * 「归档前强制打标签」那三项（漫画 / 歌曲 / 喊麦各一）。
     * <p>顺序与 {@code specs()} 里的顺序一致，断言按下标取值 —— 所以这里加项、换序都得同步改断言。
     */
    private static final List<String> REQUIRE_TAGS_KEYS = List.of(
            SettingsCatalog.PREFIX + "manga.require-tags-before-archive",
            SettingsCatalog.PREFIX + "song.require-tags-before-archive",
            SettingsCatalog.PREFIX + "shout.require-tags-before-archive");

    private SettingsCatalog catalog;

    /**
     * 一个「本次构建里有这几个模块」的属性源 —— 每个模块一行 {@code <模块>.enabled}。
     *
     * <p>值一律写 {@code true}：这一层只回答「键在不在」，值是多少跟它无关，
     * 那由各 Properties Bean 回答。不给这三行的话目录里只剩通用项 ——
     * 目录是按「键读得到没有」整块过滤的。
     */
    private static Environment envWith(String... modules) {
        MockEnvironment env = new MockEnvironment();
        for (String m : modules) {
            env.withProperty(SettingsCatalog.PREFIX + m + ".enabled", "true");
        }
        return env;
    }

    @BeforeEach
    public void setUp() {
        catalog = new SettingsCatalog(new MangaProperties(), new SongProperties(),
                new ShoutProperties(), new LocalAiProperties(), new OnlineAiProperties(),
                envWith(SettingsCatalog.MODULES.toArray(new String[0])));
    }

    @Test
    public void specs_keysAreFullQualifiedAndUnique() {
        List<String> keys = new ArrayList<>();
        for (SettingsCatalog.Spec spec : catalog.specs()) {
            keys.add(spec.key());
            assertTrue(spec.key().startsWith(SettingsCatalog.PREFIX),
                    "键少了 " + SettingsCatalog.PREFIX + " 前缀，覆盖文件里读回来的键对不上它：" + spec.key());
            assertTrue(spec.key().length() > SettingsCatalog.PREFIX.length(),
                    "键只有前缀、没有实际名字：" + spec.key());
        }
        assertEquals(keys.size(), new HashSet<>(keys).size(),
                "有重复的键，页面上会是两行同一个 data-key：" + keys);
    }

    @Test
    public void specs_typeIsOneTheFrontendKnows() {
        for (SettingsCatalog.Spec spec : catalog.specs()) {
            assertTrue(KNOWN_TYPES.contains(spec.type()),
                    "前端不认识的类型会静默退化成文本框：" + spec.key() + " → " + spec.type());
        }
    }

    @Test
    public void specs_labelAndDescArePresent() {
        for (SettingsCatalog.Spec spec : catalog.specs()) {
            assertFalse(spec.label() == null || spec.label().isBlank(),
                    "没有标签，页面上那一行会是空白：" + spec.key());
            assertFalse(spec.desc() == null || spec.desc().isBlank(),
                    "没有说明，用户只能靠键名猜：" + spec.key());
        }
    }

    /**
     * <b>密钥里只放行 e-hentai 的 cookie 一个键</b>，其余一个都不许进 {@code specs()}。
     *
     * <p>原先这条是「一个 cookie 都不许进」。2026-09-25 作者拍板口径 a：{@code manga.eh-scan.cookie}
     * 必须与站点根 {@code base-url} 成对换，而「换一半」是静默失效，所以两项一起收进页面、
     * 写在 {@code config/nya-entworks.yaml} 里（代价是明文，见 {@code docs/配置页设计.md} §14）。
     *
     * <p>所以这条测试从「一律禁 cookie」改成<b>白名单恰好一个键</b>：
     * 放行面收得越窄越好 —— 哪天有人顺手把 {@code netease-cookie} 或某个 {@code api-key}
     * 也搬进来，那两个仍在密钥文件里，症状是「页面上改完、重启、毫无变化」。
     */
    @Test
    public void specs_onlyTheEhCookieIsEditable() {
        String allowed = SettingsCatalog.PREFIX + "manga.eh-scan.cookie";
        for (SettingsCatalog.Spec spec : catalog.specs()) {
            String key = spec.key().toLowerCase();
            boolean secretish = key.contains("cookie") || key.contains("api-key") || key.contains("apikey");
            if (secretish) {
                assertEquals(allowed, spec.key(),
                        "只有 manga.eh-scan.cookie 可以进页面（2026-09-25 口径 a：它要与站点根成对换）。"
                                + "其余密钥都在 " + SettingsCatalog.SECRET_FILE + " 里，收进页面改了不会生效："
                                + spec.key());
            }
        }
        // 白名单那一项得真的在 —— 少了它页面上就没有站点根/cookie 这一对，等于口径 a 没落地
        assertTrue(byKey(catalog).containsKey(allowed),
                "e-hentai 的 cookie 不在目录里了：站点根与它必须成对出现在页面上");
    }

    /**
     * <b>站点根与 cookie 必须成对、且挨着</b> —— 「换一半」的坑就靠这个体感防住。
     *
     * <p>这两项是同一件事的两半：换成里站地址却没换 cookie，症状是<b>扫描结果少一截、一句错都不报</b>。
     * 页面上没有硬闸门（表站地址配里站 cookie 是合法组合，拦下来会拦住正当的保存），
     * 所以「成对」只能靠<b>摆在一起 + 说明里互相点名</b>表达：本测试钉住「挨着」这一半，
     * 打字那一半由两项的 {@code desc} 自己保证（都写了「必须连着…一起换」）。
     *
     * <p>顺带钉住两处<b>静默错法</b>：cookie 的值得读 {@code manga.getEhScan()}（不是 song 的
     * {@code neteaseCookie}，两行 supplier 长得像，接错了页面照画、就是不生效）；
     * 类型得是纯文本（画成路径项会多出一个「浏览…」按钮，去挑一个 cookie 文件）。
     */
    @Test
    public void specs_baseUrlAndCookieArePairedAndAdjacent() {
        List<SettingsCatalog.Spec> specs = catalog.specs();
        String baseUrl = SettingsCatalog.PREFIX + "manga.eh-scan.base-url";
        String cookie = SettingsCatalog.PREFIX + "manga.eh-scan.cookie";
        int iBase = -1, iCookie = -1;
        for (int i = 0; i < specs.size(); i++) {
            if (specs.get(i).key().equals(baseUrl)) {
                iBase = i;
            } else if (specs.get(i).key().equals(cookie)) {
                iCookie = i;
            }
        }
        assertTrue(iBase >= 0, "站点根不在目录里，换站点就没法在页面上做：" + baseUrl);
        assertTrue(iCookie >= 0, "cookie 不在目录里，站点根单独改会静默拿不到数据：" + cookie);
        assertEquals(1, iCookie - iBase, "两项挨着才看得出是一对（中间隔了别的项就只剩文案在说）："
                + specs.get(iBase).label() + " → " + specs.get(iCookie).label());
        assertEquals(specs.get(iBase).group(), specs.get(iCookie).group(),
                "两项不在同一张卡片里，页面上会分家");
        for (String key : List.of(baseUrl, cookie)) {
            assertEquals(SettingsCatalog.TEXT, byKey(catalog).get(key).type(),
                    "这两项是纯文本：cookie 画成路径项会多出一个「浏览…」按钮：" + key);
        }
        assertTrue(byKey(catalog).get(baseUrl).required(), "站点根留空会让所有请求发不出去，必须必填");
        assertFalse(byKey(catalog).get(cookie).required(),
                "cookie 必须是「空 = 没配」—— 标成必填就等于出厂写死一个值，"
                        + "而且这一页会整页存不下（见 specs_requiredImpliesNonBlankCurrent）");

        // 值接的是哪个字段：两处故意设成不同的值，接错立刻露馅
        MangaProperties manga = new MangaProperties();
        SongProperties song = new SongProperties();
        manga.getEhScan().setBaseUrl("https://e-hentai.org");
        manga.getEhScan().setCookie("ipb_member_id=1; igneous=x");
        song.setNeteaseCookie("MUSIC_U=y");
        Map<String, SettingsCatalog.Spec> m = byKey(new SettingsCatalog(manga, song, new ShoutProperties(),
                new LocalAiProperties(), new OnlineAiProperties(),
                envWith(SettingsCatalog.MODULES.toArray(new String[0]))));
        assertEquals("https://e-hentai.org", m.get(baseUrl).current(),
                "站点根读的不是 manga.eh-scan.base-url");
        assertEquals("ipb_member_id=1; igneous=x", m.get(cookie).current(),
                "cookie 读错了字段（接成 song.netease-cookie 的话页面画的是另一个 cookie，改了不生效）");
    }

    /**
     * 密钥走的是与 {@link SettingsCatalog#specs()} 完全分开的那条路，两边<b>一个键都不许重合</b>。
     *
     * <p>这条盯的是一次可能的「顺手合并」：{@link SettingsCatalog#secretStatus()} 里的
     * {@code key} 长得跟 {@code Spec.key()} 一模一样（都是全键），看上去很像可以直接并进
     * {@code specs()} —— 一旦并了，密钥就变成页面上可改的项，而它写的是另一个文件，
     * 症状是「页面上改完、重启、毫无变化」。
     *
     * <p>2026-09-25 那次口径 a 是<b>反向</b>走的：{@code manga.eh-scan.cookie} 从这条路上
     * <b>搬走</b>（进了 {@code specs()}），所以本测试仍然成立 —— 而且正是它保证那次搬动
     * 搬干净了：只把 {@code Spec} 加进去、忘了从 {@code secretStatus()} 里拿掉的话，
     * 页面上会同时出现一个可改的输入框和一行「没配」的只读文字。
     */
    @Test
    public void secretStatus_neverOverlapsSpecs() {
        Set<String> specKeys = new HashSet<>();
        for (SettingsCatalog.Spec spec : catalog.specs()) {
            specKeys.add(spec.key());
        }
        for (SettingsCatalog.SecretStatus s : catalog.secretStatus()) {
            assertFalse(specKeys.contains(s.key()),
                    "密钥键同时出现在配置项里，页面上会多出一个改了不生效的输入框：" + s.key());
        }
    }

    /** 剩下的三条密钥全在清单里、键都合格 —— 少一条就是页面上少一行，用户得回去翻文件 */
    @Test
    public void secretStatus_coversTheRemainingSecretKeys() {
        List<String> keys = new ArrayList<>();
        for (SettingsCatalog.SecretStatus s : catalog.secretStatus()) {
            keys.add(s.key());
            assertTrue(s.key().startsWith(SettingsCatalog.PREFIX),
                    "密钥键少了 " + SettingsCatalog.PREFIX + " 前缀，页面上指不出文件里是哪一行：" + s.key());
            assertFalse(s.label() == null || s.label().isBlank(), "没有用途名：" + s.key());
            assertFalse(s.where() == null || s.where().isBlank(),
                    "没写「没配会怎样」，用户不知道该不该去配：" + s.key());
        }
        assertEquals(List.of(
                        SettingsCatalog.PREFIX + "song.netease-cookie",
                        SettingsCatalog.PREFIX + "common.online-ai.base-url",
                        SettingsCatalog.PREFIX + "common.online-ai.api-key"),
                keys, "密钥清单变了 —— 页面上会少画一行，或者多画一行不存在的"
                        + "（e-hentai 的 cookie 2026-09-25 起不在这条路上，它是普通设置项）");
    }

    /**
     * 全新 {@code new} 出来的 Properties 全是空的，所以缺省一律是「没配」。
     * <p>这条同时保证 {@code secretStatus()} 不会因为某个嵌套对象没初始化而 NPE
     * —— 页面加载时白屏，跟 {@link #specs_currentValueIsReadable} 同一个理由。
     */
    @Test
    public void secretStatus_defaultsToUnconfigured() {
        for (SettingsCatalog.SecretStatus s : catalog.secretStatus()) {
            assertFalse(s.configured(),
                    "缺省被当成已配置，页面上会显示「已配置」但其实什么都没有：" + s.key());
        }
    }

    /**
     * 配上了就得报「已配置」—— 只判一半（比如漏了 api-key）会让人以为配全了。
     *
     * <p>顺带钉住<b>空白串也算「没配」</b>：在线 AI 的 base-url 缺省就是 {@code ""}，
     * 不把空白当没配的话页面上会常年显示「已配置」，比不显示还误导。
     */
    @Test
    public void secretStatus_reportsEachSecretFromItsOwnField() {
        SongProperties song = new SongProperties();
        OnlineAiProperties onlineAi = new OnlineAiProperties();
        song.setNeteaseCookie("MUSIC_U=x");
        onlineAi.setBaseUrl("https://api.example.com/v1");
        onlineAi.setApiKey("sk-x");
        SettingsCatalog c = new SettingsCatalog(new MangaProperties(), song, new ShoutProperties(),
                new LocalAiProperties(), onlineAi,
                envWith(SettingsCatalog.MODULES.toArray(new String[0])));

        Map<String, Boolean> got = new HashMap<>();
        c.secretStatus().forEach(s -> got.put(s.key(), s.configured()));
        assertEquals(Map.of(
                SettingsCatalog.PREFIX + "song.netease-cookie", true,
                SettingsCatalog.PREFIX + "common.online-ai.base-url", true,
                SettingsCatalog.PREFIX + "common.online-ai.api-key", true), got);

        onlineAi.setApiKey("");
        onlineAi.setBaseUrl("   ");
        Map<String, Boolean> after = new HashMap<>();
        c.secretStatus().forEach(s -> after.put(s.key(), s.configured()));
        assertEquals(Map.of(
                        SettingsCatalog.PREFIX + "song.netease-cookie", true,
                        SettingsCatalog.PREFIX + "common.online-ai.base-url", false,
                        SettingsCatalog.PREFIX + "common.online-ai.api-key", false),
                after, "空白串该算「没配」，且只有被清空的那两项跟着变");
    }

    /** 取值是各 Properties 的 getter：嵌套对象没 new 出来就会在这里 NPE（页面加载时白屏） */
    @Test
    public void specs_currentValueIsReadable() {
        List<SettingsCatalog.Spec> specs = catalog.specs();
        assertFalse(specs.isEmpty(), "一项都没有，配置页会是空白的");
        for (SettingsCatalog.Spec spec : specs) {
            assertNotNull(spec.current(), "读不出当前值（嵌套的 Properties 对象没初始化？）：" + spec.key());
        }
    }

    /** 限定取值的项（评分档）得给出候选，否则前端只能画个自由文本框 */
    @Test
    public void specs_optionsAreGivenWhenDeclared() {
        for (SettingsCatalog.Spec spec : catalog.specs()) {
            if (spec.options() != null && !spec.options().isEmpty()) {
                assertTrue(spec.options().contains(spec.current()),
                        "候选里没有当前生效的值，页面上会选中第一项、看起来像被改过：" + spec.key());
            }
        }
    }

    /**
     * 三个模块各恰好一个总开关（{@code nya-entworks.<模块>.enabled}）。
     * <p>它是页面每个大标题那一行的开关，缺了那个模块就没有大标题、整个模块的配置项
     * 在页面上无处可挂；多了则是两行同一个 {@code data-key}。
     */
    @Test
    public void specs_hasExactlyOneModuleSwitchPerModule() {
        Map<String, Long> counts = new HashMap<>();
        for (SettingsCatalog.Spec spec : catalog.specs()) {
            if (spec.moduleSwitch()) {
                counts.merge(spec.module(), 1L, Long::sum);
            }
        }
        for (String module : SettingsCatalog.MODULES) {
            assertEquals(1L, counts.getOrDefault(module, 0L),
                    "模块「" + module + "」的总开关不是恰好一个：" + counts);
        }
        assertEquals(SettingsCatalog.MODULES.size(), counts.size(),
                "冒出了不在 MODULES 里的模块开关：" + counts.keySet());
    }

    /**
     * 模块开关的键必须正是 {@code nya-entworks.<模块>.enabled}。
     * <p>写歪的后果是<b>全静默</b>：{@code @ConditionalOnProperty} 读的是那个精确的键，
     * 键对不上时页面照画开关、保存照落盘，就是开关不管用 —— 与 §3 那个漏前缀的坑同一类。
     */
    @Test
    public void specs_moduleSwitchKeysMatchTheConditionalProperty() {
        for (SettingsCatalog.Spec spec : catalog.specs()) {
            if (spec.moduleSwitch()) {
                assertEquals(SettingsCatalog.PREFIX + spec.module() + ".enabled", spec.key(),
                        "模块开关的键与 @ConditionalOnProperty 读的不是同一个，开关会不起作用");
            }
        }
    }

    /**
     * <b>本次构建里没有的模块，整块不出现在目录里</b> —— 连它的总开关也不出现。
     *
     * <p>这是「打包时删掉某模块的配置段＝把那个模块整个藏起来」的落点：判据是
     * {@code nya-entworks.<模块>.enabled} 这个键读不读得到（{@link SettingsCatalog#moduleExists}）。
     * 颗粒度必须是<b>整个模块</b>：只藏卡片、留下开关的话，那个开关底下的卡片在页面上
     * 无处可挂；反过来只藏开关、留下卡片，就成了没有标题的一堆项。
     */
    @Test
    public void specs_hidesModulesWhoseEnabledKeyIsAbsent() {
        SettingsCatalog c = new SettingsCatalog(new MangaProperties(), new SongProperties(),
                new ShoutProperties(), new LocalAiProperties(), new OnlineAiProperties(),
                envWith("song"));

        Set<String> modules = new HashSet<>();
        List<String> keys = new ArrayList<>();
        for (SettingsCatalog.Spec spec : c.specs()) {
            keys.add(spec.key());
            if (spec.module() != null) {
                modules.add(spec.module());
            }
        }
        assertEquals(Set.of("song"), modules,
                "只有 song 的 enabled 键读得到，页面上却还有别的模块的项：" + modules);
        assertFalse(keys.contains(SettingsCatalog.PREFIX + "manga.enabled"),
                "漫画的总开关还在 —— 页面上会画出一个没有卡片可挂的大标题");
        assertFalse(keys.contains(SettingsCatalog.PREFIX + "manga.new-dir"),
                "漫画的路径项还在 —— 歌曲线里混进了别模块的项：" + keys);
        assertTrue(keys.contains(SettingsCatalog.PREFIX + "song.enabled"),
                "歌曲的总开关被一起滤掉了，那个模块就再也打不开了");
        assertTrue(keys.contains(SettingsCatalog.PREFIX + "common.local-ai.base-url"),
                "通用项（common.*）被误伤 —— 它不属于任何模块，任何构建里都该在");
    }

    /**
     * <b>「读到 false」不等于「读不到」</b>：模块关着的时候，它的项一个都不许少。
     *
     * <p>关掉的模块在页面上要保留大标题与那个<b>关着的</b>开关 —— 那是这一页唯一的自救入口
     * （{@code settings.js} 的 {@code moduleSection} 注释写着这句），去掉就再也打不开了。
     * 只有「这个构建里根本没有它」才整块消失。
     */
    @Test
    public void specs_aModuleTurnedOffIsStillInTheCatalog() {
        MockEnvironment env = new MockEnvironment();
        for (String m : SettingsCatalog.MODULES) {
            env.withProperty(SettingsCatalog.PREFIX + m + ".enabled", "false");
        }
        SettingsCatalog c = new SettingsCatalog(new MangaProperties(), new SongProperties(),
                new ShoutProperties(), new LocalAiProperties(), new OnlineAiProperties(), env);

        assertEquals(catalog.specs().size(), c.specs().size(),
                "把三个模块都写成 false，目录里的项反而少了 —— 关掉的模块不该从页面上消失");
        assertEquals(3L, c.specs().stream().filter(SettingsCatalog.Spec::moduleSwitch).count(),
                "关掉的模块丢了总开关，页面上就再也打不开了");
    }

    /**
     * 每一项的键，第二段必须是三个模块之一或 {@code common}。
     * <p>防的是拼错模块前缀（{@code nya-entworks.mangga.new-dir}）：那种键的
     * {@link SettingsCatalog.Spec#module()} 返回 {@code null}，页面上会被当成<b>通用项</b>
     * 挂在最后那个大标题下 —— 模块关掉时它<b>不会</b>跟着隐藏，而它绑定的属性
     * 也永远绑不到任何 Bean 上。又一处「页面照画、什么也不发生」。
     */
    @Test
    public void specs_keySegmentIsAKnownModuleOrCommon() {
        Set<String> known = new HashSet<>(SettingsCatalog.MODULES);
        known.add("common");
        for (SettingsCatalog.Spec spec : catalog.specs()) {
            String sk = spec.shortKey();
            int dot = sk.indexOf('.');
            assertTrue(dot > 0, "键没有第二段，看不出属于哪个模块：" + spec.key());
            String head = sk.substring(0, dot);
            assertTrue(known.contains(head),
                    "键的第二段「" + head + "」不是模块 id 也不是 common ——"
                            + " 多半是拼错了，它会被当成通用项、模块关掉时也不隐藏：" + spec.key());
        }
    }

    /**
     * {@code dependsOn} 必须指向一个真实存在的<b>布尔</b>项。
     * <p>指向不存在的键时，前端拿不到那个开关的值，依赖判定永远落空 ——
     * 表现是「本该跟着开关显隐的那几项一直显示着」，没人会注意到。
     */
    @Test
    public void specs_dependsOnPointsAtARealBoolean() {
        Map<String, SettingsCatalog.Spec> byKey = new HashMap<>();
        for (SettingsCatalog.Spec spec : catalog.specs()) {
            byKey.put(spec.key(), spec);
        }
        for (SettingsCatalog.Spec spec : catalog.specs()) {
            if (spec.dependsOn() == null) {
                continue;
            }
            SettingsCatalog.Spec target = byKey.get(spec.dependsOn());
            assertNotNull(target, "依赖的键不存在于本目录里，依赖判定会永远落空："
                    + spec.key() + " → " + spec.dependsOn());
            assertEquals(SettingsCatalog.BOOL, target.type(),
                    "依赖的项不是布尔开关，「开 / 关」的语义不成立："
                            + spec.key() + " → " + spec.dependsOn());
        }
    }

    /**
     * <b>必填项的值不能是空的</b>：空了之后这一页<b>永远保存不了</b>。
     *
     * <p>因果链不长但每一环都静默：前端 {@code settings.js} 的 {@code collect()} 收集的是
     * 页面上<b>每一个</b> {@code [data-key]}（不只是改过的那几项），空格也照样提交上来；
     * 后端 {@link SettingsService#validate} 见到「必填 + 空」就报「不能留空」、整份拒绝。
     * 于是用户改的是别的项，看到的是「保存失败：XXX 不能留空」，而那一项他根本没碰过。
     *
     * <p>2026-09-18 就是这么踩到的：NConvert / 本地 eh 库 / AI 端点 / AI 模型四项的出厂值
     * 为了「删掉配置项 = 没配」而改成空串，却没跟着把 {@code required} 摘掉。
     */
    @Test
    public void specs_requiredImpliesNonBlankCurrent() {
        for (SettingsCatalog.Spec spec : catalog.specs()) {
            if (!spec.required()) {
                continue;
            }
            assertFalse(spec.current() == null || spec.current().isBlank(),
                    "必填项的当前值是空的 —— 页面每次保存都会被这一项挡住（而用户往往没碰它）："
                            + spec.key());
        }
    }

    /**
     * 「空 = 没配」的那四项必须可留空。
     *
     * <p>它判的是上面那条的<b>反面</b>，两条都要：{@link #specs_requiredImpliesNonBlankCurrent}
     * 只说「必填 ⇒ 非空」，只要有人把出厂值改回一个具体路径就能绕过去 —— 那样页面能存了，
     * 但「删掉这一项 = 没配」也跟着没了（值会悄悄回落到出厂的那个路径，
     * 于是「没配」在新机器上永远到不了）。这四项是用户定的口径，单独钉住。
     */
    @Test
    public void specs_localEnvironmentPathsStayOptional() {
        List<String> keys = List.of(
                SettingsCatalog.PREFIX + "manga.nconvert",
                SettingsCatalog.PREFIX + "manga.eh-scan.local-db-path",
                SettingsCatalog.PREFIX + "common.local-ai.base-url",
                SettingsCatalog.PREFIX + "common.local-ai.model");
        Map<String, SettingsCatalog.Spec> byKey = new HashMap<>();
        for (SettingsCatalog.Spec spec : catalog.specs()) {
            byKey.put(spec.key(), spec);
        }
        for (String key : keys) {
            SettingsCatalog.Spec spec = byKey.get(key);
            assertNotNull(spec, "这一项不在目录里了，本机路径改不了： " + key);
            assertFalse(spec.required(),
                    "本机差异项必须是「空 = 没配」，标成必填就等于出厂写死一个值： " + key);
        }
    }

    /**
     * 三个模块的「归档前强制打标签」各一项，且每一项读的是<b>自己模块</b>的那个 getter。
     *
     * <p>钉的是这一组开关特有的静默错法：{@code bool(...)} 的 {@code value} supplier 是逐行手写的，
     * 三行长得一模一样，复制粘贴时很容易把喊麦那行的 supplier 留成 {@code song::…} ——
     * 页面上三行照画、三行都存得下去，只有喊麦那一行「改完重启，一点变化都没有」。
     * 所以下面故意把三个值设成<b>两真一假</b>：任何一处接错了模块，对上的那一项就会立刻露馅
     * （三个都设 true 的话，接错也照样读出 true，等于没测）。
     *
     * <p>同时钉住出厂默认是 false —— 用户定的口径是「不强制」，写反了症状是
     * 升级后突然打不了分，而开关本身看起来没问题。
     */
    @Test
    public void specs_requireTagsBeforeArchiveBindsToItsOwnModule() {
        Map<String, SettingsCatalog.Spec> off = byKey(catalog);
        for (String key : REQUIRE_TAGS_KEYS) {
            SettingsCatalog.Spec spec = off.get(key);
            assertNotNull(spec, "「归档前强制打标签」少了这一项，这个口径就没法在页面上关掉： " + key);
            assertEquals(SettingsCatalog.BOOL, spec.type(),
                    "不是布尔项，页面会画成文本框、存下去的还是个串： " + key);
            assertEquals("false", spec.current(), "出厂必须是 false（不强制）： " + key);
            assertFalse(spec.required(), "布尔项标成必填没有意义，还会把空值判成「不能留空」： " + key);
        }

        // 两真一假：任何一处接了别的模块的 getter，都会有某一项读出与预期相反的值
        MangaProperties manga = new MangaProperties();
        SongProperties song = new SongProperties();
        ShoutProperties shout = new ShoutProperties();
        manga.setRequireTagsBeforeArchive(true);
        song.setRequireTagsBeforeArchive(true);
        shout.setRequireTagsBeforeArchive(false);
        Map<String, SettingsCatalog.Spec> on = byKey(new SettingsCatalog(manga, song, shout,
                new LocalAiProperties(), new OnlineAiProperties(),
                envWith(SettingsCatalog.MODULES.toArray(new String[0]))));

        assertEquals("true", on.get(REQUIRE_TAGS_KEYS.get(0)).current(),
                "漫画这一项读的不是 manga 的 getter，页面上改了不生效");
        assertEquals("true", on.get(REQUIRE_TAGS_KEYS.get(1)).current(),
                "歌曲这一项读的不是 song 的 getter，页面上改了不生效");
        assertEquals("false", on.get(REQUIRE_TAGS_KEYS.get(2)).current(),
                "喊麦这一项读的不是 shout 的 getter —— 页面上改了不生效（最像复制粘贴漏改的一处）");
    }

    /**
     * 每一项的<b>出厂值</b>都读得出来，而且与「现在生效的值」用<b>同一个 accessor</b>。
     *
     * <p>出厂值＝各 {@code *Properties} 的 Java 字段默认值，由本类手里的「同型未绑定实例」
     * （{@code freshManga} 那四个字段）读出来 —— 两处 supplier 是逐行手写的、长得几乎一样，
     * 复制粘贴时很容易把某一行留在别的模块 / 别的字段上。接错的后果<b>全静默</b>：
     * {@code SettingsService#toWrite} 会拿一个不相干的值当出厂值去比，
     * 于是那一项要么永远写不进覆盖层（改了不生效），要么永远留在里面（「已改」清不掉）。
     *
     * <p>判据取巧但严格：这里两个 Properties 都是<b>没绑过任何属性源</b>的实例，
     * 所以 {@code current()} 与出厂值必须逐项完全相同 —— 只要有一行的 supplier 接到了别的
     * 字段（或别的模块）上，值就会不一样（{@code enabled} 那种 {true/false} 也逃不掉，
     * 因为模块开关的两处 supplier 分别读 {@code manga} 与 {@code freshManga}）。
     */
    @Test
    public void specs_factoryValueComesFromTheSameAccessor() {
        for (SettingsCatalog.Spec spec : catalog.specs()) {
            assertNotNull(spec.factoryValue(), "出厂值读不出来（同型实例的嵌套对象没初始化？）：" + spec.key());
            assertEquals(spec.current(), spec.factoryValue(),
                    "出厂值不是同一个 getter 读出来的（复制粘贴接错了模块或字段？）——"
                            + " 覆盖层里该留的项与该清的项都会判错，且全静默：" + spec.key());
        }
    }

    /** 按全键取 spec —— 这套断言每处都要按键找项，摊开写容易看串行 */
    private static Map<String, SettingsCatalog.Spec> byKey(SettingsCatalog c) {
        Map<String, SettingsCatalog.Spec> map = new HashMap<>();
        c.specs().forEach(s -> map.put(s.key(), s));
        return map;
    }
}
