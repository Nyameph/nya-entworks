package io.github.Nyameph.nyaentworks.song.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.github.Nyameph.nyaentworks.common.ApiResult;
import io.github.Nyameph.nyaentworks.song.fill.LyricFillService;
import io.github.Nyameph.nyaentworks.song.fill.LyricTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnSong;

/**
 * 填词接口（{@code /api/song/fill/*}，填词工具设计 8）。
 *
 * <p>一个原曲可以有多份填词（多对一），所以除「列表 / 新建」之外的端点都认 {@code fillId}：
 * 页面先 {@code /list} 列出填词项目，点一行拿到 {@code fillId} 再 {@code /resolve} 打开，
 * 之后保存 / 导出 / 重解析都用它。
 *
 * <p>全部端点都是**同步**的：单项目、不搬磁盘文件。字数 / 切分不单独设端点 ——
 * 分析随 {@code /resolve} 下发，前端每键即时算（软提示，不拦截保存）。
 *
 * <p>轨道勾选用逗号分隔字符串传，与 {@code song_lyric_fill.track_indices} 的存储形态一致。
 */
@Tag(name = "填词")
@ConditionalOnSong
@RestController
@RequestMapping("/api/song/fill")
@RequiredArgsConstructor
public class LyricFillController {

    private final LyricFillService fillService;

    @Operation(summary = "打开填词：指定 fillId，或该原曲最近的一份；一份都没有就新建")
    @GetMapping("/resolve")
    public ApiResult<LyricTemplate> resolve(@RequestParam Long originalId,
                                            @RequestParam(required = false) Long fillId,
                                            @RequestParam(required = false) String trackIndices,
                                            @RequestParam(required = false) Double offsetSeconds,
                                            @RequestParam(required = false) Boolean realign) {
        return ApiResult.ok(fillService.resolve(originalId, fillId, parseIndices(trackIndices),
                offsetSeconds, Boolean.TRUE.equals(realign)));
    }

    @Operation(summary = "填词项目列表：给 originalId 就只列这一首的，不给列全部")
    @GetMapping("/list")
    public ApiResult<List<LyricFillService.FillSummary>> list(
            @RequestParam(required = false) Long originalId) {
        return ApiResult.ok(fillService.list(originalId));
    }

    /** 新建请求：{@code name} 空着就自动叫「填词 N」 */
    public record CreateRequest(Long originalId, String name) {
    }

    @Operation(summary = "新建一份填词（同一原曲可以有很多份）")
    @PostMapping("/create")
    public ApiResult<LyricTemplate> create(@RequestBody CreateRequest request) {
        return ApiResult.ok(fillService.create(request.originalId(), request.name()));
    }

    /**
     * 保存请求：项目名 + 勾选的轨 + 分句 + 每句的槽位值 + 视觉空位。
     *
     * @param gaps 每句的视觉空位（句内空格），与 {@code lines} 同形状；null / 形状对不上 =
     *             「这一份没编辑过空位」，库里存 NULL、之后一切按现算（见
     *             {@code LyricFillService#save}）
     */
    public record SaveRequest(Long fillId, String name, String trackIndices,
                              List<LyricTemplate.FillLine> lines, List<List<String>> filled,
                              List<List<Boolean>> gaps) {
    }

    @Operation(summary = "保存填词（名字 + 勾选 + 分句 + 填词 + 视觉空位一起落库）")
    @PostMapping("/save")
    public ApiResult<LyricTemplate> save(@RequestBody SaveRequest request) {
        return ApiResult.ok(fillService.save(request.fillId(), request.name(),
                parseIndices(request.trackIndices()), request.lines(), request.filled(),
                request.gaps()));
    }

    /**
     * 导出请求。
     *
     * @param type     {@code text} 回填模板文本 / {@code lrc} 歌词文件
     * @param offsetMs 写进 lrc 头的 {@code [offset:±ms]}，null 不写
     * @param swaps    多音字的回填替换：「轨号:音符下标」→ 单音常用字。只影响回填模板文本
     *                 （SynthV 照单音字注音不会错），歌词本体不动
     * @param gaps     前端当前（可能未保存）的视觉空位，与 {@code lines} 同形状；形状对不上 /
     *                 null 就退用库里存的、再退回现算 —— 导出 lrc 的句内空格按它还原
     * @param pinyin   「导出拼音」开关：回填模板文本里的汉字转成无声调拼音（多音字取常用读音，
     *                 回填替换过的按替换字读）。lrc 不受影响。null / false = 原样
     */
    public record ExportRequest(Long fillId, String type, Integer offsetMs,
                                List<LyricTemplate.FillLine> lines, List<List<String>> filled,
                                List<List<Boolean>> gaps,
                                Map<String, String> swaps, Boolean pinyin) {
    }

    @Operation(summary = "导出：回填模板文本（按轨各一份，空格分隔）或 lrc（歌词头 + 正文）")
    @PostMapping("/export")
    public ApiResult<LyricFillService.ExportResult> export(@RequestBody ExportRequest request) {
        return ApiResult.ok(fillService.export(request.fillId(), request.type(), request.offsetMs(),
                request.lines(), request.filled(), request.gaps(), request.swaps(),
                Boolean.TRUE.equals(request.pinyin())));
    }

    /** 重新解析 / 删除请求 */
    public record FillRequest(Long fillId) {
    }

    @Operation(summary = "强制重读 svp 覆盖骨架（svp 改过时用）")
    @PostMapping("/reparse")
    public ApiResult<LyricTemplate> reparse(@RequestBody FillRequest request) {
        return ApiResult.ok(fillService.reparse(request.fillId()));
    }

    @Operation(summary = "删除一份填词（只删库里的行，不动磁盘）")
    @PostMapping("/delete")
    public ApiResult<String> delete(@RequestBody FillRequest request) {
        fillService.delete(request.fillId());
        return ApiResult.ok("已删除这份填词");
    }

    /** "0,1" → [0, 1]；空串 / null → 空列表（表示用库里存的 / 全部歌唱轨） */
    private static List<Integer> parseIndices(String csv) {
        if (StringUtils.isBlank(csv)) {
            return List.of();
        }
        List<Integer> result = new ArrayList<>();
        for (String piece : csv.split(",")) {
            String trimmed = piece.trim();
            if (!trimmed.isEmpty()) {
                result.add(Integer.parseInt(trimmed));
            }
        }
        return result;
    }
}
