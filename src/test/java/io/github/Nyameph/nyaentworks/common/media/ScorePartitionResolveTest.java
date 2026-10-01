package io.github.Nyameph.nyaentworks.common.media;

import io.github.Nyameph.nyaentworks.manga.util.MangaScoreDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 打分自动建分区（2026-09-30）：
 * <ul>
 *   <li>{@link ScorePartition#resolveDir} / {@link MangaScoreDir#resolveScoreDir}
 *       缺档时给一个<b>裸分数字</b>目录（{@code #9} / {@code #9-}）、<b>不建目录</b>
 *       —— 真正建由落盘那一侧（{@code GroupFileOps.moveAll} / {@code MangaStoreService}）做；</li>
 *   <li>{@code ScorePartition#requireDir} 缺档仍然抛 —— 那是给歌曲 / 喊麦的
 *       「导入 / 添加文件」用的，这条不能松（漫画没有导入那条路，
 *       {@code MangaScoreDir} 只有 resolve 一个入口）；</li>
 *   <li>{@link ScorePartition#rows} 把没建的档也下发（{@code onDisk=false}）——
 *       不补的话「自动建」在页面上根本没有按钮可点。</li>
 * </ul>
 *
 * <p>不连库、不碰 {@code F:\}，全在 {@link TempDir} 里。
 */
public class ScorePartitionResolveTest {

    // ------------------------------------------------------------------
    // 歌曲 / 喊麦：#9超赞 形态
    // ------------------------------------------------------------------

    @Test
    public void resolveDir_missingPartitionReturnsBareScoreDir_andDoesNotCreate(@TempDir Path root)
            throws IOException {
        Files.createDirectory(root.resolve("#7佳作"));
        // 9 分那档没建：给裸 #9，且不落盘
        Path dir = ScorePartition.resolveDir(root.toString(), 9);
        assertEquals(root.resolve("#9").toString(), dir.toString());
        assertFalse(Files.exists(dir), "落盘阶段才建，resolve 只给路径");
    }

    @Test
    public void resolveDir_existingPartitionKeepsItsLabel(@TempDir Path root) throws IOException {
        Files.createDirectory(root.resolve("#9超赞"));
        // 已存在的档保留人起的评语，不会换成裸 #9
        assertEquals(root.resolve("#9超赞"),
                ScorePartition.resolveDir(root.toString(), 9));
    }

    @Test
    public void resolveDir_rootMissingThrows(@TempDir Path tmp) {
        Path nowhere = tmp.resolve("盘没挂上");
        assertThrows(IllegalStateException.class,
                () -> ScorePartition.resolveDir(nowhere.toString(), 9));
    }

    @Test
    public void requireDir_stillThrowsWhenPartitionMissing(@TempDir Path root) throws IOException {
        Files.createDirectory(root.resolve("#7佳作"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ScorePartition.requireDir(root.toString(), 9));
        // 原话里带分数与形态提示（导入侧的拦截语就是这句，别改丢）
        assertTrue(e.getMessage().contains("找不到 9 分"), e.getMessage());
        assertFalse(Files.exists(root.resolve("#9")), "requireDir 绝不建目录");
    }

    // ------------------------------------------------------------------
    // 页面上「可选档位」的列表（/partitions 下发的那一份）
    // ------------------------------------------------------------------

    @Test
    public void rows_listsMissingTiersAsNotOnDisk_soScoringIntoThemIsReachable(
            @TempDir Path root) throws IOException {
        Files.createDirectory(root.resolve("#9超赞"));
        Files.createDirectory(root.resolve("#5可用"));
        List<ScorePartition.Row> rows = ScorePartition.rows(root.toString());

        // 完整五档、高分在前 —— 缺的补裸分数字，且 onDisk=false（筛选用它滤掉）
        assertEquals(List.of(9, 7, 5, 3, 1), rows.stream().map(ScorePartition.Row::score).toList());
        assertEquals(List.of(true, false, true, false, false),
                rows.stream().map(ScorePartition.Row::onDisk).toList());
        assertEquals("#7", rows.get(1).dirName());
        // 真在盘上的那一档仍用它的目录名与评语
        assertEquals("#9超赞", rows.get(0).dirName());
        assertEquals("9 分 超赞", rows.get(0).display());
        // 没评语时不留下多余的空格
        assertEquals("7 分", rows.get(1).display());
        // 只给路径、不落盘（与 resolveDir 同一条纪律）
        assertFalse(Files.exists(root.resolve("#7")));
    }

    @Test
    public void rows_rootMissingIsEmpty_notFiveUnusableButtons(@TempDir Path tmp) {
        // 盘没挂上 / 根配错：这时落盘一步都走不到，画一排按下去只会报错的按钮不如一个都不画
        assertTrue(ScorePartition.rows(tmp.resolve("盘没挂上").toString()).isEmpty());
    }

    // ------------------------------------------------------------------
    // 漫画：#9-百读不厌 形态
    // ------------------------------------------------------------------

    @Test
    public void resolveScoreDir_missingPartitionReturnsBareScoreDir_andDoesNotCreate(
            @TempDir Path root) throws IOException {
        Files.createDirectory(root.resolve("#9-百读不厌"));
        // 3 分那档没建：给裸 #3-（横线是形态的一部分）
        Path dir = MangaScoreDir.resolveScoreDir(root.toString(), 3);
        assertEquals(root.resolve("#3-").toString(), dir.toString());
        assertFalse(Files.exists(dir), "落盘阶段才建，resolve 只给路径");
    }

    @Test
    public void resolveScoreDir_existingPartitionKeepsItsLabel(@TempDir Path root) throws IOException {
        Files.createDirectory(root.resolve("#9-百读不厌"));
        assertEquals(root.resolve("#9-百读不厌"),
                MangaScoreDir.resolveScoreDir(root.toString(), 9));
    }

    @Test
    public void resolveScoreDir_rootMissingThrows(@TempDir Path tmp) {
        Path nowhere = tmp.resolve("盘没挂上");
        // 盘没挂 / 根配错：两条路都抛，这一步连往前都不该走，更不能建目录
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> MangaScoreDir.resolveScoreDir(nowhere.toString(), 9));
        assertTrue(e.getMessage().contains("找不到 9 分"), e.getMessage());
    }
}
