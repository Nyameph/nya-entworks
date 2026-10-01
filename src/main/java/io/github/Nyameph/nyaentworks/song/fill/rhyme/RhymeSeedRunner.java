package io.github.Nyameph.nyaentworks.song.fill.rhyme;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.common.config.ConditionalOnSong;

/**
 * 启动时把现代规范字表（kTGHZ2013）全展开成单字词典种子（填词助手设计 §4.3）。
 *
 * <p>照 {@code MangaEhLocalDbWarmup} 的先例：<b>必须开后台 daemon 线程，不能在 run 里
 * 直接跑</b> —— 首次展开约九千行、要读两份拼音字典，同步跑会拖慢启动。库里已有
 * MODERN 行就跳过（单字是恒定的，跑过一次就不用再跑）。全程不打扰、只打一行结果日志。
 */
@ConditionalOnSong
@Component
@RequiredArgsConstructor
@Order(100)
public class RhymeSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RhymeSeedRunner.class);

    private final RhymeService rhymeService;

    @Override
    public void run(ApplicationArguments args) {
        Thread t = new Thread(() -> {
            try {
                RhymeService.SeedResult result = rhymeService.seedModern(false);
                if (result.skipped()) {
                    log.info("单字词典已有种子，跳过");
                } else {
                    log.info("单字词典种子完成：写入 {} 行（展开 {} 行，重复忽略 {} 行）",
                            result.inserted(), result.built(), result.built() - result.inserted());
                }
            } catch (Exception e) {
                log.error("单字词典种子失败（不影响启动，可在词典页点「重新生成单字词典」重试）", e);
            }
        }, "rhyme-seed");
        t.setDaemon(true);
        t.start();
    }
}
