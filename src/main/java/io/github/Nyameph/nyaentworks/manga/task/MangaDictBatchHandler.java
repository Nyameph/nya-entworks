package io.github.Nyameph.nyaentworks.manga.task;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.manga.entity.MangaDictEntry;
import io.github.Nyameph.nyaentworks.manga.service.MangaDictService;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskContext;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskHandler;

import java.util.ArrayList;
import java.util.List;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnManga;

/**
 * 批量补录词典：同类型同值的自动跳过。
 * <p>把原始请求值还原成词条再交给 {@link MangaDictService#saveBatch}（它写完立即 reload）。
 */
@ConditionalOnManga
@Component
@RequiredArgsConstructor
public class MangaDictBatchHandler implements AsyncTaskHandler {

    public static final String TYPE = "manga.dict.batch";

    private final MangaDictService dictService;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String module() {
        return MangaTaskModule.MANGA;
    }

    @Override
    public String taskName() {
        return "批量补录词典";
    }

    @Override
    public Class<?> paramType() {
        return MangaDictService.BatchParams.class;
    }

    @Override
    public Object run(Object params, AsyncTaskContext context) {
        MangaDictService.BatchParams p = (MangaDictService.BatchParams) params;
        if (p.values() == null || p.values().isEmpty()) {
            throw new IllegalArgumentException("没有要补录的词条");
        }
        List<MangaDictEntry> entries = new ArrayList<>();
        for (String value : p.values()) {
            if (value == null || value.isBlank()) {
                continue;
            }
            MangaDictEntry entry = new MangaDictEntry();
            entry.setDictType(p.dictType());
            entry.setMatchMode(p.matchMode());
            entry.setIgnoreCase(p.ignoreCase());
            entry.setDictValue(value);
            entry.setRemark(p.remark());
            entries.add(entry);
        }
        context.message("正在补录 " + entries.size() + " 条词典…");
        return dictService.saveBatch(entries);
    }

    @Override
    public String summarizeResult(Object result) {
        return "保存 " + result + " 条词条";
    }
}
