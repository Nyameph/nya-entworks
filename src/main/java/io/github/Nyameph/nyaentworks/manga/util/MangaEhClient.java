package io.github.Nyameph.nyaentworks.manga.util;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;
import io.github.Nyameph.nyaentworks.manga.config.MangaProperties;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * e-hentai / exhentai 的 HTTP 客户端（搜索 + gdata 元数据 + 详情页权重），带 cookie、
 * 代理与逐请求限流。解析交给 {@link MangaEhHtmlParser}，这里只管发请求。
 * <p>站点根、cookie、代理、请求间隔都来自 {@code nya-entworks.manga.eh-scan} 配置；cookie 走
 * 环境变量覆盖，不落仓库。
 */
@Component
public class MangaEhClient {

    /** 表站根：exhentai 里站搜索失败后的备选站点（公开、不需要 igneous） */
    private static final String E_HENTAI = "https://e-hentai.org";

    /** 封面反向搜索的上传端点（表里站共用，返回的 gid/token 两站通用） */
    private static final String IMAGE_LOOKUP = "https://upload.e-hentai.org/image_lookup.php";

    /** 搜到的一本 gallery 的定位（gid + token + 来源站点） */
    public record GalleryRef(long gid, String token, String baseUrl) {
    }

    /** gdata 返回的一本 gallery 元数据：标题（含日文）与标签（"namespace:tag_en" 原文，无权重） */
    public record GData(long gid, String token, String title, String titleJpn, List<String> tags) {
    }

    private final MangaProperties props;
    private final HttpClient client;

