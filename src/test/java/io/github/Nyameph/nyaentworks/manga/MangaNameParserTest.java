package io.github.Nyameph.nyaentworks.manga;

import org.junit.jupiter.api.Test;
import io.github.Nyameph.nyaentworks.manga.consts.MangaDictType;
import io.github.Nyameph.nyaentworks.manga.entity.MangaData;
import io.github.Nyameph.nyaentworks.manga.util.MangaNameParser;

import java.text.Normalizer;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MangaNameParser} 解析逻辑验证。
 */
public class MangaNameParserTest {

    /** 内存词典快照，纯单元测试不连库 */
    private final MangaNameParser parser = new MangaNameParser(MangaDictFixture.get());

    /** 规则1：完整 (展会) [社团 (作者)] 标题 (来源作品) [汉化组] */
    @Test
    public void rule1_full() {
        MangaData m = parser.parse("(C97) [社团名 (作者名)] 日文标题 (来源作品) [汉化组汉化]");
        assertEquals(1, m.getMatchedRule());
        assertEquals("C97", m.getExhibit());
        assertEquals("社团名", m.getGroupName());
        assertEquals("作者名", m.getArtist());
        assertEquals("日文标题", m.getTitle());
        assertEquals("来源作品", m.getParody());
        assertEquals("[汉化组汉化]", m.getReservedTag());
    }

    /** 规则1：方括号内仅作者 */
    @Test
    public void rule1_artistOnly() {
        MangaData m = parser.parse("[作者名] 标题只有作者 [某某汉化]");
        assertEquals(1, m.getMatchedRule());
        assertNull(m.getGroupName());
        assertEquals("作者名", m.getArtist());
        assertEquals("标题只有作者", m.getTitle());
        assertEquals("[某某汉化]", m.getReservedTag());
    }

    /** 规则2：[yyyy.MM] 时间标签开头 */
    @Test
    public void rule2_yyyyMM() {
        MangaData m = parser.parse("[2024.05] 规则2标题 (来源作品) [某汉化组]");
        assertEquals(2, m.getMatchedRule());
        assertEquals("2024.05", m.getDateTag());
        assertEquals("规则2标题", m.getTitle());
        assertEquals("来源作品", m.getParody() == null ?  m.getExhibit() : m.getParody());
        assertEquals("[某汉化组]", m.getReservedTag());
    }

    /** 规则2：[yy.MM] 短年份 */
    @Test
    public void rule2_yyMM() {
        MangaData m = parser.parse("[24.05] 短年份标题 [嵌字]");
        assertEquals(2, m.getMatchedRule());
        assertEquals("24.05", m.getDateTag());
        assertEquals("短年份标题", m.getTitle());
        assertEquals("[嵌字]", m.getReservedTag());
    }


    /** 规则2：[yyyy] 仅年份 */
    @Test
    public void rule2_yyyy() {
        MangaData m = parser.parse("[2023] 只有年份 (Fate) [新桥月白汉化]");
        assertEquals(2, m.getMatchedRule());
        assertEquals("2023", m.getDateTag());
        assertEquals("只有年份", m.getTitle());
        assertEquals("Fate", m.getParody());
    }

    /** 规则2：[yyyy.MM.dd] 完整日期 */
    @Test
    public void rule2_fullDate() {
        MangaData m = parser.parse("[2024.05.01] 完整日期 (作品)");
        assertEquals(2, m.getMatchedRule());
        assertEquals("2024.05.01", m.getDateTag());
        assertEquals("完整日期", m.getTitle());
        assertEquals("作品", m.getParody() == null ?  m.getExhibit() : m.getParody());
    }

    /** 汉化组标签在最前面时，仍按规则1解析，且标签被提取到末尾 */
    @Test
    public void hanhuaTagAtFront() {
        MangaData m = parser.parse("[汉化组] (C99) [社团 (作者)] 前置汉化标签标题 (原作)");
        assertEquals(1, m.getMatchedRule());
        assertEquals("C99", m.getExhibit());
        assertEquals("社团", m.getGroupName());
        assertEquals("作者", m.getArtist());
        assertEquals("前置汉化标签标题", m.getTitle());
        assertEquals("原作", m.getParody());
        assertEquals("[汉化组]", m.getReservedTag());
    }

