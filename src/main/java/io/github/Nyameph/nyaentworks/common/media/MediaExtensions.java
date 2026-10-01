package io.github.Nyameph.nyaentworks.common.media;

import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 音视频 / 歌词扩展名的分类与「去扩展名主名」（实现说明 5.6 / 5.8 的一部分）。纯静态。
 *
 * <p>原文在 {@code SongNameParser} 里，2026-09-13 拆喊麦模块时搬到这里：扫磁盘归组是
 * 歌曲与喊麦<b>逐行同构</b>的第一件事，而扩展名清单只能有一份 —— 两份的下场是加了
 * 一个新格式（比如 {@code .mkv}）只改了其中一边，另一边静默漏文件。
 * {@code SongNameParser} 保留同名转发方法，song 侧的调用点因此一个都不用改。
 */
public final class MediaExtensions {

    private MediaExtensions() {
    }

    /** 视频扩展名。只有 mp4，但留成集合便于以后加 */
    private static final List<String> VIDEO_EXTENSIONS = List.of("mp4");

    /** 音频扩展名。{@code m4p} 是实际数据里的一个异类，一并认了 */
    private static final List<String> AUDIO_EXTENSIONS = List.of("mp3", "m4a", "m4p", "flac", "wav");

    /**
     * 歌词扩展名。{@code ass} 认成歌词但<b>不解析</b>（实际只有 4 个文件），
     * 这样它至少能在页面上显示成「格式不支持」而不是被当成不存在。
     */
    private static final List<String> LYRIC_EXTENSIONS = List.of("lrc", "srt", "txt", "ass");

    public static boolean isVideo(String fileName) {
        return VIDEO_EXTENSIONS.contains(extension(fileName));
    }

    public static boolean isAudio(String fileName) {
        return AUDIO_EXTENSIONS.contains(extension(fileName));
    }

    public static boolean isLyric(String fileName) {
        return LYRIC_EXTENSIONS.contains(extension(fileName));
    }

    public static boolean isMedia(String fileName) {
        return isVideo(fileName) || isAudio(fileName);
    }

    /** 认识的扩展名，其余文件（如 {@code #统计.xlsx}）扫描时跳过 */
    public static boolean isKnown(String fileName) {
        return isMedia(fileName) || isLyric(fileName);
    }

    /**
     * 小写扩展名，不含点。
     * <p>必须小写化：实际数据里有大写后缀（2026-09-23 在库里实测 {@code song_file} 81 行、
     * {@code shout_file} 2 行是 {@code .MP3}），按原文比会漏掉。
     */
    public static String extension(String fileName) {
        if (StringUtils.isBlank(fileName)) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 后缀，<b>含点、保留原文大小写</b>（如 {@code .MP3}）。口径 = 完整文件名去掉打头的
     * {@code mainName} 那一段，构造上恒满足 {@code mainName + suffix == fileName}。
     *
     * <p><b>不要用 {@link #extension()} 代替它</b>：那个 {@code toLowerCase}，而实测
     * {@code song_file} 里 81 行、{@code shout_file} 里 2 行是大写 {@code .MP3}。
     * 小写化之后「{@code main_name} 拼回的后缀」与磁盘上的真实文件名不再相等，
     * 症状是这些行<b>每轮同步都被删掉再插一遍</b>（{@code id} 与 {@code create_time}
     * 每轮都变），而唯一的信号是同步计数不是 0 —— 见 {@code SongSyncService} 类注释。
     *
     * <p>与 {@link #mainName(String)} 一样用<b>前缀相减</b>，而不是「从最后一个点切开」：
     * 作者名里带点是常态（{@code B.Y}、{@code Paris_polyphylla & B.Y}、{@code Mr.Q}），
     * 按最后一个点切会切错。{@code fileName} 不以 {@code mainName} 打头时返回空串而不是硬切
     * —— 那意味着这对参数不是从同一个文件名推出来的，切了只会得到垃圾。
     *
     * <p>{@code mainName} 为空时<b>整名都算后缀</b>，而不是也返回空串：这样
     * {@code mainName + suffix} 仍然等于原文件名，那条不变量成立比「拦下可疑入参」重要
     * （它是 {@code uk_file(song_id, main_name, suffix)} 唯一键的前提）。实际只在一个
     * 文件名以点打头时走到（如 {@code .mp3}）。
     */
    public static String suffix(String fileName, String mainName) {
        if (StringUtils.isBlank(fileName)) {
            return "";
        }
        String prefix = StringUtils.defaultString(mainName);
        return fileName.startsWith(prefix) ? fileName.substring(prefix.length()) : "";
    }

    /** 拼回完整文件名 = {@code mainName + suffix}。{@code suffix} 已含点，别再加。 */
    public static String fullName(String mainName, String suffix) {
        return StringUtils.defaultString(mainName) + StringUtils.defaultString(suffix);
    }

    /**
     * 去掉扩展名。<b>归组的键</b>：同一分区下 {@code mainName} 相同的文件是一组
     * （文档 4.11）。
     *
     * <p><b>只剥认识的扩展名</b>，而不是从最后一个点切开。这一条不是洁癖：
     * 作者名里带点是常见的（实测有 {@code B.Y}、{@code Paris_polyphylla & B.Y}），
     * 按最后一个点切会把 {@code B.Y & 白桃 - 仙子下山（不问ciaga）} 切成 {@code B}。
     * 更麻扎的是这个方法会被调用两次 —— 扫描时剥一次扩展名，文件名解析内部
     * 为了「传完整文件名或主名都等价」又剥一次 —— 于是名字里带点的那批解析全线失败，
     * 症状是「这几首歌在页面上显示解析失败，可它们的文件名明明是标准格式」。
     */
    public static String mainName(String fileName) {
        if (StringUtils.isBlank(fileName)) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return fileName;
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        boolean known = VIDEO_EXTENSIONS.contains(ext) || AUDIO_EXTENSIONS.contains(ext)
                || LYRIC_EXTENSIONS.contains(ext);
        return known ? fileName.substring(0, dot) : fileName;
    }

    /** {@code ass} 不解析，见类注释 */
    public static boolean isParsableLyric(String fileName) {
        String ext = extension(fileName);
        return Arrays.asList("lrc", "srt", "txt").contains(ext);
    }

    /** 供页面显示：认识的扩展名分类 */
    public static List<String> videoExtensions() {
        return VIDEO_EXTENSIONS;
    }

    public static List<String> audioExtensions() {
        return AUDIO_EXTENSIONS;
    }

    public static List<String> lyricExtensions() {
        return LYRIC_EXTENSIONS;
    }
}
