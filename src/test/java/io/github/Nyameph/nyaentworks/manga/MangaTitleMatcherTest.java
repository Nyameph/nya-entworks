package io.github.Nyameph.nyaentworks.manga;

import org.junit.jupiter.api.Test;
import io.github.Nyameph.nyaentworks.manga.util.MangaTitleMatcher;
import io.github.Nyameph.nyaentworks.manga.util.MangaTitleMatcher.Match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MangaTitleMatcher} 的纯函数验证，不连库。
 *
 * <p>两块重点：①「包含」收紧——旧口径允许 eh 标题只带本地标题的短前缀（本地「…ごっこ2」、
 * eh 只有「めぐみん」4 字符）就被误判成包含命中（对应 15540 被误匹配到 1821713 的 bug）；
 * ②「续作」识别——本地「…ごっこ2」（第二部）与 eh「…ごっこ」（第一部）去续作标记后核心相等，
 * 判为不同部、不命中，交给 AI 或 NO_MATCH，而不是互相包含 0.85。
 */
public class MangaTitleMatcherTest {

    private static final String SECOND = "めぐみんコスプレイヤーとオフパコごっこ2";
    private static final String FIRST = "めぐみんコスプレイヤーとオフパコごっこ";
    private static final String SHORT_PREFIX = "めぐみん";

    /** 精确相等 → 1.0 / TITLE_EXACT */
    @Test
    public void match_exact() {
        Match m = MangaTitleMatcher.match(SECOND, SECOND, null);
        assertEquals(MangaTitleMatcher.METHOD_TITLE_EXACT, m.method());
        assertEquals(MangaTitleMatcher.SCORE_EXACT, m.score());
    }

    /** 同一系列第一部 vs 第二部：续作关系，不命中（不判包含 0.85），交给 AI/NO_MATCH */
    @Test
    public void match_firstVsSecond() {
        Match m = MangaTitleMatcher.match(SECOND, FIRST, null);
        assertNull(m.method());
        assertEquals(0.0, m.score());
    }

    /** eh 标题只带短前缀「めぐみん」→ 比例不足，不命中（修复 15540 误匹配 1821713） */
    @Test
    public void match_shortPrefixNotContains() {
        Match m = MangaTitleMatcher.match(SECOND, SHORT_PREFIX, null);
        assertNull(m.method());
        assertEquals(0.0, m.score());
    }

    /** 日文标题路径同样排除续作：eh 的 titleJpn 是第一部 → 不命中 */
    @Test
    public void match_jpnTitleFirstVsSecond() {
        Match m = MangaTitleMatcher.match(SECOND, null, FIRST);
        assertNull(m.method());
        assertEquals(0.0, m.score());
    }

    /** containsMatch 直接门槛：短前缀因「较短方 * 2 < 较长方」被拒（测试串无分隔符，可直传） */
    @Test
    public void containsMatch_rejectsShortPrefix() {
        assertFalse(MangaTitleMatcher.containsMatch(SHORT_PREFIX, SECOND));
    }

    /** containsMatch 直接门槛：第一部 vs 第二部是续作关系 → 不判包含 */
    @Test
    public void containsMatch_firstVsSecond() {
        assertFalse(MangaTitleMatcher.containsMatch(SECOND, FIRST));
    }

    /** 较短方长度 < MIN_CONTAINS_LEN(4) 一律不判包含，哪怕精确相等（短标题只走精确相等分支） */
    @Test
    public void containsMatch_shortBelowMinLen() {
        assertFalse(MangaTitleMatcher.containsMatch("abc", "abc"));
    }

    /** isSequel：去续作标记后核心相等 → 续作 */
    @Test
    public void isSequel_firstVsSecond() {
        assertTrue(MangaTitleMatcher.isSequel(SECOND, FIRST));
        assertTrue(MangaTitleMatcher.isSequel(FIRST, SECOND));
    }

    /** isSequel：完全相同 → 不是续作（是同一部）；核心不同 → 不是续作 */
    @Test
    public void isSequel_sameOrUnrelated() {
        assertFalse(MangaTitleMatcher.isSequel(SECOND, SECOND));
        assertFalse(MangaTitleMatcher.isSequel(SECOND, SHORT_PREFIX));
    }
}
