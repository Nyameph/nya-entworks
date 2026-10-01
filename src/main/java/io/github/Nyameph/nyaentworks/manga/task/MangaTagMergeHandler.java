package io.github.Nyameph.nyaentworks.manga.task;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.manga.service.MangaTagAdminService;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskContext;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskHandler;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnManga;

/**
 * 标签合并：把源标签并进目标标签，连带改归档目录名。
 */
@ConditionalOnManga
@Component
@RequiredArgsConstructor
public class MangaTagMergeHandler implements AsyncTaskHandler {

    public static final String TYPE = "manga.tag.merge";

    private final MangaTagAdminService tagAdminService;

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
        return "合并标签";
    }

    @Override
    public Class<?> paramType() {
        return MangaTagAdminService.MergeParams.class;
    }

    @Override
    public Object run(Object params, AsyncTaskContext context) {
        MangaTagAdminService.MergeParams p = (MangaTagAdminService.MergeParams) params;
        context.message("正在合并标签并改目录名…");
        return tagAdminService.applyMerge(p.sourceTagId(), p.targetTagId());
    }

    @Override
    public String summarizeResult(Object result) {
        MangaTagAdminService.TagApplyResult r = (MangaTagAdminService.TagApplyResult) result;
        return "合并标签，改 " + r.renamedFolders() + " 个目录，移 " + r.movedRefs() + " 个关联";
    }
}
