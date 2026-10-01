package io.github.Nyameph.nyaentworks.song.fill.rhyme;

import io.github.Nyameph.nyaentworks.common.pinyin.PinyinSyllable;
import io.github.Nyameph.nyaentworks.common.pinyin.PinyinUtil;
import io.github.Nyameph.nyaentworks.song.entity.RhymeEntry;
import org.apache.commons.lang3.StringUtils;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 押韵词表（TSV）解析：解码 + 切格 + 行头归一 + 每词一行词条（单字 CHAR / 多字 WORD，
 * 填词助手设计 §4.4）。
 *
 * <p><b>实测结构（按这张表写，别猜）</b>：GB18030 编码（Excel「另存为 → 文本」产物）；
 * 制表符分隔；<b>转置布局</b>——第 1 列是韵母（行头）、第 1 行是词性（列头）、第 1 行第 1 格空串；
 * 列头 {@code 名词-人 / 名词-物 / 动作 / 形容词}（是「动作」不是「动词」，原样存不做映射）；
 * 行头 26 个，写法有缩写与 v 系（{@code v / ve / van / vn / ui / iu / un}），且部分带前后空格；
 * 单元格内以 {@code 、} 分隔。每行列数不等是正常的（尾部空列被省略），按位置对齐、缺列当空。
 *
 * <p><b>本类是纯函数</b>（不碰库），便于单测；入库（INSERT IGNORE、计数与失败明细回报）
 * 在 {@link RhymeService}。
 */
public final class RhymeTsv {

    private RhymeTsv() {
    }

    /** 一个被丢弃的词：行号（0 起，含表头行）/ 列号 / 原词 / 原因。 */
    public record Failed(int row, int col, String text, String reason) {
    }

    /** 解析结果：词条（source=XLSX，tier=3）+ 丢弃明细。 */
    public record Parsed(List<RhymeEntry> entries, int total, List<Failed> failed) {
    }

    /**
     * 解码：先按 <b>UTF-8 严格</b>解码，抛 {@code CharacterCodingException} 才退回
     * <b>GB18030</b>；两个都失败才报错。判据只有「UTF-8 严格失败」这一条，不猜编码。
     * 粘贴路径拿到的是 {@code String}，跳过这一步。
     */
    public static String decode(byte[] raw) {
        try {
            CharsetDecoder utf8 = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return utf8.decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException e) {
            try {
                CharsetDecoder gbk = Charset.forName("GB18030").newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
                return gbk.decode(ByteBuffer.wrap(raw)).toString();
            } catch (CharacterCodingException e2) {
                throw new IllegalArgumentException("词表既不是 UTF-8 也不是 GB18030，没法解码");
            }
        }
    }

