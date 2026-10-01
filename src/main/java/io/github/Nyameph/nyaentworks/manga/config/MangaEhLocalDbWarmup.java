package io.github.Nyameph.nyaentworks.manga.config;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.manga.util.MangaEhLocalDb;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnManga;

/**
 * 应用就绪后在后台线程预热本地 e-hentai 数据源索引。
 *
 * <p>解决的问题：{@code MangaEhLocalDb} 是懒加载，190 万行建索引要 20 秒上下，
 * 这段时间原先落在「启动后第一次归档漫画」的那个请求上 —— 归档会同步调
 * {@code MangaEhScanService.scan}，用户就干等着。预热只是把这段时间挪到没人等的时候，
 * 匹配逻辑一个字没改。
 *
 * <p><b>必须开后台线程，不能在 run 里直接跑</b>：{@link ApplicationRunner} 跑在启动线程上，
 * 同步加载会让启动多等 20 秒（端口都还没开始服务）。用后台线程则启动照常，
 * 预热与用户操作并行。
 *
 * <p><b>不用异步任务框架</b>：预热不是用户提交的写操作，没有「重新执行」的语义，
 * 失败了下次 {@code match} 自己会重试，落一行任务记录只是噪音。
 *
 * <p>线程设成 daemon：预热中途用户关掉应用不该被这 20 秒拖住。
 * 失败只记日志 —— {@code MangaEhLocalDb.warmUp} 自己已经吞掉了所有异常并返回 false，
 * 且它不会把失败状态记成「已加载」，下次真正 match 时会重新尝试加载。
 *
 * <p>{@code @Order(100)} 与 {@code SongSyncOnStartup} 同档：排在
 * {@code AsyncTaskRecoveryRunner}（{@code @Order(0)}）之后。本类不提交任务，
 * 顺序其实无关紧要，标上只为与其它 runner 保持一致的阅读顺序。
 *
 * <p><b>没配本地库（{@code manga.eh-scan.local-db-path} 空，或文件不在）时整段跳过</b>，
 * 只记一行 INFO —— 判据是 {@link MangaEhLocalDb#available()}，与「扫描时不走本地库」
 * 是同一个口径（2026-09-17）。
 */
@ConditionalOnManga
@Component
@RequiredArgsConstructor
@Order(100)
public class MangaEhLocalDbWarmup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MangaEhLocalDbWarmup.class);

    private final MangaEhLocalDb localDb;

    @Override
    public void run(ApplicationArguments args) {
        // 没配本地库就不必起线程：下面 warmUp 会立刻返回 false，但那要先把线程开起来。
        // 更重要的是这句日志 —— 「没配」是在启动时就确定的事，直接说清楚，
        // 别等到下面那句含糊的「未预热（未配置或库文件不存在）」。它是 INFO 不是 WARN：
        // 新机器上没这份 1.4 GB 的产物是常态，本地匹配纯属加速，不影响任何功能。
        if (!localDb.available()) {
            log.info(localDb.unavailableReason() + "，本次启动不预热本地索引");
            return;
        }
        Thread t = new Thread(() -> {
            try {
                long t0 = System.currentTimeMillis();
                if (localDb.warmUp()) {
                    log.info("本地 e-hentai 数据源预热完成，耗时 {} ms", System.currentTimeMillis() - t0);
                } else {
                    log.info("本地 e-hentai 数据源未预热（库文件在，但加载失败或期间被移走），首次匹配时再试");
                }
            } catch (Exception e) {
                log.error("本地 e-hentai 数据源预热失败（不影响启动，首次匹配时会重试）", e);
            }
        }, "eh-localdb-warmup");
        t.setDaemon(true);
        t.start();
    }
}
