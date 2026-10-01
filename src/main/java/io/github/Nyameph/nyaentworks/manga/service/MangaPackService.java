package io.github.Nyameph.nyaentworks.manga.service;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import io.github.Nyameph.nyaentworks.common.file.RecycleBin;
import io.github.Nyameph.nyaentworks.manga.config.MangaProperties;
import io.github.Nyameph.nyaentworks.manga.util.MangaCbzUtil;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 把漫画目录就地打包成 {@code .cbz}（原地替换：同目录生成同名 cbz，删源目录）。
 *
 * <p>评分仍在分区目录名里、标签与归档归属都不动，所以打包只需要改 {@code manga_data}
 * 的 {@code folder_path} —— {@link MangaStoreService.MangaDataWriter#updateFolderPath}
 * 按 {@code folder_path} 认行、不区分 status，对 UNARCHIVED / ARCHIVED 都适用，
 * 封面也顺带用 {@code getFirstImage}（对 cbz 返回首个图片条目名）重算。
 *
 * <p>磁盘先动、库后动，且打包不可回滚：先写临时 zip → 原子 move 到目标 cbz → 删源目录
 * → 最后更新库。删源目录失败时<b>不回滚</b>删掉刚产出的 cbz —— cbz 是完整副本，
 * 删掉它等于把「删到一半的源目录里剩下的图片」也一起丢掉；保留 cbz、报错让人手动收尾。
 */
@Service
@RequiredArgsConstructor
public class MangaPackService {

    private final MangaProperties properties;
    private final MangaStoreService.MangaDataWriter dataWriter;

    /** 打包任务参数（异步重跑靠它还原被选中的目录清单） */
    public record PackParams(List<String> folderPaths) {
    }

    public record PackItem(String folderPath, String cbzPath, String error) {
    }

    public record PackResult(int total, int packed, int failed, List<PackItem> items) {
    }

    /** 批量打包：循环 {@link #packOne}，一本失败不中断整批，逐本记明细 */
    public PackResult packBatch(List<String> folderPaths, AsyncTaskContext context) {
        if (folderPaths == null || folderPaths.isEmpty()) {
            throw new IllegalArgumentException("没有选中要打包的漫画");
        }
        List<PackItem> items = new ArrayList<>();
        int packed = 0;
        int failed = 0;
        int total = folderPaths.size();
        for (int i = 0; i < total; i++) {
            String path = folderPaths.get(i);
            context.progress(i, total, fileNameOf(path));
            try {
                String cbz = packOne(path);
                items.add(new PackItem(path, cbz, null));
                packed++;
            } catch (Exception e) {
                items.add(new PackItem(path, null, e.getMessage()));
                failed++;
                context.log(path + " → " + e.getMessage());
            }
        }
        context.progress(total, total, null);
        return new PackResult(total, packed, failed, items);
    }

    /** 单本打包：校验 → 打包到同目录临时文件 → 原子改名 → 删源目录 → 更新库 */
    private String packOne(String folderPath) {
        Path dir = requirePackableDir(folderPath);
        String cbz = packDir(dir);
        // 库后动：folder_path 指向 cbz，封面顺带重算
        dataWriter.updateFolderPath(dir.toString(), cbz);
        return cbz;
    }

    /**
     * 纯磁盘动作：把目录打包成同目录同名 {@code .cbz}，把<b>源目录送进回收站</b>
     * （2026-09-30 起；原先是真的删掉），返回 cbz 路径。
     * <p>不更新库、不校验目录归属 —— 供存储归档流程在压缩图片之后打包用（那时目录还在
     * {@code #待看} 下或刚搬进归档根、尚未入库），也供 {@link #packOne} 复用。
     * <p>源目录没能进回收站时<b>不回滚</b>删掉刚产出的 cbz（见类注释）：cbz 是完整副本，
     * 删掉它等于把「删到一半的源目录里剩下的图片」也一起丢掉。
     */
    public String packDir(Path dir) {
        Path target = dir.resolveSibling(dir.getFileName().toString() + ".cbz");
        if (Files.exists(target)) {
            throw new IllegalStateException("目标已存在，先手工处理：" + target);
        }
        Path tmp = dir.resolveSibling("." + dir.getFileName().toString() + ".cbz.part");
        try {
            int count = MangaCbzUtil.packImages(dir, tmp);
            if (count == 0) {
                Files.deleteIfExists(tmp);
                throw new IllegalStateException("目录里没有图片：" + dir);
            }
            Files.move(tmp, target);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 临时文件删不掉也不影响结论，错误照抛
            }
            throw new IllegalStateException("打包失败：" + e.getMessage(), e);
        }
        // 磁盘先动：cbz 已就位，源目录送进回收站。这是用户 2026-09-30 点名的唯一一处
        // 「机械清理也进回收站」—— 怕 cbz 将来打不开、还想把原图捞回来。
        // 只读属性对回收站本来不碍事，但仍然先清掉：老漫画常从 CD / 压缩包复制而来、带只读位，
        // 留着它下一次别的动作还得再遇上一次 AccessDenied。
        // 送不进回收站就报错让人收尾，不回滚删 cbz（见类注释）。
        clearReadOnly(dir);
        try {
            RecycleBin.recycle(dir);
        } catch (RuntimeException e) {
            throw new IllegalStateException("打包完成但原目录没能移入回收站："
                    + dir + "。cbz 已生成 " + target + "，请关闭占用它的程序后重试，"
                    + "或手工处置原目录，再重新同步。", e);
        }
        return target.toString();
    }

    /**
     * 递归清除目录里所有条目的「只读」属性。老漫画常从 CD / 压缩包复制而来、带只读位，
     * 以前打包完删源目录（{@code Files.delete}）会抛 {@code AccessDeniedException}；
     * 现在源目录走回收站，只读本来不碍事 —— 但留着这层清理仍然是好的：下次别的动作
     * （手工删、别的脚本）就不必再遇上它一次。
     */
    private static void clearReadOnly(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.forEach(p -> {
                try {
                    Files.setAttribute(p, "dos:readonly", false);
                } catch (IOException | UnsupportedOperationException ignored) {
                    // 清不掉（被占用 / 非 Windows 文件系统）就让它去，删除时自然会报错
                }
            });
        } catch (IOException ignored) {
            // 遍历不了就算了，删除时自然会报错
        }
    }

    /**
     * 可打包的漫画目录：落在未归档根或某个归档根下、是目录（非 cbz）。
     * 有没有图片由 {@code packImages} 判（0 即报「没有图片」），这里不重复遍历。
     */
    private Path requirePackableDir(String folderPath) {
        if (StringUtils.isBlank(folderPath)) {
            throw new IllegalArgumentException("目录路径不能为空");
        }
        Path target = Paths.get(folderPath).toAbsolutePath().normalize();
        boolean underUnarchived = under(target, properties.getUnarchivedDir());
        // 评分分区都在归档根下面，比归档根本身就够
        boolean underArchive = under(target, properties.getArchiveDir());
        if (!underUnarchived && !underArchive) {
            throw new IllegalArgumentException("不是未归档或归档根下的漫画目录：" + folderPath);
        }
        if (MangaCbzUtil.isCbz(target)) {
            throw new IllegalArgumentException("已经是 cbz，无需打包：" + folderPath);
        }
        if (!Files.isDirectory(target)) {
            throw new IllegalStateException("目录不在磁盘上：" + folderPath);
        }
        return target;
    }

    /** {@code target} 严格落在 {@code root} 下（不含 root 自身），防把根目录或评分分区本身打包删掉 */
    private static boolean under(Path target, String root) {
        if (StringUtils.isBlank(root)) {
            return false;
        }
        Path r = Paths.get(root).toAbsolutePath().normalize();
        return target.startsWith(r) && !target.equals(r);
    }

    /** 路径的末级名；路径非法时返回 {@code null}，不因此中断整批 */
    private static String fileNameOf(String path) {
        if (StringUtils.isBlank(path)) {
            return null;
        }
        try {
            Path name = Paths.get(path).getFileName();
            return name == null ? null : name.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