    /**
     * 解析整份词表。行头认不出韵时整体失败（抛 {@link IllegalArgumentException}，
     * message 指明行号与行头原文）—— 静默跳过会让人以为导入成功了。
     */
    public static Parsed parse(String content) {
        List<RhymeEntry> entries = new ArrayList<>();
        List<Failed> failed = new ArrayList<>();
        int total = 0;
        String[] lines = content.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
        // 第一行是词性列头（第 1 格空串），第一列是韵母行头
        List<String> classHeads = new ArrayList<>();
        if (lines.length > 0) {
            String[] heads = lines[0].split("\t", -1);
            for (int c = 1; c < heads.length; c++) {
                classHeads.add(heads[c].trim());
            }
        }
        for (int r = 1; r < lines.length; r++) {
            String line = lines[r];
            if (StringUtils.isBlank(line)) {
                continue;
            }
            String[] cells = line.split("\t", -1);
            // 行头 → 一个或多个韵母（按 / 拆；必须 trim：实测有 ` iu ` / `ie ` / ` er` 这类写法）
            List<PinyinSyllable> anchors = new ArrayList<>();
            List<String> normalizedFinals = new ArrayList<>();
            String rawHead = cells.length > 0 ? cells[0] : "";
            for (String segment : rawHead.split("/")) {
                String normalized = RhymeService.normalizeFinal(segment);
                if (normalized.isEmpty()) {
                    continue;
                }
                PinyinSyllable syllable = anchorSyllable(normalized);
                if (syllable == null) {
                    throw new IllegalArgumentException(
                            "第 " + (r + 1) + " 行行头「" + segment.trim() + "」认不出是哪个韵");
                }
                anchors.add(syllable);
                normalizedFinals.add(normalized);
            }
            if (anchors.isEmpty()) {
                continue; // 整行行头为空（尾部的空行），不是错误
            }
            // 每个数据格：按词性切词、逐词落一行（entry_type 由字数决定）
            for (int c = 1; c < cells.length; c++) {
                String wordClass = c - 1 < classHeads.size() ? classHeads.get(c - 1) : "";
                for (String word : splitWords(cells[c])) {
                    total++;
                    int last = word.codePointBefore(word.length());
                    if (!PinyinUtil.isHanzi(last)) {
                        // 英文 / 数字 / 标点结尾（含个别笔误）：丢弃但不静默
                        failed.add(new Failed(r + 1, c, word, "尾字不是汉字"));
                        continue;
                    }
                    PinyinSyllable common = RhymeService.resolveReading(last, null);
                    if (common == null) {
                        failed.add(new Failed(r + 1, c, word, "尾字「"
                                + new String(Character.toChars(last)) + "」没有可解析的读音"));
                        continue;
                    }
                    // 一个行头拆出多个韵母时（o/uo）同一个词落多行（finals 不同、韵身相同；
                    // uk_text_pinyin(text,pinyin) 会把重复读音的行自动去重，去重后不影响按韵查）
                    for (int a = 0; a < anchors.size(); a++) {
                        PinyinSyllable anchor = anchors.get(a);
                        PinyinSyllable reading = RhymeService.resolveReading(last, anchor.rhymeBody());
                        if (reading == null) {
                            reading = common;
                        }
                        RhymeEntry e = new RhymeEntry();
                        // 与 RhymeService:627 / RhymeWordlistService:188 同口径 —— 曾经这里硬编码 "WORD"，
                        // 把单字全标成了 WORD，与 MODERN 的同字 CHAR 行并存（entry_type 进了旧唯一键），
                        // 是 151 组重复行的根因
                        e.setEntryType(word.codePointCount(0, word.length()) == 1 ? "CHAR" : "WORD");
                        e.setText(word);
                        // 列头原样存词性（「动作」就存「动作」，不做映射，§4.4 第 4 条）
                        e.setWordClass(StringUtils.trimToNull(wordClass));
                        e.setSource(RhymeService.SOURCE_XLSX);
                        e.setTier(3); // 用户自己攒的词，无官方分级；靠语料回填的 freq 往上提
                        if (reading.rhymeBody().equals(anchor.rhymeBody())) {
                            // 消歧成功：词尾字确有读这个韵的读音
                            e.setPinyin(reading.pinyin());
                            e.setFinals(reading.finals());
                            e.setRhymeBody(reading.rhymeBody());
                            e.setYun18(reading.yun18());
                        } else {
                            // 一个都不符合：仍按行头落库（用户的表比算法权威），note 标出让用户复核
                            e.setPinyin(reading.pinyin());
                            e.setFinals(normalizedFinals.get(a));
                            e.setRhymeBody(anchor.rhymeBody());
                            e.setYun18(anchor.yun18());
                            e.setNote("行头「" + rawHead.trim() + "」与尾字「"
                                    + new String(Character.toChars(last)) + "」的读音「"
                                    + reading.pinyin() + "」不符，请复核");
                        }
                        entries.add(e);
                    }
                }
            }
        }
        return new Parsed(entries, total, failed);
    }

    /** 单元格切词：以 {@code 、} 为主，同时容忍 , ， ; ； 与空白；丢空串。 */
    static List<String> splitWords(String cell) {
        List<String> words = new ArrayList<>();
        for (String piece : cell.trim().split("[、，,;；\\s]+")) {
            String word = piece.trim();
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        return words;
    }

    /**
     * 行头 → 韵部锚。舌尖元音行头 {@code -i}（实测词表第 5 行）单独放行 ——
     * {@code parse("-i")} 走不通（含连字符）、{@code parse("i")} 又是七齐，只能按五支直取。
     */
    private static PinyinSyllable anchorSyllable(String normalized) {
        if (normalized.equals("-i")) {
            return new PinyinSyllable("-i", "-i", 0, "", "-i", "-i",
                    "一七", "十三支", "五支");
        }
        return PinyinSyllable.parse(normalized);
    }
}