    /** 步骤1/2/3 综合：下划线、加号、无用标签、無修正 */
    @Test
    public void preprocessAndUncensored() {
        MangaData m = parser.parse("(同人誌) [作者] 带无用标签_下划线+加号 (Uncensored)");
        assertEquals(1, m.getMatchedRule());
        assertEquals("作者", m.getArtist());
        assertTrue(m.isUncensored());
        assertTrue(m.getNormalizedName().contains("[無修正]"));
        assertFalse(m.getNormalizedName().contains("_"));
        assertFalse(m.getNormalizedName().contains("+"));
    }

    @Test
    public void test() {
        MangaData m = parser.parse("(C96) [乱視と君と。 (santa)] 魔法少女催眠パコパコーズGAME OVER (FateGrand Order、Fatekaleid liner プリズマ☆イリヤ) [无毒汉化组]");
        System.out.println(m);
    }

    @Test
    public void test2() {
        MangaData m = parser.parse("[汉化汇总] 汉化杂图集 2 (ex-hentai 99P 截止2023.04.02) [君日本語本當上手漢化組]", new String[]{"御主人様の玩具箱", "hal、池瀧玩具店"});
        System.out.println(m);
    }



    @Test
    public void test4() {
        List<String> dirs = List.of();
        for (String dir : dirs) {
            dir = dir.substring(dir.lastIndexOf("/") + 1);
            MangaData m = parser.parse(dir, new String[]{"偽MIDI泥の会", "石恵"});
            if(m.getMatchedRule() != 0) {
                System.out.println(m.getMatchedRule() + " " + dir);
                System.out.println("  " + m.getNormalizedName());
                System.out.println("  " + m.toString());
            }
        }
    }

    private static final Pattern MAGAZINE_CONTENT_PATTERN = Pattern.compile(
            "^(?:(?:COMIC *[\\w\\p{IsHiragana}\\p{IsKatakana}\\p{InKatakanaPhoneticExtensions}\\p{IsHan}ー・α!！\\- ×]+|" +
                    "Girls ?for ?M|コミック刺激的 ?SQUIRT[！!]+|コミックリブート|web 漫畫ばんがいち|ヒロインピンチ|サンクリ ) *" +
                    "(?:[＃#]?\\d+|\\d{2,4}[-.]\\d+|vol\\. ?\\d+)? *" +
                    "(?:みにえるおー \\d時間目|\\w+|[,-]?DL版|別冊付録|特典|[春夏秋冬])?|" +
                    ".*\\d{2,4}年\\d+月[号號].*?)$",
            Pattern.CASE_INSENSITIVE
    );

    @Test
    public void testMangaNameParser() {
        String str = "Girls forM 2014年vol.6";
        System.out.println(MAGAZINE_CONTENT_PATTERN.matcher(str).find());
    }

    @Test
    public void testMangaNameParser2() {
        System.out.println("⚠😭♥");
    }

    /**
     * 卷号/页数不能被当成来源作品。
     * <p>对应 {@code IGNORE_CONTENT} 的正则词条；库里少了它时
     * {@code (1~5)}、{@code (3话)} 会被 parseRule1 当成尾部 (原作) 取走，
     * 在 scanAndArchiveMangas 里表现为一堆「新增原作：1~5 / 2 / 3话」。
     */
    @Test
    public void volumeNumberIsNotParody() {
        List<String> dirs = List.of(
                "[科Y総研] 魔女狩り(1~5) [AI翻譯 精修版]",
                "[科Y総研] 魔女狩り 奴隷娼婦編 (2) [中国翻訳] [AI翻譯 人工校正]",
                "[江森うき] 母子＋１(3话) [廉价汉化组]",
                "[科Y総研] 魔女狩り ご奉仕奴隷編 (4) [中国翻訳] [AI翻譯 人工校正]",
                "[科Y総研] 魔女狩り 虐待奴隷編 (5) [中国翻訳] [AI翻譯 人工校正]");
        for (String dir : dirs) {
            MangaData m = parser.parse(dir);
            assertEquals(1, m.getMatchedRule(), dir);
            assertNull(m.getParody(), dir + " 的卷号被当成了原作");
            assertNull(m.getMagazine(), dir);
        }
    }

