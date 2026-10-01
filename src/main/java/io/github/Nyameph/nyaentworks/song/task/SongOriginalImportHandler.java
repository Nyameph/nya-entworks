package io.github.Nyameph.nyaentworks.song.task;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnSong;
import io.github.Nyameph.nyaentworks.common.fileop.FileOpRecorder;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpModule;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpSource;
import io.github.Nyameph.nyaentworks.song.service.SongOriginalImportService;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskContext;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskHandler;

/**
 * 「新增原曲 / 修改」弹窗的执行（异步）：搬 + 改名 + 写回八个 {@code *_file_name}。
 *
 * <p>走异步的理由与 {@code song.import} 相同：<b>跨卷拷贝可能几十秒</b>（源可以是任意盘），
 * 同步请求会挂在前端等。参数落 {@code async_task.params_json}；启动时残留任务置
 * INTERRUPTED、由人在任务页点「重新执行」重跑（{@code apply} 里会重算一遍并二次校验，
 * 包括那条「新增查重」—— 请求在队列里躺了一夜之后，别人可能刚建过同一条）。
 */
@ConditionalOnSong
@Component
@RequiredArgsConstructor
public class SongOriginalImportHandler implements AsyncTaskHandler {

    public static final String TYPE = "song.original-import";

    private final SongOriginalImportService originalImportService;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String module() {
        return SongTaskModule.SONG;
    }

    @Override
    public String taskName() {
        return "原曲导入（新增 / 修改）";
    }

    @Override
    public Class<?> paramType() {
        return SongOriginalImportService.ImportRequest.class;
    }

    @Override
    public Object run(Object params, AsyncTaskContext context) {
        SongOriginalImportService.ImportRequest p =
                (SongOriginalImportService.ImportRequest) params;
        int count = p.files() == null ? 0 : p.files().size();
        context.message("正在处理 " + count + " 个文件…");
        // 改动流水的批次入口包在这里：apply 内部逐路径 recordPath 的那些行共用一个批次号
        return FileOpRecorder.batch(FileOpModule.SONG, FileOpSource.TASK, context.taskId(),
                () -> originalImportService.apply(p));
    }

    @Override
    public String summarizeResult(Object result) {
        SongOriginalImportService.ImportResult r = (SongOriginalImportService.ImportResult) result;
        StringBuilder text = new StringBuilder("导入完成，搬 ")
                .append(r.movedFiles()).append(" 个文件");
        if (r.renamedFiles() > 0) {
            text.append("，改名 ").append(r.renamedFiles()).append(" 个");
        }
        if (r.created() > 0) {
            text.append("（新建了这条原曲）");
        }
        return text.toString();
    }
}
