package io.github.Nyameph.nyaentworks.manga.util;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.github.Nyameph.nyaentworks.manga.util.MangaEhClient.GData;
import io.github.Nyameph.nyaentworks.manga.util.MangaEhClient.GalleryRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * e-hentai / exhentai 的 HTML / JSON 解析，纯静态、不连库。
 * <p>eh 前端 DOM 改版较频繁，这里全部用宽容的正则：匹配不到就返回空，调用方静默降级，
 * 不抛异常。三个用途：搜画廊列表的 (gid,token)、gdata 元数据 JSON、详情页标签赞同数。
 */
public final class MangaEhHtmlParser {

    private MangaEhHtmlParser() {
    }

    /** 搜索页里每个 gallery 的链接：/g/{gid}/{token}/ */
    private static final Pattern GALLERY_LINK = Pattern.compile("/g/(\\d+)/([0-9a-fA-F]{10})/");

    /** 裸 "gid/token"（供手工贴 gallery 定位用） */
    private static final Pattern BARE_REF = Pattern.compile("(\\d{2,10})/([0-9a-fA-F]{10})");

    /** 详情页 taglist 里的标签链接：/tag/{namespace}:{name}（取整个 <a> 块，好在属性里找投票数） */
    private static final Pattern TAG_ANCHOR = Pattern.compile(
            "<a[^>]+href=\"/tag/([a-z]+):([^\"'\\s>]+)\"[^>]*>.*?</a>");

    /** 标签权重：紧跟标签的 +N / -N 投票数 */
    private static final Pattern VOTE = Pattern.compile("([+-]\\d{1,6})");

    /** 从搜索页 HTML 提取候选 gallery 的 (gid, token)，按 gid 去重、保序。 */
    public static List<GalleryRef> parseSearchResults(String html) {
        Set<Long> seen = new LinkedHashSet<>();
        List<GalleryRef> out = new ArrayList<>();
        if (html == null) {
            return out;
        }
        Matcher m = GALLERY_LINK.matcher(html);
        while (m.find()) {
            long gid = Long.parseLong(m.group(1));
            if (seen.add(gid)) {
                out.add(new GalleryRef(gid, m.group(2), null));
            }
        }
        return out;
    }

    /** 解析 gdata 响应 {@code {"gmetadata":[{...}]}}，跳过 error（已下架）与无标题项。 */
    public static List<GData> parseGData(String json) {
        List<GData> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        JSONObject root = JSONObject.parseObject(json);
        JSONArray meta = root == null ? null : root.getJSONArray("gmetadata");
        if (meta == null) {
            return out;
        }
        for (int i = 0; i < meta.size(); i++) {
            JSONObject m = meta.getJSONObject(i);
            if (m == null || m.getString("error") != null) {
                continue;
            }
            String title = m.getString("title");
            if (title == null || title.isBlank()) {
                continue;
            }
            long gid = m.getLongValue("gid");
            String token = m.getString("token");
            String titleJpn = m.getString("title_jpn");
            List<String> tags = new ArrayList<>();
            JSONArray tarr = m.getJSONArray("tags");
            if (tarr != null) {
                for (int j = 0; j < tarr.size(); j++) {
                    tags.add(tarr.getString(j));
                }
            }
            out.add(new GData(gid, token, title, titleJpn, tags));
        }
        return out;
    }

    /**
     * 从详情页 HTML 解析每个标签的赞同数（{@code namespace:name → weight}）。
     * <p>DOM 会变，拿不到就返回空 map（调用方跳过过滤、不误删）。当前按「标签锚点内
     * 出现的第一个 +N / -N」取，兼容旧版 title 属性、新版数据属性等多种形态。
     */
    public static Map<String, Integer> parseTagWeights(String html) {
        Map<String, Integer> weights = new LinkedHashMap<>();
        if (html == null) {
            return weights;
        }
        Matcher m = TAG_ANCHOR.matcher(html);
        while (m.find()) {
            Matcher v = VOTE.matcher(m.group(0));
            if (v.find()) {
                try {
                    weights.put(m.group(1) + ":" + m.group(2), Integer.parseInt(v.group(1)));
                } catch (NumberFormatException ignore) {
                    // 忽略解析不了的投票数
                }
            }
        }
        return weights;
    }

    /** 从 gallery 链接或裸 gid/token 解析出 (gid, token)，解析不出返回 null */
    public static GalleryRef parseGalleryUrl(String url) {
        if (url == null) {
            return null;
        }
        Matcher m = GALLERY_LINK.matcher(url);
        if (m.find()) {
            return new GalleryRef(Long.parseLong(m.group(1)), m.group(2), null);
        }
        Matcher bare = BARE_REF.matcher(url);
        if (bare.find()) {
            return new GalleryRef(Long.parseLong(bare.group(1)), bare.group(2), null);
        }
        return null;
    }
}
