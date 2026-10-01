package io.github.Nyameph.nyaentworks.manga.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.github.Nyameph.nyaentworks.common.ApiResult;
import io.github.Nyameph.nyaentworks.manga.consts.MangaDictMatchMode;
import io.github.Nyameph.nyaentworks.manga.consts.MangaDictType;
import io.github.Nyameph.nyaentworks.manga.entity.MangaDictEntry;
import io.github.Nyameph.nyaentworks.manga.service.MangaDictService;
import io.github.Nyameph.nyaentworks.manga.task.MangaDictBatchHandler;
import io.github.Nyameph.nyaentworks.task.service.AsyncTaskService;

import java.util.ArrayList;
import java.util.List;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnManga;

/**
 * 词典接口（文档 4.2）。
 * <p>词条改动都是秒级操作，且 {@code MangaDictService} 写完立即 reload，
 * 所以一律同步请求 —— 保存完当场生效，不需要「记得重新加载」这一步。
 */
@Tag(name = "漫画-词典")
@ConditionalOnManga
@RestController
@RequestMapping("/api/manga/dict")
@RequiredArgsConstructor
public class MangaDictController {

    private final MangaDictService dictService;
    private final AsyncTaskService taskService;

    /**
     * 一个词典类型的元信息，供页面画标签页与预填表单。
     *
     * @param label            中文名，代码里只有枚举名
     * @param defaultMatchMode 该类型补录时的默认匹配方式，见
     *                         {@link MangaDictService#defaultMatchMode}
     */
    public record TypeMeta(MangaDictType dictType, String label, String description,
                           MangaDictMatchMode defaultMatchMode) {
    }

    @Operation(summary = "词典类型清单，含中文名与默认匹配方式")
    @GetMapping("/types")
    public ApiResult<List<TypeMeta>> types() {
        List<TypeMeta> list = new ArrayList<>();
        for (MangaDictType type : MangaDictType.values()) {
            list.add(new TypeMeta(type, label(type), description(type),
                    MangaDictService.defaultMatchMode(type)));
        }
        return ApiResult.ok(list);
    }

    private static String label(MangaDictType type) {
        return switch (type) {
            case USELESS_TAG -> "无用标签";
            case UNCENSORED -> "無修正";
            case UNBOXING -> "拆括号";
            case MODIFIER -> "修饰标签";
            case PARODY -> "原作";
            case EXHIBIT -> "展会";
            case MAGAZINE -> "杂志";
            case IGNORE_CONTENT -> "忽略内容";
        };
    }

    private static String description(MangaDictType type) {
        return switch (type) {
            case USELESS_TAG -> "整体移除的标签";
            case UNCENSORED -> "命中即标记为無修正";
            case UNBOXING -> "需要拆开括号的标签";
            case MODIFIER -> "汉化组等，摘出后拼装时追加到末尾";
            case PARODY -> "原作名，条数最多、变体也最多";
            case EXHIBIT -> "展会，多为 C\\d+ 这类正则";
            case MAGAZINE -> "杂志名，只有前缀没有正则时靠年月号识别的会全部漏判";
            case IGNORE_CONTENT -> "取展会/原作时应跳过的括号内容";
        };
    }

    @Operation(summary = "分页列出词条，可按类型与关键词筛")
    @GetMapping
    public ApiResult<MangaDictService.DictPage> list(
            @RequestParam(required = false) MangaDictType type,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResult.ok(dictService.page(type, keyword, page, size));
    }

    @Operation(summary = "类型分布与无效正则，词典页顶部的诊断")
    @GetMapping("/summary")
    public ApiResult<MangaDictService.DictSummary> summary() {
        return ApiResult.ok(dictService.summary());
    }

    @Operation(summary = "非 NFC 词条：MySQL 判等而 Java 判不等的那些行")
    @GetMapping("/non-nfc")
    public ApiResult<List<MangaDictEntry>> nonNfc() {
        return ApiResult.ok(dictService.listNonNfcEntries());
    }

    @Operation(summary = "试探：这个字符串命中哪些类型、哪几条词条")
    @GetMapping("/probe")
    public ApiResult<MangaDictService.ProbeResult> probe(@RequestParam String text) {
        return ApiResult.ok(dictService.probe(text));
    }

    /**
     * 新增前的预检结果。
     *
     * @param similar     去掉分隔符后同名的既有词条（{@code Fate／Grand Order} 与
     *                    {@code FateGrand Order} 这类变体）
     * @param overlapping 已经能匹配这个值的既有词条，多半说明不必新增
     */
    public record ValueCheck(List<MangaDictEntry> similar, List<MangaDictEntry> overlapping) {
    }

    @Operation(summary = "补录前预检：相似词条与已能匹配的词条")
    @GetMapping("/check")
    public ApiResult<ValueCheck> check(@RequestParam MangaDictType type,
                                       @RequestParam String value) {
        return ApiResult.ok(new ValueCheck(
                dictService.findSimilar(type, value),
                dictService.findOverlapping(type, value)));
    }

    @Operation(summary = "新增词条，REGEX 当场校验可编译")
    @PostMapping
    public ApiResult<MangaDictEntry> create(@RequestBody MangaDictEntry entry) {
        entry.setId(null);
        return ApiResult.ok(dictService.save(entry));
    }

    @Operation(summary = "改词条")
    @PutMapping("/{id}")
    public ApiResult<MangaDictEntry> update(@PathVariable Long id,
                                            @RequestBody MangaDictEntry entry) {
        entry.setId(id);
        return ApiResult.ok(dictService.save(entry));
    }

    @Operation(summary = "删词条")
    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable Long id) {
        dictService.delete(id);
        return ApiResult.ok();
    }

    /**
     * @param values 一行一条，已存在的（同类型同值）自动跳过
     */
    public record BatchRequest(MangaDictType dictType, MangaDictMatchMode matchMode,
                               Boolean ignoreCase, String remark, List<String> values) {
    }

    @Operation(summary = "批量补录，同类型同值的自动跳过。异步，返回任务 id")
    @PostMapping("/batch")
    public ApiResult<Long> batch(@RequestBody BatchRequest request) {
        MangaDictService.BatchParams params = new MangaDictService.BatchParams(
                request == null ? null : request.dictType(),
                request == null ? null : request.matchMode(),
                request == null ? null : request.ignoreCase(),
                request == null ? null : request.remark(),
                request == null ? null : request.values());
        int count = request == null || request.values() == null ? 0 : request.values().size();
        return ApiResult.ok(taskService.submit(MangaDictBatchHandler.TYPE, params,
                "补录 " + count + " 条词典"));
    }

    @Operation(summary = "重新加载词典，返回新版本号")
    @PostMapping("/reload")
    public ApiResult<Long> reload() {
        return ApiResult.ok(dictService.reload().getVersion());
    }
}
