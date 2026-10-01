package io.github.Nyameph.nyaentworks.manga.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import io.github.Nyameph.nyaentworks.manga.config.MangaProperties;
import io.github.Nyameph.nyaentworks.manga.consts.MangaDictType;
import io.github.Nyameph.nyaentworks.manga.consts.MangaTagTargetType;
import io.github.Nyameph.nyaentworks.manga.entity.MangaData;
import io.github.Nyameph.nyaentworks.manga.entity.MangaDictEntry;
import io.github.Nyameph.nyaentworks.manga.entity.MangaEhScan;
import io.github.Nyameph.nyaentworks.manga.entity.MangaTag;
import io.github.Nyameph.nyaentworks.manga.mapper.MangaDataMapper;
import io.github.Nyameph.nyaentworks.manga.mapper.MangaEhScanMapper;
import io.github.Nyameph.nyaentworks.manga.mapper.MangaTagMapper;
import io.github.Nyameph.nyaentworks.manga.util.MangaEhClient;
import io.github.Nyameph.nyaentworks.manga.util.MangaEhClient.GData;
import io.github.Nyameph.nyaentworks.manga.util.MangaEhClient.GalleryRef;
import io.github.Nyameph.nyaentworks.manga.util.MangaEhHtmlParser;
import io.github.Nyameph.nyaentworks.manga.util.MangaEhLocalDb;
import io.github.Nyameph.nyaentworks.manga.util.MangaEventKey;
import io.github.Nyameph.nyaentworks.manga.util.MangaNameParser;
import io.github.Nyameph.nyaentworks.manga.util.MangaEhTagMerge;
import io.github.Nyameph.nyaentworks.manga.util.MangaEhTagMerge.Candidate;
import io.github.Nyameph.nyaentworks.manga.util.MangaEhTagMerge.Merged;
import io.github.Nyameph.nyaentworks.manga.util.MangaTextUtil;
import io.github.Nyameph.nyaentworks.manga.util.MangaTitleMatcher;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 单本漫画的 e-hentai 标签扫描（docs/已完成/eh标签扫描设计.md）。
 * <p><b>扫描与应用分离</b>：{@link #scan} 只产出建议标签、落 {@code manga_eh_scan} 结果表；
 * {@link #apply} 才把建议标签写进 {@code manga_tag_ref(MANGA_DATA)}（复用
 * {@link MangaTagService#replaceRefsExact}，整组覆盖、天然幂等）。
 * <p>只处理内容类命名空间（同 {@code MangaEhTagFetcher#CONTENT_NAMESPACES}，不含 language），
 * artist/group/parody/character 不落标签表，避免与「目录名是权威」打架。
 */
@Service
@RequiredArgsConstructor
public class MangaEhScanService {

    private final MangaDataMapper mangaDataMapper;
    private final MangaEhScanMapper scanMapper;
    private final MangaTagMapper tagMapper;
    private final MangaTagService tagService;
    private final MangaEhClient client;
    private final MangaEhLocalDb localDb;
    private final AiSimilarityService aiService;
    private final MangaCoverService coverService;
    private final MangaDictService dictService;
    private final MangaProperties props;

    /**
     * 封面反向搜索命中的相似度地板值：封面对上是主信号，即便标题相似度很低（正是它兜底的场景），
     * 也给一个明确「中等可信、需人工复核」的分。落在 LOCAL_META 那一档，与其它 THUMBNAIL 记录
     * 可从 {@code match_method} 分辨。标题相似度更高时按实际分走（封面 + 标题双命中，更可信）。
     */
    private static final double SCORE_THUMBNAIL = 0.7;

    // ---- 对外形态 ----

    public record Gallery(Long gid, String token, String title, String titleJpn) {
    }

    public record CandidateView(Long gid, String token, String title, Double similarity, List<String> tags,
                                String reason) {
    }

    public record ScanResult(Long mangaId, String status, Gallery gallery, String matchMethod,
                             Double matchScore, List<MangaTagService.TagItem> suggestedTags,
                             List<CandidateView> candidates, String errorMessage,
                             LocalDateTime scannedAt) {
    }

    /** 单本 eh 扫描的任务参数（重跑靠它还原漫画 id 与可选的 gallery 直链） */
    public record ScanParams(Long mangaId, Boolean autoApply, String galleryUrl) {
    }

    /** 一条建议标签：eh 原文 + 翻译后的中文（未命中词典时中文=英文） */
    private record SuggestedTag(String namespace, String tagEn, String tagZh) {
    }

    /**
     * @param reason AI 判定的自述理由（{@code method} 为 {@code AI} 时才有）；确定性匹配一律 null
     */
    private record ScoredCandidate(GData data, double similarity, String method,
                                   Map<String, Integer> weights, String reason) {
    }

    // ---- 扫描 ----
    public ScanResult scan(Long mangaId, boolean autoApply, String galleryUrl) {
        MangaData manga = mangaDataMapper.selectById(mangaId);
        if (manga == null) {
            throw new IllegalArgumentException("漫画不存在: " + mangaId);
        }
        return scan(manga, autoApply, galleryUrl);
    }

    /**
     * 扫一本。三条路：手动直链 → 本地库命中 → 联网搜索。
     * <p><b>没配本地库（{@code manga.eh-scan.local-db-path} 空，或文件不在）时中间那条整段不走</b>，
     * 直接联网，见 {@link MangaEhLocalDb#available()}。
     *
     * <p><b>异常一律落成 FAILED 行</b>（{@link IllegalArgumentException} 除外）：批量扫是几千本的
     * 后台任务，中间一本抛 RuntimeException 若穿出去，这本连失败记录都没有，任务页看不到原因、
     * 下一轮还会再撞一次。落一行 FAILED 才能被跳过判定认出来（见 {@link #listShouldSkipIds}）。
     * 参数错（漫画不存在、gallery 链接非法）是调用方的问题，继续往上抛给 GlobalExceptionHandler。
     */
    public ScanResult scan(MangaData manga, boolean autoApply, String galleryUrl) {
        if (manga == null) {
            throw new IllegalArgumentException("漫画不存在");
        }
        try {
            if (StringUtils.isNotBlank(galleryUrl)) {
                return scanManual(manga, autoApply, galleryUrl);
            }
            // 本地库没配（或配了但文件不在）就整段不走：match 本身也会因加载失败返回 null，
            // 但那要先付一次「查文件 + 试加载」的代价，且日志里看不出是「没配」还是「没匹配上」。
            // 门开在这里，hit 恒为 null —— 下面的本地垫底、hasFallback 随之一起消失，
            // 这一本就老实走联网搜索（弱命中垫底那条路也一并没了，没有本地结果可垫）。
            MangaEhLocalDb.LocalHit hit = localDb.available() ? localDb.match(manga) : null;
            // 够可信的本地命中直接采纳，不联网
            if (hit != null && MangaEhLocalDb.isConfident(hit)) {
                return finishLocal(manga, hit, autoApply);
            }
            // 弱命中（LOCAL_META：只有 artist/parody/group 对上、标题不够像）先去联网找更好的，
            // 但把它留作垫底 —— 联网搜不到时用它，总比报「无候选」强。
            // 除本地垫底外，若开了封面反搜，联网无果也不算终点（thumbnail 兜底 → 返回 null 交回这里）。
            boolean hasFallback = hit != null || props.getEhScan().isThumbnailSearch();
            ScanResult online = scanBySearch(manga, autoApply, hasFallback);
            if (online != null) {
                return online;
            }
            // 标题/本地都对不上时的最后手段：封面反向搜索（§9.3）。开关关闭、无封面、
            // 或不过标题相似度门槛（低置信）则返回 null，落回本地垫底 / NOT_FOUND。
            if (props.getEhScan().isThumbnailSearch()) {
                ScanResult byThumb = scanByThumbnail(manga, autoApply);
                if (byThumb != null) {
                    return byThumb;
                }
            }
            return finishLocal(manga, hit, autoApply);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            writeScan(manga, MangaEhScan.STATUS_FAILED, null, null, null, null, null, null, 0,
                    null, null, msg);
            return new ScanResult(manga.getId(), MangaEhScan.STATUS_FAILED, null, null, null,
                    List.of(), List.of(), msg, LocalDateTime.now());
        }
    }

    /**
     * 把本地库命中包成结果、走统一 finish。
     * <p>调用时机有两处，语义不同：
     * <ul>
     *   <li><b>可信命中</b>（{@link MangaEhLocalDb#isConfident}：标题精确 / 候选集内标题包含 /
     *       artist+展会双命中）→ 直接采纳，不联网；</li>
     *   <li><b>弱命中垫底</b>（{@code LOCAL_META}：只有作者/原作/社团对上，标题不够像）→ 先联网找更好的，
     *       联网也搜不到时才用它。本地库是 2025.08 旧快照，可能漏掉最新卷/部，所以弱命中不优先；
     *       但联网无果时它仍是唯一线索，丢掉就变成「无候选」了。</li>
     * </ul>
     * 两种情况都把 {@code match_method} 原样落库（{@code LOCAL_META} / {@code LOCAL_TITLE_EXACT} …），
     * 事后能从结果表分辨这条是怎么来的、要不要人工复核。
     */
    private ScanResult finishLocal(MangaData manga, MangaEhLocalDb.LocalHit hit, boolean autoApply) {
        if (hit == null) {
            return simpleResult(manga, MangaEhScan.STATUS_NOT_FOUND, null, null);
        }
        GData data = new GData(hit.gid(), hit.token(), hit.title(), hit.titleJpn(), hit.tags());
        ScoredCandidate best = new ScoredCandidate(data, hit.score(), hit.method(), null, null);
        return finish(manga, List.of(best), autoApply);
    }

    private ScanResult scanManual(MangaData manga, boolean autoApply, String galleryUrl)
            throws IOException, InterruptedException {
        GalleryRef ref = MangaEhHtmlParser.parseGalleryUrl(galleryUrl);
        if (ref == null) {
            throw new IllegalArgumentException("gallery 链接解析不出 gid/token: " + galleryUrl);
        }
        List<GData> list = client.gdata(List.of(ref));
        if (list.isEmpty()) {
            return simpleResult(manga, MangaEhScan.STATUS_NOT_FOUND, null, null);
        }
        GData data = list.get(0);
        ScoredCandidate best = new ScoredCandidate(data, 1.0, "MANUAL", scrapeWeights(data), null);
        return finish(manga, List.of(best), autoApply);
    }

    /**
     * 联网搜索 + AI 排序，走统一 finish。
     * <p>联网两个「无果」退出口（搜不到候选、相似度都不过线）：{@code hasLocalFallback} 为真时
     * 不写结果行、返回 {@code null} 交给调用方用本地垫底命中，否则落 NOT_FOUND/NO_MATCH 行。
     */
    private ScanResult scanBySearch(MangaData manga, boolean autoApply, boolean hasLocalFallback)
            throws IOException, InterruptedException {
        List<String> terms = buildSearchTerms(manga);
        if (terms.isEmpty()) {
            return simpleResult(manga, MangaEhScan.STATUS_NO_MATCH, null, "无可用搜索词（title/parody/目录名都为空）");
        }
        List<GData> list = searchCandidates(terms);
        if (list.isEmpty()) {
            return hasLocalFallback ? null : simpleResult(manga, MangaEhScan.STATUS_NOT_FOUND, null, null);
        }

        double simMin = props.getEhScan().getSimMin();
        List<ScoredCandidate> scored = new ArrayList<>();
        for (GData g : list) {
            MangaTitleMatcher.Match m = MangaTitleMatcher.match(manga.getTitle(), g.title(), g.titleJpn());
            scored.add(new ScoredCandidate(g, m.score(), m.method(), null, null));
        }
        double bestScore = maxSimilarity(scored);

        // AI 兜底：确定性匹配没有「精确/包含」命中（含被续作检测排除的）时，交给本地 AI 重排，
        // 识别「同一部（标题少量修改）」还是「同一系列的不同部/卷」。续作标记已在确定性层排除。
        if (bestScore < MangaTitleMatcher.SCORE_CONTAINS) {
            Map<Long, AiSimilarityService.AiScore> ai = aiService.score(manga.getTitle(), manga.getParody(),
                    manga.getArtist(), list);
            if (!ai.isEmpty()) {
                for (int i = 0; i < scored.size(); i++) {
                    ScoredCandidate s = scored.get(i);
                    AiSimilarityService.AiScore d = ai.get(s.data().gid());
                    // AI 分数无条件覆盖：AI 既能抬高真匹配，也能压低被「包含」误判的候选
                    if (d != null) {
                        scored.set(i, new ScoredCandidate(s.data(), d.similarity(), "AI", s.weights(),
                                d.reason()));
                    }
                }
                bestScore = maxSimilarity(scored);
            }
        }

        List<ScoredCandidate> top = scored.stream()
                .filter(s -> s.similarity() >= simMin)
                .sorted(Comparator.comparingDouble(ScoredCandidate::similarity).reversed())
                .limit(props.getEhScan().getTopK())
                .toList();
        if (top.isEmpty()) {
            return hasLocalFallback ? null : simpleResult(manga, MangaEhScan.STATUS_NO_MATCH, bestScore, null);
        }

        // 抓权重（可选，默认关）：只对 Top-K 候选抓；默认关时直接复用原有 weights=null
        if (props.getEhScan().isScrapeWeight()) {
            List<ScoredCandidate> weighted = new ArrayList<>(top.size());
            for (ScoredCandidate s : top) {
                weighted.add(new ScoredCandidate(s.data(), s.similarity(), s.method(), scrapeWeights(s.data()),
                        s.reason()));
            }
            return finish(manga, weighted, autoApply);
        }
        return finish(manga, top, autoApply);
    }

    /**
     * 按「站点在外、搜索词在内」两层循环取候选：每站把全部搜索词从窄到宽试一遍，搜到就停。
     *
     * <p><b>为什么站点在外</b>：搜索词从窄到宽（带 artist/parody 过滤的窄查询命中质量高，
     * 但 eh 没打标签或写法不同就返回空，必须能退到宽查询）。若站点在内层，每个搜空的词都要
     * 付「表站 + 里站」两个请求，5 档词全空就是 10 个请求、20~40s 纯限流睡眠。站点外提后
     * 最坏 5 个请求，而<b>每个（站点, 词）组合仍然都会被访问到</b> —— 全空的路径上
     * findability 完全不变。
     *
     * <p><b>代价</b>（说清楚，不是零成本）：当「窄词只有里站搜得到、宽词表站搜得到」时，
     * 旧口径会用里站的窄结果，新口径会用表站的宽结果 —— 候选集更杂，但仍会过
     * {@link MangaTitleMatcher} 与 AI 重排，不至于给出错答案，只是可能排不中。
     * 里站独占的 gallery 在表站全空时依然能被整轮兜到。
     */
    private List<GData> searchCandidates(List<String> terms) throws IOException, InterruptedException {
        List<String> sites = client.searchSites();
        for (int si = 0; si < sites.size(); si++) {
            String site = sites.get(si);
            for (String term : terms) {
                List<GalleryRef> refs;
                try {
                    refs = client.searchOn(site, term);
                } catch (IOException e) {
                    // 非最后一站故障不致命（表站 522 之类），换下一站整轮重试。
                    // 最后一站也故障就必须抛：吞掉会把「代理挂了」误记成 NOT_FOUND（无候选），
                    // 而它该是 FAILED —— 前者看起来像「eh 上没这本」，会误导事后复核。
                    if (si == sites.size() - 1) {
                        throw e;
                    }
                    break;
                }
                if (refs.isEmpty()) {
                    continue;
                }
                List<GData> list = client.gdata(refs);
                if (!list.isEmpty()) {
                    return list;
                }
            }
        }
        return List.of();
    }

    /**
     * 封面反搜命中（只读、不写库），供覆盖率脚本用。
     * {@code bestTitleSim} 是过门槛后最佳候选的落库分数（标题相似度与地板分取高者），命中质量的直接指标。
     */
    public record ThumbHit(long gid, String token, String title, String titleJpn, double bestTitleSim) {
    }

    /**
     * 封面反向搜索兜底（docs/已完成/eh标签扫描设计.md §9.3）：标题/本地都对不上时，用漫画首图（封面）
     * 去 eh 相似搜索。
     *
     * <p><b>置信度门槛与标题/本地匹配一致</b>（见 {@link #thumbnailCandidates}）：主门槛是
     * <b>作者/社团元数据命中</b>，展会/杂志不冲突；标题相似度只作降档后的次级地板值。落库分数取
     * {@code max(标题相似度, SCORE_THUMBNAIL)}——封面+标题双命中给高分、纯封面命中给地板分，
     * {@code match_method=THUMBNAIL} 标记，属人工复核档。
     *
     * @return 命中且过门槛时的 SUCCESS 结果；无封面 / 无候选 / 不过门槛时返回 {@code null}，
     *         交回上层由 {@link #finishLocal} 决定用本地垫底还是落 NOT_FOUND
     */
    private ScanResult scanByThumbnail(MangaData manga, boolean autoApply)
            throws IOException, InterruptedException {
        List<ScoredCandidate> top = thumbnailCandidates(manga, props.getEhScan().getThumbnailMinTitleSim());
        if (top == null) {
            return null;
        }
        if (props.getEhScan().isScrapeWeight()) {
            List<ScoredCandidate> weighted = new ArrayList<>(top.size());
            for (ScoredCandidate s : top) {
                weighted.add(new ScoredCandidate(s.data(), s.similarity(), s.method(), scrapeWeights(s.data()),
                        s.reason()));
            }
            return finish(manga, weighted, autoApply);
        }
        return finish(manga, top, autoApply);
    }

    /**
     * 只读的封面反搜：上传封面 → 相似搜索 → 元数据门槛判定（见 {@link #thumbnailCandidates}），
     * 命中返回 {@link ThumbHit}，<b>不写结果表、不落标签</b>。给 {@code MangaEhScanUtil.scanCoverage}
     * 这类离线覆盖率脚本用（它不能写库）。
     *
     * @param minTitleSim 降档后的次级标题相似度地板值（同 {@code thumbnail-min-title-sim}），
     *                    作者/社团元数据命中才是主门槛
     * @return 命中且过门槛的最佳候选；无封面 / 无候选 / 不过门槛返回 {@code null}
     */
    public ThumbHit matchByThumbnail(MangaData manga, double minTitleSim)
            throws IOException, InterruptedException {
        List<ScoredCandidate> top = thumbnailCandidates(manga, minTitleSim);
        if (top == null) {
            return null;
        }
        ScoredCandidate best = top.get(0);
        GData g = best.data();
        return new ThumbHit(g.gid(), g.token(), g.title(), g.titleJpn(), best.similarity());
    }

    /**
     * 封面反搜的共用核心：加载封面 → {@code image_lookup} 相似搜索 → gdata 取元数据 → <b>元数据门槛
     * 过滤</b> → 按（展会/杂志强命中、标题相似度）排序取 Top-K。
     *
     * <p><b>置信口径与标题/本地 meta 匹配一致</b>（{@link MangaEhLocalDb}）：eh 相似图搜索会返回画风/
     * 构图相近的<b>不同作品</b>，光靠封面像会误命中，所以：
     * <ol>
     *   <li><b>主门槛（作者/社团）</b>：候选 {@code artist}/{@code group} 标签必须与本漫画的作者/社团
     *       有交集（{@link #metaHit}）。漫画自身没有作者/社团时该门槛判不了，退回纯标题相似度地板。</li>
     *   <li><b>展会/杂志（{@link MangaEventKey} 三态）</b>：从画廊标题拆出展会/杂志（{@link #eventVerdict}），
     *       CONFLICT 剔除、MATCH 视为强信号（免标题地板）、UNKNOWN 放过。</li>
     *   <li><b>标题相似度降为次级地板</b>：主门槛过了但展会/杂志没正向命中时，仍要求标题相似度
     *       ≥ {@code minTitleSim}（比标题搜的门槛低）；展会/杂志强命中则免这一关。</li>
     * </ol>
     * <p>落库分数取 {@code max(标题相似度, SCORE_THUMBNAIL)}，排序把展会/杂志强命中的排前、其余按
     * 原始标题相似度——若都用地板分排序，纯封面命中会全塌成 0.7 无法区分谁更近。
     *
     * @param minTitleSim 降档后的次级标题相似度地板值
     * @return 过门槛的 Top-K 候选（已排序）；无封面 / 无候选 / 无一过门槛时返回 {@code null}
     */
    private List<ScoredCandidate> thumbnailCandidates(MangaData manga, double minTitleSim)
            throws IOException, InterruptedException {
        byte[] cover = loadCover(manga);
        if (cover == null) {
            return null;
        }
        List<GalleryRef> refs = client.imageLookup(cover, "cover.jpg");
        List<GData> list = refs.isEmpty() ? List.of() : client.gdata(refs);
        if (list.isEmpty()) {
            return null;
        }
        String localKey = MangaTextUtil.normalizeNameKey(manga.getTitle());
        MangaNameParser parser = new MangaNameParser(dictService.current());
        boolean requireMeta = props.getEhScan().isThumbnailRequireMeta();
        // 漫画自身没有作者/社团时元数据门槛判不了，整体退回纯标题相似度地板
        boolean canMeta = requireMeta
                && (StringUtils.isNotBlank(manga.getArtist()) || StringUtils.isNotBlank(manga.getGroupName()));
        Set<String> myArtists = metaKeys(manga.getArtist());
        Set<String> myGroups = metaKeys(manga.getGroupName());
        Set<String> myExhibits = metaKeys(manga.getExhibit());
        Set<String> myMagazines = metaKeys(manga.getMagazine());

        List<ThumbScored> passed = new ArrayList<>();
        for (GData g : list) {
            double sim = rawTitleSim(localKey, g);
            boolean strong = false;
            if (canMeta) {
                if (!metaHit(g, myArtists, myGroups)) {
                    continue; // 作者/社团对不上：主门槛不过，剔除
                }
                MangaEventKey.Verdict ev = eventVerdict(parser, g, myExhibits, myMagazines);
                if (ev == MangaEventKey.Verdict.CONFLICT) {
                    continue; // 展会/杂志冲突：不是同一本
                }
                strong = ev == MangaEventKey.Verdict.MATCH;
                if (!strong && sim < minTitleSim) {
                    continue; // 元数据只弱命中，标题也不够像：降档地板兜底
                }
            } else if (sim < minTitleSim) {
                continue; // 无元数据可判，退回纯标题地板
            }
            passed.add(new ThumbScored(g, sim, strong));
        }
        if (passed.isEmpty()) {
            return null;
        }
        // 展会/杂志强命中的排前（旁证独立于标题），同档再按原始标题相似度——
        // similarity 落库分被地板抬平后无法区分，故排序用未抬平的 sim。
        passed.sort(Comparator.comparing(ThumbScored::strong)
                .thenComparingDouble(ThumbScored::sim).reversed());
        return passed.stream()
                .limit(props.getEhScan().getTopK())
                .map(t -> new ScoredCandidate(t.data(), Math.max(t.sim(), SCORE_THUMBNAIL), "THUMBNAIL", null, null))
                .toList();
    }

    /** 封面反搜过门槛的一条候选：原始标题相似度 + 展会/杂志是否强命中，仅用于排序。 */
    private record ThumbScored(GData data, double sim, boolean strong) {
    }

    /**
     * 候选的作者/社团标签是否与本漫画有交集（封面反搜的主门槛，口径同本地库 metaMatch）。
     * <p>eh 的 {@code artist}/{@code group} 命名空间标签取值，经 {@link MangaEhLocalDb#stripKey} 归一后
     * 与本漫画作者/社团键比对；<b>作者或社团任一交集即算命中</b>（用户口径「作者/社团」）。
     */
    private boolean metaHit(GData g, Set<String> myArtists, Set<String> myGroups) {
        Set<String> theirArtists = tagKeys(g, "artist");
        Set<String> theirGroups = tagKeys(g, "group");
        return intersects(myArtists, theirArtists) || intersects(myGroups, theirGroups);
    }

    /**
     * 用同一套解析器把画廊标题拆成结构化字段，取展会/杂志与本漫画比对（{@link MangaEventKey} 三态）。
     * <p>eh 没有展会/杂志的标签命名空间，它们藏在画廊标题的 {@code (C97)} 前缀 / 杂志名里，
     * 只能靠 {@link MangaNameParser}（同本地目录名解析）拆出来。展会、杂志各判一次，任一
     * CONFLICT 即冲突、任一 MATCH 即强命中，与本地库 {@code splitByEvent} 口径一致。
     */
    private MangaEventKey.Verdict eventVerdict(MangaNameParser parser, GData g,
                                               Set<String> myExhibits, Set<String> myMagazines) {
        String title = StringUtils.isNotBlank(g.titleJpn()) ? g.titleJpn() : g.title();
        MangaData parsed = parser.parse(title);
        Set<String> exhibits = metaKeys(parsed.getExhibit());
        Set<String> magazines = metaKeys(parsed.getMagazine());
        MangaEventKey.Verdict ex = MangaEventKey.classify(myExhibits, exhibits);
        MangaEventKey.Verdict mag = MangaEventKey.classify(myMagazines, magazines);
        if (ex == MangaEventKey.Verdict.CONFLICT || mag == MangaEventKey.Verdict.CONFLICT) {
            return MangaEventKey.Verdict.CONFLICT;
        }
        if (ex == MangaEventKey.Verdict.MATCH || mag == MangaEventKey.Verdict.MATCH) {
            return MangaEventKey.Verdict.MATCH;
        }
        return MangaEventKey.Verdict.UNKNOWN;
    }

    /** 取 GData 里某命名空间（artist/group）的标签值，归一成键集合。 */
    private Set<String> tagKeys(GData g, String namespace) {
        String prefix = namespace + ":";
        Set<String> keys = new HashSet<>();
        for (String tag : g.tags()) {
            if (tag != null && tag.startsWith(prefix)) {
                String k = MangaEhLocalDb.stripKey(tag.substring(prefix.length()));
                if (k != null) {
                    keys.add(k);
                }
            }
        }
        return keys;
    }

    /** 本地多值字段（作者/社团/展会/杂志）按分隔符拆成归一键集合（口径同本地库 splitKeys）。 */
    private Set<String> metaKeys(String csv) {
        Set<String> keys = new HashSet<>();
        if (StringUtils.isBlank(csv)) {
            return keys;
        }
        for (String v : csv.split("[、;，,；]")) {
            String k = MangaEhLocalDb.stripKey(v);
            if (k != null) {
                keys.add(k);
            }
        }
        return keys;
    }

    private static boolean intersects(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        for (String s : a) {
            if (b.contains(s)) {
                return true;
            }
        }
        return false;
    }

    /** 候选对本地标题的原始相似度（取原文/日文较高者）。 */
    private double rawTitleSim(String localKey, GData g) {
        return Math.max(titleSim(localKey, g.title()), titleSim(localKey, g.titleJpn()));
    }

    /** 归一后算标题相似度；任一侧空返回 0。用于封面候选排序。 */
    private double titleSim(String localKey, String ehTitle) {
        String k = MangaTextUtil.normalizeNameKey(ehTitle);
        return (localKey == null || k == null) ? 0 : MangaTitleMatcher.similarity(localKey, k);
    }

    /**
     * 取漫画封面缩略图字节供反搜。缩略图（320px jpg）已够 eh 相似搜索用，且走缓存、比原图小。
     * <p>路径越界等异常（{@link RuntimeException}）在这里吞掉返回 null：封面拿不到只是「反搜这条路走不通」，
     * 不该把整个扫描判成 FAILED（标题搜索那步已经结论无果了）。
     */
    private byte[] loadCover(MangaData manga) {
        if (StringUtils.isBlank(manga.getFolderPath())) {
            return null;
        }
        try {
            return coverService.thumbnail(manga.getFolderPath());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private double maxSimilarity(List<ScoredCandidate> candidates) {
        return candidates.stream().mapToDouble(ScoredCandidate::similarity).max().orElse(0);
    }

    private ScanResult finish(MangaData manga, List<ScoredCandidate> top, boolean autoApply) {
        ScoredCandidate best = top.get(0);
        List<Candidate> mergeInput = top.stream()
                .map(s -> new Candidate(s.similarity(), s.data().tags(), s.weights()))
                .toList();
        List<Merged> merged = MangaEhTagMerge.merge(mergeInput,
                props.getEhScan().getWeightMin(), props.getEhScan().getMaxTags());

        List<SuggestedTag> suggested = translateAll(merged);

        String candidatesJson = buildCandidatesJson(top);
        String suggestedJson = buildSuggestedJson(suggested);

        writeScan(manga, MangaEhScan.STATUS_SUCCESS, best.data().gid(), best.data().token(),
                best.data().title(), best.data().titleJpn(), best.method(), best.similarity(),
                suggested.size(), candidatesJson, suggestedJson, null);

        List<MangaTagService.TagItem> items = suggested.stream()
                .map(s -> new MangaTagService.TagItem(s.namespace(), s.tagZh()))
                .toList();

        if (autoApply) {
            applyTags(manga.getId(), suggested);
        }

        List<CandidateView> views = top.stream()
                .map(s -> new CandidateView(s.data().gid(), s.data().token(), s.data().title(),
                        s.similarity(), s.data().tags(), s.reason()))
                .toList();
        return new ScanResult(manga.getId(), MangaEhScan.STATUS_SUCCESS,
                new Gallery(best.data().gid(), best.data().token(), best.data().title(),
                        best.data().titleJpn()),
                best.method(), best.similarity(), items, views, null, LocalDateTime.now());
    }

    // ---- 应用 ----

    public int apply(Long mangaId) {
        MangaEhScan scan = scanMapper.selectOne(Wrappers.<MangaEhScan>lambdaQuery()
                .eq(MangaEhScan::getMangaId, mangaId));
        if (scan == null || !MangaEhScan.STATUS_SUCCESS.equals(scan.getStatus())) {
            throw new IllegalStateException("该漫画还没有成功的扫描结果，先扫描再应用");
        }
        List<SuggestedTag> suggested = parseSuggestedJson(scan.getSuggestedTagsJson());
        if (suggested.isEmpty()) {
            return 0;
        }
        return applyTags(mangaId, suggested);
    }

    private int applyTags(Long mangaId, List<SuggestedTag> suggested) {
        // 并集：先带上漫画现有的独立标签，再并入 eh 建议标签。replaceRefsExact 内部按
        // (namespace, 大写名) 去重，重复项自然塌掉 —— 多次 apply 幂等，且不会丢掉
        // 用户已打的标签（「有标签也拉取、取并集」的需求落点）。
        List<MangaTagService.TagItem> items = new ArrayList<>(
                tagService.listTags(MangaTagTargetType.MANGA_DATA, mangaId));
        Map<String, String> nameByKey = resolveAll(suggested);
        for (SuggestedTag s : suggested) {
            items.add(new MangaTagService.TagItem(s.namespace(), nameByKey.get(dictKey(s.namespace(), s.tagEn()))));
        }
        return tagService.replaceRefsExact(MangaTagTargetType.MANGA_DATA, mangaId, items);
    }

    // ---- 查询 ----

    public ScanResult get(Long mangaId) {
        MangaEhScan scan = scanMapper.selectOne(Wrappers.<MangaEhScan>lambdaQuery()
                .eq(MangaEhScan::getMangaId, mangaId));
        if (scan == null) {
            return null;
        }
        Gallery gallery = scan.getGalleryGid() == null ? null
                : new Gallery(scan.getGalleryGid(), scan.getGalleryToken(), scan.getGalleryTitle(),
                        scan.getGalleryTitleJpn());
        List<MangaTagService.TagItem> items = parseSuggestedJson(scan.getSuggestedTagsJson()).stream()
                .map(s -> new MangaTagService.TagItem(s.namespace(), s.tagZh()))
                .toList();
        List<CandidateView> candidates = parseCandidatesJson(scan.getCandidatesJson());
        return new ScanResult(mangaId, scan.getStatus(), gallery, scan.getMatchMethod(),
                scan.getMatchScore(), items, candidates, scan.getErrorMessage(),
                scan.getScannedAt());
    }

    /**
     * 批量扫描的跳过判定：成功过的不重扫；没成功的（FAILED/NOT_FOUND/NO_MATCH）12 小时内
     * 扫过也跳过，避免后台循环反复撞同一批暂时扫不动的项。
     */
    public List<Long> listShouldSkipIds() {
        List<Long> mangaIds = scanMapper.selectObjs(Wrappers.<MangaEhScan>lambdaQuery()
                .select(MangaEhScan::getMangaId)
                .and(q -> q.eq(MangaEhScan::getStatus, MangaEhScan.STATUS_SUCCESS)
                        .or()
                        .gt(MangaEhScan::getScannedAt, LocalDateTime.now().minusHours(12)))
        );
        return mangaIds == null ? new  ArrayList<>() : mangaIds;
    }

    // ---- 内部工具 ----

    /**
     * 一批 {@code "namespace:tag_en"} → 建议标签（词典命中给中文，否则中文=英文）。
     * <p><b>一次 {@code in} 查询查完</b>，不是每个标签查一次：单本能建议到几十个标签，
     * 批量扫上万本时逐个查会变成几十万次 SELECT。
     */
    private List<SuggestedTag> translateAll(List<Merged> merged) {
        List<String[]> pairs = new ArrayList<>(merged.size());
        Set<String> enNames = new LinkedHashSet<>();
        for (Merged m : merged) {
            String tag = m.tag();
            int i = tag.indexOf(':');
            String ns = i <= 0 ? "" : tag.substring(0, i);
            String en = i < 0 ? tag : tag.substring(i + 1);
            pairs.add(new String[]{ns, en});
            enNames.add(en);
        }
        Map<String, String> zhByKey = enNames.isEmpty() ? Map.of() : loadTagNames(enNames);
        List<SuggestedTag> out = new ArrayList<>(pairs.size());
        for (String[] p : pairs) {
            String zh = zhByKey.get(dictKey(p[0], p[1]));
            out.add(new SuggestedTag(p[0], p[1], zh == null ? p[1] : zh));
        }
        return out;
    }

    /** 按 en_name 批量捞词典行，返回 {@link #dictKey} → 中文 tag_name。 */
    private Map<String, String> loadTagNames(Set<String> enNames) {
        List<MangaTag> tags = tagMapper.selectList(Wrappers.<MangaTag>lambdaQuery()
                .select(MangaTag::getNamespace, MangaTag::getEnName, MangaTag::getTagName)
                .in(MangaTag::getEnName, enNames));
        Map<String, String> out = new LinkedHashMap<>();
        for (MangaTag t : tags) {
            // putIfAbsent：同 (ns, en) 有多行时取第一条，与原来的 limit 1 口径一致
            out.putIfAbsent(dictKey(t.getNamespace(), t.getEnName()), t.getTagName());
        }
        return out;
    }

    /**
     * 词典查找键。<b>en_name 转大写</b>：原来靠 MySQL 的大小写不敏感排序规则匹配，
     * 换成内存 Map 后必须自己归一，否则库里存 {@code Big Breasts}、eh 给 {@code big breasts} 就查不到。
     */
    private String dictKey(String namespace, String enName) {
        String ns = namespace == null ? "" : namespace.toUpperCase();
        String en = enName == null ? "" : enName.toUpperCase();
        // 用 | 分隔：命名空间是固定的小写词表（female/male/parody/...），不含 |，
        // 所以第一个 | 永远是分隔位，不会出现 ("a|b","c") 与 ("a","b|c") 撞键
        return ns + '|' + en;
    }

    /**
     * 一批建议标签全入库（不存在就插，存在用现有的 tag_name），返回最终 tag_name。
     * <p><b>批量查 + 批量插</b>：原来逐个标签 1–2 次查询 + 可能 1 次插入，
     * 批量 apply 时这是几十倍数据库往返。
     *
     * @return {@link #dictKey} → 入库后的 tag_name（可能是库里原有的中文、也可能就是 en_name）
     */
    private Map<String, String> resolveAll(List<SuggestedTag> suggested) {
        if (suggested.isEmpty()) {
            return Map.of();
        }
        Set<String> enNames = suggested.stream().map(SuggestedTag::tagEn).collect(Collectors.toSet());
        List<MangaTag> existing = tagMapper.selectList(Wrappers.<MangaTag>lambdaQuery()
                .select(MangaTag::getNamespace, MangaTag::getEnName, MangaTag::getTagName)
                .in(MangaTag::getEnName, enNames));
        Map<String, String> result = new LinkedHashMap<>();
        for (MangaTag t : existing) {
            result.putIfAbsent(dictKey(t.getNamespace(), t.getEnName()), t.getTagName());
        }
        // 缺失的批量插
        List<MangaTag> missing = new ArrayList<>();
        for (SuggestedTag s : suggested) {
            String k = dictKey(s.namespace(), s.tagEn());
            if (!result.containsKey(k)) {
                // 先查一次 tag_name：中文重名、英文重名都可能复用行
                MangaTag dup = tagMapper.selectOne(Wrappers.<MangaTag>lambdaQuery()
                        .eq(MangaTag::getNamespace, s.namespace())
                        .eq(MangaTag::getTagName, s.tagZh())
                        .last("limit 1"));
                if (dup != null) {
                    result.put(k, dup.getTagName());
                } else {
                    MangaTag t = new MangaTag();
                    t.setNamespace(s.namespace());
                    t.setEnName(s.tagEn());
                    t.setTagName(s.tagZh());
                    t.setSource(MangaTag.SOURCE_EHENTAI);
                    missing.add(t);
                    result.put(k, s.tagZh());
                }
            }
        }
        for (MangaTag t : missing) {
            tagMapper.insert(t); // MyBatis-Plus 3.5.17 的 BaseMapper 没有 insertBatch；逐个插损耗可接受
        }
        return result;
    }

    /**
     * 构造搜索词序列，<b>从窄到宽</b>，由 {@link #scanBySearch} 依次尝试直到搜出候选。
     *
     * <p>e-hentai 支持 {@code artist:"名字$"} / {@code parody:"原作$"} 的标签过滤（引号内的
     * {@code $} 表示整词匹配），能把「同标题不同作者」在搜索层就分开，命中质量高得多。
     * 但加了标签过滤就有搜不到的风险：eh 那边可能压根没打 artist 标签、或作者名写法与本地不同，
     * 此时窄查询返回空 —— <b>所以必须留退路</b>，否则本来能靠标题搜到的漫画会直接判 NOT_FOUND。
     * 这也是这套语法未经实测时的安全垫：万一语法不对，退化成纯标题搜，不至于全军覆没。
     *
     * <p>顺序：title+artist → title+parody → title → parody → 目录名。
     * 后面的更宽、召回更高，但候选更杂 —— 交给 {@link MangaTitleMatcher} 与 AI 重排去筛。
     *
     * @return 去重后的搜索词列表，全空时返回空列表
     */
    private List<String> buildSearchTerms(MangaData m) {
        String title = StringUtils.trimToNull(m.getTitle());
        String parody = StringUtils.trimToNull(m.getParody());
        // artist 可能含「、」多作者分隔符，取第一个（主作者）
        String artist = StringUtils.trimToNull(m.getArtist());
        String primaryArtist = artist == null ? null
                : StringUtils.trimToNull(artist.split("[、,;，；]")[0]);

        // title 若以 UNBOXING 词开头（如「汉化汇总」），eh 上往往没这个前缀，带着开头、
        // 去掉开头各查一次、交替排列（带前缀在前、去前缀在后），见 titleVariants
        List<String> titles = titleVariants(title);

        // LinkedHashSet：保持从窄到宽的顺序，同时避免 title 为空等情况下产生重复词
        Set<String> terms = new LinkedHashSet<>();
        for (String t : titles) {
            if (t != null && primaryArtist != null) {
                terms.add(t + " " + tagFilter("artist", primaryArtist));
            }
            if (t != null && parody != null) {
                terms.add(t + " " + tagFilter("parody", parody));
            }
            if (t != null) {
                terms.add(t);
            }
        }
        // title 含全角竖线｜（如「日文名｜英文名」）：完整 title 搜不到时，按｜拆开分别查各部分
        for (String part : titleParts(title)) {
            terms.add(part);
        }
        if (parody != null) {
            terms.add(parody);
        }
        // 最宽一档：只按作者搜，把该作者的作品全捞回来交给标题匹配/AI 去筛。
        // 专治「标题带一堆破折号/装饰符号，eh 上按标题压根搜不到」——例如
        // 《鬼哭 1-鬼姫監禁淫蟲寄生-》，前面几档全空，只有这档能捞到候选。
        // 放最后：它返回的量最大、最杂，能靠标题搜到时不该走它。
        if (primaryArtist != null) {
            terms.add(tagFilter("artist", primaryArtist));
        }
        if (!terms.isEmpty()) {
            return List.copyOf(terms);
        }
        if (StringUtils.isNotBlank(m.getFolderPath())) {
            try {
                String name = Path.of(m.getFolderPath()).getFileName().toString();
                name = name.replaceAll("【[^】]*】", " ").replaceAll("\\[[^\\]]*]", " ").trim();
                if (StringUtils.isNotBlank(name)) {
                    return List.of(name);
                }
            } catch (RuntimeException ignore) {
                // 路径非法时退到空列表
            }
        }
        return List.of();
    }

    /**
     * title 的搜索变体：不带 UNBOXING 前缀时只有原样一个；带前缀时「带前缀 + 去前缀」
     * 两个、交替排列（带前缀在前）。title 为空返回空列表，循环里不生成任何 title 词。
     */
    private List<String> titleVariants(String title) {
        if (title == null) {
            return List.of();
        }
        String stripped = stripUnboxingPrefix(title);
        // 去前缀后为空（或异常地没变短）就没有可查的第二词，仍只查完整 title
        if (StringUtils.isBlank(stripped) || stripped.equalsIgnoreCase(title)) {
            return List.of(title);
        }
        return List.of(title, stripped);
    }

    /**
     * title 按全角竖线｜拆出的各部分（每段 trim、去空），不含｜时返回空列表。
     * <p>完整 title 若带「日文名｜英文名」这类并列写法，eh 上往往只存其中一段，按完整串
     * 搜不到，故把各段拆出来单独查。放在 title 相关词之后、parody 之前（窄查询先试，
     * 试不到才轮到这些分割段），与「没查到再查分割部分」的语义一致。
     */
    private List<String> titleParts(String title) {
        if (StringUtils.isBlank(title) || title.indexOf('｜') < 0) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        for (String p : title.split("｜")) {
            String v = StringUtils.trimToNull(p);
            if (v != null) {
                parts.add(v);
            }
        }
        return parts;
    }

    /**
     * title 若以某个 UNBOXING 词条值开头（忽略大小写，如「汉化汇总」），返回去掉该前缀后的
     * 剩余部分；否则返回 {@code null}。只按「字面值是否前缀」判，不套用词条的 matchMode。
     */
    private String stripUnboxingPrefix(String title) {
        if (StringUtils.isBlank(title)) {
            return null;
        }
        for (MangaDictEntry e : dictService.list(MangaDictType.UNBOXING, null)) {
            String value = e.getDictValue();
            if (StringUtils.isBlank(value)) {
                continue;
            }
            if (title.regionMatches(true, 0, value, 0, value.length())) {
                return title.substring(value.length()).trim();
            }
        }
        return null;
    }

    /**
     * 拼一个 eh 标签过滤条件：{@code namespace:"值$"}。
     * <p>{@code $} 必须在引号<b>内</b>（{@code artist:"名字$"}），放外面 eh 不认。
     * <p>值里的 {@code "} 与 {@code $} 直接删掉而不是转义 —— eh 的搜索框没有反斜杠转义，
     * 留着只会把语法搞乱；作者名/原作名里出现这两个字符本就极少见。
     */
    private String tagFilter(String namespace, String value) {
        String v = value.replace("\"", "").replace("$", "").trim();
        return namespace + ":\"" + v + "$\"";
    }

    private Map<String, Integer> scrapeWeights(GData data) {
        if (!props.getEhScan().isScrapeWeight()) {
            return null;
        }
        try {
            return client.tagWeights(data.gid(), data.token());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    /**
     * 落扫描结果，一本漫画一行（{@code manga_id} 唯一）。
     *
     * <p><b>用 UpdateWrapper 显式写每一列（含 null）</b>，而不是 {@code selectOne} 出实体再
     * {@code updateById}：
     * <ul>
     *   <li>省掉一次整行 SELECT —— 那行带 {@code candidates_json} / {@code suggested_tags_json}
     *       两个大 TEXT 列，只为拿个 id 太贵；</li>
     *   <li><b>顺手修掉脏字段</b>：MyBatis-Plus 默认 NOT_NULL 更新策略会跳过 null 字段，
     *       所以上次 FAILED 留下的 {@code error_message}、上次匹配到的 gid/token
     *       在这次重扫成功后不会被清掉，页面会显示成功却带着旧报错。</li>
     * </ul>
     */
    private void writeScan(MangaData manga, String status, Long gid, String token, String title,
                           String titleJpn, String method, Double score, int tagCount,
                           String candidatesJson, String suggestedJson, String error) {
        LambdaUpdateWrapper<MangaEhScan> up = Wrappers.<MangaEhScan>lambdaUpdate()
                .eq(MangaEhScan::getMangaId, manga.getId())
                .set(MangaEhScan::getStatus, status)
                .set(MangaEhScan::getGalleryGid, gid)
                .set(MangaEhScan::getGalleryToken, token)
                .set(MangaEhScan::getGalleryTitle, title)
                .set(MangaEhScan::getGalleryTitleJpn, titleJpn)
                .set(MangaEhScan::getMatchMethod, method)
                .set(MangaEhScan::getMatchScore, score)
                .set(MangaEhScan::getTagCount, tagCount)
                .set(MangaEhScan::getCandidatesJson, candidatesJson)
                .set(MangaEhScan::getSuggestedTagsJson, suggestedJson)
                .set(MangaEhScan::getErrorMessage, error)
                .set(MangaEhScan::getScannedAt, LocalDateTime.now());
        if (scanMapper.update(null, up) > 0) {
            return;
        }
        MangaEhScan s = new MangaEhScan();
        s.setMangaId(manga.getId());
        s.setStatus(status);
        s.setGalleryGid(gid);
        s.setGalleryToken(token);
        s.setGalleryTitle(title);
        s.setGalleryTitleJpn(titleJpn);
        s.setMatchMethod(method);
        s.setMatchScore(score);
        s.setTagCount(tagCount);
        s.setCandidatesJson(candidatesJson);
        s.setSuggestedTagsJson(suggestedJson);
        s.setErrorMessage(error);
        s.setScannedAt(LocalDateTime.now());
        scanMapper.insert(s);
    }

    private ScanResult simpleResult(MangaData manga, String status, Double score, String error) {
        writeScan(manga, status, null, null, null, null, null, score, 0, null, null, error);
        return new ScanResult(manga.getId(), status, null, null, score, List.of(), List.of(),
                error, LocalDateTime.now());
    }

    private String buildCandidatesJson(List<ScoredCandidate> top) {
        List<Map<String, Object>> arr = new ArrayList<>();
        for (ScoredCandidate s : top) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("gid", s.data().gid());
            m.put("token", s.data().token());
            m.put("title", s.data().title());
            m.put("similarity", s.similarity());
            m.put("tags", s.data().tags());
            if (s.reason() != null) {
                // 只有 AI 判定有理由；确定性匹配不编一句假理由
                m.put("reason", s.reason());
            }
            if (s.weights() != null && !s.weights().isEmpty()) {
                m.put("weights", s.weights());
            }
            arr.add(m);
        }
        return JSON.toJSONString(arr);
    }

    private String buildSuggestedJson(List<SuggestedTag> suggested) {
        List<Map<String, String>> arr = new ArrayList<>();
        for (SuggestedTag s : suggested) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("namespace", s.namespace());
            m.put("tagEn", s.tagEn());
            m.put("tagZh", s.tagZh());
            arr.add(m);
        }
        return JSON.toJSONString(arr);
    }

    private List<SuggestedTag> parseSuggestedJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JSONArray arr = JSON.parseArray(json);
        List<SuggestedTag> out = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            JSONObject o = arr.getJSONObject(i);
            out.add(new SuggestedTag(o.getString("namespace"), o.getString("tagEn"), o.getString("tagZh")));
        }
        return out;
    }

    private List<CandidateView> parseCandidatesJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JSONArray arr = JSON.parseArray(json);
        List<CandidateView> out = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            JSONObject o = arr.getJSONObject(i);
            JSONArray tarr = o.getJSONArray("tags");
            List<String> tags = new ArrayList<>();
            if (tarr != null) {
                for (int j = 0; j < tarr.size(); j++) {
                    tags.add(tarr.getString(j));
                }
            }
            out.add(new CandidateView(o.getLong("gid"), o.getString("token"), o.getString("title"),
                    o.getDouble("similarity"), tags, o.getString("reason")));
        }
        return out;
    }
}
