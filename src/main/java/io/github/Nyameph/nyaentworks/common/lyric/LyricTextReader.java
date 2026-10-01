package io.github.Nyameph.nyaentworks.common.lyric;

import io.github.Nyameph.nyaentworks.common.media.MediaExtensions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 读一个歌词文件：编码嗅探 + 解析（实现说明 2.10 / 5.8）。
 *
 * <p><b>为什么解析放后端</b>：不是因为解析难（三种格式各十几行正则），而是因为
 * <b>编码嗅探</b>必须在后端做。磁盘上的歌词编码是混的（实测 741 utf-8 / 74 GBK 系 /
 * 12 utf-16），浏览器的 {@code fetch().text()} 只会按 UTF-8 解，GBK 那 74 个会变成乱码
 * 且没有任何报错。{@link TextDecoder} 那套 BOM → 严格 UTF-8 → GBK 的判断在
 * 前端拿不到原始字节的情况下做不了。
 *
 * <p><b>不做路径校验</b>：这里只接受「已经解析好完整路径」的调用。文件名是不是真在
 * 组里 / 模板里，由调用方（{@code SongLyricService} / {@code ShoutLyricService} /
 * {@code SongTemplateService}）用扫描结果白名单比对 —— 直接拿入参 {@code resolve}
 * 就等于让调用方指定任意相对路径（{@code ../../..}），与图片端点那句「读本机任意
 * 文件的口子」同理。
 *
 * <p>放在 {@code common} 下：填词歌曲、喊麦、原曲模板三处都读歌词。
 */
public final class LyricTextReader {

    private LyricTextReader() {
    }

    /**
     * 一个歌词文件的解析结果。
     *
     * @param fileName  歌词文件名
     * @param extension 小写扩展名
     * @param supported 是否解析了。{@code ass} 为 false，页面显示「格式不支持」
     * @param timed     是否有时间轴。txt 为 false，页面降级成纯文本并标明不滚动
     * @param lines     歌词行
     * @param message   不支持或读失败时的说明，正常时 {@code null}
     */
    public record Lyric(String fileName,
                        String extension,
                        boolean supported,
                        boolean timed,
                        List<LyricLine> lines,
                        String message) {
    }

    /**
     * 读一个<b>已经解析好完整路径</b>的歌词文件。
     *
     * @param file 歌词文件绝对路径
     * @param name 文件名（用于取扩展名与回填 {@code fileName}）
     */
    public static Lyric read(Path file, String name) {
        String extension = MediaExtensions.extension(name);

        if (!MediaExtensions.isParsableLyric(name)) {
            // ass 只有 4 个文件，不值得引解析器（实现说明 5.8）。
            // 但要显示成「格式不支持」而不是「没有歌词」—— 后者会让人以为文件丢了
            return new Lyric(name, extension, false, false, List.of(),
                    "「." + extension + "」格式不解析（全库只有 4 个 ass 文件，不值得引解析器）。"
                            + "文件仍在磁盘上，可用本机播放器打开");
        }

        if (!Files.isRegularFile(file)) {
            return new Lyric(name, extension, false, false, List.of(),
                    "歌词文件不在了：" + file + "。刷新一下列表");
        }
        String text;
        try {
            text = TextDecoder.read(file);
        } catch (IOException e) {
            return new Lyric(name, extension, false, false, List.of(),
                    "读歌词失败：" + e.getMessage());
        }

        List<LyricLine> lines = LyricParser.parse(text, extension);
        if (lines.isEmpty()) {
            return new Lyric(name, extension, true, false, List.of(),
                    "歌词文件解析后是空的，多半是内容为空或时间戳格式不认识");
        }
        // txt 没有时间轴（start 全为 null）；lrc/srt 有
        boolean timed = lines.getFirst().start() != null;
        return new Lyric(name, extension, true, timed, lines, null);
    }
}
