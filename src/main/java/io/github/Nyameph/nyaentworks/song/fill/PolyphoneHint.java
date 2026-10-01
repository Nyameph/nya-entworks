package io.github.Nyameph.nyaentworks.song.fill;

import io.github.Nyameph.nyaentworks.common.pinyin.PinyinUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 多音字提醒（填词工具增强）。
 *
 * <p>往音符槽位填的汉字若是<b>音节级多音字</b>（有 ≥2 个<b>不同无声调音节</b>的读音，
 * 如「重」读 {@code zhong}/{@code chong}），唱出来可能读错声母/韵母，就标出来并建议换成
 * 「同音且只有这一个读音」的常用字。纯声调差异（如「好」hǎo/hào、「中」zhōng/zhòng）不算
 * ——音符本身无调、工具无从判调，且它们不会读成另一个音节。
 *
 * <p><b>分两级提醒</b>（口径是「这个字有几种读法在现代汉语／歌词里真的会用到」，
 * 见 {@link PinyinUtil#commonSyllables}）：
 * <ul>
 *   <li><b>醒目</b>（{@link Index#readings}）—— 常用读音 ≥2 个，如「重」zhòng/chóng、「行」
 *       xíng/háng。真会读错，标橙色虚线并常显建议换字。</li>
 *   <li><b>弱</b>（{@link Index#rare}）—— 只剩 1 个常用读音，别的读音是古音或冷门现代音，
 *       如「他」tā（另有古音 tuó）、「单」dān（另有 chán/shàn）。**几乎不会读错**，标一道
 *       更暗的虚线就够了，不给建议换字。</li>
 * </ul>
 * 两级的<b>触发范围都是合并字典</b>（{@link PinyinUtil#allSyllables}，含古音/异体音）——
 * 只认现代字典的话，「他」这类字根本进不来，也就谈不上「降级成弱提醒」。
 *
 * <p>两种模板共用一份<b>全局索引</b> {@link Index}，随 {@code /resolve} 下发一次，前端每键
 * 做 map 成员判断（软提示，不拦保存）：拼音模板的音符拼音指明目标音节，建议按该音节给；
 * 汉字模板（音符原词是汉字，如《九九八十一柔情版》）没有这层锚，建议按填入字自己的各读音
 * 分组给。
 *
 * <p><b>建议换字字典是写死的</b>（{@link #SUGGEST}）：音节 → 几个「只有这一个读音」的常用字。
 * 多音字集合则从 {@link PinyinUtil} 现建，不做手工罗列，保证任意多音字都提示得到；只是建议字
 * 只覆盖字典里写到的常用音节，没写到的音节提示「是多音字」但给不出建议。索引懒加载后常驻内存，
 * 不落库、不依赖外挂盘。
 */
public final class PolyphoneHint {

    /**
     * 随 {@code /resolve} 下发的全局索引。
     *
     * @param readings <b>醒目</b>多音字 → 常用无声调音节列表（≥2 个才收录，如「重」→ [chong, zhong]）
     * @param rare     <b>弱</b>多音字 → 它的**生僻**无声调音节列表（长度 ≥1，如「他」→ [tuo]）。
     *                 键存在本身就表示这个字是弱多音字（它另有且仅有 1 个常用读音）
     * @param suggest  音节 → 只有这一个读音的常用字（写死字典原样）
     */
    public record Index(Map<String, List<String>> readings,
                        Map<String, List<String>> rare,
                        Map<String, List<String>> suggest) {
    }

    /** 音节（无声调、ü→v）→ 只有这一个读音的常用字（写死的建议字典）。包级可见供单测校验。 */
    static final Map<String, List<String>> SUGGEST = Map.ofEntries(
            entry("xing", "星", "形", "型", "醒", "姓", "性"),
            entry("hang", "航", "杭"),
            entry("zhong", "众", "钟", "终", "忠", "仲", "衷"),
            entry("chong", "虫", "崇", "宠", "充", "铳"),
            entry("yue", "月", "越", "阅", "岳", "悦", "跃"),
            entry("chang", "常", "肠", "尝", "昌", "唱"),
            entry("zhang", "张", "章", "掌", "丈", "账", "帐"),
            entry("hai", "孩", "海", "害", "亥"),
            entry("huan", "环", "欢", "换", "幻", "缓"),
            entry("chuan", "川", "船", "穿", "喘", "串"),
            entry("zhuan", "专", "砖", "撰", "篆"),
            entry("he", "河", "何", "盒", "鹤", "贺", "禾"),
            entry("huo", "火", "活", "伙", "或", "货"),
            entry("han", "寒", "喊", "汉", "含", "函"),
            entry("de", "德"),
            entry("di", "敌", "低", "底", "弟", "帝", "第", "滴", "笛"),
            entry("dou", "豆", "逗", "兜", "抖", "陡", "窦"),
            entry("du", "独", "毒", "杜", "堵", "妒", "督"),
            entry("liao", "料", "辽", "疗", "聊", "廖"),
            entry("hui", "回", "灰", "慧", "惠", "挥", "汇"),
            entry("kuai", "快", "块", "筷"),
            entry("jue", "决", "绝", "掘", "爵", "诀"),
            entry("jiao", "交", "叫", "娇", "焦", "胶", "郊", "较"),
            entry("shu", "书", "树", "鼠", "输", "叔", "述", "束", "蔬"),
            entry("shuo", "硕", "烁", "朔"),
            entry("diao", "掉", "吊", "雕", "钓", "刁"),
            entry("tiao", "条", "跳", "迢", "眺"),
            entry("zhe", "者", "哲", "遮", "浙", "蔗"),
            entry("zhao", "招", "找", "照", "赵", "罩", "昭"),
            entry("zhuo", "捉", "桌", "卓", "浊", "啄"),
            entry("cang", "苍", "舱", "仓"),
            entry("zang", "葬", "赃", "臧"),
            entry("bian", "边", "变", "编", "遍", "辩", "鞭"),
            entry("pian", "篇", "偏", "骗", "翩"),
            entry("cha", "茶", "插", "察", "岔"),
            entry("chai", "拆", "柴", "豺"),
            entry("ci", "词", "此", "次", "瓷", "刺", "赐"),
            entry("dan", "但", "蛋", "淡", "旦", "胆", "丹"),
            entry("tan", "谈", "叹", "炭", "探", "滩", "贪"),
            entry("cheng", "成", "城", "程", "承", "秤", "呈"),
            entry("chen", "陈", "晨", "沉", "衬", "趁", "辰"),
            entry("sheng", "生", "声", "升", "圣", "绳", "剩"),
            entry("si", "四", "死", "思", "私", "丝", "寺", "撕"),
            entry("shi", "十", "是", "时", "事", "世", "市", "诗", "师"),
            entry("she", "蛇", "设", "社", "射", "摄", "舌"),
            entry("luo", "罗", "洛", "骆", "螺", "萝", "裸"),
            entry("lao", "老", "劳", "捞", "牢", "涝"),
            entry("la", "辣", "蜡", "腊", "垃"),
            entry("nong", "农", "浓", "脓"),
            entry("long", "龙", "聋", "垄", "拢"),
            entry("qia", "恰", "洽", "掐"),
            entry("mei", "美", "每", "妹", "梅", "眉", "煤", "霉"),
            entry("mo", "摸", "墨", "末", "莫", "魔", "膜", "默"),
            entry("mu", "木", "目", "母", "牧", "墓", "幕", "慕"),
            entry("na", "拿", "纳"),
            entry("nai", "奶", "耐", "乃"),
            entry("se", "涩", "瑟", "啬"),
            entry("shai", "晒", "筛"),
            entry("shou", "手", "受", "收", "首", "兽", "瘦"),
            entry("zhu", "主", "住", "祝", "朱", "珠", "猪", "助"),
            entry("shui", "水", "睡", "税"),
            entry("su", "素", "苏", "速", "诉", "俗", "肃"),
            entry("xiu", "修", "秀", "休", "绣", "羞", "袖"),
            entry("tuo", "拖", "脱", "驼", "妥", "椭"),
            entry("ta", "他", "她", "它", "塔", "塌"),
            entry("xue", "学", "雪", "靴", "薛"),
            entry("xie", "写", "谢", "些", "斜", "鞋", "协", "卸"),
            entry("xiao", "小", "笑", "消", "晓", "萧", "效"),
            entry("chu", "出", "初", "除", "楚", "触", "础"),
            entry("xu", "需", "许", "续", "虚", "徐", "序", "绪"),
            entry("yan", "言", "眼", "演", "盐", "烟", "严", "厌"),
            entry("ye", "夜", "野", "爷", "业", "也", "液"),
            entry("yin", "音", "因", "银", "印", "引", "隐"),
            entry("zha", "眨", "渣", "闸", "榨", "诈"),
            entry("za", "砸", "杂"),
            entry("chao", "超", "潮", "炒", "抄", "钞"),
            entry("shan", "山", "闪", "善", "衫", "陕"),
            entry("chan", "产", "缠", "馋", "铲", "阐", "忏"),
            entry("ceng", "层", "蹭"),
            entry("zeng", "增", "赠", "憎"),
            entry("jiang", "江", "讲", "奖", "匠", "姜", "酱"),
            entry("xiang", "想", "向", "香", "象", "像", "箱"),
            entry("qiang", "枪", "墙", "腔", "蔷"),
            entry("lu", "路", "炉", "鲁", "录", "鹿"),
            entry("lou", "楼", "漏", "娄", "陋"),
            entry("bo", "波", "博", "播", "脖", "驳", "搏"),
            entry("bao", "包", "保", "报", "抱", "宝", "饱", "胞"),
            entry("zhan", "战", "站", "展", "沾", "崭", "毡"),
            entry("deng", "灯", "等", "登", "凳", "邓", "瞪"),
            entry("chou", "抽", "愁", "丑", "酬", "筹"),
            entry("qiu", "秋", "求", "球", "丘", "囚", "邱"),
            entry("duo", "多", "夺", "朵", "躲", "惰", "堕"),
            entry("e", "俄", "额", "鹅", "饿", "愕", "鄂"),
            entry("wu", "五", "物", "屋", "武", "舞", "务", "雾"),
            entry("fan", "反", "饭", "凡", "犯", "范", "翻"),
            entry("pan", "盘", "盼", "判", "攀", "畔", "叛"),
            entry("po", "破", "坡", "婆", "泼", "颇"),
            entry("pi", "皮", "批", "披", "脾", "疲", "匹", "屁"),
            entry("fu", "福", "父", "府", "付", "浮", "副"),
            entry("pu", "普", "扑", "谱", "浦", "蒲"),
            entry("ge", "歌", "哥", "格", "隔", "鸽", "割"),
            entry("ji", "记", "机", "及", "集", "级", "急", "鸡", "纪"),
            entry("gui", "归", "贵", "鬼", "桂", "规", "轨"),
            entry("jun", "军", "君", "俊", "均", "郡", "骏"),
            entry("hu", "湖", "虎", "互", "呼", "户", "护", "忽"),
            entry("hong", "洪", "宏", "轰", "烘", "鸿"),
            entry("gong", "公", "工", "功", "共", "贡", "攻", "宫"),
            entry("jian", "件", "建", "尖", "简", "减", "剑", "肩"),
            entry("xian", "现", "先", "线", "显", "险", "县", "仙"),
            entry("jia", "家", "加", "甲", "佳", "架", "嫁"),
            entry("jie", "街", "姐", "界", "接", "借", "节", "介"),
            entry("jin", "进", "近", "金", "今", "紧", "斤"),
            entry("jing", "经", "京", "精", "景", "静", "敬", "镜")
    );

    /** 醒目多音字 → 常用无声调音节列表（≥2 个）。懒加载，线程安全 double-checked。 */
    private static volatile Map<String, List<String>> READINGS;

    /** 弱多音字 → 生僻无声调音节列表（另有且仅有 1 个常用读音）。与 READINGS 同时初始化。 */
    private static volatile Map<String, List<String>> RARE;

    /** 音节 → 多音字（{@link #READINGS} 的倒排，**只含醒目那批**）。与 READINGS 同时初始化。 */
    private static volatile Map<String, List<String>> POLYPHONES;

    private PolyphoneHint() {
    }

    /** 全局索引（懒加载一次，之后常驻）。每份模板共用同一份，不必按音符预计算。 */
    public static Index index() {
        build();
        return new Index(READINGS, RARE, SUGGEST);
    }

    /** 该音节下的多音字（可能为空）。**只含醒目那批** —— 弱多音字的生僻读音不进倒排。 */
    public static List<String> polyphonesOf(String toneLessSyllable) {
        build();
        List<String> list = POLYPHONES.get(toneLessSyllable);
        return list == null ? List.of() : list;
    }

    /** 该音节的建议换字（写死字典里没有就返回空）。包级可见供单测校验。 */
    static List<String> suggestOf(String toneLessSyllable) {
        List<String> list = SUGGEST.get(toneLessSyllable);
        return list == null ? List.of() : list;
    }

    private static void build() {
        if (READINGS == null || RARE == null || POLYPHONES == null) {
            synchronized (PolyphoneHint.class) {
                if (READINGS == null || RARE == null || POLYPHONES == null) {
                    buildIndexes();
                }
            }
        }
    }

    /**
     * 一次建三份索引。遍历**合并字典**（含古音/异体音），每个字分两堆：
     * <ul>
     *   <li>常用读音 ≥2 个 → 醒目：{@code readings} 收它（如「重」→ [chong, zhong]），
     *       同时挂进倒排 {@code polyphones}（「重」在 chong 与 zhong 下各一份）。</li>
     *   <li>常用读音恰好 1 个、而总音节 ≥2 个 → 弱：{@code rare} 收它的**生僻音节**，
     *       值非空即代表「这是弱多音字」（如「他」→ [tuo]）。</li>
     * </ul>
     * 常用读音 0 个（全被降级）或总音节 1 个的字两边都不收 —— 前者没有可锚的读音、
     * 后者压根不是多音字（纯声调差异如「好」hǎo/hào 也在这条上被挡掉）。
     *
     * <p>注意 {@link PinyinUtil#forEach} 扫的是**合并字典**（4 万多字），其中大部分并不在
     * 《通用规范汉字表》的 8105 字里、压根没有现代读音，落到「常用读音 0 个」那支被跳过。
     * 这是对的 —— 那些字（多为 CJK 扩展区生僻字）不会出现在歌词里，也不该被提醒。
     * 所以「常用读音 ≥1」这条不变量只对**现代字典里有的字**成立（由单测按这个范围守着）。
     */
    private static void buildIndexes() {
        Map<String, List<String>> byChar = new HashMap<>();
        Map<String, List<String>> byRare = new HashMap<>();
        Map<String, List<String>> bySyllable = new HashMap<>();
        PinyinUtil.forEach((codePoint, rawReadings) ->
                classify(codePoint, byChar, byRare, bySyllable));
        // 补录读音（如「骑」的 jì）不在合并字典里，forEach 扫不到，再走一遍补录表
        PinyinUtil.forEachSupplement((codePoint, rawReadings) ->
                classify(codePoint, byChar, byRare, bySyllable));
        READINGS = byChar;
        RARE = byRare;
        POLYPHONES = bySyllable;
    }

    /** 把一个字分到醒目 / 弱 / 不管三堆里的某一堆（判据见 {@link #buildIndexes}）。 */
    private static void classify(int codePoint,
                                 Map<String, List<String>> byChar,
                                 Map<String, List<String>> byRare,
                                 Map<String, List<String>> bySyllable) {
        if (PinyinUtil.allSyllables(codePoint).size() < 2) {
            return;
        }
        List<String> common = PinyinUtil.commonSyllables(codePoint);
        String ch = new String(Character.toChars(codePoint));
        if (common.size() < 2) {
            if (common.size() == 1) {
                byRare.put(ch, PinyinUtil.rareSyllables(codePoint));
            }
            return;
        }
        byChar.put(ch, common);
        for (String s : common) {
            bySyllable.computeIfAbsent(s, k -> new ArrayList<>()).add(ch);
        }
    }

    /** 写死字典里的一条：{@code Map.entry("xing", List.of("星", "形", …))} 的简写。 */
    private static Map.Entry<String, List<String>> entry(String key, String... chars) {
        return Map.entry(key, List.of(chars));
    }
}
