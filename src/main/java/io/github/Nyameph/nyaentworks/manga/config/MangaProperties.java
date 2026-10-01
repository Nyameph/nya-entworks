package io.github.Nyameph.nyaentworks.manga.config;

import io.github.Nyameph.nyaentworks.manga.util.MangaEventKey;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 漫画模块的本机路径。原先散落在测试类里（{@code MangaNameParserTestUtil} 的
 * {@code scanRoot}、{@code scanRootScoreMap}），搬到配置里便于环境自检统一检查。
 * <p>归档根 {@link #archiveDir} 原先写死在 {@code MangaNameParser} 的静态映射表里
 * （四条 {@code F:\MangaGroup\…}），2026-09-17 随配置页一起搬到这里：分区目录改由
 * {@code MangaScoreDir.rootScoreMap(archiveDir)} 按磁盘现扫得出，于是「归档根在哪个盘、
 * 分区叫什么」都不必再改代码。那条旧注释担心的「解析器自身用到它」并不成立 ——
 * {@code MangaNameParser} 只是当初的存放处，它自己一次都没读过。
 */
@Data
@Component
@ConfigurationProperties(prefix = "nya-entworks.manga")
public class MangaProperties {

    /**
     * 模块总开关，<b>缺省 false ＝ 这个模块整个不启用</b>。
     *
     * <p>关掉之后：漫画的 Controller 不注册（接口 404）、15 个异步任务处理器不注册（提交不了）、
     * 启动预热线程 {@code MangaEhLocalDbWarmup} 不跑 —— 那 190 万行的 eh 索引因此不会被读进堆
     * （{@code MangaEhLocalDb} 是懒加载的，没有别的调用方会去碰它），约 924 MB 的活集就此省下。
     *
     * <p>判定点在 {@link io.github.Nyameph.nyaentworks.common.config.ConditionalOnManga}。
     * 本字段自己<b>不</b>带那个注解 —— {@code SettingsCatalog} 与 {@code EnvCheckService}
     * 都要读它，标掉会让配置页整个起不来。
     */
    private boolean enabled = false;

    /**
     * NConvert 可执行文件的完整路径。**空 = 没配**（2026-09-17 起出厂值是空串）。
     *
     * <p>出厂不再写一个具体机器上的路径：这是**本机差异**，写在
     * {@code config/nya-entworks.yaml} 里才成立。空串与「路径指向的文件不在」
     * 都算没配，见 {@link io.github.Nyameph.nyaentworks.manga.service.MangaCompressService#available()}，
     * 两者的后果一样（归档不压缩），只在提示语里区分。
     */
    private String nconvert = "";

    /**
     * 归档根，下面是 {@code #<评分>-<名称>} 分区目录。
     * <p>分区目录名（{@code 百读不厌} 之类）由磁盘决定、代码里不重复一份，
     * 见 {@link io.github.Nyameph.nyaentworks.manga.util.MangaScoreDir#rootScoreMap(String)}。
     */
    private String archiveDir = "F:\\MangaGroup";

    /** 单个文件的压缩超时秒数 */
    private int nconvertTimeoutSeconds = 120;

    /** 压缩并发线程数。0 或负数表示按 CPU 核数。NConvert 每文件一个进程，并行能摊掉进程启动开销 */
    private int compressThreads = 0;

    /** 新漫画根目录，下面按 {@code #<评分>-<名称>} 分评分子目录 */
    private String newDir = "F:\\NetdiskDownload\\#待看";

    /** 新作者合集根目录，一个子目录是一个合集 */
    private String collectionDir = "F:\\NetdiskDownload\\#待看合集";

    /** 未归档漫画根目录，同样按评分分子目录 */
    private String unarchivedDir = "F:\\NetdiskDownload\\#待整理散漫";

    /**
     * 归档前是否<b>强制先打标签</b>。出厂 false ＝ 不强制（2026-09-22 用户定）。
     *
     * <p>只管<b>合集存储</b>这一条路：合集标签要写进归档目录名的 {@code 【…】} 块，
     * 原先必须非空（{@code MangaStoreService#requireTags}）。关掉之后标签可以留空，
     * 目录名就是没有 {@code 【】} 块的 {@code [社团 (作者)]} —— 那仍是合法形态，
     * 下次同步照常入库。
     *
     * <p><b>单本归档本来就不拦</b>（留空则落库后从 eh 拉标签，见
     * {@code MangaStoreService#cleanTags}），所以这一项对最常见的那条路没有影响 ——
     * 配置页的说明里必须写清这一点，否则开关看起来「不管用」。
     */
    private boolean requireTagsBeforeArchive = false;

    /** 封面缩略图缓存目录，可随时清空 */
    private String thumbCacheDir = System.getProperty("java.io.tmpdir") + "/nya-entworks-manga-thumb";

    /** e-hentai 词典标签拉取的代理主机，直连被墙时填本机代理（如 127.0.0.1） */
    private String ehTagProxyHost = "";

    /** e-hentai 词典标签拉取的代理端口，与 ehTagProxyHost 配套（0 = 不走代理） */
    private int ehTagProxyPort = 0;

    /** e-hentai 单本标签扫描的配置（见 docs/已完成/eh标签扫描设计.md） */
    private EhScan ehScan = new EhScan();

    /**
     * e-hentai / exhentai 扫描配置。里站（exhentai）内容更全，但需要 igneous cookie
     * 与账号权限；cookie 走环境变量 {@code NYA_ENTWORKS_MANGA_EH_SCAN_COOKIE} 覆盖、别提交进仓库。
     */
    @Data
    public static class EhScan {

        /** 站点根：https://exhentai.org（里站）或 https://e-hentai.org（表站） */
        private String baseUrl = "https://exhentai.org";

        /** 登录 cookie（ipb_member_id/ipb_pass_hash/sk/igneous），默认空、别提交仓库；用环境变量 {@code NYA_ENTWORKS_MANGA_EH_SCAN_COOKIE} 覆盖 */
        private String cookie = "";

        /** 访问 eh 的代理（被墙时填本机代理；与词典拉取的 ehTagProxy 走不同站点，分开配） */
        private String proxyHost = "127.0.0.1";

        /** 代理端口，与 proxyHost 配套（0 = 不走代理） */
        private int proxyPort = 7890;

        /** 相邻请求基础间隔毫秒，实际会加 0~基础值 的随机抖动（即 [base, 2×base]），防封 IP */
        private long requestDelayMs = 2000;

        /**
         * 是否启用 AI 相似度比对。
         *
         * <p><b>端点在别处</b>：base-url / model / api-key 是全项目共用的
         * {@link io.github.Nyameph.nyaentworks.common.config.LocalAiProperties}
         * （{@code nya-entworks.common.local-ai.*}）—— 同一台 Ollama 也被 AI 填词与
         * 韵脚词性用着，写三份的后果是换端口漏一处就静默走旧地址。
         * 这里只留「漫画这一侧要不要用它」。
         */
        private boolean aiEnabled = true;

        /** 相似度门槛：最高候选相似度 < 此值判 NO_MATCH */
        private double simMin = 0.6;

        /** 合并标签时取的候选数 */
        private int topK = 3;

        /** 每本漫画最多保留的标签数 */
        private int maxTags = 30;

        /** 赞同数过滤门槛：已知且 < 此值的标签被剔除（0 = 剔除净反对标签） */
        private int weightMin = 0;

        /** 是否抓 gallery 详情页解析标签赞同数（gdata 不带权重，抓权重要额外请求且 DOM 会变） */
        private boolean scrapeWeight = false;

        /**
         * 是否启用「封面反向搜索」兜底（docs/已完成/eh标签扫描设计.md §9.3）。默认关：
         * 它只在标题/本地都对不上时触发，要额外上传封面图、且 eh 相似度搜索可能有误命中，
         * 属人工复核档（{@code match_method=THUMBNAIL}）。需要封面文件存在才能用。
         */
        private boolean thumbnailSearch = false;

        /**
         * 封面反搜命中的<b>最低标题相似度门槛</b>（0~1）：封面对上、但最佳候选标题相似度低于它，
         * 视为「封面像、标题对不上」的低置信命中而剔除。0 = 不设门槛（只要封面命中就采纳）。
         * <p>封面反搜比标题搜更易误命中（eh 相似搜索会返回画风/构图相近的不同作品），调高此值
         * 要求「封面 + 标题双重印证」以提升精度，代价是「标题完全对不上」的本子搜不出。
         * 批量 / 覆盖率脚本按需调高。
         */
        private double thumbnailMinTitleSim = 0.0;

        /**
         * 封面反搜是否要求<b>作者/社团元数据命中</b>（默认开，口径同本地库 meta 匹配）。
         * <p>eh 相似图搜索会返回画风/构图相近的不同作品，光靠封面像不够。开启后：候选的
         * {@code artist}/{@code group} 标签必须与本漫画的作者/社团有交集（主门槛），且从画廊标题里
         * 拆出的展会/杂志不得与本漫画冲突（{@link MangaEventKey} 三态：
         * 冲突剔除、正向命中免标题门槛、缺值放过）。这样封面反搜与标题/本地匹配的置信口径一致。
         * <p>漫画自身没有作者/社团（仅目录名）时该门槛无从判定，自动退回纯标题相似度门槛。
         */
        private boolean thumbnailRequireMeta = true;

        /**
         * 本地 e-hentai 数据源 eh-gallery.db 的路径；<b>空 = 没配</b>，本地优先匹配整段不走。
         *
         * <p>路径相对项目根（不是 classpath —— 文件 1.4 GB，放 {@code resources} 下会让
         * Maven 每次构建都整份拷进 {@code target/classes}）。生成脚本见 {@code data/merge_panda.py}。
         *
         * <p><b>2026-09-17 起出厂值是空串</b>，不再默认 {@code data/eh-gallery.db}：
         * 那个文件本身是 gitignored 的 1.4 GB 产物，新机器上根本不存在，「默认它」等于
         * 默认指向一个不存在的文件；而这一项在配置页上又允许留空，只有出厂值也为空，
         * 「没配」才是一个真能干到的状态。写了值但文件不在，与空串同样按没配处理，
         * 见 {@link io.github.Nyameph.nyaentworks.manga.util.MangaEhLocalDb#available()}。
         */
        private String localDbPath = "";

        /**
         * 搜索/gdata 结果的进程内缓存有效期（分钟），0 = 关闭。
         * <p>批量扫描时同一作者的几十本会反复发同一条 {@code artist:"名字$"} 查询，
         * 同一条查询在一次批量里结果不会变，缓存能整段省掉这些请求（含<b>空结果</b>——
         * 搜空的词是最亏的，双站两个请求换回一个 empty）。GET 幂等、只读，缓存不改变匹配口径。
         * <p>默认 30 分钟：足够覆盖一轮批量扫描，又不至于让「刚在 eh 上传了新本」长期看不到。
         */
        private long searchCacheMinutes = 30;
    }
}
