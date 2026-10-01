package io.github.Nyameph.nyaentworks.song.service;

import cn.hutool.http.HtmlUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import io.github.Nyameph.nyaentworks.song.config.SongProperties;
import io.github.Nyameph.nyaentworks.song.entity.SongOriginalSetting;
import io.github.Nyameph.nyaentworks.song.mapper.SongOriginalSettingMapper;
import io.github.Nyameph.nyaentworks.song.util.NeteaseEapi;
import io.github.Nyameph.nyaentworks.song.util.SongNameParser;
import io.github.Nyameph.nyaentworks.common.fileop.FileOpRecorder;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpLevel;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpModule;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpType;
import io.github.Nyameph.nyaentworks.common.tool.ExternalCommandProbe;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 从网络搜索并补全某原曲的歌手 / 原曲音频 / 歌词（搬迁自一次性脚本 {@code SongOriginalFetchUtil}）。
 *
 * <p>单曲「搜资源」按钮（{@code POST /api/song/template/search}）与批量补全脚本
 * {@code SongOriginalFetchUtil} 共用本类的 {@link #searchOne}，保证两条路径行为一致。
 * 原曲音频<b>只认未加密</b>（{@code looksLikeMp3} 只验 ID3/MPEG 帧同步），但网易云下到
 * {@code .ncm} 加密文件时不再放弃，而是调 {@code tools/ncm_decrypt.py} 就地解密成 flac/mp3；
 * 歌词是纯文本 LRC。
 *
 * <p><b>数据源（都只认未加密文件）</b>：
 * <ol>
 *   <li>QQ 音乐非官方接口 —— 负责<b>搜索 + 歌手 + 歌词</b>：搜索
 *       {@code c.y.qq.com/soso/fcgi-bin/client_search_cp}，歌词
 *       {@code c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg}。</li>
 *   <li>higequ（聚合站）—— 负责<b>搜索 + 歌词 + 音频</b>：搜索
 *       {@code higequ.com/s/<词>/} 服务端渲染 HTML，播放页
 *       {@code higequ.com/player/<rid>/} 里 base64 编码 mp3 直链（酷我 CDN）与逐行歌词。</li>
 *   <li>酷我 —— 负责<b>音频</b>：搜索 {@code search.kuwo.cn/r.s} 拿 {@code MUSICRID} →
 *       {@code antiserver.kuwo.cn/anti.s} 302 到 128k 未加密 mp3。</li>
 *   <li>网易云 VIP（需登录 cookie）—— 负责<b>音频</b>：搜索拿 id → eapi 加密接口
 *       {@code interface.music.163.com/eapi/song/enhance/player/url} 换 320k mp3 直链。
 *       配了 {@code nya-entworks.song.netease-cookie}（含 {@code MUSIC_U}）才启用，空则整段跳过。
 *       下到 {@code .ncm} 时用 {@code nya-entworks.song.ncm-decrypt-script} 就地解密。</li>
 *   <li>回退源（音频）：{@code a2.freemp3cloud.com} —— 服务端渲染的 POST 搜索。</li>
 * </ol>
 *
 * <p><b>限流轮换</b>：QQ / 酷我 / 网易云任一源被判限流后进入冷却（{@code SOURCE_COOLDOWN_MS}），
 * 期间跳过该源换下一源 —— artist/歌词走 QQ → higequ → 网易云，音频走酷我 → 网易云VIP → 网易云 →
 * higequ → freemp3cloud。网易云搜索对 Java JSSE 一律 405「电量不足」，故搜索走 curl，
 * 歌词走 hutool，eapi POST 先走 hutool、405 时 curl 兜底。<b>curl 缺失时网易云整源跳过</b>
 * （开关见 {@link #curlEnabled()}，与自检页同一处探测，2026-09-24 裁决的方案 b）。
 * <b>python 缺失时 .ncm 解密失败但只是跳过该文件</b>（本机那条路的外部依赖，见 4.2 裁决）。
 *
 * <p><b>{@code searchOne} 本身不休眠</b>：单曲按钮遇到全源限流时，把
 * {@code allSourcesLimited} 带进返回结果提示「稍后再试」；只有批量脚本在
 * {@code allSourcesLimited} 时调 {@link #sleepAllSources()}。
 */
@Service
@RequiredArgsConstructor
public class SongResourceSearchService {

    private static final Logger log = LoggerFactory.getLogger(SongResourceSearchService.class);

    /** 批量补全时，距上次成功搜索不足该小时数的原曲跳过（用户确认时间窗口 = 1 天） */
    public static final int SKIP_HOURS = 24;

    /** 所有源都限流时批量脚本的休眠时长（单曲按钮不睡，直接提示稍后再试） */
    public static final long ALL_LIMITED_SLEEP_MS = 10 * 60_000L;

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36";
    private static final String QQ_REFERER = "https://y.qq.com/portal/search.html";
    private static final String QQ_LYRIC_REFERER = "https://y.qq.com/portal/player.html";
    private static final String HIGEQU_REFERER = "https://higequ.com/";
    private static final String KUWO_REFERER = "http://www.kuwo.cn/";
    private static final String FMP3_REFERER = "https://a2.freemp3cloud.com/";
    private static final String NETEASE_REFERER = "https://music.163.com/";

    /** 两次搜索之间的停顿范围（毫秒）：在 [MIN, MAX] 内随机，固定间隔是爬虫特征，随机才不易被限流接口识别 */
    private static final long SEARCH_DELAY_MIN_MS = 1000;
    private static final long SEARCH_DELAY_MAX_MS = 2500;

    /** 单源被判定限流后的冷却时长（期间跳过该源，换下一源） */
    private static final long SOURCE_COOLDOWN_MS = 5 * 60_000L;

    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("__RequestVerificationToken\" type=\"hidden\" value=\"([^\"]+)\"");

    /** higequ 搜索页每个结果块：rid + 标题 + 歌手（服务端渲染 HTML） */
    private static final Pattern HIGEQU_ITEM_PATTERN = Pattern.compile(
            "class=\"result-item\"\\s+data-rid=\"(\\d+)\"[^>]*>.*?"
                    + "class=\"result-title\">([^<]*)<.*?"
                    + "class=\"result-artist\">([^<]*)<",
            Pattern.DOTALL);

    /** higequ 播放页内联 JS 里 base64 编码的 mp3 直链 */
    private static final Pattern HIGEQU_CODE_PATTERN = Pattern.compile("let code\\s*=\\s*\"([^\"]+)\"");

    /** higequ 播放页逐行歌词：data-time 秒 + 文本 */
    private static final Pattern HIGEQU_LYRIC_PATTERN = Pattern.compile(
            "class=\"lyric-line\"\\s+data-time=\"([\\d.]+)\"[^>]*>([^<]*)<");

    /** 候选标题里带这些「非原唱/改编」标记的，视为翻唱/改编，匹配时降权 */
    private static final Pattern COVER_MARKER = Pattern.compile(
            "(?i)(翻唱|cover|remix|dj版|伴奏|纯音乐|纯享|串烧|抖音|ktv|演唱会|现场|live|加速|降调|升调|男版|女版|改编|二创|消音|无人声)");

    /** artist 长度上限：太长（多 feat / 拼接异常）只留前 100 字符，也在 DB VARCHAR(200) 之内 */
    private static final int MAX_ARTIST_LEN = 100;

    private final SongOriginalSettingMapper originalMapper;
    private final SongProperties properties;
    private final SongSettingService settingService;
    /** python / curl 的探测（缓存一次）。curl 开关的初值来自它，与自检页同一处探（4.1 / 4.3） */
    private final ExternalCommandProbe toolProbe;

    // ===== 轮换状态：各源限流后的冷却截止时间戳（毫秒；0 = 未限流）=====
    private long qqCoolUntil = 0;
    private long higequCoolUntil = 0;
    private long kuwoCoolUntil = 0;
    private long neteaseCoolUntil = 0;

    /** 批量补全暂存：{@link #searchOneDeferred} 攒下的待落库行，{@link #flushPendingWrites()} 统一写 */
    private final List<PendingWrite> pendingWrites = new ArrayList<>();

    /** 延迟落库的一行：把「搜到的结果 + 该行是新增还是更新」打包，等攒够一批再写 */
    private record PendingWrite(SongOriginalSetting row, boolean isNew, String artist,
            String lyric, String original, boolean executed, LocalDateTime now) {
    }

    /** 一次命中的候选：{@code id} 对 QQ 是 songmid、对酷我是 MUSICRID；{@code durationSec} 时长（秒，0 = 未知/无该字段） */
    record SongHit(String id, String name, List<String> artists, int durationSec) {
        String artistText() {
            return String.join(" / ", artists);
        }
    }

    /** 带状态码的 GET 结果（限流判定要看 HTTP 状态与正文） */
    private record HttpResult(int status, String body) {}

    /** 带状态码的字节下载结果 */
    private record ByteResult(int status, byte[] body) {}

    /**
     * 一次搜索的结果：补到了什么（没补到为 null）、是否因全源限流被跳过、以及给人看的中文文案。
     *
     * @param artist            补到的歌手，没补到为 null
     * @param original          补到的原曲文件名（含扩展名），没补到为 null
     * @param lyric             补到的歌词文件名（含扩展名），没补到为 null
     * @param allSourcesLimited 某一步需要搜、但所需源都处于限流冷却（本次不算「成功搜索」）
     * @param message           给人看的中文文案（作为字段返回，前端 toast 直接展示）
     */
    public record SearchResult(String artist, String original, String lyric,
                               boolean allSourcesLimited, String message) {

        static SearchResult of(String artist, String original, String lyric,
                               boolean allSourcesLimited) {
            return new SearchResult(artist, original, lyric, allSourcesLimited,
                    buildMessage(artist, original, lyric, allSourcesLimited));
        }

        private static String buildMessage(String artist, String original, String lyric,
                                           boolean allSourcesLimited) {
            if (allSourcesLimited) {
                return "所有音乐源都被限流了，稍后再试";
            }
            List<String> parts = new ArrayList<>();
            if (artist != null) {
                parts.add("歌手「" + artist + "」");
            }
            if (original != null) {
                parts.add("原曲音频");
            }
            if (lyric != null) {
                parts.add("歌词");
            }
            if (parts.isEmpty()) {
                return "搜过了，但没找到可用的未加密资源";
            }
            return "已补全 " + String.join("、", parts);
        }
    }

    /** 距上次成功搜索不足 {@link #SKIP_HOURS} 小时则返回 true（批量据此跳过） */
    public boolean recentlySearched(SongOriginalSetting row) {
        if (row == null || row.getLastSearchTime() == null) {
            return false;
        }
        return row.getLastSearchTime().isAfter(LocalDateTime.now().minusHours(SKIP_HOURS));
    }

    /**
     * 对单首原曲搜索并补全缺失字段（只补空着的，已填的不动）。串行化，共享各源限流冷却。
     *
     * <p>「成功」= 至少有一处需要搜、且没有哪一步因接口报错 / 全源限流被跳过 —— 哪怕
     * 没补到数据也算搜过了（冷门曲搜不到不怪接口），会记 {@code last_search_time}。
     * 三字段都齐则什么都不做，不记时间。
     *
     * @param originalTitle  原曲名原文；批量脚本传 rawName，单曲按钮传 originalTitle
     * @param originalArtist 原曲作者（原唱）；旧格式文件名为 {@code null}，按同名原曲取第一个
     * @param justTest         true 只打印不落盘不写库（批量预演用）
     */
    public synchronized SearchResult searchOne(String originalTitle, String originalArtist,
                                               boolean justTest) {
        return doSearch(originalTitle, originalArtist, justTest, false);
    }

    /**
     * 批量补全脚本用：与 {@link #searchOne} 同逻辑，但落库延迟 —— 不在每行结束时写，
     * 而是攒进 {@link #pendingWrites}，攒够一批由 {@link #flushPendingWrites()} 统一写。
     * 搜索（含下载歌词/音频）仍然立即执行，只有 DB 写入被推迟。
     */
    public synchronized SearchResult searchOneDeferred(String originalTitle, String originalArtist,
                                                       boolean justTest) {
        return doSearch(originalTitle, originalArtist, justTest, true);
    }

    private SearchResult doSearch(String originalTitle, String originalArtist,
                                  boolean justTest, boolean defer) {
        String title = StringUtils.trimToNull(originalTitle);
        if (title == null) {
            throw new IllegalArgumentException("原曲名不能为空");
        }

        // 按 (原曲名, 原曲作者) 定位设置：新格式精确匹配，旧格式（作者为空）同名取第一个。
        // 没有就建一条空设置（与统计页补建同一口径），非 justTest 时在 persist 里真正落库。
        SongOriginalSetting row = SongSettingService.matchOriginal(
                settingService.allOriginals(), title, StringUtils.trimToNull(originalArtist));
        boolean isNew = row == null;
        if (isNew) {
            row = new SongOriginalSetting();
            row.setRawName(title);
            row.setArtist(StringUtils.trimToNull(originalArtist));
        }
        String rawName = StringUtils.defaultIfBlank(row.getRawName(), title);

        // check=1 表示该字段已手动确认（哪怕是空、也是用户有意留空），不再尝试补全
        boolean needArtist = StringUtils.isBlank(row.getArtist())
                && !Boolean.TRUE.equals(row.getArtistCheck());
        boolean needLyric = StringUtils.isBlank(row.getLyricFileName())
                && !Boolean.TRUE.equals(row.getLyricCheck());
        boolean needAudio = StringUtils.isBlank(row.getOriginalFileName())
                && !Boolean.TRUE.equals(row.getOriginalCheck());

        // artist 已确认时，搜索/下载用「原曲名 + 歌手」优先定位到该歌手版本；搜不到再退回纯原曲名
        String confirmedArtist = Boolean.TRUE.equals(row.getArtistCheck())
                ? StringUtils.trimToNull(row.getArtist()) : null;

        if (!needArtist && !needLyric && !needAudio) {
            return SearchResult.of(null, null, null, false);
        }

        String artist = null, original = null, lyric = null;
        boolean allSourcesLimited = false;

        // 1) artist + 歌词：都依赖搜索（拿源内 id）。QQ 优先，限流换 higequ / 网易云。
        if (needArtist || needLyric) {
            SongHit hit = null;
            String hitSource = null;

            if (!inCooldown(qqCoolUntil)) {
                List<SongHit> candidates = searchQqCandidates(rawName, confirmedArtist);
                if (!candidates.isEmpty()) {
                    SongHit matched = bestMatch(rawName, candidates, confirmedArtist);
                    if (matched != null) {
                        hit = matched;
                        hitSource = "QQ";
                        log.info("[搜索·QQ] \"{}\" → {} ({})  {}",
                                rawName, hit.name(), hit.artistText(), candidatesSummary(rawName, candidates));
                    } else {
                        log.info("[搜索·QQ] \"{}\" → 候选都对不上（标题/歌手），判无结果", rawName);
                    }
                } else if (!inCooldown(qqCoolUntil)) {
                    log.info("[搜索·QQ] \"{}\" → 无结果", rawName);
                }
            } else {
                log.info("[搜索] QQ 冷却中，跳过");
            }

            if (hit == null && !inCooldown(higequCoolUntil)) {
                List<SongHit> candidates = searchHigequCandidates(rawName, confirmedArtist);
                if (!candidates.isEmpty()) {
                    SongHit matched = bestMatch(rawName, candidates, confirmedArtist);
                    if (matched != null) {
                        hit = matched;
                        hitSource = "HIGEQU";
                        log.info("[搜索·higequ] \"{}\" → {} ({})  {}",
                                rawName, hit.name(), hit.artistText(), candidatesSummary(rawName, candidates));
                    } else {
                        log.info("[搜索·higequ] \"{}\" → 候选都对不上（标题/歌手），判无结果", rawName);
                    }
                } else if (!inCooldown(higequCoolUntil)) {
                    log.info("[搜索·higequ] \"{}\" → 无结果", rawName);
                }
            } else if (hit == null) {
                log.info("[搜索] higequ 冷却中，跳过");
            }

            if (hit == null && !inCooldown(neteaseCoolUntil)) {
                List<SongHit> candidates = searchNeteaseCandidates(rawName, confirmedArtist);
                if (!candidates.isEmpty()) {
                    SongHit matched = bestMatch(rawName, candidates, confirmedArtist);
                    if (matched != null) {
                        hit = matched;
                        hitSource = "NETEASE";
                        log.info("[搜索·网易云] \"{}\" → {} ({})  {}",
                                rawName, hit.name(), hit.artistText(), candidatesSummary(rawName, candidates));
                    } else {
                        log.info("[搜索·网易云] \"{}\" → 候选都对不上（标题/歌手），判无结果", rawName);
                    }
                } else if (!inCooldown(neteaseCoolUntil)) {
                    log.info("[搜索·网易云] \"{}\" → 无结果", rawName);
                }
            } else if (hit == null) {
                log.info("[搜索] 网易云冷却中，跳过");
            }

            // 需要搜索但三个源都限流 → 本次算「因限流跳过」
            if (hit == null && inCooldown(qqCoolUntil) && inCooldown(higequCoolUntil)
                    && inCooldown(neteaseCoolUntil)) {
                allSourcesLimited = true;
            }

            if (hit != null) {
                if (needArtist && StringUtils.isNotBlank(hit.artistText())) {
                    artist = truncateArtist(hit.artistText());
                }
                if (needLyric) {
                    String lrc = switch (hitSource) {
                        case "QQ" -> qqLyric(hit.id());
                        case "HIGEQU" -> higequPlayer(hit.id()).lrc();
                        default -> neteaseLyric(hit.id());
                    };
                    if (StringUtils.isNotBlank(lrc)) {
                        String fileName = lyricBaseName(row, rawName) + ".lrc";
                        Path target = templateDir(row, rawName).resolve(fileName);
                        if (justTest) {
                            log.info("[歌词] 将写 {}", target);
                            lyric = fileName;
                        } else if (writeFile(target, lrc.getBytes(StandardCharsets.UTF_8))) {
                            lyric = fileName;
                        }
                    } else {
                        log.info("[歌词] \"{}\" → {} 无歌词", rawName, hitSource);
                    }
                }
            }
        }

        // 2) 原曲音频：独立于上一步，走酷我 → 网易云VIP(配cookie时) → 网易云 → higequ → freemp3cloud。
        // 搜索 hint 用「已确认的 artist，否则本次刚搜到的 artist」——让音频命中与 artist 同一版本，
        // 而不是只在 artistCheck=true 时才用（那是同名异曲/翻唱被下错的主因）。
        String audioArtistHint = confirmedArtist != null ? confirmedArtist : artist;
        if (needAudio) {
            String fileName = safeFileName(rawName) + ".mp3";
            if (justTest) {
                log.info("[原曲] 将写 {}", Path.of(properties.getTemplateDir())
                        .resolve(safeFileName(templateBaseName(row, rawName))).resolve(fileName));
                original = fileName;
            } else {
                Path target = templateDir(row, rawName).resolve(fileName);
                // 实际写出的文件名（含扩展名）：ncm 解密可能是 .flac，与预想的 .mp3 不同
                String audioFile = null;
                if (!inCooldown(kuwoCoolUntil)) {
                    audioFile = downloadKuwoAudio(rawName, audioArtistHint, target);
                } else {
                    log.info("[原曲] 酷我冷却中，跳过");
                }
                if (audioFile == null && neteaseVipEnabled()) {
                    if (!inCooldown(neteaseCoolUntil)) {
                        audioFile = downloadNeteaseVipAudio(rawName, audioArtistHint, target);
                    } else {
                        log.info("[原曲] 网易云VIP冷却中，跳过");
                    }
                }
                if (audioFile == null && !inCooldown(neteaseCoolUntil)) {
                    audioFile = downloadNeteaseAudio(rawName, audioArtistHint, target);
                } else if (audioFile == null) {
                    log.info("[原曲] 网易云冷却中，跳过");
                }
                if (audioFile == null && !inCooldown(higequCoolUntil)) {
                    audioFile = downloadHigequAudio(rawName, audioArtistHint, target);
                } else if (audioFile == null) {
                    log.info("[原曲] higequ 冷却中，跳过");
                }
                // freemp3cloud 是聚合站，命中靠运气、不按限流轮换，作为最后一搏
                if (audioFile == null) {
                    audioFile = downloadFreemp3(rawName, audioArtistHint, target);
                }
                // 酷我 + 网易云 + higequ 都限流 → 本次算「因限流跳过」
                if (audioFile == null && inCooldown(kuwoCoolUntil) && inCooldown(neteaseCoolUntil)
                        && inCooldown(higequCoolUntil)) {
                    allSourcesLimited = true;
                }
                if (audioFile != null) {
                    original = audioFile;
                } else {
                    log.info("[原曲] \"{}\" → 各源均未拿到未加密 mp3", rawName);
                }
            }
        }

        boolean executed = !allSourcesLimited;

        if (!justTest) {
            LocalDateTime now = LocalDateTime.now();
            if (defer) {
                pendingWrites.add(new PendingWrite(row, isNew, artist, lyric, original, executed, now));
            } else {
                persist(row, isNew, artist, lyric, original, executed, now);
            }
        }

        return SearchResult.of(artist, original, lyric, allSourcesLimited);
    }

    /** 把一行的搜索结果写进库：新增则 insert，已有则只更新补到的字段 */
    private void persist(SongOriginalSetting row, boolean isNew, String artist,
            String lyric, String original, boolean executed, LocalDateTime now) {
        if (isNew) {
            row.setArtist(artist);
            row.setLyricFileName(lyric);
            row.setOriginalFileName(original);
            if (executed) {
                row.setLastSearchTime(now);
            }
            originalMapper.insert(row);
        } else {
            LambdaUpdateWrapper<SongOriginalSetting> uw =
                    Wrappers.<SongOriginalSetting>lambdaUpdate();
            if (artist != null) {
                uw.set(SongOriginalSetting::getArtist, artist);
            }
            if (lyric != null) {
                uw.set(SongOriginalSetting::getLyricFileName, lyric);
            }
            if (original != null) {
                uw.set(SongOriginalSetting::getOriginalFileName, original);
            }
            if (executed) {
                uw.set(SongOriginalSetting::getLastSearchTime, now);
            }
            uw.set(SongOriginalSetting::getUpdateTime, now)
                    .eq(SongOriginalSetting::getId, row.getId());
            originalMapper.update(null, uw);
        }
    }

    /**
     * 把 {@link #searchOneDeferred} 暂存的行一次性写进库，返回写入行数。批量脚本每处理
     * 若干首（如 10 首）调一次，收尾时再调一次冲掉不足一批的余量。
     */
    public synchronized int flushPendingWrites() {
        if (pendingWrites.isEmpty()) {
            return 0;
        }
        List<PendingWrite> batch = new ArrayList<>(pendingWrites);
        pendingWrites.clear();
        for (PendingWrite w : batch) {
            persist(w.row(), w.isNew(), w.artist(), w.lyric(), w.original(), w.executed(), w.now());
        }
        return batch.size();
    }

    /** 批量脚本在 {@code allSourcesLimited} 时调用：休眠一段，睡醒后重置全部冷却 */
    public synchronized void sleepAllSources() {
        log.warn("[轮换] 所有源均限流，休眠 {} 分钟…", ALL_LIMITED_SLEEP_MS / 60_000);
        sleepQuietly(ALL_LIMITED_SLEEP_MS);
        qqCoolUntil = 0;
        higequCoolUntil = 0;
        kuwoCoolUntil = 0;
        neteaseCoolUntil = 0;
    }

    // ==================== QQ 音乐：搜索 + 歌词 ====================

    /** QQ 搜索，返回若干候选（按相关度排序，前 10）；无结果返回空表 */
    private List<SongHit> searchQqCandidates(String rawName, String artistHint) {
        for (String query : searchQueries(rawName, artistHint)) {
            try {
                sleepSearchDelay();
                // 不要加 new_json=1：加了以后 song.list 会变空（旧格式才返回结果）。
                String url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=" + urlEncode(query)
                        + "&format=json&p=1&n=10&cr=1";
                HttpResult res = httpGetRaw(url, QQ_REFERER);
                if (res.status() >= 400 || isRateLimited(res.body())) {
                    log.warn("[限流] QQ 搜索被限流，冷却 {} 分钟", SOURCE_COOLDOWN_MS / 60_000);
                    qqCoolUntil = System.currentTimeMillis() + SOURCE_COOLDOWN_MS;
                    return List.of();
                }
                JSONObject data = JSON.parseObject(res.body()).getJSONObject("data");
                JSONObject song = data == null ? null : data.getJSONObject("song");
                JSONArray list = song == null ? null : song.getJSONArray("list");
                if (list == null || list.isEmpty()) {
                    continue;
                }
                List<SongHit> out = new ArrayList<>();
                for (int i = 0; i < list.size(); i++) {
                    JSONObject s = list.getJSONObject(i);
                    String mid = s.getString("songmid");
                    String name = s.getString("songname");
                    List<String> artists = new ArrayList<>();
                    JSONArray arr = s.getJSONArray("singer");
                    if (arr != null) {
                        for (int j = 0; j < arr.size(); j++) {
                            String an = arr.getJSONObject(j).getString("name");
                            if (StringUtils.isNotBlank(an)) {
                                artists.add(an);
                            }
                        }
                    }
                    out.add(new SongHit(mid, name, artists, s.getIntValue("interval", 0)));
                }
                if (!out.isEmpty()) {
                    return out;
                }
            } catch (Exception e) {
                log.warn("[搜索] QQ 查询 \"{}\" 失败：{}", query, e.getMessage());
            }
        }
        return List.of();
    }

    /** QQ 歌词，纯文本 LRC；无歌词 / 无版权时返回 null */
    private String qqLyric(String songmid) {
        try {
            String url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=" + songmid
                    + "&nobase64=1&format=json";
            JSONObject json = JSON.parseObject(httpGet(url, QQ_LYRIC_REFERER));
            String lyric = json.getString("lyric");
            return StringUtils.isBlank(lyric) ? null : lyric;
        } catch (Exception e) {
            log.warn("[歌词] QQ 歌词接口失败：{}", e.getMessage());
            return null;
        }
    }

    // ==================== higequ：搜索 + 歌词 + 音频（聚合站，酷我直链）====================

    /** higequ 搜索，返回候选（rid 作 id）；无结果返回空表 */
    private List<SongHit> searchHigequCandidates(String rawName, String artistHint) {
        for (String query : searchQueries(rawName, artistHint)) {
            try {
                sleepSearchDelay();
                String url = "https://higequ.com/s/" + urlEncode(query) + "/";
                HttpResult res = httpGetRaw(url, HIGEQU_REFERER);
                if (res.status() >= 400 || isRateLimited(res.body())) {
                    log.warn("[限流] higequ 搜索被限流，冷却 {} 分钟", SOURCE_COOLDOWN_MS / 60_000);
                    higequCoolUntil = System.currentTimeMillis() + SOURCE_COOLDOWN_MS;
                    return List.of();
                }
                List<SongHit> hits = parseHigequSearch(res.body());
                if (!hits.isEmpty()) {
                    return hits;
                }
            } catch (Exception e) {
                log.warn("[搜索] higequ 查询 \"{}\" 失败：{}", query, e.getMessage());
            }
        }
        return List.of();
    }

    /** 解析 higequ 搜索页（服务端渲染 HTML）里的 result-item */
    private static List<SongHit> parseHigequSearch(String html) {
        List<SongHit> out = new ArrayList<>();
        if (StringUtils.isBlank(html)) {
            return out;
        }
        Matcher m = HIGEQU_ITEM_PATTERN.matcher(html);
        while (m.find()) {
            String title = HtmlUtil.unescape(m.group(2)).trim();
            String artist = HtmlUtil.unescape(m.group(3)).trim();
            out.add(new SongHit(m.group(1), title,
                    StringUtils.isBlank(artist) ? List.of() : List.of(artist), 0));
        }
        return out;
    }

    /** higequ 播放页里能拿到的东西：base64 解码后的 mp3 直链 + 逐行歌词拼成的 LRC */
    private record HigequPlayer(String audioUrl, String lrc) {
    }

    /** 抓 higequ 播放页，一次解析出 mp3 直链与 LRC；抓取/解析失败时对应字段为 null */
    private HigequPlayer higequPlayer(String rid) {
        try {
            String html = httpGet("https://higequ.com/player/" + rid + "/", HIGEQU_REFERER);
            return new HigequPlayer(extractHigequAudioUrl(html), extractHigequLrc(html));
        } catch (Exception e) {
            log.warn("[higequ] 播放页 {} 抓取失败：{}", rid, e.getMessage());
            return new HigequPlayer(null, null);
        }
    }

    /** 从播放页内联 JS {@code let code = "..."} 里 base64 解码 mp3 直链；没有/解码失败返回 null */
    private static String extractHigequAudioUrl(String html) {
        Matcher m = HIGEQU_CODE_PATTERN.matcher(html);
        if (!m.find()) {
            return null;
        }
        try {
            return new String(Base64.getDecoder().decode(m.group(1)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            log.warn("[higequ] mp3 直链 base64 解码失败");
            return null;
        }
    }

    /** 从播放页 {@code .lyric-line data-time} 拼成 LRC；无歌词返回 null */
    private static String extractHigequLrc(String html) {
        Matcher m = HIGEQU_LYRIC_PATTERN.matcher(html);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            sb.append(formatLrcTime(Double.parseDouble(m.group(1))))
                    .append(m.group(2).trim()).append('\n');
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** higequ 下载原曲音频：搜索拿 rid → 播放页 base64 解码 mp3 直链 → 验非加密后落盘 */
    private String downloadHigequAudio(String rawName, String artistHint, Path target) {
        List<SongHit> hits = searchHigequCandidates(rawName, artistHint);
        if (hits.isEmpty()) {
            return null;
        }
        SongHit best = bestMatch(rawName, hits, artistHint);
        if (best == null) {
            return null;
        }
        String audioUrl = higequPlayer(best.id()).audioUrl();
        if (StringUtils.isBlank(audioUrl)) {
            log.info("[原曲] higequ {} 拿不到 mp3 直链", best.name());
            return null;
        }
        try {
            byte[] bytes = httpGetBytes(audioUrl, HIGEQU_REFERER);
            if (!looksLikeMp3(bytes)) {
                log.info("[原曲] higequ {} 非未加密 mp3", best.name());
                return null;
            }
            return writeFile(target, bytes) ? target.getFileName().toString() : null;
        } catch (Exception e) {
            log.warn("[原曲] higequ 下载失败：{}", e.getMessage());
            return null;
        }
    }

    /** 秒 → LRC 时间戳 {@code [mm:ss.xx]} */
    private static String formatLrcTime(double seconds) {
        int total = (int) seconds;
        int hundredths = (int) Math.round((seconds - total) * 100);
        if (hundredths == 100) {
            total++;
            hundredths = 0;
        }
        return String.format(Locale.ROOT, "[%02d:%02d.%02d]", total / 60, total % 60, hundredths);
    }

    // ==================== 网易云：搜索 + 歌词 + 音频（限流兜底）====================

    /**
     * 网易云搜索。Java 的 JSSE 会被 405「电量不足」拦（TLS 指纹），故搜索走 curl；
     * 歌词/音频接口不拦 Java，仍用 hutool。限流（操作频繁/405）则置冷却并返回空表。
     *
     * <p><b>curl 开关（《桌面化收尾（2026-09-25 实施）》4.3，方案 b —— docs/已完成/桌面壳实施计划.md）</b>：与 {@link #neteaseVipEnabled()}
     * 同形的判据（探测一次 curl 能不能起，见 {@link #curlEnabled()}），为假时整源跳过
     * 并留一句人话 —— 不抛异常、也不静默（自检页会报 curl 缺失）。
     */
    private List<SongHit> searchNeteaseCandidates(String rawName, String artistHint) {
        if (!curlEnabled()) {
            log.info("[搜索] curl 不可用，网易云这一源跳过（其余源照常；自检页有说明）");
            return List.of();
        }
        for (String query : searchQueries(rawName, artistHint)) {
            try {
                sleepSearchDelay();
                String url = "https://music.163.com/api/search/get/web?csrf_token=&s=" + urlEncode(query)
                        + "&type=1&offset=0&limit=10";
                String body = curlGet(url, NETEASE_REFERER);
                if (isRateLimited(body)) {
                    log.warn("[限流] 网易云搜索被限流，冷却 {} 分钟", SOURCE_COOLDOWN_MS / 60_000);
                    neteaseCoolUntil = System.currentTimeMillis() + SOURCE_COOLDOWN_MS;
                    return List.of();
                }
                JSONObject result = JSON.parseObject(body).getJSONObject("result");
                JSONArray songs = result == null ? null : result.getJSONArray("songs");
                if (songs == null || songs.isEmpty()) {
                    continue;
                }
                List<SongHit> out = new ArrayList<>();
                for (int i = 0; i < songs.size(); i++) {
                    JSONObject s = songs.getJSONObject(i);
                    long id = s.getLongValue("id");
                    String name = s.getString("name");
                    JSONArray arr = s.getJSONArray("artists");
                    if (arr == null) {
                        arr = s.getJSONArray("ar");
                    }
                    List<String> artists = new ArrayList<>();
                    if (arr != null) {
                        for (int j = 0; j < arr.size(); j++) {
                            String an = arr.getJSONObject(j).getString("name");
                            if (StringUtils.isNotBlank(an)) {
                                artists.add(an);
                            }
                        }
                    }
                    if (id > 0 && StringUtils.isNotBlank(name)) {
                        // duration / dt 都是毫秒，折成秒；0 = 未知
                        long durMs = s.getLongValue("duration", s.getLongValue("dt", 0));
                        out.add(new SongHit(String.valueOf(id), name, artists, (int) (durMs / 1000)));
                    }
                }
                if (!out.isEmpty()) {
                    return out;
                }
            } catch (Exception e) {
                log.warn("[搜索] 网易云查询 \"{}\" 失败：{}", query, e.getMessage());
            }
        }
        return List.of();
    }

    /** 网易云歌词，纯文本 LRC；无歌词返回 null */
    private String neteaseLyric(String songId) {
        try {
            String url = "https://music.163.com/api/song/lyric?id=" + songId + "&lv=1&kv=1&tv=-1";
            JSONObject json = JSON.parseObject(httpGet(url, NETEASE_REFERER));
            JSONObject lrc = json.getJSONObject("lrc");
            String lyric = lrc == null ? null : lrc.getString("lyric");
            return StringUtils.isBlank(lyric) ? null : lyric;
        } catch (Exception e) {
            log.warn("[歌词] 网易云歌词接口失败：{}", e.getMessage());
            return null;
        }
    }

    /** 网易云下载原曲音频：搜索拿 id → outer/url 302 到 mp3；下到 .ncm 加密文件则就地解密 */
    private String downloadNeteaseAudio(String rawName, String artistHint, Path target) {
        List<SongHit> hits = searchNeteaseCandidates(rawName, artistHint);
        if (hits.isEmpty()) {
            return null;
        }
        SongHit best = bestMatch(rawName, hits, artistHint);
        if (best == null) {
            return null;
        }
        try {
            String audioUrl = "https://music.163.com/song/media/outer/url?id=" + best.id() + ".mp3";
            byte[] bytes = httpGetBytes(audioUrl, NETEASE_REFERER);
            if (looksLikeMp3(bytes)) {
                return writeFile(target, bytes) ? target.getFileName().toString() : null;
            }
            if (looksLikeNcm(bytes)) {
                return decryptNcm(bytes, safeFileName(rawName), target.getParent());
            }
            log.info("[原曲] 网易云 {} 无未加密 mp3（可能 VIP）", best.name());
            return null;
        } catch (Exception e) {
            log.warn("[原曲] 网易云下载失败：{}", e.getMessage());
            return null;
        }
    }

    /** 是否启用网易云 VIP 直链渠道（配了 neteaseCookie 才算） */
    private boolean neteaseVipEnabled() {
        return StringUtils.isNotBlank(properties.getNeteaseCookie());
    }

    /**
     * curl 可用吗（网易云搜索与 eapi 兜底的两个 curl 调用点的共同开关）。
     *
     * <p>与 {@link #neteaseVipEnabled()} 同形，但初值不是配置而是<b>探测</b>
     * （{@link ExternalCommandProbe#canRun}，探一次、缓存 —— 与自检页那份是同一个）：
     * 缺 curl 时网易云搜索整源跳过、eapi 不做 curl 兜底，都留一句人话日志，
     * 不抛异常。这台机器装没装 curl 是<b>本机差异</b>，跟 cookie 一样不进配置页。
     */
    private boolean curlEnabled() {
        return toolProbe.canRun("curl");
    }

    /**
     * 网易云 VIP 直链下载（eapi）：搜索拿 id → eapi 换 320k mp3 直链 → 下载。
     * 只认未加密（looksLikeMp3）；下到 .ncm 加密文件则就地解密。URL 为空
     * （需更高会员/未授权）返回 null。限流置冷却。需要登录 cookie（{@code MUSIC_U}），
     * 未配时整段跳过（{@link #neteaseVipEnabled()}）。
     */
    private String downloadNeteaseVipAudio(String rawName, String artistHint, Path target) {
        if (!neteaseVipEnabled()) {
            return null;
        }
        List<SongHit> hits = searchNeteaseCandidates(rawName, artistHint);
        if (hits.isEmpty()) {
            return null;
        }
        SongHit best = bestMatch(rawName, hits, artistHint);
        if (best == null) {
            return null;
        }
        try {
            // eapi 接口：ids 必须是「字符串化的 JSON 数组」，body 里再整体 JSON 序列化
            String json = "{\"ids\":\"[" + best.id() + "]\",\"br\":320000}";
            String params = NeteaseEapi.buildParams("/api/song/enhance/player/url", json);
            HttpResult res = eapiPost("https://interface.music.163.com/eapi/song/enhance/player/url", params);
            String body = res.body();
            if (res.status() >= 400 || isRateLimited(body)) {
                log.warn("[限流] 网易云 eapi 被限流，冷却 {} 分钟", SOURCE_COOLDOWN_MS / 60_000);
                neteaseCoolUntil = System.currentTimeMillis() + SOURCE_COOLDOWN_MS;
                return null;
            }
            JSONObject jsonRes = JSON.parseObject(body);
            JSONArray data = jsonRes == null ? null : jsonRes.getJSONArray("data");
            String url = null;
            if (data != null && !data.isEmpty()) {
                url = data.getJSONObject(0).getString("url");
            }
            if (StringUtils.isBlank(url)) {
                log.info("[原曲] 网易云VIP {} 无直链（需更高会员或未授权）", best.name());
                return null;
            }
            byte[] bytes = httpGetBytes(url, NETEASE_REFERER);
            if (looksLikeMp3(bytes)) {
                return writeFile(target, bytes) ? target.getFileName().toString() : null;
            }
            if (looksLikeNcm(bytes)) {
                return decryptNcm(bytes, safeFileName(rawName), target.getParent());
            }
            log.info("[原曲] 网易云VIP {} 非未加密 mp3", best.name());
            return null;
        } catch (Exception e) {
            log.warn("[原曲] 网易云VIP下载失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 调网易云 eapi 接口。Java 的 JSSE 对网易云常被 405「电量不足」拦（TLS 指纹），
     * 先走 hutool，405 / 限流命中时用 curl POST 兜底（对称于搜索的 {@link #curlGet}）。
     */
    private HttpResult eapiPost(String url, String params) {
        HttpResult res;
        try {
            HttpResponse r = HttpRequest.post(url)
                    .header("User-Agent", UA)
                    .header("Referer", NETEASE_REFERER)
                    .header("Cookie", properties.getNeteaseCookie())
                    .form("params", params)
                    .timeout(30000)
                    .execute();
            res = new HttpResult(r.getStatus(), r.body());
        } catch (Exception e) {
            res = new HttpResult(0, "");
        }
        // 0 = hutool 异常/握手失败，405 = JSSE 被拦「电量不足」——都退回 curl 再试一次
        if (res.status() == 0 || res.status() == 405 || isRateLimited(res.body())) {
            if (!curlEnabled()) {
                log.info("[eapi] curl 不可用，跳过兜底重试（自检页有说明）");
                return res;
            }
            try {
                return new HttpResult(200, curlPost(url, properties.getNeteaseCookie(), params));
            } catch (Exception e) {
                log.warn("[eapi] curl 兜底也失败：{}", e.getMessage());
                return res;
            }
        }
        return res;
    }

    /** curl POST（表单 params），仅网易云 eapi 兜底用 */
    private static String curlPost(String url, String cookie, String params) {
        ProcessBuilder pb = new ProcessBuilder(
                "curl", "-s", "-L",
                "-X", "POST",
                "-A", UA,
                "-e", NETEASE_REFERER,
                "-b", cookie,
                "-d", "params=" + params,
                "--max-time", "30",
                url);
        pb.redirectErrorStream(true);
        Process p;
        try {
            p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("curl POST 执行失败：" + e.getMessage(), e);
        }
    }

    // ==================== 酷我：音频 ====================

    /** 酷我下载原曲音频：搜索拿 MUSICRID → anti.s 302 到 mp3 → 验非加密后落盘 */
    private String downloadKuwoAudio(String rawName, String artistHint, Path target) {
        for (String query : searchQueries(rawName, artistHint)) {
            try {
                sleepSearchDelay();
                String url = "http://search.kuwo.cn/r.s?all=" + urlEncode(query)
                        + "&ft=music&itemset=web_2013&client=kt&pn=0&rn=10&rformat=json&encoding=utf8";
                HttpResult res = httpGetRaw(url, KUWO_REFERER);
                if (res.status() >= 400 || isRateLimited(res.body())) {
                    log.warn("[限流] 酷我搜索被限流，冷却 {} 分钟", SOURCE_COOLDOWN_MS / 60_000);
                    kuwoCoolUntil = System.currentTimeMillis() + SOURCE_COOLDOWN_MS;
                    return null;
                }
                List<SongHit> hits = parseKuwo(res.body());
                if (hits.isEmpty()) {
                    continue;
                }
                SongHit best = bestMatch(rawName, hits, artistHint);
                if (best == null) {
                    continue;
                }
                String mp3 = "http://antiserver.kuwo.cn/anti.s?type=convert_url&format=mp3&rid="
                        + best.id() + "&response=res";
                ByteResult ar = httpGetBytesRaw(mp3, KUWO_REFERER);
                if (ar.status() >= 400) {
                    log.warn("[限流] 酷我音频接口限流，冷却 {} 分钟", SOURCE_COOLDOWN_MS / 60_000);
                    kuwoCoolUntil = System.currentTimeMillis() + SOURCE_COOLDOWN_MS;
                    return null;
                }
                byte[] bytes = ar.body();
                if (!looksLikeMp3(bytes)) {
                    continue;
                }
                return writeFile(target, bytes) ? target.getFileName().toString() : null;
            } catch (Exception e) {
                log.warn("[原曲] 酷我下载失败：{}", e.getMessage());
            }
        }
        return null;
    }

    /**
     * 解析酷我搜索响应。它的响应是<b>单引号</b>的类 dict 文本（不是严格 JSON，fastjson 不吃），
     * 故用正则从 {@code abslist} 的每个 {@code {...}} 项里抽 MUSICRID / NAME / ARTIST。
     */
    private List<SongHit> parseKuwo(String body) {
        List<SongHit> out = new ArrayList<>();
        if (StringUtils.isBlank(body)) {
            return out;
        }
        int abs = body.indexOf("abslist");
        if (abs < 0) {
            return out;
        }
        Matcher m = Pattern.compile("\\{[^{}]+\\}").matcher(body.substring(abs));
        while (m.find()) {
            String item = m.group();
            String rid = extractField(item, "MUSICRID");
            String name = extractField(item, "NAME");
            String artist = extractField(item, "ARTIST");
            if (rid != null && name != null) {
                int dur = 0;
                String durStr = extractField(item, "DURATION");
                if (durStr != null) {
                    try {
                        dur = (int) Double.parseDouble(durStr);
                    } catch (NumberFormatException ignore) {
                        // 时长解析失败按未知处理（0），不影响命中
                    }
                }
                out.add(new SongHit(rid, name,
                        StringUtils.isBlank(artist) ? List.of() : List.of(artist), dur));
            }
        }
        return out;
    }

    /** 从 {@code 'key':'value'} 里抽 value（单引号格式） */
    private static String extractField(String item, String key) {
        Matcher m = Pattern.compile("'" + key + "':'([^']*)'").matcher(item);
        return m.find() ? m.group(1) : null;
    }

    // ==================== 回退源：freemp3cloud ====================

    /** freemp3cloud：GET 拿 antiforgery token → POST 搜索 → 解析结果页 mp3 直链 → 下载 */
    private String downloadFreemp3(String rawName, String artistHint, Path target) {
        try {
            String home = httpGet(FMP3_REFERER, FMP3_REFERER);
            String token = extractToken(home);
            if (StringUtils.isBlank(token)) {
                log.info("[原曲] freemp3cloud 拿不到 antiforgery token");
                return null;
            }
            for (String query : searchQueries(rawName, artistHint)) {
                HttpResponse resp = HttpRequest.post(FMP3_REFERER)
                        .header("User-Agent", UA)
                        .header("Referer", FMP3_REFERER)
                        .form("searchSong", query)
                        .form("__RequestVerificationToken", token)
                        .timeout(30000)
                        .execute();
                String html = resp.body();
                String name = downloadFirstMp3(html, target);
                if (name != null) {
                    return name;
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("[原曲] freemp3cloud 失败：{}", e.getMessage());
            return null;
        }
    }

    /** 从搜索结果 HTML 里挑第一个 mp3 直链下载（验非加密）；无则返回 null */
    private String downloadFirstMp3(String html, Path target) {
        Matcher m = Pattern.compile("https?://[^\"'\\s]+\\.mp3\\?[^\"'\\s]*").matcher(html);
        if (!m.find()) {
            return null;
        }
        String url = m.group();
        try {
            byte[] bytes = httpGetBytes(url, FMP3_REFERER);
            return (looksLikeMp3(bytes) && writeFile(target, bytes))
                    ? target.getFileName().toString() : null;
        } catch (Exception e) {
            log.warn("[原曲] freemp3cloud 下载失败：{}", e.getMessage());
            return null;
        }
    }

    // ==================== 内部 ====================

    private static String extractToken(String html) {
        Matcher m = TOKEN_PATTERN.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private HttpResult httpGetRaw(String url, String referer) {
        HttpResponse r = HttpRequest.get(url)
                .header("User-Agent", UA)
                .header("Referer", referer)
                .timeout(30000)
                .execute();
        return new HttpResult(r.getStatus(), r.body());
    }

    private String httpGet(String url, String referer) {
        return httpGetRaw(url, referer).body();
    }

    private ByteResult httpGetBytesRaw(String url, String referer) {
        HttpResponse r = HttpRequest.get(url)
                .header("User-Agent", UA)
                .header("Referer", referer)
                .timeout(60000)
                .execute();
        return new ByteResult(r.getStatus(), r.bodyBytes());
    }

    private byte[] httpGetBytes(String url, String referer) {
        return httpGetBytesRaw(url, referer).body();
    }

    /** curl 拉页面（仅网易云搜索需要：Java JSSE 被 405「电量不足」拦） */
    private static String curlGet(String url, String referer) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "curl", "-s", "-L",
                    "-A", UA,
                    "-e", referer,
                    "--max-time", "30",
                    url);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("curl 执行失败：" + e.getMessage(), e);
        }
    }

    /** 判定响应体是否像「限流/封禁」：命中关键词才算，避免把正常 JSON 当限流 */
    private static boolean isRateLimited(String body) {
        if (StringUtils.isBlank(body)) {
            return false;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        return lower.contains("频繁")
                || lower.contains("too many")
                || lower.contains("rate limit")
                || lower.contains("blocked")
                || lower.contains("电量不足")
                || lower.contains("ip限制")
                || lower.contains("请求受限")
                || lower.contains("封禁");
    }

    /** 该源是否仍处于限流冷却期 */
    private boolean inCooldown(long until) {
        return System.currentTimeMillis() < until;
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** 随机停顿：在 [SEARCH_DELAY_MIN_MS, SEARCH_DELAY_MAX_MS] 内随机，避免固定节奏被识别为爬虫 */
    private static void sleepSearchDelay() {
        long ms = SEARCH_DELAY_MIN_MS
                + (long) (Math.random() * (SEARCH_DELAY_MAX_MS - SEARCH_DELAY_MIN_MS + 1));
        sleepQuietly(ms);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 按标题给候选打分，取最高。
     *
     * <p>「不是原唱」的主要来源是搜索结果里混进的翻唱/改编（标题带翻唱、Cover、DJ 版、
     * 伴奏、纯音乐等标记）。对这类候选降权，让同名的原唱候选胜出；分数相同时保留靠前的
     * 候选（搜索按热度/权威排序，原唱通常更靠前）。
     *
     * @param artistHint 已确认 / 刚搜到的歌手；为 null 或空时退化为纯标题匹配（改动前行为）。
     *                   artist 与某候选歌手互相包含时该候选 +30，优先选到「已知歌手」的版本
     * @return 最优候选；标题与 artist 都对不上（最高分仍是 0 且 artist 未命中）返回 null，
     *         调用方应视为本源无结果、落到下一源，避免把无关曲当原曲填进库
     */
    static SongHit bestMatch(String rawName, List<SongHit> songs, String artistHint) {
        String q = normalize(rawName);
        String hint = StringUtils.isBlank(artistHint) ? null : normalize(artistHint);
        SongHit best = null;
        int bestScore = -1;
        boolean bestArtistHit = false;
        for (SongHit s : songs) {
            // 试听片段/铃声：候选里存在已知完整版（≥60s）时才剔除 <60s 的，避免下到 30s 预览
            if (s.durationSec() > 0 && s.durationSec() < 60 && hasFullLengthCandidate(songs)) {
                continue;
            }
            int score = matchScore(q, normalize(s.name()));
            if (coverLike(s.name())) {
                score = Math.max(0, score - 40); // 翻唱/改编降权，不把分压成负的以便保留兜底
            }
            boolean artistHit = hint != null && artistMatches(hint, s);
            if (artistHit) {
                score += 30;
            }
            // 分高者胜；平分时 artist 命中优先（候选按热度序在前，原唱通常靠前）
            if (score > bestScore || (score == bestScore && artistHit && !bestArtistHit)) {
                bestScore = score;
                best = s;
                bestArtistHit = artistHit;
            }
        }
        // 最高分候选标题毫无关系且 artist 未命中 → 判无结果（落到下一源），避免错填
        if (best != null && matchScore(q, normalize(best.name())) == 0 && !bestArtistHit) {
            return null;
        }
        return best;
    }

    /** artistHint 与候选的某个歌手互相包含（处理「周杰伦」对「周杰伦 / 方文山」） */
    private static boolean artistMatches(String hint, SongHit s) {
        for (String a : s.artists()) {
            String na = normalize(a);
            if (!na.isEmpty() && (na.contains(hint) || hint.contains(na))) {
                return true;
            }
        }
        return false;
    }

    /** 候选里是否存在已知完整版（时长 ≥60s）：用于在可选时剔除 <60s 的试听片段/铃声 */
    private static boolean hasFullLengthCandidate(List<SongHit> songs) {
        return songs.stream().anyMatch(s -> s.durationSec() >= 60);
    }

    /** 标题是否带明显的「非原唱/改编」标记 */
    private static boolean coverLike(String name) {
        return name != null && COVER_MARKER.matcher(name).find();
    }

    /** artist 超长（多 feat / 拼接异常）只留前 100 字符 */
    private static String truncateArtist(String artist) {
        if (artist == null || artist.length() <= MAX_ARTIST_LEN) {
            return artist;
        }
        return artist.substring(0, MAX_ARTIST_LEN);
    }

    private static int matchScore(String q, String name) {
        if (q.isEmpty()) {
            return 0;
        }
        if (q.equals(name)) {
            return 100;
        }
        if (name.startsWith(q)) {
            return 90;
        }
        if (q.startsWith(name)) {
            return 80;
        }
        if (name.contains(q)) {
            return 70;
        }
        if (q.contains(name)) {
            return 60;
        }
        return 0;
    }

    /** 打印候选清单，供核对（最多 5 条） */
    private static String candidatesSummary(String rawName, List<SongHit> songs) {
        return songs.stream().limit(5)
                .map(s -> s.name() + "(" + s.artistText() + ")")
                .collect(Collectors.joining("；", "【候选: ", "】"));
    }

    /** 搜索词候选：有 artist 时「原曲名 + 歌手」优先，其后才是原串、剥掉常见后缀后的串。
     *  调用方逐个尝试，前面的词无结果就落到下一个，实现「优先查该歌手版本、查不到再退回」。 */
    private static List<String> searchQueries(String rawName, String artistHint) {
        List<String> qs = new ArrayList<>();
        String trimmed = StringUtils.trimToNull(rawName);
        if (trimmed == null) {
            return qs;
        }
        if (StringUtils.isNotBlank(artistHint)) {
            qs.add((trimmed + " " + artistHint.trim()).trim());
        }
        qs.add(trimmed);
        String stripped = trimmed
                .replaceFirst("(?i)(DJ版|柔情版|Remix|Live|伴奏版|纯音乐|纯享版|Cover)\\s*$", "")
                .trim();
        if (!stripped.equals(trimmed) && !stripped.isEmpty()) {
            qs.add(stripped);
        }
        return qs;
    }

    /** 归一化：小写 + 去空白与标点，只用于比名字 */
    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}（）()【】\\[\\]·・、，。]+", "");
    }

    /** 文件名安全化：去 Windows 非法 ASCII 字符与首尾点/空格（全角字符不动） */
    private static String safeFileName(String rawName) {
        String s = rawName.replaceAll("[\\\\/:*?\"<>|]", "").trim();
        while (s.endsWith(".") || s.endsWith(" ")) {
            s = s.substring(0, s.length() - 1);
        }
        return StringUtils.defaultIfBlank(s, "_");
    }

    /**
     * 歌词文件的主名：若原曲音频已在（{@code originalFileName} 非空），歌词跟它同名
     * （只换扩展名），播放器才能按同名自动配对歌词；否则用 rawName 的安全文件名兜底。
     */
    private static String lyricBaseName(SongOriginalSetting row, String rawName) {
        String existing = row == null ? null : row.getOriginalFileName();
        if (StringUtils.isNotBlank(existing)) {
            String base = SongNameParser.mainName(existing);
            if (!base.isEmpty()) {
                return base;
            }
        }
        return safeFileName(rawName);
    }

    /** 模板目录：{@code F:\歌曲\模板\<原曲名>\}，自动创建。作者已确认时落在
     *  {@code <原曲名>_<作者>} 目录，与模板编辑页确认作者后重命名的目录一致 */
    private Path templateDir(SongOriginalSetting row, String rawName) {
        Path dir = Path.of(properties.getTemplateDir())
                .resolve(safeFileName(templateBaseName(row, rawName)));
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            throw new IllegalStateException("创建模板目录失败：" + dir, e);
        }
        return dir;
    }

    /** 模板目录主名：作者已确认（artistCheck=1）且非空时用「原曲名_作者」，否则纯原曲名 */
    private static String templateBaseName(SongOriginalSetting row, String rawName) {
        if (row != null && Boolean.TRUE.equals(row.getArtistCheck())
                && StringUtils.isNotBlank(row.getArtist())) {
            return rawName + "_" + row.getArtist();
        }
        return rawName;
    }

    /** 写文件：已存在则跳过（幂等，不覆盖已有文件），成功返回 true */
    private boolean writeFile(Path target, byte[] bytes) {
        try {
            if (Files.exists(target)) {
                log.info("[写盘] 已存在，跳过：{}", target);
                return false;
            }
            Files.write(target, bytes);
            // 下载 / 解密落盘记一条 WRITE（层级 OTHER）。source 由最外层的 batch 给：
            // 页面那侧是 TASK（异步任务），脚本那侧是 SCRIPT —— 嵌套规则在此生效
            FileOpRecorder.recordPath(FileOpModule.SONG, FileOpType.WRITE,
                    FileOpLevel.OTHER, "下载 / 解密落盘", null, target.toString());
            log.info("[写盘] {} ({} 字节)", target, bytes.length);
            return true;
        } catch (Exception e) {
            log.warn("[写盘] 失败：{}：{}", target, e.getMessage());
            return false;
        }
    }

    /** 非加密 mp3 的粗略判定：ID3 头或 MPEG 帧同步字 */
    private static boolean looksLikeMp3(byte[] b) {
        if (b == null || b.length < 4) {
            return false;
        }
        if (b[0] == 'I' && b[1] == 'D' && b[2] == '3') {
            return true; // ID3v2
        }
        return (b[0] & 0xFF) == 0xFF && (b[1] & 0xE0) == 0xE0; // MPEG 帧同步
    }

    /** 网易云 .ncm 加密文件的判定：文件头 8 字节魔数 {@code CTENFDAM} */
    private static boolean looksLikeNcm(byte[] b) {
        if (b == null || b.length < 8) {
            return false;
        }
        return b[0] == 'C' && b[1] == 'T' && b[2] == 'E' && b[3] == 'N'
                && b[4] == 'F' && b[5] == 'D' && b[6] == 'A' && b[7] == 'M';
    }

    /**
     * 把网易云下到的 .ncm 字节调 {@code tools/ncm_decrypt.py} 解密成原曲音频并写进模板目录。
     * 返回实际写出的文件名（含扩展名，多为 .flac / .mp3），失败返回 null；临时 .ncm 用完即删。
     */
    private String decryptNcm(byte[] ncm, String baseName, Path dir) {
        Path script = Path.of(properties.getNcmDecryptScript());
        if (!Files.isRegularFile(script)) {
            log.warn("[原曲] ncm 解密脚本不存在：{}", script.toAbsolutePath());
            return null;
        }
        Path ncmFile = null;
        try {
            ncmFile = dir.resolve(baseName + ".ncm");
            Files.write(ncmFile, ncm);
            // .ncm 是解密的临时中间文件，解密产物（下面的音频）才是「落盘」——
            // 但产物由 python 进程写出、这里只知道文件名，所以记 ncm 这一步
            FileOpRecorder.recordPath(FileOpModule.SONG, FileOpType.WRITE,
                    FileOpLevel.OTHER, "下载 / 解密落盘", null, ncmFile.toString());
            runNcmDecrypt(script, ncmFile, dir);
            // 解密产物 = baseName.<fmt>（输入名非 uid 时沿用输入名；网易云 ncm 多为 flac/mp3）
            for (String ext : List.of("flac", "mp3", "ogg", "m4a")) {
                Path p = dir.resolve(baseName + "." + ext);
                if (Files.isRegularFile(p)) {
                    log.info("[原曲] ncm 解密 → {}", p.getFileName());
                    return p.getFileName().toString();
                }
            }
            log.warn("[原曲] ncm 解密未产出预期音频（{}.*）", baseName);
            return null;
        } catch (Exception e) {
            log.warn("[原曲] ncm 解密失败：{}", e.getMessage());
            return null;
        } finally {
            if (ncmFile != null) {
                try {
                    Files.deleteIfExists(ncmFile);
                } catch (Exception ignore) {
                    // 清理失败不影响主流程
                }
            }
        }
    }

    /** 调 python 脚本把单个 .ncm 解密写盘到 dir（-a 真正落盘；-o 指定输出目录） */
    private void runNcmDecrypt(Path script, Path ncmFile, Path dir) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                properties.getPythonCommand(), script.toString(),
                ncmFile.toString(), "-a", "-o", dir.toString());
        pb.redirectErrorStream(true);
        pb.environment().put("PYTHONUTF8", "1"); // stdout 走 UTF-8，中文日志不乱码
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) {
            throw new IllegalStateException("ncm_decrypt.py 退出码 " + code + "：" + out.trim());
        }
        log.debug("[原曲] ncm_decrypt.py：{}", out.trim());
    }
}
