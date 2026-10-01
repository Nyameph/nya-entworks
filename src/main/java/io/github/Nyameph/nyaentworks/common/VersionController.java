package io.github.Nyameph.nyaentworks.common;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 当前版本号，供左下角品牌区显示。
 *
 * <p><b>版本号的真源是 {@code pom.xml} 的 {@code <version>}</b>，Maven 的
 * {@code spring-boot-maven-plugin:build-info} goal 把它写进
 * {@code META-INF/build-info.properties}，这里经 {@link BuildProperties} 读出来。
 * 改版本号一律走 {@code tools/bump_version.py}（手工改 pom 会漏掉
 * {@code desktop/package.json} 那一份），日常的 patch 自增由 git 的
 * {@code post-commit} 钩子自动做 —— 规则见 {@code tools/README.md}。
 *
 * <p><b>为什么不直接读 pom.xml</b>：jar 里没有 pom.xml 的可靠副本
 * （{@code META-INF/maven/**} 那份是构建期生成、不保证存在），而静态页又是
 * 从磁盘直读的、不经 classpath，所以只能由后端下发。
 *
 * <p>用 {@link ObjectProvider} 取 {@link BuildProperties} 而<b>不</b>直接注入：
 * 它只在 build-info 生成过时才是一个 Bean（例如 IDE 直接跑某个 main、或换了种
 * 构建方式时没有），直接注入会**启动失败**。取不到就回 {@code "unknown"} ——
 * 页面上显示一个 unknown 远好过整个应用起不来。
 */
@Tag(name = "版本")
@RestController
@RequestMapping("/api/version")
@RequiredArgsConstructor
public class VersionController {

    private final ObjectProvider<BuildProperties> buildProperties;

    @Operation(summary = "当前版本号（真源是 pom.xml 的 <version>）")
    @GetMapping
    public ApiResult<Map<String, String>> version() {
        BuildProperties props = buildProperties.getIfAvailable();
        return ApiResult.ok(Map.of(
                "version", props == null ? "unknown" : props.getVersion()));
    }
}
