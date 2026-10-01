package io.github.Nyameph.nyaentworks.manga.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import io.github.Nyameph.nyaentworks.common.config.LocalAiProperties;
import io.github.Nyameph.nyaentworks.manga.config.MangaProperties;
import io.github.Nyameph.nyaentworks.manga.util.MangaEhClient.GData;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 相似度比对（可选）。标题确定性匹配不上时，交给 OpenAI 兼容接口判断
 * 本地标题与候选画廊标题是否为同一部漫画。
 * <p>默认关闭（{@code eh-scan.ai-enabled=false}）；未配置或调用失败一律返回空、
 * 由调用方退回确定性匹配，不中断扫描、不烧 token。
 */
@Service
@RequiredArgsConstructor
public class AiSimilarityService {

    private final MangaProperties props;

    /** 端点与模型（{@code nya-entworks.common.local-ai.*}），与 AI 填词、韵脚词性共用同一份 */
    private final LocalAiProperties localAi;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    /**
     * AI 对一个候选的判定。
     *
     * @param similarity 0~1；调用方无条件采用它（既能抬高真匹配，也能压低被「包含」误判的候选）
     * @param reason     模型给的一句话理由，可能为 null（模型没按格式给、或被截断）
     */
    public record AiScore(double similarity, String reason) {
    }

    /**
     * @return gid → 判定（相似度 + 理由）；未启用/未配置/失败时返回空 map
     */
    public Map<Long, AiScore> score(String localTitle, String parody, String artist,
                                    List<GData> candidates) {
        MangaProperties.EhScan c = props.getEhScan();
        // 本地 OpenAI 兼容端点（Ollama）不需要 api-key，这里不再把空 key 当「未启用」。
        // 「端点或模型没配全」判的是共用的那一句（{@link LocalAiProperties#isConfigured}）——
        // 三个 AI 功能联动关闭，不是各判各的
        if (!c.isAiEnabled() || !localAi.isConfigured()
                || isBlank(localTitle) || candidates.isEmpty()) {
            return Map.of();
        }
        try {
            StringBuilder cand = new StringBuilder();
            for (GData g : candidates) {
                cand.append(g.gid()).append(": ").append(g.title());
                if (g.titleJpn() != null && !g.titleJpn().isBlank()) {
                    cand.append(" | 日文: ").append(g.titleJpn());
                }
                cand.append('\n');
            }
            String system = "你是漫画标题匹配助手。判断本地漫画与候选 e-hentai 画廊是否为【同一部漫画】。"
                    + "判据：同一部漫画在不同语言/译名/展会编号下的标题 → 高相似(0.9~1.0)；"
                    + "同一作品的不同部/卷/话（标题里的 2、第二部、後編、下巻、続 等续作标记）→ 低相似(0~0.3)，因为不是同一部；"
                    + "题材相近但不同作品 → 低相似。"
                    + "只输出 JSON 数组：[{\"gid\":<数字>,\"similarity\":<0~1>,\"reason\":\"一句话\"}]，不要输出别的。";
            String user = "本地漫画：\n- 标题：" + localTitle
                    + (isBlank(parody) ? "" : "\n- 原作：" + parody)
                    + (isBlank(artist) ? "" : "\n- 作者：" + artist)
                    + "\n候选画廊列表：\n" + cand;

            JSONObject body = new JSONObject();
            body.put("model", localAi.getModel());
            JSONArray msgs = new JSONArray();
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", system);
            JSONObject usr = new JSONObject();
            usr.put("role", "user");
            usr.put("content", user);
            msgs.add(sys);
            msgs.add(usr);
            body.put("messages", msgs);

            String url = localAi.getBaseUrl();
            if (!url.endsWith("/chat/completions")) {
                url = url.endsWith("/") ? url + "chat/completions" : url + "/chat/completions";
            }
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json");
            if (!isBlank(localAi.getApiKey())) {
                reqBuilder.header("Authorization", "Bearer " + localAi.getApiKey());
            }
            HttpRequest req = reqBuilder.POST(HttpRequest.BodyPublishers.ofString(body.toJSONString()))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return Map.of();
            }
            JSONObject root = JSONObject.parseObject(resp.body());
            JSONArray choices = root.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                return Map.of();
            }
            String content = choices.getJSONObject(0).getJSONObject("message").getString("content");
            content = content == null ? "" : content
                    .replaceAll("(?s)^```(?:json)?\\s*", "")
                    .replaceAll("(?s)\\s*```$", "")
                    .trim();
            JSONArray arr = JSON.parseArray(content);
            if (arr == null) {
                return Map.of();
            }
            Map<Long, AiScore> result = new LinkedHashMap<>();
            for (int i = 0; i < arr.size(); i++) {
                JSONObject o = arr.getJSONObject(i);
                // reason 是模型的自述，可能缺、可能是空串 —— 留原样给页面，不在这里编
                String reason = o.getString("reason");
                result.put(o.getLongValue("gid"),
                        new AiScore(o.getDoubleValue("similarity"),
                                reason == null || reason.isBlank() ? null : reason.trim()));
            }
            return result;
        } catch (Exception e) {
            return Map.of(); // 失败静默降级
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
