package io.github.Nyameph.nyaentworks.task.runner;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.task.service.AsyncTaskService;

/**
 * 启动时处理上次没跑完的异步任务：一律标成 {@code INTERRUPTED}，<b>不自动重跑</b>。
 *
 * <p><b>{@code @Order(0)} 是硬约束，不能去掉。</b>其它 {@link ApplicationRunner}
 * （如 {@code SongSyncOnStartup}）会在启动时<b>提交</b>异步任务，那些新行是 PENDING 的；
 * 恢复逻辑跑在它们后面的话，会把自己这一轮刚建的任务当成上次的残留标成中断 ——
 * 表现为「每次启动，启动同步任务都显示已中断」。所以恢复必须最先跑。
 *
 * <p>失败只记日志不阻断启动：任务记录是附属信息，它坏了不该让整个应用起不来。
 */
@Component
@Order(0)
@RequiredArgsConstructor
public class AsyncTaskRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskRecoveryRunner.class);

    private final AsyncTaskService taskService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            taskService.recoverAndCleanup();
        } catch (Exception e) {
            log.error("异步任务恢复失败（表可能还没建，见 db/nya_entworks.sql）", e);
        }
    }
}
