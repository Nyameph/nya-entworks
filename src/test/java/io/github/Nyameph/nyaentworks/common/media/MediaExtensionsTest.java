package io.github.Nyameph.nyaentworks.common.media;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@link MediaExtensions} 的 {@code suffix} / {@code fullName} —— 2026-09-23 起的
 * 完整文件名口径。纯单测，不连库、不碰磁盘。
 *
 * <p>这两件存在的理由：{@code song_file} / {@code shout_file} 不再存完整文件名，
 * 只存 {@code main_name} + {@code suffix} 两段，于是「{@code main_name + suffix}
 * 恒等于磁盘上的文件名」从一条没人强制的隐含约定变成了唯一键
 * {@code uk_file(…, main_name, suffix)} 的前提。这个类盯的就是这条不变量。
 */
public class MediaExtensionsTest {

    /** 迁移时从库里实测到的真实文件名的形状（含大写后缀与主名里带点的） */
    private static final List<String> REAL_NAMES = List.of(
            "Paris_polyphylla & B.Y - 纳西妲 父女play（暖暖）.mp4",
            "YZLZ - 赛博朋克---母猪搜查署 终极典藏版（Mr.Q）#2.mp4",
            "YZLZ - 赛博朋克---母猪搜查署 终极典藏版（Mr.Q）#2.lrc",
            "B.Y & qwerty - 驚鴻一面 狐娘便器（惊鸿一面）.lrc",
            "白桃 - 仙子下山（不问ciaga）.mp3",
            "某首歌.MP3",
            "某首歌.Mp3");

    // ==================== suffix：前缀相减 ====================

    @Test
    public void suffix_takesEverythingAfterMainName() {
        assertEquals(".mp3", MediaExtensions.suffix("卡路里.mp3", "卡路里"));
        assertEquals(".lrc", MediaExtensions.suffix("卡路里.lrc", "卡路里"));
    }

    @Test
    public void suffix_keepsOriginalCase() {
        assertEquals(".MP3", MediaExtensions.suffix("卡路里.MP3", "卡路里"));
        assertEquals(".Mp3", MediaExtensions.suffix("卡路里.Mp3", "卡路里"));
    }

    /**
     * <b>这个类里最要紧的一条。</b>{@link MediaExtensions#extension} 特意小写化
     * （扫描时按小写比才不漏文件），但 {@code suffix} <b>绝不能</b>跟着小写 ——
     * 库里实测有 81 行 {@code song_file}、2 行 {@code shout_file} 是大写 {@code .MP3}。
     * 一旦有人把 {@code suffix} 「简化」成 {@code "." + extension(f)}，
     * 拼回的完整文件名就与磁盘上的真实文件名不相等，那些行会**每轮同步都被删掉再插一遍**
     * （id 与 create_time 每轮都变），而唯一的信号是同步计数不是 0 —— 很容易看不见。
     */
    @Test
    public void suffix_mustNotLowercaseUnlikeExtension() {
        assertEquals("mp3", MediaExtensions.extension("某首歌.MP3"), "extension 的口径就是小写");
        assertEquals(".MP3", MediaExtensions.suffix("某首歌.MP3", "某首歌"),
                "suffix 必须保留原文大小写，否则拼不回磁盘上的真名");
        assertNotEquals("." + MediaExtensions.extension("某首歌.MP3"),
                MediaExtensions.suffix("某首歌.MP3", "某首歌"),
                "两种口径必须不同 —— 相同就说明 suffix 被写成了「点 + extension」，是 bug");
    }

    /**
     * 主名里带点时不能「从最后一个点切开」：那样 {@code B.Y - 歌.mp3} 会被切成
     * {@code .Y - 歌.mp3} 之类。前缀相减天然没有这个问题。
     */
    @Test
    public void suffix_dotInsideMainNameIsNotAProblem() {
        String name = "Paris_polyphylla & B.Y - 纳西妲 父女play（暖暖）.mp4";
        String mainName = MediaExtensions.mainName(name);
        assertEquals("Paris_polyphylla & B.Y - 纳西妲 父女play（暖暖）", mainName);
        assertEquals(".mp4", MediaExtensions.suffix(name, mainName));
    }

    @Test
    public void suffix_mainNameWithVariantNumber() {
        String name = "YZLZ - 某歌（Mr.Q）#2.mp4";
        assertEquals(".mp4", MediaExtensions.suffix(name, "YZLZ - 某歌（Mr.Q）#2"));
    }

    /** 不是前缀就返回空串，不硬切 —— 那说明这对参数不是从同一个文件名推出来的 */
    @Test
    public void suffix_notAPrefix_returnsEmpty() {
        assertEquals("", MediaExtensions.suffix("abc.mp3", "bcd"));
        assertEquals("", MediaExtensions.suffix("abc.mp3", "abc.mp3.lrc"));
        assertEquals("", MediaExtensions.suffix("abc.mp3", "ABC"));
    }

    /** 主名与文件名相等（没有认识的后缀，如 xlsx）→ 后缀为空 */
    @Test
    public void suffix_noExtension_returnsEmpty() {
        assertEquals("", MediaExtensions.suffix("abc", "abc"));
        assertEquals("", MediaExtensions.suffix("#统计.xlsx", "#统计.xlsx"));
    }

    /** 主名为空时整名都算后缀 —— 不变量优先于「拦下可疑入参」 */
    @Test
    public void suffix_blankMainName_takesWholeName() {
        assertEquals(".mp3", MediaExtensions.suffix(".mp3", ""));
        assertEquals(".mp3", MediaExtensions.suffix(".mp3", null));
    }

    @Test
    public void suffix_blankFileName_returnsEmpty() {
        assertEquals("", MediaExtensions.suffix("", "x"));
        assertEquals("", MediaExtensions.suffix(null, "x"));
        assertEquals("", MediaExtensions.suffix("   ", "x"));
    }

    // ==================== fullName：拼回去 ====================

    @Test
    public void fullName_concatenatesWithoutAddingADot() {
        assertEquals("卡路里.mp3", MediaExtensions.fullName("卡路里", ".mp3"));
    }

    /** 两段都可能为 null（库里是 NOT NULL，但内存里新建的实体不是）——别拼出 "Xnull" */
    @Test
    public void fullName_nullTolerant() {
        assertEquals("卡路里", MediaExtensions.fullName("卡路里", null));
        assertEquals(".mp3", MediaExtensions.fullName(null, ".mp3"));
        assertEquals("", MediaExtensions.fullName(null, null));
    }

    // ==================== 不变量：往返 ====================

    /**
     * 这是 {@code SongSyncService} / {@code ShoutSyncService} 每轮同步都在做的运算：
     * 磁盘文件名 → 主名（入库）→ 后缀（入库）→ 拼回完整文件名（与磁盘名比）。
     * 拼不回来，同步就会把那一行删掉再插一遍。
     */
    @Test
    public void roundTrip_mainNamePlusSuffixRebuildsTheFileName() {
        for (String name : REAL_NAMES) {
            String mainName = MediaExtensions.mainName(name);
            String suffix = MediaExtensions.suffix(name, mainName);
            assertEquals(name, MediaExtensions.fullName(mainName, suffix),
                    "往返失败：" + name);
        }
    }
}
