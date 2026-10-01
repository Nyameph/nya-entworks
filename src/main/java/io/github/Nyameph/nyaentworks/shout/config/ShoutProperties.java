package io.github.Nyameph.nyaentworks.shout.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 喊麦模块的本机路径（文档 5 「磁盘上的目录」）。
 *
 * <p>只有两个根，都在歌曲那套目录里 —— 喊麦是「文件名没有规律、不解析」的那一批，
 * 磁盘上与歌曲并列放，代码与库却是分开的。
 */
@Data
@Component
@ConfigurationProperties(prefix = "nya-entworks.shout")
public class ShoutProperties {

    /**
     * 模块总开关，<b>缺省 false ＝ 这个模块整个不启用</b>。
     *
     * <p>关掉之后：喊麦的 Controller 不注册、3 个异步任务处理器不注册、启动自动任务
     * {@code ShoutSyncOnStartup} 不再跑。判定点见
     * {@link io.github.Nyameph.nyaentworks.common.config.ConditionalOnShout}。
     *
     * <p>本字段自己<b>不</b>带那个注解 —— {@code SettingsCatalog} 与 {@code EnvCheckService}
     * 都要读它，标掉会让配置页整个起不来。
     */
    private boolean enabled = false;

    /**
     * 已归档喊麦根，下面是 {@code <分数><评语>} 分区，文件平铺在分区里。
     *
     * <p><b>出厂是空串 ＝ 没配</b>（2026-09-30 起，与歌曲侧 {@code SongProperties#songDir}
     * 同一条口径）：写死本机路径会让「没配」这个状态在别人的机器上永远到不了。
     * 本机那台的真实值在 {@code config/nya-entworks.yaml} 这个覆盖层里。
     */
    private String archivedDir = "";

    /**
     * 待打分根（{@code F:\NetdiskDownload\#已压缩歌曲\喊麦}）—— <b>完整路径</b>，与歌曲侧
     * {@link io.github.Nyameph.nyaentworks.song.config.SongProperties#stagingDir} 同一口径。
     * 这一层不入库、每次现扫。出厂空串，见 {@link #archivedDir}。
     */
    private String stagingDir = "";

    /**
     * 归档前是否<b>强制先打标签</b>。出厂 false ＝ 不强制（2026-09-22 用户定，
     * 与歌曲/漫画同一天改成配置项）。
     *
     * <p>口径与歌曲完全一样：只管「待打分区 → 已归档」的首次归档，已归档改分不拦。
     * 标签是 DB-only 的（{@code shout_tag}，key = 主名），闸门在
     * {@code ShoutArchiveService#requireTagForArchive}。
     */
    private boolean requireTagsBeforeArchive = false;
}
