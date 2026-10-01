package io.github.Nyameph.nyaentworks.script;

import cn.hutool.core.collection.CollectionUtil;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import io.github.Nyameph.nyaentworks.manga.config.MangaProperties;
import io.github.Nyameph.nyaentworks.manga.consts.MangaArchiveUnitStatus;
import io.github.Nyameph.nyaentworks.manga.dict.MangaDictionary;
import io.github.Nyameph.nyaentworks.common.fileop.FileOpRecorder;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpLevel;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpModule;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpSource;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpType;
import io.github.Nyameph.nyaentworks.manga.entity.MangaArchiveUnit;
import io.github.Nyameph.nyaentworks.manga.entity.MangaData;
import io.github.Nyameph.nyaentworks.manga.service.MangaArchiveService;
import io.github.Nyameph.nyaentworks.manga.service.MangaDictService;
import io.github.Nyameph.nyaentworks.manga.util.MangaNameParser;
import io.github.Nyameph.nyaentworks.manga.util.MangaScoreDir;


import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * {@link MangaNameParser} 解析逻辑验证。
 * <p>词典已迁至数据库，故本类需要 Spring 上下文；每个方法开始时调用
 * {@link #newParser()} 从库刷新一次快照，之后全程复用该快照，
 * 保证同一批扫描结果的判定标准一致。
 */
@SpringBootTest
public class MangaNameParserTestUtil {

    @Autowired
    private MangaDictService mangaDictService;

    @Autowired
    private MangaArchiveService mangaArchiveService;

    @Autowired
    private MangaProperties mangaProperties;

    /**
     * 从库重新加载词典并返回解析器。
     * <p>每个测试方法开始时调用一次即可：网页端改动的词条在这里生效，
     * 而单次扫描过程中不会再变。
     */
    private MangaNameParser newParser() {
        MangaDictionary dict = mangaDictService.reload();
        System.out.println("词典已加载，版本 " + dict.getVersion()
                + (dict.getInvalidRegexValues().isEmpty() ? ""
                : "，⚠ 无法编译的正则 " + dict.getInvalidRegexValues()));
        return new MangaNameParser(dict);
    }

    /**
     * 扫描 目录下所有「内含图片」的漫画文件夹，
     * 用 {@link MangaNameParser} 判断其文件夹名是否符合规范（能匹配规则1或规则2），
     * 将不符合规范的文件夹完整路径按每行一个写入文件
     *
     * 😭操作顺序😭
     *   1. 先将 justTest 置为 true，跑一边，看首批输出的字符串中有没有应解析未解析的带括号字符，更新词典
     *   2. 查看控制台输出的 extra exhibit、extra parody，判断有哪些展会、原作、杂志，补入 manga_dict_entry 表
     *   2. 修正上述内容
     *   3. 将 justTest 置为 false，修改文件，查看控制台输出的带有 😭 的报错信息
     */
    @Test
    public void scanAndNormalizedFolderNames() {
        // 扫描根目录
        final String scanRoot = "F:\\NetdiskDownload\\#待压缩";
        // 是否允许移动文件
        final boolean justTest = true;


        ScanCheckFolderNameRes scanCheckFolderNameRes = scanAndCheckFolderNames(newParser(), scanRoot);
        List<MangaData> regularMangas = scanCheckFolderNameRes.regularMangas;
        boolean checkRes = printAndCheckScanRes(scanCheckFolderNameRes);
        if (!checkRes) {
            return;
        }

        System.out.println("===================================");

        // 将剩余文件夹重命名。一次脚本运行 = 一个批次：不包的话每条记录都落
        // source=UNKNOWN + 一条 warn（记录器要活动批次才知道「这是谁在动文件」），
        // 页面上「脚本」这个来源就永远筛不出来
        FileOpRecorder.batch(FileOpModule.MANGA, FileOpSource.SCRIPT, null, () -> {
            regularMangas.forEach(dataManga -> moveManga(dataManga, null, justTest));
            return null;
        });
    }

    private static boolean printAndCheckScanRes(ScanCheckFolderNameRes scanCheckFolderNameRes) {
        List<MangaData> regularMangas = scanCheckFolderNameRes.regularMangas;
        List<String> irregularPaths = scanCheckFolderNameRes.irregularPaths;
        Map<String, List<String>> extraExhibitMap = scanCheckFolderNameRes.extraExhibitMap;
        Map<String, List<String>> extraParodyMap = scanCheckFolderNameRes.extraParodyMap;

        System.out.println("=1==================================");
        System.out.println("扫描完成，漫画总数："+(irregularPaths.size() + regularMangas.size())+"，规范文件夹数量: " + regularMangas.size()+"，不规范文件夹数量: " + irregularPaths.size());
        irregularPaths.forEach(System.out::println);
        System.out.println("=2==================================");
        System.out.println("新增展会：" + extraExhibitMap.size());
        extraExhibitMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entity -> {
                    System.out.println("  \"" + entity.getKey() + "\", ");
                    for (int i = 0; i < entity.getValue().size(); i++) {
                        System.out.println("    -> " + entity.getValue().get(i));
                    }
                });
        System.out.println("新增原作：" + extraParodyMap.size());
        extraParodyMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entity -> {
                    System.out.println("  \"" + entity.getKey() + "\", ");
                    for (int i = 0; i < entity.getValue().size(); i++) {
                        System.out.println("    -> " + entity.getValue().get(i));
                    }
                });

        if(CollectionUtil.isNotEmpty(irregularPaths) || !extraExhibitMap.isEmpty() ||  !extraParodyMap.isEmpty()) {
            //当有不规范文件夹时先处理再进行后续操作
            return false;
        }
        System.out.println("=3==================================");
        return true;
    }

    private ScanCheckFolderNameRes scanAndCheckFolderNames(MangaNameParser parser, String scanRoot) {
        Path root = Paths.get(scanRoot);
        if (!Files.isDirectory(root)) {
            throw new RuntimeException("扫描根目录不存在: " + root);
        }
        List<MangaData> mangas = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isDirectory)
                    .filter(dir -> MangaNameParser.getFirstImage(dir) != null)
                    .forEach(dir -> {
                        MangaData manga = parser.parseDir(dir);
                        if(manga != null) {
                            mangas.add(manga);
                        } else {
                            System.out.println("有图非漫画：" + dir);
                        }
                    });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }


        List<String> irregularPaths = new ArrayList<>();
        List<MangaData> regularMangas = new ArrayList<>();
        MultiValueMap<String, String> extraExhibitMap = new LinkedMultiValueMap<>();
        MultiValueMap<String, String> extraParodyMap = new LinkedMultiValueMap<>();
        for (MangaData manga : mangas) {
            String folderPath = manga.getFolderPath();
            if (manga.getMatchedRule() == 0) {
                // matchedRule == 0 表示既不符合规则1也不符合规则2
                irregularPaths.add(folderPath);
            } else {
                regularMangas.add(manga);
                if(!manga.isExhibitInDict() && manga.getExhibit() != null) {
                    extraExhibitMap.add(manga.getExhibit(), folderPath);
                }
                if(!manga.isParodyInDict() && manga.getParody() != null) {
                    extraParodyMap.add(manga.getParody(), folderPath);
                }
            }
        }

        System.out.println("=====扫描完成：" + scanRoot);

        return new ScanCheckFolderNameRes(regularMangas, irregularPaths, extraExhibitMap,  extraParodyMap);
    }

    record ScanCheckFolderNameRes(List<MangaData> regularMangas, List<String> irregularPaths, Map<String, List<String>> extraExhibitMap, Map<String, List<String>> extraParodyMap) {}


    /**
     * 把多层单图直接提取到根目录（非常规方法，偶尔使用）
     * @throws IOException
     */
    @Test
    public void extractSubFile() throws IOException {
        Path rootPath = Path.of("F:\\NetdiskDownload\\#待看合集\\xxx");
        //保留几层子文件夹
        int retainDepth = 0;

        if (!Files.isDirectory(rootPath)) {
            throw new IllegalArgumentException("提供的路径不是目录: " + rootPath);
        }

        // 遍历所有文件（包括子目录）
        Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                // 获取相对路径
                Path relative = rootPath.relativize(file);
                // 按系统分隔符拆分
                String[] parts = relative.toString().split(Pattern.quote(File.separator));
                int depth = parts.length; // 例如 aa1/bb1/a.txt 深度为3

                // 只处理深度 > 2 的文件（即至少两层子目录之后）
                if (depth > retainDepth + 1) {
                    String retainDirs = Arrays.stream(parts).limit(retainDepth).collect(Collectors.joining("/"));
                    String newFileName = Arrays.stream(parts).skip(retainDepth).collect(Collectors.joining("_"));
                    List<String> middleDirs = new ArrayList<>();
                    String firstDir = parts[0];                 // 第一层子目录名

                    // 目标目录：根目录下的第一层子目录
                    Path targetDir;
                    if(StringUtils.isEmpty(retainDirs)) {
                        targetDir = rootPath;
                    } else {
                        targetDir = rootPath.resolve(firstDir);
                    }
                    if (!Files.exists(targetDir)) {
                        Files.createDirectories(targetDir);
                    }

                    Path targetFile = targetDir.resolve(newFileName);
                    // 执行移动（覆盖已存在文件）
                    if (!file.equals(targetFile)) {
                        Files.move(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
                        System.out.println("Moved: " + file + " -> " + targetFile);
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            // 不进入符号链接等，避免循环
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 扫描 {@code F:\MangaGroup} 下的归档目录，把社团/作者/标签同步入库，并报告重名冲突。
     * <p><b>这是一次写库操作</b>（表 {@code manga_archive_unit}、
     * {@code manga_archive_name}、{@code manga_tag}、{@code manga_tag_ref}），
     * 跑之前确认连的是本机 {@code nya_entworks}，且已执行
     * {@code db/nya_entworks.sql} 建表。不动任何文件。
     * <p>幂等：目录没变时重跑只有 updated，inserted/renamed/merged 应为 0。
     * <p><b>合并是唯一会删行的路径</b>：某归档目录消失、而另一个目录的社团/作者涵盖了它，
     * 就判为搬迁，被搬走那个 unit 的行会被删掉。若目录只是临时移走（外挂盘没挂上等），
     * 它的标签关联就跟着没了。所以每次跑都看一眼下面第 2 段的迁移明细，
     * 确认都是自己做过的目录调整。
     * {@code F:\MangaGroup} 的目录后要先跑这个。
     */
    @Test
    public void syncArchiveUnits() {
        MangaArchiveService.SyncResult result =
                mangaArchiveService.sync(MangaScoreDir.rootScoreMap(mangaProperties.getArchiveDir()));

        System.out.println("=1==================================");
        System.out.println("同步完成：新增 " + result.inserted()
                + "，更新 " + result.updated()
                + "，改名 " + result.renamed()
                + "，合并 " + result.merged()
                + "，失踪 " + result.missing());

        System.out.println("=2==================================");
        System.out.println("目录迁移：" + result.migrations().size() + "（合并会删行，请逐条核对）");
        result.migrations().forEach(m -> {
            System.out.println((m.merged() ? "  合并：" : "  改名：") + m.fromFolderPath());
            System.out.println("     -> " + m.toFolderPath());
        });

        System.out.println("=3==================================");
        System.out.println("不规范目录（未入库）：" + result.unparsedFolders().size());
        result.unparsedFolders().forEach(path -> System.out.println("  不规范：" + path));

        System.out.println("=4==================================");
        System.out.println("重名的群组或作者：" + result.conflicts().size());
        result.conflicts().forEach(conflict -> {
            System.out.println("😭 群组或作者重复：" + conflict.name());
            conflict.units().forEach(unit -> System.out.println("     " + unit.getFolderPath()));
        });
    }

    /**
     * 打印已归档目录及其标签，只读。用于核对 {@code 【…】} 里的标签是否都进了库。
     */
    @Test
    public void printArchiveUnits() {
        Map<MangaArchiveUnit, List<String>> units =
                mangaArchiveService.listUnitsWithTags(MangaArchiveUnitStatus.ACTIVE);
        System.out.println("已归档目录数：" + units.size());
        units.forEach((unit, tags) -> System.out.println("  " + unit.getScore()
                + "\t" + unit.getFolderName()
                + "\t标签 " + tags));
    }


    private static boolean moveManga(MangaData dataManga, Path newParentPath, boolean justTest) {
        Path originFullPath = Path.of(dataManga.getFolderPath());
        String newFolderName = dataManga.getNormalizedName();
        Path parentPath = originFullPath.getParent();
        Path newDirPath;
        if (newParentPath != null) {
            newDirPath = newParentPath.resolve(newFolderName);
        } else {
            newDirPath = parentPath.resolve(newFolderName);
        }
        if(!originFullPath.equals(newDirPath)) {
            if (Files.exists(newDirPath)) {
                System.out.println("😭 跳过重命名，目标文件夹已存在: " + "\n   " + originFullPath + "\n-> " + newDirPath);
                return false;
            } else  {
                try {
                    if(!justTest) {
                        Files.move(originFullPath, newDirPath);
                        FileOpRecorder.recordPath(FileOpModule.MANGA, FileOpType.MOVE,
                                FileOpLevel.SINGLE, "漫画文件夹规范名（脚本）",
                                originFullPath.toString(), newDirPath.toString());
                    }
                    System.out.println("重命名文件夹: " + "\n   " + originFullPath + "\n-> " + newDirPath);
                    dataManga.setFolderPath(newDirPath.toString());
                    return true;
                } catch (IOException e) {
                    System.out.println("😭 重命名失败: " + e.toString() +
                            "\n   " + originFullPath + "\n-> " + newDirPath);
                    return false;
                }
            }
        }
        return false;
    }


}
