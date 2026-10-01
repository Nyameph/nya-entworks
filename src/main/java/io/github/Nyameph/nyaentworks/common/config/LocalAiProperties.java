package io.github.Nyameph.nyaentworks.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 本机 AI 端点（OpenAI 兼容 chat/completions），<b>全项目共用一份</b>。
 *
 * <p>三个消费方本来是各配各的（漫画 eh 扫描的 {@code eh-scan.ai-base-url}、AI 填词的
 * {@code song.fill-ai.local.*}、韵脚词性的同一份），而三处的默认值一模一样 ——
 * 都是本机这一台 Ollama。同一台机器写三份的直接后果是「换端口要改三处，漏一处就是
 * 一半功能静默走旧地址」，所以 2026-09-17 收成这一份。
 *
 * <p><b>各模块自己的开关留在各模块</b>：漫画侧是否用 AI 兜底仍是
 * {@code manga.eh-scan.ai-enabled}、填词侧仍是 {@code song.fill-ai.enabled} ——
 * 那些是「这个功能要不要开」，不是「端点在哪」，混在一起会让「关掉漫画的 AI」
 * 连填词一起关掉。
 *
 * <p>前缀在 {@code common} 下、不在 {@code song} 下：将来别的模块也要用同一台机器
 * （与 {@link OnlineAiProperties} 的放法一致，用户指定）。
 *
 * <p>本机 Ollama 不需要 api-key，{@link #apiKey} 默认空即可；<b>它不进配置页</b>
 * （密钥一律不上面）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "nya-entworks.common.local-ai")
public class LocalAiProperties {

    /**
     * OpenAI 兼容端点；本机 Ollama 为 {@code http://localhost:11434/v1}。
     * <p><b>出厂是空串 = 没配</b>（2026-09-17 起）。端点指向哪台机器是**本机差异**，
     * 写在 {@code config/nya-entworks.yaml} 里才成立；出厂写死一个 localhost 会让
     * 「没配 AI 端点」这个状态在新机器上永远到不了（页面上也就永远不提示）。
     */
    private String baseUrl = "";

    /**
     * 模型名，须与 {@code ollama list} 里的一致（8G 显存实际能用的上限在 7b~9b）。
     * <p>同样**出厂空串 = 没配**，理由见 {@link #baseUrl}。
     */
    private String model = "";

    /** 需要鉴权的本机端点才填；Ollama 留空 */
    private String apiKey = "";

    /**
     * 端点与模型都填了才算配全 —— 两者是同一份配置的必填项，缺任何一个，
     * 三个 AI 功能（漫画相似度兜底 / AI 填词 / 韵脚词性）都<b>联动关闭</b>。
     *
     * <p>分开判会漏掉一种静默：只填端点不填模型时，请求照样发得出去，
     * 端点回 400，调用方按「AI 没答上来」降级 —— 页面上看是「AI 判不出来」，
     * 不是「你少填了一项」。所以判定与拒绝理由（{@link io.github.Nyameph.nyaentworks.common.ai.AiAvailability}）
     * 都收在这一处。
     *
     * <p>不是 Lombok 的 getter（没有对应的 {@code setConfigured}）：它是个派生值，
     * 配置文件里写它没有意义。Spring 绑定会忽略这种只有 getter 的属性。
     */
    public boolean isConfigured() {
        return isNotBlank(baseUrl) && isNotBlank(model);
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}
