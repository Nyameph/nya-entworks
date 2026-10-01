package io.github.Nyameph.nyaentworks.song;

import org.junit.jupiter.api.Test;
import io.github.Nyameph.nyaentworks.common.lyric.LyricLine;
import io.github.Nyameph.nyaentworks.common.lyric.LyricParser;
import io.github.Nyameph.nyaentworks.song.util.SongName;
import io.github.Nyameph.nyaentworks.song.util.SongNameParser;
import io.github.Nyameph.nyaentworks.common.media.ScorePartition;
import io.github.Nyameph.nyaentworks.common.lyric.TextDecoder;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 歌曲模块纯函数的验证：文件名解析、分区解析、歌词解析、编码嗅探。
 *
 * <p>这四样都<b>不连库、不碰目录</b>，所以这个类和 {@code MangaNameParserTest} 一样
 * 能直接跑（其余两个测试类依赖开发者本机环境，跑不了）。改这四个类之后应当跑它。
 *
 * <p>用例取自实测的边界情况：{@code #} 出现两次、版本号不是数字、{@code .MP3} 大写后缀、
 * {@code 作者 -曲名} 少一个空格、已经是 {@code 曲名（曲名）} 的补全形态、
 * 作者名里带点（{@code B.Y}）。
 */
public class SongGroupNameParserTest {

    // ------------------------------------------------------------------
    // 文件名解析
    // ------------------------------------------------------------------

    /** 完整形态：多作者 + 原曲名 + 版本号 */
    @Test
    public void parse_full() {
        SongName n = SongNameParser.parse("作者甲 & 作者乙 - 新词曲名（原曲名）#2.mp3");
        assertTrue(n.parsed());
        assertEquals(List.of("作者甲", "作者乙"), n.artists());
        assertEquals("作者甲 & 作者乙", n.artistText());
        assertEquals("新词曲名", n.title());
        assertEquals("原曲名", n.originalTitle());
        assertEquals("2", n.version());
        assertTrue(n.originalExplicit());
        assertFalse(n.needsNormalize());
    }

    /** 最简形态：一个作者、无原曲名、无版本号 */
    @Test
    public void parse_minimal() {
        SongName n = SongNameParser.parse("作者甲 - 曲名.mp3");
        assertTrue(n.parsed());
        assertEquals(List.of("作者甲"), n.artists());
        assertEquals("曲名", n.title());
        // 没写原曲名时 originalTitle = title：原曲没被改词，直接翻唱。
        // 这样按原曲名统计不必区分两种形态
        assertEquals("曲名", n.originalTitle());
        assertNull(n.version());
        assertFalse(n.originalExplicit());
        // 这一批就是「规范化命名」要补的（实测 384 个）
        assertTrue(n.needsNormalize());
        assertEquals("作者甲 - 曲名（曲名）", n.normalizedMainName());
    }

    /**
     * 版本号按<b>第一个</b> {@code #} 切，后面整段都算版本号。
     * <p>实测有 {@code 卡路里#RWqr0sXyycoK0GXm#A+B} 这种 {@code #} 出现两次的，
     * 按最后一个切会把前半段的哈希留在曲名里。
     */
    @Test
    public void parse_doubleHash() {
        SongName n = SongNameParser.parse("作者甲 - 卡路里#RWqr0sXyycoK0GXm#A+B.mp4");
        assertTrue(n.parsed());
        assertEquals("卡路里", n.title());
        assertEquals("RWqr0sXyycoK0GXm#A+B", n.version());
    }

    /** 版本号不一定是数字：实测有 #ver2、#少、#幼 */
    @Test
    public void parse_nonNumericVersion() {
        assertEquals("ver2", SongNameParser.parse("作者甲 - 曲名#ver2.mp3").version());
        assertEquals("少", SongNameParser.parse("作者甲 - 曲名#少.mp3").version());
    }

    /**
     * 松分隔符：{@code 作者 -曲名}（{@code -} 前有空格后没有）。
     * <p>实测 2 个案例。容错解析，但标记出来供页面提示规范化 —— 不静默放过。
     */
    @Test
    public void parse_looseSeparator() {
        SongName n = SongNameParser.parse("作者甲 -曲名（原曲名）.mp3");
        assertTrue(n.parsed());
        assertTrue(n.looseSeparator());
        assertEquals("作者甲", n.artistText());
        assertEquals("曲名", n.title());
        assertEquals("原曲名", n.originalTitle());
    }

    /** 大写扩展名。实测 67 个 .MP3，按原文比会漏掉 */
    @Test
    public void parse_upperCaseExtension() {
        assertEquals("mp3", SongNameParser.extension("作者甲 - 曲名.MP3"));
        assertTrue(SongNameParser.isAudio("作者甲 - 曲名.MP3"));
        assertEquals("作者甲 - 曲名", SongNameParser.mainName("作者甲 - 曲名.MP3"));
    }

    /**
     * 作者名里带点（实测 {@code B.Y}）。
     *
     * <p>{@code mainName} 只剥<b>认识的</b>扩展名，不是从最后一个点切开 ——
     * 否则这个名字会被切成 {@code B}，解析随即失败。而 {@code mainName} 在一次扫描里
     * 会被调用两次（扫描时剥一次，{@code parse} 内部为「传文件名或主名都等价」又剥一次），
     * 所以症状是「这几首在页面上显示解析失败，可文件名明明是标准格式」。实测踩到 3 个。
     */
    @Test
    public void parse_dotInArtistName() {
        assertEquals("B.Y & 白桃 - 仙子下山（不问ciaga）",
                SongNameParser.mainName("B.Y & 白桃 - 仙子下山（不问ciaga）.mp4"));
        // 幂等：已经是主名的再剥一次不变
        assertEquals("B.Y & 白桃 - 仙子下山（不问ciaga）",
                SongNameParser.mainName("B.Y & 白桃 - 仙子下山（不问ciaga）"));

        SongName n = SongNameParser.parse("B.Y & 白桃 - 仙子下山（不问ciaga）.mp4");
        assertTrue(n.parsed());
        assertEquals(List.of("B.Y", "白桃"), n.artists());
        assertEquals("仙子下山", n.title());
        assertEquals("不问ciaga", n.originalTitle());
    }

    /** 已经是补全形态（曲名 = 原曲名）：不该再被判成需要规范化。实测 23 个 */
    @Test
    public void parse_alreadyNormalized() {
        SongName n = SongNameParser.parse("作者甲 - 曲名（曲名）.mp3");
        assertTrue(n.parsed());
        assertEquals("曲名", n.title());
        assertEquals("曲名", n.originalTitle());
        assertTrue(n.originalExplicit());
        assertFalse(n.needsNormalize());
    }

    /** 「曲名（）」括号里是空的：当成没有原曲名，而不是原曲名为空串 */
    @Test
    public void parse_emptyBracket() {
        SongName n = SongNameParser.parse("作者甲 - 曲名（）.mp3");
        assertTrue(n.parsed());
        assertEquals("曲名（）", n.title());
        assertFalse(n.originalExplicit());
    }

    /** 找不到分隔符：解析失败但不抛异常 —— 播放和打分不依赖解析结果 */
    @Test
    public void parse_failedNoSeparator() {
        SongName n = SongNameParser.parse("没有分隔符的名字.mp3");
        assertFalse(n.parsed());
        assertTrue(n.parseFailedReason().contains("分隔符"));
        // 失败时 mainName 仍是原文，页面据此显示原文件名
        assertEquals("没有分隔符的名字", n.mainName());
    }

    /** 以 # 开头：取不出作者与曲名 */
    @Test
    public void parse_failedStartsWithHash() {
        SongName n = SongNameParser.parse("#统计.xlsx");
        assertFalse(n.parsed());
    }

    /** 归组的键是主名，<b>含版本号</b>：#2 是另一组，不是同一组的另一个文件 */
    @Test
    public void mainName_versionIsPartOfIdentity() {
        assertEquals("口是心非", SongNameParser.mainName("口是心非.mp4"));
        assertEquals("口是心非#2", SongNameParser.mainName("口是心非#2.mp4"));
    }

    /** 扩展名分类。ass 认成歌词但不解析 */
    @Test
    public void extensionClassification() {
        assertTrue(SongNameParser.isVideo("a.mp4"));
        assertTrue(SongNameParser.isAudio("a.flac"));
        assertTrue(SongNameParser.isAudio("a.m4p"));
        assertTrue(SongNameParser.isLyric("a.ass"));
        assertFalse(SongNameParser.isParsableLyric("a.ass"));
        assertTrue(SongNameParser.isParsableLyric("a.srt"));
        assertFalse(SongNameParser.isKnown("#统计.xlsx"));
    }

    /** 原曲名的分组键归一化：NFC + trim + 大写 */
    @Test
    public void originalKey_normalized() {
        assertEquals(SongNameParser.originalKey("abc"), SongNameParser.originalKey(" ABC "));
    }

    // ------------------------------------------------------------------
    // mergeKey（归并键，数据结构重构后写库的唯一键口径）
    // ------------------------------------------------------------------

    /**
     * mergeKey 与原曲名统计同源：NFC 归一。
     * <p>日文分解假名（{@code ホ}+{@code ゚}）与预组合（{@code ポ}）在 Java 里
     * {@code equals} 不等 —— 不归一会让「列表归并」与「按原曲名统计」对不上。
     */
    @Test
    public void mergeKey_nfcNormalizes() {
        SongName precomposed = SongNameParser.parse("作者 - 曲名（ポ）.mp3");
        // 分解形假名：ホ(U+30DB) + ゚(U+309A 组合浊点) —— 用 char 构造，不写字面量，免编码坑
        String decomposedOriginal = "ホ" + (char) 0x309A;
        SongName decomposed = SongNameParser.parse("作者 - 曲名（" + decomposedOriginal + "）.mp3");
        // 前置校验：分解形与预组合形在 Java 里确实不等，用例才有意义
        assertNotEquals(decomposedOriginal, "ポ");
        assertEquals(SongNameParser.mergeKey(precomposed, "作者 - 曲名（ポ）"),
                SongNameParser.mergeKey(decomposed, "作者 - 曲名（" + decomposedOriginal + "）"));
    }

    /** 大小写不敏感 + 首尾空白裁剪 */
    @Test
    public void mergeKey_caseAndTrimInsensitive() {
        SongName a = SongNameParser.parse("作者 - Abc（Def）.mp3");
        SongName b = SongNameParser.parse("作者 - abc（def）.mp3");
        assertEquals(SongNameParser.mergeKey(a, "作者 - Abc（Def）"),
                SongNameParser.mergeKey(b, "作者 - abc（def）"));
    }

    /** 隐式原曲名填成曲名：与「曲名（曲名）」自补形态的键相同 */
    @Test
    public void mergeKey_implicitOriginalEqualsTitle() {
        SongName implicit = SongNameParser.parse("作者 - 曲名.mp3");
        SongName explicit = SongNameParser.parse("作者 - 曲名（曲名）.mp3");
        assertEquals(SongNameParser.mergeKey(implicit, "作者 - 曲名"),
                SongNameParser.mergeKey(explicit, "作者 - 曲名（曲名）"));
    }

    /** 版本号与文件类型不参与归并：#1.mp4 与 #2.mp3 是同一个 merge row 的两个 variant */
    @Test
    public void mergeKey_versionAndTypeIgnored() {
        SongName v1 = SongNameParser.parse("作者 - 卡路里#1.mp4");
        SongName v2 = SongNameParser.parse("作者 - 卡路里#2.mp3");
        assertEquals(SongNameParser.mergeKey(v1, "作者 - 卡路里#1"),
                SongNameParser.mergeKey(v2, "作者 - 卡路里#2"));
    }

    /** 解析失败：按 mainName 单个成键，不跨 mainName 归并 */
    @Test
    public void mergeKey_unparsedFallsBackToMainName() {
        SongName failed = SongNameParser.parse("没有分隔符的名字.mp3");
        assertFalse(failed.parsed());
        assertEquals("UNPARSED|没有分隔符的名字",
                SongNameParser.mergeKey(failed, "没有分隔符的名字"));
    }

    /** 没解析出来（name 为 null）：UNPARSED 兜底，与解析失败同口径 */
    @Test
    public void mergeKey_shoutNullName() {
        assertEquals("UNPARSED|kinray7 - 卡路里#1",
                SongNameParser.mergeKey(null, "kinray7 - 卡路里#1"));
    }

    // ------------------------------------------------------------------
    // 分区解析
    // ------------------------------------------------------------------

    @Test
    public void partition_parse() {
        ScorePartition.Partition p = ScorePartition.parse("#9超赞");
        assertEquals(9, p.score());
        assertEquals("超赞", p.label());
        assertEquals("9 分 超赞", p.display());
    }

    /** 1/3/5/7/9 之外的数字、以及非分区形态都返回 null */
    @Test
    public void partition_rejectsNonPartition() {
        assertNull(ScorePartition.parse("#8还行"));
        assertNull(ScorePartition.parse("普通目录"));
        assertNull(ScorePartition.parse(null));
        // 歌曲根下的 #统计.xlsx 也以 # 开头 —— list() 里只认目录，这里名字本身也判不出分数
        assertNull(ScorePartition.parse("#统计.xlsx"));
    }

    // ------------------------------------------------------------------
    // 歌词解析
    // ------------------------------------------------------------------

    /** LRC：结束时间用下一行的开始时间补，最后一行留 null */
    @Test
    public void lrc_basic() {
        List<LyricLine> lines = LyricParser.parseLrc("""
                [ti:曲名]
                [00:12.00]第一句
                [00:15.50]第二句
                """);
        assertEquals(2, lines.size());
        assertEquals(12.0, lines.get(0).start(), 0.001);
        assertEquals(15.5, lines.get(0).end(), 0.001);
        assertEquals("第一句", lines.get(0).text());
        assertNull(lines.get(1).end());
    }

    /** LRC：一行多个时间戳要展开成多行（副歌重复） */
    @Test
    public void lrc_multipleTimestamps() {
        List<LyricLine> lines = LyricParser.parseLrc("[00:12.00][01:30.00]副歌");
        assertEquals(2, lines.size());
        assertEquals("副歌", lines.get(0).text());
        assertEquals("副歌", lines.get(1).text());
        assertEquals(12.0, lines.get(0).start(), 0.001);
        assertEquals(90.0, lines.get(1).start(), 0.001);
    }

    /** LRC：offset 正值表示歌词该更早出现，所以是减 */
    @Test
    public void lrc_offset() {
        List<LyricLine> lines = LyricParser.parseLrc("""
                [offset:+500]
                [00:12.00]第一句
                """);
        assertEquals(11.5, lines.get(0).start(), 0.001);
    }

    /** LRC：小数部分按位数定标，两位是百分秒而不是毫秒 */
    @Test
    public void lrc_fractionScale() {
        assertEquals(12.5, LyricParser.parseLrc("[00:12.5]x").get(0).start(), 0.001);
        assertEquals(12.5, LyricParser.parseLrc("[00:12.50]x").get(0).start(), 0.001);
        assertEquals(12.5, LyricParser.parseLrc("[00:12.500]x").get(0).start(), 0.001);
    }

    /** LRC：增强型逐字时间戳剥掉，只做行级 */
    @Test
    public void lrc_stripsWordTimestamps() {
        List<LyricLine> lines = LyricParser.parseLrc("[00:12.00]<00:12.00>逐<00:12.50>字");
        assertEquals("逐字", lines.get(0).text());
    }

    /** SRT：自带起止，<b>保留 gap</b> —— 不补 end，否则「这段没字幕」会变成「上一句挂着」 */
    @Test
    public void srt_keepsGap() {
        List<LyricLine> lines = LyricParser.parseSrt("""
                1
                00:00:12,000 --> 00:00:15,000
                第一句

                2
                00:00:20,000 --> 00:00:22,500
                第二句
                """);
        assertEquals(2, lines.size());
        assertEquals(12.0, lines.get(0).start(), 0.001);
        // 结束是 15 而不是下一行的 20：15 到 20 之间没有当前行
        assertEquals(15.0, lines.get(0).end(), 0.001);
        assertEquals(22.5, lines.get(1).end(), 0.001);
    }

    /** SRT：一条字幕两行文本，合成一行 */
    @Test
    public void srt_multiLineText() {
        List<LyricLine> lines = LyricParser.parseSrt("""
                1
                00:00:12,000 --> 00:00:15,000
                上半句
                下半句
                """);
        assertEquals(1, lines.size());
        assertEquals("上半句 下半句", lines.get(0).text());
    }

    /** TXT：没有时间轴，start 全为 null。前端据此降级成纯文本 */
    @Test
    public void txt_noTimeline() {
        List<LyricLine> lines = LyricParser.parseTxt("第一行\n第二行\n\n");
        assertEquals(2, lines.size());
        assertNull(lines.get(0).start());
        assertEquals("第一行", lines.get(0).text());
    }

    /** ass 不解析，返回空列表让调用方报「格式不支持」 */
    @Test
    public void parse_unsupportedExtension() {
        assertTrue(LyricParser.parse("随便什么内容", "ass").isEmpty());
    }

    // ------------------------------------------------------------------
    // 编码嗅探
    // ------------------------------------------------------------------

    @Test
    public void decode_utf8WithBom() {
        byte[] body = "[00:12.00]中文".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);
        // BOM 要去掉：留着会顶在第一行开头，[00:12.00] 就匹配不上了
        assertEquals("[00:12.00]中文", TextDecoder.decode(withBom));
    }

    @Test
    public void decode_utf16le() {
        byte[] body = "中文".getBytes(StandardCharsets.UTF_16LE);
        byte[] withBom = new byte[body.length + 2];
        withBom[0] = (byte) 0xFF;
        withBom[1] = (byte) 0xFE;
        System.arraycopy(body, 0, withBom, 2, body.length);
        assertEquals("中文", TextDecoder.decode(withBom));
    }

    /**
     * 无 BOM 的 GBK 要能认出来。
     * <p>这是整套嗅探存在的理由：实测有 74 个歌词是 GBK 系。宽松 UTF-8 解码会把它们
     * 替换成 {@code �} 并「成功」返回，症状是歌词全乱码却没有任何报错。
     */
    @Test
    public void decode_gbkFallback() {
        Charset gbk = Charset.forName("GBK");
        String text = "第一句歌词";
        assertEquals(text, TextDecoder.decode(text.getBytes(gbk)));
    }

    /** 无 BOM 的 UTF-8 走严格解码这一路，不该被误判成 GBK */
    @Test
    public void decode_utf8NoBom() {
        String text = "第一句歌词";
        assertEquals(text, TextDecoder.decode(text.getBytes(StandardCharsets.UTF_8)));
    }
}
