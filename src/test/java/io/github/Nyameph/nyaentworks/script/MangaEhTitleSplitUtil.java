package io.github.Nyameph.nyaentworks.script;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import io.github.Nyameph.nyaentworks.common.config.LocalAiProperties;
import io.github.Nyameph.nyaentworks.manga.config.MangaProperties;
import io.github.Nyameph.nyaentworks.manga.consts.MangaDictMatchMode;
import io.github.Nyameph.nyaentworks.manga.consts.MangaDictType;
import io.github.Nyameph.nyaentworks.manga.entity.MangaData;
import io.github.Nyameph.nyaentworks.manga.entity.MangaDictEntry;
import io.github.Nyameph.nyaentworks.manga.service.MangaDictService;
import io.github.Nyameph.nyaentworks.manga.util.MangaEhLocalDb;
import io.github.Nyameph.nyaentworks.manga.util.MangaNameParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 eh-gallery.db 的完整标题（{@code title} / {@code title_jpn}）拆成结构化列，
 * 并按 title↔title_jpn 的括号结构对齐提取「英文↔日文」翻译对，经本地 AI（Ollama）
 * 判断合理性后存入翻译字典表 —— 可执行脚本，非单元测试。
 *
 * <p><b>为什么拆</b>：库里 {@code title} 是完整画廊标题 {@code (展会) [社团 (作者)] 标题 (原作)}，
 * 而 {@code manga_data.title} 存的是纯标题，归一后精确相等几乎不可能（LOCAL_TITLE_EXACT 仅几十条）；
 * 库里 {@code artist}/{@code group_name}/{@code parody} 是英文罗马字，用户存的是日文，元数据匹配也失效。
 * 日文的社团/作者/原作其实藏在 {@code title_jpn} 里，拆出来并建成「英文↔日文」字典，
 * 才能让 {@code MangaEhLocalDb} 的本地优先匹配真正命中。
 *
 * <p><b>三个阶段，各自带 justTest，按顺序跑</b>：
 * <ol>
 *   <li>{@link #phase1Schema()} —— 给 gallery 加结构化列、建翻译字典表（DDL）。</li>
 *   <li>{@link #phase2Split()} —— 逐条拆 title/title_jpn 写回新列，聚合翻译对写入字典。</li>
 *   <li>{@link #phase3AiJudge()} —— 对高频（count≥2）翻译对批量调 AI 判断合理性，写回 ai_ok。</li>
 * </ol>
 *
 * <p><b>不是一次性脚本</b>：它生产的是**运行时依赖** —— {@code MangaEhLocalDb} 启动时读
 * {@code translate_dict}（{@code ai_ok=1}）与 gallery 的 {@code title_clean} /
 * {@code title_jpn_clean} / {@code artist} / {@code group_name} / {@code parody} 等列，
 * 缺了这些列本地优先匹配就退化。换了 eh-gallery.db、或词典里的日文名明显没命中时，
 * 按 phase1 → phase2 → phase3 重跑一遍。三个阶段都是幂等的（唯一键 + {@code IF NOT EXISTS}）。
 *
 * <p><b>用法</b>：改对应方法里的 {@code justTest}/{@code limit} 后跑
 * {@code ./mvnw test -Dtest=MangaEhTitleSplitUtil#phase2Split}。
 * 默认 {@code justTest=true} 只打印不落库；每阶段先干跑、读完输出确认无误，再置 {@code false} 真实执行。
 * 只依赖本机 SQLite 文件（eh-gallery.db）与 MySQL 词典（构造解析器用），不需要 eh 网络。
 */
@SpringBootTest
public class MangaEhTitleSplitUtil {

    /** 字段取值：artist / group / parody / exhibit / magazine，与 translate_dict.field 对齐 */
    private static final String F_ARTIST = "artist";
    private static final String F_GROUP = "group";
    private static final String F_PARODY = "parody";
    private static final String F_EXHIBIT = "exhibit";
    private static final String F_MAGAZINE = "magazine";

    /** 合法 field 值集合，供 AI 判定 field 修正时校验（不含 "invalid"） */
    private static final Set<String> VALID_FIELDS = Set.of(
            F_ARTIST, F_GROUP, F_PARODY, F_EXHIBIT, F_MAGAZINE);

    /** 非五类的「无效」名称（题材/元数据/公司名等），词典侧对应 IGNORE_CONTENT */
    private static final String F_INVALID = "invalid";

    /** 建表 SQL：翻译字典表。唯一键 (field, en_key, ja_key)，重跑靠它幂等 */
    private static final String CREATE_DICT_SQL = "CREATE TABLE IF NOT EXISTS translate_dict ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "field TEXT NOT NULL,"
            + "en TEXT NOT NULL,"
            + "ja TEXT NOT NULL,"
            + "en_key TEXT NOT NULL,"
            + "ja_key TEXT NOT NULL,"
            + "count INTEGER NOT NULL,"
            + "ai_ok INTEGER,"
            + "ai_reason TEXT,"
            + "UNIQUE(field, en_key, ja_key)"
            + ")";

    @Autowired
    private MangaProperties props;

    /** 本机 AI 端点（{@code nya-entworks.common.local-ai.*}），与 AI 填词、韵脚词性同一份 */
    @Autowired
    private LocalAiProperties localAi;

    @Autowired
    private MangaDictService dictService;

    // ------------------------------------------------------------------
    // 阶段一：建列 + 建翻译字典表
    // ------------------------------------------------------------------

    /**
     * 给 gallery 加结构化列、建 translate_dict 表。
     * <p>幂等：已存在的列跳过；表用 IF NOT EXISTS。justTest 只打印 DDL。
     */
    @Test
    public void phase1Schema() throws Exception {
        boolean justTest = true;   // true = 只打印；false = 真实执行 DDL

        String[] newCols = {
                "title_clean", "title_jpn_clean",
                "artist_jpn", "group_jpn", "parody_jpn",
                "exhibit", "exhibit_jpn", "magazine", "magazine_jpn"
        };

        String dbPath = requireDbPath();
        try (Connection conn = open(dbPath)) {
            Set<String> existing = existingColumns(conn, "gallery");
            List<String> toAdd = new ArrayList<>();
            for (String c : newCols) {
                if (!existing.contains(c)) {
                    toAdd.add(c);
                }
            }

            System.out.println("==============================================");
            System.out.println((justTest ? "【干跑】" : "【真实】") + "gallery 已有列 " + existing.size()
                    + " 个，待加列 " + toAdd.size() + " 个：" + toAdd);
            System.out.println("翻译字典表：translate_dict" + (existing.contains("translate_dict")
                    || tableExists(conn, "translate_dict") ? "（已存在，IF NOT EXISTS 跳过）" : ""));

            if (!justTest) {
                try (Statement st = conn.createStatement()) {
                    for (String c : toAdd) {
                        st.executeUpdate("ALTER TABLE gallery ADD COLUMN " + c + " TEXT");
                        System.out.println("  已加列 " + c);
                    }
                    st.executeUpdate(CREATE_DICT_SQL);
                }
            } else {
                System.out.println("未执行 DDL。确认列清单无误后置 justTest=false 重跑");
            }
        }
    }

    // ------------------------------------------------------------------
    // 阶段二：拆分 + 写回新列 + 聚合翻译对
    // ------------------------------------------------------------------

    /**
     * 逐条拆 {@code title}/{@code title_jpn}，写回 gallery 新列，并把 title↔title_jpn
     * 的「英文↔日文」翻译对聚合后写入 translate_dict（重建、ai_ok 置 NULL）。
     *
     * <p>拆分复用 {@link MangaNameParser#parse(String)}：它内部走 BracketParser 按括号位置
     * 判定规则，提取展会/社团/作者/标题/原作/杂志。标题侧拆英文字段，日文侧拆日文字段，
     * 两者按字段对齐（group/artist/parody/exhibit/magazine 各成一对），这就是「按首尾括号
     * 结构比对」——两侧字段都来自同一套括号位置判定，天然对应。
     */
    @Test
    public void phase2Split() throws Exception {
        boolean justTest = true;   // true = 只读不写（可配 limit 缩小）；false = 真实写回
        int limit = 0;           // 0 = 全量；>0 = 只处理前 N 条（自测）

        String dbPath = requireDbPath();
        MangaNameParser parser = new MangaNameParser(dictService.current());

        Map<String, Pair> pairs = new LinkedHashMap<>();
        List<String[]> samples = new ArrayList<>();   // 前 20 条拆分样例
        long processed = 0;
        long withJpn = 0;

        try (Connection conn = open(dbPath)) {
            conn.setAutoCommit(false);
            long maxRowid = maxRowid(conn);

            String updSql = "UPDATE gallery SET title_clean=?, title_jpn_clean=?, artist_jpn=?,"
                    + " group_jpn=?, parody_jpn=?, exhibit=?, exhibit_jpn=?, magazine=?, magazine_jpn=?"
                    + " WHERE rowid=?";
            try (PreparedStatement upd = justTest ? null : conn.prepareStatement(updSql)) {
                for (long from = 1; from <= maxRowid; from += 5000) {
                    long to = Math.min(from + 4999, maxRowid);
                    for (RowRec r : readBatch(conn, from, to)) {
                        processed++;
                        // 先剥掉标题末尾的扫图/语言/格式元数据再拆，否则这些标签会混进 title_clean
                        // 或把英文独有的扫图组、日文「英訳」类标注当成实体
                        String enTitle = stripEhMetadata(r.title);
                        boolean hasJpn = notBlank(r.titleJpn);
                        String jaTitle = hasJpn ? stripEhMetadata(r.titleJpn) : null;
                        MangaData en = parser.parse(enTitle);
                        MangaData ja = jaTitle == null ? null : parser.parse(jaTitle);
                        if (hasJpn) {
                            withJpn++;
                        }

                        String titleClean = en.getTitle();
                        String titleJpnClean = ja == null ? null : ja.getTitle();
                        String artistJpn = ja == null ? null : clean(ja.getArtist());
                        String groupJpn = ja == null ? null : clean(ja.getGroupName());
                        String parodyJpn = ja == null ? null : clean(ja.getParody());
                        String exhibit = clean(en.getExhibit());
                        String exhibitJpn = ja == null ? null : clean(ja.getExhibit());
                        String magazine = clean(en.getMagazine());
                        String magazineJpn = ja == null ? null : clean(ja.getMagazine());

                        if (hasJpn) {
                            collectPairs(pairs, F_GROUP, en.getGroupName(), ja.getGroupName());
                            collectPairs(pairs, F_ARTIST, en.getArtist(), ja.getArtist());
                            collectPairs(pairs, F_PARODY, en.getParody(), ja.getParody());
                            collectPairs(pairs, F_EXHIBIT, en.getExhibit(), ja.getExhibit());
                            collectPairs(pairs, F_MAGAZINE, en.getMagazine(), ja.getMagazine());
                        }

                        if (samples.size() < 20) {
                            samples.add(new String[]{r.title, r.titleJpn,
                                    titleClean, titleJpnClean, artistJpn, groupJpn, parodyJpn});
                        }

                        if (!justTest) {
                            setOrNull(upd, 1, titleClean);
                            setOrNull(upd, 2, titleJpnClean);
                            setOrNull(upd, 3, artistJpn);
                            setOrNull(upd, 4, groupJpn);
                            setOrNull(upd, 5, parodyJpn);
                            setOrNull(upd, 6, exhibit);
                            setOrNull(upd, 7, exhibitJpn);
                            setOrNull(upd, 8, magazine);
                            setOrNull(upd, 9, magazineJpn);
                            upd.setLong(10, r.rowid);
                            upd.addBatch();
                        }
                        if (limit > 0 && processed >= limit) {
                            break;
                        }
                    }
                    if (!justTest) {
                        upd.executeBatch();
                        conn.commit();
                    }
                    System.out.println("已处理 " + processed + " / 最大 rowid " + maxRowid);
                    if (limit > 0 && processed >= limit) {
                        break;
                    }
                }
            }
        }

        System.out.println("==============================================");
        System.out.println((justTest ? "【干跑】" : "【真实】") + "拆分 " + processed + " 条（title_jpn 非空 "
                + withJpn + " 条），去重后翻译对 " + pairs.size() + " 条");
        System.out.println();
        for (String[] s : samples) {
            System.out.println("  标题    : " + s[0]);
            System.out.println("  日文标题: " + s[1]);
            System.out.println("    → 纯标题=" + s[2] + " | 纯日文标题=" + s[3]
                    + " | 作者(日)=" + s[4] + " | 社团(日)=" + s[5] + " | 原作(日)=" + s[6]);
            System.out.println();
        }

        if (justTest) {
            System.out.println("未写库。样例确认无误后置 justTest=false 重跑");
            return;
        }

        // 写翻译字典前，先读回上一次已判过的结果（ai_ok IS NOT NULL），
        // 按 (field, en_key, ja_key) 沿用 —— 只有 en/ja 真的变了（或被 strip/过滤改掉）的才重判，
        // 避免每次重跑 phase2 都丢掉已经跑完一天的 AI 判断。
        Map<String, String[]> prior = new HashMap<>();   // field\u0000en_key\u0000ja_key -> [ai_ok, ai_reason]
        try (Connection conn = open(dbPath)) {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT field,en_key,ja_key,ai_ok,ai_reason"
                         + " FROM translate_dict WHERE ai_ok IS NOT NULL")) {
                while (rs.next()) {
                    prior.put(rs.getString("field") + "\u0000" + rs.getString("en_key")
                                    + "\u0000" + rs.getString("ja_key"),
                            new String[]{String.valueOf(rs.getInt("ai_ok")), rs.getString("ai_reason")});
                }
            }
        }

        // 写翻译字典（重建；已判过的对沿用旧 ai_ok/ai_reason，其余置 NULL 交给阶段三判断）
        try (Connection conn = open(dbPath)) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.executeUpdate(CREATE_DICT_SQL);
                st.executeUpdate("DELETE FROM translate_dict");
            }
            String insSql = "INSERT INTO translate_dict(field,en,ja,en_key,ja_key,count,ai_ok,ai_reason)"
                    + " VALUES(?,?,?,?,?,?,?,?)";
            try (PreparedStatement ins = conn.prepareStatement(insSql)) {
                int reused = 0;
                for (Pair p : pairs.values()) {
                    ins.setString(1, p.field);
                    ins.setString(2, p.en);
                    ins.setString(3, p.ja);
                    ins.setString(4, p.enKey);
                    ins.setString(5, p.jaKey);
                    ins.setInt(6, p.count);
                    String[] old = prior.get(p.field + "\u0000" + p.enKey + "\u0000" + p.jaKey);
                    if (old != null) {
                        ins.setInt(7, "1".equals(old[0]) ? 1 : 0);
                        ins.setString(8, old[1]);
                        reused++;
                    } else {
                        ins.setNull(7, java.sql.Types.INTEGER);
                        ins.setNull(8, java.sql.Types.VARCHAR);
                    }
                    ins.addBatch();
                }
                ins.executeBatch();
                System.out.println("沿用上次已判结果 " + reused + " / " + pairs.size() + " 条");
            }
            conn.commit();
        }
        System.out.println("翻译字典已写入 " + pairs.size() + " 条。下一步：phase3AiJudge 判断高频对");
    }

    // ------------------------------------------------------------------
    // 阶段三：AI 批量判断高频翻译对
    // ------------------------------------------------------------------

    /**
     * 对 translate_dict 里 {@code count >= 2} 且 {@code ai_ok IS NULL} 的记录批量调本地 AI
     * 判断「英文↔日文」是否为同一实体的合理翻译，写回 {@code ai_ok}/{@code ai_reason}。
     * <p>低频（count=1）保持 NULL（待判断）；本阶段天然断点续跑——只处理 ai_ok IS NULL 的行。
     * <p>想只重判被 AI 误杀的（{@code ai_ok=0}）而保留已判合理的（{@code ai_ok=1}），置
     * {@code rejudgeRejected=true}（不要用 {@code reset=true}，那会把 6 万多条 ai_ok=1 也一起重判）。
     */
    @Test
    public void phase3AiJudge() throws Exception {
        boolean justTest = true;   // true = 只打印待判对数；false = 真实调 AI 并写回
        int limit = 0;           // 0 = 全部；>0 = 只处理前 N 条（自测）
        int batchSize = 30;      // 每批塞给 AI 的对数
        boolean reset = false;           // true = 先把所有 ai_ok/ai_reason 置 NULL（全量重判）
        boolean rejudgeRejected = true; // true = 只把 ai_ok=0 的置 NULL（重判被误杀的，保留 ai_ok=1）

        String dbPath = requireDbPath();
        MangaProperties.EhScan c = props.getEhScan();

        if (reset) {
            try (Connection conn = open(dbPath); Statement st = conn.createStatement()) {
                int n = st.executeUpdate("UPDATE translate_dict SET ai_ok=NULL, ai_reason=NULL");
                System.out.println("已重置 " + n + " 条 AI 判定（ai_ok/ai_reason 置 NULL），将全部重判");
            }
        } else if (rejudgeRejected) {
            try (Connection conn = open(dbPath); Statement st = conn.createStatement()) {
                int n = st.executeUpdate(
                        "UPDATE translate_dict SET ai_ok=NULL, ai_reason=NULL WHERE ai_ok=0");
                System.out.println("已重置 " + n + " 条被拒（ai_ok=0）判定，仅重判这些；ai_ok=1 的保留");
            }
        }

        List<DictRow> rows;
        try (Connection conn = open(dbPath)) {
            rows = readPending(conn);
        }
        System.out.println("==============================================");
        System.out.println((justTest ? "【干跑】" : "【真实】") + "待判高频翻译对 " + rows.size() + " 条");
        if (justTest) {
            rows.stream().limit(20).forEach(r -> System.out.println("  " + r.field
                    + " | " + r.en + " | " + r.ja));
            System.out.println("未调 AI。确认无误后置 justTest=false 重跑");
            return;
        }

        int judged = 0;
        int okCount = 0;
        try (Connection conn = open(dbPath)) {
            conn.setAutoCommit(false);
            String updSql = "UPDATE translate_dict SET ai_ok=?, ai_reason=?, field=? WHERE id=?";
            try (PreparedStatement upd = conn.prepareStatement(updSql)) {
                for (int i = 0; i < rows.size(); i += batchSize) {
                    List<DictRow> batch = rows.subList(i, Math.min(i + batchSize, rows.size()));
                    List<Judge> judges = callOllama(c, localAi, batch);
                    for (Judge j : judges) {
                        String ek = key(j.en);
                        String jk = key(j.ja);
                        DictRow matched = null;
                        for (DictRow r : batch) {
                            if (r.enKey.equals(ek) && r.jaKey.equals(jk)) {
                                matched = r;
                                break;
                            }
                        }
                        if (matched == null) {
                            continue;   // AI 返回了本批之外的对，忽略
                        }
                        String suggested = j.field == null ? null : j.field.trim();
                        boolean invalid = "invalid".equals(suggested);
                        String newField = (!invalid && VALID_FIELDS.contains(suggested))
                                ? suggested : matched.field;
                        upd.setInt(1, invalid ? 0 : (j.ok ? 1 : 0));
                        upd.setString(2, invalid ? ("不属于五类：" + j.reason) : j.reason);
                        upd.setString(3, newField);
                        upd.setLong(4, matched.id);
                        upd.addBatch();
                        judged++;
                        if (j.ok) {
                            okCount++;
                        }
                    }
                    upd.executeBatch();
                    conn.commit();
                    System.out.println("已判断 " + Math.min(i + batchSize, rows.size()) + " / " + rows.size()
                            + "，其中合理 " + okCount);
                    if (limit > 0 && i + batchSize >= limit) {
                        break;
                    }
                }
            }
        }
        System.out.println("==============================================");
        System.out.println("AI 判断完成：写回 " + judged + " 条，判定合理 " + okCount + " 条");
    }

    // ------------------------------------------------------------------
    // 阶段四：AI 判定合理的 parody/exhibit/magazine 导出为词典项
    // ------------------------------------------------------------------

    /**
     * 把 AI 判定合理（{@code ai_ok=1}）的 invalid/parody/exhibit/magazine 翻译对的<b>英文、日文两侧</b>
     * 导出为 MySQL 词典项（manga_dict_entry），让 {@code MangaNameParser} 后续解析目录名时能识别
     * 原作/展会/杂志/需忽略内容。
     *
     * <p>artist/group 不走词典识别（靠 {@code [社团 (作者)]} 括号位置），所以不导出。匹配策略按类型三档：
     * <ul>
     *   <li>invalid→{@link MangaDictType#IGNORE_CONTENT}、parody→{@link MangaDictType#PARODY}，
     *       都是具体名字，统一 {@link MangaDictMatchMode#EXACT}；</li>
     *   <li>exhibit→{@link MangaDictType#EXHIBIT}，带期号（含数字）的名字把数字改 {@code \d+}
     *       成 {@link MangaDictMatchMode#REGEX}，其余 EXACT；</li>
     *   <li>magazine→{@link MangaDictType#MAGAZINE}，带期号（Vol./No./# 等标记 + 数字）的名字取数字前的
     *       固定前缀作 {@link MangaDictMatchMode#PREFIX}，其余 EXACT。</li>
     * </ul>
     * 全部忽略大小写；词条按「值」去重（不论分类）——同一展会/杂志的多个期号收敛成同一条前缀/正则，
     * 且与库中已存在的任何词条值重复时跳过，幂等可重跑。
     */
    @Test
    public void phase4DictExport() throws Exception {
        boolean justTest = true;   // true = 只打印；false = 真实写 MySQL
        int limit = 0;           // 0 = 全部；>0 = 只导出前 N 条（自测）

        String dbPath = requireDbPath();
        List<MangaDictEntry> entries = new ArrayList<>();
        // 去重口径：词条值全局唯一（不论分类）——先把库中已有的全部词条值装进 set，本批重复或与库中重复都跳过
        Set<String> seen = new HashSet<>();
        for (MangaDictEntry existing : dictService.list(null, null)) {
            if (existing.getDictValue() != null) {
                seen.add(existing.getDictValue().trim().toLowerCase());
            }
        }
        int existingCount = seen.size();
        try (Connection conn = open(dbPath);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT field, en, ja FROM translate_dict"
                             + " WHERE ai_ok = 1 AND field IN ('invalid','parody','exhibit','magazine')"
                             + " ORDER BY field, count DESC")) {
            while (rs.next()) {
                String field = rs.getString("field");
                String en = rs.getString("en");
                String ja = rs.getString("ja");
                MangaDictType type = dictTypeOf(field);
                if (type == null) {
                    continue;
                }
                for (String side : new String[]{en, ja}) {
                    DictSpec spec = dictSpecOf(field, side);
                    if (spec == null) {
                        continue;
                    }
                    if (!seen.add(spec.value().toLowerCase())) {
                        continue;   // 值与库中已有词条或本批已导出词条重复（不论分类），跳过
                    }
                    MangaDictEntry e = new MangaDictEntry();
                    e.setDictType(type);
                    e.setDictValue(spec.value());
                    e.setMatchMode(spec.matchMode());
                    e.setIgnoreCase(true);
                    e.setRemark("eh翻译字典 AI判定: " + en + " ↔ " + ja);
                    entries.add(e);
                    if (limit > 0 && entries.size() >= limit) {
                        break;
                    }
                }
                if (limit > 0 && entries.size() >= limit) {
                    break;
                }
            }
        }

        System.out.println("==============================================");
        System.out.println("当前库中已有词条值 " + existingCount + " 个（去重基准）");
        System.out.println((justTest ? "【干跑】" : "【真实】") + "待导出词典项 " + entries.size() + " 条");
        if (justTest) {
            entries.stream().limit(50).forEach(e -> System.out.println("  " + e.getDictType()
                    + " | " + e.getMatchMode() + " | " + e.getDictValue() + "  ← " + e.getRemark()));
            System.out.println("未写库。确认无误后置 justTest=false 重跑");
            return;
        }
        int inserted = dictService.saveBatch(entries);
        System.out.println("已新增词典项 " + inserted + " 条（值与库中已有词条重复则跳过）");
    }

    /** 一条 en/ja 侧值的词条规格：匹配方式 + 词条值 */
    private record DictSpec(MangaDictMatchMode matchMode, String value) {
    }

    /**
     * 按 field 决定一条 en/ja 侧值的匹配方式与词条值，空值返回 {@code null}。
     * <p>invalid/parody 与不含数字的 exhibit/magazine → EXACT 原样；
     * exhibit 含数字 → 数字改 {@code \d+} 的 REGEX；
     * magazine 含「Vol./No./# 等标记 + 数字」期号 → 取期号前固定前缀的 PREFIX。
     */
    private static DictSpec dictSpecOf(String field, String side) {
        if (side == null) {
            return null;
        }
        String v = side.trim();
        if (v.isEmpty()) {
            return null;
        }
        return switch (field) {
            case F_PARODY, F_INVALID -> new DictSpec(MangaDictMatchMode.EXACT, v);
            case F_EXHIBIT -> containsDigit(v)
                    ? new DictSpec(MangaDictMatchMode.REGEX, v.replaceAll("\\d+", "\\\\d+"))
                    : new DictSpec(MangaDictMatchMode.EXACT, v);
            case F_MAGAZINE -> {
                Matcher m = MAG_ISSUE_START.matcher(v);
                if (m.find() && m.start() > 0) {
                    String prefix = v.substring(0, m.start()).trim();
                    if (!prefix.isEmpty()) {
                        yield new DictSpec(MangaDictMatchMode.PREFIX, prefix);
                    }
                }
                yield new DictSpec(MangaDictMatchMode.EXACT, v);
            }
            default -> null;
        };
    }

    /** 是否含半角数字（与 {@code \d} 同口径，避免全角数字「判定含数字却替换不掉」的不一致） */
    private static boolean containsDigit(String v) {
        for (int i = 0; i < v.length(); i++) {
            if (isDigitChar(v.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** magazine 期号标记起始：空白/行首后接 Vol./No./# 等标记（可省略）再紧跟数字。
     *  用于把「COMIC 高 Vol.4」「COMIC X-EROS #37」这类带期号的杂志名收敛成前缀。
     *  不匹配「COMIC1」这类数字紧贴字母的名字，也不匹配「ケモッ娘ラヴァーズ10」这类日文直连数字。 */
    private static final Pattern MAG_ISSUE_START = Pattern.compile(
            "(?:\\s|^)(?:[Vv][Oo][Ll]\\.?\\s*|[Nn][Oo]\\.?\\s*|[#＃]\\s*)?(?=\\d)");

    private static boolean isDigitChar(char c) {
        return c >= '0' && c <= '9';
    }

    private static MangaDictType dictTypeOf(String field) {
        return switch (field) {
            case F_PARODY -> MangaDictType.PARODY;
            case F_EXHIBIT -> MangaDictType.EXHIBIT;
            case F_MAGAZINE -> MangaDictType.MAGAZINE;
            case F_INVALID -> MangaDictType.IGNORE_CONTENT;
            default -> null;
        };
    }

    // ------------------------------------------------------------------
    // AI 调用（复用 AiSimilarityService 的 Ollama HTTP 模板）
    // ------------------------------------------------------------------

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    /** 批量判断翻译对，返回可解析出的判定列表；失败/未启用返回空列表 */
    private static List<Judge> callOllama(MangaProperties.EhScan c, LocalAiProperties ai,
                                          List<DictRow> batch) {
        if (!c.isAiEnabled() || blank(ai.getBaseUrl()) || blank(ai.getModel())) {
            return List.of();
        }
        try {
            StringBuilder lines = new StringBuilder();
            for (DictRow r : batch) {
                lines.append(r.field).append(" | ").append(r.en).append(" | ").append(r.ja).append('\n');
            }
            String system = """
                    你是漫画元数据翻译对判定助手。我会给你若干行「字段 | 英文 | 日文」，
                    字段取 artist（作者）/group（社团）/parody（原作）/exhibit（展会）/magazine（杂志）。

                    这些英文与日文来自同一部漫画的英文标题与日文标题的同一位置（括号结构已对齐），
                    因此它们大概率指向同一个实体。你的任务只是过滤掉少数因结构错位产生的
                    「驴唇不对马嘴」配对，口径要宽松、宁可不漏杀。

                    核心判断标准只有一条：把日文按假名读音读出来，听它像不像英文的发音。
                    罗马字本质就是日文读音的字母转写，所以「字形不同」是音译的常态，永远不是否决理由。

                    判 ok（同一实体）的情形，满足任一条即可：
                    - 音译：罗马字 ↔ 假名，发音大致对应。如 okamoto ↔ オカモト、Itachi ↔ イタチ、Drachef ↔ ドラチェフ。
                    - 汉字音读/训读：日文汉字按读音对应罗马字，别管汉字的字面意思。如 Kozukai Nisshi ↔ 小使日誌、
                      Takiura Kichi ↔ 滝浦基地、Kurotsuki ↔ 黒月、Ari ↔ 蟻、Chinbotsu Tower ↔ 沈没タワー。
                    - 音译不完美：长音「ー」可加可不加、促音「っ」、浊音、元音增减、个别音节不同都属正常，
                      不能因「少了/多了某个音」就判不同。如 Meifu Madou ↔ めーふまどー、Warudarake ↔ わるだらけっ、
                      Junji ↔ じゅんじぃ、Ryoku Shinpan ↔ 領空侵犯。
                    - 意译/谐音/官方译名：语义相同或近义就 ok。如 White Crow ↔ 白鸦工作室、
                      Strawberry and Tea ↔ いちごと紅茶、Best Student Council ↔ 極上生徒会、
                      Kid Icarus: Uprising ↔ 新・光神話 パルテナの鏡、Tokyo Ravens ↔ 東京レイヴンズ。
                    - 缩写/简称 ↔ 全称：如 SC38 ↔ サンクリ38、C ↔ C: The Money of Soul and Possibility Control。
                    - 日期/卷号写法差异：如 COMIC Kairakuten BEAST 2013-12 ↔ COMIC 快楽天 BEAST 2013年12月号、
                      ANGEL Club 2022-8 ↔ ANGEL 倶楽部 2022年8月号。
                    - 韩文/中文写法：同一名字的其它书写系统也算同一实体。如 Pokemon ↔ 포켓몬스터、Hongbai ↔ 红白。

                    以下情况【必须忽略、不能作为判 false 的理由】：
                    - 「字形不同/字形完全不同」—— 罗马字与假名/汉字字形本来就不可能相同。
                    - 「语义不同/词义差异大」—— 名字（作者/社团/原作）没有字面意思，只看读音。
                    - 「音译不完美/发音差异大」—— 个别音节、长音、促音、浊音差异都属正常音译。
                    - 「名字不同/名称不符/重复项」—— 同一实体换个写法不叫不同。
                    - 日文里有多个名字用「、」或「,」分隔，英文只对应其中一个，也算 ok。
                    - 日文里混入元数据标注（如 非エロ、英訳、Ch.3、Vol.49、语言标签、日文标题里的 ♡ 等），忽略后再比。

                    只有当把日文读出来跟英文发音完全对不上、且也不是它的意译/官方译名/简称时，才判 ok=false。
                    例如：Chiaki Tarou ↔ 月刊少年チャンピオン（人名 vs 杂志名）、
                    Kakumei Seifu Kouhoushitsu ↔ ラヂヲヘッド、Tutuplanetica ↔ トランスフォーマー、
                    korean ↔ モンスターハンター、various Artist ↔ Dogear。

                    除了判断 ok，你还要判断每一行的「字段」归类是否正确，并在 "field" 里给出修正后的字段：
                    - 选集/合同志的名称 → 归 magazine（杂志），不是 parody。例如日文含「アンソロジー」「アンソロ」「合同誌」、
                      英文含「Anthology」「Goudoushi」的，都是选集/合同志（对应 e-hentai 标签 other:anthology / other:goudoushi）。
                    - 单行本的名称 → 不属于五类，field 填 "invalid"。例如日文含「単行本」、英文含「Tankoubon」的
                      （对应 e-hentai 标签 other:tankoubon）。
                    - 人名（包括虚拟主播名、演员名）可以作为 parody（原作），不要因为是人名就排除。
                    - 例外：当选集/合同志/单行本的名称本身是常用词汇（题材词、元数据词，如「人妻」「痴漢」「総集編」「CG集」）
                      或与原作名重复时，不按上面三条改，field 保持原样（照抄输入的字段）。

                    "field" 合法取值只有 artist / group / parody / exhibit / magazine / invalid 六个；
                    字段没错就照抄输入的字段值。

                    只输出 JSON 数组，每项 {"en":"英文","ja":"日文","ok":true或false,"reason":"一句话","field":"字段"}，不要输出别的。
                    """;
            String user = lines.toString();

            JSONObject body = new JSONObject();
            body.put("model", ai.getModel());
            JSONArray msgs = new JSONArray();
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", system);
            JSONObject usr = new JSONObject();
            usr.put("role", "user");
            usr.put("content", user);
            msgs.add(sys);
            msgs.add(usr);
            body.put("messages", msgs);

            String url = ai.getBaseUrl();
            if (!url.endsWith("/chat/completions")) {
                url = url.endsWith("/") ? url + "chat/completions" : url + "/chat/completions";
            }
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json");
            if (!blank(ai.getApiKey())) {
                reqBuilder.header("Authorization", "Bearer " + ai.getApiKey());
            }
            HttpRequest req = reqBuilder.POST(HttpRequest.BodyPublishers.ofString(body.toJSONString()))
                    .build();
            HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return List.of();
            }
            JSONObject root = JSONObject.parseObject(resp.body());
            JSONArray choices = root.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                return List.of();
            }
            String content = choices.getJSONObject(0).getJSONObject("message").getString("content");
            content = content == null ? "" : content
                    .replaceAll("(?s)^```(?:json)?\\s*", "")
                    .replaceAll("(?s)\\s*```$", "")
                    .trim();
            JSONArray arr = JSON.parseArray(content);
            if (arr == null) {
                return List.of();
            }
            List<Judge> out = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String en = o.getString("en");
                String ja = o.getString("ja");
                if (blank(en) || blank(ja)) {
                    continue;
                }
                Boolean ok = o.getBoolean("ok");
                out.add(new Judge(en, ja, Boolean.TRUE.equals(ok), o.getString("reason"),
                        o.getString("field")));
            }
            return out;
        } catch (Exception e) {
            return List.of();   // 失败静默降级，不中断整批
        }
    }

    // ------------------------------------------------------------------
    // SQLite 读写辅助
    // ------------------------------------------------------------------

    private Connection open(String dbPath) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }

    private String requireDbPath() {
        String dbPath = props.getEhScan().getLocalDbPath();
        if (blank(dbPath)) {
            throw new IllegalStateException("eh-scan.localDbPath 未配置，无法定位 eh-gallery.db");
        }
        return dbPath;
    }

    private static Set<String> existingColumns(Connection conn, String table) throws SQLException {
        Set<String> cols = new HashSet<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                cols.add(rs.getString("name"));
            }
        }
        return cols;
    }

    private static boolean tableExists(Connection conn, String table) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name='" + table + "'")) {
            return rs.next();
        }
    }

    private static long maxRowid(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT MAX(rowid) FROM gallery")) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private record RowRec(long rowid, String title, String titleJpn) {
    }

    private static List<RowRec> readBatch(Connection conn, long from, long to) throws SQLException {
        List<RowRec> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT rowid, title, title_jpn FROM gallery WHERE rowid BETWEEN ? AND ?")) {
            ps.setLong(1, from);
            ps.setLong(2, to);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new RowRec(rs.getLong(1), rs.getString(2), rs.getString(3)));
                }
            }
        }
        return list;
    }

    private record DictRow(long id, String field, String en, String ja, String enKey, String jaKey) {
    }

    private static List<DictRow> readPending(Connection conn) throws SQLException {
        List<DictRow> list = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, field, en, ja, en_key, ja_key FROM translate_dict"
                             + " WHERE count >= 2 AND ai_ok IS NULL")) {
            while (rs.next()) {
                list.add(new DictRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6)));
            }
        }
        return list;
    }

    private static void setOrNull(PreparedStatement ps, int idx, String v) throws SQLException {
        if (v == null) {
            ps.setNull(idx, java.sql.Types.VARCHAR);
        } else {
            ps.setString(idx, v);
        }
    }

    // ------------------------------------------------------------------
    // 翻译对聚合
    // ------------------------------------------------------------------

    /**
     * 拆分出的字段值里混入的 e-hentai 元数据标签（不是实体，应丢弃）：
     * 语言标签（标题尾部括号里的 (Korean) 等）、占位符（various/unknown）、
     * 平台标签（pixiv/fanbox 等作者来源）。判断时转小写后精确比对。
     */
    private static final Set<String> SKIP_VALUES = Set.of(
            // 语言标签（e-hentai language 值）
            "korean", "english", "japanese", "chinese", "spanish", "french", "german",
            "italian", "dutch", "portuguese", "russian", "polish", "thai", "vietnamese",
            "indonesian", "malay", "tagalog", "filipino", "hindi", "bengali", "arabic",
            "turkish", "czech", "slovak", "romanian", "hungarian", "greek", "bulgarian",
            "croatian", "serbian", "swedish", "norwegian", "danish", "finnish",
            "icelandic", "hebrew", "persian", "ukrainian", "lithuanian", "latvian",
            "estonian", "slovenian", "albanian", "macedonian", "georgian", "armenian",
            "azerbaijani", "kazakh", "uzbek", "mongolian", "burmese", "khmer", "lao",
            "sinhala", "tamil", "telugu", "nepali", "urdu", "pashto",
            // 占位符
            "various", "various artists", "unknown", "random", "sample", "preview",
            "anonymous", "anon", "none", "null", "artist", "misc",
            // 平台标签（作者来源）
            "pixiv", "fanbox", "twitter", "e621", "patreon", "fantia", "skeb",
            "gumroad", "subscribestar", "ci-en", "cien");

    /** 值是否为垃圾（空、语言标签、占位符、平台标签，或全由后者经「/」组合的平台串）。 */
    private static boolean isSkipValue(String s) {
        if (blank(s)) {
            return true;
        }
        String t = s.trim().toLowerCase();
        if (SKIP_VALUES.contains(t)) {
            return true;
        }
        // 平台组合，如 "pixiv / fanbox"、"twitter/e621"：拆开后每段都是垃圾才跳过
        if (t.contains("/")) {
            for (String part : t.split("/")) {
                if (!SKIP_VALUES.contains(part.trim())) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /** 垃圾值归 null，供写回 gallery 新列用；非垃圾原样返回。 */
    private static String clean(String s) {
        return isSkipValue(s) ? null : s;
    }

    /**
     * 去掉 e-hentai 标题末尾的扫图/语言/格式等元数据方括号标签。
     *
     * <p>e-hentai 的标题末尾方括号（{@code [English]}、{@code [Digital]}、{@code [Team Envy]}、
     * {@code [英訳]}、{@code [中国翻訳]}…）都是元数据，不是实体——社团/作者在<b>头部</b>方括号、
     * 原作/杂志在<b>尾部小括号</b>。不去掉它们，{@code title_clean} 就不是纯标题，
     * 且英文标题里英文独有的扫图组/语言标签会被当成实体、日文标题里的「英訳」类标注也会
     * 污染标题与翻译对（英文标题多出 {@code [Team Envy]} 而日文标题没有对应段）。
     *
     * <p>只在前面确有正文时剥离（{@code (?<=\S)}），避免把整段都是方括号的裸标题
     * （如 {@code [社团名]}）误删；尾部小括号（原作/杂志）不受影响。
     */
    private static String stripEhMetadata(String title) {
        if (blank(title)) {
            return title;
        }
        String s = title.replace('［', '[').replace('］', ']')
                .replace('　', ' ');   // 全角方括号/空格归一半角，便于统一匹配
        return s.replaceAll("(?<=\\S)\\s*(?:\\[[^\\]\\[]*\\]\\s*)+$", "");
    }

    /** 一条「英文↔日文」翻译对，聚合多个画廊的印证次数 */
    private static final class Pair {
        final String field;
        final String en;
        final String ja;
        final String enKey;
        final String jaKey;
        int count;

        Pair(String field, String en, String ja, String enKey, String jaKey) {
            this.field = field;
            this.en = en;
            this.ja = ja;
            this.enKey = enKey;
            this.jaKey = jaKey;
            this.count = 0;
        }
    }

    private record Judge(String en, String ja, boolean ok, String reason, String field) {
    }

    /**
     * 把一组英文↔日文字段值拆成翻译对。多值按分隔符拆分后按序配对；
     * 数量不一致时整段配对（宁可少拆不错拆，交给 AI 判断）。
     */
    private static void collectPairs(Map<String, Pair> pairs, String field, String en, String ja) {
        List<String> enParts = splitMulti(en);
        List<String> jaParts = splitMulti(ja);
        if (enParts.size() == 1 && jaParts.size() == 1) {
            addPair(pairs, field, enParts.get(0), jaParts.get(0));
        } else if (enParts.size() == jaParts.size()) {
            for (int i = 0; i < enParts.size(); i++) {
                addPair(pairs, field, enParts.get(i), jaParts.get(i));
            }
        } else {
            addPair(pairs, field, trim(en), trim(ja));
        }
    }

    private static void addPair(Map<String, Pair> pairs, String field, String en, String ja) {
        String e = trim(en);
        String j = trim(ja);
        if (e == null || j == null || isSkipValue(e) || isSkipValue(j)) {
            return;
        }
        String ek = key(e);
        String jk = key(j);
        if (ek == null || jk == null || ek.equals(jk)) {
            return;   // 相同（如展会 C71 与 C71）不需要翻译
        }
        String mapKey = field + "\u0000" + ek + "\u0000" + jk;
        pairs.computeIfAbsent(mapKey, k -> new Pair(field, e, j, ek, jk)).count++;
    }

    private static List<String> splitMulti(String s) {
        if (blank(s)) {
            return List.of();
        }
        String[] parts = s.split("[、，,;；]");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out.isEmpty() ? List.of(s.trim()) : out;
    }

    /** 与库/索引同源的归一键（NFC + trim + 大写，只留字母数字含 CJK） */
    private static String key(String raw) {
        return MangaEhLocalDb.stripKey(raw);
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
