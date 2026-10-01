package io.github.Nyameph.nyaentworks.common.ai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * OpenAI 兼容对话端点（{@code POST {base-url}/chat/completions}）。
 * 抄自 {@code manga/service/AiSimilarityService} 的请求拼装，抽出来给歌曲填词共用；
 * manga 侧继续用它自己那份，迁移留到以后（填词助手设计 §0.2 第 6 条 / §7.3）。
 *
 * <p>构造时把「地址 / key / 模型 / 超时」全塞进来，<b>自己不读配置</b> —— 免得 song 与
 * manga 抢同一个 {@code @ConfigurationProperties}。成功返回模型文本；非 200 / 超时 /
 * 解析失败一律 {@link Optional#empty()} 静默降级，WARN 日志只打状态码与耗时，
 * <b>绝不打 prompt 或回复正文</b>（语料红线，§9.4）。
 */
public class AiChatClient {

    private static final Logger log = LoggerFactory.getLogger(AiChatClient.class);

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration timeout;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public AiChatClient(String baseUrl, String apiKey, String model, Duration timeout) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = timeout;
    }

    /** 对话一次。模型文本在 {@code choices[0].message.content}；失败给空。 */
    public Optional<String> chat(String systemPrompt, String userPrompt, double temperature) {
        long start = System.currentTimeMillis();
        try {
            String url = baseUrl;
            if (!url.endsWith("/chat/completions")) {
                url = url.endsWith("/") ? url + "chat/completions" : url + "/chat/completions";
            }
            JSONObject body = new JSONObject();
            body.put("model", model);
            JSONArray messages = new JSONArray();
            JSONObject system = new JSONObject();
            system.put("role", "system");
            system.put("content", systemPrompt);
            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", userPrompt);
            messages.add(system);
            messages.add(user);
            body.put("messages", messages);
            body.put("temperature", temperature);
            body.put("stream", false);

            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString()));
            if (StringUtils.isNotBlank(apiKey)) {
                builder.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> resp = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("AI chat 失败：HTTP {}，耗时 {} ms", resp.statusCode(),
                        System.currentTimeMillis() - start);
                return Optional.empty();
            }
            JSONObject root = JSONObject.parseObject(resp.body());
            JSONArray choices = root.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                log.warn("AI chat 响应没有 choices，耗时 {} ms", System.currentTimeMillis() - start);
                return Optional.empty();
            }
            String content = choices.getJSONObject(0).getJSONObject("message").getString("content");
            log.info("AI chat 完成，耗时 {} ms", System.currentTimeMillis() - start);
            return Optional.ofNullable(content);
        } catch (Exception e) {
            log.warn("AI chat 异常：{}，耗时 {} ms", e.getClass().getSimpleName(),
                    System.currentTimeMillis() - start);
            return Optional.empty();
        }
    }
}
