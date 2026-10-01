package io.github.Nyameph.nyaentworks.script;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import io.github.Nyameph.nyaentworks.manga.config.MangaProperties;
import io.github.Nyameph.nyaentworks.manga.entity.MangaData;
import io.github.Nyameph.nyaentworks.manga.mapper.MangaDataMapper;
import io.github.Nyameph.nyaentworks.manga.service.MangaEhScanService;
import io.github.Nyameph.nyaentworks.manga.util.MangaEhLocalDb;

import java.util.ArrayList;
import java.util.List;

/**
 * 漫画 e-hentai 标签扫描工具（可执行脚本，非单元测试）。
 *
 * <p>两个入口，共用本机 MySQL 与本地数据源 {@code eh-gallery.db}：
 * <ul>
 *   <li>{@link #scanCoverage()} —— 本地数据源覆盖率自检：只读、不写库。把库内每本漫画拿去
 *       {@link MangaEhLocalDb} 匹配一次，看 2025.08 静态快照能覆盖多少本。可选对本地未命中的
 *       本子再走封面反搜（{@code imageScanMisses}，联网、只读），统计图搜能额外救回多少。</li>
 *   <li>{@link #scanAll()} —— 后台持续扫：逐本 {@link MangaEhScanService#scan}（本地优先、未命中
 *       再联网、都对不上再封面反搜），落 {@code manga_eh_scan} 结果表；justTest=false 时才写标签关联。</li>
 * </ul>
 *
 * <p><b>置信度</b>：封面反搜的主门槛是<b>作者/社团元数据命中 + 展会/杂志不冲突</b>（口径同标题/本地
 * 匹配，{@code thumbnailRequireMeta} 全局默认开）；两个入口把标题相似度这一次级地板降到 0.4
 * （{@code thumbnailMinTitleSim} / {@code imageScanMinTitleSim}，同本地库 {@code SIM_MIN_META}），
 * 元数据命中为准、标题略放宽，减少 eh 相似搜索的误命中。
 *
 * <p><b>前置</b>：需本机 MySQL。联网（{@link #scanAll()}、
 * {@link #scanCoverage()} 开 {@code imageScanMisses} 时）还需 e-hentai cookie（环境变量
 * {@code NYA_ENTWORKS_MANGA_EH_SCAN_COOKIE}，别提交进仓库）；{@code imageScanMisses=false} 时 {@code scanCoverage} 离线即可跑。
 */
@SpringBootTest
public class MangaEhScanUtil {

    @Autowired
    private MangaDataMapper mangaDataMapper;

    @Autowired
    private MangaEhScanService scanService;

    @Autowired
    private MangaEhLocalDb localDb;

    @Autowired
    private MangaProperties mangaProperties;

    // ---- 本地数据源覆盖率自检（只读、离线） ----