    /**
     * 「COMIC xxx yyyy年M月号」应判为杂志而非来源作品。
     * <p>对应 {@code MAGAZINE} 的整体正则词条 —— 库里的 PREFIX 词条只覆盖
     * 固定刊名，靠年月号识别的要靠这条正则。
     */
    @Test
    public void magazineIsNotParody() {
        MangaData bavel = parser.parse("[鉢本] 君を売る (COMIC BAVEL 2019年3月号) [不咕鸟汉化组]");
        assertEquals("COMIC BAVEL 2019年3月号", bavel.getMagazine());
        assertNull(bavel.getParody());
        assertEquals("君を売る", bavel.getTitle());

        MangaData lo = parser.parse("[堀出井靖水] 綴ちゃんあふたー (COMIC LO 2017年11月号) [中国翻訳]");
        assertEquals("COMIC LO 2017年11月号", lo.getMagazine());
        assertNull(lo.getParody());
    }

    /**
     * 社团名以「Comic」开头时不能被误判为杂志（MAGAZINE 有 Comic 前缀词）。
     * <p>对应 bug：{@code [Comic Kingdom (作者)]} 的 "Comic" 前缀命中 MAGAZINE 词条，
     * 社团块被提前置 used，导致 magazine 误填、规则1 找不到头部方括号、group/artist 丢失。
     */
    @Test
    public void groupNameWithComicPrefixIsNotMagazine() {
        MangaData m = parser.parse("(C63) [Comic Kingdom (Koyama Unkaku, Oyama Yasunaga, Tecchan)] "
                + "Orange Road Sex (Kimagure Orange Road)");
        assertEquals(1, m.getMatchedRule());
        assertEquals("Comic Kingdom", m.getGroupName());
        assertEquals("Koyama Unkaku, Oyama Yasunaga, Tecchan", m.getArtist());
        assertEquals("Orange Road Sex", m.getTitle());
        assertEquals("Kimagure Orange Road", m.getParody());
        assertNull(m.getMagazine());
    }

    /** 词典里的原作应命中并标记 parodyInDict，不再报成「新增原作」 */
    @Test
    public void knownParodyIsMarkedInDict() {
        MangaData m = parser.parse("(C107) [えすぶいのサークル (えすぶい)] ユウリ×ポケモン "
                + "(ポケットモンスター ソード・シールド) [固拉多还有三小时降临地球个人汉化]");
        assertEquals(1, m.getMatchedRule());
        assertEquals("C107", m.getExhibit());
        assertEquals("ポケットモンスター ソード・シールド", m.getParody());
        assertTrue(m.isParodyInDict(), "词典里已有该原作，不应报成新增");
    }

    /**
     * 词条以 Unicode NFD 存储时也要能匹配 NFC 的文件夹名。
     * <p>{@code ポ} 有预组合（U+30DD）与分解（U+30DB U+309A）两种写法，
     * MySQL 的 {@code utf8mb4_0900_ai_ci} 视作相等，Java 的 equals 不等 ——
     * 库里那条 {@code ソード・シールド} 正是 NFD，导致既匹配不上、
     * 又因唯一键冲突补录不进去。
     */
    @Test
    public void nfdDictEntryMatchesNfcFolderName() {
        String nfd = Normalizer.normalize("ポケットモンスター ソード・シールド", Normalizer.Form.NFD);
        assertNotEquals(nfd, "ポケットモンスター ソード・シールド", "构造的 NFD 串应与 NFC 不同");
        assertTrue(MangaDictFixture.get().matches(MangaDictType.PARODY, nfd),
                "NFD 写法应能命中 NFC 词条");
    }

    // ------------------------------------------------------------------
    // 归档目录名解析（MangaArchiveService#sync 的输入）
    // ------------------------------------------------------------------

    /** 完整形态：社团 + 多作者 + 多标签 */
    @Test
    public void archiveFolder_full() {
        var info = MangaNameParser.parseArchiveFolderName("[社团名 (作者甲、作者乙)]【标签1 标签2】");
        assertEquals("社团名", info.groupName());
        assertEquals("作者甲、作者乙", info.artistNames());
        assertEquals(List.of("标签1", "标签2"), info.tags());
    }

