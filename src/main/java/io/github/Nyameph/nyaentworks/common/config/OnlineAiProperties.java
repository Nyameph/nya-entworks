package io.github.Nyameph.nyaentworks.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 在线 AI 端点（OpenAI 兼容 chat/completions）。含密钥，配置放 gitignored 的
 * {@code config/application-secret.yaml}（前缀在 {@code common} 下、不在 song 下 —— 将来
 * manga 也能用，用户指定）。整段缺失时用默认值：不报错不崩，只是在线分支不可用。
 *
 * <p>本文件由人手工维护，AI 不建、不填（填词助手设计 §14.1）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "nya-entworks.common.online-ai")
public class OnlineAiProperties {

    /** 与 song.fill-ai.use-online 同时为 true 才走在线。 */
    private boolean enabled = false;

    private String baseUrl = "";

    private String model = "";

    private String apiKey = "";
}
