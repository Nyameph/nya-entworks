package io.github.Nyameph.nyaentworks.manga.task;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.manga.service.MangaTagAdminService;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskContext;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskHandler;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnManga;

/**
 * 标签重命名：连带改归档目录名。新名已存在时按合并处理。
 * <p>目录移动不可回滚，走后台任务是为了不占着请求线程逐目录改名。
 */
@ConditionalOnManga
@Component
@RequiredArgsConstructor
public class MangaTagRenameHandler implements AsyncTaskHandler {

    public static final String TYPE = "manga.tag.rename";

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
        return "重命名标签";
    }

    @Override
    public Class<?> paramType() {
        return MangaTagAdminService.RenameParams.class;
    }

    @Override
    public Object run(Object params, AsyncTaskContext context) {
        MangaTagAdminService.RenameParams p = (MangaTagAdminService.RenameParams) params;
        context.message("正在重命名标签并改目录名…");
        return tagAdminService.applyRename(p.tagId(), p.newTagName());
    }

    @Override
    public String summarizeResult(Object result) {
        MangaTagAdminService.TagApplyResult r = (MangaTagAdminService.TagApplyResult) result;
        return "重命名标签「" + r.newTagName() + "」，改 " + r.renamedFolders() + " 个目录";
    }
}
