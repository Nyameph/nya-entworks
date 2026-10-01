package io.github.Nyameph.nyaentworks.manga.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 目录名校验（{@link MangaFolderName#requireSimple}）的纯单测，不连库、不碰外挂盘。
 * <p>存在的理由是一次真实故障（2026-09-24）：判据原先写成 {@code contains("..")}，
 * 于是名字里带 ASCII 省略号的漫画改不了名 ——「公園でかくれんぼしてただけなのに...」
 * 被报成「目录名里不能有路径分隔符」。它盯住的就是「点只在整段是它时才算路径语义」。
 */
class MangaFolderNameTest {

    @Test
    void dotsInTheMiddle_areJustCharacters() {
        // 真实故障样本：省略号是 ASCII 三个点，夹在名字中间
        String name = "[SignalRed (ウラガエル)] 公園でかくれんぼしてただけなのに... "
                + "(搾り取らないで、女商人さん!!) [中国翻訳]";
        assertEquals(name, MangaFolderName.requireSimple(name));
    }

    @Test
    void twoDotsInTheMiddle_alsoAllowed() {
        assertEquals("あ..い", MangaFolderName.requireSimple("あ..い"));
        assertEquals("...", MangaFolderName.requireSimple("..."));
    }

    @Test
    void loneDotAndDotDot_rejected() {
        IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class,
                () -> MangaFolderName.requireSimple(".."));
        assertEquals("目录名不能是 ..", e1.getMessage());
        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> MangaFolderName.requireSimple("."));
        assertEquals("目录名不能是 .", e2.getMessage());
    }

    @Test
    void separators_rejected() {
        assertThrows(IllegalArgumentException.class, () -> MangaFolderName.requireSimple("a/b"));
        assertThrows(IllegalArgumentException.class, () -> MangaFolderName.requireSimple("a\\b"));
        assertThrows(IllegalArgumentException.class, () -> MangaFolderName.requireSimple("../x"));
    }

    @Test
    void blank_rejected() {
        assertThrows(IllegalArgumentException.class, () -> MangaFolderName.requireSimple(null));
        assertThrows(IllegalArgumentException.class, () -> MangaFolderName.requireSimple(""));
        assertThrows(IllegalArgumentException.class, () -> MangaFolderName.requireSimple("   "));
    }

    @Test
    void trimmed() {
        assertEquals("名前", MangaFolderName.requireSimple("  名前  "));
    }

    @Test
    void normalName_untouched() {
        String name = "[SignalRed (ウラガエル)] タイトル [中国翻訳]";
        assertEquals(name, MangaFolderName.requireSimple(name));
    }
}
