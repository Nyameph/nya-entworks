package io.github.Nyameph.nyaentworks.manga.service;

import cn.hutool.core.io.FileUtil;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import io.github.Nyameph.nyaentworks.manga.config.MangaProperties;
import io.github.Nyameph.nyaentworks.manga.consts.MangaScoreSource;
import io.github.Nyameph.nyaentworks.manga.dict.MangaDictionary;
import io.github.Nyameph.nyaentworks.manga.entity.MangaData;
import io.github.Nyameph.nyaentworks.manga.service.MangaArchiveService.ArchiveMatch;
import io.github.Nyameph.nyaentworks.manga.service.MangaArchiveService.NameTargets;
import io.github.Nyameph.nyaentworks.manga.util.MangaCbzUtil;
import io.github.Nyameph.nyaentworks.manga.util.MangaFolderName;
import io.github.Nyameph.nyaentworks.manga.util.MangaNameParser;
import io.github.Nyameph.nyaentworks.manga.util.MangaScoreDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 新漫画的扫描（文档 4.8）。
 *
 * <p><b>新漫画不入库，每次现扫。</b>这一层的漫画名字、评分、标签都还在变
 * （改名、打分、规范化命名都是常规操作），存一份镜像就要维护一致性，
 * 而扫一次只要秒级。落库发生在「存储」那一刻 —— 那时漫画已压缩、名字定下来，
 * 成为「已归档」或「未归档」，见 {@link MangaStoreService}。
 *
 * <p><b>评分存在目录名里。</b>{@code #待看} 下有四个 {@code #<评分>-<名称>} 分区目录，
 * 打分就是把漫画移进对应分区（沿用 {@code scanAndArchiveMangas} 的既有做法）。
 * 直接放在 {@code #待看} 根下的漫画即「未评分」。这样新漫画不入库也存得下评分，
 * 且在资源管理器里就看得见。
 *
 * <p>扫描全程复用同一个词典快照与同一份归档查找表（文档五「技术选型」的要求）：
 * 逐本重查会把一次扫描变成上千次全表查询，而中途词典变了会让同批结果判定标准不一致。
 */
@Service
@RequiredArgsConstructor
public class MangaNewService {

    private final MangaProperties properties;
    private final MangaDictService dictService;
    private final MangaArchiveService archiveService;

    /**
     * 一本新漫画。字段分三组：解析结果、磁盘事实、闸门判定。
     *
     * @param folderPath      目录全路径，前端拿它当 id（新漫画不入库，没有数字 id）
     * @param folderName      当前目录名
     * @param normalizedName  规范化命名后的目录名。与 {@code folderName} 相同表示已规范
     * @param score           评分，来自所在分区目录；未评分为 {@code null}
     * @param scoreSource     评分是自己的还是继承归档目录的。只有已归档的漫画有值，
     *                        新漫画与未归档都是 {@code null}（还没落到「继承」那层）
     * @param fileCount       目录下的文件数（含子目录），卡片上显示
     * @param imageCount      其中的图片数。压缩只动图片，两个数差得多说明混了别的东西
     * @param coverFile       封面文件名，取首张图
     * @param matchedRule     命中的命名规则，0 表示不规范
     * @param irregularReason {@code matchedRule == 0} 时为什么没匹配上，给人看的线索
     * @param extraExhibit    取到了展会但词典里没有，需要补录或改名
     * @param extraParody     同上，原作
     * @param archiveMatch    归档匹配结果，决定存储后是归档还是落散漫
     * @param destFolder      归档提示用的去向目录：命中归档时是其目录全路径，
     *                        否则是散漫目录的评分分区（未评分时按最低分算占位）。
     *                        只是给人看的，真正去向在存储时用实际评分重算
     * @param blockers        存储闸门：非空表示不能存储，每条都是一句人话
     * @param storable        能不能存储，即 {@code blockers} 为空。<b>是记录组件而不是
     *                        派生方法</b>：Jackson 3 只序列化记录组件，写成方法的话
     *                        前端拿不到这个字段，只能自己按 blockers 再判一遍
     * @param needsRename     规范化命名会不会改动目录名，同上
     */
    public record NewManga(String folderPath, String folderName, String normalizedName,
                          Integer score, MangaScoreSource scoreSource,
                          int fileCount, int imageCount, String coverFile,
                          String groupName, String artist, String title,
                          String exhibit, String parody, String magazine, String dateTag,
                          int matchedRule, String irregularReason,
                          String extraExhibit, String extraParody,
                          ArchiveMatch archiveMatch, String destFolder, List<String> blockers,
                          boolean storable, boolean needsRename, Long mangaId,
                          List<String> tags) {

        /** 各字段齐了之后算出 storable 与 needsRename，避免调用方各算一遍 */
        static NewManga of(String folderPath, String folderName, String normalizedName,
                           Integer score, MangaScoreSource scoreSource,
                           int fileCount, int imageCount, String coverFile,
                           String groupName, String artist, String title,
                           String exhibit, String parody, String magazine, String dateTag,
                           int matchedRule, String irregularReason,
                           String extraExhibit, String extraParody,
                           ArchiveMatch archiveMatch, String destFolder, List<String> blockers,
                           Long mangaId) {
            return new NewManga(folderPath, folderName, normalizedName, score, scoreSource,
                    fileCount, imageCount, coverFile, groupName, artist, title, exhibit, parody,
                    magazine, dateTag, matchedRule, irregularReason, extraExhibit,
                    extraParody, archiveMatch, destFolder, blockers,
                    blockers.isEmpty(),
                    normalizedName != null && !normalizedName.equals(folderName),
                    mangaId, null);
        }

        /**
         * 注入回显标签（列表接口在现扫后批量补上，卡片与阅读页只读不判）。
         * <p>值由 {@code MangaArchiveService#withDisplayTags} 算好：独立标签
         * （MANGA_DATA）优先，没有独立标签就用父级标签（归档目录 ARCHIVE_UNIT）。
         * {@code of} 构造时恒为 {@code null}，要回显必须先过那一层。
         */
        NewManga withTags(List<String> tags) {
            return new NewManga(folderPath, folderName, normalizedName, score, scoreSource,
                    fileCount, imageCount, coverFile, groupName, artist, title, exhibit, parody,
                    magazine, dateTag, matchedRule, irregularReason, extraExhibit, extraParody,
                    archiveMatch, destFolder, blockers, storable, needsRename, mangaId, tags);
        }
    }

    /**
     * 一次扫描的结果。
     *
     * @param dictVersion   本次用的词典版本。补录词条后版本会变，页面据此提示重扫
     *                      （文档 1.1 的词典版本一致性）
     * @param scoreDirs     评分 → 分区目录名，页面画评分按钮用
     * @param extraExhibits 未识别展会 → 出现在哪些目录，补录的主入口（文档 4.2.1）
     * @param extraParodies 同上，原作
     * @param noArchiveDb   库里没有任何归档目录，此时所有漫画都会被判成未归档
     */
    public record ScanResult(List<NewManga> mangas, long dictVersion,
                             Map<Integer, String> scoreDirs,
                             Map<String, List<String>> extraExhibits,
                             Map<String, List<String>> extraParodies,
                             boolean noArchiveDb, String rootPath) {
    }

    /**
     * 改名预演的判定结果：一个<b>还没落盘</b>的新目录名匹配哪条规则、
     * 有没有未识别展会/原作。阅读页改名输入的实时反馈。
     *
     * @param matchedRule     命中的规则，0 表示不规范
     * @param irregularReason 不规范时为什么，给人看的线索
     * @param extraExhibit    未识别展会（取到了但词典里没有）
     * @param extraParody     未识别原作（同上）
     * @param normalizedName  这个新名字规范化后的目录名
     */
    public record NameCheck(int matchedRule, String irregularReason,
                            String extraExhibit, String extraParody, String normalizedName) {
    }

    /**
     * 扫描新漫画根目录。只读，不动磁盘也不动库。
     * <p>扫的是 {@code #待看} 下的漫画目录：根下直接放的算未评分，
     * 放在 {@code #<评分>-…} 分区目录里的带评分。
     */
    public ScanResult scan() {
        String root = properties.getNewDir();
        requireDir(root, "新漫画根目录");

        MangaDictionary dict = dictService.current();
        MangaNameParser parser = new MangaNameParser(dict);
        NameTargets targets = archiveService.loadNameTargets(
                MangaScoreDir.rootScoreMap(properties.getArchiveDir()));

        Map<Integer, Path> scoreDirs = MangaScoreDir.scoreDirs(root);
        List<NewManga> mangas = new ArrayList<>();

        // 根目录下直接放着的（未评分）
        for (File dir : listSubDirs(root)) {
            if (MangaScoreDir.parseScore(dir.getName()) != null) {
                continue;
            }
            NewManga manga = parseManga(parser, dir.toPath(), null, targets);
            if (manga != null) {
                mangas.add(manga);
            }
        }
        // 各评分分区里的
        scoreDirs.forEach((score, dir) -> {
            for (File sub : listSubDirs(dir.toString())) {
                NewManga manga = parseManga(parser, sub.toPath(), score, targets);
                if (manga != null) {
                    mangas.add(manga);
                }
            }
        });

        mangas.sort(Comparator.comparing(NewManga::folderName));

        Map<Integer, String> scoreDirNames = new LinkedHashMap<>();
        scoreDirs.forEach((score, path) -> scoreDirNames.put(score, path.getFileName().toString()));

        return new ScanResult(mangas, dict.getVersion(), scoreDirNames,
                collectExtras(mangas, true), collectExtras(mangas, false),
                targets.isEmpty(), root);
    }

    /**
     * 改名预演：判断一个<b>还没落盘</b>的新目录名是否匹配规则、有没有未识别展会/原作。
     * <p>与 {@link #toNewManga} 用同一套解析与词典判定 —— 前端只画不判，
     * 否则「预览说能存、改名后又不能」又会冒出来。新名字只替换最后一段，
     * 父目录不变，规则2、3 的补全逻辑照旧走父级目录名。
     */
    public NameCheck checkName(String folderPath, String newFolderName) {
        Path dir = underAnyRoot(folderPath);
        String name = MangaFolderName.requireSimple(newFolderName);

        MangaDictionary dict = dictService.current();
        MangaData parsed = new MangaNameParser(dict).parseCandidateName(name, dir.getParent());
        if (parsed == null) {
            // 名字本身是 [社团 (作者)] 形态 → 那是合集目录，不是单本
            return new NameCheck(0, "这个名字是 [社团 (作者)] 合集目录形态，不是单本漫画名",
                    null, null, null);
        }
        String extraExhibit = !parsed.isExhibitInDict() && parsed.getExhibit() != null
                ? parsed.getExhibit() : null;
        String extraParody = !parsed.isParodyInDict() && parsed.getParody() != null
                ? parsed.getParody() : null;
        // irregularReason 要读目录名与父目录名，用假路径一样能算
        String irregularReason = parsed.getMatchedRule() == 0
                ? irregularReason(dir.getParent().resolve(name), parsed) : null;
        return new NameCheck(parsed.getMatchedRule(), irregularReason,
                extraExhibit, extraParody, parsed.getNormalizedName());
    }

    /**
     * 校验路径落在新漫画、合集或未归档根目录下（改名预演对这三处都有用）。
     * 未归档的改名同样要预演「新名字匹配哪条规则、有无未识别展会/原作」。
     */
    private Path underAnyRoot(String folderPath) {
        for (String root : new String[]{properties.getNewDir(), properties.getCollectionDir(),
                properties.getUnarchivedDir()}) {
            try {
                return requireUnder(folderPath, root, "漫画");
            } catch (IllegalArgumentException e) {
                // 逐个根试，都不在才报错
            }
        }
        throw new IllegalArgumentException("不在新漫画/合集/未归档根目录下：" + folderPath);
    }

    /**
     * 解析一个漫画目录。
     * <p>返回 {@code null} 表示这个目录不是漫画：{@code parseDir} 对「本身是
     * {@code [社团 (作者)]} 形态的中间目录」与「不含图片」都返回 null。
     * 前者是合集目录，后者是杂物目录，都不该出现在新漫画列表里。
     */
    private NewManga parseManga(MangaNameParser parser, Path dir, Integer score,
                                NameTargets targets) {
        MangaData parsed = parser.parseDir(dir);
        if (parsed == null) {
            return null;
        }
        return toNewManga(parsed, dir, score, targets);
    }

    /**
     * 把解析结果与磁盘事实、闸门判定拼成一张卡片要的全部信息。
     * <p>包可见：{@code MangaUnarchivedService} 读库里的未归档行时，
     * 对仍存在于磁盘的目录同样要产出卡片字段，走同一套构造才不至于两处各写一遍。
     */
    NewManga toNewManga(MangaData parsed, Path dir, Integer score, NameTargets targets) {
        return toNewManga(parsed, dir, score, null, targets, null, null, null);
    }

    /** 带评分来源的版本：归档目录下的漫画用它，来源取自库里那行（SELF / INHERIT_ARCHIVE） */
    NewManga toNewManga(MangaData parsed, Path dir, Integer score, MangaScoreSource scoreSource,
                        NameTargets targets, Long mangaId,
                        Integer knownFileCount, Integer knownImageCount) {
        boolean cbz = MangaCbzUtil.isCbz(dir);
        int fileCount;
        int imageCount;
        if (knownFileCount != null && knownImageCount != null) {
            // 入库行已带统计值（扫描/存储时算好落库），直接读，省一遍目录遍历
            fileCount = knownFileCount;
            imageCount = knownImageCount;
        } else {
            FileCounts counts = countFiles(dir);
            fileCount = counts.fileCount();
            imageCount = counts.imageCount();
        }

        String irregularReason = parsed.getMatchedRule() == 0
                ? irregularReason(dir, parsed) : null;
        String extraExhibit = !parsed.isExhibitInDict() && parsed.getExhibit() != null
                ? parsed.getExhibit() : null;
        String extraParody = !parsed.isParodyInDict() && parsed.getParody() != null
                ? parsed.getParody() : null;

        // 归档匹配只看名字；评分不再参与归档判定（「高分映射入低分」已去掉），
        // 传进来的目的只是让存储时用实际评分再算一遍归档目标。
        // targets 为 null 表示归档作者合集弹窗：漫画已在归档目录里，去向与提示都无意义，
        // 直接给空匹配，省一次 loadNameTargets 的全量查表
        ArchiveMatch match = targets == null
                ? new ArchiveMatch(null, List.of())
                : archiveService.matchArchive(parsed.getGroupName(), parsed.getArtist(),
                        score == null ? MangaScoreDir.SCORES[MangaScoreDir.SCORES.length - 1] : score,
                        targets);

        List<String> blockers = new ArrayList<>();
        // cbz 不走存储管线：压缩只能处理目录里的散图，cbz 是打包好的成品。
        // 要归档就手动放进归档根，扫描/封面/阅读/评分/改名都照常
        if (cbz) {
            blockers.add("cbz 不走存储流程，要归档请手动放进归档目录");
        }
        if (score == null) {
            blockers.add("还没评分");
        }
        if (parsed.getMatchedRule() == 0) {
            blockers.add("目录名不规范，规范化命名不出结果");
        }
        if (extraExhibit != null) {
            blockers.add("展会「" + extraExhibit + "」不在词典里");
        }
        if (extraParody != null) {
            blockers.add("原作「" + extraParody + "」不在词典里");
        }
        if (imageCount == 0) {
            blockers.add("目录里没有图片，没什么可压缩的");
        }

        // 归档提示里的去向：命中归档目录就是它，否则落散漫的评分分区。
        // 未评分时按最低分算占位 —— 反正闸门挡着存不了，真要存时后端用实际评分重算。
        // 用 resolveScoreDir 而不是要求分区已存在：这一档没建时打分（归档）会自己建出来
        // （MangaStoreService#commitOne），提示里就该写那个将来会有的路径；
        // 用会抛的那个版本则会在「未归档根下缺一档」时把整页扫描带崩
        String destFolder = null;
        if (targets != null) {
            destFolder = match.target() != null
                    ? match.target().folderPath()
                    : MangaScoreDir.resolveScoreDir(properties.getUnarchivedDir(),
                    score == null ? MangaScoreDir.SCORES[MangaScoreDir.SCORES.length - 1] : score)
                    .toString();
        }

        return NewManga.of(dir.toString(), dir.getFileName().toString(),
                parsed.getNormalizedName(), score, scoreSource, fileCount, imageCount,
                parsed.getCoverFile(), parsed.getGroupName(), parsed.getArtist(),
                parsed.getTitle(), parsed.getExhibit(), parsed.getParody(),
                parsed.getMagazine(), parsed.getDateTag(),
                parsed.getMatchedRule(), irregularReason,
                extraExhibit, extraParody, match, destFolder, blockers, mangaId);
    }

    private static boolean isImage(File file) {
        String name = StringUtils.lowerCase(file.getName());
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
                || name.endsWith(".gif") || name.endsWith(".bmp") || name.endsWith(".webp");
    }

    /** 一次目录遍历的产物：文件总数 + 其中的图片数 */
    record FileCounts(int fileCount, int imageCount) {
    }

    /**
     * 统计目录（或 cbz）下的文件数与图片数，目录只递归遍历一遍。
     * <p>{@link #toNewManga} 读侧与归档/存储写侧共用，保证口径一致、不各写一遍。
     */
    static FileCounts countFiles(Path dir) {
        if (MangaCbzUtil.isCbz(dir)) {
            return new FileCounts(MangaCbzUtil.entryCount(dir), MangaCbzUtil.imageEntryCount(dir));
        }
        List<File> files = FileUtil.loopFiles(dir.toFile());
        return new FileCounts(files.size(),
                (int) files.stream().filter(MangaNewService::isImage).count());
    }

    /**
     * 「不规范」要给出为什么没匹配上，而不只是路径（文档 4.3 的要求）。
     * <p>线索按 {@code parseByRule} 的判定顺序给：首个方括号是时间标签 → 规则2；
     * 存在头部方括号 → 规则1；能从父级补全作者且有展会/原作/杂志 → 规则3。
     */
    private static String irregularReason(Path dir, MangaData parsed) {
        String folderName = dir.getFileName().toString();
        boolean hasHeadBracket = folderName.trim().startsWith("[")
                || folderName.trim().startsWith("［");
        Path parent = dir.getParent();
        boolean parentIsGroupArtist = parent != null && parent.getFileName() != null
                && MangaNameParser.parentGroupArtistNode(
                parent.getFileName().toString().trim()) != null;

        if (!hasHeadBracket && !parentIsGroupArtist) {
            return "没有头部方括号（规则1 要 [社团 (作者)]），"
                    + "父级目录也不是 [社团 (作者)] 形态、无法补全作者（规则2、3 的前提）";
        }
        if (!hasHeadBracket) {
            return "没有头部方括号，虽然父级目录能补全作者，"
                    + "但也没取到展会/原作/杂志中的任何一项（规则3 的前提）";
        }
        if (StringUtils.isBlank(parsed.getTitle())) {
            return "有头部方括号但取不到标题";
        }
        return "有头部方括号，但社团/作者块解析不出来（括号可能不成对）";
    }

    /** 未识别展会/原作 → 出现在哪些目录。补录界面按出现次数排先后 */
    private static Map<String, List<String>> collectExtras(List<NewManga> mangas, boolean exhibit) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (NewManga manga : mangas) {
            String value = exhibit ? manga.extraExhibit() : manga.extraParody();
            if (value != null) {
                result.computeIfAbsent(value, k -> new ArrayList<>()).add(manga.folderPath());
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 新作者合集（文档 4.8 后半 / 概述「下载新作者合集」）
    // ------------------------------------------------------------------

    /**
     * 一个作者合集：{@code #待看合集} 下的一个子目录，目录名形如 {@code [社团 (作者)]}。
     * <p>与单本的区别在概述里写得很明确：合集整体评分、整体打标签、整体规范化命名，
     * <b>此时不视为对单个漫画评分</b>（存库时落 {@code INHERIT_ARCHIVE}）。
     * 合集里的每本仍可单独改名/删除，那部分复用单本的操作。
     *
     * @param folderName    合集目录名
     * @param groupArtistOk 目录名是不是 {@code [社团 (作者)]} 形态。不是的话
     *                      里面的漫画补全不了作者，整个合集都归不了档
     * @param archiveMatch  按合集目录名的社团/作者算出的归档匹配。与已归档作者重名时
     *                      要引到合并冲突流程去（文档 4.6，界面未做）
     * @param blockers      整体存储的闸门。比单本多一条「无新展会/原作」——
     *                      概述对合集额外要求了这一项
     */
    public record MangaCollection(String folderPath, String folderName, String groupName,
                                 String artist, boolean groupArtistOk, Integer score,
                                 int mangaCount, int fileCount, String coverPath,
                                 List<NewManga> mangas, ArchiveMatch archiveMatch,
                                 List<String> blockers, boolean storable) {

        /** storable 是记录组件而非派生方法，理由同 {@link NewManga} */
        static MangaCollection of(String folderPath, String folderName, String groupName,
                                  String artist, boolean groupArtistOk, Integer score,
                                  int mangaCount, int fileCount, String coverPath,
                                  List<NewManga> mangas, ArchiveMatch archiveMatch,
                                  List<String> blockers) {
            return new MangaCollection(folderPath, folderName, groupName, artist,
                    groupArtistOk, score, mangaCount, fileCount, coverPath, mangas,
                    archiveMatch, blockers, blockers.isEmpty());
        }
    }

    /**
     * 扫描合集根目录。只读。
     * <p>合集的评分同样存在目录名里，但位置不同：合集目录直接放在
     * {@code #待看合集} 下（那里没有评分分区），所以评分只能来自「合集目录名里的
     * {@code 【…】} 标签块」放不下的东西 —— 这里的做法是<b>合集评分不落磁盘</b>，
     * 由前端在存储表单里当场选。理由：合集是一次性的，扫出来就要处理掉，
     * 没有「攒着以后再说」的阶段，不需要把评分持久化。
     */
    public List<MangaCollection> scanCollections() {
        String root = properties.getCollectionDir();
        requireDir(root, "新作者合集根目录");

        MangaDictionary dict = dictService.current();
        MangaNameParser parser = new MangaNameParser(dict);
        NameTargets targets = archiveService.loadNameTargets(
                MangaScoreDir.rootScoreMap(properties.getArchiveDir()));

        List<MangaCollection> result = new ArrayList<>();
        for (File dir : listSubDirs(root)) {
            result.add(parseCollection(parser, dir.toPath(), targets));
        }
        return result;
    }

    /** 单个合集，含它下面的漫画 */
    public MangaCollection collection(String folderPath) {
        Path dir = requireUnder(folderPath, properties.getCollectionDir(), "合集");
        MangaDictionary dict = dictService.current();
        return parseCollection(new MangaNameParser(dict), dir,
                archiveService.loadNameTargets(
                        MangaScoreDir.rootScoreMap(properties.getArchiveDir())));
    }

    private MangaCollection parseCollection(MangaNameParser parser, Path dir,
                                            NameTargets targets) {
        String folderName = dir.getFileName().toString();
        MangaNameParser.ArchiveFolderInfo info =
                MangaNameParser.parseArchiveFolderName(folderName);
        boolean groupArtistOk = info != null && StringUtils.isNotBlank(info.artistNames());

        // 合集下的漫画：parseDir 会顺着父级目录名补全社团/作者（规则2、3），
        // 所以这里不必自己往下传，只要合集目录名是 [社团 (作者)] 形态
        List<NewManga> mangas = new ArrayList<>();
        collectMangasUnder(parser, dir, targets, mangas);
        mangas.sort(Comparator.comparing(NewManga::folderName));

        List<File> allFiles = FileUtil.loopFiles(dir.toFile());
        ArchiveMatch match = info == null
                ? new ArchiveMatch(null, List.of())
                : archiveService.matchArchive(info.groupName(), info.artistNames(),
                MangaScoreDir.SCORES[MangaScoreDir.SCORES.length - 1], targets);

        List<String> blockers = new ArrayList<>();
        if (!groupArtistOk) {
            blockers.add("合集目录名不是 [社团 (作者)] 形态，取不到作者，归不了档");
        }
        if (mangas.isEmpty()) {
            blockers.add("合集里没有漫画（没有含图片的子目录）");
        }
        long irregular = mangas.stream().filter(m -> m.matchedRule() == 0).count();
        if (irregular > 0) {
            blockers.add("有 " + irregular + " 本目录名不规范");
        }
        // 概述对合集额外要求「无新展会/原作」才能存储
        long newDictItems = mangas.stream()
                .filter(m -> m.extraExhibit() != null || m.extraParody() != null).count();
        if (newDictItems > 0) {
            blockers.add("有 " + newDictItems + " 本带未识别的展会/原作，合集要求先补录词典");
        }

        String cover = mangas.isEmpty() ? null
                : Paths.get(mangas.getFirst().folderPath())
                .resolve(StringUtils.defaultString(mangas.getFirst().coverFile())).toString();

        // 合集内单本不入库（无独立标签），回显父级标签（归档匹配命中的目录标签）
        mangas = archiveService.withDisplayTags(mangas, null);

        return MangaCollection.of(dir.toString(), folderName,
                info == null ? null : info.groupName(),
                info == null ? null : info.artistNames(),
                groupArtistOk, null, mangas.size(), allFiles.size(), cover,
                mangas, match, blockers);
    }

    /**
     * 递归找合集下的漫画目录。
     * <p>合集里常见两层结构（{@code 合集/某本/内页} 甚至更深，实测有三层），
     * 所以不能只看一级子目录。命中一个漫画目录后不再往下钻 ——
     * 漫画里的子目录是分卷或杂物，不是另一本漫画。
     */
    private void collectMangasUnder(MangaNameParser parser, Path dir, NameTargets targets,
                                    List<NewManga> out) {
        for (File sub : listSubDirs(dir.toString())) {
            MangaData parsed = parser.parseDir(sub.toPath());
            if (parsed != null) {
                out.add(toNewManga(parsed, sub.toPath(), null, targets));
                continue;
            }
            collectMangasUnder(parser, sub.toPath(), targets, out);
        }
    }

    // ------------------------------------------------------------------
    // 共用的小工具，合集与存储那边也要用
    // ------------------------------------------------------------------

    /**
     * 校验路径确实在某个根目录下，防目录穿越。
     * <p>前端拿路径当 id 传回来（新漫画不入库，没有数字 id），所以每个写操作
     * 都要确认它落在自己该管的根目录里 —— 否则一个 {@code ..} 就能让「删除」
     * 删到别处去。
     */
    static Path requireUnder(String path, String rootPath, String what) {
        if (StringUtils.isBlank(path)) {
            throw new IllegalArgumentException(what + "路径不能为空");
        }
        Path target = Paths.get(path).toAbsolutePath().normalize();
        Path root = Paths.get(rootPath).toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IllegalArgumentException(what + "不在 " + rootPath + " 下：" + path);
        }
        return target;
    }

    /**
     * 一级子目录，外加 {@code .cbz} 文件 —— cbz 当漫画单元，与目录同等参与扫描。
     * <p>是本模块统一的枚举入口（新漫画/合集/未归档/已归档扫描都走它），改这一处
     * cbz 就在所有列表里可见。评分分区目录（{@code #<评分>-…}）仍是目录，照旧被收进来，
     * 由调用方按 {@link MangaScoreDir#parseScore} 过滤。
     */
    static List<File> listSubDirs(String rootPath) {
        File[] children = new File(rootPath).listFiles(f ->
                f.isDirectory() || MangaCbzUtil.isCbz(f.toPath()));
        if (children == null) {
            return List.of();
        }
        List<File> dirs = new ArrayList<>(List.of(children));
        dirs.sort(Comparator.comparing(File::getName));
        return dirs;
    }

    static void requireDir(String path, String what) {
        if (StringUtils.isBlank(path)) {
            throw new IllegalStateException(what + "没有配置（application.yaml 的 nya-entworks.manga）");
        }
        if (!Files.isDirectory(Paths.get(path))) {
            throw new IllegalStateException(what + "不存在：" + path
                    + "。外挂盘没挂上？挂上再来，否则扫描结果会是空的");
        }
    }
}
