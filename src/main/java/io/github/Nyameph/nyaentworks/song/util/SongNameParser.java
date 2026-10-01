package io.github.Nyameph.nyaentworks.song.util;

import org.apache.commons.lang3.StringUtils;
import io.github.Nyameph.nyaentworks.common.media.MediaExtensions;
import io.github.Nyameph.nyaentworks.manga.util.MangaTextUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 歌曲文件名解析（实现说明 5.6）。<b>纯函数，不连库、不碰目录</b> —— 所以
 * {@code SongNameParserTest} 能像 {@code MangaNameParserTest} 一样直接跑。
 *
 * <pre>
 * 作者1 &amp; 作者2 - 曲名（原曲名）#版本号.mp3
 * </pre>
 *
 * <p>与 {@code MangaNameParser} <b>没有任何共用代码</b>，尽管两者都在解析文件名：
 * 歌曲格式是固定四段、无嵌套括号、无词典依赖，套 {@code BracketParser} 那套树形解析
 * 是把简单问题复杂化。这与「模块之间没有调用关系」的项目定位一致。
 * （唯一的例外是复用 {@link MangaTextUtil#normalizeNameKey}，理由见 {@link #originalKey}。）
 *
 * <p><b>扩展名分类不在这里</b>：2026-09-13 拆喊麦模块时搬到了
 * {@link MediaExtensions}（扫磁盘归组是歌曲与喊麦逐行同构的部分）。下面那些
 * {@code isVideo} / {@code extension} / {@code mainName} 是<b>转发</b>，留着只为不动
 * song 侧那 10 个调用点；新代码请直接用 {@code MediaExtensions}。
 */
public final class SongNameParser {

    private SongNameParser() {
    }

    /** 标准分隔符：空格-横线-空格 */
    private static final String SEPARATOR = " - ";

    // ------------------------------------------------------------------
    // 扩展名分类：转发到 common.media.MediaExtensions
    // ------------------------------------------------------------------

    public static boolean isVideo(String fileName) {
        return MediaExtensions.isVideo(fileName);
    }

    public static boolean isAudio(String fileName) {
        return MediaExtensions.isAudio(fileName);
    }

    public static boolean isLyric(String fileName) {
        return MediaExtensions.isLyric(fileName);
    }

    public static boolean isMedia(String fileName) {
        return MediaExtensions.isMedia(fileName);
    }

    /** 认识的扩展名，其余文件（如 {@code #统计.xlsx}）扫描时跳过 */
    public static boolean isKnown(String fileName) {
        return MediaExtensions.isKnown(fileName);
    }

    public static String extension(String fileName) {
        return MediaExtensions.extension(fileName);
    }

    public static String mainName(String fileName) {
        return MediaExtensions.mainName(fileName);
    }

    /**
     * 解析文件名。传入的可以是完整文件名（带扩展名）或已去扩展名的主名，两者等价。
     * <p>解析失败不抛异常，而是返回带 {@code parseFailedReason} 的结果 ——
     * 播放和打分不依赖解析结果，不能因为名字不规范就让人播不了
     * （同「识别不了的文件默认不导入、但列出来」的立场）。
     */
    public static SongName parse(String fileName) {
        String main = mainName(StringUtils.defaultString(fileName)).trim();
        if (main.isEmpty()) {
            return failed("", "文件名为空");
        }

        // 1) 先切版本号。按第一个 # 切，后面整段都是版本号 ——
        //    实际数据里 # 会出现两次（卡路里#RWqr0sXyycoK0GXm#A+B），按最后一个切会切错。
        //    版本号也不一定是数字（有 #ver2、#少、#幼）。
        String withoutVersion = main;
        String version = null;
        int hash = main.indexOf('#');
        if (hash >= 0) {
            withoutVersion = main.substring(0, hash).trim();
            version = StringUtils.trimToNull(main.substring(hash + 1));
            if (withoutVersion.isEmpty()) {
                // 整个名字以 # 开头，没有作者与曲名可言
                return failed(main, "文件名以 # 开头，取不出作者与曲名");
            }
        }

        // 2) 切作者与曲名。标准是 " - "，但实际有 2 个案例是 " -"（前有空格后没有），
        //    容错但标记 looseSeparator，让页面能提示去规范化。
        boolean loose = false;
        int sep = withoutVersion.indexOf(SEPARATOR);
        int sepLength = SEPARATOR.length();
        if (sep < 0) {
            sep = withoutVersion.indexOf(" -");
            sepLength = 2;
            loose = sep >= 0;
        }
        if (sep < 0) {
            return failed(main, "找不到「 - 」分隔符，取不出作者");
        }
        String artistPart = withoutVersion.substring(0, sep).trim();
        String titlePart = withoutVersion.substring(sep + sepLength).trim();
        if (artistPart.isEmpty()) {
            return failed(main, "分隔符前没有作者");
        }
        if (titlePart.isEmpty()) {
            return failed(main, "分隔符后没有曲名");
        }

        // 3) 切原曲名：曲名尾部的全角（…）。没有则原曲名 = 曲名（原曲没改词，直接翻唱）。
        //    括号内允许写成「原曲名_原曲作者」，靠最后一个 _ 把作者切出来 —— 同名原曲
        //    （如「后来_刘若英」 vs「后来_xxx」）靠它区分。
        String title = titlePart;
        String original = titlePart;
        String originalArtist = null;
        boolean explicit = false;
        if (titlePart.endsWith("）")) {
            int open = titlePart.lastIndexOf('（');
            if (open > 0) {
                String inner = StringUtils.trimToNull(
                        titlePart.substring(open + 1, titlePart.length() - 1));
                String outer = StringUtils.trimToNull(titlePart.substring(0, open));
                // 括号内外都得有内容，否则「曲名（）」这种反而不如当成没有原曲名
                if (inner != null && outer != null) {
                    title = outer;
                    String[] split = splitOriginal(inner);
                    original = split[0];
                    originalArtist = split[1];
                    explicit = true;
                }
            }
        }

        // 4) 作者按 & 拆
        List<String> artists = new ArrayList<>();
        for (String piece : artistPart.split("&")) {
            String one = StringUtils.trimToNull(piece);
            if (one != null) {
                artists.add(one);
            }
        }
        if (artists.isEmpty()) {
            return failed(main, "作者为空");
        }

        return new SongName(main, List.copyOf(artists), title, original, originalArtist,
                version, explicit, loose, null);
    }

    /**
     * 把「原曲名」或「原曲名_原曲作者」按最后一个 {@code _} 拆成 {@code [原曲名, 原曲作者]}。
     * 只有两边拆完都非空才算拆成功，否则整体当成原曲名、作者为 null —— 原曲名本身含
     * {@code _} 的概率高于作者名含 {@code _}，所以按最后一个切能保证 round-trip
     * （拆出去还能拼回来）。文件名解析与模板文件夹解析共用这一份口径。
     */
    public static String[] splitOriginal(String original) {
        int idx = original.lastIndexOf('_');
        if (idx <= 0) {
            return new String[]{original, null};
        }
        String name = StringUtils.trimToNull(original.substring(0, idx));
        String artist = StringUtils.trimToNull(original.substring(idx + 1));
        if (name == null || artist == null) {
            return new String[]{original, null};
        }
        return new String[]{name, artist};
    }

    /**
     * 原曲名的分组键：NFC + trim + 大写。
     * <p>复用漫画那套 {@link MangaTextUtil#normalizeNameKey} 的口径，理由与那边一样 ——
     * 同一个假名有预组合与分解两种写法，不归一会把同一首原曲算成两首。
     */
    public static String originalKey(String originalTitle) {
        return MangaTextUtil.normalizeNameKey(originalTitle);
    }

    /**
     * 归并键：{@code normalize(作者&连接)|normalize(曲名)|normalize(原曲名)}。
     *
     * <p><b>必须与 {@link #originalKey} 同源（都走 NFC + trim + 大写）</b>——否则
     * 日文分解形式的假名在列表归并里算两首、在按原曲名统计里算一首，两端对不上。
     *
     * <p>隐式原曲名（无括号）统一填充为曲名；版本号与文件类型不参与归并 ——
     * {@code 卡路里#1.mp4} 与 {@code 卡路里#2.mp3} 是同一个 merge row 的两个 variant。
     *
     * @param name     解析结果；为 null（没解析出来）时按 {@code mainName} 单个成键
     * @param mainName 去扩展名主名，作未解析时的兜底键
     * @return {@code 作者|曲名|原曲名} 归一化，或 {@code UNPARSED|mainName}
     */
    public static String mergeKey(SongName name, String mainName) {
        if (name == null || !name.parsed()) {
            // 没解析出来：每 mainName 一条，不跨 mainName 归并
            return "UNPARSED|" + mainName;
        }
        String artistsKey = MangaTextUtil.normalizeNameKey(String.join("&", name.artists()));
        String titleKey = MangaTextUtil.normalizeNameKey(name.title());
        String origKey = MangaTextUtil.normalizeNameKey(
                name.originalExplicit() ? name.originalTitle() : name.title());
        // normalizeNameKey 对空白返回 null，已解析的名字三段都应非空，defaultString 兜底
        return StringUtils.defaultString(artistsKey) + "|"
                + StringUtils.defaultString(titleKey) + "|"
                + StringUtils.defaultString(origKey);
    }

    private static SongName failed(String main, String reason) {
        return new SongName(main, List.of(), main, main, null, null, false, false, reason);
    }

    /** 供页面显示：认识的扩展名分类 */
    public static List<String> videoExtensions() {
        return MediaExtensions.videoExtensions();
    }

    public static List<String> audioExtensions() {
        return MediaExtensions.audioExtensions();
    }

    public static List<String> lyricExtensions() {
        return MediaExtensions.lyricExtensions();
    }

    /** {@code ass} 不解析，见 {@link MediaExtensions} 类注释 */
    public static boolean isParsableLyric(String fileName) {
        return MediaExtensions.isParsableLyric(fileName);
    }
}
