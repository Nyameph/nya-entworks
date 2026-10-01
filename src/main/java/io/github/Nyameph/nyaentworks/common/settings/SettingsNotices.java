package io.github.Nyameph.nyaentworks.common.settings;

import io.github.Nyameph.nyaentworks.common.ai.AiAvailability;
import io.github.Nyameph.nyaentworks.manga.config.MangaProperties;
import io.github.Nyameph.nyaentworks.manga.service.MangaCompressService;
import io.github.Nyameph.nyaentworks.manga.util.MangaEhLocalDb;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 配置页顶部的提示条：<b>「按配置页上这份配置启动，因为缺了什么，所以哪些功能是关着的」</b>。
 *
 * <p>与 {@code SettingsService.plan()} 的 {@code warnings} 不是一回事，别混：
 * 那些是拿<b>提交值</b>算的「这一改会怎样」，只在预演/保存时出现；
 * 这里是页面一打开就有的「这台机器上什么用不了」。
 *
 * <p><b>判据是「重启后会生效的值」（不是进程里那个快照）</b> —— 2026-09-25 作者报
 * 「删除本地 eh 库之后没有任何提示」时改的：原先判的是进程里那一份（这些 Bean 在<b>构造时</b>
 * 就把路径快照走了），所以删完保存、页面重画，提示照样不出现，只有重启后端才轮到它变 ——
 * 而「删了就等于没配」正是用户此刻要看到的结论。现在这一条与输入框画的是同一个值
 * （{@code SettingsService#restartValue} 那个三层阶梯：覆盖层 > 密钥文件 > 出厂值），
 * <b>保存完立刻就有说法，不用重启</b>。
 *
 * <p>代价是「重启前不算数」，所以每个判断为「缺」的提示都会带上
 * {@link #staleClause} 那句：这项刚改过时，把「此刻其实还在按旧的走」说清楚 ——
 * 否则提示读起来像是已经生效了。
 *
 * <p><b>整张卡片一律按那一份算</b>：包括「漫画模块在不在」（{@code manga.enabled}）
 * 与「要不要提示 AI」（三个功能各自的开关）这些前置闸门，也不再读进程里的值。
 * 于是「这一页上写的」与「这张卡片说的」永远是同一件事。
 * 唯一的例外正是 {@code staleClause} 里那句「此刻实际上是什么样」—— 那里必须读进程里的值
 * （两个 {@code available()} / {@code localReady()}），因为那句话讲的就是进程此刻的状态。
 *
 * <p>为什么要这一层：缺了外部依赖时，这些功能都是<b>静默降级</b>的 ——
 * 没配 NConvert 归档照走、只是不压缩；没配 AI 端点各功能各自不生效。
 * 没有提示的话，用户看到的现象是「这次归档怎么没省空间」「AI 按钮怎么没了」，
 * 而这两件事在页面上长得和「功能坏了」一模一样。
 *
 * <p>判定一律复用各功能自己的口径，不在这里重新判一遍 ——
 * 提示与实际行为必须是同一句话，否则就会出现「提示说没事、实际不工作」。
 * 路径类的两条因此用的是 {@link MangaEhLocalDb#availableAt} /
 * {@link MangaCompressService#availableAt} 这两个<b>参数化</b>的判据
 * （{@code available()} 只是拿进程里那一份来问它），而不是在这里自己写一遍
 * 「非空而且文件在」。
 */
@Component
@RequiredArgsConstructor
public class SettingsNotices {

    /** 提示级别。前端照着上色：{@code WARN} 是黄条，{@code INFO} 是灰条 */
    public static final String WARN = "WARN";
    public static final String INFO = "INFO";

    /** 这些键的判据都要拿「重启后会生效的那份值」来问，所以值从外面传进来（见类注释） */
    private static final String KEY_EH_LOCAL_DB = SettingsCatalog.PREFIX + "manga.eh-scan.local-db-path";
    private static final String KEY_NCONVERT = SettingsCatalog.PREFIX + "manga.nconvert";
    private static final String KEY_MANGA_ENABLED = SettingsCatalog.PREFIX + "manga.enabled";
    private static final String KEY_MANGA_AI = SettingsCatalog.PREFIX + "manga.eh-scan.ai-enabled";
    private static final String KEY_SONG_ENABLED = SettingsCatalog.PREFIX + "song.enabled";
    private static final String KEY_AI_BASE_URL = SettingsCatalog.PREFIX + "common.local-ai.base-url";
    private static final String KEY_AI_MODEL = SettingsCatalog.PREFIX + "common.local-ai.model";

    /**
     * 这四个 bean 都是进程里那份<b>启动时绑好的</b>配置，本类只拿它们问一件事：
     * 「这一项还没重启的话，此刻实际上是什么样」（{@link #staleClause} 那句里的
     * {@code available()} / {@code localReady()} / 两个路径值）。
     * 判断「缺不缺」一律用外面递进来的那份（见类注释），不吃它们的值。
     */
    private final MangaProperties manga;
    private final MangaCompressService compressService;
    private final MangaEhLocalDb localDb;
    private final AiAvailability aiAvailability;

    /**
     * 一条提示。
     *
     * @param title 一句话说清「缺了什么、后果是什么」，与下面的正文不重复
     * @param text  正文：缺什么、去哪儿补、补之前功能是什么样
     */
    public record Notice(String level, String title, String text) {
    }

    /**
     * 当前的提示条。
     *
     * @param effective 全键 → <b>重启后会生效的值</b>（{@code SettingsService#view()} 算好的那一份）。
     *                  缺的键按空串处理（模块不在构建里时它就不在这张表里，而那几条提示本来就早退）
     * @param stale     其中<b>还没生效</b>的键（＝「重启后生效值 ≠ 现在生效的值」）：
     *                  带上它们，提示才能说清「这条是重启后才算数的」
     */
    public List<Notice> notices(Map<String, String> effective, Set<String> stale) {
        List<Notice> list = new ArrayList<>();
        addNConvert(list, effective, stale);
        addEhLocalDb(list, effective, stale);
        addAi(list, effective, stale);
        return list;
    }

    /**
     * 没配本地 eh 库：标签扫描整段不走「本地优先匹配」，直接联网。
     *
     * <p><b>用 INFO 而不是 WARN</b>（与上面两条不同）：NConvert 缺了是「这个功能不做了」，
     * AI 端点缺了是「三个功能关了」，都属「功能是关着的」；本地库缺了功能一个没少 ——
     * 联网搜索本来就能独立完成扫描，本地匹配只是<b>加速</b>（省下每本的搜索请求）。
     * 更要紧的是：这份 1.4 GB 的快照是 gitignored 的本机产物，新机器上人人如此，
     * 挂个常驻黄条等于「新装的机器永远有一处告警」。降级确实不假 —— 批量扫几千本时
     * 每本都要真发请求，慢且更贴近限流 —— 正文里说清楚就够了。
     *
     * <p>判据是扫描入口、启动预热用的那一个（{@link MangaEhLocalDb#availableAt}），
     * 只是问的<b>路径不同</b>：那两处问进程里这份（还没重启时是旧的），这里问重启后会生效的那份
     * （见类注释）。两边不会各说各话。
     */
    private void addEhLocalDb(List<Notice> list, Map<String, String> effective, Set<String> stale) {
        // 判「重启后会生效的值」那一份（见类注释）：删掉这一项之后不用重启就该有这句
        String path = effective.getOrDefault(KEY_EH_LOCAL_DB, "");
        if (!isOn(effective, KEY_MANGA_ENABLED) || MangaEhLocalDb.availableAt(path)) {
            return;
        }
        list.add(new Notice(INFO, "没配本地 eh 库：标签扫描直接走联网搜索",
                MangaEhLocalDb.unavailableReasonFor(path)
                        + "。扫描照常出结果，只是每本都要真去 eh 搜一次 —— 单本慢一点，"
                        + "批量扫几千本时请求数会明显上去（本地命中一本能省掉整串搜索请求）。"
                        + "把 eh-gallery.db 放到项目根、在配置页填上路径，重启后端即可恢复。"
                        + staleClause(stale, localDb.available()
                        ? "扫描仍在用本地库（" + manga.getEhScan().getLocalDbPath() + "）"
                        : "扫描也走联网搜索", KEY_EH_LOCAL_DB)));
    }

    /** 没配 NConvert：归档不再压缩。压缩是优化不是前提，所以是提示而不是错误 */
    private void addNConvert(List<Notice> list, Map<String, String> effective, Set<String> stale) {
        String path = effective.getOrDefault(KEY_NCONVERT, "");
        if (!isOn(effective, KEY_MANGA_ENABLED) || MangaCompressService.availableAt(path)) {
            return;
        }
        list.add(new Notice(WARN, "没配 NConvert：归档时不再压缩图片",
                MangaCompressService.unavailableReasonFor(path)
                        + "。归档流程照常走完（改名、落库、打包 cbz 都不受影响），"
                        + "只是图片不会被压小、原图原样入库。配好这一项后重跑一次归档即可压缩。"
                        + staleClause(stale, compressService.available()
                        ? "归档仍在压缩（用的是 " + manga.getNconvert() + "）"
                        : "归档也不压缩", KEY_NCONVERT)));
    }

    /**
     * 「这一项刚改过、还没重启」的追加句。<b>只在提示判成「缺」而相关项又还没生效时</b>才有 —
     * 否则这一条提示读起来像是已经生效了，而实际上此刻还在按旧的那份走。
     *
     * @param nowText 此刻实际上是什么样（拿各功能自己的 {@code available()} /
     *                {@code localReady()} 现问一句，与上面那份「重启后会生效的值」相对）——
     *                这里是全类唯一读进程里那些值的地方，因为它问的正是「进程现在怎样」
     * @param keys    这一条提示与哪几项有关（AI 那条缺的可能只是 base-url 也可能只是 model，
     *                任一项没生效这句都成立）
     */
    private static String staleClause(Set<String> stale, String nowText, String... keys) {
        boolean anyStale = false;
        for (String key : keys) {
            anyStale |= stale.contains(key);
        }
        if (!anyStale) {
            return "";
        }
        return "  ⚠️ 这一项刚改过、还没重启：现在" + nowText
                + "，后端重启之后才真的按上面这条走。";
    }

    /**
     * 没配全 AI 端点：三个 AI 功能联动关闭。
     * <p>只有「本来会用 AI 的功能」才值得提示 —— 三个功能各自的开关都关着时，
     * 端点缺不缺都一样，挂个黄条只是噪音。
     */
    private void addAi(List<Notice> list, Map<String, String> effective, Set<String> stale) {
        String deny = AiAvailability.localDenyAt(effective.getOrDefault(KEY_AI_BASE_URL, ""),
                effective.getOrDefault(KEY_AI_MODEL, ""));
        if (deny == null || !anyAiFeatureWanted(effective)) {
            return;
        }
        list.add(new Notice(WARN, "没配全 AI 端点：三个 AI 功能已关闭",
                deny + "。受影响的是「AI 相似度兜底」（漫画）、「AI 填词」与「补全词性」（歌曲）三处，"
                        + "它们现在都不工作，页面上的按钮也已收起来 ——"
                        + "配好之后重启后端，三个功能一起恢复。"
                        + staleClause(stale, aiAvailability.localReady()
                        ? "三处 AI 还在用旧的那台端点"
                        : "三处 AI 也不工作", KEY_AI_BASE_URL, KEY_AI_MODEL)));
    }

    /**
     * 有没有哪个功能本来是会用本机 AI 的。
     * <p>判的是<b>各自的开关</b>而不是端点：端点配没配是这份提示要说的结论，不能拿来当前提。
     * <p>歌曲侧不必再看 {@code song.fill-ai.enabled}：韵脚词性没有自己的开关，
     * 歌曲模块开着它就默认在用，所以「歌曲模块开着」已经够了。
     * <p>开关本身也按「重启后会生效的那份配置」读（见类注释）：模块不在本次构建里时
     * 这个键根本不在表里，{@link #isOn} 判成 {@code false}，正好是该有的结果。
     */
    private static boolean anyAiFeatureWanted(Map<String, String> effective) {
        return (isOn(effective, KEY_MANGA_ENABLED) && isOn(effective, KEY_MANGA_AI))
                || isOn(effective, KEY_SONG_ENABLED);
    }

    /** 布尔项在这张表里是字符串。缺键（模块不在本次构建里）按关着算 */
    private static boolean isOn(Map<String, String> effective, String key) {
        return StringUtils.equalsIgnoreCase(StringUtils.trimToEmpty(effective.get(key)), "true");
    }
}
