package io.github.Nyameph.nyaentworks.song.task;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.song.service.SongTemplateService;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskContext;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskHandler;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnSong;

/**
 * 扫描模板根下的一级目录，回写 {@code song_original_setting}。目录可能几十上百个，
 * 所以走后台任务。
 */
@ConditionalOnSong
@Component
@RequiredArgsConstructor
public class SongTemplateScanHandler implements AsyncTaskHandler {

    public static final String TYPE = "song.template.scan";

    private final SongTemplateService templateService;

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
        return "扫描模板目录";
    }

    @Override
    public Class<?> paramType() {
        return Void.class;
    }

    @Override
    public Object run(Object params, AsyncTaskContext context) {
        context.message("正在扫模板目录…");
        return templateService.scanTemplates();
    }

    @Override
    public String summarizeResult(Object result) {
        SongTemplateService.ScanResult r = (SongTemplateService.ScanResult) result;
        String msg = "扫 " + r.scannedDirs() + " 个目录，更新 " + r.upserted() + " 条";
        if (r.moved() > 0) {
            msg += "，搬移 " + r.moved() + " 个目录";
        }
        return msg;
    }
}
