package io.github.Nyameph.nyaentworks.manga;

import io.github.Nyameph.nyaentworks.manga.consts.MangaDictMatchMode;
import io.github.Nyameph.nyaentworks.manga.consts.MangaDictType;
import io.github.Nyameph.nyaentworks.manga.dict.MangaDictionary;
import io.github.Nyameph.nyaentworks.manga.entity.MangaDictEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * 供纯单元测试使用的内存词典快照，不连库。
 * <p>只包含断言涉及的少量词条；需要完整词典时用 {@code @SpringBootTest} 从库加载。
 * <p>匹配方式逐条声明 —— 类型本身不带默认语义。
 */
public final class MangaDictFixture {

    private MangaDictFixture() {
    }

    private static final MangaDictionary INSTANCE = build();

    public static MangaDictionary get() {
        return INSTANCE;
    }

    private static MangaDictionary build() {
        List<MangaDictEntry> entries = new ArrayList<>();
        // 展会（正则）
        regex(entries, MangaDictType.EXHIBIT, "C\\d+");
        regex(entries, MangaDictType.EXHIBIT, "SC\\d+");
        regex(entries, MangaDictType.EXHIBIT, "COMIC1☆\\d+");
        // 原作（全等）
        exact(entries, MangaDictType.PARODY, "Fate");
        exact(entries, MangaDictType.PARODY, "FateGrand Order");
        exact(entries, MangaDictType.PARODY, "Fate kaleid liner プリズマ☆イリヤ");
        exact(entries, MangaDictType.PARODY, "ポケットモンスター ソード・シールド");
        // 修饰标签 —— 汉化组后缀（后缀匹配）
        modifier(entries, MangaDictMatchMode.SUFFIX, "汉化");
        modifier(entries, MangaDictMatchMode.SUFFIX, "汉化组");
        modifier(entries, MangaDictMatchMode.SUFFIX, "嵌字");
        modifier(entries, MangaDictMatchMode.SUFFIX, "翻译");
        modifier(entries, MangaDictMatchMode.SUFFIX, "翻訳");
        modifier(entries, MangaDictMatchMode.SUFFIX, "翻譯");
        modifier(entries, MangaDictMatchMode.SUFFIX, "精修版");
        modifier(entries, MangaDictMatchMode.SUFFIX, "人工校正");
        // 修饰标签 —— 汉化组特征词（包含匹配）
        modifier(entries, MangaDictMatchMode.CONTAINS, "新桥月白");
        modifier(entries, MangaDictMatchMode.CONTAINS, "无毒汉化组");
        modifier(entries, MangaDictMatchMode.CONTAINS, "君日本語本當上手漢化組");
        // 无用标签
        exact(entries, MangaDictType.USELESS_TAG, "同人誌");
        exact(entries, MangaDictType.USELESS_TAG, "DL版");
        // 無修正
        exact(entries, MangaDictType.UNCENSORED, "無修正");
        exact(entries, MangaDictType.UNCENSORED, "Uncensored");
        // 拆括号
        exact(entries, MangaDictType.UNBOXING, "汉化汇总");
        // 杂志整体正则（原 MangaNameParser.MAGAZINE_CONTENT_PATTERN）
        regexIgnoreCase(entries, MangaDictType.MAGAZINE, MAGAZINE_CONTENT_REGEX);
        // 杂志前缀词 —— 模拟真实库里的固定刊名前缀（如 Comic、コミック），用于回归
        // 「社团名以 Comic 开头被误判为杂志」的 bug
        prefix(entries, MangaDictType.MAGAZINE, "Comic");
        // 取展会/原作时应跳过的内容（原 MangaNameParser.IGNORE_PATTERN）
        regexIgnoreCase(entries, MangaDictType.IGNORE_CONTENT, IGNORE_CONTENT_REGEX);
        return MangaDictionary.from(entries, 1L);
    }

    /** 与线上 {@code manga_dict_entry} 里的同名词条保持一致（本 fixture 是它的最小快照） */
    static final String MAGAZINE_CONTENT_REGEX =
            "(?:COMIC *[\\w\\p{IsHiragana}\\p{IsKatakana}\\p{InKatakanaPhoneticExtensions}\\p{IsHan}ー・α!！\\- ×]+|"
                    + "Girls ?for ?M|コミック刺激的 ?SQUIRT[！!]+|コミックリブート|web 漫畫ばんがいち|ヒロインピンチ|サンクリ |コミックめづ ) *"
                    + "(?:[＃#]?\\d+|\\d{2,4}[-.]\\d+|vol\\. ?\\d+)? *"
                    + "(?:みにえるおー \\d時間目|\\w+|[,-]?DL版|別冊付録|特典|[春夏秋冬]|LOE .*)?"
                    + "|.*\\d{2,4}年\\d+月[号號].*?";

    static final String IGNORE_CONTENT_REGEX =
            "[p\\d 一二三四五六七八九十\\-枚目话／～~]+|.*截止\\d+\\.\\d+\\.\\d+.*|part ?\\d+";

    private static void exact(List<MangaDictEntry> list, MangaDictType type, String value) {
        add(list, type, MangaDictMatchMode.EXACT, true, value);
    }

    private static void regex(List<MangaDictEntry> list, MangaDictType type, String value) {
        add(list, type, MangaDictMatchMode.REGEX, false, value);
    }

    private static void regexIgnoreCase(List<MangaDictEntry> list, MangaDictType type, String value) {
        add(list, type, MangaDictMatchMode.REGEX, true, value);
    }

    private static void modifier(List<MangaDictEntry> list, MangaDictMatchMode mode, String value) {
        add(list, MangaDictType.MODIFIER, mode, true, value);
    }

    private static void prefix(List<MangaDictEntry> list, MangaDictType type, String value) {
        add(list, type, MangaDictMatchMode.PREFIX, true, value);
    }

    private static void add(List<MangaDictEntry> list, MangaDictType type, MangaDictMatchMode mode,
                            boolean ignoreCase, String value) {
        MangaDictEntry e = new MangaDictEntry();
        e.setDictType(type);
        e.setDictValue(value);
        e.setMatchMode(mode);
        e.setIgnoreCase(ignoreCase);
        list.add(e);
    }
}
