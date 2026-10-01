package io.github.Nyameph.nyaentworks.manga.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.Nyameph.nyaentworks.manga.MangaDictFixture;
import io.github.Nyameph.nyaentworks.manga.config.MangaProperties;
import io.github.Nyameph.nyaentworks.manga.consts.MangaArchiveUnitStatus;
import io.github.Nyameph.nyaentworks.manga.entity.MangaArchiveUnit;
import io.github.Nyameph.nyaentworks.manga.mapper.MangaArchiveNameMapper;
import io.github.Nyameph.nyaentworks.manga.mapper.MangaArchiveUnitMapper;
import io.github.Nyameph.nyaentworks.manga.mapper.MangaDataMapper;
import io.github.Nyameph.nyaentworks.manga.util.MangaNameParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 合并冲突页的「根目录其他文件」（{@link MangaArchiveService#scanOthers} 与
 * {@link MangaArchiveService#requireChildName}）。
 *
 * <p>在临时目录里摆一个归档作者目录该有的样子，钉三条口径：
 * <ul>
 *   <li><b>漫画与评分分区不算「其他文件」</b> —— 漫画已经列在左右两栏里，重复列一遍
 *       会让人以为有两份；评分分区目录底下全是漫画，搬它等于搬漫画。</li>
 *   <li><b>普通文件即使名字像漫画名也不算漫画</b> —— 扫描侧（{@code listSubDirs}）只收
 *       目录与 cbz，这里认了就会出现「左右两栏里没有、其他文件里有」的对不上。</li>
 *   <li><b>「底下有漫画的目录」要拦住</b> —— 那些漫画已经落库（{@code collectUnitMangas}
 *       会钻进认不出漫画的目录继续找），整个搬走会让库里的路径指向不存在的地方。</li>
 * </ul>
 * 纯文件系统，不连库（词典用内存 fixture）。
 */
public class MangaArchiveOtherTest {

    @TempDir
    Path root;

    private static final MangaNameParser PARSER = new MangaNameParser(MangaDictFixture.get());

    /** 摆一个目录；里面给的那个 {@code image} 为真时放一张假图（有图才算漫画） */
    private Path dir(String name, boolean image) throws IOException {
        Path dir = Files.createDirectory(root.resolve(name));
        if (image) {
            Files.writeString(dir.resolve("1.jpg"), "x");
        }
        return dir;
    }

    @Test
    public void scanOthers_listsOnlyNonMangas() throws IOException {
        dir("[社团 (作者)] 标题 (C99)", true);        // 漫画：有图且名字认得出
        dir("9-百读不厌", true);                       // 评分分区：整块跳过
        dir("旧版本", false);                          // 认不出漫画的目录（下面没图）
        dir("有漫画的目录", false);                     // 认不出漫画，但底下藏着一本
        dir("有漫画的目录/[社团 (作者)] 别的 (C97)", true);
        Files.writeString(root.resolve("备注.txt"), "x");
        Files.writeString(root.resolve("封面.jpg"), "img"); // 根上的图：是文件，不是漫画
        Files.writeString(root.resolve("[社团 (作者)] 像漫画的.txt"), "x"); // 名字像，但扫描侧不当漫画

        Map<String, MangaArchiveService.OtherEntry> byName =
                MangaArchiveService.scanOthers(root, PARSER).stream()
                        .collect(Collectors.toMap(MangaArchiveService.OtherEntry::name,
                                Function.identity()));

        assertEquals(List.of("备注.txt", "封面.jpg", "旧版本", "有漫画的目录",
                        "[社团 (作者)] 像漫画的.txt").stream().sorted().toList(),
                byName.keySet().stream().sorted().toList(),
                "只该剩下不是漫画的直接子项：" + byName.keySet());

        MangaArchiveService.OtherEntry txt = byName.get("备注.txt");
        assertFalse(txt.directory());
        assertEquals(1L, txt.size(), "文件要带字节数（预览里给人看大小）");
        assertFalse(txt.path().isEmpty());
        assertEquals(root.resolve("备注.txt").toString(), txt.path());

        assertTrue(byName.get("旧版本").directory());
        assertEquals(0L, byName.get("旧版本").size(), "目录不算字节数");

        String blocked = byName.get("有漫画的目录").blockedReason();
        assertNotNull(blocked, "底下有漫画的目录该被拦住");
        assertTrue(blocked.contains("1 本"), "该报出底下有几本：" + blocked);
    }

    /** 干净的其他文件一个拦截原因都没有 —— 页面上「可搬」与否全靠它 */
    @Test
    public void scanOthers_unblockedWhenNoMangasInside() throws IOException {
        dir("旧版本", false);
        Files.writeString(root.resolve("a.txt"), "x");
        for (MangaArchiveService.OtherEntry e : MangaArchiveService.scanOthers(root, PARSER)) {
            assertNull(e.blockedReason(), e.name() + " 不该被拦");
        }
    }

    /** 目录不在磁盘上时返回空清单（与 unitMangas 一致），不抛 */
    @Test
    public void scanOthers_missingDirIsEmpty() {
        assertTrue(MangaArchiveService.scanOthers(
                root.resolve("不存在"), PARSER).isEmpty());
    }

    /** 名字必须是不带路径的直接子项名 —— 请求体是字符串数组，不守这道等于能搬任意位置 */
    @Test
    public void requireChildName_rejectsPaths() {
        assertEquals("a.txt", MangaArchiveService.requireChildName(" a.txt "));
        for (String bad : new String[]{"..", "a/b", "a\\b", "", "  ", "C:\\a.txt"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> MangaArchiveService.requireChildName(bad), "该拒绝：" + bad);
        }
    }

    // ==================== plan / apply ====================

    /**
     * 预演只受理「确实是这个目录下的其他文件」的名字，**漫画名字混进来要拒**。
     *
     * <p>这是这条链上最要紧的一道：请求体对接口开放，不按 {@code scanOthers} 再判一次，
     * 就等于让接口能把<b>落了库的漫画目录</b>搬走（{@code folder_path} 会指到不存在的地方），
     * 还绕过了「漫画走上面的移动 / 删除」这条分工。前端确实只列其他文件，但判定要在后端。
     */
    @Test
    public void planOtherMove_rejectsMangaNames() throws IOException {
        Path left = dir("左", false);
        dir("左/[社团 (作者)] 标题 (C99)", true); // 漫画：有图且名字认得出
        Files.writeString(left.resolve("备注.txt"), "x"); // 其他文件
        Path right = dir("右", false);
        MangaArchiveService service = service(left, right);

        MangaArchiveService.OtherMovePlan plan = service.planOtherMove(1L, 2L,
                List.of("备注.txt", "[社团 (作者)] 标题 (C99)"));
        assertEquals(2, plan.moves().size());
        assertNull(plan.moves().get(0).blockedReason(), "其他文件该能搬");
        assertTrue(plan.moves().get(1).blockedReason().contains("不是这个目录下的其他文件"),
                plan.moves().get(1).blockedReason());
        // 整批会被拦下来：页面上那条会画成红标签，确认键不出现
        assertTrue(plan.moves().stream().anyMatch(mv -> mv.blockedReason() != null));
    }

    /** 一条被拦就整批不搬 —— 执行时重算，预演之后目标被占了也拒 */
    @Test
    public void applyOtherMove_blockedRefusesWholeBatch() throws IOException {
        Path left = dir("左", false);
        Path right = dir("右", false);
        Files.writeString(left.resolve("a.txt"), "x");
        Files.writeString(left.resolve("b.txt"), "x");
        Files.writeString(right.resolve("b.txt"), "占了"); // 目标已存在同名 → 拦下 b，整批不动
        MangaArchiveService service = service(left, right);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.applyOtherMove(1L, 2L, List.of("a.txt", "b.txt")));
        assertTrue(e.getMessage().contains("目标已存在同名"), e.getMessage());
        assertTrue(Files.exists(left.resolve("a.txt")), "整批不搬：a.txt 该原封不动");
    }

    /** 一路畅通时真的把文件搬过去（这些文件不在库里，搬完没有「库跟着走」这一步） */
    @Test
    public void applyOtherMove_movesTheFiles() throws IOException {
        Path left = dir("左", false);
        Path right = dir("右", false);
        Files.writeString(left.resolve("a.txt"), "x");
        MangaArchiveService service = service(left, right);

        assertEquals(1, service.applyOtherMove(1L, 2L, List.of("a.txt")));
        assertFalse(Files.exists(left.resolve("a.txt")));
        assertEquals("x", Files.readString(right.resolve("a.txt")));
    }

    /**
     * 用 mock 的 mapper / 词典把 service 拼起来（不连库）：
     * unit 1 指向 from、unit 2 指向 to，词典用内存 fixture。
     */
    private MangaArchiveService service(Path from, Path to) {
        MangaArchiveUnitMapper unitMapper = mock(MangaArchiveUnitMapper.class);
        when(unitMapper.selectById(1L)).thenReturn(unit(1L, from));
        when(unitMapper.selectById(2L)).thenReturn(unit(2L, to));
        MangaDictService dictService = mock(MangaDictService.class);
        when(dictService.current()).thenReturn(MangaDictFixture.get());
        // 测试不碰归档根，给一份默认配置即可
        return new MangaArchiveService(unitMapper, mock(MangaArchiveNameMapper.class),
                mock(MangaDataMapper.class), dictService, mock(MangaTagService.class),
                mock(MangaNewService.class), new MangaProperties());
    }

    private static MangaArchiveUnit unit(Long id, Path folder) {
        MangaArchiveUnit unit = new MangaArchiveUnit();
        unit.setId(id);
        unit.setFolderPath(folder.toString());
        unit.setFolderName(folder.getFileName().toString());
        unit.setStatus(MangaArchiveUnitStatus.ACTIVE);
        return unit;
    }
}
