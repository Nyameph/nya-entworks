package io.github.Nyameph.nyaentworks.script;

import cn.hutool.core.io.FileUtil;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import io.github.Nyameph.nyaentworks.manga.entity.MangaData;
import io.github.Nyameph.nyaentworks.manga.mapper.MangaDataMapper;
import io.github.Nyameph.nyaentworks.manga.service.MangaStoreService;
import io.github.Nyameph.nyaentworks.manga.util.MangaCbzUtil;
import io.github.Nyameph.nyaentworks.common.fileop.FileOpRecorder;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpLevel;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpModule;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpSource;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 清理「打包 cbz」留下的目录与库不一致 —— 可执行脚本，非单元测试。
 *
 * <p><b>为什么需要</b>：打包 cbz 时老漫画常带「只读」属性，删源目录会
 * {@code AccessDenied}（hutool {@code FileUtil.del} 底层是 {@code Files.delete}），
 * 结果 cbz 已生成、源目录却删不掉残留下来；也可能目录已删、库没跟上，或打包后
 * 用户又往目录里补了文件。这里按磁盘现状把库 {@code folder_path}/{@code file_type}
 * 收拾到跟 cbz 一致。
 *
 * <p><b>扫哪些、分三种处理</b>（只扫「旁边有同名 .cbz」的行）：
 * <ol>
 *   <li><b>有 cbz、没目录</b>：cbz 非空则直接改库指向 cbz（目录已不在，磁盘就是 cbz）。</li>
 *   <li><b>有 cbz、空目录</b>：删掉空目录，改库指向 cbz。</li>
 *   <li><b>有 cbz、非空目录</b>：解压旧 cbz 与目录合并（<b>同名以目录为准</b>）后重新压成
 *       cbz，替换旧 cbz、删目录，再改库指向 cbz。</li>
 * </ol>
 *
 * <p><b>用法</b>：先置 {@code justTest=true} 跑一遍看清单，确认后置 {@code false} 真实执行。
 * 需本机 MySQL 与 {@code F:\} 漫画目录，同 {@code MangaPackAllCbzUtil}。
 */
@SpringBootTest
public class MangaCleanCbzResidueUtil {

    @Autowired
    private MangaDataMapper mangaDataMapper;

    @Autowired
    private MangaStoreService.MangaDataWriter dataWriter;

    @Test
    public void clean() {
        boolean justTest = true;   // true = 只打印清单不落盘；false = 真实清理并更新库

        List<Target> targets = new ArrayList<>();
        for (MangaData row : mangaDataMapper.selectList(null)) {
            if (StringUtils.isBlank(row.getFolderPath())) {
                continue;
            }
            Path dir = Paths.get(row.getFolderPath());
            Path cbz = dir.resolveSibling(dir.getFileName().toString() + ".cbz");
            if (!Files.exists(cbz)) {
                continue;   // 没有同名 cbz，不是本脚本要处理的场景
            }

            if (!Files.isDirectory(dir)) {
                // 情况 1：有 cbz、没目录。cbz 非空才改库，空 cbz 是坏数据不碰
                if (size(cbz) > 0) {
                    targets.add(new Target(dir, cbz, Kind.NO_DIR));
                } else {
                    System.out.println("  跳过（cbz 为空）：" + cbz);
                }
            } else if (!dirHasFiles(dir)) {
                // 空目录：删掉即可，不用合并
                targets.add(new Target(dir, cbz, Kind.EMPTY_DIR));
            } else {
                // 情况 2：非空目录 → 解压 cbz 与目录合并（以目录为准）后重压
                targets.add(new Target(dir, cbz, Kind.MERGE));
            }
        }

        System.out.println("==============================================");
        System.out.println((justTest ? "【干跑】" : "【真实】") + "找到 " + targets.size() + " 处要处理：");
        targets.forEach(t -> System.out.println("  " + kindLabel(t.kind()) + " " + t.dir()));

        if (targets.isEmpty()) {
            System.out.println("没有要处理的");
            return;
        }
        if (justTest) {
            System.out.println();
            System.out.println("未动磁盘。确认清单无误后置 justTest=false 重跑");
            return;
        }

        // 只圈批次（source=SCRIPT）；cbz 合写不记（形态转换），只记「删空目录」那一条
        FileOpRecorder.batch(FileOpModule.MANGA, FileOpSource.SCRIPT, null, () -> {
        int done = 0;
        int failed = 0;
        for (Target t : targets) {
            try {
                switch (t.kind()) {
                    case NO_DIR -> {
                        // 磁盘已是 cbz（目录没了），直接让库跟上
                        dataWriter.updateFolderPath(t.dir().toString(), t.cbz().toString());
                        System.out.println("  改库指向 cbz：" + t.cbz());
                    }
                    case EMPTY_DIR -> {
                        if (!deleteDir(t.dir())) {
                            throw new IOException("删空目录失败（可能被占用）");
                        }
                        FileOpRecorder.recordPath(FileOpModule.MANGA, FileOpType.DELETE,
                                FileOpLevel.OTHER, "清理 cbz 残留：删空目录",
                                t.dir().toString(), null);
                        dataWriter.updateFolderPath(t.dir().toString(), t.cbz().toString());
                        System.out.println("  删空目录并改库：" + t.cbz());
                    }
                    case MERGE -> {
                        boolean dirDeleted = mergeAndRepack(t.dir(), t.cbz());
                        dataWriter.updateFolderPath(t.dir().toString(), t.cbz().toString());
                        System.out.println((dirDeleted ? "  合并重压完成：" : "  合并重压完成（原目录残留，需手动删）：")
                                + t.cbz());
                    }
                }
                done++;
            } catch (Exception e) {
                failed++;
                System.out.println("  失败 " + t.dir() + " —— " + e.getMessage());
            }
        }

        System.out.println("==============================================");
        System.out.println("处理 " + done + "/" + targets.size() + " 处，失败 " + failed + " 处");
        if (failed > 0) {
            System.out.println("失败的本原状保留（磁盘与库都没动），可单独处理后重跑");
        }
        System.out.println("跑完核对库中 folder_path / file_type 与磁盘 .cbz 一致");
        return null;
        });
    }

