package io.github.Nyameph.nyaentworks.manga.task;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.manga.service.MangaTagAdminService;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskContext;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskHandler;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnManga;

/**
 * 标签删除：先把它从相关归档目录名里去掉，再删库里的行与全部关联。
 */
@ConditionalOnManga
@Component
@RequiredArgsConstructor
public class MangaTagDeleteHandler implements AsyncTaskHandler {

    public static final String TYPE = "manga.tag.delete";

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
        return "删除标签";
    }

    @Override
    public Class<?> paramType() {
        return MangaTagAdminService.DeleteParams.class;
    }

    @Override
    public Object run(Object params, AsyncTaskContext context) {
        MangaTagAdminService.DeleteParams p = (MangaTagAdminService.DeleteParams) params;
        context.message("正在删除标签并改目录名…");
        return tagAdminService.applyDelete(p.tagId());
    }

    @Override
    public String summarizeResult(Object result) {
        MangaTagAdminService.TagApplyResult r = (MangaTagAdminService.TagApplyResult) result;
        return "删除标签「" + r.tagName() + "」，改 " + r.renamedFolders() + " 个目录";
    }
}
