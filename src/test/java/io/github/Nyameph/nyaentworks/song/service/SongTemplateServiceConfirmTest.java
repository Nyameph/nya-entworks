package io.github.Nyameph.nyaentworks.song.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SongTemplateService#confirmsValue} 的纯函数测试（无 Spring / 无 DB / 不碰磁盘，可直接跑）。
 *
 * <p>口径：确认位只保护「有内容」的字段。回归的是「空悬的确认」把扫描挡死 —— 行里
 * {@code svp_check=1} 而 {@code svp_file_name=null} 时，扫描既不敢覆盖（已确认），也无从检测
 * 文件是否还在（文件名为空），于是磁盘上真实存在的 {@code [工程] xxx.svp} 永远进不了库，
 * 页面上表现为「模板扫描漏文件」。
 */
class SongTemplateServiceConfirmTest {

    @Test
    void confirmedAndNonBlankProtects() {
        assertTrue(SongTemplateService.confirmsValue(true, "[工程] 红马.svp"));
    }

    @Test
    void confirmedButBlankIsNotAConfirmation() {
        assertFalse(SongTemplateService.confirmsValue(true, null));
        assertFalse(SongTemplateService.confirmsValue(true, ""));
        assertFalse(SongTemplateService.confirmsValue(true, "   "));
    }

    @Test
    void notConfirmedDoesNotProtect() {
        assertFalse(SongTemplateService.confirmsValue(false, "[工程] 红马.svp"));
        assertFalse(SongTemplateService.confirmsValue(null, "[工程] 红马.svp"));
    }

    @Test
    void neitherConfirmedNorNonBlankDoesNotProtect() {
        assertFalse(SongTemplateService.confirmsValue(null, null));
        assertFalse(SongTemplateService.confirmsValue(false, ""));
    }
}
