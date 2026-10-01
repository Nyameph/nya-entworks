package io.github.Nyameph.nyaentworks.manga;

import org.junit.jupiter.api.Test;
import io.github.Nyameph.nyaentworks.manga.util.MangaTextUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link MangaTextUtil} 的纯函数验证，不连库。
 *
 * <p>重点是 {@code escapeLike}：按路径前缀查漫画全靠它，而 Windows 路径里全是反斜杠、
 * 反斜杠又正好是 SQL {@code LIKE} 的转义字符。漏转义的症状是「算出 0 行受影响」——
 * 一个看着像正常值的数字，调用方照样往下执行，归档目录改名后漫画全部失联。
 */
public class MangaTextUtilTest {

    /** 反斜杠要变成两个：LIKE 解析时 `\\` 才还原成一个字面反斜杠 */
    @Test
    public void escapeLike_backslash() {
        assertEquals("F:\\\\MangaGroup\\\\", MangaTextUtil.escapeLike("F:\\MangaGroup\\"));
    }

    /** 真实的归档目录前缀：结尾那个分隔符转义后，likeRight 追加的 % 才是通配符 */
    @Test
    public void escapeLike_archiveFolderPrefix() {
        String raw = "F:\\MangaGroup\\#8\\[社团 (作者)]【标签】\\";
        assertEquals("F:\\\\MangaGroup\\\\#8\\\\[社团 (作者)]【标签】\\\\",
                MangaTextUtil.escapeLike(raw));
    }

    /** % 与 _ 出现在路径里时会被当通配符，要转义掉 */
    @Test
    public void escapeLike_percentAndUnderscore() {
        assertEquals("a\\%b", MangaTextUtil.escapeLike("a%b"));
        assertEquals("a\\_b", MangaTextUtil.escapeLike("a_b"));
    }

    /**
     * 替换顺序不能反。{@code a\%b} 的正确结果是 {@code a\\\%b}
     * （字面反斜杠 + 字面百分号）；先替换 % 再替换反斜杠会得到 {@code a\\\\%b}
     * ——两个字面反斜杠 + 通配符，语义完全不同。
     */
    @Test
    public void escapeLike_orderMatters() {
        assertEquals("a\\\\\\%b", MangaTextUtil.escapeLike("a\\%b"));
    }

    /** 没有特殊字符时原样返回 */
    @Test
    public void escapeLike_plainText() {
        assertEquals("巨乳", MangaTextUtil.escapeLike("巨乳"));
    }

    @Test
    public void escapeLike_null() {
        assertNull(MangaTextUtil.escapeLike(null));
    }

    /** 比较键：NFC + trim + 大写 */
    @Test
    public void normalizeNameKey_trimAndUpper() {
        assertEquals("ERO", MangaTextUtil.normalizeNameKey("  Ero "));
        assertNull(MangaTextUtil.normalizeNameKey("   "));
        assertNull(MangaTextUtil.normalizeNameKey(null));
    }
}
