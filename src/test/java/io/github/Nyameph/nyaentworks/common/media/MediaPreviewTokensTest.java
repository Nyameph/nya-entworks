package io.github.Nyameph.nyaentworks.common.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 媒体端点的临时预览令牌（2026-09-25 加，原曲表单导入前试听）。
 *
 * <p><b>不连库、不碰 {@code F:\}</b>：{@link TempDir} 里摆几个文件，受管根给空表
 * —— 于是「受管根内」这条永远不成立，能放行的只可能是令牌那条路。
 *
 * <p>盯的是三件必须成立的事：
 * <ol>
 *   <li><b>只放行签过的那一个路径</b>：拿 A 的令牌读 B 必须不行（否则令牌就等于
 *       「读这个目录」甚至「读这个盘」，而它换来的放行面本来只是「试听这一个文件」）；</li>
 *   <li><b>重启即失效</b>：另一个实例（＝另一次启动）给同一路径签的串验不过；</li>
 *   <li><b>扩展名白名单永远先判</b>：带着合法令牌也不给读 {@code .txt} —— 那是这个端点
 *       是「读本机任意文件」还是「读本机任意音视频」的分界。</li>
 * </ol>
 */
class MediaPreviewTokensTest {

    @TempDir
    Path tmp;

    private final MediaPreviewTokens tokens = new MediaPreviewTokens();

    private Path put(String name) throws IOException {
        return Files.writeString(tmp.resolve(name), "x", StandardCharsets.UTF_8);
    }

    @Test
    void sign_samePathSameToken_everyTime() throws IOException {
        Path song = put("甲 - 乙.mp3");
        assertEquals(tokens.sign(song.toString()), tokens.sign(song.toString()),
                "同一路径恒得同一个串 —— 前端反复重画时 URL 不该抖");
        assertNotNull(tokens.sign(song.toString()));
    }

    @Test
    void matches_acceptsItsOwnToken() throws IOException {
        Path song = put("甲 - 乙.mp3");
        assertTrue(tokens.matches(Path.of(song.toString()).toAbsolutePath().normalize(),
                tokens.sign(song.toString())));
    }

    @Test
    void matches_rejectsTokenSignedForAnotherPath() throws IOException {
        Path a = put("甲 - 乙.mp3");
        Path b = put("只有一个字的.mp3");
        Path target = Path.of(b.toString()).toAbsolutePath().normalize();
        assertFalse(tokens.matches(target, tokens.sign(a.toString())),
                "拿着 A 的令牌读 B —— 令牌只该开它签的那一个路径");
    }

    @Test
    void matches_survivesPathSpelling() throws IOException {
        // 前端拿到的路径可能带 . 或 .. 段（壳递回来的、粘贴进来的都可能），
        // 校验那一侧会先 normalize —— 两边不烙到同一个串上的症状是「签了却永远验不过」
        Path song = put("甲 - 乙.mp3");
        String signed = tokens.sign(song.toString());
        Path spelled = Path.of(tmp.toString(), ".", "甲 - 乙.mp3");
        assertTrue(tokens.matches(spelled.toAbsolutePath().normalize(), signed));
    }

    @Test
    void matches_rejectsGarbageAndBlank() throws IOException {
        Path song = put("甲 - 乙.mp3");
        Path target = Path.of(song.toString()).toAbsolutePath().normalize();
        assertFalse(tokens.matches(target, "不是十六进制"), "伪造串当拒绝处理，不抛");
        assertFalse(tokens.matches(target, ""));
        assertFalse(tokens.matches(target, null));
        assertFalse(tokens.matches(null, tokens.sign(song.toString())));
    }

    @Test
    void matches_otherProcessKeyDoesNotVerify() throws IOException {
        Path song = put("甲 - 乙.mp3");
        Path target = Path.of(song.toString()).toAbsolutePath().normalize();
        assertFalse(tokens.matches(target, new MediaPreviewTokens().sign(song.toString())),
                "密钥是启动时随机生成的：重启后前端手里那些旧 URL 全部作废");
    }

    @Test
    void stream_outsideManagedRoot_tokenOpensIt() throws IOException {
        MediaStreamService service = new MediaStreamService(List.of(), tokens);
        Path song = put("甲 - 乙.mp3");
        assertNotNull(service.stream(song.toString(), tokens.sign(song.toString())).getBody(),
                "受管根给空表 + 令牌对得上 → 放行（原曲表单的源文件就是这么播的）");
        assertThrows(IllegalArgumentException.class, () -> service.stream(song.toString()),
                "不带令牌时受管根空表 → 拒绝（这条是原本的行为，没有被令牌改动）");
    }

    @Test
    void stream_extensionWhitelistComesFirst_evenWithAValidToken() throws IOException {
        MediaStreamService service = new MediaStreamService(List.of(), tokens);
        Path text = put("歌词.lrc");
        String token = tokens.sign(text.toString());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.stream(text.toString(), token));
        assertTrue(e.getMessage().contains("不是认识的音视频"), e.getMessage());
        assertNull(tokens.sign(""), "空路径不给签");
    }

    @Test
    void stream_missingFileWithToken_stillRefuses() throws IOException {
        MediaStreamService service = new MediaStreamService(List.of(), tokens);
        Path gone = Path.of(tmp.toString(), "不在了.mp3");
        String token = tokens.sign(gone.toString());
        assertThrows(IllegalStateException.class, () -> service.stream(gone.toString(), token),
                "签过的路径也要真在盘上 —— 令牌不是「这文件存在」的证明");
    }
}
