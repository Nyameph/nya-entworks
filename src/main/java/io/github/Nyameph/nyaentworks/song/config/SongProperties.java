package io.github.Nyameph.nyaentworks.song.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 填词歌曲模块的本机路径（文档 5 「磁盘上的目录」）。喊麦的两个根在
 * {@link io.github.Nyameph.nyaentworks.shout.config.ShoutProperties}。
 * <p>与漫画不同，这里的三个根都<b>不硬编码</b>在解析器里 —— 歌曲解析
 * （{@code SongNameParser}）是纯字符串处理，不需要知道文件在哪，所以路径能干净地
 * 留在配置里。
 */
@Data
@Component
@ConfigurationProperties(prefix = "nya-entworks.song")
public class SongProperties {

    /**
     * 模块总开关，<b>缺省 false ＝ 这个模块整个不启用</b>。
     *
     * <p>关掉之后：歌曲与填词的所有 Controller 不注册、12 个异步任务处理器不注册、
     * 启动自动任务（{@code SongSyncOnStartup} 全量同步、{@code RhymeSeedRunner} 韵脚种子）
     * 不再跑。判定点见 {@link io.github.Nyameph.nyaentworks.common.config.ConditionalOnSong}。
     *
     * <p>本字段自己<b>不</b>带那个注解 —— {@code SettingsCatalog} 与 {@code EnvCheckService}
     * 都要读它，标掉会让配置页整个起不来。
     */
    private boolean enabled = false;

    /**
     * 已归档填词歌曲根，下面是 {@code #<分数><评语>} 分区，文件平铺在分区里。
     *
     * <p><b>出厂是空串 ＝ 没配</b>（2026-09-30 起，与 NConvert / 本地 eh 库 / AI 端点同一条口径）：
     * 写死一个本机路径会让「没配」这个状态在别人的机器上永远到不了 —— 而那是发出去的包上
     * <b>一定</b>会发生的事，页面却只会显示一串指着 {@code F:\} 的假警报。空着时自检按
     * 「没配」报一行、不报「路径不存在」。本机那台的真实值在 {@code config/nya-entworks.yaml}
     * 这个覆盖层里。
     */
    private String songDir = "";

    /**
     * 待打分根（{@code F:\NetdiskDownload\#已压缩歌曲\歌曲} 那一层）—— <b>完整路径</b>，
     * 本模块不再往它下面拼子目录。这一层不入库、每次现扫。出厂空串，见 {@link #songDir}。
     *
     * <p>2026-10-01 前它停在 {@code #已压缩歌曲} 那一层、下分「歌曲」与喊麦两个子目录，
     * 由本模块自己拼出「歌曲/」。那与喊麦侧「一项一个完整路径」的口径不一致（磁盘目录
     * 改名时两边都得跟着调）。现在两个模块各配一项，各自是完整路径。
     */
    private String stagingDir = "";

    /**
     * 归档前是否<b>强制先打标签</b>。出厂 false ＝ 不强制（2026-09-22 用户定）。
     *
     * <p>只管「待打分区 → 已归档」的<b>首次归档</b>：没有标签就拦下（预演里给
     * {@code blockedReason}）。已归档的组改评分不拦 —— 那种情况标签早就打过了。
     *
     * <p>标签是 DB-only 的（{@code song_tag}，key = {@code merge_key}），不写进文件名，
     * 所以不打标签归档不会造出形态异常的目录，只是这首歌暂时查不到标签。
     * 闸门在 {@code SongArchiveService#requireTagForArchive}。
     */
    private boolean requireTagsBeforeArchive = false;

    /** 已整理好的模板根，目录名即原曲名（多模板为「原曲 模板N」）。出厂空串，见 {@link #songDir} */
    private String templateDir = "";

    /** 仅原曲根：只有原曲音频、没有合成工程文件的原曲目录。与 {@link #templateDir} 同作「原曲来源」扫描。出厂空串，见 {@link #songDir} */
    private String onlyOriginalDir = "";