    /**
     * 情况 2 的核心：解压旧 cbz 到临时目录 → 目录文件覆盖（同名以目录为准）→ 重压
     * → 替换旧 cbz → 删目录。全程在临时目录/临时文件上做，重压失败时原状保留。
     *
     * @return true=目录已删；false=cbz 已替换但目录没删掉（数据在 cbz 里是对的）
     */
    private boolean mergeAndRepack(Path dir, Path cbz) throws IOException {
        Path tmp = Files.createTempDirectory("cbz-merge-");
        Path newCbz = cbz.resolveSibling("." + cbz.getFileName() + ".merge");
        try {
            unzipCbz(cbz, tmp);       // 旧 cbz 全部条目解压到临时目录
            copyDirOver(dir, tmp);    // 目录文件覆盖上去，同名以目录为准
            int count = MangaCbzUtil.packImages(tmp, newCbz);
            if (count == 0) {
                throw new IOException("合并后目录里没有图片");
            }
            // cbz 可能带只读，覆盖前先清掉
            try {
                Files.setAttribute(cbz, "dos:readonly", false);
            } catch (IOException | UnsupportedOperationException ignored) {
            }
            Files.move(newCbz, cbz, StandardCopyOption.REPLACE_EXISTING);
            return deleteDir(dir);    // cbz 已就位，删目录；删不掉返回 false 由调用方提示
        } finally {
            Files.deleteIfExists(newCbz);
            deleteDir(tmp);
        }
    }

    /** 解压 cbz 全部条目到 target（相对路径原样保留，跳过目录条目与越界名） */
    private static void unzipCbz(Path cbz, Path target) throws IOException {
        try (ZipFile zip = new ZipFile(cbz.toFile())) {
            var it = zip.entries();
            while (it.hasMoreElements()) {
                ZipEntry entry = it.nextElement();
                if (entry.isDirectory() || entry.getName().contains("..")) {
                    continue;
                }
                Path out = target.resolve(entry.getName());
                Files.createDirectories(out.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** 递归把 from 的文件复制到 to，同名覆盖（目录文件优先） */
    private static void copyDirOver(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(d)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) throws IOException {
                Files.copy(f, to.resolve(from.relativize(f)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** 目录里有没有图片文件（含子目录）。读不了按「有」处理，走合并更安全 */
    private static boolean dirHasFiles(Path dir) {
        try (var walk = Files.walk(dir)) {
            return walk.anyMatch(Files::isRegularFile);
        } catch (IOException e) {
            return true;
        }
    }

    /** 文件大小；读不到按 0 处理（当作空 cbz，跳过） */
    private static long size(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    /** 清只读后删目录。删不掉（被占用/清不了只读）返回 false */
    private static boolean deleteDir(Path dir) {
        clearReadOnly(dir);
        try {
            return FileUtil.del(dir.toFile());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 递归清目录里所有条目的只读属性 */
    private static void clearReadOnly(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.forEach(p -> {
                try {
                    Files.setAttribute(p, "dos:readonly", false);
                } catch (IOException | UnsupportedOperationException ignored) {
                    // 清不掉就让它去，删除时自然会报错
                }
            });
        } catch (IOException ignored) {
            // 遍历不了就算了，删除时自然会报错
        }
    }

    private record Target(Path dir, Path cbz, Kind kind) {
    }

    private enum Kind { NO_DIR, EMPTY_DIR, MERGE }

    private static String kindLabel(Kind k) {
        return switch (k) {
            case NO_DIR -> "【有cbz无目录】";
            case EMPTY_DIR -> "【空目录残留】";
            case MERGE -> "【合并重压】";
        };
    }
}