    /** 仅作者，无社团无标签 */
    @Test
    public void archiveFolder_artistOnly() {
        var info = MangaNameParser.parseArchiveFolderName("[作者甲]");
        assertNull(info.groupName());
        assertEquals("作者甲", info.artistNames());
        assertTrue(info.tags().isEmpty());
    }

    /** 社团 + 作者，无标签块 */
    @Test
    public void archiveFolder_noTags() {
        var info = MangaNameParser.parseArchiveFolderName("[社团名 (作者甲)]");
        assertEquals("社团名", info.groupName());
        assertEquals("作者甲", info.artistNames());
        assertTrue(info.tags().isEmpty());
    }

    /** 标签块内多个空格应被折叠，不产出空标签 */
    @Test
    public void archiveFolder_tagsWithExtraSpaces() {
        var info = MangaNameParser.parseArchiveFolderName("[作者甲]【  标签1   标签2  】");
        assertEquals(List.of("标签1", "标签2"), info.tags());
    }

    /** 不是归档目录形态时返回 null，交由调用方计入「不规范目录」 */
    @Test
    public void archiveFolder_notArchiveShape() {
        // 普通漫画文件夹名：头部是小括号展会
        assertNull(MangaNameParser.parseArchiveFolderName("(C97) [社团名 (作者名)] 标题"));
        // 纯文本
        assertNull(MangaNameParser.parseArchiveFolderName("#待压缩"));
        assertNull(MangaNameParser.parseArchiveFolderName(""));
        assertNull(MangaNameParser.parseArchiveFolderName(null));
    }

    // ------------------------------------------------------------------
    // 归档目录名的标签改写（标签重命名/合并/删除会照着它改磁盘目录）
    // ------------------------------------------------------------------

    /** 改名：只动被点名的那个标签，社团块与其余标签原样 */
    @Test
    public void replaceArchiveTag_rename() {
        assertEquals("[社团名 (作者甲)]【标签1 新标签】",
                MangaNameParser.replaceArchiveTag("[社团名 (作者甲)]【标签1 标签2】", "标签2", "新标签"));
    }

    /** 删除：去掉标签块里的一项，其余保留 */
    @Test
    public void replaceArchiveTag_removeOne() {
        assertEquals("[作者甲]【标签2】",
                MangaNameParser.replaceArchiveTag("[作者甲]【标签1 标签2】", "标签1", null));
    }

    /** 删掉最后一个标签时整个 【】 块消失，且不留结尾空格（Windows 目录名不允许） */
    @Test
    public void replaceArchiveTag_removeLast() {
        assertEquals("[作者甲]",
                MangaNameParser.replaceArchiveTag("[作者甲] 【标签1】", "标签1", null));
    }

    /** 合并到目录里已有的标签时会撞车，去重而不是留下两个一样的 */
    @Test
    public void replaceArchiveTag_mergeIntoExisting() {
        assertEquals("[作者甲]【标签1】",
                MangaNameParser.replaceArchiveTag("[作者甲]【标签1 标签2】", "标签2", "标签1"));
    }

    /** 比对走 normalizeNameKey，大小写与分解形式的假名都应命中 */
    @Test
    public void replaceArchiveTag_matchIgnoreCase() {
        assertEquals("[作者甲]【NEW】",
                MangaNameParser.replaceArchiveTag("[作者甲]【ero】", "ERO", "NEW"));
    }

    /** 目录里没有这个标签时原样返回，不至于把别的标签改掉 */
    @Test
    public void replaceArchiveTag_notPresent() {
        assertEquals("[作者甲]【标签1】",
                MangaNameParser.replaceArchiveTag("[作者甲]【标签1】", "别的标签", "新名"));
    }

    /** 社团块里的空格与括号形态按原文照抄，不重新拼装 */
    @Test
    public void replaceArchiveTag_keepsGroupBlockVerbatim() {
        String from = "[社团名  (作者甲、作者乙)] 【标签1】";
        assertEquals("[社团名  (作者甲、作者乙)] 【标签2】",
                MangaNameParser.replaceArchiveTag(from, "标签1", "标签2"));
    }

