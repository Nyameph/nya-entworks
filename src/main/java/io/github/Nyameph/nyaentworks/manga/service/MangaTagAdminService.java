package io.github.Nyameph.nyaentworks.manga.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import io.github.Nyameph.nyaentworks.manga.consts.MangaTagTargetType;
import io.github.Nyameph.nyaentworks.manga.entity.MangaTag;
import io.github.Nyameph.nyaentworks.manga.service.MangaArchiveService.FolderRewrite;
import io.github.Nyameph.nyaentworks.common.fileop.FileOpRecorder;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpModule;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpSource;

import java.util.List;

/**
 * 标签的重命名、合并与删除 —— <b>连带改归档目录名</b>的那一层。
 * <p>为什么不能只改库：归档目录的标签是从目录名的 {@code 【…】} 块扫出来的，
 * 目录名才是权威。库里把「巨乳、」并进「巨乳」、目录名没动，下次
 * {@link MangaArchiveService#sync} 会照着目录名把旧标签原样建回来，
 * 表现为「合并过了又冒出来」。所以这三个操作一律走「先改目录名、再收拾库」。
 * <p>代价是改个标签要动文件夹名，稍显笨重；换来的是库与磁盘不会各说各话，
 * 而目录名是肉眼可见的那一份，漂移了很难发现。
 * <p>每个操作都拆成 {@code plan} 与 {@code apply} 两步：{@code plan} 只读，
 * 产出「哪些目录会改成什么名」，页面拿它当审查清单；确认后才 {@code apply}。
 * 目录移动不可回滚，这个闸门不是使用建议。
 */
@Service
@RequiredArgsConstructor
public class MangaTagAdminService {

    private final MangaTagService tagService;
    private final MangaArchiveService archiveService;

    /**
     * 一次标签改动的预演结果。
     *
     * @param action    RENAME / MERGE / DELETE，供页面组织措辞
     * @param merge     目标标签名已存在，本次实为合并
     * @param rewrites  要改的目录名清单，含改不了的（带 {@code blockedReason}）
     * @param mangaRefs 受影响的漫画标签关联数。漫画侧只在库里，没有目录名要改
     */
    public record TagPlan(String action, String tagName, String newTagName, boolean merge,
                          List<FolderRewrite> rewrites, long mangaRefs) {

        /** 有一条改不了就整批不执行，理由见 {@link MangaArchiveService#applyTagRewrite} */
        public boolean blocked() {
            return rewrites.stream().anyMatch(r -> r.blockedReason() != null);
        }
    }

    /**
     * @param renamedFolders 实际改名的目录数
     * @param movedRefs      合并时改指向目标标签的关联数
     */
    public record TagApplyResult(String action, String tagName, String newTagName,
                                 int renamedFolders, int movedRefs, boolean tagDeleted) {
    }

    /** 标签重命名的任务参数 */
    public record RenameParams(Long tagId, String newTagName) {
    }

    /** 标签合并的任务参数 */
    public record MergeParams(Long sourceTagId, Long targetTagId) {
    }

    /** 标签删除的任务参数 */
    public record DeleteParams(Long tagId) {
    }

    // ------------------------------------------------------------------
    // 预演
    // ------------------------------------------------------------------

    /** 重命名（或改成一个已存在的名字，那就是合并）的预演 */
    public TagPlan planRename(Long tagId, String newTagName) {
        MangaTag tag = tagService.require(tagId);
        // 预演阶段就校验：新名字带空格的话，拼出来的目录名下次同步会被拆成两个标签，
        // 等执行时才报错，用户已经照着一份错的清单点过确认了
        String newName = MangaTagService.requireTagName(newTagName);
        MangaTag exists = tagService.findByName(newName);
        boolean merge = exists != null && !exists.getId().equals(tagId);
        return new TagPlan(merge ? "MERGE" : "RENAME", tag.getTagName(), newName, merge,
                archiveService.planTagRewrite(tag.getTagName(), newName), countMangaRefs(tag));
    }

    /** 合并的预演，等价于「把源标签改成目标标签的名字」 */
    public TagPlan planMerge(Long sourceTagId, Long targetTagId) {
        MangaTag target = tagService.require(targetTagId);
        if (target.getId().equals(sourceTagId)) {
            throw new IllegalArgumentException("不能合并到自己");
        }
        return planRename(sourceTagId, target.getTagName());
    }

