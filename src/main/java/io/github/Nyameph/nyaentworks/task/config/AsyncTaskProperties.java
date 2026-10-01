package io.github.Nyameph.nyaentworks.task.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 异步任务框架的可调项。默认值对单人自用的量级都够，一般不用改。
 */
@Data
@Component
@ConfigurationProperties(prefix = "nya-entworks.task")
public class AsyncTaskProperties {

    /**
     * 运行中任务的进度刷库间隔（毫秒）。
     * <p>压缩几百张图会逐张调 {@code progress()}，逐次 UPDATE 会把库打爆，所以按时间节流。
     * 调小了费 IO，调大了页面进度条在刷新后会往回跳一截（内存热态没了，只能读库里那份）。
     */
    private long flushIntervalMs = 2000;

    /**
     * 单个任务在内存里最多留多少行日志。
     * <p>超出后掐掉中间段、保留首尾（开头是参数与总数，结尾是失败明细，中间是流水账）。
     */
    private int maxLogLines = 5000;

    /** DONE / FAILED 保留天数 */
    private int keepDoneDays = 7;

    /**
     * INTERRUPTED 保留天数。比 DONE / FAILED 长，因为它是重跑候选 ——
     * 要留到人点完「重新执行」或者确认放弃为止。
     */
    private int keepInterruptedDays = 30;

    /** 终态任务总量上限，超出的按 id 从旧到新删。与保留天数<b>同时满足</b>才删 */
    private int keepMaxRows = 200;

    /** 任务列表一次返回多少条 */
    private int listLimit = 100;
}
