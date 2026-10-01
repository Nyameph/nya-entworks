package io.github.Nyameph.nyaentworks.manga.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.manga.config.MangaProperties;
import io.github.Nyameph.nyaentworks.manga.entity.MangaData;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 本地 e-hentai 数据源（eh-gallery.db）的只读索引 + 单本匹配。
 *
 * <p>数据源是 pandabrowser 2025-08-04 dump 生成的静态快照（见 {@code merge_panda.py}），
 * 约 190 万条画廊，含标题（英文/日文）、作者/原作/社团/角色/语言等元数据列，以及内容类
 * 标签（female/male/mixed/other/cosplayer/rest）。本类懒加载把库一次性载入内存建索引，
 * 之后按漫画标题/作者/原作/社团匹配，命中即返回对应画廊的 gid/token/标题/标签，供
 * {@code MangaEhScanService} 优先本地、跳过网络搜索。
 *
 * <p><b>匹配口径与在线爬取一致，外加作者/原作/社团辅助</b>：
 * <ol>
 *   <li>跳过：漫画 date_tag ≥ 数据源快照时间（2025.08，只有年份则 ≥ 2026）；</li>
 *   <li>标题精确：完整标题（title_norm/title_jpn）或纯标题（title_clean/title_jpn_clean）归一等值；</li>
 *   <li>作者/原作/社团候选集：本地 artist 必须命中 + 可选 parody/group 命中同一条；</li>
 *   <li>候选集内按标题相似度取最佳：达到包含档记 CONTAINS，够不上包含但过得了
 *       {@link #SIM_MIN_META} 记 META（命中展会/杂志的记 META_STRONG）。</li>
 * </ol>
 *
 * <p><b>四档的分数</b>：EXACT 固定 1.0、CONTAINS 固定 0.85（{@link MangaTitleMatcher#SCORE_CONTAINS}）；
 * meta 两档不是固定值，而是把算好的标题相似度折进各自评分带 —— STRONG 落 {@code [0.7, 0.8)}、
 * META 落 {@code [0.6, 0.7)}，见 {@link #metaScore}。这样 similarity 列是可比的测量值，
 * 而不只是档位标签。
 *
 * <p><b>标题比较只在候选集内做，不扫全库</b>：全库 190 万行，逐本线性扫描（15000 本漫画）
 * 算不完；且「标题在全库某处是子串」这个条件太弱，命中不可信。收窄到同作者/原作/社团的
 * 候选集（几十行）后既快又更准。
 *
 * <p><b>候选集内必须按标题排序取最佳，不能任取</b>：同一作者名下几十本，任取一条等于蒙。
 * 展会/杂志命中的行优先，但它们本身也要按标题排序（命中展会的可能有多本）。标题相似度
 * 低于 {@link #SIM_MIN_META} 时宁可不报命中、回退联网。
 *
 * <p><b>懒加载 + 只读</b>：索引加载后不可变，多线程并发 match 安全（只在首次加载时同步）。
 * 库文件不存在或加载失败时 {@link #match} 返回 {@code null}，调用方降级到网络搜索。
 * <p>加载 190 万行要 20 秒上下，落在谁头上取决于谁先碰这个类。放着不管就是「每次启动后
 * 第一次归档漫画卡二十秒」，所以 {@code MangaEhLocalDbWarmup} 在应用就绪后拿后台线程
 * 先调一次 {@link #warmUp}，把这段时间挪到没人等的地方。
 *
 * <p><b>索引只存匹配用得上的列</b>：{@code title}/{@code title_jpn}/{@code tags} 这三列只有
 * 命中的那一行会用到（{@link #toHit} 拿去构造 {@link LocalHit}），却曾经为 190 万行全都常驻在
 * 堆里，其中 {@code tags} 还是每行一个 JSON 数组字符串，是三者里最占地方的。现在改成命中后
 * 回 SQLite 单点查一次（{@link #loadDetail}）。查询按 {@code title_norm} 走 ——
 * 它是 gallery 表的 PRIMARY KEY（见 {@code merge_panda.py}），单点查亚毫秒级；
 * <b>不能按 gid 查</b>，gid 上没有索引，那会退化成 190 万行全表扫。
 * 匹配口径完全不受影响：这三列从来不参与任何判定，参与判定的四个归一标题键和
 * 展会/杂志 key 仍然全量在内存里。
 */
@Slf4j
@Component
public class MangaEhLocalDb {

    /** 本地标题精确命中的匹配方式标记 */
    public static final String METHOD_LOCAL_TITLE_EXACT = "LOCAL_TITLE_EXACT";

    /**
     * 本地标题包含命中的匹配方式标记。
     * <p><b>范围限定在同作者/原作/社团的候选集内</b>（不是全库）：全库 190 万行的逐本线性扫描
     * 算不完（15000 本 × 190 万行），且「标题在全库某处是子串」这个条件太弱、命中不可信。
     * 收窄到候选集后既快（几十行）又更准 —— 「同作者 + 标题包含」比「全库标题包含」强得多。
     */
    public static final String METHOD_LOCAL_TITLE_CONTAINS = "LOCAL_TITLE_CONTAINS";

    /** 本地作者/原作/社团辅助命中的匹配方式标记（标题没对上、靠元数据兜底） */
    public static final String METHOD_LOCAL_META = "LOCAL_META";

    /**
     * 本地强元数据兜底命中：artist 命中且展会/杂志等强区分字段也命中（同一作者同一展会
     * 通常只有一部），置信度高于纯 meta，可被 {@code MangaEhScanService} 直接采纳。
     */
    public static final String METHOD_LOCAL_META_STRONG = "LOCAL_META_STRONG";

    /**
     * 作者/原作/社团辅助命中的<b>评分带上界</b>（不含）：比标题包含低一档，因为是间接匹配。
     * 实际分数由 {@link #metaScore} 把标题相似度折进 {@code (上界-带宽, 上界)} 区间。
     */
    public static final double SCORE_LOCAL_META = 0.7;

    /** 强元数据兜底命中（artist + 展会/杂志）的<b>评分带上界</b>（不含）：介于标题包含与纯 meta 之间 */
    public static final double SCORE_LOCAL_META_STRONG = 0.8;

    /**
     * meta 两档评分带的带宽。
     * <p>取 0.1 <b>不是任意值</b>：它等于两档上界之差（0.8 - 0.7），这样两条带恰好首尾相接
     * 而不重叠 —— META 落 [0.6, 0.7)、STRONG 落 [0.7, 0.8)，档位序仍然成立。
     * 再大就会重叠（STRONG 带底跌进 META 带内），档位序被带内的相似度差异盖过去。
     * 而 STRONG 的上界 0.8 与 {@link MangaTitleMatcher#SCORE_CONTAINS} 0.85 之间留了 0.05 空隙，
     * 保证 meta 档整体压在包含档之下。
     * <p><b>改动两档上界时必须同步检查这个值</b>，约束由 {@code MangaEhMetaScoreTest} 守着。
     */
    private static final double META_BAND_WIDTH = 0.1;

    /**
     * meta 兜底（只 artist 对上、标题不够相似）的相似度下限：低于这个分数（标题毫不相关），
     * 即使作者对上也不报命中 —— 同一作者名下几十本，标题无关时随便取一本等于蒙。
     * <p>Dice 系数 0.4 是启发式取值：同一作品标题略改（简繁/罗马音/小差异）通常 &gt;0.5，
     * 完全无关作品（同一作者）通常 &lt;0.3。可配置化，此处硬编码求快。
     */
    public static final double SIM_MIN_META = 0.4;

    private final String dbPath;

    /**
     * 加载时解析出的绝对路径，供 {@link #loadDetail} 回查用。
     * <p>不直接用 {@link #dbPath}：它可能是相对路径（默认就是），而回查发生在请求线程上，
     * 用绝对路径不受工作目录影响。索引加载成功后才有值，与 {@link #loaded} 同一个 happens-before。
     */
    private String dbAbsPath;

    /** 索引加载后不可变；loaded 用 volatile 保证可见性，加载走 synchronized */
    private volatile boolean loaded = false;
    private Map<String, Row> stripExact = Map.of();
    private Map<String, List<Row>> artistIdx = Map.of();
    private Map<String, List<Row>> parodyIdx = Map.of();
    private Map<String, List<Row>> groupIdx = Map.of();

    /** 翻译字典（translate_dict，ai_ok=1）的归一 key 双向映射：en_key↔ja_key */
    private Map<String, Set<String>> transEnToJa = Map.of();
    private Map<String, Set<String>> transJaToEn = Map.of();

    /**
     * 库内一行<b>参与匹配所需的部分</b>：完整标题归一（norm/jpnNorm）+ 纯标题归一（cleanNorm/jpnCleanNorm）
     * + gid/token + 该行自己的展会/杂志归一 key。
     * <p>包含匹配时四个归一字段都比，覆盖「完整标题前后缀干扰、纯标题才对得上」的情况。
     * <p><b>原始标题与标签故意不在这里</b>：它们只有命中的那一行用得到，为 190 万行常驻不值，
     * 命中后按 {@code norm}（即 gallery 表主键 {@code title_norm}）回库单点查
     * （{@link #loadDetail}）。留在这里的字段都是判定要用的。
     * <p>{@code exhibitKeys}/{@code magazineKeys} 是行自带的值（英文列 + 日文列合一），
     * 存在行上而不是建 key→行 索引：索引只能回答「哪些行命中了这个展会」，回答不了
     * 「这一行自己属于哪个展会」，而 {@link #splitByEvent 展会/杂志判定} 需要后者。
     * 为省内存，这两个集合在 load 时经规范化 Map 去重，
     * 同一展会的所有行共用同一个 Set 实例；无值的行共用 {@link Set#of()}。
     */
    private record Row(String norm, String jpnNorm, String cleanNorm, String jpnCleanNorm,
                       long gid, String token,
                       Set<String> exhibitKeys, Set<String> magazineKeys) {
    }

    /** 命中行的展示信息，命中后才按 {@code title_norm} 回库查（见 {@link #loadDetail}）。 */
    private record Detail(String title, String titleJpn, List<String> tags) {
    }

    /**
     * 候选集经展会/杂志判定后的切分结果：{@code viable} 是没被冲突剔除的行，
     * {@code strong} 是其中展会/杂志正向对上的子集（{@code strong ⊆ viable}）。
     * <p>两者都交出来给「按标题相似度取最佳」用 —— 「同作者 + 展会对上 + 标题最像的那一本」
     * 比「同作者里随便一本」准得多。
     */
    private record EventSplit(Set<Row> viable, Set<Row> strong) {
    }

    /** 一条候选行 + 它与目标标题的相似度，用于在候选集内排序取最佳。 */
    private record Scored(Row row, double sim) {
    }

    /** 命中的本地画廊，供 {@code MangaEhScanService} 构造成 GData 走统一 finish */
    public record LocalHit(long gid, String token, String title, String titleJpn,
                           List<String> tags, String method, double score) {
    }

    /**
     * 该本地命中是否值得直接采纳（跳过网络搜索）。
     * <p>可采纳三类：标题精确命中（天然可靠）、强元数据兜底（artist + 展会/杂志）、
     * 候选集内标题包含（同作者/原作/社团 + 标题包含，已排除续作）。
     * <p>只有纯 meta 兜底（仅 artist 命中、标题对不上）不采纳，回退联网 —— 同一作者名下
     * 多部作品时，随便取一部是错的。
     */
    public static boolean isConfident(LocalHit hit) {
        return METHOD_LOCAL_TITLE_EXACT.equals(hit.method())
                || METHOD_LOCAL_META_STRONG.equals(hit.method())
                || METHOD_LOCAL_TITLE_CONTAINS.equals(hit.method());
    }

    public MangaEhLocalDb(MangaProperties props) {
        this.dbPath = props.getEhScan().getLocalDbPath();
    }

    /**
     * 本地库能不能用：配了路径，且那个文件真的在。
     *
     * <p><b>只查文件在不在，不触发加载</b>。索引 190 万行、约 924 MB 活集、加载要 20 秒，
     * 而这个方法会被配置页提示与每次扫描的入口调用 —— 让它顺手 {@link #warmUp()} 等于
     * 「打开配置页就把索引拖进堆」。真正的加载仍只发生在 {@link #match} 与预热线程里。
     *
     * <p>「配了但文件不在」（路径写错、外挂盘没挂上）与「压根没配」后果完全一样，
     * 都按没配处理，只在 {@link #unavailableReason()} 里区分说法。
     */
    public boolean available() {
        return availableAt(dbPath);
    }

    /**
     * 拿<b>任意一份</b>路径问「这样配能不能用」。{@link #available()} 就是拿进程里那一份来问它。
     *
     * <p>为什么要参数化：配置页顶部的提示条判的是「<b>重启后会生效的值</b>」
     * （覆盖层 > 密钥文件 > 出厂值，见 {@code SettingsService#restartValue}），
     * 与进程里那一份可能不同 —— 2026-09-25 作者要求「删掉本地 eh 库之后不用重启就该看到提示」。
     * 提示与实际行为必须同一句话，所以判定口径只有这一处，提示条拿手边那份路径来问同一段代码。
     */
    public static boolean availableAt(String path) {
        return StringUtils.isNotBlank(path) && new File(path).isFile();
    }

    /**
     * 没配本地库时给人看的那句话。配置页提示与扫描日志用的是同一句。
     * <p>「没填」与「填了但不在」分开说：前者要人去填，后者要人去改。
     */
    public String unavailableReason() {
        return unavailableReasonFor(dbPath);
    }

    /** 给定一份路径时的那句话，见 {@link #availableAt} 说明为什么要参数化 */
    public static String unavailableReasonFor(String path) {
        if (StringUtils.isBlank(path)) {
            return "本地 eh 库没配（配置页「漫画 · 路径」里的本地 eh 库是空的），标签扫描直接走联网搜索";
        }
        return "本地 eh 库不在：" + path
                + "（配置页「漫画 · 路径」里的本地 eh 库），标签扫描直接走联网搜索";
    }

    /**
     * 单本匹配。命中返回 {@link LocalHit}；未命中、库文件不存在、加载失败或应跳过（date_tag
     * 晚于快照）时返回 {@code null}。
     * <p>匹配口径三级（便宜的先算）：精确 → meta（含候选内标题包含）→ 弱 meta。
     */
    public LocalHit match(MangaData m) {
        if (!ensureLoaded()) {
            return null;
        }
        if (skipByDate(m.getDateTag())) {
            return null;
        }
        String lk = stripKey(m.getTitle());
        if (lk == null) {
            return null;
        }
        // 1. 精确：完整/纯标题归一相等（stripExact 索引，最快）
        Row hit = stripExact.get(lk);
        if (hit != null) {
            return toHit(hit, METHOD_LOCAL_TITLE_EXACT, MangaTitleMatcher.SCORE_EXACT);
        }
        // 2. meta 候选集：artist 命中建集 → parody/group 交叉过滤（几十行，vs 全库 190 万）
        Set<Row> candidates = metaMatch(m);
        if (candidates == null) {
            return null;
        }
        // 3. 展会/杂志判定：两边都有值却对不上的整行剔除，对上的记为强信号。
        //    只作用于非精确档 —— 精确命中在第 1 步已返回，标题归一相等本身就够可靠。
        EventSplit split = splitByEvent(candidates, m);
        if (split.viable().isEmpty()) {
            return null;
        }
        // 4. 候选集内按标题相似度取最佳 —— 不能任取一条：同一作者名下几十本，
        //    任取等于蒙。展会/杂志对上的行优先，它们本身也要按标题排序，
        //    因为「同展会」的可能有多本。
        boolean strong = !split.strong().isEmpty();
        Scored best = bestByTitle(lk, strong ? split.strong() : split.viable());
        if (best == null) {
            return null;
        }
        // 标题达到包含档：同作者 + 标题包含，且已排除续作 —— 最可信的一档 meta 命中
        if (best.sim() >= MangaTitleMatcher.SCORE_CONTAINS) {
            return toHit(best.row(), METHOD_LOCAL_TITLE_CONTAINS, best.sim());
        }
        // 标题差太远：artist（乃至展会）对上也不足以认定是同一本，回退联网。
        // 不设这道门槛的话，作者对上就报命中，标题毫不相关也会被当成结果。
        if (best.sim() < SIM_MIN_META) {
            return null;
        }
        return strong
                ? toHit(best.row(), METHOD_LOCAL_META_STRONG, metaScore(best.sim(), true))
                : toHit(best.row(), METHOD_LOCAL_META, metaScore(best.sim(), false));
    }

    /**
     * meta 两档的分数 = 档位评分带 + 带内按标题相似度线性插值。
     *
     * <p><b>为什么不能只记档位基准分</b>：{@link #bestByTitle} 已经算出了真实的标题相似度，
     * 写死 0.7 / 0.8 等于把它扔掉。后果是「展会对上但标题相似度只有 0.45」与
     * 「标题相似度 0.84（差一点够包含档）但没展会」记成同一个数，事后按 similarity
     * 排序做人工复核时排不出先后 —— 这个字段就成了档位标签而不是测量值。
     *
     * <p><b>为什么不能只记相似度</b>：展会/杂志正向命中是独立于标题的旁证（同一作者同一展会
     * 通常只有一部），这条信息也得留在分数里，否则 STRONG 和 META 无从区分。
     *
     * <p>所以两者都留：<b>档位决定落在哪条带，相似度决定带内位置</b>。
     * 归一区间取 {@code [SIM_MIN_META, SCORE_CONTAINS)} —— 正是能走到 meta 档的相似度取值范围
     * （低于下限已在 {@link #match} 里判不命中，达到上限则记 CONTAINS 档了）。
     *
     * <p>算式：{@code 上界 - 带宽 × (1 - (sim - 0.4) / (0.85 - 0.4))}。
     * 端点：{@code sim=0.4} → META 0.60 / STRONG 0.70；{@code sim→0.85} → META →0.70 / STRONG →0.80。
     * 上界取不到（相似度达到 0.85 就走 CONTAINS 了），所以旧的 0.7 / 0.8 成了两档的确界。
     *
     * <p><b>历史数据</b>：改这个之前落库的 0.7 / 0.8 相当于各自带的上界，即偏高；
     * 重扫后同一本大概率降一点，那是分数变准了，不是匹配变差了。
     *
     * @param sim    候选集内最佳标题相似度，取值 {@code [SIM_MIN_META, SCORE_CONTAINS)}
     * @param strong 展会/杂志是否正向命中
     */
    public static double metaScore(double sim, boolean strong) {
        double ceiling = strong ? SCORE_LOCAL_META_STRONG : SCORE_LOCAL_META;
        double span = MangaTitleMatcher.SCORE_CONTAINS - SIM_MIN_META;
        // 入参已由 match 的两道门槛保证在区间内；这里仍夹一次，避免以后被别处直接调用时算出越界分
        double t = (sim - SIM_MIN_META) / span;
        t = Math.max(0, Math.min(1, t));
        return ceiling - META_BAND_WIDTH * (1 - t);
    }

    // ---- 加载 ----

    /**
     * 预热：提前把索引加载好，让第一次 {@link #match} 不用等这 20 秒。
     * <p>由 {@code MangaEhLocalDbWarmup} 在应用就绪后用后台线程调用。除了「谁来承担这段等待」
     * 之外与懒加载没有任何区别 —— 同一个 {@code synchronized} 入口，预热线程与真实请求撞上时
     * 后者等前者，不会重复加载。
     *
     * @return 是否加载成功（库文件不存在 / 未配置 / 加载出错都返回 {@code false}）
     */
    public boolean warmUp() {
        return ensureLoaded();
    }

    private boolean ensureLoaded() {
        if (loaded) {
            return true;
        }
        synchronized (this) {
            if (loaded) {
                return true;
            }
            if (dbPath == null || dbPath.isBlank()) {
                return false; // 未配置，禁用本地优先
            }
            File f = new File(dbPath);
            if (!f.isFile()) {
                return false; // 库文件不存在：不置 loaded，下次可能已生成，再试
            }
            try {
                load(f);
                loaded = true;
                return true;
            } catch (Exception e) {
                log.error("加载本地 e-hentai 数据源失败: {}", dbPath, e);
                return false;
            }
        }
    }

    private void load(File f) throws Exception {
        long t0 = System.currentTimeMillis();
        String abs = f.getAbsolutePath();
        Map<String, Row> exact = new HashMap<>();
        Map<String, List<Row>> artist = new HashMap<>();
        Map<String, List<Row>> parody = new HashMap<>();
        Map<String, List<Row>> group = new HashMap<>();
        // 展会/杂志 key 集合的规范化池：全库只有几千个不同展会/杂志，190 万行共用这几千个 Set 实例，
        // 否则每行两个 HashSet 会直接把堆吃穿。
        Map<Set<String>, Set<String>> keyCanon = new HashMap<>();
        int totalRows = 0;
        Map<String, Set<String>> enToJa = new HashMap<>();
        Map<String, Set<String>> jaToEn = new HashMap<>();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + abs)) {
            // 探测拆分工具（MangaEhTitleSplitUtil.phase1Schema）加的列：没跑过时这些列不存在，
            // 退回老口径，不能因为少几列让整个本地数据源失效。
            Set<String> cols = new HashSet<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("PRAGMA table_info(gallery)")) {
                while (rs.next()) {
                    cols.add(rs.getString("name"));
                }
            }
            boolean hasClean = cols.contains("title_clean") && cols.contains("title_jpn_clean");
            boolean hasJpnMeta = cols.contains("artist_jpn") && cols.contains("group_jpn")
                    && cols.contains("parody_jpn");
            boolean hasExhibit = cols.contains("exhibit") && cols.contains("exhibit_jpn")
                    && cols.contains("magazine") && cols.contains("magazine_jpn");

            StringBuilder sql = new StringBuilder(
                    "SELECT title_norm, title_jpn, gid, token, artist, group_name, parody");
            if (hasClean) {
                sql.append(", title_clean, title_jpn_clean");
            }
            if (hasJpnMeta) {
                sql.append(", artist_jpn, group_jpn, parody_jpn");
            }
            if (hasExhibit) {
                sql.append(", exhibit, exhibit_jpn, magazine, magazine_jpn");
            }
            sql.append(" FROM gallery");

            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql.toString())) {
                while (rs.next()) {
                    String norm = rs.getString("title_norm");
                    long gid = rs.getLong("gid"); // SQL NULL 读成 0，pandabrowser 数据都有 gid
                    String token = rs.getString("token");
                    // title_jpn 只用来算归一键，原字符串不留（命中后回库查）
                    String jpnNorm = stripKey(rs.getString("title_jpn"));
                    String cleanNorm = null;
                    String jpnCleanNorm = null;
                    if (hasClean) {
                        cleanNorm = stripKey(rs.getString("title_clean"));
                        jpnCleanNorm = stripKey(rs.getString("title_jpn_clean"));
                    }
                    Set<String> exhibitKeys = Set.of();
                    Set<String> magazineKeys = Set.of();
                    if (hasExhibit) {
                        exhibitKeys = canonKeys(rs.getString("exhibit"), rs.getString("exhibit_jpn"), keyCanon);
                        magazineKeys = canonKeys(rs.getString("magazine"), rs.getString("magazine_jpn"), keyCanon);
                    }
                    Row row = new Row(norm, jpnNorm, cleanNorm, jpnCleanNorm, gid, token,
                            exhibitKeys, magazineKeys);
                    // 精确键：完整标题（兜底）+ 纯标题（用户 manga_data.title 是纯标题，靠它精确命中）
                    exact.putIfAbsent(norm, row);
                    if (jpnNorm != null) {
                        exact.putIfAbsent(jpnNorm, row);
                    }
                    if (hasClean) {
                        if (cleanNorm != null) {
                            exact.putIfAbsent(cleanNorm, row);
                        }
                        if (jpnCleanNorm != null) {
                            exact.putIfAbsent(jpnCleanNorm, row);
                        }
                    }
                    totalRows++;
                    indexValues(rs.getString("artist"), artist, row);
                    indexValues(rs.getString("parody"), parody, row);
                    indexValues(rs.getString("group_name"), group, row);
                    if (hasJpnMeta) {
                        indexValues(rs.getString("artist_jpn"), artist, row);
                        indexValues(rs.getString("parody_jpn"), parody, row);
                        indexValues(rs.getString("group_jpn"), group, row);
                    }
                }
            }

            // 翻译字典双向映射（只认 AI 判定合理的）；表不存在（还没跑拆分工具）时忽略
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT en_key, ja_key FROM translate_dict WHERE ai_ok = 1")) {
                while (rs.next()) {
                    String ek = rs.getString("en_key");
                    String jk = rs.getString("ja_key");
                    if (ek != null && jk != null) {
                        enToJa.computeIfAbsent(ek, x -> new HashSet<>()).add(jk);
                        jaToEn.computeIfAbsent(jk, x -> new HashSet<>()).add(ek);
                    }
                }
            } catch (SQLException e) {
                log.debug("translate_dict 不存在或读取失败，翻译字典为空: {}", e.getMessage());
            }
        }
        this.dbAbsPath = abs;
        this.stripExact = exact;
        this.artistIdx = artist;
        this.parodyIdx = parody;
        this.groupIdx = group;
        this.transEnToJa = enToJa;
        this.transJaToEn = jaToEn;
        log.info("本地 e-hentai 数据源加载完成: {} 条，翻译对 {} 条，耗时 {} ms", totalRows,
                enToJa.size(), System.currentTimeMillis() - t0);
    }

    private LocalHit toHit(Row r, String method, double score) {
        Detail d = loadDetail(r.norm());
        return new LocalHit(r.gid(), r.token(), d.title(), d.titleJpn(), d.tags(), method, score);
    }

    /**
     * 按主键 {@code title_norm} 回库取命中行的标题与标签。
     *
     * <p>这三列不进内存索引（见类注释），换来的代价就是这一次查询。按主键单点查，
     * SQLite 走 {@code title_norm} 的自动索引亚毫秒级返回；一轮全量扫 15000 本最多查 15000 次，
     * 相对于每本动辄几秒的联网阶段可以忽略。
     *
     * <p><b>每次新开连接，不缓存</b>：SQLite 的 {@link Connection} 不能并发使用，而
     * {@link #match} 是并发入口（归档请求与批量任务可能同时在跑）。库是只读静态快照，
     * 开连接只是打开文件读个头，比为一个共享连接加锁划算。
     *
     * <p><b>查不到或出错时返回空 Detail 而不是抛</b>：调用方已经认定这是一次命中
     * （gid/token 都拿到了），这里失败只是拿不到展示用的标题和标签，把整次匹配作废
     * 反而更糟 —— 那会让本来能命中的漫画变成「本地未命中」去走联网。
     * 库是只读静态快照、norm 就是从它自己读出来的，正常情况查不空。
     */
    private Detail loadDetail(String norm) {
        String sql = "SELECT title, title_jpn, tags FROM gallery WHERE title_norm = ?";
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbAbsPath);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, norm);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new Detail(rs.getString("title"), rs.getString("title_jpn"),
                            parseTags(rs.getString("tags")));
                }
            }
        } catch (SQLException e) {
            log.warn("回查本地画廊详情失败 title_norm={}: {}", norm, e.getMessage());
        }
        return new Detail(null, null, List.of());
    }

    /** 把库里的标签 JSON 数组（["ns:name", ...]）解析成列表；解析失败返回空 */
    private static List<String> parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return List.of();
        }
        try {
            JSONArray arr = JSON.parseArray(tagsJson);
            List<String> out = new ArrayList<>(arr.size());
            for (int i = 0; i < arr.size(); i++) {
                out.add(arr.getString(i));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    // ---- 匹配口径 ----

    /**
     * 按作者/原作/社团圈出候选集：本地 artist 必须命中；parody/group 若有则也要命中同一条。
     * <p>作用是把范围从全库 190 万行压到几十行，后续的标题相似度比较只在这几十行里做。
     * 展会/杂志不在这里判，交给 {@link #splitByEvent}。
     * <p>每个 key 先经翻译字典（en↔ja）扩充，覆盖「库英文列有值、日文列没值，
     * 用户存日文」这类语言错配。
     *
     * @return 候选集；artist 没值或任一级过滤后为空则返回 {@code null}
     */
    private Set<Row> metaMatch(MangaData m) {
        Set<String> artists = expandKeys(splitKeys(m.getArtist()));
        if (artists.isEmpty()) {
            return null;
        }
        Set<Row> candidates = new HashSet<>();
        for (String ak : artists) {
            List<Row> rs = artistIdx.get(ak);
            if (rs != null) {
                candidates.addAll(rs);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        Set<String> parodies = expandKeys(splitKeys(m.getParody()));
        if (!parodies.isEmpty()) {
            candidates = intersect(candidates, parodyIdx, parodies);
            if (candidates.isEmpty()) {
                return null;
            }
        }
        Set<String> groups = expandKeys(splitKeys(m.getGroupName()));
        if (!groups.isEmpty()) {
            candidates = intersect(candidates, groupIdx, groups);
            if (candidates.isEmpty()) {
                return null;
            }
        }
        return candidates;
    }

    /**
     * 按展会/杂志把候选集切成「留下的」与「其中强信号的」，一次遍历两件事都办了。
     *
     * <p>口径见 {@link MangaEventKey}，三态：
     * <ul>
     *   <li><b>MATCH</b>（两边都有值且对得上）→ 留下，且计入强信号。同一作者同一展会通常只有一部，
     *       这是很硬的旁证。</li>
     *   <li><b>CONFLICT</b>（两边都有值但对不上）→ <b>整行剔除</b>。写法差异容忍
     *       （{@code C97} / {@code コミックマーケット97}），但期号/届数/日期必须对上，
     *       {@code C97} 不会是 {@code C98}。</li>
     *   <li><b>UNKNOWN</b>（任一边没值）→ 留下但不加分。本地拆分列覆盖不全、目录名也不总写展会，
     *       缺值当冲突会误杀大量本可命中的漫画。</li>
     * </ul>
     *
     * <p>展会与杂志<b>各自独立判</b>：任一项 CONFLICT 即剔除，任一项 MATCH 即算强。同人志有展会
     * 没杂志、商业志有杂志没展会，两者不该互相牵连。
     *
     * <p><b>强信号集交的是行、不是 boolean</b>：只知道「有行命中」还不够 —— 作者名下几十本时，
     * 命中展会的可能是第 7 本，调用方若从候选集任取一条就取到了别的书。这些行要交回去参与
     * 「按标题相似度取最佳」，否则强信号反而带来错配。由构造保证 {@code strong ⊆ viable}。
     */
    private EventSplit splitByEvent(Set<Row> candidates, MangaData m) {
        Set<String> exhibits = expandKeys(splitKeys(m.getExhibit()));
        Set<String> magazines = expandKeys(splitKeys(m.getMagazine()));
        if (exhibits.isEmpty() && magazines.isEmpty()) {
            return new EventSplit(candidates, Set.of()); // 漫画两侧都没值，判不了，全放过
        }
        Set<Row> viable = new HashSet<>(candidates.size());
        Set<Row> strong = new HashSet<>();
        for (Row r : candidates) {
            MangaEventKey.Verdict ex = MangaEventKey.classify(exhibits, r.exhibitKeys());
            if (ex == MangaEventKey.Verdict.CONFLICT) {
                continue;
            }
            MangaEventKey.Verdict mag = MangaEventKey.classify(magazines, r.magazineKeys());
            if (mag == MangaEventKey.Verdict.CONFLICT) {
                continue;
            }
            viable.add(r);
            if (ex == MangaEventKey.Verdict.MATCH || mag == MangaEventKey.Verdict.MATCH) {
                strong.add(r);
            }
        }
        return new EventSplit(viable, strong);
    }

    /** 候选集内按标题相似度取最佳的一条；候选集为空返回 {@code null}。 */
    private static Scored bestByTitle(String lk, Set<Row> candidates) {
        Row best = null;
        double bestSim = -1;
        for (Row r : candidates) {
            double sim = Math.max(
                    Math.max(MangaTitleMatcher.similarity(lk, r.norm()),
                            MangaTitleMatcher.similarity(lk, r.jpnNorm())),
                    Math.max(MangaTitleMatcher.similarity(lk, r.cleanNorm()),
                            MangaTitleMatcher.similarity(lk, r.jpnCleanNorm())));
            if (sim > bestSim) {
                bestSim = sim;
                best = r;
            }
        }
        return best == null ? null : new Scored(best, bestSim);
    }

    /** 用翻译字典把每个 key 的双向翻译补进集合，翻译字典为空时原样返回。 */
    private Set<String> expandKeys(Set<String> keys) {
        if (keys.isEmpty() || (transEnToJa.isEmpty() && transJaToEn.isEmpty())) {
            return keys;
        }
        Set<String> out = new HashSet<>(keys);
        for (String k : keys) {
            Set<String> t = transEnToJa.get(k);
            if (t != null) {
                out.addAll(t);
            }
            t = transJaToEn.get(k);
            if (t != null) {
                out.addAll(t);
            }
        }
        return out;
    }

    private static Set<Row> intersect(Set<Row> base, Map<String, List<Row>> idx, Set<String> keys) {
        Set<Row> out = new HashSet<>();
        for (String k : keys) {
            List<Row> rs = idx.get(k);
            if (rs != null) {
                for (Row r : rs) {
                    if (base.contains(r)) {
                        out.add(r);
                    }
                }
            }
        }
        return out;
    }

    /**
     * 把英文列 + 日文列两个 ';' 分隔多值合成一个归一 key 集合，并经 {@code canon} 池去重实例。
     * <p>返回不可变集合；无值时返回共享的 {@link Set#of()}，不为空值分配对象。
     */
    private static Set<String> canonKeys(String en, String jpn, Map<Set<String>, Set<String>> canon) {
        Set<String> keys = new HashSet<>(4);
        addSplit(en, keys);
        addSplit(jpn, keys);
        if (keys.isEmpty()) {
            return Set.of();
        }
        Set<String> existing = canon.get(keys);
        if (existing != null) {
            return existing;
        }
        Set<String> frozen = Set.copyOf(keys);
        canon.put(frozen, frozen);
        return frozen;
    }

    /** 把 db 的 ';' 分隔多值列拆成归一 key 加进 {@code out}。 */
    private static void addSplit(String csv, Set<String> out) {
        if (csv == null || csv.isBlank()) {
            return;
        }
        for (String v : csv.split(";")) {
            String k = stripKey(v);
            if (k != null) {
                out.add(k);
            }
        }
    }

    /** 把 db 的 ';' 分隔多值列拆成单个 key 入索引。 */
    private static void indexValues(String csv, Map<String, List<Row>> idx, Row row) {
        if (csv == null || csv.isBlank()) {
            return;
        }
        for (String v : csv.split(";")) {
            String k = stripKey(v);
            if (k != null) {
                idx.computeIfAbsent(k, x -> new ArrayList<>()).add(row);
            }
        }
    }

    /** 本地多值字段（作者/原作/社团）拆成单个归一 key 集合。 */
    private static Set<String> splitKeys(String csv) {
        Set<String> keys = new HashSet<>();
        if (csv == null || csv.isBlank()) {
            return keys;
        }
        for (String v : csv.split("[、;，,；]")) {
            String k = stripKey(v);
            if (k != null) {
                keys.add(k);
            }
        }
        return keys;
    }

    /**
     * date_tag 跳过判定：数据源是 2025.08 的快照，之后出的漫画肯定不在里面。
     * <p>{@code dateTag} 格式 {@code YYYY / YY / YYYY.MM / YY.MM / YYYY.MM.DD / YY.MM.DD}。
     * 有月份按 {@code ≥ 2025.08} 跳过；只有年份按 {@code ≥ 2026} 跳过。
     */
    public static boolean skipByDate(String dateTag) {
        if (dateTag == null || dateTag.isBlank()) {
            return false;
        }
        String[] parts = dateTag.trim().split("\\.");
        int year;
        int month = 0;
        try {
            year = Integer.parseInt(parts[0].trim());
            if (parts.length > 1) {
                month = Integer.parseInt(parts[1].trim());
            }
        } catch (NumberFormatException e) {
            return false;
        }
        if (year < 100) {
            year += 2000;
        }
        if (parts.length > 1) {
            return year > 2025 || (year == 2025 && month >= 8);
        }
        return year >= 2026;
    }

    /** 与 merge 脚本的 title_norm 一致：NFC + trim + 大写，只留字母数字（含 CJK）。 */
    public static String stripKey(String raw) {
        String k = MangaTextUtil.normalizeNameKey(raw);
        if (k == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < k.length(); i++) {
            char c = k.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
