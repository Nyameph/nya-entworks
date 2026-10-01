package io.github.Nyameph.nyaentworks.song.fill.rhyme;

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
import io.github.Nyameph.nyaentworks.song.entity.RhymeEntry;
import io.github.Nyameph.nyaentworks.song.task.RhymeDedupHandler;
import io.github.Nyameph.nyaentworks.song.task.RhymePosHandler;
import io.github.Nyameph.nyaentworks.song.task.RhymeWordlistHandler;
import io.github.Nyameph.nyaentworks.song.task.SongRhymeSeedHandler;

import java.util.List;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnSong;

/**
 * 韵脚词典接口（{@code /api/song/rhyme/*}，填词助手设计 §4.6）。
 *
 * <p>统一 {@code ApiResult}，失败也是 HTTP 200（项目硬约束）。锚认不出不算错误：
 * {@code /entries} 返回 0 条 + {@code EntryPage.message}（放在 data 里而不是 {@code ApiResult.message}，
 * 因为 {@code success} 仍是 true —— 这是「这个锚没认出」而不是「这次调用失败了」），
 * 让前端好展示「认不出这个锚」。维护端点只写 {@code rhyme_entry}，MODERN（种子）行不允许删。
 *
 * <p>词典页的「管理」页签整块去掉，下面四个端点已随之删除，<b>别再往回加</b>：
 * {@code GET /stats}（来源统计）、{@code GET /entries/list}（列表）、{@code POST /entries}（单条添加）、
 * {@code POST /import}（词表导入，含「配置的文件」分支）。人工补录统一走 {@code /entries/batch}
 * （粘贴多行，失败的逐行回报）。
 *
 * <p><b>{@code /import-open} 不是把 {@code POST /import} 加回来</b>（2026-09-14 新增，§12）：
 * 那条旧的「词表导入」要人在配置里指一个仓库外的文件、格式随人变，所以整条链连同管理页一起去掉了。
 * {@code /import-open} 读的是<b>仓库内固定的一份</b> classpath 词表（{@code rhyme/open-rhyme-words.tsv}，
 * 来源与口径都写在文件头），无参数、幂等、可重跑，与「重新生成单字词典」是同一类运维动作。
 */
@Tag(name = "韵脚词典")
@ConditionalOnSong
@RestController
@RequestMapping("/api/song/rhyme")
@RequiredArgsConstructor
public class RhymeController {

    private final RhymeService rhymeService;
    private final io.github.Nyameph.nyaentworks.task.service.AsyncTaskService taskService;

    @Operation(summary = "韵部总表 + 粒度/词性/来源清单：一次拉全，前端缓存")
    @GetMapping("/meta")
    public ApiResult<RhymeService.Meta> meta() {
        return ApiResult.ok(rhymeService.meta());
    }

    @Operation(summary = "按锚查词典：锚是汉字（展开全部读音）或韵母/韵部名；来源可多选")
    @GetMapping("/entries")
    public ApiResult<RhymeService.EntryPage> entries(
            @RequestParam String anchor,
            @RequestParam(required = false) String granularity,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String wordClass,
            @RequestParam(required = false) List<String> source,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer limit) {
        return ApiResult.ok(rhymeService.entries(anchor, granularity, type, wordClass, source, q, limit));
    }

    // ==================== 维护（只写 rhyme_entry） ====================

    @Operation(summary = "批量粘贴入库：每行「字词」或「字词 拼音」；失败明细逐行回报")
    @PostMapping("/entries/batch")
    public ApiResult<RhymeService.BatchResult> addBatch(
            @RequestBody RhymeService.BatchRequest request) {
        return ApiResult.ok(rhymeService.addBatch(request));
    }

    @Operation(summary = "只改词性 / 备注（带 text 或 pinyin 的请求被拒：等于换了一条，要删了重加）")
    @PutMapping("/entries/{id}")
    public ApiResult<RhymeEntry> update(@PathVariable long id,
                                        @RequestBody RhymeService.UpdateRequest request) {
        return ApiResult.ok(rhymeService.updateNote(id, request));
    }

    /**
     * 批量改词性。路径字面量 {@code word-class} 与 {@code /entries/{id}} 的模板不冲突：
     * Spring 的路径匹配里字面段优先于变量段，所以这条不会被上面那条吃掉。
     */
    @Operation(summary = "批量改词性：把勾选的多条一次改成同一个词性（不传词性 = 清空）")
    @PutMapping("/entries/word-class")
    public ApiResult<Integer> updateWordClass(@RequestBody RhymeService.WordClassRequest request) {
        return ApiResult.ok(rhymeService.updateWordClass(request));
    }

    @Operation(summary = "删除：按 ids 或按来源（CORPUS/MANUAL/XLSX）；MODERN 拒绝")
    @DeleteMapping("/entries")
    public ApiResult<Integer> delete(@RequestBody RhymeService.DeleteRequest request) {
        return ApiResult.ok(rhymeService.delete(request));
    }

    @Operation(summary = "重新生成单字词典：提交异步任务（INSERT IGNORE 只补新增）")
    @PostMapping("/seed")
    public ApiResult<Long> seed() {
        return ApiResult.ok(taskService.submit(SongRhymeSeedHandler.TYPE, null));
    }

    @Operation(summary = "补全词性：提交异步任务（只调本机 Ollama，词条不出本机）")
    @PostMapping("/pos")
    public ApiResult<Long> pos() {
        return ApiResult.ok(taskService.submit(RhymePosHandler.TYPE, null));
    }

    @Operation(summary = "导入开源词表：提交异步任务（INSERT IGNORE，只补新增、幂等）")
    @PostMapping("/import-open")
    public ApiResult<Long> importOpen() {
        return ApiResult.ok(taskService.submit(RhymeWordlistHandler.TYPE, null));
    }

    @Operation(summary = "合并重复词：把同词同音的重复行并成一行（幂等、可重跑）")
    @PostMapping("/dedup")
    public ApiResult<Long> dedup() {
        return ApiResult.ok(taskService.submit(RhymeDedupHandler.TYPE, null));
    }
}
