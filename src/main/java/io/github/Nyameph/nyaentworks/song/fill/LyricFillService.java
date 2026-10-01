package io.github.Nyameph.nyaentworks.song.fill;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import io.github.Nyameph.nyaentworks.common.pinyin.PinyinUtil;
import io.github.Nyameph.nyaentworks.song.config.SongProperties;
import io.github.Nyameph.nyaentworks.song.entity.SongLyricFill;
import io.github.Nyameph.nyaentworks.song.entity.SongOriginalSetting;
import io.github.Nyameph.nyaentworks.song.fill.LyricTemplate.FillLine;
import io.github.Nyameph.nyaentworks.song.fill.LyricTemplate.FillNote;
import io.github.Nyameph.nyaentworks.song.fill.LyricTemplate.FillSlot;
import io.github.Nyameph.nyaentworks.song.fill.LyricTemplate.FillTrack;
import io.github.Nyameph.nyaentworks.song.fill.corpus.CorpusService;
import io.github.Nyameph.nyaentworks.song.mapper.SongLyricFillMapper;
import io.github.Nyameph.nyaentworks.song.mapper.SongOriginalSettingMapper;
import io.github.Nyameph.nyaentworks.song.service.SongTemplateService;
import io.github.Nyameph.nyaentworks.common.lyric.LyricLine;
import io.github.Nyameph.nyaentworks.common.lyric.LyricTextReader;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 填词项目：落库读写（{@code song_lyric_fill}）、分句、导出（实现说明 3.4 / 5.12）。
 *
 * <p><b>一个原曲可以有多份填词</b>（多对一，{@code original_id} → {@code song_original_setting.id}）：
 * 每开一次「新建填词」就插一行，各行的骨架 / 分句 / 填词互不干扰。打开时按 {@code fillId}
 * 定位；没给就取该原曲<b>最近</b>的一份，一份都没有才新建。
 *
 * <p><b>固化</b>：解析一次落库，之后直读已固化的骨架，不再重解析；只有 {@link #reparse}
 * 才重读 svp。svp 文件之后改了、搬了都不影响已固化的骨架（打开时只用库里存的路径做展示）。
 *
 * <p><b>改勾选 / 手填偏移会重跑分句</b>——句子是「勾选轨合并时间线的划分」，
 * 轨变了划分必然变。重跑时已填内容按（轨, 音符）位置回指（见 {@code carry}），
 * 前端在切换音轨前会二次确认。
 *
 * <p>填词是<b>同步接口</b>：单项目、不搬磁盘文件，与 {@code template/apply}、
 * {@code original-rate} 同类；批量 / 耗时操作才走异步任务框架。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LyricFillService {

    /** 导出 lrc 时，相邻两句之间超过这个时长（1 秒）就算「空白期」，补一个只有时间戳的空行 */
    private static final long LRC_GAP_THRESHOLD_BLICK = LyricFillParser.BLICK_PER_SECOND;

    /** 导出 lrc 时，曲终那行空歌词延后多久：最后一个音符结束之后再等 1 秒（见 {@link #buildLrc}） */
    private static final long LRC_TAIL_BLANK_SECONDS = 1;

    private final SongOriginalSettingMapper originalMapper;
    private final SongLyricFillMapper fillMapper;
    private final SongTemplateService templateService;
    private final CorpusService corpusService;
    /** 只为导出 lrc 时取「填词署名」({@code fillSignature}) —— 那是全局一份，不随填词走。 */
    private final SongProperties properties;

    /** 导出结果。{@code tracks} 只有回填模板文本用（按轨各一份），lrc 时为 null。 */
    public record ExportResult(String type, String text, List<TrackText> tracks) {
    }

    /** 一条轨的回填模板文本：逐 note 一个 token、空格分隔（一整行） */
    public record TrackText(int trackIndex, String trackName, String text) {
    }

    /** 项目列表里的一行：够页面画列表，不带骨架正文（那是 resolve 的事）。 */
    public record FillSummary(Long id, Long originalId, String originalName, String name,
                              int lineCount, int filledCount, LocalDateTime updateTime) {
    }

    // ==================== 项目列表 ====================

    /**
     * 填词项目列表，按创建顺序（「填词 1」在前）。
     *
     * @param originalId 只列这一首原曲的；{@code null} = 列全部 —— 左侧导航直接点「填词」
     *                   进来时没有原曲上下文，列表页要能自己立住
     */
    public List<FillSummary> list(Long originalId) {
        var query = Wrappers.<SongLyricFill>lambdaQuery().orderByAsc(SongLyricFill::getId);
        if (originalId != null) {
            requireSetting(originalId);
            query.eq(SongLyricFill::getOriginalId, originalId);
        }
        return fillMapper.selectList(query).stream().map(LyricFillService::summary).toList();
    }

    private static FillSummary summary(SongLyricFill row) {
        List<FillLine> lines = LyricFillStore.readLines(row.getLinesJson());
        List<List<String>> filled = LyricFillStore.readFilled(row.getFilledJson());
        int done = 0;
        for (List<String> values : filled) {
            if (values.stream().anyMatch(StringUtils::isNotBlank)) {
                done++;
            }
        }
        return new FillSummary(row.getId(), row.getOriginalId(),
                StringUtils.defaultString(row.getOriginalName()), displayName(row),
                lines.size(), done, row.getUpdateTime());
    }

    /** 删掉一份填词。只删库里的行，磁盘上的 svp / 歌词一动不动。 */
    public void delete(Long fillId) {
        requireFill(fillId);
        // 配对行没有外键，不主动清就是孤儿 —— 会原样进 /pairs.jsonl 微调素材（§9.4 红线之外
        // 的脏数据）。与保存钩子同口径：清理失败只记日志，不能让删填词跟着失败。
        try {
            corpusService.syncPairs(fillId, List.of());
        } catch (Exception e) {
            log.warn("语料配对清理失败（不影响填词删除）：fill {}", fillId, e);
        }
        fillMapper.deleteById(fillId);
    }

    // ==================== 打开 ====================

    /**
     * 打开填词项目。
     *
     * @param fillId        指定要打开哪一份；null = 该原曲最近的一份，一份都没有就新建
     * @param trackIndices  改勾选时传（null / 空 = 用库里存的，库里也没有就用全部歌唱轨）
     * @param offsetSeconds 手填的整体偏移（秒）；null = 自动对齐
     * @param realign       强制重跑分句（页面上点「重新分句」用 —— 它要的正是
     *                      {@code offsetSeconds = null} 的自动值，而单看 null 分不出
     *                      「自动」与「没要求重算」）
     */
    public LyricTemplate resolve(Long originalId, Long fillId, List<Integer> trackIndices,
                                 Double offsetSeconds, boolean realign) {
        SongOriginalSetting setting = requireSetting(originalId);
        SongLyricFill row = fillId == null ? latest(originalId) : requireFill(fillId);
        if (row == null) {
            return create(originalId, null);
        }
        if (!originalId.equals(row.getOriginalId())) {
            throw new IllegalArgumentException("填词项目 " + fillId + " 不属于这首原曲");
        }
        return open(setting, row, trackIndices, offsetSeconds, realign);
    }

    /**
     * 新建一份填词（同一原曲可以有任意多份）。解析 svp 得到全新骨架 + 自动分句，
     * 填词留空；名字空着就自动叫「填词 N」（N = 已有份数 + 1）。
     */
    public LyricTemplate create(Long originalId, String name) {
        SongOriginalSetting setting = requireSetting(originalId);
        Path svpPath = requireSvpPath(originalId);
        List<FillTrack> tracks = LyricFillParser.parse(svpPath);
        List<Integer> selected = allTrackIndices(tracks);
        LyricFillAligner.SplitResult split = LyricFillAligner.split(tracks, selected,
                readLrcLines(originalId), null);

        SongLyricFill row = new SongLyricFill();
        row.setOriginalId(originalId);
        row.setName(StringUtils.defaultIfBlank(StringUtils.trim(name), defaultName(originalId)));
        row.setSvpPath(svpPath.toString());
        row.setOriginalName(setting.getRawName());
        row.setTrackIndices(join(selected));
        row.setNotesJson(LyricFillStore.toJson(tracks));
        row.setLinesJson(LyricFillStore.toJson(split.lines()));
        row.setFilledJson("[]");
        fillMapper.insert(row);

        return template(row, setting, tracks, split.lines(),
                carry(List.of(), List.of(), split.lines()),
                split.defaults(), split.gaps(),
                LyricFillAligner.brackets(tracks, selected, split.lines(),
                        readLrcLines(originalId)),
                split.pinyin(), selected, split.offset(), lyricFileName(originalId), false,
                split.notice());
    }

    /** 打开已存在的一份：骨架已固化，按需重跑分句。 */
    private LyricTemplate open(SongOriginalSetting setting, SongLyricFill row,
                               List<Integer> trackIndices, Double offsetSeconds, boolean realign) {
        Long originalId = setting.getId();
        List<LyricLine> lrcLines = readLrcLines(originalId);
        String lyricName = lyricFileName(originalId);

        List<FillTrack> tracks = LyricFillStore.readTracks(row.getNotesJson());
        if (tracks.isEmpty()) {
            // 列被清空 / 损坏：按「重新解析」处理，别让页面白屏
            return reparse(row.getId());
        }
        List<Integer> stored = parseIndices(row.getTrackIndices());
        List<List<String>> filled = LyricFillStore.readFilled(row.getFilledJson());

        List<Integer> selected = (trackIndices == null || trackIndices.isEmpty())
                ? stored : List.copyOf(trackIndices);
        if (selected.isEmpty()) {
            selected = allTrackIndices(tracks);
        }

        boolean rebuild = realign || offsetSeconds != null
                || !new HashSet<>(selected).equals(new HashSet<>(stored));
        if (rebuild) {
            LyricFillAligner.SplitResult split =
                    LyricFillAligner.split(tracks, selected, lrcLines, offsetSeconds);
            // 旧填词按音符位置回指（旧分句可能为空——首次分句或历史数据没有）
            return template(row, setting, tracks, split.lines(),
                    carry(LyricFillStore.readLines(row.getLinesJson()), filled, split.lines()),
                    split.defaults(), split.gaps(),
                    LyricFillAligner.brackets(tracks, selected, split.lines(), lrcLines),
                    split.pinyin(), selected, split.offset(), lyricName, false, split.notice());
        }

        List<FillLine> lines = LyricFillStore.readLines(row.getLinesJson());
        if (lines.isEmpty()) {
            LyricFillAligner.SplitResult split =
                    LyricFillAligner.split(tracks, selected, lrcLines, null);
            return template(row, setting, tracks, split.lines(),
                    carry(List.of(), filled, split.lines()), split.defaults(), split.gaps(),
                    LyricFillAligner.brackets(tracks, selected, split.lines(), lrcLines),
                    split.pinyin(), selected, split.offset(), lyricName, false, split.notice());
        }
        // 直读路径也要补齐：库里存的可能是旧格式（一句一个稠密字符串 → readFilled 读成空列表），
        // 不补的话下发到前端就是「行长度 0」，而契约是 filled.get(i) 与 slots 等长。
        // 标红判据与分句质量提示一起取（readHints 只跑一趟对齐）
        LyricFillAligner.ReadHints hints =
                LyricFillAligner.readHints(tracks, selected, lrcLines);
        return template(row, setting, tracks, lines, carry(lines, filled, lines),
                LyricFillAligner.defaults(tracks, selected, lines, lrcLines),
                // 直读库里那份骨架：空位取用户编辑过的（gaps_json），没有 / 形状不对才现算
                resolveGaps(lines, null, row.getGapsJson(),
                        LyricFillAligner.gaps(tracks, selected, lines, lrcLines)),
                LyricFillAligner.brackets(tracks, selected, lines, lrcLines),
                hints.pinyin(), selected, null, lyricName, true, hints.notice());
    }

    // ==================== 视觉空位：取哪一份 ====================

    /**
     * 视觉空位取哪一份：<b>前端刚编辑的 &gt; 库里存的 &gt; 现算的</b>（与 {@code lines} /
     * {@code filled} 「传了就用传的、没传就用库里的」同一口径）。
     *
     * <p>三份都必须与 {@code lines} <b>形状完全一致</b>才认（{@link #shapeMatches}）：空位是
     * 「第几格之后加空格」，错一格就是**另一个位置上的空格**，比没有更糟。都不认就退回
     * {@code derived}（{@link LyricFillAligner#gaps} 现算的），页面与导出都不会串位。
     *
     * @param edited     前端这次传上来的（没传 / 形状不对则不看）
     * @param storedJson 库里 {@code gaps_json}（NULL = 从没编辑过）
     * @param derived    现算的那一份
     */
    static List<List<Boolean>> resolveGaps(List<FillLine> lines, List<List<Boolean>> edited,
                                           String storedJson, List<List<Boolean>> derived) {
        if (shapeMatches(lines, edited)) {
            return edited;
        }
        List<List<Boolean>> stored = LyricFillStore.readGaps(storedJson);
        return shapeMatches(lines, stored) ? stored : derived;
    }

    /** 空位与分句形状完全一致：行数相同、每一行的长度等于那一句的槽位数。 */
    static boolean shapeMatches(List<FillLine> lines, List<List<Boolean>> gaps) {
        if (gaps == null || gaps.size() != lines.size()) {
            return false;
        }
        for (int i = 0; i < lines.size(); i++) {
            List<Boolean> row = gaps.get(i);
            if (row == null || row.size() != lines.get(i).slots().size()) {
                return false;
            }
        }
        return true;
    }

    // ==================== 保存 ====================

    /** 保存某一份填词的勾选 + 分句 + 填词。派生字段由槽位重算，保证库里自洽。 */
    public LyricTemplate save(Long fillId, String name, List<Integer> trackIndices,
                              List<FillLine> lines, List<List<String>> filled) {
        return save(fillId, name, trackIndices, lines, filled, null);
    }

    /**
     * 保存（带视觉空位）。
     *
     * <p>{@code gaps} 与 {@code cleanLines} **形状完全一致**时才落库，否则存 NULL ——
     * NULL 的语义是「这一份从没编辑过空位」，页面与导出都退回现算（{@link #resolveGaps}）。
     * 形状校验比「尽量存」安全：对不上的空位一旦落库，画出来就是**串位的空格**
     * （哪个槽位后面加空格全看错位差多少），而退回现算最多是「用户刚加的空位没了」。
     */
    public LyricTemplate save(Long fillId, String name, List<Integer> trackIndices,
                              List<FillLine> lines, List<List<String>> filled,
                              List<List<Boolean>> gaps) {
        SongLyricFill row = requireFill(fillId);
        SongOriginalSetting setting = requireSetting(row.getOriginalId());

        List<FillLine> cleanLines = lines == null ? List.of()
                : lines.stream().map(LyricFillAligner::recompute).toList();
        // 旧填词按音符位置回指：合唱组展开后槽位数量会变，按序号对不上
        List<List<String>> cleanFilled = carry(lines, filled, cleanLines);
        List<FillTrack> tracks = LyricFillStore.readTracks(row.getNotesJson());
        // 合唱重复组联动：空槽从另一组抄值（前端已实时复制，这里兜旧数据 / 直调）
        cleanFilled = LyricFillAligner.syncGroupCopies(cleanLines, cleanFilled, tracks);
        // 落库前把延音槽位归一到与填词一致（复制可能把值写到另一组的延音格上），库里就不会
        // 再留下「空着的 HANZI 延音」
        cleanLines = LyricFillAligner.syncDashes(cleanLines, cleanFilled);

        // 本次<b>实际落库</b>的勾选轨：钩子必须用这一份，不能用 row 里的旧值 —— 同一次保存
        // 又改了勾选轨时，旧值算出的 gaps / brackets 会让配对文本与导出 lrc 不同源（§5.6）
        List<Integer> selected = (trackIndices == null || trackIndices.isEmpty())
                ? parseIndices(row.getTrackIndices()) : trackIndices;
        var update = Wrappers.<SongLyricFill>lambdaUpdate()
                .set(SongLyricFill::getOriginalName, setting.getRawName())
                .set(SongLyricFill::getName,
                        StringUtils.defaultIfBlank(StringUtils.trim(name), displayName(row)))
                .set(SongLyricFill::getTrackIndices, join(selected))
                .set(SongLyricFill::getLinesJson, LyricFillStore.toJson(cleanLines))
                .set(SongLyricFill::getFilledJson, LyricFillStore.toJson(cleanFilled))
                // 视觉空位：形状对得上才存，对不上 / 没传就存 NULL（= 从没编辑过，退回现算）
                .set(SongLyricFill::getGapsJson,
                        shapeMatches(cleanLines, gaps) ? LyricFillStore.toJson(gaps) : null)
                .eq(SongLyricFill::getId, row.getId());
        fillMapper.update(null, update);
        hookCorpusPairs(row.getId(), row.getOriginalId(), tracks, selected,
                cleanLines, cleanFilled, gaps);
        return resolve(row.getOriginalId(), row.getId(), null, null, false);
    }

    // ==================== 语料配对回流（钩子，填词助手设计 §5.6） ====================

    /**
     * 保存成功后把「整句填满」的句 diff 式写进 {@code lyric_corpus_pair}。挂在 save 上
     * 等于每 5 秒自动保存都会走，所以：<b>整段 try/catch，异常只打日志绝不上抛</b>
     * （一次语料写入失败不该让用户的填词保存失败）；没有整句填满时也要同步一次空集 ——
     * 用户清空了已收过料的句时，对应的 pair 行要消失。
     *
     * <p>参数都是 save 里<b>算好的本次落库口径</b>（勾选轨 / 骨架 / 清洗后的分句与填词），
     * 钩子不回头读 row —— row 是保存开始时的快照，勾选轨一变它就跟导出 lrc 不同源了
     * （空位也一样：用前端这次传上来的，不去读 row 里那份旧的）。
     */
    private void hookCorpusPairs(Long fillId, Long originalId, List<FillTrack> tracks,
                                 List<Integer> selected, List<FillLine> lines,
                                 List<List<String>> filled, List<List<Boolean>> editedGaps) {
        try {
            List<Integer> fullIndexes = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                List<String> values = i < filled.size() ? filled.get(i) : List.of();
                if (LyricFillAligner.isLineFilled(lines.get(i), values)) {
                    fullIndexes.add(i);
                }
            }
            if (fullIndexes.isEmpty()) {
                corpusService.syncPairs(fillId, List.of());
                return;
            }
            // 新词句拼装与导出 lrc 同一份口径（lineText：按组去重、括号声部、- 剔除、
            // gap 空格还原）；gaps/brackets 现算一次共用。defaults 传空 —— 满句不会回落。
            if (selected.isEmpty()) {
                selected = allTrackIndices(tracks); // 库里勾选为空 = 全轨（同 open 的口径）
            }
            Map<Long, Long> onsets = new HashMap<>();
            for (FillTrack track : tracks) {
                for (int i = 0; i < track.notes().size(); i++) {
                    onsets.put(LyricFillAligner.key(track.trackIndex(), i),
                            track.notes().get(i).onset());
                }
            }
            List<LyricLine> lyricSource = readLrcLines(originalId);
            // 空位取与导出 lrc 同一份（前端刚编辑的优先，见 resolveGaps）—— 收进语料的
            // 新词句与导出的歌词才是同一句话。不读 row 里那份旧的（见上面的方法注释）
            List<List<Boolean>> gaps = resolveGaps(lines, editedGaps, null,
                    LyricFillAligner.gaps(tracks, selected, lines, lyricSource));
            List<List<Boolean>> brackets =
                    LyricFillAligner.brackets(tracks, selected, lines, lyricSource);
            List<CorpusService.PairDesired> desired = new ArrayList<>(fullIndexes.size());
            for (int i : fullIndexes) {
                String text = lineText(lines.get(i),
                        i < filled.size() ? filled.get(i) : List.of(), List.of(),
                        i < gaps.size() ? gaps.get(i) : List.of(),
                        i < brackets.size() ? brackets.get(i) : List.of(), onsets);
                if (StringUtils.isBlank(text)) {
                    continue;
                }
                desired.add(new CorpusService.PairDesired(i,
                        StringUtils.defaultString(lines.get(i).originalText()), text));
            }
            corpusService.syncPairs(fillId, desired);
        } catch (Exception e) {
            log.warn("语料配对回流失败（不影响填词保存）：fill {}", fillId, e);
        }
    }

    // ==================== 重新解析 ====================

    /**
     * 强制重读 svp 覆盖骨架（svp 改过时用）。已填内容按音符位置回指。
     *
     * <p>库里存的轨号可能是「svp 数组序」（本版之前解析出来的），重解析出来的却是「工程显示
     * 顺序」——先按轨名把勾选轨号与旧分句的轨号换成新轨号（{@link #remapTracks}），否则勾选
     * 与已填的字会落到别的轨上。
     */
    public LyricTemplate reparse(Long fillId) {
        SongLyricFill row = requireFill(fillId);
        SongOriginalSetting setting = requireSetting(row.getOriginalId());
        Path svpPath = requireSvpPath(row.getOriginalId());

        List<FillLine> oldLines = LyricFillStore.readLines(row.getLinesJson());
        List<List<String>> oldFilled = LyricFillStore.readFilled(row.getFilledJson());
        List<Integer> stored = parseIndices(row.getTrackIndices());
        List<FillTrack> tracks = LyricFillParser.parse(svpPath);
        // 旧骨架的轨号可能是「svp 数组序」（本版之前解析出来的，见 LyricFillParser 类头）——
        // 按轨名对回新轨号，勾选与已填内容才不会落到别的轨上
        Map<Integer, Integer> remap = remapTracks(LyricFillStore.readTracks(row.getNotesJson()), tracks);
        if (!remap.isEmpty()) {
            stored = stored.stream().filter(remap::containsKey).map(remap::get)
                    .filter(i -> i < tracks.size()).distinct().sorted().toList();
            oldLines = remapTrackIndices(oldLines, remap);
        }
        List<Integer> selected = stored.isEmpty() ? allTrackIndices(tracks) : stored;
        LyricFillAligner.SplitResult split = LyricFillAligner.split(tracks, selected,
                readLrcLines(row.getOriginalId()), null);
        List<List<String>> filled = carry(oldLines, oldFilled, split.lines());
        // 合唱重复组联动：旧值回指到展开后的槽位后，组内空格抄已填那组的字
        filled = LyricFillAligner.syncGroupCopies(split.lines(), filled, tracks);
        // 保留的旧填词可能落到新的延音格上，归一后再落库（见 syncDashes）
        List<FillLine> lines = LyricFillAligner.syncDashes(split.lines(), filled);

        var update = Wrappers.<SongLyricFill>lambdaUpdate()
                .set(SongLyricFill::getOriginalName, setting.getRawName())
                .set(SongLyricFill::getSvpPath, svpPath.toString())
                .set(SongLyricFill::getTrackIndices, join(selected))
                .set(SongLyricFill::getNotesJson, LyricFillStore.toJson(tracks))
                .set(SongLyricFill::getLinesJson, LyricFillStore.toJson(lines))
                .set(SongLyricFill::getFilledJson, LyricFillStore.toJson(filled))
                // 骨架整个重建了，旧空位对不上新一轮的槽位 —— 清掉，回到现算
                .set(SongLyricFill::getGapsJson, null)
                .eq(SongLyricFill::getId, row.getId());
        fillMapper.update(null, update);
        row.setSvpPath(svpPath.toString());
        return template(row, setting, tracks, lines, filled, split.defaults(), split.gaps(),
                LyricFillAligner.brackets(tracks, selected, lines,
                        readLrcLines(row.getOriginalId())),
                split.pinyin(), selected, split.offset(),
                lyricFileName(row.getOriginalId()), false, split.notice());
    }

    // ==================== 导出 ====================

    /**
     * 导出。
     *
     * @param type   {@code text} = 回填模板的逐 note 文本；{@code lrc} = 歌词头 + 正文
     * @param offsetMs 写进 lrc 头的 {@code [offset:±ms]}，null 不写（只对 lrc 有意义）
     * @param lines / filled 前端当前（可能未保存）的编辑状态；传 null 就用库里的
     * @param gaps   同上，前端当前的视觉空位；形状对不上就退用库里的、再退回现算
     *               （{@link #resolveGaps}）—— 导出的 lrc 与页面上看到的是同一份空格
     * @param swaps  多音字的回填替换：「轨号:音符下标」→ 单音字。只影响回填模板文本
     *               （SynthV 照单音字注音不会错），歌词本体不动
     * @param pinyin 「导出拼音」开关：回填模板文本里的汉字再转成拼音（多音字取常用读音，
     *               回填替换过的按替换字读）。lrc 不受影响 —— 它是给人看的歌词
     */
    public ExportResult export(Long fillId, String type, Integer offsetMs,
                               List<FillLine> lines, List<List<String>> filled,
                               Map<String, String> swaps, boolean pinyin) {
        return export(fillId, type, offsetMs, lines, filled, null, swaps, pinyin);
    }

    /** 带视觉空位的重载（页面传了当前状态就用它，见 {@link #resolveGaps}）。 */
    public ExportResult export(Long fillId, String type, Integer offsetMs,
                               List<FillLine> lines, List<List<String>> filled,
                               List<List<Boolean>> gaps,
                               Map<String, String> swaps, boolean pinyin) {
        SongLyricFill row = requireFill(fillId);
        SongOriginalSetting setting = requireSetting(row.getOriginalId());
        List<FillTrack> tracks = LyricFillStore.readTracks(row.getNotesJson());
        Map<Long, String> swapByNote = new HashMap<>();
        if (swaps != null) {
            swaps.forEach((k, v) -> {
                int sep = k.indexOf(':');
                if (sep > 0) {
                    swapByNote.put(LyricFillAligner.key(
                            Integer.parseInt(k.substring(0, sep)),
                            Integer.parseInt(k.substring(sep + 1))), v);
                }
            });
        }

        List<FillLine> useLines = (lines == null || lines.isEmpty())
                ? LyricFillStore.readLines(row.getLinesJson()) : lines;
        List<List<String>> useFilled = carry(useLines, filled == null
                ? LyricFillStore.readFilled(row.getFilledJson()) : filled, useLines);
        // 合唱重复组联动：空槽从另一组抄值（前端已实时复制，这里兜旧数据 / 直调），
        // 否则导出的回填模板里合唱轨会缺字
        useFilled = LyricFillAligner.syncGroupCopies(useLines, useFilled, tracks);
        Map<Long, String> filledByNote = LyricFillAligner.filledByNote(useLines, useFilled);

        // 拼音模板匹配出的汉字（默认词）：没填到的音符，导出时优先用它回填，而不是原来的拼音音节
        List<Integer> selected = parseIndices(row.getTrackIndices());
        if (selected.isEmpty()) {
            selected = allTrackIndices(tracks);
        }
        List<LyricLine> lyricSource = readLrcLines(row.getOriginalId());
        List<List<String>> defaults = LyricFillAligner.defaults(tracks, selected, useLines, lyricSource);
        // 歌词句内空格（视觉空位）：lrc 导出时在空位处还原空格，保持原歌词的断句观感。
        // 页面上编辑过的（gaps_json）优先 —— 与页面上看到的、与保存的同一份
        List<List<Boolean>> useGaps = resolveGaps(useLines, gaps, row.getGapsJson(),
                LyricFillAligner.gaps(tracks, selected, useLines, lyricSource));
        // 括号声部：原歌词是「A（B）」双声部的句子，导出写回括号形式
        List<List<Boolean>> brackets =
                LyricFillAligner.brackets(tracks, selected, useLines, lyricSource);
        Map<Long, String> defaultByNote = LyricFillAligner.filledByNote(useLines, defaults);

        if ("lrc".equalsIgnoreCase(type)) {
            return new ExportResult("lrc",
                    buildLrc(setting, tracks, useLines, useFilled, defaults, useGaps, brackets,
                            displayName(row), properties.getFillSignature(), offsetMs),
                    List.of());
        }
        return new ExportResult("text", null,
                buildTrackTexts(tracks, filledByNote, defaultByNote, swapByNote, pinyin));
    }

    /**
     * 回填模板文本：按轨各一份，逐 note 一个 token、**空格分隔**（{@code br}→br、
     * {@code -}→-、{@code 0}→0、汉字/英文→填的词），整条轨就是一行。
     *
     * <p><b>先查填词表再看槽位类型</b>：被点开的延音（界面上把 {@code -} 槽位激活）填了字，
     * 在 {@code lines} 里是 {@code HANZI}，但骨架里那个音符仍是 {@code DASH} ——
     * 先按骨架类型分支会把它写回 {@code -}、把填的字吞掉。没填到才回落。
     *
     * <p><b>回落顺序</b>：回填替换字（多音字建议，{@code swapByNote}）→ 填的词 → 拼音模板
     * 匹配出的汉字（{@code defaultByNote}，见 {@link #export}）→ 原词（{@code -} / {@code br}
     * / 拼音音节）。替换字排最前：它就是为「让 SynthV 注对音」准备的，排最前才起作用。
     * 带喉塞音标记的音符（svp 里 {@code '曾} 一类）无论值来自哪一层，最后都拼回 {@code '}
     * 前缀（填「啊」导出 {@code '啊}），lrc 歌词则不含它。
     */
    static List<TrackText> buildTrackTexts(List<FillTrack> tracks, Map<Long, String> filledByNote,
                                                   Map<Long, String> defaultByNote,
                                                   Map<Long, String> swapByNote) {
        return buildTrackTexts(tracks, filledByNote, defaultByNote, swapByNote, false);
    }

    /**
     * 带「导出拼音」开关的重载：{@code pinyin=true} 时每个 token 里的汉字再经
     * {@link PinyinUtil#toPinyin} 转成无声调拼音（多音字取常用读音；上一步已经换成替换字的
     * 按替换字读，所以回填替换在拼音导出里照样生效）。
     *
     * <p>转换放在**最后一步**（喉塞音前缀拼好之后）：{@code '曾} → {@code 'zeng}，撇号原样留着
     * —— 它表达的是喉塞起音，不该被当成汉字的一部分。{@code -} / {@code br} 与英文词里没有
     * 汉字，原样输出。
     */
    static List<TrackText> buildTrackTexts(List<FillTrack> tracks, Map<Long, String> filledByNote,
                                                   Map<Long, String> defaultByNote,
                                                   Map<Long, String> swapByNote, boolean pinyin) {
        List<TrackText> result = new ArrayList<>(tracks.size());
        for (FillTrack track : tracks) {
            List<FillNote> notes = track.notes();
            List<String> tokens = new ArrayList<>(notes.size());
            for (int i = 0; i < notes.size(); i++) {
                FillNote note = notes.get(i);
                long k = LyricFillAligner.key(track.trackIndex(), i);
                String value = swapByNote.get(k);
                if (value == null) {
                    value = filledByNote.get(k);
                }
                if (value == null) {
                    value = defaultByNote.get(k);
                }
                if (value == null) {
                    value = switch (note.slotType()) {
                        case DASH -> "-";
                        case BREATH -> "br";
                        case ZERO -> "0";
                        // 没填到的音符保留原词，避免「只填了一半」时把另一轨的词抹掉
                        default -> note.lyrics();
                    };
                }
                // 喉塞音标记拼回前缀（'曾 → 填「啊」导出 '啊）：SynthV 靠它保持喉塞起音，
                // 不论值来自替换字 / 填的词 / 匹配汉字还是原词回落
                if (note.glottal() && value != null) {
                    value = "'" + value;
                }
                tokens.add(pinyin ? PinyinUtil.toPinyin(value == null ? "" : value)
                        : (value == null ? "" : value));
            }
            // 空格分隔、整轨一行（token 本身不含空白，tokenize 就是按空白切的）
            result.add(new TrackText(track.trackIndex(), track.trackName(), String.join(" ", tokens)));
        }
        return result;
    }

    /**
     * lrc：歌词头（{@code [ti:]}，可选 {@code [offset:±ms]}）+ 一句一行。
     *
     * <p><b>空白期补空行</b>：相邻两句之间如果超过 {@link #LRC_GAP_THRESHOLD_BLICK}
     *（1 秒）没人唱，就在前一句后面插一个<b>只有时间戳、没有词</b>的行，时间 = 前一句
     * <b>最后一个字唱完</b>的时刻（该句最后一个非换气槽位的音符 {@code onset + duration}）。
     * 这是原曲 lrc 的既有写法（模板目录下的 demo 歌词里就有），播放器读到这行会把歌词清空，
     * 长间奏期间不至于一直挂着上一句。**结尾同理**：最后一句唱完到<b>全曲结束</b>
     *（所有轨里最晚一个音符的结束）超过 1 秒也补一个空行。
     *
     * <p><b>曲终恒定补一行</b>：不论上面那一支触没触发，末尾都再补一行只有时间戳的空行，
     * 时间 = 最后一个音符结束 + {@link #LRC_TAIL_BLANK_SECONDS} 秒（按模板曲速折算，见 {@link #inBlick}）。
     * 末句尾音就是全曲最后一个
     * 音符（最常见的情形）时上面那一支本就不触发，播放器会把最后一句一直挂到曲终 —— 这一行
     * 就是给它清屏用的。全曲一个音符都没有（{@code songEnd == null}）时不补。
     *
     * <p>包级可见（不是 {@code private}）：只为了让同包单测直接调，生产入口只有 {@link #export}。
     */
    static String buildLrc(SongOriginalSetting setting, List<FillTrack> tracks,
                           List<FillLine> lines, List<List<String>> filled, Integer offsetMs) {
        return buildLrc(setting, tracks, lines, filled, List.of(), offsetMs);
    }

    /** 带默认词（拼音模板匹配出的汉字）的重载：没填到的槽位回落匹配汉字，再回落原词。 */
    static String buildLrc(SongOriginalSetting setting, List<FillTrack> tracks,
                           List<FillLine> lines, List<List<String>> filled,
                           List<List<String>> defaults, Integer offsetMs) {
        return buildLrc(setting, tracks, lines, filled, defaults, List.of(), offsetMs);
    }

    /**
     * 带默认词 + 视觉空位的重载：{@code gaps} 与 {@code lines}/{@code filled} 同形状，
     * {@code true} = 该槽位之后是歌词句内空格，导出文本在空位处还原一个空格。
     */
    static String buildLrc(SongOriginalSetting setting, List<FillTrack> tracks,
                           List<FillLine> lines, List<List<String>> filled,
                           List<List<String>> defaults, List<List<Boolean>> gaps, Integer offsetMs) {
        return buildLrc(setting, tracks, lines, filled, defaults, gaps, List.of(), offsetMs);
    }

    /**
     * 带括号声部标记的重载：{@code brackets} 与 {@code lines}/{@code filled} 同形状，
     * {@code true} = 该槽位唱的是原歌词行尾括号里的那句，导出拼成「主流（括号流）」。
     */
    static String buildLrc(SongOriginalSetting setting, List<FillTrack> tracks,
                           List<FillLine> lines, List<List<String>> filled,
                           List<List<String>> defaults, List<List<Boolean>> gaps,
                           List<List<Boolean>> brackets, Integer offsetMs) {
        return buildLrc(setting, tracks, lines, filled, defaults, gaps, brackets, null, null, offsetMs);
    }

    /**
     * 真正拼 lrc 的那个重载（上面的四个重载都汇到这儿），比它多收两个歌词头字段。
     *
     * <p><b>歌词头</b>（作者 2026-10-01 定，见 {@code docs/填词工具设计.md} §9）：
     * <pre>
     * [ti:原曲名]      ← 一直都有
     * [al:填词名]      ← 有填词名才写（{@code displayName} 兜底后恒非空）
     * [by:填词署名]    ← 配了「填词署名」才写，见 {@code SongProperties#fillSignature}
     * [offset:±ms]     ← 非 0 才写，原样
     * </pre>
     * 两个都可以为 {@code null} —— 那正是上面四个重载转调时传的值，输出与加这两行之前
     * <b>逐字相同</b>（约 40 处既有单测走的就是那条路，所以它们一条都不用改）。
     *
     * <p><b>只有 lrc 有头</b>：回填模板文本({@link #buildTrackTexts})是喂给 SynthV 的音符
     * 数据，多一行字就唱不进去了，一个字都不加。
     */
    static String buildLrc(SongOriginalSetting setting, List<FillTrack> tracks,
                           List<FillLine> lines, List<List<String>> filled,
                           List<List<String>> defaults, List<List<Boolean>> gaps,
                           List<List<Boolean>> brackets, String fillName, String signature,
                           Integer offsetMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("[ti:").append(tagValue(setting.getRawName())).append("]\n");
        if (StringUtils.isNotBlank(fillName)) {
            sb.append("[al:").append(tagValue(fillName)).append("]\n");
        }
        if (StringUtils.isNotBlank(signature)) {
            sb.append("[by:").append(tagValue(signature)).append("]\n");
        }
        if (offsetMs != null && offsetMs != 0) {
            sb.append("[offset:").append(offsetMs > 0 ? "+" : "").append(offsetMs).append("]\n");
        }
        Map<Long, FillNote> notes = notesByKey(tracks);
        Long songEnd = songEndOnset(tracks);
        // (轨, 音符) → onset：导出去重（被包含组只导一份）按位置配对用
        Map<Long, Long> onsets = new HashMap<>();
        notes.forEach((k, v) -> onsets.put(k, v.onset()));
        // 秒换算按模板曲速（bpm 参与折算，见 LyricFillParser.secondsOf）
        Double bpm = setting.getBpm() == null ? null : setting.getBpm().doubleValue();
        for (int i = 0; i < lines.size(); i++) {
            FillLine line = lines.get(i);
            String text = lineText(line, i < filled.size() ? filled.get(i) : List.of(),
                    i < defaults.size() ? defaults.get(i) : List.of(),
                    i < gaps.size() ? gaps.get(i) : List.of(),
                    i < brackets.size() ? brackets.get(i) : List.of(), onsets);
            // 时间戳用句首 note 的 onset（svp 时间轴），不是原 lrc 的时间
            sb.append(LyricFillParser.lrcTime(LyricFillParser.secondsOf(line.startOnset(), bpm)))
                    .append(StringUtils.defaultString(text)).append('\n');

            // 后一句的起点；最后一句的「后一句」就是全曲结束（结尾的间奏同样补空行）
            Long nextStart = i + 1 < lines.size() ? lines.get(i + 1).startOnset() : songEnd;
            Long sungEnd = sungEndOnset(line, notes);
            if (sungEnd != null && nextStart != null
                    && nextStart - sungEnd > LRC_GAP_THRESHOLD_BLICK) {
                sb.append(LyricFillParser.lrcTime(LyricFillParser.secondsOf(sungEnd, bpm))).append('\n');
            }
        }
        // 曲终恒定补一行空歌词：时间 = 最后一个音符结束 + 1 秒（2026-09-28 口径）。
        // 与上面「结尾间奏」那一支不是一回事 —— 那一支管的是「末句唱完到全曲还有一段没人唱」，
        // 这一行是「整曲确实唱完了」之后的清屏标记（最后一句的尾音拖得很长、或尾音就是最后一个
        // 音符时，上面那一支不会触发，播放器会把最后一句一直挂到曲终）。
        if (songEnd != null) {
            sb.append(LyricFillParser.lrcTime(
                            LyricFillParser.secondsOf(
                                    songEnd + inBlick(LRC_TAIL_BLANK_SECONDS, bpm), bpm)))
                    .append('\n');
        }
        return sb.toString();
    }

    /**
     * lrc 标签的值：折掉换行、换掉 {@code ]}。
     *
     * <p>两个都是<b>真会写出畸形 lrc</b> 的字符，不是凭空设想的边界 —— 标签值有两个来源，
     * 一个是<b>库里的原曲名</b>（{@code SongGroupNameParser} 从目录名解析，目录名里什么都有），
     * 一个是<b>页面上手打的署名</b>。换行会把标签断成两行，后半截没有时间戳、播放器按垃圾行
     * 丢掉（`[by:甲` / `某]` 两行）；{@code ]} 会提前闭合标签，后面的字全掉出标签外。
     * 换成一个中文右括号是<b>有意</b>的：署名里真出现方括号（「某某[ver.2]」）时，留住内容
     * 比留对称的括号重要 —— 这是给人看的署名，不是机器解析的字段。
     */
    private static String tagValue(String value) {
        return StringUtils.defaultString(value)
                .replaceAll("[\\r\\n]+", " ")
                .replace(']', '）')
                .trim();
    }

    /**
     * {@code seconds} 秒折成 blick（{@code LyricFillParser#secondsOf} 的逆运算）。
     * <b>必须按模板曲速折算</b>：svp 的 blick 是「1 拍 = 705600000」（与 bpm 无关），
     * {@link LyricFillParser#BLICK_PER_SECOND} 那个数只是 120bpm 下 1 秒的长度 ——
     * 拿它当「1 秒」用，60bpm 的模板上就是 2 秒。{@code bpm} 为 null / 非正时按 120 兜底，
     * 与 {@code secondsOf} 同一口径。
     */
    private static long inBlick(double seconds, Double bpm) {
        double effective = (bpm == null || bpm <= 0) ? 120.0 : bpm;
        return Math.round(LyricFillParser.BLICK_PER_SECOND * effective / 120.0 * seconds);
    }

    /** 全曲结束时刻（blick）：所有轨里最晚一个音符的结束时间；一个音符都没有时 {@code null}。 */
    private static Long songEndOnset(List<FillTrack> tracks) {
        Long end = null;
        for (FillTrack track : tracks) {
            for (FillNote note : track.notes()) {
                long stop = note.onset() + note.duration();
                if (end == null || stop > end) {
                    end = stop;
                }
            }
        }
        return end;
    }

    /**
     * 一句「最后一个字唱完」的时刻（blick）：最后一个非换气、非静音槽位的音符结束时间。
     * 该句没有任何可回指的音符时返回 {@code null}（调用方跳过空行）。
     */
    private static Long sungEndOnset(FillLine line, Map<Long, FillNote> notes) {
        Long end = null;
        for (FillSlot slot : line.slots()) {
            if (slot.slotType() == SlotType.BREATH || slot.slotType() == SlotType.ZERO) {
                continue;   // 换气 / 静音占位都不出声，不该算成「唱到哪」
            }
            FillNote note = notes.get(LyricFillAligner.key(slot.trackIndex(), slot.noteIndex()));
            if (note == null) {
                continue;
            }
            long stop = note.onset() + note.duration();
            if (end == null || stop > end) {
                end = stop;
            }
        }
        return end;
    }

    /** {@code key(trackIndex, noteIndex) → 音符}，用于把槽位换回 onset / duration。 */
    private static Map<Long, FillNote> notesByKey(List<FillTrack> tracks) {
        Map<Long, FillNote> map = new HashMap<>();
        for (FillTrack track : tracks) {
            List<FillNote> notes = track.notes();
            for (int i = 0; i < notes.size(); i++) {
                map.put(LyricFillAligner.key(track.trackIndex(), i), notes.get(i));
            }
        }
        return map;
    }

    /**
     * 一句的歌词文本：填过的槽位用新词，没填到的先回落拼音模板匹配出的汉字（{@code defaults}），
     * 再回落原词（{@code -} / {@code br} 不写进去，与回填模板文本同口径）。整句都没填、也没有
     * 匹配汉字时结果正好等于 {@code originalText}。{@code gaps} 为 true 的槽位（歌词句内空格）
     * 在写完它的词之后补一个空格，保持原歌词「五百年前一场疯 腾霄又是孙悟空」的断句观感。
     *
     * <p><b>合唱去重</b>（需求 3）：句内有多个声部组时，音符完全被另一组包含的组只导出长的那组
     *（两组完全相同也只导一份），剩下互不包含的组（同轨的不同段）用空格连接。
     * <b>完全相同的两组只能丢一份</b>：双向互相包含时保留组号小的那份——不然两组都被判
     * 「被包含」而全丢，整行导出成空行（实测《免我蹉跎苦》整轨复制成「副本」的工程，
     * 每句两组逐音符完全相同，导出的 lrc 全是只有时间戳的空行）。
     *
     * <p><b>括号声部</b>：原歌词是「A（B）」双声部时（{@code brackets} 标记的槽位），
     * 主声部照上面的规则照旧拼、括号声部拼成后半段，写成 {@code A（B）}；一句里只有括号声部
     * 时写成 {@code （B）}。一段里带没带括号按<b>组</b>算（组内任一格标了就是括号声部）——
     * 组就是界面上的一个声部行，一段一半带括号的组不存在。
     */
    private static String lineText(FillLine line, List<String> values, List<String> defaults,
                                   List<Boolean> gaps, List<Boolean> brackets,
                                   Map<Long, Long> onsets) {
        List<Integer> groups = line.groups();
        if (groups == null || groups.stream().distinct().count() < 2) {
            return lineText(line.slots(), indexes(line.slots().size()),
                    values, defaults, gaps);
        }
        Map<Integer, List<Integer>> byGroup = LyricFillAligner.groupIndexes(line);
        List<Integer> kept = new ArrayList<>();
        for (var entry : byGroup.entrySet()) {
            boolean covered = false;
            for (var other : byGroup.entrySet()) {
                if (other.getKey().equals(entry.getKey())
                        || !LyricFillAligner.groupCoveredBy(line.slots(), entry.getValue(),
                        other.getValue(), onsets)) {
                    continue;
                }
                // 被对方包含；但若对方也被自己包含（两组完全相同），保留组号小的那份
                if (LyricFillAligner.groupCoveredBy(line.slots(), other.getValue(),
                        entry.getValue(), onsets) && other.getKey() > entry.getKey()) {
                    continue;
                }
                covered = true;
                break;
            }
            if (!covered) {
                kept.add(entry.getKey());
            }
        }
        StringBuilder main = new StringBuilder();
        StringBuilder sub = new StringBuilder();
        for (int group : kept) {
            List<Integer> indexes = byGroup.get(group);
            StringBuilder target = bracketGroup(line, indexes, brackets) ? sub : main;
            if (target.length() > 0) {
                target.append(' ');
            }
            target.append(lineText(line.slots(), indexes, values, defaults, gaps));
        }
        if (sub.length() == 0) {
            return main.toString();
        }
        return main + "（" + sub + "）";
    }

    /** 这个声部组是不是括号声部：组内任一槽位被标了就算（见 {@link #lineText}）。 */
    private static boolean bracketGroup(FillLine line, List<Integer> indexes,
                                        List<Boolean> brackets) {
        for (int k : indexes) {
            if (k < brackets.size() && Boolean.TRUE.equals(brackets.get(k))) {
                return true;
            }
        }
        return false;
    }

    /** 连续的槽位序号 0..size-1（单组 / 旧数据的整句拼装用）。 */
    private static List<Integer> indexes(int size) {
        List<Integer> all = new ArrayList<>(size);
        for (int k = 0; k < size; k++) {
            all.add(k);
        }
        return all;
    }

    /**
     * 一段槽位序（整句或某个声部组）的歌词拼装：填的词 → 匹配汉字 → 原词，gap 处补空格。
     *
     * <p>「-」<b>不进歌词</b>——不论来源（批量填词写进去的占位符、匹配汉字、延音原词），
     * 拼出来的每一格逐字剔除它。占位符的语义就是「占住位置、不出声」：批量填词把「-」当普通
     * 单元填进格子（见 {@code songfill.js} 的 {@code batchUnits}），导出时它必须在歌词里消失，
     * 否则会唱成个字。判定放在**每格拼完之后**而不是取值之前，是为了让「这一格有没有产出」
     * 与剔除后的文本一致 —— 纯占位符的格子不产生字符，也就不该补 gap 空格（唯一的例外是
     * 延音格，见 {@link #wroteText}）。
     * 是「<b>所有</b> `-`」的字面口径：英文词里当连字符用的（{@code well-known}）照样会被删成
     * {@code wellknown} —— 填词语料里 `-` 只有延音 / 占位符一种身份，故按字面走。
     *
     * <p>换气（br）不进歌词，但它是无歌词匹配时的空位锚点（{@code breathGaps}）：句中 br
     * 的空位在<b>下一个字进来前</b>落地成句内空格 —— 这样句首 br（前面还没有字）不产生
     * 行首空格、句尾 br 不留下尾空格；已有空格（歌词空位刚补过）也不再叠第二个。
     * <b>静音占位（{@code 0}）直接跳过</b>：与 {@code -} / {@code br} 一样不出现在 lrc 里
     * （回填模板文本那边才写回，见 {@link #buildTrackTexts}），但它没有空位语义。
     */
    private static String lineText(List<FillSlot> slots, List<Integer> indexes,
                                   List<String> values, List<String> defaults, List<Boolean> gaps) {
        StringBuilder sb = new StringBuilder();
        boolean pendingSpace = false;   // br 空位：等下一个真的写字的格子进来前补
        for (int k : indexes) {
            FillSlot slot = slots.get(k);
            if (slot.slotType() == SlotType.BREATH || slot.slotType() == SlotType.ZERO) {
                // br 顺带当空位锚点（见 javadoc）；静音占位（0）没有空位语义、直接跳过
                if (slot.slotType() == SlotType.BREATH
                        && k < gaps.size() && Boolean.TRUE.equals(gaps.get(k))) {
                    pendingSpace = true;
                }
                continue;
            }
            int before = sb.length();
            String cell = null;
            String value = k < values.size() ? values.get(k) : null;
            if (StringUtils.isNotBlank(value)) {
                cell = value;
            } else {
                String def = k < defaults.size() ? defaults.get(k) : null;
                if (StringUtils.isNotBlank(def)) {
                    cell = def;
                } else if (slot.slotType().fillable() && slot.original() != null) {
                    cell = slot.original();
                }
            }
            if (cell != null) {
                if (pendingSpace && sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
                    sb.append(' ');
                }
                pendingSpace = false;
                sb.append(cell.replace("-", ""));
            }
            if (wroteText(sb.length() - before, slot, before)
                    && k < gaps.size() && Boolean.TRUE.equals(gaps.get(k))) {
                sb.append(' ');
            }
        }
        return sb.toString();
    }

    /**
     * 这一格的空位（gap）该不该补一个空格。两种算「该补」：
     * <ul>
     *   <li>{@code produced > 0}：本格往歌词里写出了字（填的词 / 匹配汉字 / 原词，剔除 {@code -}
     *       之后仍有字符）—— 字后补空格，旧口径；</li>
     *   <li>本格是<b>延音</b>（{@code -}，自己不出字）而句里已经写了字（{@code length > 0}）：
     *       空位标记被推到了延音链尾（{@code LyricFillAligner#prolongationEnd}），
     *       空格该补在整串延音<b>之后</b> —— 字与它的 {@code -} 之间不许插空格。句首不补
     *       （与 br 空位同口径：不产生行首空格）。</li>
     * </ul>
     * 其余「没产出」的格（换气 / 静音占位 / 被剔光的纯占位符）不补：空格的语义是「这个词唱完了」
     * 的分隔，不该挂在没出声的格上。
     */
    private static boolean wroteText(int produced, FillSlot slot, int length) {
        return produced > 0 || (slot.slotType() == SlotType.DASH && length > 0);
    }

    // ==================== 每句的 lrc 文本（导出 lrc 口径，原词 / 填词两份） ====================

    /**
     * 一句的<b>两份</b>导出 lrc 文本（同一次拼装算出来，免得两份口径漂移）：
     * {@code original} = 原词那一份（没填过的样子），{@code filled} = 当前填词那一份。
     */
    public record LrcTexts(List<String> original, List<String> filled) {
    }

    /**
     * 每句的「原词 / 当前填词」按导出 lrc 的口径合并后的文本，与 {@code lines} 同形状、同序。
     *
     * <p>AI 填词的方案表用它给模型「原句」与字数（填词助手设计 §6.1），以及给「填词」列
     * 一个与导出同一口径的默认值：一句里有多个音轨时，导出 lrc 的 {@link #lineText} 会把它们
     * 去重 / 合并成<b>一行</b>（被包含的声部不重复导出、括号声部写成 {@code A（B）}），
     * 模型看到的字数必须与最终进格的那一行同口径，否则「标注的字数」与「回填时吃掉的格子数」
     * 对不上。
     *
     * <p><b>原词那一份</b>：{@code values} 传空，导出时它回落到 {@code defaults}（demo 歌词
     * 匹配出的词）再到原词 —— 正是没填过的样子。{@code defaults} 照导出那份给：汉字模板只给
     * 其中的拼音格（原词行本来就写着汉字，回了也是白回），拼音模板给每一格。
     *
     * <p><b>{@code defaults} 不能传空</b>（试过）：拼音模板的槽位原词是拼音音节，回落原词会把
     * 整句拼成一串粘在一起的拼音（{@code chunfengyou}）——{@link LyricFillAligner#tokenize}
     * 里含 ASCII 字母的 token <b>整串只算 1 个单元</b>，句子的「字数」于是变成 1，标注与校验
     * 全崩。传了 defaults 才是「原句」该有的样子（歌词里的汉字）。
     *
     * <p><b>填词那一份</b>：把当前填词当 {@code values} 传进去（同一条链、同一份拼装，不另写），
     * <b>整句一个字都没填的句给空串</b>（前端拿它当输入框默认值，回落成原词就变成「看着像已经
     * 填过了」）；只填了一部分的句按导出口径走 —— 没填的格子照旧回落原词 / 匹配词。
     *
     * <p>{@code -}（延音占位）在这里就被剔掉了：AI 那条路恒走 {@code applyByLine(rows, true)}
     * （忽略延音、延音格不填字），两边天然一致 —— 所以「取消勾选忽略延音」那条批量填词路径上
     * 的差异<b>与 AI 填词无关，不用来修这里</b>。
     * gaps / brackets / defaults 与 {@link #hookCorpusPairs} 同款现算（同一套口径，不另写一份）。
     */
    public LrcTexts lrcTexts(SongLyricFill row) {
        List<FillTrack> tracks = LyricFillStore.readTracks(row.getNotesJson());
        List<Integer> selected = parseIndices(row.getTrackIndices());
        if (selected.isEmpty()) {
            selected = allTrackIndices(tracks);   // 库里勾选为空 = 全轨（同 open 的口径）
        }
        return lrcTexts(tracks, selected, LyricFillStore.readLines(row.getLinesJson()),
                readLrcLines(row.getOriginalId()), LyricFillStore.readFilled(row.getFilledJson()));
    }

    /**
     * {@link #lrcTexts(SongLyricFill)} 的纯计算部分（歌词行 / 当前填词直接给，不读磁盘、可单测）。
     *
     * @param filled 当前填词，与 {@code lines} 同形状（下标 = 句内槽位序）；缺 / 空 = 没填过
     */
    public static LrcTexts lrcTexts(List<FillTrack> tracks, List<Integer> selected,
                                    List<FillLine> lines, List<LyricLine> lyricSource,
                                    List<List<String>> filled) {
        if (lines == null || lines.isEmpty()) {
            return new LrcTexts(List.of(), List.of());
        }
        List<List<Boolean>> gaps = LyricFillAligner.gaps(tracks, selected, lines, lyricSource);
        List<List<Boolean>> brackets =
                LyricFillAligner.brackets(tracks, selected, lines, lyricSource);
        List<List<String>> defaults =
                LyricFillAligner.defaults(tracks, selected, lines, lyricSource);
        Map<Long, Long> onsets = new HashMap<>();
        for (FillTrack track : tracks) {
            for (int i = 0; i < track.notes().size(); i++) {
                onsets.put(LyricFillAligner.key(track.trackIndex(), i), track.notes().get(i).onset());
            }
        }
        List<String> original = new ArrayList<>(lines.size());
        List<String> filledOut = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            FillLine line = lines.get(i);
            List<String> def = i < defaults.size() ? defaults.get(i) : List.of();
            List<Boolean> gp = i < gaps.size() ? gaps.get(i) : List.of();
            List<Boolean> br = i < brackets.size() ? brackets.get(i) : List.of();
            original.add(lineText(line, List.of(), def, gp, br, onsets));
            List<String> values = filled != null && i < filled.size() ? filled.get(i) : List.of();
            // 整句没填过 → 空串（半填的句照导出口径走：没填的格子回落原词 / 匹配词）
            filledOut.add(values.stream().anyMatch(StringUtils::isNotBlank)
                    ? lineText(line, values, def, gp, br, onsets) : "");
        }
        return new LrcTexts(original, filledOut);
    }

    // ==================== 内部 ====================

    private SongOriginalSetting requireSetting(Long originalId) {
        if (originalId == null) {
            throw new IllegalArgumentException("原曲 id 不能为空");
        }
        SongOriginalSetting setting = originalMapper.selectById(originalId);
        if (setting == null) {
            throw new IllegalArgumentException("原曲设置不存在（id=" + originalId + "）");
        }
        return setting;
    }

    private SongLyricFill requireFill(Long fillId) {
        if (fillId == null) {
            throw new IllegalArgumentException("填词项目 id 不能为空");
        }
        SongLyricFill row = fillMapper.selectById(fillId);
        if (row == null) {
            throw new IllegalArgumentException("填词项目不存在（id=" + fillId + "）");
        }
        return row;
    }

    /** 该原曲最近建的一份填词（按 id 最大）；一份都没有时 null。 */
    private SongLyricFill latest(Long originalId) {
        return fillMapper.selectOne(Wrappers.<SongLyricFill>lambdaQuery()
                .eq(SongLyricFill::getOriginalId, originalId)
                .orderByDesc(SongLyricFill::getId)
                .last("LIMIT 1"));
    }

    /** 自动名：该原曲已有几份就叫「填词 N+1」。 */
    private String defaultName(Long originalId) {
        Long count = fillMapper.selectCount(Wrappers.<SongLyricFill>lambdaQuery()
                .eq(SongLyricFill::getOriginalId, originalId));
        return "填词 " + ((count == null ? 0 : count) + 1);
    }

    private static String displayName(SongLyricFill row) {
        return StringUtils.defaultIfBlank(row.getName(), "填词");
    }

    private Path requireSvpPath(Long originalId) {
        Path svpPath = templateService.resolveSvpPath(originalId);
        if (svpPath == null) {
            throw new IllegalStateException("这一原曲没有可用的 svp 模板文件："
                    + "到原曲页的「编辑」弹窗里把 svp 指派到模板，或确认模板目录下确实有 .svp 文件");
        }
        return svpPath;
    }

    /** 分句用的歌词行。样例音频歌词优先，其次原曲歌词；没有 / 无时间轴时返回空（回落到 br + 空隙）。 */
    private List<LyricLine> readLrcLines(Long originalId) {
        Path lyric = templateService.resolveFillLyricPath(originalId);
        if (lyric == null) {
            return List.of();
        }
        LyricTextReader.Lyric parsed = LyricTextReader.read(lyric, lyric.getFileName().toString());
        return parsed.supported() && parsed.timed() ? parsed.lines() : List.of();
    }

    private String lyricFileName(Long originalId) {
        Path lyric = templateService.resolveFillLyricPath(originalId);
        return lyric == null ? null : lyric.getFileName().toString();
    }

    private static List<Integer> allTrackIndices(List<FillTrack> tracks) {
        return tracks.stream().map(FillTrack::trackIndex).toList();
    }

    private static String join(List<Integer> indices) {
        if (indices == null || indices.isEmpty()) {
            return null;
        }
        return indices.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }

    private static List<Integer> parseIndices(String text) {
        if (StringUtils.isBlank(text)) {
            return List.of();
        }
        List<Integer> result = new ArrayList<>();
        for (String piece : text.split(",")) {
            String trimmed = piece.trim();
            if (!trimmed.isEmpty()) {
                result.add(Integer.parseInt(trimmed));
            }
        }
        return result;
    }

    /**
     * 已填内容回指到新的分句：优先按音符位置（(轨, 音符) 键——句数 / 槽位数在声部组展开后
     * 会变，按序号对不上；按位置回指则合唱组两边的旧值都能对上）。旧分句为空（首次分句 /
     * 历史数据缺失）或音符已不存在（svp 改了）时留空。
     */
    private static List<List<String>> carry(List<FillLine> oldLines, List<List<String>> filled,
                                            List<FillLine> newLines) {
        Map<Long, String> oldByNote =
                oldLines == null ? Map.of() : LyricFillAligner.filledByNote(oldLines, filled);
        List<List<String>> result = new ArrayList<>(newLines.size());
        for (FillLine line : newLines) {
            List<String> values = new ArrayList<>(line.slots().size());
            for (FillSlot slot : line.slots()) {
                String old = oldByNote.get(LyricFillAligner.key(slot.trackIndex(), slot.noteIndex()));
                values.add(old != null ? old : "");
            }
            result.add(values);
        }
        return result;
    }

    /**
     * 旧骨架的轨号 → 重新解析后的轨号。
     *
     * <p><b>为什么需要</b>：{@link LyricFillParser} 现在按工程显示顺序（{@code dispOrder}）
     * 编号，而本版之前解析出来的库数据是按 svp {@code tracks[]} 数组序编的 —— 两者在实测
     * 工程里经常不一致（《栖凰》完全不同）。{@code reparse} 用新轨号重建骨架，若直接按
     * (轨, 音符) 回指旧值，值会落到<b>另一条轨</b>上（两套编号都是 0..N-1 的排列，(轨, 音符)
     * 键照样能命中，错得毫无痕迹）；勾选轨号同理。三级配对，稳的先用：
     *
     * <ol>
     *   <li><b>音符序列完全一致</b> —— svp 没改过时同一轨解析出来的音符逐字段相同（onset /
     *       时长 / 原词 / 类型），这是最强的身份。实测 24 个工程有<b>同名</b>歌唱轨
     *       （《不想长大》两条「填词」、两条「副歌」，《木偶戏DJ版》23 条轨里一半重名），
     *       只靠名字会把值配错轨，靠音符才不会。</li>
     *   <li><b>轨名</b> —— 音符对不上（svp 改过，或老数据是 blickOffset 修复之前解析的）时
     *       按名字配未占用的轨。</li>
     *   <li><b>剩余位次</b> —— 名字也缺失 / 对不上时，按新旧顺序把还没占用的轨号依次填上。</li>
     * </ol>
     *
     * @return 旧轨号 → 新轨号；旧骨架为空（老数据没存轨）时返回空表 = 不重排
     */
    static Map<Integer, Integer> remapTracks(List<FillTrack> oldTracks,
                                             List<FillTrack> newTracks) {
        Map<Integer, Integer> remap = new HashMap<>();
        if (oldTracks.isEmpty() || newTracks.isEmpty()) {
            return remap;
        }
        Set<Integer> used = new HashSet<>();
        List<FillTrack> rest = new ArrayList<>();
        for (FillTrack track : oldTracks) {
            FillTrack hit = null;
            for (FillTrack now : newTracks) {
                if (!used.contains(now.trackIndex()) && track.notes().equals(now.notes())) {
                    hit = now;
                    break;
                }
            }
            if (hit == null) {
                rest.add(track);
                continue;
            }
            remap.put(track.trackIndex(), hit.trackIndex());
            used.add(hit.trackIndex());
        }
        Map<String, Deque<Integer>> byName = new LinkedHashMap<>();
        for (FillTrack now : newTracks) {
            if (!used.contains(now.trackIndex()) && StringUtils.isNotBlank(now.trackName())) {
                byName.computeIfAbsent(now.trackName(), k -> new ArrayDeque<>())
                        .add(now.trackIndex());
            }
        }
        List<FillTrack> unmatched = new ArrayList<>();
        for (FillTrack track : rest) {
            Deque<Integer> queue = byName.get(track.trackName());
            if (queue == null || queue.isEmpty()) {
                unmatched.add(track);
                continue;
            }
            int now = queue.poll();
            remap.put(track.trackIndex(), now);
            used.add(now);
        }
        // 名字也配不上的按位次兜底：轨的集合是一样的，只差排列
        List<Integer> free = new ArrayList<>();
        for (FillTrack now : newTracks) {
            if (!used.contains(now.trackIndex())) {
                free.add(now.trackIndex());
            }
        }
        for (int i = 0; i < unmatched.size() && i < free.size(); i++) {
            remap.put(unmatched.get(i).trackIndex(), free.get(i));
        }
        return remap;
    }

    /** 旧分句的槽位轨号按 {@link #remapTracks} 的表就地换成新轨号（其余字段不动）。 */
    static List<FillLine> remapTrackIndices(List<FillLine> lines, Map<Integer, Integer> remap) {
        List<FillLine> out = new ArrayList<>(lines.size());
        for (FillLine line : lines) {
            List<FillSlot> slots = new ArrayList<>(line.slots().size());
            for (FillSlot slot : line.slots()) {
                Integer now = remap.get(slot.trackIndex());
                slots.add(now == null ? slot : new FillSlot(now, slot.noteIndex(),
                        slot.original(), slot.slotType(), slot.glottal()));
            }
            out.add(new FillLine(slots, line.originalText(), line.needCount(), line.startOnset(),
                    line.groups()));
        }
        return out;
    }

    /**
     * 组装下发给页面的骨架。{@code defaults} / {@code gaps} / {@code brackets} / {@code pinyin}
     * / {@code notice} 都是派生字段（每次现算、不落库），调用方各自算好传进来 —— 直读路径用
     * {@link LyricFillAligner} 的静态方法现算，重解析路径用 {@code split} 的结果。
     */
    private static LyricTemplate template(SongLyricFill row, SongOriginalSetting setting,
                                          List<FillTrack> tracks, List<FillLine> lines,
                                          List<List<String>> filled, List<List<String>> defaults,
                                          List<List<Boolean>> gaps, List<List<Boolean>> brackets,
                                          boolean pinyin, List<Integer> selected,
                                          Double offset, String lyricName, boolean fromCache,
                                          String notice) {
        return new LyricTemplate(row.getId(), displayName(row), row.getSvpPath(),
                setting.getRawName(), setting.getArtist(), setting.getBpm(), tracks,
                LyricFillAligner.syncDashes(lines, filled),
                filled, defaults, gaps, brackets, pinyin,
                PolyphoneHint.index(),
                selected, lyricName, offset, fromCache, notice);
    }
}