    @Test
    public void scanCoverage() throws Exception {
        // 对本地未命中的本子再走封面反搜（联网、只读），统计图搜能额外救回多少。false = 纯离线覆盖率。
        boolean imageScanMisses = true;
        double imageScanMinTitleSim = 0.4; // 降档后的次级标题地板（作者/社团元数据命中才是主门槛）
        int imageScanMissLimit = 0;        // 0 = 对所有未命中都试图搜；>0 = 只试前 N 本（省请求）

        List<MangaData> mangas = mangaDataMapper.selectList(
                Wrappers.<MangaData>lambdaQuery().orderByAsc(MangaData::getId));
        System.out.println("=== 搜索完成：" + mangas.size());
        int exactHit = 0;
        int containsHit = 0;
        int metaHit = 0;
        int miss = 0;
        int skip = 0;
        int blank = 0;
        int total = mangas.size();
        int imageRecovered = 0; // 本地未命中、封面反搜救回的本数
        int imageTried = 0;     // 实际发起封面反搜的本数

        List<String> exactSamples = new ArrayList<>();
        List<String> containsSamples = new ArrayList<>();
        List<String> metaSamples = new ArrayList<>();
        List<String> missSamples = new ArrayList<>();
        List<String> imageSamples = new ArrayList<>();

        int count = 0;
        long start = System.currentTimeMillis();
        for (MangaData m : mangas) {
            count++;

            System.out.printf("扫描进度=%d/%d 耗时%.2f秒 跳过(date≥2025.08)=%d 空标题=%d 精确命中=%d 包含命中=%d 作者/原作/社团命中=%d 未命中=%d\n",
                    count, total, (System.currentTimeMillis() - start) / 1000.0, skip, blank, exactHit, containsHit, metaHit, miss);
            if (count % 20 == 0) {
            }
            if (MangaEhLocalDb.skipByDate(m.getDateTag())) {
                skip++;
                continue;
            }
            if (MangaEhLocalDb.stripKey(m.getTitle()) == null) {
                blank++;
                continue;
            }
            MangaEhLocalDb.LocalHit hit = localDb.match(m);
            if (hit == null) {
                miss++;
                if (missSamples.size() < 10) {
                    missSamples.add(String.format("%d 《%s》 artist=%s parody=%s",
                            m.getId(), m.getTitle(), m.getArtist(), m.getParody()));
                }
                // 本地未命中：可选再走封面反搜（联网、只读），看图搜能否额外救回
                if (imageScanMisses && (imageScanMissLimit <= 0 || imageTried < imageScanMissLimit)) {
                    imageTried++;
                    MangaEhScanService.ThumbHit th = scanService.matchByThumbnail(m, imageScanMinTitleSim);
                    if (th != null) {
                        imageRecovered++;
                        if (imageSamples.size() < 20) {
                            imageSamples.add(String.format("%d 《%s》 → %s (gid=%d, sim=%.2f)",
                                    m.getId(), m.getTitle(),
                                    th.titleJpn() != null ? th.titleJpn() : th.title(),
                                    th.gid(), th.bestTitleSim()));
                        }
                    }
                }
                continue;
            }
            if (MangaEhLocalDb.METHOD_LOCAL_META.equals(hit.method())
                    || MangaEhLocalDb.METHOD_LOCAL_META_STRONG.equals(hit.method())) {
                metaHit++;
                if (metaSamples.size() < 10) {
                    metaSamples.add(fmt(m, hit));
                }
            } else if (MangaEhLocalDb.METHOD_LOCAL_TITLE_EXACT.equals(hit.method())) {
                exactHit++;
                if (exactSamples.size() < 10) {
                    exactSamples.add(fmt(m, hit));
                }
            } else {
                containsHit++;
                if (containsSamples.size() < 10) {
                    containsSamples.add(fmt(m, hit));
                }
            }
        }

        int hit = exactHit + containsHit + metaHit;
        int eligible = total - skip - blank;
        System.out.println("======== 本地数据源覆盖率 ========");
        System.out.printf("漫画总数=%d 跳过(date≥2025.08)=%d 空标题=%d 应匹配=%d%n",
                total, skip, blank, eligible);
        System.out.printf("精确命中=%d 包含命中=%d 作者/原作/社团命中=%d 未命中=%d 总命中=%d 命中率=%.1f%%%n",
                exactHit, containsHit, metaHit, miss, hit,
                eligible == 0 ? 0 : hit * 100.0 / eligible);
        System.out.println("--- 精确命中样例 ---");
        exactSamples.forEach(System.out::println);
        System.out.println("--- 包含命中样例 ---");
        containsSamples.forEach(System.out::println);
        System.out.println("--- 作者/原作/社团命中样例 ---");
        metaSamples.forEach(System.out::println);
        System.out.println("--- 未命中样例 ---");
        missSamples.forEach(System.out::println);
        if (imageScanMisses) {
            System.out.println("======== 封面反搜救回（本地未命中的本子） ========");
            System.out.printf("发起图搜=%d 救回=%d 救回率=%.1f%%（占本地未命中 %d 的比例=%.1f%%）%n",
                    imageTried, imageRecovered,
                    imageTried == 0 ? 0 : imageRecovered * 100.0 / imageTried,
                    miss, miss == 0 ? 0 : imageRecovered * 100.0 / miss);
            System.out.println("--- 封面反搜救回样例 ---");
            imageSamples.forEach(System.out::println);
        }
    }

    private static String fmt(MangaData m, MangaEhLocalDb.LocalHit hit) {
        return String.format("%d 《%s》 → %s (gid=%d)",
                m.getId(), m.getTitle(), hit.titleJpn() != null ? hit.titleJpn() : hit.title(), hit.gid());
    }

    // ---- 后台持续扫（本地优先 + 联网） ----