    /** 不是归档目录形态时返回 null，调用方据此把这条标成「改不了」 */
    @Test
    public void replaceArchiveTag_notArchiveShape() {
        assertNull(MangaNameParser.replaceArchiveTag("#待压缩", "标签1", "标签2"));
    }

    // ------------------------------------------------------------------
    // 按字段拼归档目录名（归档编辑表单提交时用）
    // ------------------------------------------------------------------

    /** 社团 + 多作者 + 多标签，拼出来要能被 parseArchiveFolderName 原样解回去 */
    @Test
    public void buildArchiveFolderName_roundTrip() {
        String name = MangaNameParser.buildArchiveFolderName(
                "社团名", "作者甲、作者乙", List.of("标签1", "标签2"));
        assertEquals("[社团名 (作者甲、作者乙)]【标签1 标签2】", name);
        var info = MangaNameParser.parseArchiveFolderName(name);
        assertEquals("社团名", info.groupName());
        assertEquals("作者甲、作者乙", info.artistNames());
        assertEquals(List.of("标签1", "标签2"), info.tags());
    }

    /**
     * 多社团：社团位是 {@code (} 之前的自由文本，按 {@code 、} 连写即可，不限一个。
     * 归档侧的 {@code nameKeysOf} / {@code insertNames} 也按 {@code 、} 拆，两边对得上。
     */
    @Test
    public void buildArchiveFolderName_multipleGroups() {
        String name = MangaNameParser.buildArchiveFolderName(
                "社团甲、社团乙", "作者甲、作者乙", List.of("标签1"));
        assertEquals("[社团甲、社团乙 (作者甲、作者乙)]【标签1】", name);
        var info = MangaNameParser.parseArchiveFolderName(name);
        assertEquals("社团甲、社团乙", info.groupName());
        assertEquals("作者甲、作者乙", info.artistNames());
        assertEquals(List.of("标签1"), info.tags());
    }

    /** 无社团时是 [作者] 形态，不是 [ (作者)] */
    @Test
    public void buildArchiveFolderName_artistOnly() {
        assertEquals("[作者甲]【标签1】",
                MangaNameParser.buildArchiveFolderName(null, "作者甲", List.of("标签1")));
    }

    /** 无标签时不带 【】 块，且不留结尾空格 */
    @Test
    public void buildArchiveFolderName_noTags() {
        assertEquals("[社团名 (作者甲)]",
                MangaNameParser.buildArchiveFolderName("社团名", "作者甲", List.of()));
    }

    /** 作者为空拼不出合法目录名，返回 null 让调用方拒绝提交 */
    @Test
    public void buildArchiveFolderName_noArtistIsNull() {
        assertNull(MangaNameParser.buildArchiveFolderName("社团名", null, List.of("标签1")));
        assertNull(MangaNameParser.buildArchiveFolderName(null, "  ", List.of()));
    }

    @Test
    public void testMangaNameParser3() {
        List<String> dirs = List.of(
                "零 ～月蝕の仮面～", "電波女と青春男", "青の6号", "青春ブタ野郎はバニーガール先輩の夢を見ない", "静凛", "革命機ヴァルヴレイヴ", "響け!ユーフォニアム",
                "食戟のソーマ", "食戟のソーマ,ニセコイ,To LOVEる -とらぶる-", "餓狼伝説", "餓狼傳說", "鬼滅の刃", "魔女の宅急便", "魔女の旅々",
                "魔法つかいプリキュア!", "魔法使いの夜", "魔法先生ネギま!", "魔法少女にあこがれて", "魔法少女まどか☆マギカ", "魔法少女まどかマギカ", "魔法少女リリカルなのは",
                "魔法少女リリカルなのは 聖剣の刀鍛冶", "魔法科高校の劣等生", "魔法騎士レイアース", "魔界天使ジブリール", "魔界戦記ディスガイア", "麗しの"
                );
        List<String> result = dirs.stream().distinct().sorted().toList();
        int maxLength = 80;
        StringBuilder sb = new StringBuilder();
        for (String s : result) {
            sb.append("\"").append(s).append("\", ");
            if (sb.length() > maxLength) {
                System.out.println(sb);
                sb =  new StringBuilder();
            }
        }
    }





}
