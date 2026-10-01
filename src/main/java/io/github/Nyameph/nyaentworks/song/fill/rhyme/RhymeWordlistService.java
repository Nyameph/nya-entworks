package io.github.Nyameph.nyaentworks.song.fill.rhyme;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import io.github.Nyameph.nyaentworks.common.pinyin.PinyinSyllable;
import io.github.Nyameph.nyaentworks.common.pinyin.PinyinUtil;
import io.github.Nyameph.nyaentworks.song.entity.RhymeEntry;
import io.github.Nyameph.nyaentworks.song.mapper.RhymeEntryMapper;
import io.github.Nyameph.nyaentworks.task.handler.AsyncTaskContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 开源词表导入：把 classpath 下的 {@value #WORDLIST} 灌进 {@code rhyme_entry}（{@code source=OPEN}）。
 *
 * <p><b>词表从哪来、为什么是它</b>（填词助手设计 §12）：抓 yayunla / wanmeiyunjiao 两个在线韵脚站
 * 是能抓的（服务端渲染直出），但收益不抵 —— 三个词表类每页只直出 20 条、整句与英文类对本项目无用。
 * 改用 {@code mozillazg/phrase-pinyin-data}（MIT）的 {@code 词: 逐字拼音} 与 jieba（MIT）的
 * {@code 词 词频 词性}：前者提供词表与读音，后者提供过滤门槛与词性。
 *
 * <p><b>为什么不用归一韵部</b>：外部词表进来算出的 {@code finals} / {@code rhyme_body} / {@code yun18}
 * 与本库<b>同口径</b>（实测 13.1 万条 100% 拆得出、正好落 18 个韵身），因为两边都由拼音现算 ——
 * 这也是当初判断「抓站最贵的一块是韵部归一」不成立的原因。
 *
 * <p><b>入库走 {@code INSERT IGNORE}</b>：撞 {@code uk_text_pinyin(text, pinyin)} 的行静默跳过，
 * 于是人工行（MANUAL / XLSX）与语料行（CORPUS）的既有 {@code freq} / {@code word_class} 一律不动
 * —— 与 §4.4 第 8 条「人工添加的行永远优先」一致。所以本任务是<b>幂等</b>的，重跑只补新增。
 *
 * <p><b>数据红线（§9.4）</b>：日志与 {@code result_json} 里<b>只出现计数</b>，绝不出现词条原文
 * —— {@link ImportResult} 五个字段全是数字，解析失败也只记行数不记内容。
 */
@Service
@RequiredArgsConstructor
public class RhymeWordlistService {

    /** classpath 下的词表（`#` 开头的行是注释、空行跳过）。 */
    static final String WORDLIST = "rhyme/open-rhyme-words.tsv";

    /** 一次 INSERT 多少行（与 {@link RhymeService} 的 {@code INSERT_BATCH} 同量级）。 */
    static final int BATCH = 500;

    /** 映射表里没有的词性落这里（{@link RhymeService#WORD_CLASSES} 的最后一项）。 */
    static final String OTHER_CLASS = "其他";

    /**
     * jieba（ICTCLAS）词性 → {@link RhymeService#WORD_CLASSES} 的 8 值枚举。
     *
     * <p>只列有把握的对应；表里没有的（含 {@code i} 成语 / {@code l} 惯用语 / {@code j} 简称 /
     * {@code b} 区别词 / {@code f} 方位词）一律落「其他」—— 落个枚举外的值等于这一列又脏回去，
     * 而查询侧的下拉只认枚举。
     *
     * <p>{@code nr}/{@code ns}/{@code nt}/{@code nrt}/{@code nrfg}（人名/地名/机构/音译）
     * <b>不在表里也不必在</b>：那一类在<b>词表生成阶段</b>就被排掉了（用户 2026-09-14 拍板），
     * 所以文件里根本不会出现。
     */
    static final Map<String, String> POS_TO_CLASS = Map.ofEntries(
            Map.entry("n", "名词"), Map.entry("nz", "名词"), Map.entry("vn", "名词"),
            Map.entry("s", "名词"), Map.entry("t", "名词"),
            Map.entry("v", "动词"),
            Map.entry("a", "形容词"), Map.entry("z", "形容词"),
            Map.entry("d", "副词"),
            Map.entry("r", "代词"),
            Map.entry("m", "数量词"), Map.entry("q", "数量词"),
            Map.entry("c", "虚词"), Map.entry("p", "虚词"), Map.entry("u", "虚词"),
            Map.entry("y", "虚词"), Map.entry("e", "虚词"), Map.entry("o", "虚词"));

    private final RhymeEntryMapper mapper;

    /** 任务结果（进 result_json 与任务说明行）：<b>只有计数，没有词</b>。 */
    public record ImportResult(int total, int inserted, int skipped, int failed, int batches) {
    }

    /**
     * 跑一轮导入：读词表 → 逐行解析 → 分批 {@code INSERT IGNORE}。
     *
     * <p>{@code done} 与 {@code total} 都是<b>扫描过的行数</b>；解析失败的行也算扫描过，
     * 所以进度文案不能写成「已写入 N」——{@code inserted} 才是真进库的行数，两个数会差。
     */
    public ImportResult importOpen(AsyncTaskContext context) throws IOException {
        List<String> lines = readDataLines();
        int total = lines.size();
        context.message("待导入 " + total + " 行（词表 " + WORDLIST + "）");

        int done = 0;
        int inserted = 0;
        int skipped = 0;
        int failed = 0;
        int batches = 0;
        List<RhymeEntry> buf = new ArrayList<>(BATCH);
        for (String line : lines) {
            done++;
            RhymeEntry entry = toEntry(line);
            if (entry == null) {
                failed++;
            } else {
                buf.add(entry);
            }
            if (buf.size() >= BATCH) {
                batches++;
                int n = mapper.insertIgnoreBatch(buf);
                inserted += n;
                skipped += buf.size() - n;
                buf.clear();
                context.progress(done, total, progressText(done, total, inserted));
            }
        }
        if (!buf.isEmpty()) {
            batches++;
            int n = mapper.insertIgnoreBatch(buf);
            inserted += n;
            skipped += buf.size() - n;
        }
        if (failed > 0) {
            // 只记行数：词表可能含成人词，词条原文不进日志（§9.4）
            context.log("解析失败 " + failed + " 行已跳过（未写入）");
        }
        context.progress(total, total, progressText(total, total, inserted));
        // skipped 只数「解析成功但撞 uk_text_pinyin」的；失败行已经进了 failed，再算进 skipped
        // 就会出现总数对不上（与 RhymeService#addBatch 同一口径：三个数要能相加）。
        return new ImportResult(total, inserted, skipped, failed, batches);
    }

    private static String progressText(int done, int total, int inserted) {
        return "已扫描 " + done + " / " + total + " 词（写入成功 " + inserted + " 词）";
    }

    /** 读词表：跳过空行与 `#` 注释；返回的行已 strip。 */
    static List<String> readDataLines() throws IOException {
        List<String> out = new ArrayList<>();
        try (InputStream in = new ClassPathResource(WORDLIST).getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String s = line.strip();
                if (s.isEmpty() || s.charAt(0) == '#') {
                    continue;
                }
                out.add(s);
            }
        }
        return out;
    }

    /**
     * 一行 TSV → 词条；<b>返回 null 表示这一行没解析出来</b>（调用方按失败计数，不静默丢）。
     *
     * <pre>
     * 列：词 &lt;TAB&gt; 逐字拼音 &lt;TAB&gt; 词频 &lt;TAB&gt; jieba 词性（缺列 / 词频非数字 → null）
     * 读音：整串拼音先经 {@link #tailSyllable} 取尾音节，再走 {@link RhymeService#lastSyllable}
     *       → 拆不出 → null
     * 词频：直接进 {@code freq}（排序第二键；OPEN 行的 freq 是 jieba 词频，不是语料次数）
     * </pre>
     */
    static RhymeEntry toEntry(String line) {
        String[] parts = StringUtils.defaultString(line).split("\t", -1);
        if (parts.length < 4) {
            return null;
        }
        String word = StringUtils.trimToNull(parts[0]);
        String pinyin = StringUtils.trimToNull(parts[1]);
        if (word == null || pinyin == null) {
            return null;
        }
        int freq;
        try {
            freq = Integer.parseInt(parts[2].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        int last = word.codePointBefore(word.length());
        if (!PinyinUtil.isHanzi(last)) {
            return null;
        }
        PinyinSyllable reading = RhymeService.lastSyllable(tailSyllable(pinyin));
        if (reading == null) {
            return null;
        }
        RhymeEntry e = new RhymeEntry();
        e.setEntryType(word.codePointCount(0, word.length()) == 1 ? "CHAR" : "WORD");
        e.setText(word);
        e.setPinyin(StringUtils.defaultIfBlank(reading.pinyin(), pinyin));
        e.setFinals(reading.finals());
        e.setRhymeBody(reading.rhymeBody());
        e.setYun18(reading.yun18());
        e.setWordClass(posToClass(parts[3]));
        e.setSource(RhymeService.SOURCE_OPEN);
        e.setTier(PinyinUtil.tier(last));
        e.setFreq(freq);
        return e;
    }

    /** jieba 词性 → 8 值枚举；表外（含 null / 空）落「其他」。 */
    static String posToClass(String pos) {
        String key = StringUtils.trimToNull(pos);
        if (key == null) {
            return OTHER_CLASS;
        }
        return POS_TO_CLASS.getOrDefault(key, OTHER_CLASS);
    }

    /**
     * 词表里的逐字拼音（{@code yī xià zi}）→ 最后一个音节（{@code zi}）。
     *
     * <p><b>这一步不能省</b>：{@link RhymeService#lastSyllable} 是按「最长可解析前缀」<b>逐字符</b>
     * 切整串的，切到空格处就 {@code return null} —— 它设计上只吃单音节或连写（{@code suixing}），
     * 不吃空格分隔的整串拼音（{@code /entries/batch} 那边传进来的是「最后一个空白之后」的部分，
     * 所以那个坑一直没露出来）。先取尾音节再交给它，两类输入就都对了。
     *
     * <p>分隔符认半角空格、tab 与全角空格（词表里只有半角空格，多认两个不花什么）。
     */
    static String tailSyllable(String pinyin) {
        String s = StringUtils.trimToNull(pinyin);
        if (s == null) {
            return null;
        }
        int cut = -1;
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '　') {
                cut = i;
                break;
            }
        }
        return cut < 0 ? s : StringUtils.trimToNull(s.substring(cut + 1));
    }
}