    @Test
    public void scanAll() {
        boolean justTest = true;    // true = 只扫描落结果表，不写标签关联；false = 扫描后立即应用
        int limit = 0;            // 0 = 全部；>0 = 只扫前 N 本（调试用）
        String artistFilter = ""; // 只扫某作者，空 = 全部
        boolean thumbnailSearch = true; // true = 标题/本地都对不上时用封面反向搜索兜底（§9.3）
        double thumbnailMinTitleSim = 0.4; // 降档后的次级标题地板（作者/社团元数据命中才是主门槛）

        // 全局 yaml 默认关封面反搜（额外上传请求、可能误命中）；批量扫时按脚本开关临时打开，
        // 让「标题带一堆装饰符号、按标题搜不到」的本子多一条兜底路。置信口径与标题/本地匹配一致：
        // 主门槛是作者/社团元数据命中 + 展会/杂志不冲突（thumbnailRequireMeta，全局默认已开），
        // 标题相似度只作降档后的次级地板（0.4，同本地库 SIM_MIN_META），避免误命中落库。
        mangaProperties.getEhScan().setThumbnailSearch(thumbnailSearch);
        mangaProperties.getEhScan().setThumbnailMinTitleSim(thumbnailMinTitleSim);

        List<MangaData> mangas = mangaDataMapper.selectList(Wrappers.<MangaData>lambdaQuery()
                .eq(!artistFilter.isBlank(), MangaData::getArtist, artistFilter)
                .orderByAsc(MangaData::getId)
                .last(limit > 0, "limit " + limit)
        );

        List<Long> shouldSkipIds = scanService.listShouldSkipIds();

        int localHit = 0;
        long localHitTime = 0;
        int onlineHit = 0;
        long onlineHitTime = 0;
        int notFound = 0;
        long notFoundTime = 0;
        int noMatch = 0;
        long noMatchTime = 0;
        int failed = 0;
        long failedTime = 0;
        int skipped = 0;

        long start = System.currentTimeMillis();
        long partStart;
        int total = mangas.size();
        for (int i = 0; i < total; i++) {
            partStart = System.currentTimeMillis();
            MangaData m = mangas.get(i);
            if (i % 20 == 0) {
                System.out.printf("==== 扫描进度=%d/%d 耗时%.2f秒 " +
                                "本地命中=%d(平均%.2f秒) 线上命中=%d(平均%.2f秒) " +
                                "无候选=%d(平均%.2f秒) 无匹配=%d(平均%.2f秒) " +
                                "失败=%d(平均%.2f秒) 跳过=%d%n",
                        i, total, (System.currentTimeMillis() - start) / 1000.0,
                        localHit, localHit == 0 ? 0.0 : (localHitTime/(1000.0*localHit)), onlineHit, onlineHit == 0 ? 0.0 : (onlineHitTime/(1000.0*onlineHit)),
                        notFound, notFound == 0 ? 0.0 : (notFoundTime/(1000.0*notFound)), noMatch, noMatch == 0 ? 0.0 : (noMatchTime/(1000.0*noMatch)),
                        failed, failed == 0 ? 0.0 : (failedTime/(1000.0*failed)), skipped);
            }
            System.out.printf("[%d/%d] %d 《%s》 (作者=%s)%n",
                    i + 1, total, m.getId(), m.getTitle(), m.getArtist());

            if (shouldSkipIds.contains(m.getId())) {
                skipped++;
                System.out.println("  跳过（已扫过）");
                continue;
            }
            try {
                MangaEhScanService.ScanResult r = scanService.scan(m, !justTest, null);
                switch (r.status()) {
                    case "SUCCESS" -> {
                        boolean local = r.matchMethod() != null && r.matchMethod().startsWith("LOCAL");
                        if (local) {
                            localHit++;
                            localHitTime += (System.currentTimeMillis() - partStart);
                        } else {
                            onlineHit++;
                            onlineHitTime += (System.currentTimeMillis() - partStart);
                        }
                        System.out.printf("  %s命中 gid=%s 方式=%s 相似度=%s 建议标签=%d%n",
                                local ? "本地" : "线上",
                                r.gallery() == null ? "?" : r.gallery().gid(),
                                r.matchMethod(), r.matchScore(), r.suggestedTags().size());
                    }
                    case "NOT_FOUND" -> {
                        notFound++;
                        notFoundTime += (System.currentTimeMillis() - partStart);
                        System.out.println("  无候选");
                    }
                    case "NO_MATCH" -> {
                        noMatch++;
                        noMatchTime += (System.currentTimeMillis() - partStart);
                        System.out.println("  无匹配");
                    }
                    default -> {
                        failed++;
                        failedTime += (System.currentTimeMillis() - partStart);
                        System.out.println("  失败: " + r.errorMessage());
                    }
                }
            } catch (Exception e) {
                failed++;
                failedTime += (System.currentTimeMillis() - partStart);
                System.out.println("  异常: " + e.getMessage());
            }

        }
        System.out.println("================ 汇总 ================");
        System.out.printf("总数=%d 本地命中=%d 线上命中=%d 无候选=%d 无匹配=%d 失败=%d 跳过=%d%n",
                total, localHit, onlineHit, notFound, noMatch, failed, skipped);
    }
}
