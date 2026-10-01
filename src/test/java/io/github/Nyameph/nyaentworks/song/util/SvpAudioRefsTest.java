package io.github.Nyameph.nyaentworks.song.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SvpAudioRefs} 的单测：{@code @TempDir} 里自己写假的 svp 文本，不碰 {@code F:\}。
 *
 * <p>其中「尾部带 {@code \0} 能解析出来」那条是在钉那个 {@code lastIndexOf('}') + 1}
 * —— 少了 1 会恒失败，而失败被设计成返回空列表（不抛），所以一眼看不出来。
 */
class SvpAudioRefsTest {

    @TempDir
    Path dir;

    private Path svp(String text) throws IOException {
        Path file = dir.resolve("工程.svp");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void read_oneRef_returnsFilenameVerbatim() throws IOException {
        Path file = svp("""
                {"tracks":[{"name":"伴奏","mainRef":{"audio":{"filename":"[伴奏] 乙.mp3","duration":1.0}}}]}""");
        List<SvpAudioRefs.AudioRef> refs = SvpAudioRefs.read(file);
        assertEquals(1, refs.size());
        assertEquals("伴奏", refs.get(0).trackName());
        assertEquals("[伴奏] 乙.mp3", refs.get(0).filename());
    }

    @Test
    void read_blankOrMissingFilename_notCounted() throws IOException {
        Path blank = svp("""
                {"tracks":[{"name":"伴奏","mainRef":{"audio":{}}},
                           {"name":"人声","mainRef":{"audio":{"filename":""}}},
                           {"name":"和声","mainRef":{"audio":{"filename":"   "}}}]}""");
        assertTrue(SvpAudioRefs.read(blank).isEmpty());
    }

    @Test
    void read_noAudioKey_notCounted() throws IOException {
        Path file = svp("""
                {"tracks":[{"name":"空轨"},{"name":"只有主引用","mainRef":{"groupID":"g"}}]}""");
        assertTrue(SvpAudioRefs.read(file).isEmpty());
    }

    @Test
    void read_audioNotAnObject_notCounted() throws IOException {
        // 旧文档把 mainRef.audio 当成「有无音频」的布尔（本文 §0.2 第 8 条纠正过），
        // 真写成布尔也得当「没有引用」而不是炸掉整份解析
        Path file = svp("""
                {"tracks":[{"name":"甲","mainRef":{"audio":true}},
                           {"name":"乙","mainRef":{"audio":"[伴奏] 乙.mp3"}}]}""");
        assertTrue(SvpAudioRefs.read(file).isEmpty());
    }

    @Test
    void read_trackNameMissing_isEmptyString() throws IOException {
        Path file = svp("""
                {"tracks":[{"mainRef":{"audio":{"filename":"[伴奏] 乙.mp3"}}}]}""");
        List<SvpAudioRefs.AudioRef> refs = SvpAudioRefs.read(file);
        assertEquals(1, refs.size());
        assertEquals("", refs.get(0).trackName());
    }

    @Test
    void read_absolutePaths_bothSlashStyles_keptVerbatim() throws IOException {
        Path file = svp("""
                {"tracks":[{"name":"甲","mainRef":{"audio":{"filename":"F:\\\\歌曲\\\\模板\\\\[伴奏] 乙.mp3"}}},
                           {"name":"乙","mainRef":{"audio":{"filename":"F:/歌曲/模板/[人声] 乙.mp3"}}}]}""");
        List<SvpAudioRefs.AudioRef> refs = SvpAudioRefs.read(file);
        assertEquals(2, refs.size());
        assertEquals("F:\\歌曲\\模板\\[伴奏] 乙.mp3", refs.get(0).filename());
        assertEquals("F:/歌曲/模板/[人声] 乙.mp3", refs.get(1).filename());
    }

    @Test
    void read_trailingNulByte_stillParses() throws IOException {
        // 真实 svp 的尾巴：\0 正好在最后。这条钉住「裁到最后一个 }（含）」那个 +1
        Path file = svp("""
                {"tracks":[{"name":"伴奏","mainRef":{"audio":{"filename":"[伴奏] 乙.mp3"}}}]}""" + "\0");
        List<SvpAudioRefs.AudioRef> refs = SvpAudioRefs.read(file);
        assertEquals(1, refs.size());
        assertEquals("[伴奏] 乙.mp3", refs.get(0).filename());
    }

    @Test
    void read_missingFileOrBrokenJson_emptyListNoThrow() throws IOException {
        assertTrue(SvpAudioRefs.read(dir.resolve("不存在.svp")).isEmpty());
        assertTrue(SvpAudioRefs.read(svp("这不是 JSON")).isEmpty());
        assertTrue(SvpAudioRefs.read(svp("")).isEmpty());
        assertTrue(SvpAudioRefs.read(null).isEmpty());
        // 合法 JSON 但没有 tracks（比如作者只存了个 tempo）
        assertTrue(SvpAudioRefs.read(svp("{\"time\":{\"tempo\":[{\"bpm\":120}]}}")).isEmpty());
    }
}
