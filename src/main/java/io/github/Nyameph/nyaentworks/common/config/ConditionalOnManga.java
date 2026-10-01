package io.github.Nyameph.nyaentworks.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 漫画模块的总开关：{@code nya-entworks.manga.enabled} 为 {@code true} 时才注册被标注的 Bean。
 *
 * <p><b>缺省是关</b>（{@code matchIfMissing} 默认为 {@code false}）。所以「这个模块要不要开」
 * 必须在 {@code config/nya-entworks.yaml} 里写一句 {@code nya-entworks.manga.enabled: true}
 * 才会是开的 —— 出厂状态是一个三个模块全关的空壳，配置页上把它们逐个打开。
 * 这正是「发给朋友」那条线要的：装完先看到一张干净的配置页，而不是三个指向 {@code F:\} 的空列表。
 *
 * <p><b>标在哪些类上</b>：只标「入口」—— Controller、{@code AsyncTaskHandler}、启动 Runner。
 * Service 与 {@code *Properties} <b>刻意不标</b>，两个原因：
 * <ul>
 *   <li>它们<b>不主动干活</b>（没有构造期副作用、没有 {@code @PostConstruct} 干活），
 *       模块关了就没有调用方，创建出来也只是几个空壳对象；</li>
 *   <li>它们被 {@code common} 里的 {@code EnvCheckService} / {@code SettingsCatalog} /
 *       {@code MediaController} 注入着 —— 标掉它们，这三个 common 组件会因为注入不到依赖而
 *       <b>启动失败</b>（症状是「关掉漫画，整个应用起不来」，比不做还糟）。</li>
 * </ul>
 * 那 924 MB 的 eh 索引因此也不会建：{@code MangaEhLocalDb} 是<b>懒加载</b>（构造只存路径），
 * 唯一的触发性入口是 {@link io.github.Nyameph.nyaentworks.manga.config.MangaEhLocalDbWarmup}
 * 那个预热线程 —— 它被这个注解标掉，索引就没有人再去读。
 *
 * <p><b>唯一一处 Service 里的例外</b>：{@code MangaDictService#init} 是 {@code @PostConstruct}，
 * 会在启动时读词典。它没标注解（同上面第二条理由），改在方法体里早退 ——
 * 见该方法的注释。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(prefix = "nya-entworks.manga", name = "enabled", havingValue = "true")
public @interface ConditionalOnManga {
}
