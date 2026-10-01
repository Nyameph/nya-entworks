package io.github.Nyameph.nyaentworks.common.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 外部命令（python / curl 这类）起不起得来的探测，按命令缓存结果。
 *
 * <p>服务两处（<b>同一处探、同一处报</b>，《桌面化收尾（2026-09-25 实施）》4.1 / 4.3 —— docs/已完成/桌面壳实施计划.md）：
 * <ul>
 *   <li>{@code EnvCheckService} —— 自检页报一条「有 / 没有」，缺了不再静默；</li>
 *   <li>{@code SongResourceSearchService} —— 网易云搜索那条 curl 的开关初值就来自这里，
 *       缺 curl 时整源跳过而不是抛异常。</li>
 * </ul>
 *
 * <p><b>判据：进程起得来且退出码为 0</b>（探一次 {@code <命令> --version}）。
 * Windows 上 Microsoft Store 的 python 占位 stub 会以 9009 退出，正好落在「起不来」里 ——
 * 它不装解释器、只弹商店，拿它跑 ncm 解密必失败，所以不能把「进程起来了」当可用。
 *
 * <p><b>缓存到进程退出为止</b>：spawn 一个进程不便宜，而自检红点每次开页都会拉一遍
 * {@code /api/env/check}，不能每次都真探。代价是「装上 python 之后要重启后端才翻绿」——
 * 与模块开关同一口径（{@code @ConditionalOnProperty} 也只在启动时求值一次），
 * 「改完要重启」是本项目的既有规矩，不算新增负担。
 */
@Component
public class ExternalCommandProbe {

    private static final Logger log = LoggerFactory.getLogger(ExternalCommandProbe.class);

    /** 探测等这么久还不出结果就当起不来（挂着的进程比失败的进程更不能等） */
    private static final long PROBE_TIMEOUT_SECONDS = 10;

    private final Map<String, Boolean> cache = new ConcurrentHashMap<>();

    /** 这条命令现在起得来吗。同一个命令只真探一次，之后都走缓存 */
    public boolean canRun(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        return cache.computeIfAbsent(command.trim(), this::probe);
    }

    private boolean probe(String command) {
        Process p = null;
        try {
            // 参数数组，不拼命令行字符串 —— 命令带空格 / 中文也不碎（设计稿 §7 第一条纪律）
            ProcessBuilder pb = new ProcessBuilder(command, "--version");
            pb.redirectErrorStream(true);
            p = pb.start();
            if (!p.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("[能力探测] {} --version {} 秒无响应，判为不可用", command, PROBE_TIMEOUT_SECONDS);
                return false;
            }
            boolean ok = p.exitValue() == 0;
            if (log.isDebugEnabled()) {
                String out = new String(p.getInputStream().readAllBytes()).trim();
                log.debug("[能力探测] {} → {}（输出：{}）", command, ok ? "可用" : "不可用", out);
            }
            return ok;
        } catch (Exception e) {
            // 起不来（命令不存在是最常见的 IOException）正是要探的那个状态，不是错误
            log.info("[能力探测] {} 起不来：{}", command, e.getMessage());
            return false;
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }
}
