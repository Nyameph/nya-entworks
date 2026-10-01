package io.github.Nyameph.nyaentworks.common.media;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 媒体流（实现说明 2.11 / 第 8 节）。
 *
 * <p><b>必须返回 {@code ResponseEntity<Resource>}，不能照抄漫画的图片端点。</b>
 * 图片端点返回 {@code byte[]}：整个文件读进堆、不支持 {@code Range}。视频上这两条都致命 ——
 * 一个 200MB 的 mp4 会占 200MB 堆，而且：
 * <ul>
 *   <li><b>拖不了进度条</b>：没有 {@code Accept-Ranges} 与 206 的话浏览器只能从头下。
 *   <li><b>可能整个播不了</b>：mp4 的 {@code moov} atom 在文件尾时，浏览器要先取尾部
 *       才知道怎么解码，做不到就直接失败。
 * </ul>
 * 用 {@code FileSystemResource} 交给 Spring MVC 的 {@code HttpEntityMethodProcessor}，
 * {@code Accept-Ranges}、{@code Range} 解析、206 与 {@code ResourceRegion} 全自动。
 *
 * <p><b>路径校验两道，缺一不可</b>（实现说明坑 12）：扩展名白名单 + {@code normalize()}
 * 后仍落在受管根内。少了任一道，这个端点就是「读本机任意文件」。
 * 受管根由各模块的 {@link MediaRootProvider} 申报，见那个接口。
 *
 * <p><b>第三道「或者」：临时预览令牌</b>（2026-09-25 加，见 {@link MediaPreviewTokens}）。
 * 原曲页「新增原曲」选的文件天生在受管根之外，导入前试听是这一步最有用的功能，
 * 所以带 {@code preview} 参数时允许「受管根外 + 令牌对得上这一个路径」。前两道里
 * 的扩展名白名单与 {@code normalize()} 一个都不松 —— 令牌只替换「落在受管根内」那一半。
 *
 * <p>放在 {@code common} 而不是某个模块下：这个端点是「读本机任意文件」的唯一闸门，
 * 校验只能有一份 —— 两份的下场是其中一份少了 {@code normalize()}。
 */
@Service
@RequiredArgsConstructor
public class MediaStreamService {

    /** 各模块申报的受管根。没有模块实现这个接口时是空表，此时任何路径都不受管 */
    private final List<MediaRootProvider> rootProviders;

    /** 受管根外那一条路的签名与校验，见类注释与那个类自己的 javadoc */
    private final MediaPreviewTokens previewTokens;

