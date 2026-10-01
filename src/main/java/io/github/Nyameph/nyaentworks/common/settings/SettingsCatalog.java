package io.github.Nyameph.nyaentworks.common.settings;

import io.github.Nyameph.nyaentworks.common.config.LocalAiProperties;
import io.github.Nyameph.nyaentworks.common.config.OnlineAiProperties;
import io.github.Nyameph.nyaentworks.manga.config.MangaProperties;
import io.github.Nyameph.nyaentworks.shout.config.ShoutProperties;
import io.github.Nyameph.nyaentworks.song.config.SongProperties;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 「配置」页的元数据目录：页面上的每一项（标签、说明、类型、范围、缺了会怎样）都来自这里，
 * 前端只画不判 —— 与项目其余页面同一条规矩。
 *
 * <p><b>只收「本机路径 + 常用开关」</b>，密钥原则上不收。{@code netease-cookie} /
 * 两个 {@code api-key} 都在另一个文件（{@link #SECRET_FILE}）里，本页与
 * {@code EnvController} 一样无鉴权，明文回显只是白白扩大暴露面 ——
 * {@code SettingsCatalogTest#specs_onlyTheEhCookieIsEditable} 拦着。
 * <p><b>唯一的例外是 {@code manga.eh-scan.cookie}</b>（2026-09-25 作者拍板，口径 a，
 * 见 {@code docs/配置页设计.md} §14）：它必须与站点根 {@code base-url} <b>成对换</b>，
 * 而「换一半」的症状是<b>静默</b>的（里站地址配着表站 cookie，扫描结果少一截、一句错都不报），
 * 所以把两项一起收进页面、写在 {@link #FILE_NAME} 这份覆盖层里 —— 代价是它明文落在那个文件里，
 * 而本页无鉴权。**这条口径只针对这一个键**，其余三个密钥照旧。
 * <p>其余密钥「配没配」仍得看得见，所以另走一条只读的路：{@link #secretStatus()}，
 * 只下发<b>位置</b>与<b>配没配</b>，不下发值、也不给可编辑的 {@code data-key}。
 * 两条路互不重叠，由 {@code SettingsCatalogTest#secretStatus_neverOverlapsSpecs} 盯着
 * —— 上面那个例外已从这条路上搬走，所以两边不再重合。
 *
 * <p><b>出厂值有一个、也只有一个来源</b>：各 {@code *Properties} 的<b>Java 字段默认值</b>
 * （{@link Spec#factoryValue()}，由同型的未绑定实例读出来 —— 见下面那几个 {@code fresh*} 字段）。
 * 原先它还有第二个来源：{@code application.yaml} 里整份写着 {@code nya-entworks:} 段，
 * 且与字段默认值有几项不同，于是「字段默认值」到底算不算出厂值说不清；2026-09-25 起那一段
 * 已整段删掉（裁剪的介质就是外侧的覆盖层，见 {@code docs/桌面化与模块裁剪设计.md} §5），
 * 两个来源并存的歧义随之消失。
 *
 * <p><b>但出厂值仍然不上页面</b>：它是<b>后端自己用的</b> —— 覆盖层里只写「偏离出厂值」的项，
 * 于是「文件里有这个键」与「这一项改过」才是一回事（{@code SettingsService#toWrite}）。
 * 页面的口径仍旧只有两个：<b>现在生效的值</b>（本类从 Bean 读）与
 * <b>覆盖文件里写没写这一项</b>（{@link SettingsService} 判）。
 * 「恢复出厂」＝把该项从覆盖文件里删掉，值便落回下一层（一般就是上面那个出厂值）。
 *
 * <p><b>「本次构建里没有的模块」整块不下发</b>：{@code nya-entworks.<模块>.enabled} 这个键
 * 在任何属性源里都读不到时，该模块的<b>全部</b>设置项（连那个总开关）都不进 {@link #specs()}。
 * 这是「打包时删掉某模块的配置段＝把那个模块整个藏起来」的落点，判据见 {@link #moduleExists}。
 */
@Component
@RequiredArgsConstructor
public class SettingsCatalog {

    /** 属性名公共前缀。接口与覆盖文件里都用全键（{@code nya-entworks.manga.new-dir}），与 Spring 属性名一一对应，零转换 */
    public static final String PREFIX = "nya-entworks.";

    // 类型。前端按它决定画输入框还是复选框 —— 类型由后端下发，前端不自己猜
    public static final String PATH_DIR = "PATH_DIR";
    public static final String PATH_FILE = "PATH_FILE";
    public static final String TEXT = "TEXT";
    public static final String INT = "INT";
    public static final String BOOL = "BOOL";

    /**
     * 三个业务模块的 id，与各 {@code *Properties} 的前缀第二段、以及
     * {@code static/js/modules.js} 里那几项注册表的 id 一一对应。
     * <p>{@link Spec#module()} 靠它把「这个键属于哪个模块」推出来。
     */
    public static final List<String> MODULES = List.of("manga", "song", "shout");

    /** 覆盖文件相对项目根的位置（与 {@code application.yaml} 的 {@code spring.config.import} 一致） */
    public static final String FILE_NAME = "nya-entworks.yaml";
    public static final String DIR_NAME = "config";

    /**
     * 密钥文件相对项目根的位置，同样是 {@code spring.config.import} 里的一项。
     * <p>2026-09-17 从 {@code src/main/resources/application-secret.yaml} 挪出来：
     * 以前靠 {@code spring.profiles.active: secret} 从 classpath 隐式加载，文件在哪、
     * 谁赢了优先级都得去翻 {@code application.yaml} 才知道；现在它就是 {@code config/}
     * 下一个普通的外部文件，与 {@link #FILE_NAME} 并排在同一个 import 列表里。
     */
    public static final String SECRET_FILE = "config/application-secret.yaml";

    private final MangaProperties manga;
    private final SongProperties song;
    private final ShoutProperties shout;
    private final LocalAiProperties localAi;
    private final OnlineAiProperties onlineAi;

    /**
     * 同型的、<b>没有绑定过任何属性源</b>的实例 —— 读出来就是各 {@code *Properties} 的
     * Java 字段默认值，也就是这一项的<b>出厂值</b>（{@link Spec#factoryValue()}）。
     *
     * <p>为什么不另写一份「默认值表」：这张表会与字段默认值各改各的、慢慢漂移，
     * 而漂移的后果是<b>静默</b>的 —— 覆盖层里某个键被当成「等于出厂值」删掉，
     * 值于是悄悄变成另一个数。同型的空实例取的是<b>同一个 getter</b>，
     * 两份值在编译期就绑在一起（{@code SettingsCatalogTest#specs_factoryValueComesFromTheSameAccessor}
     * 逐项钉住这一点）。
     *
     * <p>这几个字段是 {@code final} 且在声明处就初始化好了，所以不进
     * {@code @RequiredArgsConstructor} 的参数表 —— 测试里照旧 {@code new} 六个参数。
     */
    private final MangaProperties freshManga = new MangaProperties();
    private final SongProperties freshSong = new SongProperties();
    private final ShoutProperties freshShout = new ShoutProperties();
    private final LocalAiProperties freshLocalAi = new LocalAiProperties();

    /**
     * 只用来问一件事：{@code nya-entworks.<模块>.enabled} 这个键<b>读得到吗</b>
     * （见 {@link #moduleExists}）。各模块的值仍旧从上面的 Properties Bean 读 ——
     * 那两个问题不一样：「值是多少」Bean 知道，「这个键在不在」只有属性源知道。
     */
    private final Environment environment;

    /**
     * 一项设置。
     *
     * @param group           分组标题，页面按它分卡片
     * @param key             全键（{@code nya-entworks.manga.new-dir}）
     * @param type            {@link #PATH_DIR} 等。前端只认这几个值
     * @param min             INT 的下限，其余类型为 {@code null}
     * @param max             INT 的上限，其余类型为 {@code null}
     * @param options         限定取值（评分档只有 1/3/5/7/9），空表示不限
     * @param required        不允许留空
     * @param relativeAllowed 允许相对路径 —— 只有「有意写成相对项目根」的那两项才开
     * @param restartRequired 改完必须重启后端才生效（启动时就被读走并固化的那些）
     * @param onMissing       路径不在磁盘上时的后果，直接给人看；{@code null} 表示无所谓
     * @param value           现在生效的值。每次取都重新读 Bean 的 getter，
     *                        所以永远与代码真正用的那份同源，不会抄错第二份
     * @param factory         <b>出厂值</b>：同一个 getter 打在同型的未绑定实例上
     *                        （{@link SettingsCatalog} 的 {@code fresh*} 字段）。
     *                        {@code SettingsService} 拿它判「这一项改过没有」——
     *                        覆盖层里只留偏离出厂值的项，等于出厂值的顺手清掉
     */
    public record Spec(String group, String key, String label, String desc, String type,
                       Integer min, Integer max, List<String> options, boolean required,
                       boolean relativeAllowed, boolean restartRequired, String onMissing,
                       String dependsOn, Supplier<String> value, Supplier<String> factory) {

        /** 现在生效的值 */
        public String current() {
            return value.get();
        }

        /** 出厂值（Java 字段默认值）。空串与 {@code null} 同义 —— 页面那一层只认字符串 */
        public String factoryValue() {
            return StringUtils.defaultString(factory.get());
        }

        /** 去掉 {@link SettingsCatalog#PREFIX} 的短键（{@code manga.new-dir}） */
        public String shortKey() {
            return key.startsWith(PREFIX) ? key.substring(PREFIX.length()) : key;
        }

        /**
         * 所属模块 id（{@code manga} / {@code song} / {@code shout}），通用项为 {@code null}。
         *
         * <p><b>由全键推出来，不另有字段</b>：模块的属性前缀本来就是
         * {@code nya-entworks.<模块>.}，多存一份只会多一个能写歪的地方。
         * 页面靠它把卡片挂到对应的大标题底下。
         */
        public String module() {
            String sk = shortKey();
            int dot = sk.indexOf('.');
            if (dot < 0) {
                return null;
            }
            String head = sk.substring(0, dot);
            return MODULES.contains(head) ? head : null;
        }

        /**
         * 是不是模块的总开关（{@code nya-entworks.manga.enabled} 这种）。
         *
         * <p>判据是「短键恰好等于 {@code <模块>.enabled}」—— 光看结尾的 {@code .enabled}
         * 会把 {@code song.fill-ai.enabled} 也当成模块开关。模块开关画在大标题那一行、
         * 不进任何卡片。
         */
        public boolean moduleSwitch() {
            String m = module();
            return m != null && shortKey().equals(m + ".enabled");
        }
    }

    /**
     * 一条密钥的**状态**（只看不改）。
     *
     * <p>有意不复用 {@link Spec}：{@code Spec} 走的是一整套「画控件 → 收集 → 写覆盖文件」
     * 的链路，收进密钥就等于让页面能改它们，而密钥在另一个文件里（{@link #SECRET_FILE}），
     * 改了不生效就是最隐蔽的那种坑。这里只下发位置与配没配，前端画成一行只读文字。
     *
     * @param label      页面上的中文名
     * @param key        全键，用来告诉人「去那个文件的哪一行改」；它<b>不是</b>可编辑项
     * @param where      配了会多什么、没配会少什么，直接给人看
     * @param configured 值非空即算配了
     */
    public record SecretStatus(String label, String key, String where, boolean configured) { }

    /**
     * 密钥的状态清单，页面底部那张「密钥」卡片用。顺序就是页面上的顺序。
     *
     * <p>将来加新密钥就往这里加一行，<b>不要</b>往 {@link #specs()} 里加。
     *
     * <p><b>原先这里还有 {@code manga.eh-scan.cookie} 一行</b>，2026-09-25 搬去了
     * {@link #specs()}（口径 a）：它成了页面上可改的普通项，值直接画出来，
     * 「配没配」这一层就多余了。所以本方法现在只管剩下三个 ——
     * {@code netease-cookie} 与两个 {@code api-key}。
     */
    public List<SecretStatus> secretStatus() {
        List<SecretStatus> list = new ArrayList<>();
        list.add(new SecretStatus("网易云登录 cookie", PREFIX + "song.netease-cookie",
                "没配就没有「网易云 VIP 直链」渠道，其余几个渠道照常",
                isNotBlank(song.getNeteaseCookie())));
        list.add(new SecretStatus("在线 AI 端点", PREFIX + "common.online-ai.base-url",
                "没配时「填词走在线 AI」那一项打开也没用，只能走本机 Ollama",
                isNotBlank(onlineAi.getBaseUrl())));
        list.add(new SecretStatus("在线 AI 密钥", PREFIX + "common.online-ai.api-key",
                "有的端点不要 key（本机 Ollama 就不要），那就留空",
                isNotBlank(onlineAi.getApiKey())));
        return list;
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** 全部设置项，按页面上的先后顺序 */
    public List<Spec> specs() {
        List<Spec> list = new ArrayList<>();

        // 三个模块总开关。列在最前面只是「读代码时先看到」—— 页面上它们被前端从卡片里
        // 提出来、画成各模块大标题那一行的开关，不占卡片的位置。
        // 出厂的三个 true 写在 application.yaml 各模块段的头一行；把它们删掉就是
        // 「本次构建里没有这个模块」，那时这三个 Spec 连同各自模块的全部项都不出页面
        // （dropAbsentModules）。所以这里列出来的三行**不一定都会被画出来**。
        list.add(moduleSwitch("漫画", "manga.enabled", "漫画",
                "关掉整个漫画模块：左侧导航不再显示它、它的接口一并关闭、启动时也不再预热 "
                        + "e-hentai 本地库（那 190 万行索引约占 924 MB 内存）。改完要重启后端。",
                () -> String.valueOf(manga.isEnabled()),
                () -> String.valueOf(freshManga.isEnabled())));
        list.add(moduleSwitch("歌曲", "song.enabled", "歌曲",
                "关掉整个歌曲模块（含填词助手）：导航不再显示、接口关闭、启动时不再自动同步归档歌曲，"
                        + "也不再展开韵脚词典种子。改完要重启后端。",
                () -> String.valueOf(song.isEnabled()),
                () -> String.valueOf(freshSong.isEnabled())));
        list.add(moduleSwitch("喊麦", "shout.enabled", "喊麦",
                "关掉整个喊麦模块：导航不再显示、接口关闭、启动时不再自动同步归档喊麦。"
                        + "歌曲的语料采集仍会读到喊麦的已归档列表（只读，不受影响）。改完要重启后端。",
                () -> String.valueOf(shout.isEnabled()),
                () -> String.valueOf(freshShout.isEnabled())));

        String g = "漫画 · 路径";
        list.add(dir(g, "manga.archive-dir", "漫画归档根",
                "下面是 #<评分>-<名称> 四个评分分区目录。分区名由磁盘决定 —— 评分就存在目录名里",
                "同步会整个拒跑、一行库都不写（缺一档会把那一档下的归档目录全判成失踪），先看自检",
                manga::getArchiveDir, freshManga::getArchiveDir));
        list.add(dir(g, "manga.new-dir", "新漫画根",
                "解压到这里，评分就是移进它下面的分区子目录。这一层不入库、每次现扫",
                "「新漫画」页扫出来会是空的，与「确实没有新漫画」在界面上长得一样",
                manga::getNewDir, freshManga::getNewDir));
        list.add(dir(g, "manga.collection-dir", "新作者合集根",
                "一个子目录是一个合集，目录名形如 [社团 (作者)]",
                "「新漫画」页的合集扫不出来，与「确实没有合集」无法区分",
                manga::getCollectionDir, freshManga::getCollectionDir));
        list.add(dir(g, "manga.unarchived-dir", "未归档漫画根",
                "存储时归不了档的落这里，同样按评分分区。视为「未归档」",
                "「未归档」页扫出来会是空的",
                manga::getUnarchivedDir, freshManga::getUnarchivedDir));
        list.add(dir(g, "manga.thumb-cache-dir", "封面缩略图缓存",
                "可随时清空，下次访问会重建", null,
                manga::getThumbCacheDir, freshManga::getThumbCacheDir));
        list.add(fileOptional(g, "manga.eh-scan.local-db-path", "本地 eh 库",
                "e-hentai 的本地快照，相对项目根（文件 1.4 GB，放 resources 下会让每次构建都整份拷进 target）。"
                        + "留空 = 没配，标签扫描直接走联网搜索，其余功能不受影响",
                "标签扫描整段不走本地优先匹配、退回联网搜索 —— 慢但不报错，看起来像「突然变慢了」",
                true, true, manga.getEhScan()::getLocalDbPath, freshManga.getEhScan()::getLocalDbPath));

        g = "漫画 · 工具与开关";
        list.add(fileOptional(g, "manga.nconvert", "NConvert 可执行文件",
                "打包 cbz 时用的图片转换工具。留空 = 没配，归档时不压缩，其余功能不受影响",
                "压缩功能不可用（归档照做、图片不压），其余功能不受影响", false, false,
                manga::getNconvert, freshManga::getNconvert));
        list.add(number(g, "manga.compress-threads", "压缩并发线程数", -1, 64,
                "NConvert 每文件一个进程，并发能把进程启动开销摊掉。0 或负数 = 按 CPU 核数；"
                        + "机器老、压缩时卡就调小",
                () -> String.valueOf(manga.getCompressThreads()),
                () -> String.valueOf(freshManga.getCompressThreads())));
        list.add(number(g, "manga.nconvert-timeout-seconds", "单文件压缩超时（秒）", 10, 3600,
                "卡住的多半是坏文件，让它拖着整批不如算它失败",
                () -> String.valueOf(manga.getNconvertTimeoutSeconds()),
                () -> String.valueOf(freshManga.getNconvertTimeoutSeconds())));

        g = "漫画 · 归档";
        list.add(bool(g, "manga.require-tags-before-archive", "归档前强制打标签",
                "只管「合集存储」这一条路（标签要写进归档目录名的【…】块）。默认关："
                        + "标签可以留空，目录名就是没有【】块的 [社团 (作者)]，照样能入库。"
                        + "单本归档本来就不拦 —— 留空则落库后从 eh 拉取（失败再手动补）",
                () -> String.valueOf(manga.isRequireTagsBeforeArchive()),
                () -> String.valueOf(freshManga.isRequireTagsBeforeArchive())));

        // 站点根与 cookie 是**一对**，必须连着换：只换一半不会报错，只是拿不到数据
        // （里站地址配着表站 cookie → 「扫描结果少一截」这种最隐蔽的静默失效）。
        // 原先两项都挡在页面外（base-url 的理由是这条，cookie 另撞着「密钥不做可编辑项」），
        // 2026-09-25 作者拍板口径 a：两项一起收进页面，就画在**相邻两行**，
        // 说明里互相点名 —— 「成对」这层关系靠摆在一起 + 文案表达，不设硬闸门：
        // 表站地址配里站 cookie 是合法组合，拦下来只会拦住正当的保存。
        // cookie 进页面写的是**本页这份文件**（config/nya-entworks.yaml，gitignored），
        // 所以 config/application-secret.yaml 里那一行要删掉 —— 同一个键两处都有时，
        // 后加载的 nya-entworks.yaml 赢，改密钥文件那份就成了「改了不生效」。详见配置页设计.md §14。
        g = "e-hentai 扫描";
        list.add(text(g, "manga.eh-scan.base-url", "站点根",
                "里站 https://exhentai.org（内容全，要账号权限）/ 表站 https://e-hentai.org。"
                        + "换站点**必须连着下面那项 cookie 一起换** —— 只换一半不报错，只是拿不到数据，"
                        + "看起来像「扫描结果突然少了一截」",
                () -> manga.getEhScan().getBaseUrl(),
                () -> freshManga.getEhScan().getBaseUrl()));
        list.add(textOptional(g, "manga.eh-scan.cookie", "e-hentai 登录 cookie",
                "浏览器里登录后复制整条 Cookie（含 ipb_member_id / ipb_pass_hash / sk，里站还要 igneous）。"
                        + "与上面的站点根配成一对，换里站/表站时两项一起改。"
                        + "留空 = 没配：只能看表站与公开内容，里站的权限拿不到。"
                        + "★ 这一项按 2026-09-25 的口径明文存在 config/nya-entworks.yaml 里"
                        + "（本机文件、已 gitignore，但本页无鉴权）—— 只在这台机器上用",
                manga.getEhScan()::getCookie, freshManga.getEhScan()::getCookie));
        list.add(bool(g, "manga.eh-scan.ai-enabled", "AI 相似度兜底",
                "标题与本地库都没命中时，用本机 AI 判「同一部」还是「同一系列的不同部/卷」。"
                        + "关掉则只靠确定性匹配（端点与模型在下面「AI 端点」一组里，不在这）",
                () -> String.valueOf(manga.getEhScan().isAiEnabled()),
                () -> String.valueOf(freshManga.getEhScan().isAiEnabled())));
        list.add(textOptional(g, "manga.eh-scan.proxy-host", "eh 代理主机",
                "exhentai 直连被墙，一般填本机代理 127.0.0.1", manga.getEhScan()::getProxyHost,
                freshManga.getEhScan()::getProxyHost));
        list.add(number(g, "manga.eh-scan.proxy-port", "eh 代理端口", 0, 65535,
                "与上面的代理主机配套；0 = 不走代理。改了 Clash 端口就在这里同步改",
                () -> String.valueOf(manga.getEhScan().getProxyPort()),
                () -> String.valueOf(freshManga.getEhScan().getProxyPort())));
        list.add(number(g, "manga.eh-scan.request-delay-ms", "扫描请求间隔（毫秒）", 0, 60000,
                "相邻请求的基础间隔，实际会加 0~基础值 的随机抖动（填 2000 即 [2s, 4s]），防封 IP",
                () -> String.valueOf(manga.getEhScan().getRequestDelayMs()),
                () -> String.valueOf(freshManga.getEhScan().getRequestDelayMs())));
        list.add(textOptional(g, "manga.eh-tag-proxy-host", "词典拉取代理主机",
                "拉 eh 标签词典走另一个站点（GitHub），与上面的 eh 代理分开配；留空 = 不走代理",
                manga::getEhTagProxyHost, freshManga::getEhTagProxyHost));
        list.add(number(g, "manga.eh-tag-proxy-port", "词典拉取代理端口", 0, 65535,
                "与上面的词典代理主机配套；0 = 不走代理",
                () -> String.valueOf(manga.getEhTagProxyPort()),
                () -> String.valueOf(freshManga.getEhTagProxyPort())));

        // 原先歌曲与喊麦合成一组「歌曲与喊麦」，2026-09-17 拆开：页面上的大标题就是按模块分的，
        // 一个组里混两个模块，会让「关掉喊麦」时不知道该藏哪半张卡。
        // 原组里的两项 ncm 解密配置（song.ncm-decrypt-script / song.python-command）已删除 ——
        // 用户暂不把 ncm 解密并入项目。功能本身没动：SongProperties 上那两个字段还在，
        // 解密的调用链也还完整，只是页面上改不了它们（想改就手写进覆盖文件，会被原样保留）。
        g = "歌曲 · 基础";
        // 填词署名排在组首（2026-10-01 作者两次校准后的落点：从一开始的「填词相关配置最前面」，
        // 改成歌曲分类的第一组）。组名同时由「歌曲 · 路径」改成「歌曲 · 基础」—— 这一组不再
        // 只有路径。**组名是前端与提示语共用的**：SongTemplateService 报「没配原曲冗余根」时
        // 直接点名这一组，改名要连那句一起改。
        list.add(textOptional(g, "song.fill-signature", "填词署名",
                "你自己的署名。导出的 lrc 歌词头会多写一行 [by:…]（原曲名 [ti:]、填词名 [al:] "
                        + "排在它前面）。留空 = 不写这一行",
                song::getFillSignature, freshSong::getFillSignature));
        list.add(dirOptional(g, "song.song-dir", "已归档歌曲根",
                "下面是 #<分数><评语> 分区，文件平铺在分区里；语料采集也从这里取",
                "歌曲列表为空，且语料采集会退化成「没有语料」",
                song::getSongDir, freshSong::getSongDir));
        list.add(dirOptional(g, "song.staging-dir", "歌曲待打分根",
                "歌曲待打分的目录（完整路径，就是 …\\#已压缩歌曲\\歌曲 那一层）。这一层不入库（文件是过路的）",
                "「未归档」页会是空的",
                song::getStagingDir, freshSong::getStagingDir));
        list.add(dirOptional(g, "song.template-dir", "模板根",
                "已整理好的 svp 模板，目录名即原曲名（多模板为「原曲 模板N」）",
                "填词页的模板列表为空，与「确实没有模板」无法区分",
                song::getTemplateDir, freshSong::getTemplateDir));
        list.add(dirOptional(g, "song.only-original-dir", "仅原曲根",
                "只有原曲音频、没有合成工程文件的原曲目录，与模板根同作「原曲来源」扫描",
                "原曲侧的搜索结果会缺一部分来源",
                song::getOnlyOriginalDir, freshSong::getOnlyOriginalDir));
        list.add(dirOptional(g, "song.original-retired-dir", "原曲冗余根",
                "原曲页「移入冗余」的去处（文件没丢，只是从原曲目录挪开、等你去资源管理器处置）；"
                        + "下面按「原曲名_歌手」建子目录。**别填成模板根或仅原曲根下的子目录** —— "
                        + "那两个根下任意子目录都会被当成一首原曲，会冒出一首叫「冗余」的原曲",
                "「移入冗余」会失败（报一句路径不可用），原曲页其余功能不受影响",
                song::getOriginalRetiredDir, freshSong::getOriginalRetiredDir));

        g = "歌曲 · 归档";
        list.add(bool(g, "song.require-tags-before-archive", "归档前强制打标签",
                "打开后，待打分区的歌没有标签就拦下（已归档的改评分不拦）。默认关："
                        + "标签是库字段、不写进文件名，不打标签也能归档，只是暂时查不到标签",
                () -> String.valueOf(song.isRequireTagsBeforeArchive()),
                () -> String.valueOf(freshSong.isRequireTagsBeforeArchive())));

        g = "喊麦 · 路径";
        list.add(dirOptional(g, "shout.archived-dir", "喊麦已归档根",
                "同样是 #<分数><评语> 分区，文件平铺在分区里",
                "喊麦列表为空",
                shout::getArchivedDir, freshShout::getArchivedDir));
        list.add(dirOptional(g, "shout.staging-dir", "喊麦待打分根",
                "喊麦待打分的目录（完整路径，就是 …\\#已压缩歌曲\\喊麦 那一层）。这一层不入库",
                "喊麦的待打分列表为空",
                shout::getStagingDir, freshShout::getStagingDir));

        g = "喊麦 · 归档";
        list.add(bool(g, "shout.require-tags-before-archive", "归档前强制打标签",
                "打开后，待打分区的喊麦没有标签就拦下（已归档的改评分不拦）。默认关："
                        + "标签是库字段、不写进文件名，不打标签也能归档，只是暂时查不到标签",
                () -> String.valueOf(shout.isRequireTagsBeforeArchive()),
                () -> String.valueOf(freshShout.isRequireTagsBeforeArchive())));

        g = "填词与语料";
        list.add(bool(g, "song.fill-ai.enabled", "AI 填词",
                "关掉就没有「AI 填词」按钮，也不需要本机 Ollama",
                () -> String.valueOf(song.getFillAi().isEnabled()),
                () -> String.valueOf(freshSong.getFillAi().isEnabled())));
        // 依赖上一行的「AI 填词」总开关：它关掉时本行隐藏 —— 都不填词了，也就无所谓走本地还是在线
        list.add(dependsOn(bool(g, "song.fill-ai.use-online", "填词走在线 AI",
                "关 = 走本机 Ollama；开 = 走 online-ai 端点。端点的 enabled / base-url / api-key "
                        + "在 config/application-secret.yaml 里，本页不含密钥",
                () -> String.valueOf(song.getFillAi().isUseOnline()),
                () -> String.valueOf(freshSong.getFillAi().isUseOnline())), "song.fill-ai.enabled"));
        list.add(choice(g, "song.corpus.min-score", "语料最低评分", List.of("1", "3", "5", "7", "9"),
                "已归档歌曲里评分达此阈值的组，才作韵脚词典与 AI few-shot 的语料",
                () -> String.valueOf(song.getCorpus().getMinScore()),
                () -> String.valueOf(freshSong.getCorpus().getMinScore())));
        list.add(bool(g, "song.corpus.include-shout", "语料含喊麦",
                "喊麦句也采集，但只进韵脚词典、不进 AI few-shot",
                () -> String.valueOf(song.getCorpus().isIncludeShout()),
                () -> String.valueOf(freshSong.getCorpus().isIncludeShout())));

        // 端点单独一组、而且全项目只有一份：漫画的相似度兜底、AI 填词、韵脚词性用的是
        // 同一台本机 Ollama。原先三处各配一份（eh-scan.ai-base-url / fill-ai.local.*），
        // 换端口漏一处就静默走旧地址 —— 2026-09-17 收成 common.local-ai 这一份。
        // api-key 不进页面（密钥一律不上面；本机 Ollama 也不需要）。
        g = "AI 端点（本机）";
        list.add(textOptional(g, "common.local-ai.base-url", "AI 端点",
                "OpenAI 兼容端点，漫画相似度兜底 / AI 填词 / 韵脚词性三处共用这一个。"
                        + "本机 Ollama 是 http://localhost:11434/v1，无需 key；换机器就填它的地址。"
                        + "AI 填词只在这一项指向本机时才放行 —— 要发到外部端点请开它的「走在线 AI」开关。"
                        + "留空 = 没配",
                localAi::getBaseUrl, freshLocalAi::getBaseUrl));
        // 与上面同一份配置的另一半：两项都填了才算配全，缺任何一个三处 AI 功能一起关
        // （判据在 LocalAiProperties#isConfigured），所以它同样可留空。
        list.add(textOptional(g, "common.local-ai.model", "AI 模型",
                "名字须与 ollama list 里的一致（8G 显存实际能用的上限在 7b~9b）。留空 = 没配",
                localAi::getModel, freshLocalAi::getModel));

        return dropAbsentModules(list);
    }

    /**
     * 这个模块在<b>本次构建里存在吗</b> —— 判据只有一条：{@code nya-entworks.<模块>.enabled}
     * 这个键读不读得到。
     *
     * <p><b>「读到 false」与「读不到」是两件事</b>，别混：
     * <ul>
     *   <li>读到 {@code false} ＝ 模块在，只是关着。配置页照常显示它的大标题与开关
     *       （关着的开关是这一页唯一的自救入口，去掉就再也打不开了），左侧导航不显示它的入口。</li>
     *   <li>读不到 ＝ 这个构建里<b>根本没有这个模块</b>（打包时把它的配置整段删掉了）。
     *       配置页连那个关着的开关都不画 —— 见 {@link #dropAbsentModules}。</li>
     * </ul>
     *
     * <p>为什么用 {@code Environment} 而不是 Properties Bean 的 getter：Bean 的字段带着
     * Java 默认值（三个都是 {@code false}），「没写」与「写了 false」在它那里长得一模一样。
     * 「键在不在」只有属性源知道。
     */
    public boolean moduleExists(String moduleId) {
        return environment.containsProperty(PREFIX + moduleId + ".enabled");
    }

    /**
     * 丢掉「本次构建里根本没有的模块」的<b>全部</b>设置项 —— 不只是那个总开关。
     *
     * <p>颗粒度是整个模块，理由是那个开关一消失，它底下的卡片在页面上就<b>无处可挂</b>
     * （{@code settings.js} 的 {@code moduleSection} 是按大标题分组画的）；
     * 而且「构建里没有这个模块」本来就意味着它的路径、端点、开关全都不存在。
     *
     * <p>判据落在这一处，于是配置文件那边只有一个动作：<b>把那个模块的配置整段删掉</b>
     * （见 {@code docs/桌面化与模块裁剪设计.md} §5）。前端零改动 —— 它只画后端给的
     * {@code modules} 与 {@code groups}。
     *
     * <p>{@code module() == null} 的是通用项（{@code common.*}），永远留着。
     */
    private List<Spec> dropAbsentModules(List<Spec> all) {
        List<Spec> kept = new ArrayList<>(all.size());
        for (Spec spec : all) {
            String m = spec.module();
            if (m == null || moduleExists(m)) {
                kept.add(spec);
            }
        }
        return kept;
    }

    /**
     * 补上 {@link #PREFIX}。
     *
     * <p>全键是这一页的<b>唯一口径</b>：接口下发的 {@code key}、前端回传的 {@code key}、
     * 覆盖文件里读回来的键，三处必须是同一个串，否则会静默错位 ——
     * 比如「覆盖文件里写没写这一项」永远判成没写。下面各个工厂只管收短键，
     * 加前缀这一件事只在这里做，省得漏一处。
     */
    private static String full(String key) {
        return PREFIX + key;
    }

    private static Spec dir(String group, String key, String label, String desc, String onMissing,
                            Supplier<String> value, Supplier<String> factory) {
        return new Spec(group, full(key), label, desc, PATH_DIR, null, null, List.of(),
                true, false, false, onMissing, null, value, factory);
    }

    /**
     * <b>可留空的目录</b>（出厂值就是空串的那几个本机路径）。
     *
     * <p>与 {@link #dir} 只差 {@code required}。这几个必须可留空，理由与
     * {@code fileOptional} 那两项完全相同、且更硬：出厂值改成空串之后（2026-09-30，
     * 打包发版那条线要的「空 = 没配」），{@code required=true} 会让页面
     * <b>整页存不下</b> —— {@code settings.js} 的 {@code collect()} 提交页面上每一个
     * {@code [data-key]}，一项必填且为空就在 {@code plan()} 里报错，别的项一并存不进去。
     *
     * <p>{@code onMissing} 照旧保留：这几项空着时页面上仍要说清「哪里会空」，那是给人看的。
     */
    private static Spec dirOptional(String group, String key, String label, String desc,
                                    String onMissing, Supplier<String> value, Supplier<String> factory) {
        return new Spec(group, full(key), label, desc, PATH_DIR, null, null, List.of(),
                false, false, false, onMissing, null, value, factory);
    }

    private static Spec file(String group, String key, String label, String desc, String onMissing,
                             Supplier<String> value, Supplier<String> factory) {
        return file(group, key, label, desc, onMissing, false, false, value, factory);
    }

    private static Spec file(String group, String key, String label, String desc, String onMissing,
                             boolean relativeAllowed, boolean restartRequired,
                             Supplier<String> value, Supplier<String> factory) {
        return new Spec(group, full(key), label, desc, PATH_FILE, null, null, List.of(),
                true, relativeAllowed, restartRequired, onMissing, null, value, factory);
    }

    /**
     * 可留空的外部程序路径（「空 = 没配」的那两项：NConvert、本地 eh 库）。
     *
     * <p>与 {@link #file} 只差 {@code required}。这两项必须可留空，原因有两层：
     * 出厂值就是空串（换了机器本来就该没人配过），而 {@code settings.js} 的 {@code collect()}
     * 会把页面上<b>每个</b> {@code [data-key]} 都提交上来 —— 只要有一项必填且为空，
     * 整页保存都会被 {@link SettingsService#validate} 挡回去，连改别的项都存不下。
     */
    private static Spec fileOptional(String group, String key, String label, String desc,
                                     String onMissing, boolean relativeAllowed,
                                     boolean restartRequired, Supplier<String> value,
                                     Supplier<String> factory) {
        return new Spec(group, full(key), label, desc, PATH_FILE, null, null, List.of(),
                false, relativeAllowed, restartRequired, onMissing, null, value, factory);
    }

    /** 必填文本（模型名、命令名这些填错就没法用的） */
    private static Spec text(String group, String key, String label, String desc,
                             Supplier<String> value, Supplier<String> factory) {
        return new Spec(group, full(key), label, desc, TEXT, null, null, List.of(),
                true, false, false, null, null, value, factory);
    }

    /** 可留空的文本（代理主机这类，空 = 不走代理） */
    private static Spec textOptional(String group, String key, String label, String desc,
                                     Supplier<String> value, Supplier<String> factory) {
        return new Spec(group, full(key), label, desc, TEXT, null, null, List.of(),
                false, false, false, null, null, value, factory);
    }

    private static Spec bool(String group, String key, String label, String desc,
                             Supplier<String> value, Supplier<String> factory) {
        return new Spec(group, full(key), label, desc, BOOL, null, null, List.of(),
                false, false, false, null, null, value, factory);
    }

    private static Spec number(String group, String key, String label, Integer min, Integer max,
                               String desc, Supplier<String> value, Supplier<String> factory) {
        return new Spec(group, full(key), label, desc, INT, min, max, List.of(),
                true, false, false, null, null, value, factory);
    }

    /** 限定取值的一项（评分档），不设范围、只给候选 */
    private static Spec choice(String group, String key, String label, List<String> options,
                               String desc, Supplier<String> value, Supplier<String> factory) {
        return new Spec(group, full(key), label, desc, INT, null, null, options,
                true, false, false, null, null, value, factory);
    }

    /**
     * 模块总开关。它与普通布尔项的唯一差别是<b>画在哪</b>：前端把它提出来画在模块大标题
     * 那一行，不放进卡片（{@link Spec#moduleSwitch()} 认的就是它）。
     *
     * <p>{@code restartRequired = true}：判定它的
     * {@link io.github.Nyameph.nyaentworks.common.config.ConditionalOnManga} 是
     * {@code @ConditionalOnProperty}，只在<b>启动时</b>求值一次 —— 关掉模块再重启，
     * 那些 Controller 与任务处理器才会真的消失。
     *
     * <p>它<b>必须留在 {@code specs()} 里</b>：{@code SettingsService.render} 只写
     * {@code specs()} 里出现过的键，不在这里就等于「页面能点、保存不落盘」——
     * 又一个全静默的坑。
     */
    private static Spec moduleSwitch(String group, String key, String label, String desc,
                                     Supplier<String> value, Supplier<String> factory) {
        return new Spec(group, full(key), label, desc, BOOL, null, null, List.of(),
                false, false, true, null, null, value, factory);
    }

    /**
     * 标出这一项<b>依赖哪个开关</b>：那个开关当前是关的时候，本行在页面上隐藏。
     *
     * <p>隐藏只是「不画」—— 提交时它的值仍然照常回传（见 {@code settings.js} 的
     * {@code collect}），所以「把开关关上再保存」不会把被隐藏的那几项一起抹掉，
     * 回头把开关打开，它们还在原处。
     *
     * <p>参数收的是<b>短键</b>（{@code song.fill-ai.enabled}），与其他工厂同一口径。
     */
    private static Spec dependsOn(Spec spec, String switchKey) {
        return new Spec(spec.group(), spec.key(), spec.label(), spec.desc(), spec.type(),
                spec.min(), spec.max(), spec.options(), spec.required(), spec.relativeAllowed(),
                spec.restartRequired(), spec.onMissing(), full(switchKey), spec.value(), spec.factory());
    }
}
