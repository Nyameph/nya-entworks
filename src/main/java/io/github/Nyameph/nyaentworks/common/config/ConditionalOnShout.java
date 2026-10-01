package io.github.Nyameph.nyaentworks.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 喊麦模块的总开关：{@code nya-entworks.shout.enabled} 为 {@code true} 时才注册被标注的 Bean。
 *
 * <p>用法与边界同 {@link ConditionalOnManga}：<b>缺省是关</b>，只标「入口」
 * （Controller / {@code AsyncTaskHandler} / 启动 Runner），Service 与 {@code *Properties}
 * 不标。本模块的启动自动任务是 {@code ShoutSyncOnStartup}（提交一次归档喊麦全量同步）。
 *
 * <p><b>与歌曲模块的一处耦合要注意</b>：{@code song} 的语料采集在
 * {@code song.corpus.include-shout=true}（默认）时会读喊麦的归档列表
 * （{@code CorpusService} 注入 {@code ShoutGroupService}）。喊麦关掉而歌曲开着时，
 * 语料**仍能采**（{@code ShoutGroupService} 是 Service、不被本注解拦），只是喊麦那份
 * 数据不再更新。这是有意的：语料是只读的历史积累，不该因为「暂时不看喊麦」而丢一截。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(prefix = "nya-entworks.shout", name = "enabled", havingValue = "true")
public @interface ConditionalOnShout {
}
