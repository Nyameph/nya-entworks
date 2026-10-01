package io.github.Nyameph.nyaentworks.song.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link SongResourceSearchService#bestMatch} 的纯函数测试（无 Spring / 无 DB，可直接跑）。
 * 覆盖：artist 感知打分、翻唱降权、试听片段剔除、0 分无关候选判 null（避免错填）。
 */
class SongResourceSearchServiceMatchTest {

    private static SongResourceSearchService.SongHit hit(String id, String name, String artist, int durationSec) {
        return new SongResourceSearchService.SongHit(id, name,
                artist == null ? List.of() : List.of(artist), durationSec);
    }

    @Test
    void exactTitleWins() {
        var candidates = List.of(
                hit("a", "晴天", "周杰伦", 269),
                hit("b", "晴天", "周杰伦", 120));
        var best = SongResourceSearchService.bestMatch("晴天", candidates, "周杰伦");
        assertNotNull(best);
        assertEquals("a", best.id());
    }

    @Test
    void artistHintPrefersMatchingArtist() {
        // 同名异曲：标题完全一样，只有歌手能区分
        var candidates = List.of(
                hit("a", "明天", "王力宏", 240),
                hit("b", "明天", "林俊杰", 250));
        var best = SongResourceSearchService.bestMatch("明天", candidates, "林俊杰");
        assertNotNull(best);
        assertEquals("b", best.id());
    }

    @Test
    void coverPenaltyKeepsOriginal() {
        // 原唱在前、翻唱紧随其后；翻唱标题带「DJ版」被降权
        var candidates = List.of(
                hit("a", "晴天", "周杰伦", 269),
                hit("b", "晴天 (DJ版)", "DJ小明", 300));
        var best = SongResourceSearchService.bestMatch("晴天", candidates, null);
        assertNotNull(best);
        assertEquals("a", best.id());
    }

    @Test
    void unrelatedCandidateRejectedWhenNoArtistHit() {
        // 标题毫无关系、artist 也未命中 → 判无结果（落到下一源），避免错填
        var candidates = List.of(hit("a", "完全无关的歌", "张三", 200));
        assertNull(SongResourceSearchService.bestMatch("晴天", candidates, null));
    }

    @Test
    void unrelatedCandidateKeptWhenArtistHit() {
        // 标题毫无关系但歌手对上：视为异名版本，保留兜底
        var candidates = List.of(hit("a", "Summer Breeze", "周杰伦", 200));
        var best = SongResourceSearchService.bestMatch("晴天", candidates, "周杰伦");
        assertNotNull(best);
        assertEquals("a", best.id());
    }

    @Test
    void clipDroppedWhenFullLengthExists() {
        // 候选里存在完整版（≥60s）时，剔除 <60s 的试听片段
        var candidates = List.of(
                hit("a", "晴天", "周杰伦", 30),
                hit("b", "晴天", "周杰伦", 269));
        var best = SongResourceSearchService.bestMatch("晴天", candidates, null);
        assertNotNull(best);
        assertEquals("b", best.id());
    }

    @Test
    void clipKeptWhenOnlyClipExists() {
        // 只有片段可选时保留，避免彻底拿不到
        var candidates = List.of(hit("a", "晴天", "周杰伦", 30));
        var best = SongResourceSearchService.bestMatch("晴天", candidates, null);
        assertNotNull(best);
        assertEquals("a", best.id());
    }

    @Test
    void multiArtistContainsHint() {
        // 「周杰伦」应命中「周杰伦 / 梁心颐」这类多歌手候选
        var candidates = List.of(hit("a", "珊瑚海", "周杰伦 / 梁心颐", 260));
        var best = SongResourceSearchService.bestMatch("珊瑚海", candidates, "周杰伦");
        assertNotNull(best);
        assertEquals("a", best.id());
    }
}
