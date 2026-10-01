package io.github.Nyameph.nyaentworks.manga;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import io.github.Nyameph.nyaentworks.manga.consts.MangaDictType;
import io.github.Nyameph.nyaentworks.manga.dict.MangaDictionary;
import io.github.Nyameph.nyaentworks.manga.entity.MangaDictEntry;
import io.github.Nyameph.nyaentworks.manga.service.MangaDictService;

import javax.sql.DataSource;
import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 词典自检，只读不写。需要 MySQL 实例。
 * <p>扫描结果里出现「本该识别却报成新增展会/新增原作」时先跑这几个方法，
 * 能把「解析代码的问题」和「{@code manga_dict_entry} 表的数据问题」分开：
 * <ol>
 *     <li>{@link #dumpDictSummary} —— 某个类型是否整批缺失、或缺某种匹配方式。
 *         例如 MAGAZINE 只有 PREFIX 没有 REGEX，靠年月号识别的杂志就会全部漏判。</li>
 *     <li>{@link #probeSamples} —— 把漏判的字符串直接丢给词典，看它到底命中哪种类型。</li>
 *     <li>{@link #countNonNfcEntries} —— 揪出 Unicode 分解形式的词条，这类行
 *         MySQL 判等而 Java 判不等，症状是「明明在库里却匹配不上、又补录不进去」。</li>
 *     <li>{@link #dumpTableDdl} —— 长正则存不进去时看列宽。</li>
 * </ol>
 */
@SpringBootTest
public class MangaDictDiagnoseTest {

    @Autowired
    private MangaDictService mangaDictService;

    @Autowired
    private DataSource dataSource;

    /** 每种类型的词条数量与匹配方式分布；某类型为空或缺 REGEX 时格外可疑 */
    @Test
    public void dumpDictSummary() {
        MangaDictionary dict = mangaDictService.reload();
        System.out.println("词典版本 " + dict.getVersion());
        if (!dict.getInvalidRegexValues().isEmpty()) {
            System.out.println("⚠ 无法编译的正则：" + dict.getInvalidRegexValues());
        }
        for (MangaDictType type : MangaDictType.values()) {
            List<MangaDictEntry> list = mangaDictService.list(type, null);
            Map<String, Long> byMode = list.stream().collect(Collectors.groupingBy(
                    e -> e.effectiveMatchMode().name(), Collectors.counting()));
            System.out.println((list.isEmpty() ? "😭 " : "   ") + type
                    + " 共 " + list.size() + " 条 " + byMode);
        }
    }

    /** 把可疑字符串丢给词典，看它命中哪些类型；改这里的 samples 复现具体问题 */
    @Test
    public void probeSamples() {
        MangaDictionary dict = mangaDictService.reload();
        List<String> samples = List.of(
                "1~5", "2", "3话",
                "COMIC BAVEL 2019年3月号", "COMIC LO 2017年11月号",
                "ポケットモンスター ソード・シールド");
        for (String sample : samples) {
            String hit = java.util.Arrays.stream(MangaDictType.values())
                    .filter(type -> dict.matchesAnySegment(type, sample))
                    .map(MangaDictType::name)
                    .collect(Collectors.joining(", "));
            System.out.println((hit.isEmpty() ? "😭 未命中任何类型  " : "   命中 " + hit + "  ") + sample);
        }
    }

    /**
     * 库里非 NFC（含组合字符）的词条。
     * <p>{@code utf8mb4_0900_ai_ci} 认为 {@code ホ}+{@code ゚} 与 {@code ポ} 相等，
     * Java 的 {@code String.equals} 认为不等 —— 这类行会匹配不上，且因唯一键
     * 冲突连预组合形式都补录不进去。{@link MangaDictionary} 已在构建快照时统一
     * 归一，所以这里报出来的行不影响解析，仅供了解数据现状。
     */
    @Test
    public void countNonNfcEntries() {
        int total = 0;
        for (MangaDictType type : MangaDictType.values()) {
            List<MangaDictEntry> bad = mangaDictService.list(type, null).stream()
                    .filter(e -> !Normalizer.isNormalized(e.getDictValue(), Normalizer.Form.NFC))
                    .toList();
            if (!bad.isEmpty()) {
                total += bad.size();
                System.out.println(type + " 有 " + bad.size() + " 条非 NFC：");
                bad.forEach(e -> System.out.println("   id=" + e.getId() + "  ["
                        + Normalizer.normalize(e.getDictValue(), Normalizer.Form.NFC) + "]"));
            }
        }
        System.out.println("非 NFC 词条合计 " + total + " 条");
    }

    /** 建表语句与现有最长词条；长正则报 Data too long 时看这个 */
    @Test
    public void dumpTableDdl() throws Exception {
        try (var conn = dataSource.getConnection(); var st = conn.createStatement()) {
            try (var rs = st.executeQuery("SHOW CREATE TABLE manga_dict_entry")) {
                while (rs.next()) {
                    System.out.println(rs.getString(2));
                }
            }
            try (var rs = st.executeQuery(
                    "SELECT MAX(CHAR_LENGTH(dict_value)) FROM manga_dict_entry")) {
                while (rs.next()) {
                    System.out.println("现有最长词条 " + rs.getInt(1) + " 字符");
                }
            }
        }
    }
}
