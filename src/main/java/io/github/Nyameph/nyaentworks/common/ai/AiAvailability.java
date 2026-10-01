package io.github.Nyameph.nyaentworks.common.ai;

import io.github.Nyameph.nyaentworks.common.config.LocalAiProperties;
import io.github.Nyameph.nyaentworks.common.config.OnlineAiProperties;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * 「AI 现在能用吗、不能用是缺什么」的<b>唯一口径</b>。
 *
 * <p>全项目有三个 AI 功能，用同一台本机端点（{@code common.local-ai.*}，见
 * {@link LocalAiProperties} 的类注释）：漫画相似度兜底、AI 填词、韵脚词性。
 * <b>端点或模型没配全时，三个一起关</b> —— 不是三个各自判一遍：各判各的必然长出三种
 * 说法，用户看到的是「AI 填词说没配端点、补全词性直接报错、漫画兜底悄悄不生效」。
 *
 * <p>判定本身只有一句（{@link LocalAiProperties#isConfigured()}），但「关了就关在哪儿」
 * 要说清楚，所以拒绝理由是给人看的一句话，由这里统一给：配置页的提示、各功能的报错、
 * 前端隐藏按钮，都用同一份文本。
 *
 * <p>它<b>不</b>替各功能做开关判断：漫画侧是否用 AI 兜底仍是
 * {@code manga.eh-scan.ai-enabled}、填词侧仍是 {@code song.fill-ai.enabled}。
 * 这里只回答「硬前提满足没有」，功能自己的开关由各功能判 —— 两件事分开，
 * 「关掉漫画的 AI」才不会连填词一起关掉。
 */
@Component
@RequiredArgsConstructor
public class AiAvailability {

    private final LocalAiProperties localAi;
    private final OnlineAiProperties onlineAi;

    /** 本机端点（{@code common.local-ai}）配全了没有 */
    public boolean localReady() {
        return localAi.isConfigured();
    }

    /** 本机端点配全了返回 {@code null}，否则是给人看的、说清缺哪一项的一句话 */
    public String localDeny() {
        return localDenyAt(localAi.getBaseUrl(), localAi.getModel());
    }

    /**
     * 拿<b>任意一份</b>端点值问「这样配行不行」。{@link #localDeny()} 就是拿进程里那一份来问它。
     *
     * <p>为什么要参数化（与 {@code MangaEhLocalDb#availableAt}、{@code MangaCompressService#availableAt}
     * 同一个理由）：配置页顶部的提示条判的是<b>「重启后会生效的值」</b>（覆盖层里那一份），
     * 不是进程里那个启动时就绑好的快照 —— 否则在页面上删掉端点、保存完页面什么都不说，
     * 要等重启后端才轮到它变。而「缺哪一项」这句话只该有一份，所以在原处参数化，
     * 而不是让提示条自己再写一遍。
     */
    public static String localDenyAt(String baseUrl, String model) {
        if (StringUtils.isBlank(baseUrl) && StringUtils.isBlank(model)) {
            return "本机 AI 端点没配：nya-entworks.common.local-ai.base-url 与 model 都是空的"
                    + "（配置页「AI 端点（本机）」一组，改完要重启后端）";
        }
        if (StringUtils.isBlank(baseUrl)) {
            return "本机 AI 端点没配：nya-entworks.common.local-ai.base-url 是空的"
                    + "（配置页「AI 端点（本机）」一组，改完要重启后端）";
        }
        if (StringUtils.isBlank(model)) {
            return "本机 AI 模型没配：nya-entworks.common.local-ai.model 是空的"
                    + "（配置页「AI 端点（本机）」一组，模型名要与 ollama list 里的一致）";
        }
        return null;
    }

    /** 在线端点（{@code common.online-ai}，在 config/application-secret.yaml 里）配全了没有 */
    public boolean onlineReady() {
        return onlineAi.isEnabled() && StringUtils.isNotBlank(onlineAi.getBaseUrl())
                && StringUtils.isNotBlank(onlineAi.getModel());
    }

    /**
     * 在线端点配全了返回 {@code null}，否则是拒绝理由。
     * <p>给 AI 填词的「走在线 AI」用：那条路不碰本机端点，所以判的是另一份配置。
     */
    public String onlineDeny() {
        if (!onlineAi.isEnabled()) {
            return "在线模式被拒：common.online-ai.enabled=false（配置在 config/application-secret.yaml，"
                    + "与 song.fill-ai.use-online 同时为 true 才走在线）";
        }
        if (StringUtils.isBlank(onlineAi.getBaseUrl())) {
            return "在线模式被拒：common.online-ai.base-url 为空（配置在 config/application-secret.yaml）";
        }
        if (StringUtils.isBlank(onlineAi.getModel())) {
            return "在线模式被拒：common.online-ai.model 为空（配置在 config/application-secret.yaml）";
        }
        return null;
    }
}
