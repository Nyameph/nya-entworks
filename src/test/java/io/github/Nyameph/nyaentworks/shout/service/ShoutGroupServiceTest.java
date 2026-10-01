package io.github.Nyameph.nyaentworks.shout.service;

import org.junit.jupiter.api.Test;
import io.github.Nyameph.nyaentworks.shout.service.ShoutGroupService.ShoutGroup;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 喊麦归组的验证（{@link ShoutGroupService#groupFiles} 是纯函数，不碰磁盘、不连库）。
 *
 * <p>归组是喊麦一切写操作的入口：改评分 / 改名 / 删除都是「整组一起搬」，归错组就会
 * 把别的组的歌词带走，或者漏搬一个文件把一组裂成两半。所以这段必须有测试兜底 ——
 * 扫描本身（{@code listArchived/listStaging}）只做一次目录列举再调它。
 *
 * <p>用例覆盖与歌曲侧的<b>差异点</b>：主名里的 {@code #} 是普通字符（歌曲那边是版本号）、
 * 不解析文件名（所以不存在「解析失败的组」）、只有歌词的组要跳过。
 */
public class ShoutGroupServiceTest {

    private static final String ROOT = "F:\\歌曲\\成品-喊麦";
    private static final String PARTITION = "#9超赞";

    private static List<ShoutGroup> groups(String... fileNames) {
        return ShoutGroupService.groupFiles(List.of(fileNames), ROOT, PARTITION, 9);
    }

    // ------------------------------------------------------------------
    // 归组与分类
    // ------------------------------------------------------------------

    /** 一组齐全：视频 + 音频 + 歌词，按扩展名各归各位 */
    @Test
    public void group_full() {
        List<ShoutGroup> groups = groups("喊麦甲.mp4", "喊麦甲.mp3", "喊麦甲.lrc");
        assertEquals(1, groups.size());
        ShoutGroup group = groups.getFirst();
        assertEquals("喊麦甲", group.mainName());
        assertEquals("喊麦甲.mp4", group.videoFile());
        assertEquals("喊麦甲.mp3", group.audioFile());
        assertEquals(List.of("喊麦甲.lrc"), group.lyricFiles());
        assertEquals(PARTITION, group.partitionName());
        assertEquals(9, group.score());
        assertEquals(ROOT, group.root());
        assertTrue(group.hasVideo());
        assertTrue(group.hasAudio());
    }

    /** 主名不同就是不同的组，各归各的（不看行序，行序另外测） */
    @Test
    public void group_byMainName() {
        Map<String, ShoutGroup> byName = groups("甲.mp4", "乙.mp4", "甲.lrc", "乙.lrc").stream()
                .collect(Collectors.toMap(ShoutGroup::mainName, g -> g));
        assertEquals(2, byName.size());
        assertEquals(List.of("甲.lrc"), byName.get("甲").lyricFiles());
        assertEquals(List.of("乙.lrc"), byName.get("乙").lyricFiles());
    }

    /** 大写扩展名照认（实际数据里有 {@code .MP3}），主名不因大小写分裂成两组 */
    @Test
    public void group_upperCaseExtension() {
        List<ShoutGroup> groups = groups("喊麦甲.MP4", "喊麦甲.MP3", "喊麦甲.LRC");
        assertEquals(1, groups.size());
        ShoutGroup group = groups.getFirst();
        assertEquals("喊麦甲", group.mainName());
        assertEquals("喊麦甲.MP4", group.videoFile());
        assertEquals("喊麦甲.MP3", group.audioFile());
        assertEquals(List.of("喊麦甲.LRC"), group.lyricFiles());
    }

    /** 只有音频没视频也算一组（拿来听的喊麦） */
    @Test
    public void group_audioOnly() {
        List<ShoutGroup> groups = groups("喊麦甲.mp3", "喊麦甲.lrc");
        assertEquals(1, groups.size());
        assertFalse(groups.getFirst().hasVideo());
        assertTrue(groups.getFirst().hasAudio());
    }

    /** 只有歌词没有媒体：播不了，也不该占一行（遗留的孤儿歌词） */
    @Test
    public void group_lyricOnlySkipped() {
        assertTrue(groups("孤儿歌词.lrc").isEmpty());
        assertTrue(groups("孤儿歌词.lrc", "孤儿歌词.srt").isEmpty());
    }

    /** 不认识的扩展名跳过：{@code #统计.xlsx} 之类不是喊麦文件 */
    @Test
    public void group_unknownExtensionSkipped() {
        List<ShoutGroup> groups = groups("喊麦甲.mp4", "#统计.xlsx", "说明.doc", "封面.jpg");
        assertEquals(1, groups.size());
        assertEquals("喊麦甲", groups.getFirst().mainName());
    }

    // ------------------------------------------------------------------
    // 主名：喊麦不解析文件名
    // ------------------------------------------------------------------

    /**
     * {@code #} 是普通字符。
     * <p>这是喊麦与歌曲最要命的一处差异：歌曲那边 {@code #2} 是版本号、会被剥掉后归并，
     * 喊麦这边它就是名字的一部分 —— {@code 甲#2} 与 {@code 甲} 是两组，各自都有 {@code #}
     * 也互不影响。照歌曲的解析口径写会把这些组并成一组，进而搬错文件。
     */
    @Test
    public void mainName_hashIsLiteral() {
        List<ShoutGroup> groups = groups("喊麦甲#2.mp4", "喊麦甲#3.mp4");
        assertEquals(2, groups.size());
        assertEquals("喊麦甲#2", groups.get(0).mainName());
        assertEquals("喊麦甲#3", groups.get(1).mainName());
    }

    /** 作者名里带点：只剥认识的扩展名，不按最后一个点切 */
    @Test
    public void mainName_dotInName() {
        List<ShoutGroup> groups = groups("B.Y - 仙子下山.mp4", "B.Y - 仙子下山.lrc");
        assertEquals(1, groups.size());
        assertEquals("B.Y - 仙子下山", groups.getFirst().mainName());
    }

    /** 变形分隔符与括号原样留着：喊麦没有「作者 － 曲名」的拆解，主名就是全名 */
    @Test
    public void mainName_keptVerbatim() {
        assertEquals("作者甲 - 曲名（原曲名）",
                groups("作者甲 - 曲名（原曲名）.mp4").getFirst().mainName());
    }

    // ------------------------------------------------------------------
    // 排序 / 路径 / 播放文件
    // ------------------------------------------------------------------

    /** 按主名字典序排（忽略大小写），与 {@code listArchived} 的行序一致 */
    @Test
    public void sort_byMainName() {
        List<ShoutGroup> groups = groups("b.mp4", "A.mp4", "c.mp4");
        assertEquals(List.of("A", "b", "c"),
                groups.stream().map(ShoutGroup::mainName).toList());
    }

    /**
     * 一组有多个歌词文件时按名排序，页面上的下拉才是稳定的。
     * <p>同组多个歌词 = 同名不同扩展名（{@code .lrc} + {@code .srt}）——
     * 名字不一样就是不同的主名、不同的组。下面「歌词带后缀」那条测的就是这个。
     */
    @Test
    public void lyricFiles_sorted() {
        List<ShoutGroup> groups = groups("甲.mp4", "甲.srt", "甲.lrc");
        assertEquals(List.of("甲.lrc", "甲.srt"), groups.getFirst().lyricFiles());
    }

    /**
     * 歌词名与主名不一致 = 另一个主名，不会挂到这一组上。
     * <p>{@code 甲_a.lrc} 的主名是 {@code 甲_a}，不解析文件名，所以没有「同名前缀就算一组」
     * 这种说法 —— 它只有歌词没有媒体，整组被跳过。
     */
    @Test
    public void lyricWithSuffix_isOwnGroup() {
        List<ShoutGroup> groups = groups("甲.mp4", "甲_a.lrc");
        assertEquals(1, groups.size());
        assertEquals(List.of(), groups.getFirst().lyricFiles());
    }

    /** 播放优先视频，没有视频才用音频 */
    @Test
    public void playFile_prefersVideo() {
        assertEquals("甲.mp4", groups("甲.mp4", "甲.mp3").getFirst().playFile());
        assertEquals("甲.mp3", groups("甲.mp3").getFirst().playFile());
    }

    /** 整组文件清单：视频 → 音频 → 歌词，搬动与删除都按它来 */
    @Test
    public void allFiles_order() {
        ShoutGroup group = groups("甲.mp4", "甲.mp3", "甲.srt", "甲.lrc").getFirst();
        assertEquals(List.of("甲.mp4", "甲.mp3", "甲.lrc", "甲.srt"), group.allFiles());
    }

    /** 待打分区的组：没有分区，目录就是根，key 的分区段为空 */
    @Test
    public void staging_group() {
        List<ShoutGroup> groups = ShoutGroupService.groupFiles(
                List.of("甲.mp4"), "F:\\NetdiskDownload\\#已压缩歌曲\\喊麦", null, null);
        ShoutGroup group = groups.getFirst();
        assertNull(group.partitionName());
        assertNull(group.score());
        assertEquals("|甲", group.key());
        assertEquals("F:\\NetdiskDownload\\#已压缩歌曲\\喊麦", group.dir().toString());
    }

    /** 已归档的组：目录是「根/分区」，key 是「分区|主名」 */
    @Test
    public void archived_group() {
        ShoutGroup group = groups("甲.mp4").getFirst();
        assertEquals(PARTITION + "|甲", group.key());
        assertEquals("F:\\歌曲\\成品-喊麦\\#9超赞", group.dir().toString());
    }
}