    public MangaEhClient(MangaProperties props) {
        this.props = props;
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL);
        String host = props.getEhScan().getProxyHost();
        int port = props.getEhScan().getProxyPort();
        if (host != null && !host.isBlank() && port > 0) {
            b.proxy(ProxySelector.of(new InetSocketAddress(host, port)));
        }
        this.client = b.build();
    }

    private String baseUrl() {
        String base = props.getEhScan().getBaseUrl();
        if (base == null || base.isBlank()) {
            base = "https://exhentai.org";
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /**
     * e-hentai 登录 cookie。配置里没填时从环境变量兜底读取：Spring 宽松绑定把
     * {@code nya-entworks.manga.eh-scan.cookie} 映射成 {@code NYA_ENTWORKS_MANGA_EH_SCAN_COOKIE}，
     * 这里另兼容旧拼写 {@code NYA_ENTWORKS_MANGA_EHSCAN_COOKIE}。别提交进仓库。
     */
    private String cookie() {
        String c = props.getEhScan().getCookie();
        if (isBlank(c)) {
            c = System.getenv("NYA_ENTWORKS_MANGA_EH_SCAN_COOKIE");
        }
        if (isBlank(c)) {
            c = System.getenv("NYA_ENTWORKS_MANGA_EHSCAN_COOKIE");
        }
        return c;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String location(HttpResponse<?> resp) {
        return resp.headers().firstValue("location").map(l -> " → " + l).orElse("");
    }

    /**
     * 相邻请求限流，防封 IP。实际间隔在 {@code [base, 2×base]} 之间随机 —— 固定节奏会被
     * 反爬识别，随机抖动更像人，增加一层保险。
     */
    private void rateLimit() throws InterruptedException {
        long delay = props.getEhScan().getRequestDelayMs();
        if (delay > 0) {
            long jitter = ThreadLocalRandom.current().nextLong(0, delay + 1);
            Thread.sleep(delay + jitter);
        }
    }

    /**
     * 搜索站点顺序：表站 e-hentai（快、稳、无过载保护）优先，再主站（默认里站 exhentai，更全、
     * 能搜到表站隐藏的 gallery）。主站本身是表站时只有一个。
     * <p>交给调用方是为了让它把站点循环放在<b>搜索词循环之外</b>：一个词搜空就立刻换站，
     * 等于每个空词都付两个请求；而「先用全部词搜表站，全空再整轮换里站」在词表 5 档时
     * 把最坏请求数从 10 压到 5，且每个（站点, 词）组合仍然都会被访问到，findability 不变。
     */
    public List<String> searchSites() {
        String primary = baseUrl();
        return E_HENTAI.equals(primary) ? List.of(primary) : List.of(E_HENTAI, primary);
    }

    /**
     * 在指定站点搜一个词。结果（<b>含空结果</b>）按 {@code search-cache-minutes} 缓存。
     */
    public List<GalleryRef> searchOn(String base, String query) throws IOException, InterruptedException {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String key = base + '\n' + query;
        List<GalleryRef> hit = fromCache(searchCache, key);
        if (hit != null) {
            return hit;
        }
        rateLimit();
        String url = base + "/?f_search="
                + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&f_cats=1023&f_apply=Apply";
        List<GalleryRef> refs = MangaEhHtmlParser.parseSearchResults(get(url)).stream()
                .map(r -> new GalleryRef(r.gid(), r.token(), base))
                .toList();
        putCache(searchCache, key, refs);
        return refs;
    }

    /**
     * 批量取候选 gallery 元数据与标签（gdata 一次可带多组 gid/token，天然省请求）。
     * <p>逐 gid 走缓存：批量扫描时同一作者的多本会反复搜出同一批 gallery，
     * 已取过的 gid 直接复用，只为真正缺的那些发请求；全部命中缓存时一个请求都不发。
     */
    public List<GData> gdata(List<GalleryRef> refs) throws IOException, InterruptedException {
        if (refs == null || refs.isEmpty()) {
            return List.of();
        }
        // 按来源站点分组：搜索回退到表站后，ref 来自 e-hentai，api 请求得用对应域（cookie 不同）
        Map<String, List<GalleryRef>> byBase = new LinkedHashMap<>();
        List<GData> out = new ArrayList<>();
        for (GalleryRef r : refs) {
            GData cached = fromCache(gdataCache, String.valueOf(r.gid()));
            if (cached != null) {
                out.add(cached);
                continue;
            }
            String base = r.baseUrl() == null ? baseUrl() : r.baseUrl();
            byBase.computeIfAbsent(base, k -> new ArrayList<>()).add(r);
        }
        for (Map.Entry<String, List<GalleryRef>> e : byBase.entrySet()) {
            String base = e.getKey();
            List<GalleryRef> group = e.getValue();
            for (int i = 0; i < group.size(); i += 25) {
                List<GalleryRef> chunk = group.subList(i, Math.min(group.size(), i + 25));
                rateLimit();
                JSONArray gidlist = new JSONArray();
                for (GalleryRef r : chunk) {
                    JSONArray pair = new JSONArray();
                    pair.add(r.gid());
                    pair.add(r.token());
                    gidlist.add(pair);
                }
                JSONObject body = new JSONObject();
                body.put("method", "gdata");
                body.put("gidlist", gidlist);
                body.put("namespace", 1);
                List<GData> fetched = MangaEhHtmlParser.parseGData(post(base + "/api.php", body.toJSONString()));
                for (GData g : fetched) {
                    putCache(gdataCache, String.valueOf(g.gid()), g);
                }
                out.addAll(fetched);
            }
        }
        return out;
    }

    // ---- 进程内缓存 ----

    /**
     * 缓存值 + 写入时刻。TTL 判定用 {@code System.currentTimeMillis()}，不做后台清理线程 ——
     * 过期项在下次读到时自然失效，容量到顶时整体清空（见 {@link #putCache}）。
     */
    private record Cached<T>(T value, long at) {
    }

    /** 容量上限：到顶整体清空。批量扫 15000 本的量级远够用，且避免无界增长吃堆。 */
    private static final int CACHE_MAX = 20_000;

    private final Map<String, Cached<List<GalleryRef>>> searchCache = new ConcurrentHashMap<>();
    private final Map<String, Cached<GData>> gdataCache = new ConcurrentHashMap<>();

    private long cacheTtlMs() {
        return props.getEhScan().getSearchCacheMinutes() * 60_000L;
    }

    private <T> T fromCache(Map<String, Cached<T>> cache, String key) {
        long ttl = cacheTtlMs();
        if (ttl <= 0) {
            return null;
        }
        Cached<T> c = cache.get(key);
        if (c == null) {
            return null;
        }
        if (System.currentTimeMillis() - c.at() > ttl) {
            cache.remove(key, c);
            return null;
        }
        return c.value();
    }

    private <T> void putCache(Map<String, Cached<T>> cache, String key, T value) {
        if (cacheTtlMs() <= 0) {
            return;
        }
        if (cache.size() >= CACHE_MAX) {
            cache.clear();
        }
        cache.put(key, new Cached<>(value, System.currentTimeMillis()));
    }

    /**
     * 抓 gallery 详情页的标签赞同数（tag → weight）。未开启 {@code scrape-weight} 时直接返回空。
     */
    public Map<String, Integer> tagWeights(long gid, String token) throws IOException, InterruptedException {
        if (!props.getEhScan().isScrapeWeight()) {
            return Map.of();
        }
        rateLimit();
        return MangaEhHtmlParser.parseTagWeights(get(baseUrl() + "/g/" + gid + "/" + token + "/"));
    }

    /**
     * 封面反向搜索：把封面图字节传给 e-hentai 的 {@code image_lookup.php}，返回相似 gallery。
     * <p>对齐 LANraragi 的 thumbnail reverse search：{@code fs_similar} 开相似搜索、
     * {@code fs_covers} 只比对封面（漫画首图就是封面），命中质量比全图搜索高。站点返回
     * 302 跳到结果页，{@link HttpClient.Redirect#NORMAL} 会自动跟随，再用搜索页解析器抽 (gid,token)。
     * <p>返回的 gid/token 表里站通用，故统一走表站上传（不需要 igneous），后续 gdata 仍按站点取。
     *
     * @param image    封面图字节（jpg/png 均可，站点会自己缩）
     * @param fileName 上传用的文件名，仅用于 multipart 的 filename 字段
     * @return 相似 gallery 列表（可能为空）；封面为空时返回空列表
     */
    public List<GalleryRef> imageLookup(byte[] image, String fileName)
            throws IOException, InterruptedException {
        if (image == null || image.length == 0) {
            return List.of();
        }
        rateLimit();
        String boundary = "----nyaEntworksManga" + Long.toHexString(
                ThreadLocalRandom.current().nextLong() & 0x7fffffffffffffffL);
        byte[] body = buildMultipart(boundary, image,
                isBlank(fileName) ? "cover.jpg" : fileName);
        String html = postMultipart(IMAGE_LOOKUP, boundary, body);
        return MangaEhHtmlParser.parseSearchResults(html).stream()
                .map(r -> new GalleryRef(r.gid(), r.token(), null))
                .toList();
    }

    /**
     * 拼 {@code image_lookup.php} 要的 multipart 表单：一个文件字段 {@code sfile} + 三个开关字段。
     * <p>字段口径同 e-hentai 上传页的「File Search」表单，缺一个站点就当普通表单、搜不出结果。
     */
    private static byte[] buildMultipart(String boundary, byte[] image, String fileName)
            throws IOException {
        var out = new java.io.ByteArrayOutputStream();
        String dash = "--" + boundary + "\r\n";
        // 文件字段
        out.write(dash.getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"sfile\"; filename=\""
                + fileName + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(image);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        // 三个开关：相似搜索、只比封面、提交按钮
        for (String[] kv : new String[][]{
                {"fs_similar", "on"}, {"fs_covers", "on"}, {"f_sfile", "File Search"}}) {
            out.write(dash.getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"" + kv[0] + "\"\r\n\r\n"
                    + kv[1] + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private String postMultipart(String url, String boundary, byte[] body)
            throws IOException, InterruptedException {
        return withRetry(() -> {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(45))
                    .header("User-Agent", UA)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            if (cookie() != null && !cookie().isBlank()) {
                b.header("Cookie", cookie());
            }
            HttpResponse<String> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code != 200) {
                String msg = "POST " + url + " 返回 HTTP " + code + location(resp);
                throw retryable(code) ? new RetryableException(msg) : new IOException(msg);
            }
            return resp.body();
        });
    }

    private String get(String url) throws IOException, InterruptedException {
        return withRetry(() -> {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(45))
                    .header("User-Agent", UA)
                    .GET();
            if (cookie() != null && !cookie().isBlank()) {
                b.header("Cookie", cookie());
            }
            HttpResponse<String> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code != 200) {
                String msg = "GET " + url + " 返回 HTTP " + code + location(resp);
                throw retryable(code) ? new RetryableException(msg) : new IOException(msg);
            }
            return resp.body();
        });
    }

    private String post(String url, String json) throws IOException, InterruptedException {
        return withRetry(() -> {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(45))
                    .header("User-Agent", UA)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
            if (cookie() != null && !cookie().isBlank()) {
                b.header("Cookie", cookie());
            }
            HttpResponse<String> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code != 200) {
                String msg = "POST " + url + " 返回 HTTP " + code + location(resp);
                throw retryable(code) ? new RetryableException(msg) : new IOException(msg);
            }
            return resp.body();
        });
    }

    /**
     * 可重试错误的退避重试。exhentai 源站响应方差极大（同一查询 1.7s~20s~100s+ 波动），
     * 偶发 522 源站超时、302 过载重定向、429 限流、连接/请求超时 —— 这些都是暂时的，
     * 退避 5s/15s 后各重试一次（最多 3 次尝试）。HTTP 4xx 属确定性错误（如 403/404），
     * 不重试、直接抛。
     */
    private String withRetry(Fetch f) throws IOException, InterruptedException {
        IOException last = null;
        int[] backoff = {5_000, 15_000};
        for (int attempt = 0; attempt <= backoff.length; attempt++) {
            try {
                return f.run();
            } catch (HttpTimeoutException | RetryableException e) {
                last = e;
                if (attempt < backoff.length) {
                    Thread.sleep(backoff[attempt]);
                }
            }
        }
        throw last;
    }

    /** 522/5xx 源站过载、429 限流、302/301 过载重定向 —— 都是暂时的，退避后重试 */
    private static boolean retryable(int code) {
        return code == 522 || code == 429 || code == 302 || code == 301
                || (code >= 500 && code < 600);
    }

    /** 可重试的 HTTP 错误（区别于 4xx 确定性错误） */
    private static class RetryableException extends IOException {
        RetryableException(String message) {
            super(message);
        }
    }

    @FunctionalInterface
    private interface Fetch {
        String run() throws IOException, InterruptedException;
    }

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";
}
