package io.github.Nyameph.nyaentworks.script;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import io.github.Nyameph.nyaentworks.song.entity.SongOriginalSetting;
import io.github.Nyameph.nyaentworks.song.mapper.SongOriginalSettingMapper;
import io.github.Nyameph.nyaentworks.song.service.SongResourceSearchService;
import io.github.Nyameph.nyaentworks.common.fileop.FileOpRecorder;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpModule;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpSource;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 批量补全 {@code song_original_setting} 的 artist / 原曲 / 歌词（可执行脚本，非单元测试）。
 *
 * <p>搜索 / 下载 / 限流轮换逻辑已搬到 {@link SongResourceSearchService}，单曲「搜资源」
 * 按钮（{@code POST /api/song/template/search}）与本批量脚本共用同一个 {@code searchOne}，
 * 保证两条路径行为一致。本类只剩：
 * <ol>
 *   <li>读全部原曲行（{@code SongOriginalSettingMapper.selectList}）按 rawName 排序；</li>
 *   <li>跳过 {@link SongResourceSearchService#SKIP_HOURS} 小时内已经搜过的
 *       （{@link SongResourceSearchService#recentlySearched}）；</li>
 *   <li>逐行调 {@link SongResourceSearchService#searchOne}，全源限流时调
 *       {@link SongResourceSearchService#sleepAllSources} 休眠后再试。</li>
 * </ol>
 *
 * <p><b>用法</b>：改 {@link #fetch()} 里的开关（{@code justTest} / {@code limit} /
 * {@code onlyNames}）后跑 {@code ./mvnw test -Dtest=SongOriginalFetchUtil#fetch}。
 * 默认 {@code justTest=true} 只打印不落盘不写库；确认后再把 {@code justTest} 置
 * {@code false} 小样本试跑。不要上来就全量跑 —— 歌曲名匹配是启发式的，先看几行输出。
 */
@SpringBootTest
public class SongOriginalFetchUtil {

    @Autowired
    private SongOriginalSettingMapper originalMapper;

    @Autowired
    private SongResourceSearchService searchService;

    @Test
    public void fetch() {
        // 脚本侧的批次：落盘记录的 source=SCRIPT（页面那侧的批次在 SongTemplateSearchHandler）
        FileOpRecorder.batch(FileOpModule.SONG, FileOpSource.SCRIPT, null,
                () -> {
                    fetchInside();
                    return null;
                });
    }

    private void fetchInside() {
        // ===== 开关（改这里）=====
        boolean justTest = true;       // true = 只打印、不落盘不写库；false = 真正下载并回写
        int limit = 0;             // 只处理前 N 行（0 = 全部）
        Set<String> onlyNames = Set.of();
        // ======================

        List<SongOriginalSetting> rows = originalMapper.selectList(null).stream()
                .sorted(Comparator.comparing(r -> StringUtils.defaultString(r.getRawName(),
                        String.valueOf(r.getId()))))
                .collect(Collectors.toList());

        int total = 0;
        int skipped = 0;
        int artistFilled = 0, originalFilled = 0, lyricFilled = 0;
        int limited = 0;
        int processed = 0; // 自上次落库以来真正搜索过（未跳过）的行数
        for (SongOriginalSetting row : rows) {
            String rawName = StringUtils.defaultIfBlank(row.getRawName(), String.valueOf(row.getId()));
            if (!onlyNames.isEmpty() && !onlyNames.contains(rawName)) {
                continue;
            }
            if (limit > 0 && total >= limit) {
                break;
            }
            total++;

            if (searchService.recentlySearched(row)) {
                System.out.println(rawName + " →（" + SongResourceSearchService.SKIP_HOURS
                        + " 小时内搜过，跳过）");
                skipped++;
                continue;
            }

            SongResourceSearchService.SearchResult r =
                    searchService.searchOneDeferred(rawName, row.getArtist(), justTest);
            System.out.println(rawName + " →" + summarize(r));
            if (r.artist() != null) {
                artistFilled++;
            }
            if (r.original() != null) {
                originalFilled++;
            }
            if (r.lyric() != null) {
                lyricFilled++;
            }
            processed++;
            // 每处理 10 首把暂存的搜索结果落一次库，避免每首一行写、也能在中断时保住进度
            if (processed % 10 == 0) {
                searchService.flushPendingWrites();
            }
            if (r.allSourcesLimited()) {
                limited++;
                searchService.sleepAllSources();
            } else {
                sleepBetweenRows();
            }
        }
        // 收尾：冲掉不足 10 首的余量（justTest 下 pendingWrites 恒为空，这里是无害空操作）
        searchService.flushPendingWrites();

        System.out.println();
        System.out.println("==============================================");
        System.out.println((justTest ? "【干跑】" : "【真实】") + "处理 " + total + " 行："
                + " 跳过(近期已搜) " + skipped
                + "，补 artist " + artistFilled
                + "，原曲 " + originalFilled
                + "，歌词 " + lyricFilled
                + "，全源限流 " + limited);
    }

    /** 复用 SearchResult 的字段拼一行「补了什么」；summary() 是给前端看的，这里分开计数更清楚 */
    private static String summarize(SongResourceSearchService.SearchResult r) {
        StringBuilder sb = new StringBuilder();
        if (r.artist() != null) {
            sb.append(" artist=").append(r.artist());
        }
        if (r.original() != null) {
            sb.append(" 原曲=").append(r.original());
        }
        if (r.lyric() != null) {
            sb.append(" 歌词=").append(r.lyric());
        }
        if (r.allSourcesLimited()) {
            sb.append("（全源限流）");
        }
        return sb.length() == 0 ? "（无需补）" : sb.toString();
    }

    /** 行与行之间随机停顿（毫秒），让批量搜索的请求节奏不可预测，降低被识别为爬虫的概率 */
    private static void sleepBetweenRows() {
        long ms = 500 + (long) (Math.random() * 1500); // 0.5 ~ 2 秒
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
