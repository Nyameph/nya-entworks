package io.github.Nyameph.nyaentworks.song.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link SongNaming} 的拼装规则 —— 名字的拼法全项目只准有这一份，这里逐条钉住。
 * 纯单测，不连库、不碰磁盘。
 */
public class SongNamingTest {

    @Test
    public void minimal() {
        assertEquals("张三 - 歌（原曲）", SongNaming.build("张三", "歌", "原曲", null, null));
    }

    @Test
    public void withOriginalArtist() {
        assertEquals("张三 - 歌（原曲_刘若英）",
                SongNaming.build("张三", "歌", "原曲", "刘若英", null));
    }

    @Test
    public void withVersion() {
        assertEquals("张三 - 歌（原曲）#2",
                SongNaming.build("张三", "歌", "原曲", null, "2"));
    }

    @Test
    public void nonNumericVersion() {
        assertEquals("张三 - 歌（原曲）#幼",
                SongNaming.build("张三", "歌", "原曲", null, "幼"));
    }

    @Test
    public void multiArtistSpacesAreNormalized() {
        // & 两侧的空格由 build 补齐
        assertEquals("张三 & 李四 - 歌（原曲）",
                SongNaming.build("张三&李四", "歌", "原曲", null, null));
    }

    @Test
    public void multiArtistIdempotent() {
        // 已带空格、或中间多个空格，trim 归一后结果一致（幂等）
        assertEquals("张三 & 李四 - 歌（原曲）",
                SongNaming.build("张三 &  李四", "歌", "原曲", null, null));
        assertEquals(SongNaming.build("张三 & 李四", "歌", "原曲", null, null),
                SongNaming.build("张三&李四", "歌", "原曲", null, null));
    }

    @Test
    public void blankPartsThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> SongNaming.build(" ", "歌", "原曲", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> SongNaming.build("张三", " ", "原曲", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> SongNaming.build("张三", "歌", "", null, null));
    }

    @Test
    public void versionMustNotContainHash() {
        assertThrows(IllegalArgumentException.class, () -> SongNaming.requireVersion("2#幼"));
        // 空白就是「没有编号」，不抛
        SongNaming.requireVersion(null);
        SongNaming.requireVersion("  ");
        SongNaming.requireVersion("2");
    }

    /**
     * 与 {@link SongName#normalizedMainName()} 逐例一致 —— 那个方法转调到这里之后，
     * 「解析 → 重拼」必须还是原来的结果，否则批量规范化会把已有歌的名字改坏、
     * merge_key 变掉、标签全丢。样本取自解析器的真实形态。
     */
    @Test
    public void agreesWithNormalizedMainName() {
        String[] samples = {
                // 标准：作者 - 曲名（原曲名_原曲作者）#版本
                "张三 & 李四 - 卡路里（卡路里_初音未来）#ver2",
                // 无版本
                "白桃 - 仙子下山（不问ciaga）",
                // 松散分隔符（容错解析、标记 looseSeparator）
                "B.Y-某歌",
                // 隐式原曲名（无括号，规范化要补的就是这批）
                "某人 - 原曲名#2",
                // 单作者带点（mainName 只剥认识的扩展名，点不会被切坏）
                "Paris_polyphylla & B.Y - 纳西妲 父女play（暖暖）",
        };
        for (String sample : samples) {
            SongName name = SongNameParser.parse(sample);
            if (!name.parsed()) {
                // 解析失败的走原文兜底，不进 build（normalizedMainName 自己的守卫）
                assertEquals(sample, name.normalizedMainName(), "未解析样本应原样返回：" + sample);
                continue;
            }
            String expected = SongNaming.build(name.artistText(), name.title(),
                    name.originalTitle(), name.originalArtist(), name.version());
            assertEquals(expected, name.normalizedMainName(), "样本：" + sample);
        }
    }

    /** 显式括号 + 原曲作者 + 版本的样本：重拼必须逐字还原（round-trip） */
    @Test
    public void explicitRoundTrip() {
        String sample = "张三 & 李四 - 卡路里（卡路里_初音未来）#ver2";
        SongName name = SongNameParser.parse(sample);
        assertEquals(sample, name.normalizedMainName());
    }
}
