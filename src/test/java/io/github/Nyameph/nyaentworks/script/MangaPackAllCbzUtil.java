package io.github.Nyameph.nyaentworks.script;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import io.github.Nyameph.nyaentworks.manga.entity.MangaData;
import io.github.Nyameph.nyaentworks.manga.mapper.MangaDataMapper;
import io.github.Nyameph.nyaentworks.manga.service.MangaPackService;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskContext;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 批量把存量<b>目录形态</b>的漫画打包成 {@code .cbz} —— 可执行脚本，非单元测试。
 *
 * <p><b>为什么需要</b>：归档流程（{@code MangaStoreService}）已经在存储时打包，但库里
 * 更早存进去的漫画还是目录形态，要一次性把它们补齐成 cbz，跟新归档的形态一致。
 *
 * <p><b>选哪些</b>：扫全部 {@code manga_data}，只挑磁盘上<b>仍是目录</b>的
 * （{@code Files.isDirectory}）。不依赖 {@code file_type} 字段是否已回填，也天然跳过
 * 已经是 cbz 的与标了 MISSING（目录已不在磁盘）的行。
 *
 * <p><b>用法</b>：先置 {@code justTest=true} 跑一遍看清单，确认后置 {@code false} 真实执行。
 * 需本机 MySQL 与 {@code F:\} 漫画目录。
 */
@SpringBootTest
public class MangaPackAllCbzUtil {

    @Autowired
    private MangaDataMapper mangaDataMapper;

    @Autowired
    private MangaPackService packService;

    @Test
    public void packAll() {
        boolean justTest = true;   // true = 只打印清单不打包；false = 真实打包并删源目录

        List<String> folderDirs = new ArrayList<>();
        for (MangaData row : mangaDataMapper.selectList(null)) {
            if (StringUtils.isBlank(row.getFolderPath())
                    || !Files.isDirectory(Paths.get(row.getFolderPath()))) {
                continue;
            }
            folderDirs.add(row.getFolderPath());
        }

        System.out.println("==============================================");
        System.out.println((justTest ? "【干跑】" : "【真实】") + "找到目录形态的漫画 "
                + folderDirs.size() + " 本：");
        folderDirs.forEach(p -> System.out.println("  " + p));

        if (folderDirs.isEmpty()) {
            System.out.println("没有要打包的目录漫画");
            return;
        }

        if (justTest) {
            System.out.println();
            System.out.println("未动磁盘。确认清单无误后置 justTest=false 重跑");
            return;
        }

        MangaPackService.PackResult result = packService.packBatch(folderDirs, new AsyncTaskContext() {
            @Override
            public void progress(int done, int total, String message) {
                if (message != null) {
                    System.out.println("  (" + done + "/" + total + ") " + message);
                }
            }

            @Override
            public void message(String message) {
                if (message != null) {
                    System.out.println("  " + message);
                }
            }

            @Override
            public void log(String line) {
                System.out.println("  " + line);
            }

            @Override
            public void updateParams(Object params) {
                // 脚本直接跑，不需要重跑参数
            }
        });

        System.out.println("==============================================");
        System.out.println("打包 " + result.packed() + "/" + result.total() + " 本，失败 "
                + result.failed() + " 本");
        for (MangaPackService.PackItem item : result.items()) {
            if (item.error() != null) {
                System.out.println("  失败 " + item.folderPath() + " —— " + item.error());
            }
        }
        System.out.println("跑完后确认库中 folder_path / file_type 与磁盘 .cbz 一致");
    }
}
