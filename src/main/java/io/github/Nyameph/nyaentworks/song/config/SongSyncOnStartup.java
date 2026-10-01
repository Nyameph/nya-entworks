package io.github.Nyameph.nyaentworks.song.config;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.song.task.SongSyncHandler;
import io.github.Nyameph.nyaentworks.task.service.AsyncTaskService;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnSong;

/**
 * 应用就绪后提交一次归档歌曲全量同步任务。
 * <p>同步已改成后台任务（不再阻塞启动线程），这次同步的结果在任务页可翻到，而不是只进日志。
 * 归档根不存在（外挂盘没挂）时 {@code SongSyncService.sync} 会跳过该种类，不会误判 MISSING。
 * 提交失败只记日志不阻断启动 —— 同步是镜像，随时能手动 {@code POST /api/song/sync} 补跑。
 *
 * <p>{@code @Order(100)} 是硬约束：必须排在 {@code AsyncTaskRecoveryRunner}（{@code @Order(0)}）
 * 之后，否则恢复逻辑会把自己这一轮刚提交的 PENDING 行误标成 INTERRUPTED。
 */
@ConditionalOnSong
@Component
@RequiredArgsConstructor
@Order(100)
public class SongSyncOnStartup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SongSyncOnStartup.class);

    private final AsyncTaskService taskService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            Long taskId = taskService.submit(SongSyncHandler.TYPE, null);
            log.info("启动归档歌曲同步已提交为后台任务 #{}", taskId);
        } catch (Exception e) {
            log.error("提交启动归档歌曲同步任务失败（可稍后手动 POST /api/song/sync 补跑）", e);
        }
    }
}
