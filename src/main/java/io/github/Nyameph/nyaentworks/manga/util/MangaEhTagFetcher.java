package io.github.Nyameph.nyaentworks.manga.util;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 EhTagTranslation/Database 拉取 e-hentai 内容类标签并解析成结构化行。
 *
 * <p>纯静态、不连库、不依赖 Spring，方便单测。原先这套逻辑在一个一次性测试脚本里、
 * 产物是 CSV（那个脚本已删，2026-09-15）；现在直接内存返回，供
 * {@code MangaEhTagService} 入库，省掉 CSV 这个过路文件。
 *
 * <p>数据源是 EhTagTranslation 仓库里每个命名空间一个 {@code .md} 文件，格式为
 * YAML frontmatter + 一张四列表格（原始标签 / 名称 / 描述 / 外部链接）。表格里的
 * 「分组行」（原始标签列为空、名称列为 {@code == 分类 ==}）是 ehwiki Gallery
 * Tagging 的细分类，层级用 {@code =} 的数量表示、路径用 {@code >} 分隔，且名称里
 * 已带完整路径，因此解析时只需「当前分组被最近一条分组行覆盖」，无需维护层级栈。
 *
 * <p><b>语义大类（{@link TagRow#majorCategory}）的确定性</b>：{@code confident=false}
 * 表示大类是兜底猜的、分不出来，需要人工填 —— 见 {@link #classify}。
 */
public final class MangaEhTagFetcher {

    private MangaEhTagFetcher() {
    }

    /** 数据源根地址，每个命名空间一个 .md 文件 */
    public static final String BASE_URL =
            "https://raw.githubusercontent.com/EhTagTranslation/Database/master/database/";

    /** 单个文件下载失败的重试次数 */
    private static final int MAX_RETRY = 3;

    /** 内容类命名空间集合，供 eh 扫描按 namespace 过滤用（不含 language —— 语言是元信息、不是内容标签） */
    public static final Set<String> CONTENT_NAMESPACES = Set.of(
            "female", "male", "mixed", "other", "location", "reclass");

    /** 内容类命名空间（保持这个顺序，决定返回行序），值 = 中文名 */
    private static final Map<String, String> NAMESPACES = new LinkedHashMap<>();

    static {
        NAMESPACES.put("female", "女性");
        NAMESPACES.put("male", "男性");
        NAMESPACES.put("mixed", "混合");
        NAMESPACES.put("other", "其他");
        NAMESPACES.put("location", "地点");
        NAMESPACES.put("language", "语言");
        NAMESPACES.put("reclass", "重新分类");
    }

    private static final Pattern EQUALS_WRAPPED = Pattern.compile("^=+\\s*(.+?)\\s*=+$");
    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)]\\([^)]*\\)");
    private static final Pattern BR = Pattern.compile("</?br\\s*/?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_TAG = Pattern.compile("</?[a-zA-Z][^>]*>");
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern ITALIC = Pattern.compile("(?<!\\*)\\*([^*]+)\\*(?!\\*)");
    private static final Pattern CODE = Pattern.compile("`([^`]+)`");

    /**
     * tag_zh 里的 emoji 码点：ZWJ（U+200D）、变体选择符（U+FE0F）、
     * 箭头/杂项技术/杂项符号/装饰符（U+2190~U+27BF，含 ♀♂⚢⚣⏱）、
     * 以及非 BMP 码点（U+10000+，即代理对，绝大多数 emoji 在此）。
     */
    private static final Pattern EMOJI = Pattern.compile(
            "[\\u200D\\uFE0F\\u2190-\\u27BF]|[\\uD800-\\uDBFF][\\uDC00-\\uDFFF]");

    /** 一行标签的解析结果 */
    public record TagRow(String majorCategory, boolean confident, String namespace,
                         String namespaceZh, String tagEn, String tagZh,
                         String category, String description) {
    }

    /**
     * 拉取全部内容类命名空间并解析。
     *
     * @param proxyHost 可空，非空且 {@code proxyPort > 0} 时走代理（被墙时填本机代理）
     * @param proxyPort 与 {@code proxyHost} 配套
     */
    public static List<TagRow> fetch(String proxyHost, int proxyPort) throws IOException, InterruptedException {
        HttpClient client = buildClient(proxyHost, proxyPort);
        List<TagRow> all = new ArrayList<>();
        for (String ns : NAMESPACES.keySet()) {
            String md = download(client, ns);
            all.addAll(parse(ns, md));
        }
        return all;
    }

    private static HttpClient buildClient(String proxyHost, int proxyPort) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (proxyHost != null && !proxyHost.isBlank() && proxyPort > 0) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)));
        }
        return builder.build();
    }

    private static String download(HttpClient client, String ns) throws IOException, InterruptedException {
        String url = BASE_URL + ns + ".md";
        IOException last = null;
        for (int i = 0; i < MAX_RETRY; i++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("User-Agent", "Mozilla/5.0 (nya-entworks tag-fetch)")
                        .GET()
                        .build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    throw new IOException(ns + " 返回 HTTP " + resp.statusCode());
                }
                return resp.body();
            } catch (IOException e) {
                last = e;
                Thread.sleep(1000L * (i + 1));
            }
        }
        throw new IOException("下载失败：" + url, last);
    }

    /**
     * 解析单个命名空间的 markdown。
     * <p>表格列是「原始标签 / 名称 / 描述 / 外部链接」。原始标签列为空、名称列是
     * {@code ==…==} 的是分组行；原始标签非空的是数据行，继承当前分组作细分类。
     */
    private static List<TagRow> parse(String ns, String md) {
        List<TagRow> rows = new ArrayList<>();
        String currentCategory = "";
        boolean inTable = false;
        for (String line : md.split("\n", -1)) {
            String t = line.trim();
            if (!t.startsWith("|")) {
                continue;
            }
            String[] cols = t.split("\\|", -1);
            if (cols.length < 4) {
                continue;
            }
            String tag = cols[1].trim();
            String name = cols[2].trim();
            String desc = cols[3].trim();

            if (!inTable) {
                if (tag.equals("原始标签")) {
                    inTable = true;
                }
                continue;
            }
            if (tag.matches("-+")) {
                continue; // 表头分隔行
            }
            if (tag.isEmpty()) {
                String cat = extractCategory(name, desc);
                if (!cat.isEmpty()) {
                    currentCategory = cat;
                }
                continue;
            }
            Classified major = classify(ns, currentCategory, tag);
            rows.add(new TagRow(major.majorCategory(), major.confident(), ns,
                    NAMESPACES.getOrDefault(ns, ns), tag,
                    stripEmoji(cleanMarkdown(name)), currentCategory, cleanMarkdown(desc)));
        }
        return rows;
    }

    /** 从分组行的名称列 / 描述列提取分类名；描述列有中文翻译则优先 */
    private static String extractCategory(String name, String desc) {
        String zh = extractEqualsContent(desc);
        if (!zh.isEmpty()) {
            return normalizeCategory(zh);
        }
        return normalizeCategory(extractEqualsContent(name));
    }

    /** 提取 {@code ==…==} 包裹的内容（不含两端等号与空白） */
    private static String extractEqualsContent(String cell) {
        if (cell == null || cell.isEmpty()) {
            return "";
        }
        Matcher m = EQUALS_WRAPPED.matcher(cell);
        return m.find() ? m.group(1) : "";
    }

    /** 把 {@code 身体 > 生物} 两侧的多余空格规范化成 {@code 身体 > 生物} */
    private static String normalizeCategory(String s) {
        return s.replaceAll("\\s*>\\s*", " > ").trim();
    }

    // ===== 语义大类分类 =====

    /** 大类（男女不区分，人物类型不再按命名空间拆（女）/（男）） */
    public static final String CAT_CHARACTER = "人物类型";
    public static final String CAT_RELATION = "关系";
    public static final String CAT_CLOTHING = "服饰";
    public static final String CAT_PLAY = "玩法";
    public static final String CAT_LOCATION = "地点";
    public static final String CAT_WORK_TYPE = "作品类型";
    public static final String CAT_STYLE = "风格";
    public static final String CAT_LANGUAGE = "语言";

    /** 大类全集，供前端「手动填写」下拉框用，顺序即展示顺序 */
    public static final List<String> MAJOR_CATEGORIES = List.of(
            CAT_CHARACTER, CAT_RELATION, CAT_CLOTHING, CAT_PLAY,
            CAT_LOCATION, CAT_WORK_TYPE, CAT_STYLE, CAT_LANGUAGE);

    record Classified(String majorCategory, boolean confident) {
    }

    /**
     * category（细分类）→ 大类，仅 female / male 命名空间走到这张表。
     * 静态属性归人物类型、动态行为归玩法；个别 category 内混杂的标签在
     * {@link #TAG_OVERRIDES} 里再覆盖。
     */
    private static final Map<String, String> CATEGORY_MAP = new LinkedHashMap<>();

    static {
        String[] bodyAttr = {
                "年龄", "身体", "身体 > 生物", "身体 > 皮肤", "身体 > 身高", "身体 > 体重",
                "头部", "头部 > 眼睛", "头部 > 头发",
                "性别", "残疾"
        };
        for (String c : bodyAttr) {
            CATEGORY_MAP.put(c, CAT_CHARACTER);
        }
        String[] play = {
                "头部 > 思维", "头部 > 嘴", "头部 > 鼻子",
                "脖子", "躯干", "手臂", "手臂 > 手", "腿", "足",
                "下半身 > 臀部", "下半身 > 阴部", "下半身 > 阴部 > 阴茎", "下半身 > 阴部 > 阴道",
                "下半身 > 任何洞",
                "胸部 > 乳房", "胸部 > 乳房 > 乳头",
                "身体 > 其他改变", "身体 > 生物 > 动物",
                "多人活动", "多人活动 > 多个洞", "自我愉悦",
                "强迫", "强迫 > 虐待", "强迫 > 暴力", "强迫 > 束缚",
                "液体", "液体 > 体液", "液体 > 体液 > 精液", "液体 > 体液 > 排泄物",
                "工具", "隐私", "高存在", "低存在", "消费", "上下文 > 不忠"
        };
        for (String c : play) {
            CATEGORY_MAP.put(c, CAT_PLAY);
        }
        CATEGORY_MAP.put("服装", CAT_CLOTHING);
        CATEGORY_MAP.put("性别 > 性别间关系", CAT_RELATION);
        CATEGORY_MAP.put("上下文 > 亲属", CAT_RELATION);
        CATEGORY_MAP.put("上下文 > 全图库", CAT_WORK_TYPE);
    }

    /** tag 级覆盖表：category 级规则会判错的边界标签（静态/动态判定）。 */
    private static final Map<String, String> TAG_OVERRIDES = new HashMap<>();

    static {
        String[] attrToPlay = {
                "age progression", "age regression", "infantilism",
                "body modification", "inflation", "muscle growth", "shapening", "stretching",
                "tailjob", "wingjob",
                "necrophilia", "tentacles", "human on furry",
                "ahegao", "brain fuck", "cockslapping", "ear fuck", "facesitting", "headless",
                "crying", "cum in eye", "eye penetration",
                "hairjob",
                "gender change", "gender morph", "feminization",
                "bite mark", "body painting", "body writing", "large tattoo", "lipstick mark"
        };
        for (String t : attrToPlay) {
            TAG_OVERRIDES.put(t, CAT_PLAY);
        }
        String[] playToAttr = {
                "adventitious mouth", "big lips", "long tongue", "split tongue", "unusual teeth",
                "hairy armpits",
                "big ass", "multiple tails", "tail",
                "hairy", "pubic stubble", "ball-less shemale", "full-packaged futanari", "no balls",
                "adventitious penis", "big balls", "big penis", "horse cock", "huge penis",
                "knotted penis", "multiple penises", "penis bumps", "phimosis", "retractable penis",
                "adventitious vagina", "big clit", "big vagina", "multiple vaginas",
                "big areolae", "big breasts", "gigantic breasts", "huge breasts", "oppai loli",
                "small breasts",
                "big nipples", "dark nipples", "dicknipples", "inverted nipples", "multiple nipples"
        };
        for (String t : playToAttr) {
            TAG_OVERRIDES.put(t, CAT_CHARACTER);
        }
        String[] toClothing = {
                "collar", "leash",
                "gloves",
                "braces", "gag",
                "crown", "gasmask", "hood", "kigurumi pajama", "makeup", "masked face", "mouth mask",
                "blindfold", "eyemask", "eyepatch", "glasses", "sunglasses",
                "skinsuit"
        };
        for (String t : toClothing) {
            TAG_OVERRIDES.put(t, CAT_CLOTHING);
        }
        String[] ctxRelation = {"coach", "teacher", "tutor", "widow", "widower", "yuri", "yaoi"};
        for (String t : ctxRelation) {
            TAG_OVERRIDES.put(t, CAT_RELATION);
        }
        String[] ctxPlay = {"prostitution", "impregnation", "virginity"};
        for (String t : ctxPlay) {
            TAG_OVERRIDES.put(t, CAT_PLAY);
        }
        String[] ctxChar = {"mesugaki", "tomboy", "tomgirl", "yandere", "vtuber"};
        for (String t : ctxChar) {
            TAG_OVERRIDES.put(t, CAT_CHARACTER);
        }
        TAG_OVERRIDES.put("kodomo doushi", CAT_RELATION);
        TAG_OVERRIDES.put("low lolicon", CAT_CHARACTER);
        TAG_OVERRIDES.put("low shotacon", CAT_CHARACTER);
    }

    /**
     * 判定单个标签的语义大类及其确定性。
     * <p>{@code confident=false} 的两条兜底分支即「无法判断大类」：
     * <ul>
     *   <li>female / male：{@code TAG_OVERRIDES} 与 {@code CATEGORY_MAP} 都没命中 → 兜底「人物类型」；</li>
     *   <li>other：{@link #classifyOther} 落到默认 → 兜底「作品类型」。</li>
     * </ul>
     * 其余命名空间与命中映射/覆盖表的都是确定值。
     */
    static Classified classify(String ns, String category, String tagEn) {
        switch (ns) {
            case "location":
                return new Classified(CAT_LOCATION, true);
            case "language":
                return new Classified(CAT_LANGUAGE, true);
            case "reclass":
                return new Classified(CAT_WORK_TYPE, true);
            case "mixed":
                return new Classified(("上下文 > 亲属".equals(category) || "年龄".equals(category))
                        ? CAT_RELATION : CAT_PLAY, true);
            case "other":
                return classifyOther(category);
            default:
                break;
        }
        // female / male：先查 tag 级覆盖表，再查 category 级映射表
        String override = TAG_OVERRIDES.get(tagEn);
        if (override != null) {
            return new Classified(override, true);
        }
        String mapped = CATEGORY_MAP.get(category);
        return mapped != null ? new Classified(mapped, true)
                : new Classified(CAT_CHARACTER, false);
    }

    /** other 命名空间（无性别、多为技术/作品类）的分类。 */
    static Classified classifyOther(String category) {
        if ("技术 > 语言".equals(category)) {
            return new Classified(CAT_LANGUAGE, true);
        }
        if ("工具".equals(category) || "强迫".equals(category)) {
            return new Classified(CAT_PLAY, true);
        }
        return new Classified(CAT_WORK_TYPE, false);
    }

    /** 描述里的 markdown 标记剥成纯文本（图片删除、链接留文字、标签引用去反引号） */
    private static String cleanMarkdown(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String r = s;
        r = IMAGE.matcher(r).replaceAll("");
        r = LINK.matcher(r).replaceAll("$1");
        r = BR.matcher(r).replaceAll(" ");
        r = HTML_TAG.matcher(r).replaceAll("");
        r = BOLD.matcher(r).replaceAll("$1");
        r = ITALIC.matcher(r).replaceAll("$1");
        r = CODE.matcher(r).replaceAll("$1");
        return r.replaceAll("\\s+", " ").trim();
    }

    /** 去掉中文翻译里的 emoji，只留中文（如 妖精🧚♀️ → 妖精） */
    private static String stripEmoji(String s) {
        return s == null ? "" : EMOJI.matcher(s).replaceAll("").trim();
    }
}
