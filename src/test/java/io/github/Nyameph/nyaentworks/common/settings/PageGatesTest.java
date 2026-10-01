package io.github.Nyameph.nyaentworks.common.settings;

import io.github.Nyameph.nyaentworks.common.config.LocalAiProperties;
import io.github.Nyameph.nyaentworks.common.config.OnlineAiProperties;
import io.github.Nyameph.nyaentworks.manga.config.MangaProperties;
import io.github.Nyameph.nyaentworks.shout.config.ShoutProperties;
import io.github.Nyameph.nyaentworks.song.config.SongProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按页遮罩的判据（{@link PageGates}）的自检。
 *
 * <p>盯的是几件<b>错了不报、只静默</b>的事：
 * <ul>
 *   <li><b>「缺」的两个分支各自成立</b> —— 空着与「填了但磁盘上不在」都算缺，
 *       但文案不同（要补的地方不一样）；目录真在时<b>不能</b>算缺，
 *       否则本机自用的页面会全被遮住。</li>
 *   <li><b>只有 {@code PATH_DIR} 项进结果</b> —— 判多了会把「降级但能用」的页面
 *       （缺 AI 端点、缺 NConvert、缺本地 eh 库）也一起遮住，那几项归配置页顶部的
 *       提示条管。</li>
 *   <li><b>前端 {@code modules.js} 里每个 {@code needs} 的键都真在
 *       {@link SettingsCatalog#specs()} 里</b>，且<b>恰好</b>该有 needs 的那几页有 ——
 *       写歪一个键名或漏配一页，症状是那一页<b>永远不遮</b>：不报错、只是「怎么还是空的」，
 *       只能靠这条兜住。反过来，多配的会把本来能用的页遮住（比如配置页把自己遮上＝死锁）。</li>
 * </ul>
 *
 * <p>纯单测：五个 Properties 都直接 {@code new}（嵌套对象是在字段声明处就 new 好的），
 * 属性源用一个只装着 {@code *.enabled} 三行的 {@link MockEnvironment}，
 * 不连库、不碰 {@code F:\}（除了那一份文件本身，它就在仓库里）。
 */
public class PageGatesTest {

    /** {@code modules.js} 相对项目根（surefire 的工作目录就是项目根） */
    private static final String MODULES_JS = "src/main/resources/static/js/modules.js";

    /**
     * 该有 {@code needs} 的页面，与 {@code modules.js} 里写的那份<b>逐页对上</b>。
     * <p>改动 mapping（加一页、去掉一页）时这份清单要跟着改 —— 这条测试正是为了
     * 让「改了 mapping 却忘了改另一边」不可能发生。
     */
    private static final List<String> GATED_PAGES = List.of(
            "manga-new", "manga-unarchived", "manga-archive",
            "song-score", "song-list", "song-stat", "song-fill", "song-artist",
            "shout-staging", "shout-list");

    /**
     * 一个「本次构建里有这三个模块」的属性源 —— 每个模块一行 {@code <模块>.enabled}。
     * 值写什么无关，这一层只回答「键在不在」。
     */
    private static Environment envWithAllModules() {
        MockEnvironment env = new MockEnvironment();
        for (String m : SettingsCatalog.MODULES) {
            env.withProperty(SettingsCatalog.PREFIX + m + ".enabled", "true");
        }
        return env;
    }

    private static SettingsCatalog catalog(MangaProperties manga) {
        return new SettingsCatalog(manga, new SongProperties(), new ShoutProperties(),
                new LocalAiProperties(), new OnlineAiProperties(), envWithAllModules());
    }

    // ---------- 一、判「缺」的两个分支 ----------

    @Test
    public void reasonFor_blankIsAGap() {
        assertEquals("还没填", PageGates.reasonFor(null));
        assertEquals("还没填", PageGates.reasonFor(""));
        assertEquals("还没填", PageGates.reasonFor("   "));
    }

    @Test
    public void reasonFor_existingDirectoryIsNotAGap(@TempDir Path dir) {
        assertNull(PageGates.reasonFor(dir.toString()),
                "目录真在的时候算成缺，本机自用的页面会被全遮住");
    }

    @Test
    public void reasonFor_missingDirectoryIsAGapAndSaysWhich(@TempDir Path dir) {
        String missing = dir.resolve("没有这个目录").toString();
        String reason = PageGates.reasonFor(missing);
        assertNotNull(reason, "填了但磁盘上没有，同样是缺");
        assertTrue(reason.contains(missing), "文案里要带上填的那个值，人才知道去哪儿找：" + reason);
    }

    @Test
    public void reasonFor_fileIsNotADirectory(@TempDir Path dir) throws IOException {
        Path file = Files.createFile(dir.resolve("这是个文件"));
        assertNotNull(PageGates.reasonFor(file.toString()),
                "填了个文件不算目录，扫描照样是空的");
    }

    // ---------- 二、只有目录路径项会进结果 ----------

    @Test
    public void gaps_onlyDirectoryPaths(@TempDir Path dir) {
        // 把「新漫画根」指到一个真目录上，其余项保持出厂默认值不动
        MangaProperties manga = new MangaProperties();
        manga.setNewDir(dir.toString());
        SettingsCatalog catalog = catalog(manga);

        Map<String, PageGates.Gap> gaps = new PageGates(catalog).gaps();

        assertFalse(gaps.containsKey(SettingsCatalog.PREFIX + "manga.new-dir"),
                "目录在磁盘上躺着，不该判成缺");

        // 每一项都必须是对应到 PATH_DIR 的那一项
        for (Map.Entry<String, PageGates.Gap> entry : gaps.entrySet()) {
            SettingsCatalog.Spec spec = catalog.specs().stream()
                    .filter(s -> s.key().equals(entry.getKey())).findFirst().orElse(null);
            assertNotNull(spec, "结果里冒出目录里没有的键：" + entry.getKey());
            assertEquals(SettingsCatalog.PATH_DIR, spec.type(),
                    "只有目录路径项才判「缺不缺」：「降级但能用」的那几项归配置页顶部的提示条管："
                            + entry.getKey());
            // label / group 直接从 spec 取，卡片上那句话才不会与配置页分家
            assertEquals(spec.label(), entry.getValue().label());
            assertEquals(spec.group(), entry.getValue().group());
        }

        // 三处「降级但能用」的，逐一点名 —— 它们<b>必须</b>不在结果里
        assertFalse(gaps.containsKey(SettingsCatalog.PREFIX + "common.local-ai.base-url"),
                "AI 端点缺了是「三个 AI 功能关着」，页面其余部分照常能用");
        assertFalse(gaps.containsKey(SettingsCatalog.PREFIX + "common.local-ai.model"),
                "同上");
        assertFalse(gaps.containsKey(SettingsCatalog.PREFIX + "manga.nconvert"),
                "NConvert 缺了只是归档不压缩，归档本身照走");
        assertFalse(gaps.containsKey(SettingsCatalog.PREFIX + "manga.eh-scan.local-db-path"),
                "本地 eh 库缺了只是走联网搜索，扫描照出结果");
    }

    // ---------- 三、前端那份 needs 清单 ----------

    @Test
    public void needs_everyKeyIsInTheCatalog() throws IOException {
        String js = readModulesJs();
        Map<String, SettingsCatalog.Spec> byKey = new LinkedHashMap<>();
        for (SettingsCatalog.Spec spec : catalog(new MangaProperties()).specs()) {
            byKey.put(spec.key(), spec);
        }

        for (String pageId : GATED_PAGES) {
            String body = needsBody(sectionOf(js, pageId));
            assertNotNull(body, "modules.js 的 " + pageId + " 没有 needs：" +
                    "这一页缺根时会静默显示成空列表，正是要遮的那一种");
            List<String> keys = quotedKeys(body);
            assertFalse(keys.isEmpty(), pageId + " 的 needs 是个空数组");
            for (String key : keys) {
                assertTrue(byKey.containsKey(key),
                        pageId + " 的 needs 里有拼错的键：" + key
                                + "（键名写歪＝这一页永远不遮，页面上一点提示都没有）");
            }
        }
    }

    @Test
    public void needs_exactlyTheExpectedPages() throws IOException {
        String js = readModulesJs();
        Set<String> actual = pagesWithNeeds(js);
        assertEquals(new LinkedHashSet<>(GATED_PAGES), actual,
                "「有 needs 的页面」与预期不符：漏一页＝那一页缺根时静默显示为空，"
                        + "多一页＝本来能用的页面被遮住");
        assertFalse(actual.contains("settings"),
                "配置页把自己遮住就是死锁：页面里唯一的补救入口在它自己身上");
    }

    // ---------- 从 modules.js 里读 needs ----------

    private static String readModulesJs() throws IOException {
        Path path = Paths.get(MODULES_JS);
        assertTrue(Files.exists(path),
                "找不到 " + MODULES_JS + "（surefire 的工作目录应当就是项目根）");
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** 某个页面对象的那一段：从它的 {@code id: '…'} 起，到下一个 {@code id: '} 之前 */
    private static String sectionOf(String js, String pageId) {
        String marker = "id: '" + pageId + "'";
        int start = js.indexOf(marker);
        if (start < 0) {
            return "";
        }
        int next = js.indexOf("id: '", start + marker.length());
        return next < 0 ? js.substring(start) : js.substring(start, next);
    }

    /** 一段里 {@code needs:} 那个数组的正文（含方括号）；没有 needs 时返回 {@code null} */
    private static String needsBody(String section) {
        int at = section.indexOf("needs:");
        if (at < 0) {
            return null;
        }
        int open = section.indexOf('[', at);
        if (open < 0) {
            return null;
        }
        // 按括号配平取到配对的 ] —— 数组里可以再嵌数组（OR 组），
        // 简单的正则截不住那一层
        int depth = 0;
        for (int i = open; i < section.length(); i++) {
            char c = section.charAt(i);
            if (c == '\'') {
                int close = section.indexOf('\'', i + 1);
                if (close < 0) {
                    break;
                }
                i = close;   // 键名里的括号不算数
                continue;
            }
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return section.substring(open, i + 1);
                }
            }
        }
        return null;
    }

    /** 一段文本里所有 {@code '…'} 的内容 */
    private static List<String> quotedKeys(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("'([^']+)'").matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** 注册表里所有写了 needs 的页面 id */
    private static Set<String> pagesWithNeeds(String js) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("id: '([^']+)'").matcher(js);
        while (m.find()) {
            String id = m.group(1);
            if (needsBody(sectionOf(js, id)) != null) {
                out.add(id);
            }
        }
        return out;
    }
}
