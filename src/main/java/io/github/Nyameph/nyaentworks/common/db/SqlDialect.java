package io.github.Nyameph.nyaentworks.common.db;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * SQL 方言片段：<b>同一份代码要在 MySQL（开发机）与 SQLite（打包发版）下都跑对</b>。
 *
 * <p>为什么需要它：发版那条线把库换成 SQLite（《桌面化收尾实施计划》阶段 6），
 * 但开发机仍然是 MySQL，两边共用同一份业务代码。分开写两套 DAO 显然不行，
 * 所以把「只有方言不同」的那几个片段收在这里 —— 调用方拿到的是<b>一整段能拼进
 * MyBatis-Plus {@code apply}/{@code last} 的 SQL 文本</b>。
 *
 * <h2>为什么是静态的</h2>
 * 库方言是<b>整个进程唯一</b>的属性（一个 DataSource，启动时探测一次就定死），
 * 不是某个 Bean 的私有状态。做成构造注入的话，{@code CorpusService} / {@code RhymeService} /
 * {@code EnvCheckService} 等七八个类都要多一个构造参数，而它们<b>全都是被单测直接
 * {@code new} 出来的</b> —— 一处注入、满地改构造，收益却只有「形式上的 DI」。
 * 探测由 {@link SqlDialectDetector} 在启动时调一次 {@link #detect}，此后只读。
 *
 * <p><b>默认 MySQL</b>：没探测过（纯单测、工具类里直接调）时行为与引入本类之前
 * 逐字一致。这条是刻意的 —— 单测不该因为「没人给它探测方言」而变形。
 *
 * <p><b>探测失败也不抛</b>：探测走的是 {@code DataSource.getConnection()}，
 * 那是个可能抛的调用，而它不该把应用启动整个拽下来（连不上库这件事另有
 * {@code EnvCheckService} 那条红点报）。失败就保持 MySQL，后果是发出去的包会报 SQL 语法错 ——
 * 响亮地坏，不是静默地坏。
 */
public final class SqlDialect {

    /** 两个受支持的方言。值同时是 {@code DatabaseIdProvider} 下发给 MyBatis 的那个串（小写）。 */
    public enum Kind {
        MYSQL("mysql"),
        SQLITE("sqlite");

        private final String databaseId;

        Kind(String databaseId) {
            this.databaseId = databaseId;
        }

        /** MyBatis {@code databaseId}：mapper XML 里 {@code databaseId="sqlite"} 认的就是它 */
        public String databaseId() {
            return databaseId;
        }

        /** 报给人看、也拼进自检文案里的名字 */
        public String displayName() {
            return this == SQLITE ? "SQLite" : "MySQL";
        }
    }

    private static volatile Kind kind = Kind.MYSQL;

    private SqlDialect() {
    }

    public static Kind kind() {
        return kind;
    }

    public static boolean isSqlite() {
        return kind == Kind.SQLITE;
    }

    /** 报给用户看的库名（自检里那几条文案用）。 */
    public static String displayName() {
        return kind.displayName();
    }

    /**
     * 按 {@code DatabaseMetaData.getDatabaseProductName()} 判定方言。
     * 探测不到 / 抛异常时<b>保持原样（MySQL）</b>，见类注释。
     *
     * @return 探测出来的方言
     */
    public static Kind detect(DataSource dataSource) {
        if (dataSource == null) {
            return kind;
        }
        try (Connection conn = dataSource.getConnection()) {
            String product = conn.getMetaData().getDatabaseProductName();
            kind = product != null && product.toLowerCase().contains("sqlite") ? Kind.SQLITE : Kind.MYSQL;
        } catch (Exception ignored) {
            // 连不上库不是这里要报的事（EnvCheckService 有专门一条）。保持默认，见类注释。
        }
        return kind;
    }

    /** 测试用：显式指定方言。传 null 等于回到默认（MySQL）。 */
    public static void override(Kind k) {
        kind = k == null ? Kind.MYSQL : k;
    }

    // ==================== 片段 ====================

    /**
     * 随机数表达式，供 {@code ORDER BY} 用（每次求值都不同，与行无关）。
     * <p>SQLite 的 {@code RANDOM()} 是 64 位整数，**不能直接当 {@code RAND()} 使** ——
     * 它与整数相除走的是<b>整数除法</b>（{@code random()/score} 在 |random| &lt; score 时恒为 0，
     * 大量行并列，加权就废了）。所以这里规约到 {@code [0,1)} 的实数，与 MySQL 的 {@code RAND()} 同值域。
     */
    public static String random() {
        return isSqlite() ? "(abs(random()) / 9223372036854775808.0)" : "RAND()";
    }

    /**
     * 字符数（不是字节数）。{@code LENGTH(text) > 1} 这类判据在两边含义必须一致 ——
     * MySQL 的 {@code LENGTH} 是<b>字节</b>、{@code CHAR_LENGTH} 才是字符，SQLite 的
     * {@code length()} 对 TEXT 就是字符。统一取字符数。
     */
    public static String charLength(String column) {
        return isSqlite() ? "length(" + column + ")" : "CHAR_LENGTH(" + column + ")";
    }

    /**
     * 「某列这个逗号分隔的表里有 {0} 吗」的<b>整个谓词</b>（含 {@code > 0}），
     * 调用方只给它列名，值仍走 {@code apply} 的 {@code {0}} 占位：
     * <pre>w.or().apply(SqlDialect.findInSet("yun18"), v)</pre>
     *
     * <p>SQLite 没有 {@code FIND_IN_SET}。等价写法是把两边都用逗号包起来再 {@code instr} ——
     * 直接 {@code instr(col, ?)} 会误判子串（找 {@code 中东} 时命中 {@code 中东东}），
     * 两边各垫一个逗号就把「元素」的边界钉死了。
     * <p>NULL 语义两边也一致：都是 {@code NULL > 0} → NULL → 不算命中。
     */
    public static String findInSet(String column) {
        return isSqlite()
                ? "instr(',' || " + column + " || ',', ',' || {0} || ',') > 0"
                : "FIND_IN_SET({0}, " + column + ") > 0";
    }

    /**
     * 「某列包含 {0} 这个片段吗」的谓词，值由调用方拼成 {@code %…%}（先过
     * {@code MangaTextUtil.escapeLike}，再补两头）。
     *
     * <p><b>为什么不像原来那样写 {@code CONCAT('%', {0}, '%')}</b>：SQLite 没有 {@code CONCAT}
     * （只有 SQL Server 风格的 {@code ||}），而把通配符挪到 Java 侧拼接是两库通用的。
     *
     * <p><b>SQLite 这半必须带 {@code ESCAPE}</b>：MySQL 的 {@code LIKE} 默认拿反斜杠当转义符，
     * SQLite <b>没有默认转义符</b> —— 同一份 {@code escapeLike} 转出来的 {@code \%} 在那边
     * 会退化成「一个反斜杠 + 一个通配符」，搜索含 {@code %} / {@code _} 的词就静默匹配错。
     */
    public static String likeContains(String column) {
        return isSqlite() ? column + " LIKE {0} ESCAPE '\\'" : column + " LIKE {0}";
    }

    /**
     * 分页片段（含 {@code LIMIT} 关键字，直接交给 {@code last()}）。
     * MySQL 认 {@code LIMIT off, size}，SQLite 只认 {@code LIMIT size OFFSET off}。
     */
    public static String limit(long offset, long size) {
        return isSqlite() ? "LIMIT " + size + " OFFSET " + offset : "LIMIT " + offset + "," + size;
    }

    /**
     * 「{@code ?} 是库里的表吗」的计数查询。
     */
    public static String tableExistsSql() {
        return isSqlite()
                ? "select count(*) from sqlite_master where type = 'table' and name = ?"
                : "select count(*) from information_schema.tables "
                + "where table_schema = database() and table_name = ?";
    }

    /**
     * 「{@code ?} 是某表里的列吗」的计数查询。<b>表名内联、列名走参数</b>：
     * SQLite 的 {@code pragma_table_info()} 是个表值函数，表名当参数传要额外的括法，
     * 而这里的表名<b>全是代码里写死的常量</b>（{@code EnvCheckService} 那张清单），
     * 没有注入面。
     */
    public static String columnExistsSql(String table) {
        return isSqlite()
                ? "select count(*) from pragma_table_info('" + table + "') where name = ?"
                : "select count(*) from information_schema.columns "
                + "where table_schema = database() and table_name = '" + table + "' and column_name = ?";
    }
}