    /**
     * 原曲冗余根：原曲页「移入冗余」的去处（2026-09-25 用户定「新建 F:\歌曲\原曲冗余」）。
     *
     * <p><b>必须放在 {@link #templateDir} / {@link #onlyOriginalDir} 之外</b>，别为了
     * 「就近」把它挪成那两个根下的一个 {@code 冗余} 子目录：原曲扫描是把根下<b>任意</b>
     * 子目录当成一首原曲（{@code SongTemplateService#scanTemplateDirGroups} 只按名字拆
     * 原曲名与歌手，没有跳过的名字），于是会冒出一首叫「冗余」的原曲，而
     * {@code relocateMisplacedDirs} 还会把它当成「没有工程文件」搬去仅原曲根。
     *
     * <p>与歌曲侧的 {@code <songDir>\冗余}（{@code SongImportService#retiredDir}）同一个
     * 语义：文件没有丢，只是从受管目录里挪开、等人在资源管理器里处置。搬动记进
     * {@code file_op_log}（{@code unit_level = OTHER}），所以「搬到哪去了」查得到。
     */
    private String originalRetiredDir = "";

    /**
     * 网易云登录 cookie（至少含 {@code MUSIC_U}）。配了才启用「网易云 VIP 直链」渠道
     * （eapi 换 320k mp3）；空则整段跳过、行为与不配时一致。别提交进仓库，
     * 值填在已被 gitignore 的 {@code config/application-secret.yaml} 里。
     */
    private String neteaseCookie = "";

    /**
     * 解密 .ncm 用到的 python 解释器命令（Windows 下通常是 {@code python}，不是
     * {@code python3}——后者可能是商店占位 stub）。仅网易云下到 .ncm 加密文件时使用。
     */
    private String pythonCommand = "python";

    /**
     * NCM 解密脚本路径（相对项目根或绝对路径）。网易云下到 .ncm 加密文件时用
     * {@link #pythonCommand} 调它解密成 flac/mp3，详见 {@code tools/README.md}。
     */
    private String ncmDecryptScript = "tools/ncm_decrypt.py";

    /**
     * 填词署名：导出的 lrc 歌词头多写一行 {@code [by:…]}（见
     * {@code LyricFillService#buildLrc}）。一份全局值，所有填词共用 ——
     * 署名是「谁填的」，与具体哪一首无关。
     *
     * <p><b>出厂空串 ＝ 不写这一行</b>：署名因人而异，写死一个值在新机器上等于替人签名。
     * 与 {@link #songDir} 那几个「空 = 没配」同一个道理，所以配置页那一项是
     * {@code textOptional}（可留空），见 {@code SettingsCatalog}。
     */
    private String fillSignature = "";

    /** 语料采集（填词助手设计 §7.1）：已归档填词歌曲里评分达阈值的组，作词典与 AI few-shot 语料。 */
    private Corpus corpus = new Corpus();

    /** AI 填词。默认关：不开就没有「AI 填词」按钮，也不需要本地 Ollama。 */
    private FillAi fillAi = new FillAi();

    @Data
    public static class Corpus {
        /** 全库评分只有 1/3/5/7/9。默认 5 = 可用 + 佳作 + 超赞（实测 765 组）。 */
        private int minScore = 5;
        /** 喊麦句也采集，但只进词典、不进 few-shot（用户已定）。 */
        private boolean includeShout = true;
    }

    @Data
    public static class FillAi {
        /** 总开关：false 时填词页不出现「AI 填词」按钮。 */
        private boolean enabled = false;
        /**
         * false = 走本机 Ollama；true = 走 common.online-ai（要求它 enabled=true 且 base-url 非空）。
         *
         * <p>本机那一条端点不在这里 —— 端点是全项目共用的
         * {@link io.github.Nyameph.nyaentworks.common.config.LocalAiProperties}
         * （{@code nya-entworks.common.local-ai.*}），因为漫画的 eh 扫描相似度与韵脚词性
         * 用的是同一台 Ollama。
         */
        private boolean useOnline = false;
        /** 单次请求超时（秒）。7b 在 3070 上写 8 句约 10 秒，300 秒足够宽松。 */
        private int timeoutSeconds = 300;
        /** few-shot 注入条数（仅本地模式注入；在线模式恒不注入）。 */
        private int fewshotLines = 8;
        private int fewshotPairs = 4;
        /** 每批送模型几句（进度与重试的粒度）。 */
        private int batchSize = 8;
        private double temperature = 0.85;
    }
}
