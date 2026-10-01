package io.github.Nyameph.nyaentworks.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 歌曲（填词）模块的总开关：{@code nya-entworks.song.enabled} 为 {@code true} 时才注册被标注的 Bean。
 *
 * <p>用法与边界同 {@link ConditionalOnManga}：<b>缺省是关</b>，只标「入口」
 * （Controller / {@code AsyncTaskHandler} / 启动 Runner），Service 与 {@code *Properties}
 * 不标 —— 后者被 {@code common} 的 {@code EnvCheckService} / {@code SettingsCatalog} /
 * {@code MediaController} 注入着，标掉会让那些 common 组件起不来。
 *
 * <p>关掉本模块时下列「自动任务」随之停掉：
 * <ul>
 *   <li>{@code SongSyncOnStartup} —— 启动时提交一次归档歌曲全量同步；</li>
 *   <li>{@code RhymeSeedRunner} —— 启动时把现代规范字表展开成单字韵脚词典种子。</li>
 * </ul>
 * 注意 {@code RhymeSeedRunner} 属于「填词」子域，但它与本模块同生共死
 * （韵脚词典是填词页的东西，歌曲模块关了就没有调用方）。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(prefix = "nya-entworks.song", name = "enabled", havingValue = "true")
public @interface ConditionalOnSong {
}
