package io.github.Nyameph.nyaentworks.song.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 读出 svp（SynthV 工程）里引用了<b>外部音频文件</b>的音轨。
 *
 * <p>实测结构（2026-09-24，{@code F:\歌曲\模板} 下 152 个 svp / 237 条引用，其中 43 条已空或失效）：
 * <pre>
 * "tracks": [ { "name": "伴奏", "mainRef": { "audio": { "filename": "[伴奏] 36.5°C.mp3",
 *                                                       "duration": 245.3 } } }, ... ]
 * </pre>
 * {@code filename} 可能是<b>同目录的文件名</b>（{@code [伴奏] 36.5°C.mp3}），也可能是
 * <b>绝对路径</b>（正反斜杠两种写法都见过）。所以这里<b>原样返回</b>，不判「是不是同目录文件名」
 * —— 那是调用方的事（原曲导入要把「这次会被改名 / 搬走的文件」与它对照）。
 *
 * <p><b>为什么本类不抛异常</b>：这个读取只服务「提醒用户 svp 的音频引用会失效」（只提醒、
 * 不替用户改 svp）。文件读不了 / JSON 坏了就返回空列表 —— 一次提醒的读取不该让整个
 * 导入 plan 挂掉。
 *
 * <p>用 fastjson2 而不是另引一套数据绑定：项目里另一个读 svp 的地方
 * （{@code SongTemplateService#bpmFromSvp}、{@code LyricFillParser}）就是 fastjson2，
 * 且这里只走 {@code tracks} 数组、不建 record 镜像。
 *
 * <p><b>不要用 {@code LyricFillParser} 改这里</b>（那边是填词解析，口径与生命周期都不同）。
 *
 * <p>纯静态、无状态 —— 单测用 {@code @TempDir} 里的假 svp 直接跑。
 */
public final class SvpAudioRefs {

    private SvpAudioRefs() {
    }

    /** svp 里的一条音频引用。{@code trackName} 是音轨名（取不到时是空串），
     *  {@code filename} <b>原样</b>（可能空、可能是绝对路径）。 */
    public record AudioRef(String trackName, String filename) {
    }

    /**
     * 读一个 svp 里所有「引用了音频」的音轨。
     *
     * <p>只有 {@code tracks[].mainRef.audio} <b>是对象且 {@code filename} 非空</b>才算一条引用
     * （{@code audio: {}}、{@code audio: {filename: ""}}、没有 {@code audio} 键都不算）。
     *
     * @return 顺序与文件里的 tracks 一致；读不了 / 解析失败 → 空列表（不抛）
     */
    public static List<AudioRef> read(Path svp) {
        if (svp == null) {
            return List.of();
        }
        String text;
        try {
            text = Files.readString(svp, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return List.of();   // 文件不在 / 读不动：这一项放弃
        }
        // 尾部那 1 字节 00：svp 文件末尾有个 \0，parseObject 会连它一起解析而失败。
        // 裁到最后一个 }（含），少这个 +1 会把结尾的大括号一起切掉、于是恒失败
        // —— 项目里踩过（速查索引「svp 尾部那 1 字节」）。
        int end = text.lastIndexOf('}');
        if (end < 0) {
            return List.of();
        }
        try {
            JSONObject root = JSON.parseObject(text.substring(0, end + 1));
            if (root == null || !(root.get("tracks") instanceof JSONArray tracks)) {
                return List.of();
            }
            List<AudioRef> refs = new ArrayList<>();
            for (int i = 0; i < tracks.size(); i++) {
                if (!(tracks.get(i) instanceof JSONObject track)
                        || !(track.get("mainRef") instanceof JSONObject mainRef)
                        || !(mainRef.get("audio") instanceof JSONObject audio)) {
                    continue;
                }
                if (!(audio.get("filename") instanceof String filename)
                        || StringUtils.isBlank(filename)) {
                    continue;   // 空 filename 不是引用（实测 43 条是这种）
                }
                refs.add(new AudioRef(track.get("name") instanceof String name ? name : "", filename));
            }
            return refs;
        } catch (Exception e) {
            return List.of();   // JSON 坏了：同上，不往上抛
        }
    }
}
