package io.github.Nyameph.nyaentworks.song.task;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.song.service.SongSyncService;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskContext;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskHandler;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnSong;

/**
 * 从磁盘全量同步归档歌曲库。会扫三个种类、几百上千个文件，所以走后台任务。
 * <p>幂等：磁盘没变时重跑只产生「更新」，不重复插入。
 */
@ConditionalOnSong
@Component
@RequiredArgsConstructor
public class SongSyncHandler implements AsyncTaskHandler {

    public static final String TYPE = "song.sync";

    private final SongSyncService syncService;

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
        return "同步归档歌曲";
    }

    @Override
    public Class<?> paramType() {
        return Void.class;
    }

    @Override
    public Object run(Object params, AsyncTaskContext context) {
        context.message("正在扫归档歌曲目录…");
        return syncService.sync();
    }

    @Override
    public String summarizeResult(Object result) {
        SongSyncService.SyncResult r = (SongSyncService.SyncResult) result;
        return "新增 " + r.songsInserted() + "，更新 " + r.songsUpdated() + "，失踪 " + r.songsMissing();
    }
}
