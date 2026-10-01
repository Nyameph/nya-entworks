package io.github.Nyameph.nyaentworks.song.fill.corpus;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.github.Nyameph.nyaentworks.common.ApiResult;
import io.github.Nyameph.nyaentworks.song.fill.LyricFillAligner;
import io.github.Nyameph.nyaentworks.song.fill.ai.FillAiPrompts;
import io.github.Nyameph.nyaentworks.song.task.SongCorpusRescanHandler;

import java.util.List;
import java.util.stream.Collectors;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnSong;

/**
 * 语料接口（{@code /api/song/corpus/*}，填词助手设计 §5.5 / §5.7）。
 *
 * <p>重扫是分钟级写操作、走异步任务（提交后立刻返回任务 id，前端提示去任务页看进度，
 * 不要等它跑完）。
 *
 * <p><b>没有「按韵检索语料句」的端点</b>：底层 {@link CorpusService#searchLines} 是活的
 * （AI 填词的 few-shot 取材走它，见 {@code FillAiService}），但从没有页面调它，
 * 原先那个 {@code GET /lines} 是零消费的死端点，已删。
 */
@Tag(name = "语料")
@ConditionalOnSong
@RestController
@RequestMapping("/api/song/corpus")
@RequiredArgsConstructor
public class CorpusController {

    private final CorpusService corpusService;
    private final io.github.Nyameph.nyaentworks.task.service.AsyncTaskService taskService;

    @Operation(summary = "重扫歌词语料：删掉重建，返回任务 id（分钟级，去任务页看进度）")
    @PostMapping("/rescan")
    public ApiResult<Long> rescan() {
        return ApiResult.ok(taskService.submit(SongCorpusRescanHandler.TYPE, null));
    }

    /**
     * 导出微调素材（JSONL，每行一条 messages 三元组）。接口只返回文本、不落磁盘；
     * 下载由前端做（Blob + a[download]）。注意：返回的是<b>原始 JSONL</b>，不走 ApiResult 包装。
     *
     * <p><b>user 段与推理期同口径</b>（§5.7 要求同 §6.2）：拼装走
     * {@link FillAiPrompts#userSection} —— 序号<b>批内从 1 起</b>（训练素材是逐句样本，配对的
     * 那一句就是「第 1 句」，用库里的绝对句下标与推理期对不上）、尾韵走
     * {@link FillAiPrompts#rhymeLabelOf}（{@code 十六唐/ang/iang/uang}，与推理期同一个写法）、
     * 主题行放占位（{@code lyric_corpus_pair} 没有主题列，不为它改表，只保持形状一致）。
     */
    @Operation(summary = "导出原词→新词配对的 JSONL（微调素材，本期只铺素材不做训练）")
    @GetMapping(value = "/pairs.jsonl", produces = "application/jsonl;charset=utf-8")
    public ResponseEntity<String> pairsJsonl(@RequestParam(required = false) Integer limit) {
        String system = FillAiPrompts.systemLocal();
        String body = corpusService.pairsForExport(limit == null ? 10000 : limit).stream()
                .map(pair -> {
                    String user = FillAiPrompts.userSection(null,
                            List.of(FillAiPrompts.sentenceLine(1,
                                    unitCount(pair.getOriginalText()), pair.getYun18(),
                                    pair.getOriginalText(), englishWords(pair.getOriginalText()))),
                            List.of());
                    String assistant = "[\"" + pair.getFilledText()
                            .replace("\\", "\\\\").replace("\"", "\\\"") + "\"]";
                    return "{\"messages\":["
                            + jsonMessage("system", system) + ","
                            + jsonMessage("user", user) + ","
                            + jsonMessage("assistant", assistant) + "]}";
                })
                .collect(Collectors.joining("\n"));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/jsonl;charset=utf-8"))
                .body(body + (body.isEmpty() ? "" : "\n"));
    }

    private static String jsonMessage(String role, String content) {
        return "{\"role\":\"" + role + "\",\"content\":\""
                + content.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "")
                + "\"}";
    }

    /** 字数口径与 §6.3 一致：一个汉字算 1，一个英文单词算 1。 */
    private static int unitCount(String text) {
        return LyricFillAligner.countUnits(text);
    }

    /** 「含英文 K 词」的 K：与推理期 {@code FillAiService.readLines} 同一个口径（整词算一格）。 */
    private static int englishWords(String text) {
        return (int) LyricFillAligner.tokenize(text).stream()
                .filter(LyricFillAligner::hasAsciiLetter).count();
    }
}
