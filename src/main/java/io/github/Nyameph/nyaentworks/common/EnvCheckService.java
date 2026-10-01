package io.github.Nyameph.nyaentworks.common;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import io.github.Nyameph.nyaentworks.common.config.LocalAiProperties;
import io.github.Nyameph.nyaentworks.common.db.SqlDialect;
import io.github.Nyameph.nyaentworks.common.settings.SettingsCatalog;
import io.github.Nyameph.nyaentworks.common.tool.ExternalCommandProbe;
import io.github.Nyameph.nyaentworks.manga.config.MangaProperties;
import io.github.Nyameph.nyaentworks.manga.consts.MangaDictType;
import io.github.Nyameph.nyaentworks.manga.dict.MangaDictionary;
import io.github.Nyameph.nyaentworks.manga.service.MangaDictService;
import io.github.Nyameph.nyaentworks.manga.util.MangaNameParser;
import io.github.Nyameph.nyaentworks.manga.util.MangaScoreDir;
import io.github.Nyameph.nyaentworks.shout.config.ShoutProperties;
import io.github.Nyameph.nyaentworks.song.config.SongProperties;
import io.github.Nyameph.nyaentworks.common.media.ScorePartition;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 环境自检。单机本地系统特有的一页：MySQL、归档目录、NConvert、建表、词典是否就绪。
 * <p>做这个的理由是「空列表和功能坏了在界面上长得一样」 —— {@code F:\MangaGroup} 没挂上时
 * 扫描结果为空，与「确实没有漫画」无法区分。所以主动把缺什么报出来。
 *
 * <p>两条补充口径（《桌面化收尾（2026-09-25 实施）》阶段 1 / 4.1，见 docs/已完成/桌面壳实施计划.md）：
 * <ul>
 *   <li><b>模块判据</b> —— 各模块自己的检查组（表、列、根、NConvert、词典）前面都有一道
 *       {@link #inBuild}：模块不在本次构建里就整组不出现，不发假警报；</li>
 *   <li><b>能力探测</b> —— python / curl / 本机 AI 端点这些<b>可选</b>依赖报「有 / 没有」，
 *       缺了不报错只降级，但降级必须看得见（不再「用到时才静默失败」）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class EnvCheckService {

    private final JdbcTemplate jdbcTemplate;
    private final MangaDictService mangaDictService;
    private final MangaProperties mangaProperties;
    private final SongProperties songProperties;
    private final ShoutProperties shoutProperties;
    /** 只用它问「这个模块在不在本次构建里」，见 {@link #inBuild} */
    private final SettingsCatalog settingsCatalog;
    private final ExternalCommandProbe toolProbe;
    private final LocalAiProperties localAi;

    /** 探本机 AI 端点可达性用的；短连接超时 —— 本机地址连不上要立刻知道，不能拖住自检 */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    /**
     * 一项检查。
     *
     * @param level OK / WARN / ERROR。WARN 表示能用但会有隐性后果，ERROR 表示相关功能直接不可用
     * @param hint  怎么修。为空表示无需处理
     */
    public record CheckItem(String name, String level, String detail, String hint) {

        static CheckItem ok(String name, String detail) {
            return new CheckItem(name, "OK", detail, null);
        }

        static CheckItem warn(String name, String detail, String hint) {
            return new CheckItem(name, "WARN", detail, hint);
        }

        static CheckItem error(String name, String detail, String hint) {
            return new CheckItem(name, "ERROR", detail, hint);
        }
    }

    /** 检查结果汇总，{@code errors} 大于 0 时左下角亮红点 */
    public record EnvReport(List<CheckItem> items, int errors, int warns) {
    }

    public EnvReport check() {
        List<CheckItem> items = new ArrayList<>();
        items.add(checkModulePresence());
        items.add(checkDatabase());
        items.addAll(checkTables());
        // 以下每组检查前面都有一道「模块在不在本次构建里」的判据：不在就整组不 append ——
        // 不是把 level 改成 OK、也不是标「不可用」，而是那个模块根本不在这份构建里，
        // 它的事就不该出现在页面上（《桌面化收尾（2026-09-25 实施）》阶段 1）。
        if (inBuild("manga")) {
            items.addAll(checkArchiveRoots());
            items.add(checkMangaDataColumns());
        }
        if (inBuild("song")) {
            items.add(checkSongOriginalColumns());
        }
        items.addAll(checkArchiveFileColumns());
        if (inBuild("manga")) {
            items.addAll(checkMangaScanRoots());
            items.add(checkNconvert());
            items.addAll(checkDictionary());
        }
        items.addAll(checkSongRoots());
        items.addAll(checkCapabilities());

        int errors = (int) items.stream().filter(i -> "ERROR".equals(i.level())).count();
        int warns = (int) items.stream().filter(i -> "WARN".equals(i.level())).count();
        return new EnvReport(items, errors, warns);
    }

    /**
     * 这个模块<b>在本次构建里</b>吗 —— 判据只有一处：{@link SettingsCatalog#moduleExists}
     * （{@code nya-entworks.<模块>.enabled} 这个键读不读得到）。
     *
     * <p><b>「读到 false」与「读不到」是两件事</b>，别在这里混：读到 {@code false} ＝ 模块在、
     * 只是关着，它的检查组<b>照常出现</b>（关着的模块正需要自检告诉你「缺什么」）；
     * 读不到 ＝ 这份构建里根本没有它，它的检查组一条都不出现 —— 否则组里的路径检查
     * 读到的全是 Java 字段默认值（{@code F:\…}），报出来的就是一条
     * 「未归档根 F:\NetdiskDownload\#待整理散漫 不存在」这类
     * <b>指着对方机器上根本不该存在的路径的假警报</b>（2026-09-18 实测）。
     *
     * <p>判据必须走属性源（{@code moduleExists}），不能用 Properties Bean 的
     * {@code isEnabled()} —— 三个 {@code enabled} 字段的 Java 默认值都是 {@code false}，
     * getter 分不出「没写」与「写了 false」。
     */
    private boolean inBuild(String moduleId) {
        return settingsCatalog.moduleExists(moduleId);
    }

    /**
     * 本次构建里<b>一个模块都没有</b> —— 覆盖层没带上的典型症状，也是「三个模块一起消失」
     * 唯一能在界面上看见的地方（2026-09-25 加）。
     *
     * <p>为什么值得单列一条 ERROR：那个状态下**什么都不报错**。配置页只剩通用那几项、
     * 左侧导航是空的、接口全不注册 —— 全都不是异常，换台机器打开只会觉得「这个应用
     * 就长这样」，而不是「我少带了个文件」。所以这里必须主动喊一声。
     *
     * <p>判据与 {@link #inBuild} 同源（{@link SettingsCatalog#moduleExists}）。
     * <b>「读到 false」不算</b>：三个都写 {@code false} 是「都关着」，那是明确的选择，
     * 页面上那三个开关还能开回来（见 {@code 配置页设计.md} §12 的三态表）。
     * 只有「一个键都读不到」才是这份构建里真的没有模块。
     */
    private CheckItem checkModulePresence() {
        List<String> present = SettingsCatalog.MODULES.stream().filter(this::inBuild).toList();
        if (!present.isEmpty()) {
            return CheckItem.ok("模块", "本次构建里有 " + String.join(" / ", present));
        }
        return CheckItem.error("模块",
                "本次构建里一个模块都没有：manga / song / shout 的 enabled 键全都读不到",
                "多半是 config/nya-entworks.yaml 没带上（application.yaml 里有意不留这几个键，"
                        + "所以它是唯一的来路）。把 config/nya-entworks.example.yaml 拷成 "
                        + "config/nya-entworks.yaml、留下要用的模块段（enabled: true），再重启后端。"
                        + "见 docs/桌面化与模块裁剪设计.md §5.2");
    }

    /**
     * 连得上库吗。<b>名字与文案跟着方言走</b>：发出去的包跑的是 SQLite（本地一个文件），
     * 那里报「确认本机 MySQL 已启动」是彻底指错方向。
     */
    private CheckItem checkDatabase() {
        String what = SqlDialect.displayName();
        try {
            jdbcTemplate.queryForObject("select 1", Integer.class);
            return CheckItem.ok(what + " 连接", what + " 可连接");
        } catch (Exception e) {
            return CheckItem.error(what + " 连接", e.getMessage(),
                    SqlDialect.isSqlite()
                            ? "数据库文件打不开（data/nya_entworks.sqlite）—— "
                            + "目录不可写、文件被占用，或者磁盘满了"
                            : "确认本机 MySQL 已启动，且 application.yaml 里的库名/账号正确");
        }
    }

    /**
     * 全库唯一的一份建表脚本。20 张表都在它里面，全是 {@code CREATE TABLE IF NOT EXISTS}，
     * 可重复执行；**不含任何 {@code ALTER}** —— 它是「当前结构」的快照，缺结构就改正文，
     * 而**已经建好的库**（重跑本文件是 no-op、补不上列）走 {@code docs/sql/待执行/} 那条通道。
     *
     * <p>SQLite 那份由发版脚本按同一份 DDL 转出来，随包打进 jar，<b>首启由
     * {@code spring.sql.init} 自动执行</b>（见 {@code db/nya_entworks.sqlite.sql}）——
     * 所以那边不存在「手工跑一遍」这件事，缺表的提示也跟着不同。
     */
    private static final String DDL_SCRIPT = "db/nya_entworks.sql";

    /** 缺表时那句「怎么修」。两个库的修法不一样，混着说等于没说。 */
    private static String fixTableHint() {
        return SqlDialect.isSqlite()
                ? "随包内置的建表脚本没能生效 —— 重启后端再试；仍缺多半是 data/ 目录不可写"
                : "手工执行 " + DDL_SCRIPT;
    }

    /** 缺列时那句「怎么修」。SQLite 那份是发版时按快照新生成的，不存在「老库缺列」。 */
    private static String fixColumnHint() {
        return SqlDialect.isSqlite()
                ? "内置建表脚本与程序对不上（发版包自相矛盾）—— 报给发版的人"
                : "建表脚本只建表、不会给已有的表补列 —— 让 AI 把补列语句写到 docs/sql/待执行/ 下再执行。";
    }

    /** 表结构无迁移工具、靠手工跑 {@link #DDL_SCRIPT}，所以缺表是常见状态，值得逐张报 */
    private List<CheckItem> checkTables() {
        List<CheckItem> items = new ArrayList<>();
        // 每张表标上所属模块（null = 通用表，任何构建都在）。**表跟着模块走**：
        // 模块不在本次构建里时它的表连检查一起跳过 —— 那份构建里那些表本来就不该被用，
        // 留着检查只会让「只要歌曲」的包里显示一堆漫画的表（《桌面化收尾（2026-09-25 实施）》阶段 1 的口径）。
        record ModuleTable(String module, String table) {
        }
        List<ModuleTable> tables = List.of(
                new ModuleTable("manga", "manga_dict_entry"),
                new ModuleTable("manga", "manga_archive_unit"),
                new ModuleTable("manga", "manga_archive_name"),
                new ModuleTable("manga", "manga_tag"),
                new ModuleTable("manga", "manga_tag_ref"),
                new ModuleTable("manga", "manga_data"),
                new ModuleTable("manga", "manga_eh_scan"),
                new ModuleTable(null, "async_task"),
                new ModuleTable(null, "file_op_log"),
                new ModuleTable("song", "song_group"),
                new ModuleTable("song", "song_file"),
                new ModuleTable("song", "song_tag"),
                new ModuleTable("song", "song_original_setting"),
                new ModuleTable("song", "song_lyric_fill"),
                new ModuleTable("song", "rhyme_entry"),
                new ModuleTable("song", "lyric_corpus_line"),
                new ModuleTable("song", "lyric_corpus_pair"),
                new ModuleTable("shout", "shout_group"),
                new ModuleTable("shout", "shout_file"),
                new ModuleTable("shout", "shout_tag"));
        for (ModuleTable mt : tables) {
            if (mt.module() != null && !inBuild(mt.module())) {
                continue;
            }
            if (tableExists(mt.table())) {
                items.add(CheckItem.ok("表 " + mt.table(), "已存在"));
            } else {
                items.add(CheckItem.error("表 " + mt.table(), "不存在", fixTableHint()));
            }
        }
        return items;
    }

    private boolean tableExists(String table) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    SqlDialect.tableExistsSql(), Integer.class, table);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * {@code manga_data} 的四个新列。
     * <p>单独检查而不是只看表在不在：这张表远早于「新漫画」这一块存在，
     * 建表语句以前也不在仓库里，所以「表有、列没有」是很可能的状态 ——
     * 那时存储会在最后一步落库时失败，而压缩已经跑完且不可回滚了。
     */
    private CheckItem checkMangaDataColumns() {
        if (!tableExists("manga_data")) {
            // 表本身缺失已由 checkTables 报过，这里不重复
            return CheckItem.ok("表 manga_data 的列", "表不存在，跳过列检查");
        }
        List<String> required = List.of("score", "score_source", "status", "archive_unit_id");
        List<String> missing = new ArrayList<>();
        for (String column : required) {
            try {
                Integer count = jdbcTemplate.queryForObject(
                        SqlDialect.columnExistsSql("manga_data"), Integer.class, column);
                if (count == null || count == 0) {
                    missing.add(column);
                }
            } catch (Exception e) {
                return CheckItem.error("表 manga_data 的列", e.getMessage(),
                        "确认 " + SqlDialect.displayName() + " 可连接");
            }
        }
        return missing.isEmpty()
                ? CheckItem.ok("表 manga_data 的列", "score / score_source / status / archive_unit_id 齐全")
                : CheckItem.error("表 manga_data 的列", "缺列：" + String.join("、", missing),
                fixColumnHint() + "缺列时存储会在最后落库那步失败，而压缩已经跑完且不可回滚");
    }

    /**
     * {@code song_original_setting} 的 {@code last_search_time} 列。
     * <p>「搜资源」按钮 / 批量补全搜完后会写这一列，缺列时查询/回写会在运行时报
     * Unknown column，与 manga_data 的「表有、列没有」是同一类坑。
     */
    private CheckItem checkSongOriginalColumns() {
        if (!tableExists("song_original_setting")) {
            // 表本身缺失已由 checkTables 报过，这里不重复
            return CheckItem.ok("表 song_original_setting 的列", "表不存在，跳过列检查");
        }
        List<String> required = List.of("last_search_time");
        List<String> missing = new ArrayList<>();
        for (String column : required) {
            try {
                Integer count = jdbcTemplate.queryForObject(
                        SqlDialect.columnExistsSql("song_original_setting"), Integer.class, column);
                if (count == null || count == 0) {
                    missing.add(column);
                }
            } catch (Exception e) {
                return CheckItem.error("表 song_original_setting 的列", e.getMessage(),
                        "确认 " + SqlDialect.displayName() + " 可连接");
            }
        }
        return missing.isEmpty()
                ? CheckItem.ok("表 song_original_setting 的列", "last_search_time 齐全")
                : CheckItem.error("表 song_original_setting 的列", "缺列：" + String.join("、", missing),
                fixColumnHint() + "缺列时「搜资源」按钮会在查询时失败");
    }

    /**
     * {@code song_file} / {@code shout_file} 的 {@code suffix} 列。
     *
     * <p>2026-09-23 起这两张表存 {@code main_name} + {@code suffix} 两段，
     * 完整文件名不再存列（恒等于两段之和）—— 老库要跑一次 {@code docs/sql/待执行/}
     * 里那条补 {@code suffix} 的一次性 SQL 才有这一列。
     *
     * <p><b>为什么值得单独报</b>：缺它的症状是同步在**插行**时报 Unknown column，
     * 而同步是异步任务、失败只落在任务页那一条上 —— 页面上看起来只是「列表没更新」，
     * 与「外挂盘没挂」长得一模一样。这里报出来就是一个红点，不用去猜。
     */
    private List<CheckItem> checkArchiveFileColumns() {
        List<CheckItem> items = new ArrayList<>();
        record ModuleTable(String module, String table) {
        }
        // 同一段里两半分属两个模块，各自过一遍「模块在不在」的判据
        for (ModuleTable mt : List.of(new ModuleTable("song", "song_file"),
                new ModuleTable("shout", "shout_file"))) {
            if (!inBuild(mt.module())) {
                continue;
            }
            String table = mt.table();
            String name = "表 " + table + " 的列";
            if (!tableExists(table)) {
                // 表本身缺失已由 checkTables 报过，这里不重复
                items.add(CheckItem.ok(name, "表不存在，跳过列检查"));
                continue;
            }
            try {
                Integer count = jdbcTemplate.queryForObject(
                        SqlDialect.columnExistsSql(table), Integer.class, "suffix");
                items.add(count != null && count > 0
                        ? CheckItem.ok(name, "suffix 齐全")
                        : CheckItem.error(name, "缺列：suffix",
                        fixColumnHint() + "缺列时同步会在插行那步失败，而失败只落在任务页里"));
            } catch (Exception e) {
                items.add(CheckItem.error(name, e.getMessage(),
                        "确认 " + SqlDialect.displayName() + " 可连接"));
            }
        }
        return items;
    }

    /**
     * 归档根及其四个评分分区。
     * <p>分区目录按磁盘现扫（{@code MangaScoreDir}）—— 分区名后半段（百读不厌之类）
     * 是人起的，代码里不重复一份。缺哪个分区要单独报：打分就是往分区里搬，
     * 分区不在时报「找不到目录」不如这里一次说清缺哪几个。
     */
    private List<CheckItem> checkArchiveRoots() {
        List<CheckItem> items = new ArrayList<>();
        String archiveRoot = mangaProperties.getArchiveDir();
        if (!new File(archiveRoot).isDirectory()) {
            // 这条必须是 ERROR：归档根不在时 sync 会把该根下所有 unit 判成 MISSING。
            // 整根都不在，再逐分区报只是把同一件事说四遍
            items.add(CheckItem.error("归档根", archiveRoot + " 不存在",
                    "外挂盘没挂上？此时同步会把归档目录全判为失踪，先别跑同步"));
            return items;
        }
        Map<Integer, Path> dirs = MangaScoreDir.scoreDirs(archiveRoot);
        for (int score : MangaScoreDir.SCORES) {
            Path dir = dirs.get(score);
            if (dir == null) {
                items.add(CheckItem.error("归档根 " + score + " 分",
                        archiveRoot + " 下没有 " + score + " 分的分区目录（形如 " + score + "-百读不厌）",
                        "分区目录是人建好的固定四个，缺了就没法归档到这个分数"));
            } else {
                items.add(CheckItem.ok("归档根 " + score + " 分", dir.toString()));
            }
        }
        return items;
    }

    /**
     * NConvert 在不在。<b>「没填」与「填了但文件不在」分开说</b>：出厂值就是空串，
     * 空着不做特殊处理的话这里会报「 不存在」（前面是个空的路径），读起来像路径写错了，
     * 而实际只是没配 —— 两件事要补的地方不一样。
     */
    private CheckItem checkNconvert() {
        String nconvertPath = mangaProperties.getNconvert();
        if (nconvertPath == null || nconvertPath.isBlank()) {
            return CheckItem.warn("NConvert",
                    "没配（配置页「漫画 · 工具与开关」里的 NConvert 可执行文件是空的）",
                    "归档时不压缩图片，归档流程本身照常；其余功能不受影响");
        }
        File exe = new File(nconvertPath);
        return exe.isFile()
                ? CheckItem.ok("NConvert", nconvertPath)
                : CheckItem.warn("NConvert", nconvertPath + " 不存在", "压缩功能不可用，其余功能不受影响");
    }

    /**
     * 合集与未归档两个扫描根，以及未归档下的四个评分分区。
     * <p>评分分区单独检查是因为「打分」这个动作就是把漫画移进分区目录
     * （评分存在目录名里，见 {@code MangaScoreDir}）—— 分区缺了页面上看不出来，
     * 不如在这里一次报清楚缺哪几个。
     * <p><b>新漫画根不在这里查</b>（2026-09-30 去掉，别再加回来）：它那两件事都有别的地方管 ——
     * 根目录缺着时「新漫画」页自己会挂遮罩（{@code PageGates}，见 {@code docs/配置页设计.md} §19），
     * 缺哪一档分区时打分（{@code MangaStoreService#score}）会自己把裸 {@code #9-} 建出来
     * （「打分自动建分区」，见 {@code docs/速查索引.md} 第 23 条）。留一行没人再据此动作的重复
     * 只会让自检长出来。
     */
    private List<CheckItem> checkMangaScanRoots() {
        List<CheckItem> items = new ArrayList<>();
        record RootCheck(String name, String path, boolean needScoreDirs) {
        }
        List<RootCheck> checks = List.of(
                new RootCheck("新作者合集根", mangaProperties.getCollectionDir(), false),
                new RootCheck("未归档根", mangaProperties.getUnarchivedDir(), true));

        for (RootCheck check : checks) {
            if (!new File(check.path()).isDirectory()) {
                // 与归档根同理：目录不在时扫描结果是空的，与「确实没有漫画」长得一样
                // （措辞不点具体页面：这里现在只剩合集根与未归档根，一个喂合集页签、一个喂未归档页）
                items.add(CheckItem.error(check.name(), check.path() + " 不存在",
                        "外挂盘没挂上？扫描出的是空列表，与「确实没有漫画」无法区分"));
                continue;
            }
            if (!check.needScoreDirs()) {
                items.add(CheckItem.ok(check.name(), check.path()));
                continue;
            }
            Map<Integer, Path> scoreDirs = MangaScoreDir.scoreDirs(check.path());
            List<String> missing = new ArrayList<>();
            for (int score : MangaScoreDir.SCORES) {
                if (!scoreDirs.containsKey(score)) {
                    missing.add(String.valueOf(score));
                }
            }
            if (missing.isEmpty()) {
                items.add(CheckItem.ok(check.name(), check.path() + "（四个评分分区齐全）"));
            } else {
                items.add(CheckItem.warn(check.name(),
                        check.path() + " 缺评分分区：" + String.join("、", missing) + " 分",
                        "分区目录形如 #9-百读不厌；缺的这一档里当然没有漫画，"
                                + "打分进这一档时程序会自己把裸目录建出来（名字只有分数字，评语回头自己加）"));
            }
        }
        return items;
    }

    /**
     * 歌曲与喊麦的根、待打分根、评分分区。
     *
     * <p><b>这里是 WARN 而不是 ERROR</b>，与漫画归档根刻意不同：漫画那边根目录不在时
     * {@code sync} 会把该根下所有 unit 判成 MISSING —— 有一个会<b>改库</b>的动作
     * 需要被拦在前面。歌曲 / 喊麦的同步遇到归档根不在会<b>直接跳过</b>、一个 MISSING
     * 都不标，根不在只会让列表是空的，而空列表本身就在页面上写着「根目录不存在」。
     * 所以够不上 ERROR。
     *
     * <p>分区缺失也只是 WARN：打分时缺的那一档会<b>自动建出来</b>（{@code ScorePartition.resolveDir}
     * 给裸 {@code #9}、落盘时建目录），所以这里只是提前告诉人「这一档还没建」，
     * 不是拦路的事。「导入 / 添加文件」那条路仍要求分区已存在，但那是任务级的拦截、
     * 由 {@code plan} 当场说清缺哪一档，也不够 ERROR。
     */
    private List<CheckItem> checkSongRoots() {
        List<CheckItem> items = new ArrayList<>();
        /**
         * @param needPartitions 归档根才查评分分区（待打分根下面不是分区，是散文件）
         */
        record RootCheck(String name, String path, boolean needPartitions, String module) {
        }
        // 前半是歌曲、后半是喊麦，各过各的「模块在不在」判据 —— 关掉喊麦时连「喊麦根不存在」
        // 这条 WARN 也不该出现
        List<RootCheck> checks = new ArrayList<>();
        if (inBuild("song")) {
            checks.add(new RootCheck("歌曲根", songProperties.getSongDir(), true, "song"));
            checks.add(new RootCheck("待打分根", songProperties.getStagingDir(), false, "song"));
        }
        if (inBuild("shout")) {
            checks.add(new RootCheck("喊麦根", shoutProperties.getArchivedDir(), true, "shout"));
            checks.add(new RootCheck("喊麦待打分根", shoutProperties.getStagingDir(), false, "shout"));
        }

        for (RootCheck check : checks) {
            // 「没填」与「填了但目录不在」分开说（照 checkNconvert 的先例）：出厂值就是空串
            // （2026-09-30 起，见 SongProperties#songDir），不分开的话这里会报「 不存在」
            // （前面是个空路径），读起来像路径写错了，而实际只是还没配 —— 发出去的包上
            // 这是**一定**会经过的一步，不能让人对着一条假警报猜。
            if (check.path() == null || check.path().isBlank()) {
                items.add(CheckItem.warn(check.name(), "没配",
                        "去左下角「系统配置」的「" + (check.module().equals("song") ? "歌曲" : "喊麦")
                                + " · 路径」里填上；填完要重启后端才生效"));
                continue;
            }
            if (!new File(check.path()).isDirectory()) {
                items.add(CheckItem.warn(check.name(), check.path() + " 不存在",
                        // 归档根有镜像与同步，但同步遇到根不在会跳过、不判 MISSING；
                        // 待打分根压根不入库（每次现扫）。两者都只是列表为空
                        check.needPartitions()
                                ? "外挂盘没挂上？同步会跳过这一根（不会误判 MISSING），只是列表为空"
                                : "外挂盘没挂上？这里不入库，所以只是列表为空，其余模块不受影响"));
                continue;
            }
            if (!check.needPartitions()) {
                items.add(CheckItem.ok(check.name(), check.path() + " 存在"));
                continue;
            }
            List<ScorePartition.Partition> partitions = ScorePartition.list(check.path());
            if (partitions.isEmpty()) {
                items.add(CheckItem.warn(check.name(),
                        check.path() + " 下没有评分分区目录",
                        // 打分时缺的那一档会自动建（ScorePartition.resolveDir）—— 所以不是
                        // 「没分区就没法打分」了，而是「选哪一档就建哪一档」；这里只是提前说一声
                        "分区目录形如 #9超赞；打分选哪一档就自动建哪一档（没评语，"
                                + "回头自己改名加评语即可），「导入 / 添加文件」仍要求先建好"));
                continue;
            }
            items.add(CheckItem.ok(check.name(), check.path() + "（"
                    + partitions.size() + " 个分区："
                    + partitions.stream().map(ScorePartition.Partition::dirName)
                    .collect(java.util.stream.Collectors.joining(" ")) + "）"));
        }
        return items;
    }

    /**
     * 词典自检，两项都来自 {@code MangaDictDiagnoseTest} 的结论：
     * 某类型为空会让该类解析全线漏判；无法编译的正则是静默失效的。
     */
    private List<CheckItem> checkDictionary() {
        List<CheckItem> items = new ArrayList<>();
        MangaDictionary dict;
        try {
            dict = mangaDictService.current();
        } catch (Exception e) {
            items.add(CheckItem.error("词典", e.getMessage(), "确认 manga_dict_entry 表存在且可读"));
            return items;
        }

        List<String> emptyTypes = new ArrayList<>();
        for (MangaDictType type : MangaDictType.values()) {
            if (!dict.hasEntry(type)) {
                emptyTypes.add(type.name());
            }
        }
        if (emptyTypes.isEmpty()) {
            items.add(CheckItem.ok("词典类型分布", "版本 " + dict.getVersion() + "，各类型均有词条"));
        } else {
            items.add(CheckItem.warn("词典类型分布",
                    "以下类型没有词条：" + String.join("、", emptyTypes),
                    "该类型相关的识别会全部漏判，到词典页补录"));
        }

        List<String> invalid = dict.getInvalidRegexValues();
        if (invalid.isEmpty()) {
            items.add(CheckItem.ok("词典正则", "全部可编译"));
        } else {
            items.add(CheckItem.error("词典正则", "无法编译并已被跳过：" + invalid,
                    "这些词条静默失效了，到词典页修正"));
        }
        return items;
    }

    // ==================== 可选依赖的能力探测（《桌面化收尾（2026-09-25 实施）》4.1） ====================

    /**
     * 可选外部依赖「有 / 没有」的探测。与上面的表 / 列 / 磁盘根检查互补：
     * 那些报的是「必配的东西缺了」，这一组报的是「可选的能力在不在」——
     * 缺了<b>不报错、只降级</b>，但降级必须看得见（现状是缺了什么都不说，
     * 直到用的时候静默失效），前端与操作者据此知道「这个渠道为什么没用」。
     *
     * <p><b>只报这里该报的，不重复别处的</b>：HEVC 解码是浏览器能力，在前端探
     * （{@code songplayer.js} 的 {@code probeHevc}，播页横幅引导）；网易云 cookie
     * 配没配在配置页的密钥卡（{@code SettingsCatalog#secretStatus}）；
     * e-hentai 的 cookie 自 2026-09-25 起是配置页上的普通设置项（同一处，不再是密钥卡）。
     */
    private List<CheckItem> checkCapabilities() {
        List<CheckItem> items = new ArrayList<>();
        // python / curl 都只服务歌曲模块的网易云链路，模块不在时连探测都不必做
        if (inBuild("song")) {
            items.add(checkPython());
            items.add(checkCurl());
        }
        // 本机 AI 端点三处共用：漫画相似度兜底 / AI 填词 / 韵脚词性 —— 有任一消费方才探
        if (inBuild("manga") || inBuild("song")) {
            items.add(checkLocalAi());
        }
        return items;
    }

    /**
     * Python（本机 ncm 解密用）。
     * <p>ncm 解密保持外部脚本现状（2026-09-24 裁决，《桌面化收尾（2026-09-25 实施）》4.2），
     * 所以 python 是那条路的外部依赖，缺了不能静默 —— 4.2 之前是什么都不报，
     * 直到网易云下到 {@code .ncm} 时解密失败。
     */
    private CheckItem checkPython() {
        String command = songProperties.getPythonCommand();
        if (command == null || command.isBlank()) {
            return CheckItem.warn("Python（ncm 解密）",
                    "没配（nya-entworks.song.python-command 是空的）",
                    "网易云下到 .ncm 加密文件时无法自动解密，该渠道会跳过这类文件；其余源不受影响");
        }
        return toolProbe.canRun(command)
                ? CheckItem.ok("Python（ncm 解密）", command + " 可用")
                : CheckItem.warn("Python（ncm 解密）", command + " 起不来",
                "网易云下到 .ncm 加密文件时无法自动解密（该渠道跳过这类文件）；"
                        + "装上 Python 后重启后端生效");
    }

    /**
     * curl（网易云搜索与 eapi 兜底都靠它：JSSE 被网易云 405 拦）。
     * <p>探测结果同时是 {@code SongResourceSearchService} 里 curl 开关的初值
     * （4.3 方案 b：缺 curl 就整源跳过，不抛异常）—— 同一处探、同一处报。
     */
    private CheckItem checkCurl() {
        return toolProbe.canRun("curl")
                ? CheckItem.ok("curl", "PATH 里有可用的 curl")
                : CheckItem.warn("curl", "起不来（PATH 里没有？）",
                "网易云搜索与 eapi 兜底会整段跳过该源（其余源不受影响）；装上 curl 后重启后端生效");
    }

    /**
     * 本机 AI 端点（OpenAI 兼容，三处 AI 功能共用）。
     * <p>三种状态分开说：没配（= 合法状态，前端已把 AI 按钮收起）、
     * 配了且可达、<b>配了但连不上</b>（最容易发生的一种 —— Ollama 没起）。
     * 最后一档只给 WARN 不给 ERROR：端点随时可以起，起来了就恢复，
     * 挂个常驻红点只会让人无视红点本身。
     */
    private CheckItem checkLocalAi() {
        if (!localAi.isConfigured()) {
            return CheckItem.ok("本机 AI 端点",
                    "没配 —— 相关的 AI 按钮已收起，其余功能不受影响");
        }
        String where = localAi.getBaseUrl() + "（模型 " + localAi.getModel() + "）";
        return endpointReachable(localAi.getBaseUrl())
                ? CheckItem.ok("本机 AI 端点", where + " 可达")
                : CheckItem.warn("本机 AI 端点", where + " 连不上",
                "端点起了没有、地址对不对？连不上时 AI 填词 / 韵脚词性 / 相似度兜底会在使用时失败");
    }

    /** 端点可达性：GET {@code <base>/models}（OpenAI 兼容端点都有）。protected 是为了测试里替换掉真实网络探测 */
    protected boolean endpointReachable(String baseUrl) {
        String url = baseUrl.endsWith("/") ? baseUrl + "models" : baseUrl + "/models";
        try {
            HttpResponse<Void> resp = httpClient.send(HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(3))
                            .GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            // 任何 HTTP 应答（含 404/401）都说明对面是个活着的服务；5xx 也算进程在，但保守起见不算可达
            return resp.statusCode() < 500;
        } catch (Exception e) {
            return false;
        }
    }
}
