package io.github.Nyameph.nyaentworks.manga;

import org.junit.jupiter.api.Test;
import io.github.Nyameph.nyaentworks.manga.util.MangaEventKey;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 展会/杂志容错比对的纯函数测试，不依赖 Spring 与外部环境。
 * <p>入参按 {@code MangaEhLocalDb.stripKey} 之后的形态写（大写、只留字母数字）。
 */
class
MangaEventKeyTest {

    /** 缩写与全称：文字部分毫无重合，靠「届数相同 + 一边是缩写」判兼容 */
    @Test
    void abbreviationMatchesFullName() {
        assertTrue(MangaEventKey.compatible("C97", "コミックマーケット97"));
        assertTrue(MangaEventKey.compatible("C97", "COMICMARKET97"));
    }

    /** 届数/期号不同 → 不兼容，这是最要防的错配 */
    @Test
    void differentNumberIsIncompatible() {
        assertFalse(MangaEventKey.compatible("C97", "C98"));
        assertFalse(MangaEventKey.compatible("C97", "コミックマーケット98"));
        assertFalse(MangaEventKey.compatible("COMIC高VOL4", "COMIC高VOL5"));
    }

    /** 前导零不该造成差异 */
    @Test
    void leadingZeroIgnored() {
        assertTrue(MangaEventKey.compatible("VOL04", "VOL4"));
    }

    /** 日期型编号按顺序比，年相同月不同要区分开 */
    @Test
    void dateNumbersCompareInOrder() {
        assertFalse(MangaEventKey.compatible("COMIC202405", "COMIC202406"));
        assertTrue(MangaEventKey.compatible("COMIC202405", "COMIC202405"));
    }

    /** 一边没数字：退化成文字比较，不因缺数字就否掉 */
    @Test
    void missingNumberFallsBackToLetters() {
        assertTrue(MangaEventKey.compatible("COMICMARKET", "COMICMARKET97"));
    }

    /** 文字部分差太远且都不短 → 不兼容 */
    @Test
    void unrelatedLettersIncompatible() {
        assertFalse(MangaEventKey.compatible("COMICMARKET", "SUNSHINECREATION"));
    }

    /** 冲突判定：两边都有值且没有一对兼容才算冲突 */
    @Test
    void conflictsOnlyWhenBothSidesPresent() {
        assertTrue(MangaEventKey.conflicts(Set.of("C97"), Set.of("C98")));
        assertFalse(MangaEventKey.conflicts(Set.of("C97"), Set.of("C97")));
        // 任一边缺值 = 判不了，不是冲突
        assertFalse(MangaEventKey.conflicts(Set.of(), Set.of("C98")));
        assertFalse(MangaEventKey.conflicts(Set.of("C97"), Set.of()));
        assertFalse(MangaEventKey.conflicts(null, Set.of("C98")));
    }

    /** 多值时只要有一对兼容就不冲突 */
    @Test
    void anyCompatiblePairClearsConflict() {
        assertFalse(MangaEventKey.conflicts(Set.of("C97", "C99"), Set.of("C99")));
    }

    /** matches 与 conflicts 三态互补：两边都有值时二者恰好相反 */
    @Test
    void matchesIsComplementOfConflictsWhenBothPresent() {
        assertTrue(MangaEventKey.matches(Set.of("C97"), Set.of("コミックマーケット97")));
        assertFalse(MangaEventKey.conflicts(Set.of("C97"), Set.of("コミックマーケット97")));
        // 缺值时两者同时为 false（中性）
        assertFalse(MangaEventKey.matches(Set.of("C97"), Set.of()));
        assertFalse(MangaEventKey.conflicts(Set.of("C97"), Set.of()));
    }

    /** 三态：对上 / 对不上 / 判不了，缺值必须落在 UNKNOWN 而不是 CONFLICT */
    @Test
    void classifyReturnsThreeStates() {
        assertEquals(MangaEventKey.Verdict.MATCH,
                MangaEventKey.classify(Set.of("C97"), Set.of("コミックマーケット97")));
        assertEquals(MangaEventKey.Verdict.CONFLICT,
                MangaEventKey.classify(Set.of("C97"), Set.of("C98")));
        assertEquals(MangaEventKey.Verdict.UNKNOWN,
                MangaEventKey.classify(Set.of("C97"), Set.of()));
        assertEquals(MangaEventKey.Verdict.UNKNOWN,
                MangaEventKey.classify(Set.of(), Set.of("C97")));
        assertEquals(MangaEventKey.Verdict.UNKNOWN,
                MangaEventKey.classify(null, null));
    }

    @Test
    void numbersStripsLeadingZeroAndKeepsOrder() {
        // stripKey 已去掉分隔符，2024.05 归一后是连续一段数字，不会被拆成两个
        assertEquals(List.of("202405"), MangaEventKey.numbers("COMIC202405"));
        assertEquals(List.of("4"), MangaEventKey.numbers("VOL04"));
        assertEquals(List.of(), MangaEventKey.numbers("COMICMARKET"));
        assertEquals(List.of("0"), MangaEventKey.numbers("VOL00"));
        // 被文字隔开的两段数字各自成项，顺序保留
        assertEquals(List.of("2", "97"), MangaEventKey.numbers("VOL2C97"));
    }

    @Test
    void lettersDropsDigits() {
        assertEquals("COMIC", MangaEventKey.letters("COMIC202405"));
        assertEquals("", MangaEventKey.letters("97"));
    }
}
