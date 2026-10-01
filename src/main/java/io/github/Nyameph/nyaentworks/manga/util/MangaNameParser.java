package io.github.Nyameph.nyaentworks.manga.util;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.file.FileNameUtil;
import org.apache.commons.lang3.StringUtils;
import io.github.Nyameph.nyaentworks.manga.consts.MangaDictType;
import io.github.Nyameph.nyaentworks.manga.dict.MangaDictionary;
import io.github.Nyameph.nyaentworks.manga.entity.MangaData;
import io.github.Nyameph.nyaentworks.manga.util.BracketParser.BracketType;
import io.github.Nyameph.nyaentworks.manga.util.BracketParser.Node;
import io.github.Nyameph.nyaentworks.manga.util.BracketParser.Position;

import java.io.File;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 本地漫画文件夹名称解析工具类。
 * <p>
 * 支持两种命名规则：
 * <pre>
 * 规则1： (展会) [社团 (作者)] 日文原文完整标题 (来源作品) [汉化组]
 * 规则2： [时间] 日文原文完整标题 (来源作品) [汉化组]
 * 规则3： (展会) 日文原文完整标题 (来源作品) [汉化组]
 * </pre>
 * 其中展会名、来源作品、汉化组均可能缺失，[社团 (作者)] 也可能仅为 [作者]；
 * 规则2的时间标签支持 [yyyy.MM]、[yy.MM]、[yyyy]、[yyyy.MM.dd] 等格式。
 * <p>
 * 解析过程被拆分为 5 个步骤，全部以 {@link MangaData} 作为中间存储对象：
 * <ol>
 *     <li>{@link #clearIllegalCharacters} 预处理：下划线/加号转空格、中文括号转英文</li>
 *     <li>{@link #removeUselessTags} 移除或提取无用标签</li>
 *     <li>{@link #parseByRule} 判定规则并解析展会/社团/作者/来源作品</li>
 *     <li>{@link #assemble} 整合为规范化名称</li>
 * </ol>
 * <p>
 * 词典（展会、原作、杂志、汉化组等）来自构造时传入的 {@link MangaDictionary} 快照，
 * 本类不查库、不依赖 Spring，是纯函数式的解析器。一次扫描任务应全程复用同一个快照，
 * 以免中途词典变更导致同批结果判定标准不一致。
 *
 * @author Nyameph
 */
public final class MangaNameParser {

    private final MangaDictionary dict;

    public MangaNameParser(MangaDictionary dict) {
        this.dict = Objects.requireNonNull(dict, "dict 不能为空");
    }

    /** 当前解析器使用的词典快照 */
    public MangaDictionary getDict() {
        return dict;
    }

    // ----------------------------------------------------------------------
    // 关键词配置（步骤2、步骤3）
    // ----------------------------------------------------------------------

    /** 規範化命名阶段补全的無修正标签 */
    private static final String UNCENSORED_TAG = "[無修正]";

    /**
     * 步骤3：判断某个方括号标签内容是否为时间标签，如 2024、24.05、2024.05.01。
     */
    private static final Pattern DATE_CONTENT_PATTERN = Pattern.compile(
            "^\\s*(?:" +
                    "(?:19|20)\\d{2}(?:\\.[01]\\d)?(?:[.-][0123]\\d)?|" +
                    "\\d{2}[.-][01]\\d(?:[.-][0123]\\d)?" +
                    ")\\s*$"  //20-01
    );

    /**
     * 图片格式后缀名
     */
    private static final List<String> SUPPORTED_IMAGE_EXTENSIONS = List.of("jpg", "jpeg", "png", "gif", "bmp", "webp");

    // 归档根 → 评分的映射表原先写死在这里（四条 F:\MangaGroup\…，顺序即优先级）。
    // 2026-09-17 起改为配置驱动：根路径取 nya-entworks.manga.archive-dir，
    // 分区目录按磁盘现扫 —— 调用方用 MangaScoreDir.rootScoreMap(properties.getArchiveDir())。
    // 本类自己从来没读过那张表，它只是当初的存放处。

    // ----------------------------------------------------------------------
    // 入口
    // ----------------------------------------------------------------------


    /**
     * 解析文件夹名称，返回填充好的 {@link MangaData} 中间对象。
     *
     * @param dir 原始文件夹名
     * @return 解析结果；未匹配任何规则时 {@code matchedRule == 0}
     */
    public MangaData parseDir(Path dir) {
        // cbz 当漫画单元：解析用「去掉 .cbz 的名字」，封面取 zip 首图条目名。
        // 其余步骤（父级补全、规范化命名）与目录漫画完全一致
        boolean cbz = MangaCbzUtil.isCbz(dir);
        String folderName = cbz
                ? MangaCbzUtil.stripCbz(dir.getFileName().toString())
                : dir.getFileName().toString();
        // 无视父级目录（本身形如 [社团 (作者)]【标签…】 的中间目录）
        if (parentGroupArtistNode(folderName.trim()) != null) {
            return null;
        }
        // 仅查找有图片的
        String firstImage = MangaNameParser.getFirstImage(dir);
        if (firstImage == null) {
            return null;
        }
        // 解析文件夹名（携带各级父级文件夹名，供规则2补全社团/作者）
        String[] groupArtist = null;
        for (Path p = dir.getParent(); p != null; p = p.getParent()) {
            Path name = p.getFileName();
            if (name != null) {
                Node parentGroupArtist = parentGroupArtistNode(name.toString().trim());
                if (parentGroupArtist != null) {
                    groupArtist = extractGroupArtistFromNode(parentGroupArtist);
                    break;
                }
            }
        }
        MangaData dataManga = parse(folderName, groupArtist);
        dataManga.setCoverFile(firstImage);
        dataManga.setFolderPath(dir.toString());
        // cbz 的规范化名要带回 .cbz 后缀：folderName 已去后缀参与解析，此处补回，
        // 这样 needsRename 的「规范名 vs 当前名」比较、以及改名目标都保留扩展名
        if (cbz && StringUtils.isNotBlank(dataManga.getNormalizedName())) {
            dataManga.setNormalizedName(dataManga.getNormalizedName() + ".cbz");
        }

        return dataManga;
    }

    /**
     * 解析一个<b>尚未落盘</b>的目录名 —— 阅读页改名的实时预演用。
     * <p>与 {@link #parseDir} 的差别只在最后一处磁盘检查：它不要求目录真实存在、
     * 不查图片，因此可以在输入过程中直接判断新名字匹配哪条规则、有没有未识别
     * 展会/原作。父级目录名照常参与规则2、3 的作者补全。
     *
     * @param folderName 候选新目录名（原始串，未经 {@link #clearIllegalCharacters}）
     * @param parentDir  该目录的父目录，规则2、3 从它往上找 {@code [社团 (作者)]} 补全作者
     * @return 解析结果；名字本身形如 {@code [社团 (作者)]}（合集目录形态）时返回 {@code null}
     */
    public MangaData parseCandidateName(String folderName, Path parentDir) {
        String name = folderName.trim();
        if (parentGroupArtistNode(name) != null) {
            return null;
        }
        String[] groupArtist = null;
        for (Path p = parentDir; p != null; p = p.getParent()) {
            Path n = p.getFileName();
            if (n != null) {
                Node parentGroupArtist = parentGroupArtistNode(n.toString().trim());
                if (parentGroupArtist != null) {
                    groupArtist = extractGroupArtistFromNode(parentGroupArtist);
                    break;
                }
            }
        }
        return parse(folderName, groupArtist);
    }

    /**
     * 解析文件夹名称，返回填充好的 {@link MangaData} 中间对象。
     * <p>各步骤依次以 dataManga 为载体累加解析结果，
     * {@code working} 为逐步清洗的工作字符串。
     *
     * @param folderName 原始文件夹名
     * @return 解析结果；未匹配任何规则时 {@code matchedRule == 0}
     */
    public MangaData parse(String folderName) {
        return parse(folderName, null);
    }

    /**
     * 解析文件夹名称，返回填充好的 {@link MangaData} 中间对象。
     * <p>各步骤依次以 dataManga 为载体累加解析结果，
     * {@code working} 为逐步清洗的工作字符串。
     *
     * @param folderName    原始文件夹名
     * @param groupArtist 各级父级文件夹名提取的社团/作者
     * @return 解析结果；未匹配任何规则时 {@code matchedRule == 0}
     */
    public MangaData parse(String folderName, String[] groupArtist) {
        MangaData manga = new MangaData();

        String clearString = clearIllegalCharacters(folderName);
        List<Node> working = BracketParser.parseStr(clearString);
        working = removeUselessTags(working, manga);
        parseByRule(working, manga, groupArtist);
        assemble(manga);

        return manga;
    }


    /**
     * 判断一个文件夹是否包含图片文件。
     *
     * @param dir 文件夹路径
     * @return 是否包含图片
     */
    public static String getFirstImage(Path dir) {
        // cbz：封面是 zip 里按自然序的首个图片条目名
        if (MangaCbzUtil.isCbz(dir)) {
            return MangaCbzUtil.firstImageEntry(dir);
        }
        // 遍历目录下的文件，检查是否包含支持的图片文件
        List<File> files = FileUtil.loopFiles(dir.toFile(), 1, file -> {
            String ext = FileNameUtil.extName(file);
            if (ext == null) {
                return false;
            } else {
                return SUPPORTED_IMAGE_EXTENSIONS.contains(ext.toLowerCase());
            }
        });
        if (files.isEmpty()) {
            return null;
        } else {
            return files.getFirst().getName();
        }
    }

    /**
     * 判定父级文件夹名是否形如 {@code [社团名 (作者)]【标签…】}：
     * 顶层首个节点为方括号，且其后存在全角方括号（{@link BracketType#BRACKET_CN}）标签块。
     * 满足则返回该方括号节点（供提取社团/作者），否则 {@code null}。
     */
    public static Node parentGroupArtistNode(String dir) {
        List<Node> nodes = BracketParser.parseStr(dir);
        if (nodes.isEmpty() || nodes.size() > 2 || nodes.get(0).getBracketType() != BracketType.BRACKET) {
            return null;
        }
        if (nodes.size() == 2 && nodes.get(1).getBracketType() != BracketType.BRACKET_CN) {
            return null;
        }
        return nodes.getFirst();
    }

    /**
     * 归档目录名的完整拆解结果。
     *
     * @param groupName   社团名原文，{@code [作者]} 形态时为 {@code null}
     * @param artistNames 作者名原文，多作者保留 {@code 、} 分隔符
     * @param tags        {@code 【…】} 块内按空白拆分出的标签，无标签块时为空列表
     */
    public record ArchiveFolderInfo(String groupName, String artistNames, List<String> tags) {
    }

    /**
     * 解析归档目录名 {@code [社团 (作者甲、作者乙)]【标签1 标签2】}。
     * <p>社团/作者的判定沿用 {@link #parentGroupArtistNode} 与
     * {@link #extractGroupArtistFromNode}，与规则2、3 从父级补全作者时是同一套逻辑；
     * 本方法额外取出被前者丢弃的 {@code 【…】} 标签块。
     * <p><b>必须传未经 {@link #clearIllegalCharacters} 处理的原始目录名</b>：
     * 那个方法会把 {@code 【】} 替换成 {@code []}，替换后标签块与社团块无法区分。
     *
     * @return 拆解结果；不是归档目录形态时返回 {@code null}
     */
    public static ArchiveFolderInfo parseArchiveFolderName(String folderName) {
        if (StringUtils.isBlank(folderName)) {
            return null;
        }
        String trimmed = folderName.trim();
        Node groupArtistNode = parentGroupArtistNode(trimmed);
        if (groupArtistNode == null) {
            return null;
        }
        String[] groupArtist = extractGroupArtistFromNode(groupArtistNode);
        if (groupArtist == null) {
            return null;
        }
        String group = groupArtist.length == 1 ? null : trimToNull(groupArtist[0]);
        String artists = trimToNull(groupArtist[groupArtist.length - 1]);

        // parentGroupArtistNode 已保证：节点数至多 2，且第 2 个必为 BRACKET_CN
        List<Node> nodes = BracketParser.parseStr(trimmed);
        List<String> tags = new ArrayList<>();
        if (nodes.size() == 2) {
            String tagText = nodes.get(1).toContentString();
            if (StringUtils.isNotBlank(tagText)) {
                for (String tag : tagText.trim().split("\\s+")) {
                    if (StringUtils.isNotBlank(tag)) {
                        tags.add(tag.trim());
                    }
                }
            }
        }
        return new ArchiveFolderInfo(group, artists, tags);
    }

    /**
     * 把归档目录名里的标签 {@code oldTag} 换成 {@code newTag}，其余部分原样保留。
     * <p>标签改名/合并/删除时要连带改目录名（目录名是标签的权威来源），这里是那步
     * 字符串改写。两条刻意的取舍：
     * <ol>
     *     <li><b>只动 {@code 【…】} 块</b>，前面的社团/作者部分按原文照抄 ——
     *         那部分的空格与括号形态是手写的，按 {@link ArchiveFolderInfo} 重新拼装容易走样；</li>
     *     <li><b>其余标签保留目录里的原文</b>，不拿库里的形式覆盖 ——
     *         {@code manga_tag} 存的是大写归一形式，写回目录会把 {@code Ero} 变成 {@code ERO}。</li>
     * </ol>
     * <p>比对标签用 {@link MangaTextUtil#normalizeNameKey}，与 {@code manga_tag} 的键同源。
     *
     * @param newTag 新标签名；{@code null} 表示从目录名里去掉该标签
     * @return 改写后的目录名；{@code folderName} 不是归档目录形态时返回 {@code null}
     */
    public static String replaceArchiveTag(String folderName, String oldTag, String newTag) {
        ArchiveFolderInfo info = parseArchiveFolderName(folderName);
        String oldKey = MangaTextUtil.normalizeNameKey(oldTag);
        if (info == null || oldKey == null) {
            return null;
        }
        String replacement = trimToNull(newTag);
        List<String> tags = new ArrayList<>();
        for (String tag : info.tags()) {
            String kept = oldKey.equals(MangaTextUtil.normalizeNameKey(tag)) ? replacement : tag;
            // 合并到本目录已有的标签时会撞车，去重（【】块按空白拆，重复标签没有意义）
            if (kept != null && tags.stream().noneMatch(t -> t.equalsIgnoreCase(kept))) {
                tags.add(kept);
            }
        }
        return withArchiveTags(folderName, tags);
    }

    /**
     * 按结构化字段重新拼出归档目录名 {@code [社团 (作者甲、作者乙)]【标签1 标签2】}。
     * <p>与 {@link #replaceArchiveTag} 的分工：那个是「只动标签、其余照抄」的最小改动，
     * 用于标签重命名；本方法是归档编辑表单用的完整拼装 —— 社团/作者也可能变，
     * 没法照抄。因此手写的多余空格会被规整掉，这是重新拼装的固有代价。
     * <p>作者为空时拼不出合法的归档目录名（{@code []} 不成形态），返回 {@code null}
     * 让调用方拒绝提交，而不是造出一个下次同步就不再入库的目录名。
     *
     * @param groupName   社团名，为空表示 {@code [作者]} 形态
     * @param artistNames 作者名，多作者用 {@code 、} 分隔；为空则返回 {@code null}
     * @param tags        标签，为空表示没有 {@code 【…】} 块
     */
    public static String buildArchiveFolderName(String groupName, String artistNames,
                                                List<String> tags) {
        String artists = trimToNull(artistNames);
        if (artists == null) {
            return null;
        }
        String group = trimToNull(groupName);
        String head = group == null ? "[" + artists + "]" : "[" + group + " (" + artists + ")]";
        return withArchiveTags(head, tags);
    }

    /**
     * 换掉归档目录名末尾的 {@code 【…】} 标签块，前面的部分原样保留。
     * <p>{@link #parentGroupArtistNode} 保证归档目录名至多两个顶层节点、第二个必是
     * {@code 【】}，所以按最后一个 {@code 【} 切开就能把社团块完整留下。
     */
    public static String withArchiveTags(String folderName, List<String> tags) {
        String trimmed = folderName.trim();
        int cn = trimmed.lastIndexOf('【');
        String prefix = cn >= 0 && trimmed.endsWith("】") ? trimmed.substring(0, cn) : trimmed;
        if (CollectionUtil.isEmpty(tags)) {
            // 标签块没了，原来 ] 与 【 之间的那个空格会变成结尾空格，Windows 目录名不能留
            return StringUtils.stripEnd(prefix, null);
        }
        return prefix + "【" + StringUtils.join(tags, " ") + "】";
    }

    /**
     * 读取某一目录下的 作者 - 目录映射，用于归档
     * <p>归档匹配已改走 {@code MangaArchiveService}（映射存于 {@code manga_archive_unit}
     * 与 {@code manga_archive_name}，并额外保留标签与重名冲突）。本方法是不连库的
     * 等价实现，仅留作临时排查用；它不产出标签，重名也只打印。
     * @param rootPath
     * @return
     */
    public static List<Map<String, Path>> readArchiveGroupArtists(String rootPath) {
        Map<String, Path> groupPathMap = new HashMap<>();
        Map<String, Path> artistPathMap = new HashMap<>();
        Map<String, Path> allPathMap = new HashMap<>();
        FileUtil.loopFiles(Paths.get(rootPath), 1, file -> {
            if (!file.isDirectory()) {
                return false;
            }
            BracketParser.Node groupArtistNode = parentGroupArtistNode(file.getName());
            if (groupArtistNode != null) {
                String[] groupArtistPart = extractGroupArtistFromNode(groupArtistNode);
                List<String> groups = new ArrayList<>();
                List<String> artists = new ArrayList<>();
                if (groupArtistPart.length == 1) {
                    artists.addAll(splitGroupArtistToCompare(groupArtistPart[0]));
                } else {
                    groups.addAll(splitGroupArtistToCompare(groupArtistPart[0]));
                    artists.addAll(splitGroupArtistToCompare(groupArtistPart[1]));
                }

                Path path = file.toPath();
                if (CollectionUtil.isNotEmpty(groups)) {
                    for (String group : groups) {
                        if (allPathMap.containsKey(group)) {
                            System.out.println("😭 群组或作者重复：" + group + " " + path + " " + allPathMap.get(group));
                        } else {
                            groupPathMap.put(group, path);
                            allPathMap.put(group, path);
                        }
                    }
                }
                if (CollectionUtil.isNotEmpty(artists)) {
                    for (String artist : artists) {
                        if (allPathMap.containsKey(artist)) {
                            System.out.println("😭 群组或作者重复：" + artist + " " + path + " " + allPathMap.get(artist));
                        } else {
                            artistPathMap.put(artist, path);
                            allPathMap.put(artist, path);
                        }
                    }
                }
                return true;
            } else {
                System.out.println("不规范：" + file.getAbsolutePath());
                return false;
            }
        });
        return List.of(groupPathMap, artistPathMap);
    }


    // ----------------------------------------------------------------------
    // 步骤1：预处理
    // ----------------------------------------------------------------------

    /**
     * 步骤1：将 {@code _}、{@code +} 替换为空格，并将中文括号统一替换为英文括号。
     * <p>{@code 【】} 不做替换，保持 {@link BracketParser.BracketType#BRACKET_CN} 原样参与解析 ——
     * 它是标签块的形态（如 {@code 【漢化組】}），替换成 {@code []} 会把类型信息丢掉。
     * 全角半方括号 {@code ［］} 仍归一到 {@code []}。
     */
    private static String clearIllegalCharacters(String input) {
        if (input == null) {
            return "";
        }
        return input
                .replace('_', ' ')
                .replace('+', ' ')
                .replaceAll("\\h+", " ")
                .replace('（', '(').replace('）', ')')
                .replace('［', '[').replace('］', ']')
                .replaceAll("(?i)\\s*[vV]\\d+$", "")  // 结尾的 v2 / V3 等版本号)
                .replaceAll("\\[MJK-[\\w-]+\\]", "")  // [MJK-xxxx] 机翻编号
                .trim();
    }

    // ----------------------------------------------------------------------
    // 步骤2：移除或提取无用标签
    // ----------------------------------------------------------------------

    /**
     * 步骤2：判断无修正、移除 {@link MangaDictType#USELESS_TAG} 词典中定义的无用标签。
     */
    private List<Node> removeUselessTags(List<Node> nodes, MangaData manga) {

        // 判定無修正（在丢弃标签前判断，避免误删信息）
        boolean uncensored = false;
        List<Node> result = new ArrayList<>(nodes.size());
        Set<String> reservedTags = new LinkedHashSet<>();
        for (Node node : nodes) {
            // 判断无修正
            if (dict.matches(MangaDictType.UNCENSORED, node.getText())) {
                uncensored = true;
                continue;
            }

            // 部分标签移除括号
            if (dict.matches(MangaDictType.UNBOXING, node.getText())) {
                node.setBracketType(null);
            }

            // 提取后置标签
            // 汉化组等修饰标签，后缀词/整词/特征词均归入 MODIFIER，由词条自身的匹配方式区分
            if (node.getBracketType() != null) {
                if (dict.matches(MangaDictType.MODIFIER, node.getText())) {
                    reservedTags.add(node.toSimpleString().trim());
                    continue;
                }
                if ("4K".equalsIgnoreCase(node.getText()) && node.getChildren().size() == 2 && "掃圖組".equals(node.getChildren().getLast().getText())) {
                    reservedTags.add("[4KS版掃圖組]");
                    continue;
                }
                if (node.getText().matches("^RJ\\d+$")) {
                    reservedTags.add(node.toSimpleString().trim());
                    continue;
                }
            }

            // 保留部分内容
            if (!dict.matches(MangaDictType.USELESS_TAG, node.getText())) {
                result.add(node);
            }
        }
        manga.setUncensored(uncensored);
        if (CollectionUtil.isNotEmpty(reservedTags)) {
            manga.setReservedTag(StringUtils.join(reservedTags, " ").trim());
        }

        //重新标记 FRONT 和 BACK
        BracketParser.assignPositions(result);
        return result;
    }

    // ----------------------------------------------------------------------
    // 步骤3：判定规则并解析
    // ----------------------------------------------------------------------

    /**
     * 步骤3：先用 {@link BracketParser} 将 {@code working} 解析为树形 {@link Node}，
     * 依据顶层节点的括号类型与位置区分规则1 / 规则2 / 规则3，再解析
     * 展会、社团、作者、来源作品等信息写入 {@link MangaData}。
     * <p>判定顺序：
     * <ol>
     *     <li>顶层首个节点为方括号且内容为时间标签 → 规则2；</li>
     *     <li>否则存在头部方括号（社团/作者块）→ 规则1；</li>
     *     <li>都不满足时若能从父级补全作者，且顶层存在头部小括号（展会）→ 规则3。</li>
     * </ol>
     */
    private void parseByRule(List<Node> nodes, MangaData manga, String[] groupArtist) {
        Node dateNode = dateTagNode(nodes);
        // 展会为整体判定（原 EXHIBIT_CONTENT_PATTERN 的 matches 语义），不拆分
        fillDataFromNode(manga, "exhibit", findNodeByDictionary(nodes, MangaDictType.EXHIBIT, false));
        if (StringUtils.isNotEmpty(manga.getExhibit())) {
            manga.setExhibitInDict(true);
        }
        fillDataFromNode(manga, "parody", findNodeByDictionary(nodes, MangaDictType.PARODY));
        if (StringUtils.isNotEmpty(manga.getParody())) {
            manga.setParodyInDict(true);
        }
        // 杂志的前缀词与整体正则同属 MAGAZINE 类型，一次查找即可
        fillDataFromNode(manga, "magazine", findNodeByDictionary(nodes, MangaDictType.MAGAZINE));
        if (dateNode != null) {
            if (groupArtist != null || findBracketNodeByPosition(nodes, Position.FRONT, BracketType.BRACKET) != null) {
                // 规则2：开头为时间标签
                dateNode.setText(dateNode.getText().replace("-", "."));
                fillDataFromNode(manga, "dateTag", dateNode);
                parseRule2(nodes, manga, groupArtist);
            }
        } else {
            if (findBracketNodeByPosition(nodes, Position.FRONT, BracketType.BRACKET) != null) {
                // 规则1：(展会) [社团 (作者)] ...
                parseRule1(nodes, manga);
            } else if (groupArtist != null
                    //需要考虑一种特殊情况，即内部括号有作者名的情况，这种情况往往是有多余前缀，应该算作规则0
                    && !artistInMiddle(nodes, groupArtist) && (
                    StringUtils.isNotEmpty(manga.getExhibit()) ||
                            StringUtils.isNotEmpty(manga.getParody()) ||
                            StringUtils.isNotEmpty(manga.getMagazine()) ||
                            findBracketNodeByPosition(nodes, Position.FRONT, BracketType.PARENTHESIS, dict) != null
            )) {
                // 规则3：(展会)  ...
                parseRule3(nodes, manga, groupArtist);
            } else {
                // 特殊规则
                parseRuleSpecial(nodes, manga, groupArtist);
            }
        }
        if (manga.getMatchedRule() == 0) {
            manga.setTitle(nodes.stream().map(Node::toSimpleString).collect(Collectors.joining()));
        }
    }

    /** 解析规则1，提取展会/社团/作者/标题/来源作品 */
    private void parseRule1(List<Node> nodes, MangaData manga) {
        fillDataFromNode(manga, "exhibit", findBracketNodeByPosition(nodes, Position.FRONT, BracketType.PARENTHESIS, dict));
        fillDataFromNode(manga, "parody", findBracketNodeByPosition(nodes, Position.TAIL, BracketType.PARENTHESIS, dict));
        Node groupArtistNode = findBracketNodeByPosition(nodes, Position.FRONT, BracketType.BRACKET);
        fillGroupArtistFromNode(manga, groupArtistNode);
        manga.setGroupArtistFromParent(false);
        manga.setMatchedRule(1);

        manga.setTitle(trimToNull(joinMiddleText(nodes)));
    }

    /**
     * 解析规则2，提取标题/来源作品（首个方括号已作为时间标签处理）。
     * <p>规则2的文件夹名本身不含社团/作者，若其某一级父级文件夹形如
     * {@code [社团名 (作者)]【标签 标签…】}，则从中补全社团与作者，
     * 仅填充空缺字段，不改变标题、来源作品及规范化命名。
     */
    private void parseRule2(List<Node> nodes, MangaData manga, String[] groupArtist) {
        fillDataFromNode(manga, "exhibit", findBracketNodeByPosition(nodes, null, BracketType.PARENTHESIS, dict));
        fillDataFromNode(manga, "parody", findBracketNodeByPosition(nodes, Position.TAIL, BracketType.PARENTHESIS, dict));
        Node groupArtistNode = findBracketNodeByPosition(nodes, Position.FRONT, BracketType.BRACKET);
        if (groupArtistNode != null) {
            fillGroupArtistFromNode(manga, groupArtistNode);
            manga.setGroupArtistFromParent(false);
        } else {
            fillGroupArtistFromString(manga, groupArtist);
            manga.setGroupArtistFromParent(true);
        }
        manga.setTitle(trimToNull(joinMiddleText(nodes)));
        manga.setMatchedRule(2);
    }

    /** 解析规则3，提取展会/社团/作者/标题/来源作品 */
    private void parseRule3(List<Node> nodes, MangaData manga, String[] groupArtist) {
        fillDataFromNode(manga, "exhibit", findBracketNodeByPosition(nodes, Position.FRONT, BracketType.PARENTHESIS, dict));
        fillDataFromNode(manga, "parody", findBracketNodeByPosition(nodes, Position.TAIL, BracketType.PARENTHESIS, dict));
        // 规则3
        fillGroupArtistFromString(manga, groupArtist);
        manga.setGroupArtistFromParent(true);
        manga.setMatchedRule(3);
        manga.setTitle(trimToNull(joinMiddleText(nodes)));
    }

    /** 解析规则3，特殊情况 */
    private void parseRuleSpecial(List<Node> nodes, MangaData manga, String[] groupArtist) {
        // fanbox 作品集
        if (nodes.size() == 2
                && (nodes.get(0).getText().endsWith("作品集") || nodes.get(0).getText().endsWith("杂图集")|| nodes.get(0).getText().startsWith("汉化汇总")) && nodes.get(0).getBracketType() == null
                && nodes.get(1).getText().contains("截止") && nodes.get(1).getBracketType() == BracketType.PARENTHESIS
        ) {
            //nodes.get(0).setText(nodes.get(0).getText() + " " + nodes.get(1).getText());
            //nodes.remove(1);

            //视作自带作者名
            fillGroupArtistFromString(manga, groupArtist);
            manga.setGroupArtistFromParent(false);
            manga.setMatchedRule(1);
            manga.setTitle(trimToNull(joinMiddleText(nodes)));
        }
    }


    /**
     * 根据头部方括号节点填充社团与作者：
     * 节点含小括号子节点时视为 {@code [社团 (作者)]}，社团取节点文本、作者取子节点文本；
     * 否则整体视为 {@code [作者]}。{@code bracketNode} 为 {@code null} 时不填充。
     */
    private static void fillGroupArtistFromNode(MangaData manga, Node bracketNode) {
        String[] groupArtist = extractGroupArtistFromNode(bracketNode);
        if (groupArtist != null) {
            fillGroupArtistFromString(manga, groupArtist);
            bracketNode.setUsed(true);
            Node artistNode = findBracketNodeByPosition(bracketNode.getChildren(), Position.TAIL, BracketType.PARENTHESIS);
            if (artistNode != null) {
                artistNode.setUsed(true);
            }
        }
    }

    private static void fillGroupArtistFromString(MangaData manga, String[] groupArtist) {
        if (groupArtist == null || groupArtist.length == 0 || manga.getArtist() != null || manga.getGroupName() != null) {
            return;
        }
        if (groupArtist.length == 1) {
            manga.setArtist(trimToNull(groupArtist[0]));
        } else {
            manga.setGroupName(trimToNull(groupArtist[0]));
            manga.setArtist(trimToNull(groupArtist[1]));
        }
    }


    // ----------------------------------------------------------------------
    // 步骤4：整合规范化名称
    // ----------------------------------------------------------------------

    /**
     * 步骤4：整合成 {@link MangaData}，补全汉化组与無修正标签，
     * 生成用于重命名的 {@link MangaData#getNormalizedName()}。
     * <p>
     * 与旧实现不同，本方法不再直接沿用步骤3清洗后的整串，而是依据
     * {@link MangaData#getMatchedRule()} 用已解析的结构化字段重新拼装主体：
     * <pre>
     * 规则1： (展会) [社团 (作者)] 标题 (来源作品)
     * 规则2： [时间] 标题 (来源作品)
     * </pre>
     * 当未匹配任何规则（{@code matchedRule == 0}，此时结构化字段全为空）时，
     * 回退到 {@code cleaned} 以避免整体名称丢失。
     *
     */
    public static void assemble(MangaData manga) {
        // 由结构化字段重建主体；规则0无字段可用时回退到清洗后的原串
        String body = buildBody(manga);

        StringBuilder name = new StringBuilder(body);
        if (StringUtils.isNotEmpty(manga.getReservedTag())) {
            name.append(' ').append(manga.getReservedTag());
        }
        if (manga.isUncensored()) {
            name.append(' ').append(UNCENSORED_TAG);
        }
        manga.setNormalizedName(name.toString().trim());
    }

    /**
     * 依据匹配到的规则，用 {@link MangaData} 的结构化字段重新拼装主体名称。
     * 各段缺失时自动跳过，段间以单个空格分隔。
     *
     * @return 拼装后的主体；未匹配任何规则时返回空串
     */
    private static String buildBody(MangaData manga) {
        StringBuilder body = new StringBuilder();
        if (manga.getMatchedRule() != 0) {
            appendPart(body, wrap(manga.getDateTag(), '[', ']'));
            appendPart(body, wrap(manga.getExhibit(), '(', ')'));
            if (!manga.isGroupArtistFromParent()) {
                appendPart(body, buildGroupArtist(manga));
            }
            appendPart(body, manga.getTitle());
            appendPart(body, wrap(manga.getParody(), '(', ')'));
            appendPart(body, wrap(manga.getMagazine(), '(', ')'));
        } else {
            appendPart(body, manga.getTitle());
        }
        return body.toString().trim();
    }

    /** 拼装 {@code [社团 (作者)]}；仅有其一时按 {@code [作者]} 处理，皆空时返回 {@code null} */
    private static String buildGroupArtist(MangaData manga) {
        String group = trimToNull(manga.getGroupName());
        String artist = trimToNull(manga.getArtist());
        if (group == null && artist == null) {
            return null;
        }
        if (group != null && artist != null) {
            return "[" + group + " (" + artist + ")]";
        }
        return "[" + (artist != null ? artist : group) + "]";
    }

    /** 向 {@code sb} 追加非空段，非首段前补一个空格 */
    private static void appendPart(StringBuilder sb, String part) {
        if (StringUtils.isBlank(part)) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(part.trim());
    }

    /** 用 {@code open}/{@code close} 包裹非空值，空值返回 {@code null} */
    private static String wrap(String value, char open, char close) {
        return StringUtils.isBlank(value) ? null : open + value.trim() + close;
    }

    // ----------------------------------------------------------------------
    // 辅助方法
    // ----------------------------------------------------------------------

    // ---- 基于 BracketParser.Node 的结构辅助 ----

    /**
     * 返回顶层位于某个位置的有括号节点，无则 {@code null}
     *
     * @param position 传入null则代表头尾都可以
     * @param type 传入null则任意一种括号都可以
     */
    private static Node findBracketNodeByPosition(List<Node> nodes, Position position, BracketType type) {
        // ignoreSomething = false 时不查词典，故可为静态
        return findBracketNodeByPosition(nodes, position, type, null);
    }

    /**
     * @param ignoreDict 传入词典则跳过命中 {@link MangaDictType#IGNORE_CONTENT} 的节点
     *                   （含页数、截止日期等正则词条）；传 {@code null} 表示不做此过滤
     */
    private static Node findBracketNodeByPosition(List<Node> nodes, Position position, BracketType type,
                                                  MangaDictionary ignoreDict) {
        for (int i = 0; i < nodes.size(); i++) {
            Node node = Position.TAIL.equals(position) ? nodes.get(nodes.size() - 1 - i) : nodes.get(i);
            if (node.isUsed() || Position.MIDDLE.equals(node.getPosition())) {
                continue;
            }
            if (position != null && !position.equals(node.getPosition())) {
                continue;
            }
            if (node.getBracketType() == null || (type != null && !type.equals(node.getBracketType()))) {
                continue;
            }
            if (ignoreDict != null && ignoreDict.matches(MangaDictType.IGNORE_CONTENT, node.getText())) {
                continue;
            }
            return node;
        }
        return null;
    }

    /**
     * 判定规则2的时间标签节点：顶层首个非空节点若为方括号且其文本为时间标签则返回，否则 {@code null}。
     */
    private static Node dateTagNode(List<Node> nodes) {
        if (nodes.isEmpty()) {
            return null;
        }
        for (Node node : nodes) {
            if (node.getBracketType() == BracketType.BRACKET
                    && node.getPosition() == Position.FRONT
                    && node.getText() != null
                    && DATE_CONTENT_PATTERN.matcher(node.getText()).matches()) {
                return node;
            }
        }
        return null;
    }

    /**
     * 查找首个命中指定词典的顶层带括号节点（跳过已使用及 {@link Position#MIDDLE} 的节点）。
     * <p>节点内容除整体比对外，还会按 {@code ", "} 与 {@code "、"} 拆分后逐段比对，
     * 以支持多原作合写，如 {@code (FateGrand Order、Fate stay night)}。
     */
    private Node findNodeByDictionary(List<Node> nodes, MangaDictType type) {
        return findNodeByDictionary(nodes, type, true);
    }

    /**
     * @param splitSegments 是否按 {@code ", "}/{@code "、"} 拆分后逐段比对。
     *                      展会为整体判定，传 {@code false}
     */
    private Node findNodeByDictionary(List<Node> nodes, MangaDictType type, boolean splitSegments) {
        if (nodes.isEmpty() || !dict.hasEntry(type)) {
            return null;
        }
        for (Node node : nodes) {
            if (node.isUsed()) {
                continue;
            }
            if (node.getPosition() != Position.MIDDLE && node.getBracketType() != null && node.getText() != null) {
                // 社团块 [社团 (作者)] 不可能是展会/原作/杂志，跳过以免社团名误命中词典
                // （如 [Comic Kingdom (作者)] 的 "Comic" 前缀命中 MAGAZINE 词条）
                if (isGroupArtistBlock(node)) {
                    continue;
                }
                if (hitDictionary(type, node.toContentString(), splitSegments)) {
                    return node;
                }
                // 含子括号时（如 [COMIC快楽天 2024年5月号 [DL版]]）再单独比对节点自身文本，
                // 否则子节点内容会让整体正则匹配不上
                if (!node.getChildren().isEmpty() && hitDictionary(type, node.getText(), splitSegments)) {
                    return node;
                }
            }
        }
        return null;
    }

    /**
     * 判断节点是否为社团块形态 {@code [社团 (作者)]}：方括号内含有作者小括号子节点。
     * <p>社团块只在规则判定后才被提取，但词典识别（exhibit/parody/magazine）跑在它前面，
     * 若社团名恰好命中词典词条（如 {@code Comic} 前缀），会把社团块误当成杂志并提前置
     * {@code used}，导致规则1 找不到头部方括号、group/artist 丢失。故词典识别阶段跳过该形态。
     */
    private static boolean isGroupArtistBlock(Node node) {
        return node.getBracketType() == BracketType.BRACKET
                && findBracketNodeByPosition(node.getChildren(), Position.TAIL, BracketType.PARENTHESIS) != null;
    }

    private boolean hitDictionary(MangaDictType type, String text, boolean splitSegments) {
        return splitSegments
                ? dict.matchesAnySegment(type, text)
                : dict.matches(type, text.trim());
    }

    /**
     * 拼接顶层所有无括号节点文本作为标题，节点间以单空格分隔。
     */
    private static String joinMiddleText(List<Node> nodes) {
        boolean appendBracket = false;
        StringBuilder sb = new StringBuilder();
        for (Node node : nodes) {
            if (!node.isUsed()) {
//                if (!sb.isEmpty() && StringUtils.isNotBlank(node.getText())) {
//                    sb.append(' ');
//                }
                sb.append(node.toSimpleString());
                if (node.isHasSpaceWithNext()) {
                    sb.append(' ');
                }
                if (node.getBracketType() != null) {
                    appendBracket = true;
                }
            }
        }
        if (appendBracket) {
            //fixme 临时标记
            System.out.println("    " + sb.toString());
        }
        return sb.toString();
    }


    private static String trimToNull(String s) {
        return StringUtils.isBlank(s) ? null : s.trim();
    }

    private static void fillDataFromNode(MangaData manga, String fieldName, Node node) {
        try {
            VarHandle handle = MethodHandles
                    .privateLookupIn(MangaData.class, MethodHandles.lookup())
                    .findVarHandle(MangaData.class, fieldName, String.class);
            if (handle.get(manga) != null) {
                return;
            }
            if (node != null && StringUtils.isNotBlank(node.getText())) {
                handle.set(manga, node.toContentString().trim());
                node.setUsed(true);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 作者名出现在了文件名中间，说明
     * @return
     */
    private static boolean artistInMiddle(List<Node> nodes, String[] groupArtist) {
        if (CollectionUtil.isEmpty(nodes) || groupArtist == null || groupArtist.length == 0) {
            return false;
        }

        List<String> baseKeys = new ArrayList<>();
        for (String s : groupArtist) {
            baseKeys.addAll(splitGroupArtistToCompare(s));
        }
        for (Node node : nodes) {
            if (Position.MIDDLE.equals(node.getPosition()) && BracketType.BRACKET.equals(node.getBracketType())) {
                List<String> currentKeys = new ArrayList<>();
                Node artistNode = findBracketNodeByPosition(node.getChildren(), Position.TAIL, BracketType.PARENTHESIS);
                if (artistNode != null) {
                    currentKeys.addAll(splitGroupArtistToCompare(artistNode.toContentString()));
                    currentKeys.addAll(splitGroupArtistToCompare(extractGroupWithoutArtist(node)));
                } else {
                    currentKeys.addAll(splitGroupArtistToCompare(node.toContentString()));
                }
                if (CollectionUtil.containsAny(baseKeys, currentKeys)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String extractGroupWithoutArtist(Node bracketNode) {
        Node clone = bracketNode.clone();
        clone.setChildren(clone.getChildren().stream().limit(clone.getChildren().size() - 1).collect(Collectors.toList()));
        return clone.toContentString();
    }


    /**
     * 提取 group 和 artist
     */
    private static String[] extractGroupArtistFromNode(Node bracketNode) {
        if (bracketNode == null) {
            return null;
        }
        String artist = null;
        String group = null;
        if (CollectionUtil.isNotEmpty(bracketNode.getChildren())) {
            Node artistNode = findBracketNodeByPosition(bracketNode.getChildren(), Position.TAIL, BracketType.PARENTHESIS);
            if (artistNode != null) {
                artist = artistNode.toContentString();
            }
        }
        if (StringUtils.isNotEmpty(artist)) {
            group = extractGroupWithoutArtist(bracketNode);
        } else {
            artist = bracketNode.toContentString();
        }
        if (StringUtils.isEmpty(artist)) {
            return null;
        } else if (StringUtils.isEmpty(group)) {
            return new String[]{artist};
        } else {
            return new String[]{group, artist};
        }
    }

    public static List<String> splitGroupArtistToCompare(String groupArtist) {
        if (StringUtils.isBlank(groupArtist)) {
            return new ArrayList<>();
        }
        return Arrays.stream(groupArtist.split("、")).map(s -> s.trim().toUpperCase()).toList();
    }
}