    /** 用于 {@code Content-Type}。浏览器主要靠这个决定要不要试着解码 */
    private static MediaType contentType(String fileName) {
        return switch (MediaExtensions.extension(fileName)) {
            case "mp4" -> MediaType.parseMediaType("video/mp4");
            case "mp3" -> MediaType.parseMediaType("audio/mpeg");
            // m4a/m4p 是 MP4 容器里的 AAC。写 audio/mp4 而不是 audio/x-m4a：
            // 后者是个非标准的老写法，部分浏览器不认
            case "m4a", "m4p" -> MediaType.parseMediaType("audio/mp4");
            case "flac" -> MediaType.parseMediaType("audio/flac");
            case "wav" -> MediaType.parseMediaType("audio/wav");
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }

    /**
     * 流式返回一个媒体文件。
     *
     * @param path 绝对路径。必须落在受管的根之一下，且是认识的媒体扩展名
     */
    public ResponseEntity<Resource> stream(String path) {
        return stream(path, null);
    }

    /**
     * 同上，但允许用<b>临时预览令牌</b>换取「受管根之外」的放行
     * （原曲表单导入前试听，见 {@link MediaPreviewTokens}）。
     *
     * @param previewToken {@code expand} 下发的令牌；不传 = 只认受管根
     */
    public ResponseEntity<Resource> stream(String path, String previewToken) {
        Path file = requireManagedMedia(path, previewToken);
        FileSystemResource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .contentType(contentType(file.getFileName().toString()))
                // 显式声明一遍。Spring 对 Resource 本来就会加，写出来是为了让「这个端点
                // 支持 Range」成为一件读代码就能看到的事
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                // 媒体文件不会原地改内容（改了就是改名或换文件），可以让浏览器缓存久一点
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400")
                .body(resource);
    }

    /**
     * 校验并返回受管的媒体文件路径。
     *
     * <p>只放媒体（视频 / 音频），不放歌词 —— 歌词走各模块的歌词接口
     * （{@code LyricTextReader}），那边要做编码嗅探，直接给字节流反而会让前端按 UTF-8 解出乱码。
     */
    public Path requireManagedMedia(String path) {
        return requireManagedMedia(path, null);
    }

    /**
     * 同上，外加一条「或者令牌对得上这一个路径」。
     *
     * <p>顺序是刻意的：<b>扩展名白名单永远先判</b>，令牌只顶替「落在受管根内」那一条
     * —— 否则令牌就成了「读本机任意文件」的通票（它本来只该是「这一个音视频文件让你试听」）。
     */
    public Path requireManagedMedia(String path, String previewToken) {
        if (StringUtils.isBlank(path)) {
            throw new IllegalArgumentException("文件路径不能为空");
        }
        Path target = Path.of(path).toAbsolutePath().normalize();
        String fileName = target.getFileName() == null ? "" : target.getFileName().toString();
        if (!MediaExtensions.isMedia(fileName)) {
            throw new IllegalArgumentException("不是认识的音视频文件，不给读：" + fileName);
        }
        boolean allowed = false;
        for (String root : managedRoots()) {
            if (target.startsWith(Path.of(root).toAbsolutePath().normalize())) {
                allowed = true;
                break;
            }
        }
        if (!allowed && !previewTokens.matches(target, previewToken)) {
            throw new IllegalArgumentException("不在受管的媒体目录下，不给读：" + path);
        }
        if (!Files.isRegularFile(target)) {
            throw new IllegalStateException("文件不在了：" + target + "。刷新一下列表");
        }
        return target;
    }

    /**
     * 给一个文件出「能在浏览器里播的 URL」；放不了返回 {@code null}（原因由调用方自己给）。
     *
     * <p>一律带预览令牌，不区分受管根里还是外：令牌对受管根内的路径同样有效
     * （那一条本来就放行），多一个参数换来调用方只有一条规矩。
     * <b>这是 Java 侧唯一知道媒体端点路径的地方</b> —— 前端不要自己拼，
     * 拿这个串挂 {@code <audio src>} 就行。
     */
    public String previewUrl(String path) {
        if (StringUtils.isBlank(path)) {
            return null;
        }
        Path target = Path.of(path).toAbsolutePath().normalize();
        String fileName = target.getFileName() == null ? "" : target.getFileName().toString();
        if (!MediaExtensions.isMedia(fileName) || !Files.isRegularFile(target)) {
            return null;
        }
        return "/api/media/stream?path="
                // 空格写成 %20 而不是 +：与前端 Util.mediaUrl 的 encodeURIComponent 同形，
                // 免得同一个文件在两条路上出来两个不同的 URL（排查时容易以为是两个文件）
                + URLEncoder.encode(target.toString(), StandardCharsets.UTF_8).replace("+", "%20")
                + "&preview=" + previewTokens.sign(target.toString());
    }

    /** 受管的根：各模块申报的合起来，去重保序 */
    private Set<String> managedRoots() {
        Set<String> roots = new LinkedHashSet<>();
        for (MediaRootProvider provider : rootProviders) {
            for (String root : provider.mediaRoots()) {
                if (StringUtils.isNotBlank(root)) {
                    roots.add(root);
                }
            }
        }
        return roots;
    }

    /**
     * 用系统默认播放器打开 —— 显卡解不了 HEVC 时的逃生口（文档 8.0）。
     * <p>不走 AWT 的 {@code Desktop}：它在 headless 会话下会误报不支持
     * （同 {@code MangaStoreService.openFolder} 的理由）。这是 Windows 单机，
     * {@code cmd /c start} 会按文件关联挑播放器，最贴近「双击这个文件」。
     */
    public void openInLocalPlayer(String path) {
        Path file = requireManagedMedia(path);
        try {
            // 第一个空串是 start 的窗口标题参数：路径带空格时若不给标题，
            // start 会把带引号的路径当成标题而不是要打开的文件
            new ProcessBuilder("cmd", "/c", "start", "", file.toString()).start();
        } catch (IOException e) {
            throw new IllegalStateException("打开本机播放器失败：" + e.getMessage(), e);
        }
    }
}