    /** 删除的预演：目录名里的该标签一并去掉 */
    public TagPlan planDelete(Long tagId) {
        MangaTag tag = tagService.require(tagId);
        return new TagPlan("DELETE", tag.getTagName(), null, false,
                archiveService.planTagRewrite(tag.getTagName(), null), countMangaRefs(tag));
    }

    // ------------------------------------------------------------------
    // 执行
    // ------------------------------------------------------------------

    /**
     * 执行重命名/合并。
     * <p>顺序是「先改目录、后收拾库」，因为改目录时
     * {@link MangaArchiveService#renameFolder} 会按新目录名重建标签关联 ——
     * 新标签在这一步自动建出来、旧标签的归档关联自动掉光。之后剩下的只有
     * 漫画侧关联与旧标签这一行，收尾即可。
     */
    public TagApplyResult applyRename(Long tagId, String newTagName) {
        TagPlan plan = planRename(tagId, newTagName);
        MangaTag source = tagService.require(tagId);

        if (plan.rewrites().isEmpty() && !plan.merge()) {
            // 没有任何归档目录用着它：目录名无从改起，直接改库里的行，保住 id 与备注
            tagService.renameRow(tagId, plan.newTagName());
            return new TagApplyResult("RENAME", plan.tagName(), plan.newTagName(), 0, 0, false);
        }

        int renamed = applyRewrites(plan.rewrites());

        // 目录改完后新标签必然已存在（renameFolder 里的 replaceRefs 建的），
        // 除非一个目录都没改到 —— 那种情况下退回改行，避免凭空多出一个空标签
        MangaTag target = tagService.findByName(plan.newTagName());
        if (target == null) {
            tagService.renameRow(tagId, plan.newTagName());
            return new TagApplyResult(plan.action(), plan.tagName(), plan.newTagName(),
                    renamed, 0, false);
        }
        if (target.getId().equals(source.getId())) {
            return new TagApplyResult(plan.action(), plan.tagName(), plan.newTagName(),
                    renamed, 0, false);
        }
        int moved = tagService.repointRefs(source.getId(), target.getId());
        tagService.deleteTag(source.getId());
        return new TagApplyResult(plan.action(), plan.tagName(), plan.newTagName(),
                renamed, moved, true);
    }

    /** 合并：把源标签并进目标标签 */
    public TagApplyResult applyMerge(Long sourceTagId, Long targetTagId) {
        MangaTag target = tagService.require(targetTagId);
        if (target.getId().equals(sourceTagId)) {
            throw new IllegalArgumentException("不能合并到自己");
        }
        return applyRename(sourceTagId, target.getTagName());
    }

    /**
     * 删除标签：先把它从相关目录名里去掉，再删库里的行与全部关联。
     * <p>删的是标签本身，归档目录与漫画都不会被删。
     */
    public TagApplyResult applyDelete(Long tagId) {
        TagPlan plan = planDelete(tagId);
        int renamed = applyRewrites(plan.rewrites());
        tagService.deleteTag(tagId);
        return new TagApplyResult("DELETE", plan.tagName(), null, renamed, 0, true);
    }

    /**
     * 逐个改目录名。
     * <p>整批不在一个事务里 —— 目录移动本来就回滚不了。中途失败时前面已改完的目录
     * 保持已改状态，异常照常抛给页面；重跑一次同步即可让库对上磁盘。
     *
     * @return 实际改名的目录数（名字没变化的不计）
     */
    private int applyRewrites(List<FolderRewrite> rewrites) {
        // 一次标签连带改名 = 一个批次：每个受影响的作者目录一条记录（在 applyEdit 里出，
        // 这里只圈批次）。applyRename / applyMerge / applyDelete 三个页面入口都收敛到这里
        return FileOpRecorder.batch(FileOpModule.MANGA, FileOpSource.PAGE, null, () -> {
            archiveService.checkTagRewritable(rewrites);
            int renamed = 0;
            for (FolderRewrite rewrite : rewrites) {
                if (rewrite.fromFolderName().equals(rewrite.toFolderName())) {
                    continue;
                }
                archiveService.renameFolder(rewrite.unitId(), rewrite.toFolderName());
                renamed++;
            }
            return renamed;
        });
    }

    private long countMangaRefs(MangaTag tag) {
        return tagService.listTargetIds(MangaTagTargetType.MANGA_DATA, tag.getTagName()).size();
    }
}
