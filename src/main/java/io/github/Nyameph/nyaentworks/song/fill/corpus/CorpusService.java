package io.github.Nyameph.nyaentworks.song.fill.corpus;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import io.github.Nyameph.nyaentworks.common.db.SqlDialect;
import io.github.Nyameph.nyaentworks.common.lyric.LyricTextReader;
import io.github.Nyameph.nyaentworks.manga.util.MangaTextUtil;
import io.github.Nyameph.nyaentworks.common.pinyin.PinyinSyllable;
import io.github.Nyameph.nyaentworks.common.pinyin.PinyinUtil;
import io.github.Nyameph.nyaentworks.shout.service.ShoutGroupService;
import io.github.Nyameph.nyaentworks.common.lyric.LyricLine;
import io.github.Nyameph.nyaentworks.song.consts.SongFileType;
import io.github.Nyameph.nyaentworks.song.consts.SongStatus;
import io.github.Nyameph.nyaentworks.song.config.SongProperties;
import io.github.Nyameph.nyaentworks.song.entity.LyricCorpusLine;
import io.github.Nyameph.nyaentworks.song.entity.LyricCorpusPair;
import io.github.Nyameph.nyaentworks.song.entity.RhymeEntry;
import io.github.Nyameph.nyaentworks.song.entity.SongFile;
import io.github.Nyameph.nyaentworks.song.entity.SongGroup;
import io.github.Nyameph.nyaentworks.song.fill.LyricFillAligner;
import io.github.Nyameph.nyaentworks.song.fill.rhyme.RhymeService;
import io.github.Nyameph.nyaentworks.song.mapper.LyricCorpusLineMapper;
import io.github.Nyameph.nyaentworks.song.mapper.LyricCorpusPairMapper;
import io.github.Nyameph.nyaentworks.song.mapper.RhymeEntryMapper;
import io.github.Nyameph.nyaentworks.song.mapper.SongFileMapper;
import io.github.Nyameph.nyaentworks.song.mapper.SongGroupMapper;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 语料库：采集（组 → 歌词 → 清洗 → 句尾注音 → 落库）、词典回填、配对 diff、检索
 * （填词助手设计 §5）。
 *
 * <p><b>采集口径</b>：SONG 从 {@code song_group}（ACTIVE 且评分达阈值）走库取组、
 * 磁盘读文件；SHOUT 从成品-喊麦磁盘现扫（喊麦的列表页同款做法，§12）。
 * 清洗用 {@link LyricFillAligner#cleanLines}（保留无时间轴行，txt 也收），句尾注音
 * 全读音展开（多音字的每个读音韵部都算命中），tail_pinyin 只取最常用读音。
 *
 * <p><b>重建口径（记一条规则）</b>：重扫 = 删掉重建 —— line 表全清、rhyme_entry 只删
 * CORPUS 来源，再整批重建。累加会在组降分 / 删除后留永久残留。{@code lyric_corpus_pair}
 * 来自填词保存钩子，重扫<b>绝不碰</b>。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CorpusService {

    public static final String KIND_SONG = "SONG";
    public static final String KIND_SHOUT = "SHOUT";

    private final LyricCorpusLineMapper lineMapper;
    private final LyricCorpusPairMapper pairMapper;
    private final RhymeEntryMapper rhymeMapper;
    private final SongGroupMapper groupMapper;
    private final SongFileMapper fileMapper;
    private final SongProperties properties;
    private final ShoutGroupService shoutGroupService;

    // ==================== 采集 ====================

    /** 一个待采集组：目录 + 全部候选歌词文件名。 */
    public record CorpusGroup(long groupId, String kind, String groupKey, String partitionName,
                              String displayName, int score, Path dir, List<String> lyricFiles) {
    }

    /**
     * 待采集组清单。SONG：{@code song_group} 里 ACTIVE 且评分 ≥ min-score 的组
     * （组目录 = song-dir / partition_name，文件在 song_file）；SHOUT：成品-喊麦磁盘现扫。
     */
    public List<CorpusGroup> planGroups() {
        List<CorpusGroup> result = new ArrayList<>();
        int minScore = properties.getCorpus().getMinScore();
        List<SongGroup> groups = groupMapper.selectList(Wrappers.<SongGroup>lambdaQuery()
                .eq(SongGroup::getStatus, SongStatus.ACTIVE)
                .ge(SongGroup::getScore, minScore));
        for (SongGroup group : groups) {
            // 要的是**完整文件名**（下面按它 resolve 到磁盘路径去读歌词），
            // 而库里存的是 main_name / suffix 两段 —— 拼回来（SongFile#fullName）
            List<String> lyrics = fileMapper.selectList(Wrappers.<SongFile>lambdaQuery()
                            .eq(SongFile::getSongId, group.getId())
                            .eq(SongFile::getFileType, SongFileType.LYRIC))
                    .stream().map(SongFile::fullName).toList();
            result.add(new CorpusGroup(group.getId(), KIND_SONG, null,
                    group.getPartitionName(), group.getOriginalTitle(),
                    group.getScore() == null ? 0 : group.getScore(),
                    Path.of(properties.getSongDir(), group.getPartitionName()), lyrics));
        }
        if (properties.getCorpus().isIncludeShout()) {
            int ordinal = 0;
            for (ShoutGroupService.ShoutGroup group : shoutGroupService.listArchived(null)) {
                if (group.score() == null || group.score() < minScore) {
                    continue;
                }
                ordinal++;
                result.add(new CorpusGroup(-(long) ordinal, KIND_SHOUT, group.key(),
                        group.partitionName(), group.mainName(), group.score(),
                        group.dir(), group.lyricFiles()));
            }
        }
        return result;
    }

    /** 重扫前置：line 表全清（含喊麦），rhyme_entry 只删 CORPUS。配对表绝不碰。 */
    public void resetForRescan() {
        lineMapper.delete(Wrappers.emptyWrapper());
        rhymeMapper.delete(Wrappers.<RhymeEntry>lambdaQuery()
                .eq(RhymeEntry::getSource, RhymeService.SOURCE_CORPUS));
    }

    /**
     * 一句可入库的文本上限。480 是「远超过任何一句歌词」的经验界（再长就是整页字幕之类的
     * 误读），同时给列宽 {@code VARCHAR(500)} 留出余量 —— 贴着列宽卡会在插入时直接报错。
     */
    static final int MAX_LINE_LENGTH = 480;

    /** 超长行判定（采集与计数共用一把尺子）。 */
    static boolean isOverlong(String text) {
        return text != null && text.length() > MAX_LINE_LENGTH;
    }

    /** 一次采集的结果：入库句数 + 因超长被丢掉的行数。 */
    public record CollectCount(int lines, int skippedLong) {
    }

    /**
     * 采集一个组：每个版本（同主名）只取一个歌词文件（.lrc → .srt → .txt），逐文件
     * 清洗、注音、入库。<b>丢弃的行要能报数</b>：一个文件都没挑出来（{@code lines=0}）由
     * 调用方计 {@code skippedNoLyric}，超长行在这里单独计数 —— 静默丢行会让用户看到
     * 「入库 M 句」却不知道 M 不是全集（§15 M2 的偏差清单原本就漏了这条）。
     */
    public CollectCount collectGroup(CorpusGroup group) {
        List<String> picks = pickLyricFiles(group.lyricFiles());
        if (picks.isEmpty()) {
            return new CollectCount(0, 0);
        }
        int skippedLong = 0;
        // 整批共用一个时刻：几百上千行各取一次 now() 只会在同一批里差出几十毫秒，
        // 反而让「同一批」看起来不整齐。自定义 @Insert 走不到 MetaObjectHandler 的自动填充，
        // 所以由这里显式填（见 LyricCorpusLineMapper#insertBatch 的注释）。
        LocalDateTime now = LocalDateTime.now();
        List<LyricCorpusLine> batch = new ArrayList<>(64);
        for (String fileName : picks) {
            LyricTextReader.Lyric lyric = LyricTextReader.read(group.dir().resolve(fileName), fileName);
            if (!lyric.supported() || lyric.lines().isEmpty()) {
                continue;
            }
            List<LyricLine> cleaned = LyricFillAligner.cleanLines(lyric.lines());
            for (int i = 0; i < cleaned.size(); i++) {
                String text = cleaned.get(i).text();
                if (StringUtils.isBlank(text)) {
                    continue;
                }
                if (isOverlong(text)) {
                    skippedLong++; // 整页字幕之类，不入库，但要让用户知道丢了多少
                    continue;
                }
                TailRhyme tail = tailOf(text);
                LyricCorpusLine row = new LyricCorpusLine();
                row.setGroupId(group.groupId());
                row.setKind(group.kind());
                row.setGroupKey(group.groupKey());
                row.setFileName(fileName);
                row.setOriginalTitle(group.displayName());
                row.setScore(group.score());
                row.setLineIndex(i);
                row.setText(text);
                row.setTailChar(tail.tailChar());
                row.setTailPinyin(tail.tailPinyin());
                row.setFinals(tail.finals());
                row.setYun18(tail.yun18());
                row.setCreateTime(now);
                row.setUpdateTime(now);
                batch.add(row);
            }
        }
        for (int from = 0; from < batch.size(); from += 500) {
            lineMapper.insertBatch(batch.subList(from, Math.min(batch.size(), from + 500)));
        }
        return new CollectCount(batch.size(), skippedLong);
    }

    /**
     * 重扫结果（任务页的 result_json 与说明行）。{@code skippedLong} = 被超长判定丢掉的行数
     * —— 它同样是一句「本该进语料却没进」的话，必须和 {@code skippedNoLyric} 一样报出来，
     * 否则「入库 M 句」会被当成全集（§15 M2 的偏差清单原本漏了它）。
     */
    public record RescanStats(int scanned, int songLines, int shoutLines,
                              int skippedNoLyric, int skippedLong, int corpusWords) {
    }

    /**
     * 句尾词回填词典（§4.5）：每句提取句尾 2~4 字候选词，计数 ≥ 2 的以 CORPUS 来源插入。
     * 落库走 {@code upsertCorpusBatch}：撞上已有的 CORPUS 行时更新 {@code freq}（重算后不
     * 刷新它，freq 就停在第一次的值上），撞上 XLSX / MANUAL 行时一个字段都不动
     *（§4.4 第 8 条）。返回词条数。
     * 调用前置：{@link #resetForRescan} 已删光 CORPUS 词条 —— 重建口径，不累加。
     *
     * <p><b>词表里已有的词直接跳过、不建 CORPUS 行</b>（2026-09-14 修，见 {@link #wordlistTexts}）。
     */
    public int rebuildCorpusWords() {
        Set<String> wordlist = wordlistTexts();
        Map<String, Integer> freq = new LinkedHashMap<>();
        Map<String, Integer> tailByWord = new LinkedHashMap<>();
        // 全部句子逐行扫（喊麦句也进词典；kind 不区分），按行流式取词计频
        Long total = lineMapper.selectCount(null);
        int pageSize = 2000;
        for (int offset = 0; offset < total; offset += pageSize) {
            List<LyricCorpusLine> page = lineMapper.selectList(Wrappers.<LyricCorpusLine>lambdaQuery()
                    .orderByAsc(LyricCorpusLine::getId)
                    .last("LIMIT " + pageSize + " OFFSET " + offset));
            for (LyricCorpusLine line : page) {
                String word = RhymeService.extractTailWord(line.getText());
                if (word == null) {
                    continue;
                }
                freq.merge(word, 1, Integer::sum);
                // 词尾字从词本身取：行尾可能带标点，extractTailWord 的窗口停在标点前
                tailByWord.putIfAbsent(word, word.codePointBefore(word.length()));
            }
        }
        List<RhymeEntry> entries = new ArrayList<>();
        for (Map.Entry<String, Integer> e : freq.entrySet()) {
            if (e.getValue() < 2) {
                continue; // 偶然组合不成词
            }
            String word = e.getKey();
            if (wordlist.contains(word)) {
                continue; // 词表已收录：读音以词表那行为准，别再拿尾字猜一个出来
            }
            Integer tailCp = tailByWord.get(word);
            PinyinSyllable reading = tailCp == null ? null
                    : RhymeService.resolveReading(tailCp, null);
            if (reading == null) {
                continue;
            }
            RhymeEntry entry = new RhymeEntry();
            entry.setEntryType("WORD");
            entry.setText(word);
            entry.setPinyin(reading.pinyin());
            entry.setFinals(reading.finals());
            entry.setRhymeBody(reading.rhymeBody());
            entry.setYun18(reading.yun18());
            entry.setSource(RhymeService.SOURCE_CORPUS);
            // 词是人攒的，但尾字有官方分级：用它排序比全填 3 好（§4.5 第 5 条）
            entry.setTier(PinyinUtil.tier(tailCp));
            entry.setFreq(e.getValue());
            entries.add(entry);
        }
        for (int from = 0; from < entries.size(); from += 500) {
            rhymeMapper.upsertCorpusBatch(entries.subList(from, Math.min(entries.size(), from + 500)));
        }
        return entries.size();
    }

    /**
     * 词表（XLSX / MANUAL / OPEN）里已有的多字词原文集合 —— {@link #rebuildCorpusWords} 用它
     * 决定哪些词不必再建 CORPUS 行。
     *
     * <p><b>为什么必须跳过</b>：CORPUS 行的读音是 {@code resolveReading(尾字, null)}，即
     * <b>拿词的尾字当单字</b>取最常用音，与整词怎么读无关。词表行存的是这个词的真实读音。
     * 两者<b>同音</b>时本来就撞唯一键（{@code upsertCorpusBatch} 遇到非 CORPUS 行一个字段
     * 都不动），可<b>不同音</b>时就会多出一行错读音 —— 2026-09-14 实测 59 组（一场 chǎng/cháng、
     * 主角 jiǎo/jué、反应 yīng/yìng…），靠去重任务事后收拾。CORPUS 的定位是「补词表没收录的
     * 词」，所以这里直接跳过，从源头不再造出那一行。
     *
     * <p>只取多字词（{@code CHAR_LENGTH(text) > 1}）：{@code extractTailWord} 保证候选词 ≥ 2 字，
     * 把 8 千多个单字词表行载进来纯属浪费。集合约 4.5 万个 text，一次全载可接受。
     *
     * <p>注意这是<b>快照</b>：若词表行之后被删（按来源批量删 OPEN / XLSX），下次重扫这些词会被
     * 重新建成 CORPUS 行 —— 这正是想要的（CORPUS 补漏）。
     */
    private Set<String> wordlistTexts() {
        List<RhymeEntry> rows = rhymeMapper.selectList(Wrappers.<RhymeEntry>lambdaQuery()
                .select(RhymeEntry::getText)
                .ne(RhymeEntry::getSource, RhymeService.SOURCE_CORPUS)
                .apply(SqlDialect.charLength("text") + " > 1"));
        Set<String> texts = new HashSet<>(rows.size() * 2);
        for (RhymeEntry row : rows) {
            texts.add(row.getText());
        }
        return texts;
    }

    // ==================== 句尾注音（采集与配对共用） ====================

    /** 句尾注音结果。英文句尾 / 拆不出时韵部列为空（不编造）。 */
    public record TailRhyme(String tailChar, String tailPinyin, String finals, String yun18) {
    }

    /**
     * 一句的句尾注音：用 {@link LyricFillAligner#tokenize} 切单元，倒着找第一个汉字单元；
     * 倒着第一个单元是英文 → 英文句尾（tail_char 留空、不判韵）。多音字全部读音的韵母 /
     * 十八韵名去重逗号连接；tail_pinyin 取最常用读音（readingsByFrequency 第一个）。
     */
    public static TailRhyme tailOf(String text) {
        List<String> units = LyricFillAligner.tokenize(text);
        if (units.isEmpty()) {
            return new TailRhyme(null, null, null, null);
        }
        if (LyricFillAligner.hasAsciiLetter(units.get(units.size() - 1))) {
            return new TailRhyme(null, null, null, null); // 英文句尾：不判韵
        }
        for (int k = units.size() - 1; k >= 0; k--) {
            String unit = units.get(k);
            int cp = unit.codePoints().filter(PinyinUtil::isHanzi).findFirst().orElse(-1);
            if (cp < 0) {
                if (LyricFillAligner.hasAsciiLetter(unit)) {
                    break; // 英文单元拦在句尾，不再往前找
                }
                continue; // 标点 / 数字：继续往前找汉字单元
            }
            String tailChar = new String(Character.toChars(cp));
            List<PinyinSyllable> readings = PinyinUtil.readings(cp);
            String tailPinyin = null;
            List<PinyinSyllable> byFrequency = PinyinUtil.readingsByFrequency(cp);
            if (!byFrequency.isEmpty()) {
                tailPinyin = byFrequency.getFirst().pinyin();
            }
            Set<String> finals = new LinkedHashSet<>();
            Set<String> yun18 = new LinkedHashSet<>();
            for (PinyinSyllable reading : readings) {
                finals.add(reading.finals());
                yun18.add(reading.yun18());
            }
            return new TailRhyme(tailChar, tailPinyin,
                    finals.isEmpty() ? null : String.join(",", finals),
                    yun18.isEmpty() ? null : String.join(",", yun18));
        }
        return new TailRhyme(null, null, null, null);
    }

    /**
     * 歌词文件选择（纯函数，§5.1）：按去扩展名主名分组（一个主名 = 一个版本），每版只取
     * 一个 —— 优先级 {@code .lrc → .srt → .txt}，其余（.ass 等）不选。返回被选中的文件名。
     */
    public static List<String> pickLyricFiles(Iterable<String> lyricFileNames) {
        Map<String, String> best = new LinkedHashMap<>();
        for (String name : lyricFileNames) {
            int dot = name.lastIndexOf('.');
            String main = dot < 0 ? name : name.substring(0, dot);
            String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
            int rank = switch (ext) {
                case "lrc" -> 3;
                case "srt" -> 2;
                case "txt" -> 1;
                default -> 0; // ass 等：不收
            };
            if (rank == 0) {
                continue;
            }
            best.merge(main, name, (a, b) -> rankOf(a) >= rankOf(b) ? a : b);
        }
        return new ArrayList<>(best.values());
    }

    private static int rankOf(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".lrc")) {
            return 3;
        }
        if (lower.endsWith(".srt")) {
            return 2;
        }
        if (lower.endsWith(".txt")) {
            return 1;
        }
        return 0;
    }

    // ==================== 配对 diff（保存钩子，§5.6） ====================

    /** 一次保存里「整句填满」的句：行下标 + 原词句 + 新词句。 */
    public record PairDesired(int lineIndex, String originalText, String filledText) {
    }

    /**
     * diff 式写配对：逐句与库里现有行比对，只动变化的行 —— 钩子挂在 save 上，前端每
     * 5 秒自动保存一次都会走到，不 diff 就是每 5 秒刷一遍库。没有任何变化时一行 SQL 不发。
     */
    public void syncPairs(long fillId, List<PairDesired> desired) {
        Map<Integer, PairDesired> want = new LinkedHashMap<>();
        for (PairDesired d : desired) {
            want.put(d.lineIndex(), d);
        }
        List<LyricCorpusPair> existing = pairMapper.selectList(Wrappers.<LyricCorpusPair>lambdaQuery()
                .eq(LyricCorpusPair::getFillId, fillId));
        Map<Integer, LyricCorpusPair> existingByIndex = new LinkedHashMap<>();
        for (LyricCorpusPair row : existing) {
            existingByIndex.put(row.getLineIndex(), row);
        }
        boolean changed = false;
        for (Map.Entry<Integer, PairDesired> e : want.entrySet()) {
            PairDesired d = e.getValue();
            LyricCorpusPair old = existingByIndex.get(e.getKey());
            String yun18 = tailOf(d.filledText()).yun18();
            if (old == null) {
                LyricCorpusPair row = new LyricCorpusPair();
                row.setFillId(fillId);
                row.setLineIndex(d.lineIndex());
                row.setOriginalText(d.originalText());
                row.setFilledText(d.filledText());
                row.setYun18(yun18);
                pairMapper.insert(row);
                changed = true;
            } else if (!d.filledText().equals(old.getFilledText())
                    || !Objects.equals(d.originalText(), old.getOriginalText())
                    || !Objects.equals(yun18, old.getYun18())) {
                old.setOriginalText(d.originalText());
                old.setFilledText(d.filledText());
                old.setYun18(yun18);
                pairMapper.updateById(old);
                changed = true;
            }
        }
        for (LyricCorpusPair old : existing) {
            if (!want.containsKey(old.getLineIndex())) {
                pairMapper.deleteById(old.getId()); // 句被清空 / 删了：对应行消失，不留旧值
                changed = true;
            }
        }
        if (changed) {
            log.info("语料配对更新：fill {} 现有 {} 行，目标 {} 行", fillId, existing.size(), want.size());
        }
    }

    // ==================== 检索（RAG 取材侧，§5.4） ====================

    /** 检索结果行。 */
    public record LineView(Long groupId, String kind, String fileName, String originalTitle,
                           int score, int lineIndex, String text, String tailChar,
                           String tailPinyin, String yun18) {
    }

    /**
     * 按韵检索语料句。命中的是句尾字<b>全部读音</b>的韵部集合（逗号分隔列里找元素，全表扫——
     * 2~3 万行几十毫秒，可接受，见 §5.3）。
     *
     * @param weighted true = 加权随机（RAND()/score 升序，分越高越容易抽中）；false = 按分数序
     */
    public List<LineView> searchLines(List<String> yun18s, List<String> finalss,
                                      String kind, String q, int limit, boolean weighted) {
        var query = Wrappers.<LyricCorpusLine>lambdaQuery();
        String k = StringUtils.defaultIfBlank(kind, KIND_SONG);
        if (!"ALL".equalsIgnoreCase(k)) {
            query.eq(LyricCorpusLine::getKind, k);
        }
        if (!yun18s.isEmpty() || !finalss.isEmpty()) {
            query.and(w -> {
                for (String v : yun18s) {
                    w.or().apply(SqlDialect.findInSet("yun18"), v);
                }
                for (String v : finalss) {
                    w.or().apply(SqlDialect.findInSet("finals"), v);
                }
            });
        }
        if (StringUtils.isNotBlank(q)) {
            // 通配符在 Java 侧拼（SQLite 没有 CONCAT），转义后的模式仍走绑定参数
            query.apply(SqlDialect.likeContains("text"),
                    "%" + MangaTextUtil.escapeLike(q.trim()) + "%");
        }
        int capped = Math.max(1, Math.min(limit, 200));
        if (weighted) {
            // 加权随机 = RAND()/score 升序：score 只会是 1/3/5/7/9，除法安全。
            // 不要写成「先按分数筛一道再随机」——那会让 9 分反复出现、5 分抽不到
            query.last("ORDER BY " + SqlDialect.random() + " / score ASC LIMIT " + capped);
        } else {
            query.last("ORDER BY score DESC, id ASC LIMIT " + capped);
        }
        return mapperLines(query);
    }

    private List<LineView> mapperLines(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LyricCorpusLine> query) {
        return lineMapper.selectList(query).stream()
                .map(l -> new LineView(l.getGroupId(), l.getKind(), l.getFileName(),
                        l.getOriginalTitle(), l.getScore() == null ? 0 : l.getScore(),
                        l.getLineIndex(), l.getText(), l.getTailChar(), l.getTailPinyin(),
                        l.getYun18()))
                .toList();
    }

    // ==================== JSONL 导出（§5.7） ====================

    /** 微调素材行：yun18 有值的优先（无韵部排后面）。 */
    public List<LyricCorpusPair> pairsForExport(int limit) {
        return pairMapper.selectList(Wrappers.<LyricCorpusPair>lambdaQuery()
                .last("ORDER BY yun18 IS NULL ASC, id DESC LIMIT "
                        + Math.max(1, Math.min(limit, 100000))));
    }

    /** 随机取 N 对改写示例（AI few-shot 用；量级小，RAND() 全表扫可接受）。 */
    public List<LyricCorpusPair> randomPairs(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return pairMapper.selectList(Wrappers.<LyricCorpusPair>lambdaQuery()
                .last("ORDER BY " + SqlDialect.random() + " LIMIT " + Math.min(limit, 50)));
    }
}
