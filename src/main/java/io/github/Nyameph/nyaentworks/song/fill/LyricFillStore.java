package io.github.Nyameph.nyaentworks.song.fill;

import com.alibaba.fastjson2.JSON;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code song_lyric_fill} 四个 TEXT 列（{@code notes_json} / {@code lines_json} /
 * {@code filled_json} / {@code gaps_json}）的读写。
 *
 * <p>用 fastjson2（项目解析 JSON 的既有选择，见 {@code SongResourceSearchService}），
 * 不引第三套 JSON 库。结构是 {@link LyricTemplate} 里的嵌套 record，fastjson2 原生支持 record。
 */
public final class LyricFillStore {

    private LyricFillStore() {
    }

    public static String toJson(Object value) {
        return JSON.toJSONString(value);
    }

    /** 骨架（全部歌唱轨）。列损坏 / 为空时返回空列表，由调用方决定是否重新解析。 */
    public static List<LyricTemplate.FillTrack> readTracks(String json) {
        if (StringUtils.isBlank(json)) {
            return List.of();
        }
        return JSON.parseArray(json, LyricTemplate.FillTrack.class);
    }

    public static List<LyricTemplate.FillLine> readLines(String json) {
        if (StringUtils.isBlank(json)) {
            return List.of();
        }
        return JSON.parseArray(json, LyricTemplate.FillLine.class);
    }

    /**
     * 填词：每句一个列表，与那一句的槽位一一对应（空串 = 没填）。
     *
     * <p>旧格式（一句一个稠密字符串）读不出来对应关系，按「这一句没填」处理，
     * 不抛异常 —— 列坏了只该让页面少显示点内容，不该白屏。
     */
    public static List<List<String>> readFilled(String json) {
        if (StringUtils.isBlank(json)) {
            return List.of();
        }
        List<Object> raw = JSON.parseArray(json);
        List<List<String>> result = new ArrayList<>(raw.size());
        for (Object item : raw) {
            if (!(item instanceof List<?> values)) {
                result.add(List.of());
                continue;
            }
            List<String> line = new ArrayList<>(values.size());
            for (Object value : values) {
                line.add(value == null ? "" : String.valueOf(value));
            }
            result.add(line);
        }
        return result;
    }

    /**
     * 视觉空位（句内空格）：每句一个布尔列表，与那一句的槽位一一对应（{@code true} = 这一格
     * <b>之后</b>画一个空位）。
     *
     * <p>空 / 读不出来返回空列表 —— 调用方按「没编辑过」处理、退回现算（{@code resolveGaps}）。
     * 单格里非布尔的（手改过的脏数据 / 旧格式）按 {@code false} 算，与 {@code readFilled}
     * 一样只让数据瘦一点，不抛异常。
     */
    public static List<List<Boolean>> readGaps(String json) {
        if (StringUtils.isBlank(json)) {
            return List.of();
        }
        List<Object> raw = JSON.parseArray(json);
        List<List<Boolean>> result = new ArrayList<>(raw.size());
        for (Object item : raw) {
            if (!(item instanceof List<?> values)) {
                result.add(List.of());
                continue;
            }
            List<Boolean> line = new ArrayList<>(values.size());
            for (Object value : values) {
                line.add(Boolean.TRUE.equals(value));
            }
            result.add(line);
        }
        return result;
    }
}
