package io.github.Nyameph.nyaentworks.common.settings;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「这一页缺了最低限度运行所需的配置」的判据，供前端的<b>按页遮罩</b>用。
 *
 * <p><b>为什么要有它</b>：某一页缺了那个磁盘根时（比如「新漫画」页要
 * {@code manga.new-dir}，而这一项空着、或者填了但盘没挂上），页面的表现与
 * 「这里确实没有内容」<b>长得一模一样</b> —— 扫出来是空列表，一句错都不报。
 * 用户看到的是「怎么没有新漫画」，而不是「这一页还差一项配置」。
 *
 * <p>原设计（{@code docs/桌面化与模块裁剪设计.md} §2.3 阻塞 2）的解法是
 * <b>首次打开自动跳转配置页</b>。2026-09-26 改成本类支撑的遮罩：跳转把用户从当前页赶走，
 * 既没告诉他缺哪一项、也让他没法对照页面内容；遮罩留在原地，把「缺什么 / 去哪配」
 * 直接摆在眼前。
 *
 * <p><b>只判 {@code PATH_DIR} 类型的项</b>：目录路径才有「这个目录在不在」这个问题。
 * {@code TEXT}（AI 端点、cookie、站点根）与 {@code PATH_FILE}（NConvert、本地 eh 库）
 * 都<b>不判</b> —— 那几项缺了是「降级但能用」，归配置页顶部那条提示条
 * （{@link SettingsNotices}）管；把它们也算成「缺」会把本来能用的页面一起遮住。
 *
 * <p><b>这里不维护「哪几个键算数」的清单</b>：哪个页关心哪个键由前端
 * {@code static/js/modules.js} 里各页的 {@code needs} 决定，后端多判几项无害 ——
 * {@code manga.thumb-cache-dir} / {@code song.original-retired-dir} 会被判出来，
 * 但没有任何页面引用它们，于是永远不会触发遮罩。少维护一份清单，就少一处
 * 「清单与 {@code specs()} 漂移」的静默失效。
 *
 * <p><b>判的是进程此刻的值</b>（{@link SettingsCatalog.Spec#current()}），
 * <b>不是</b>「重启后会生效的值」（{@code SettingsService#restartValue} 那个三层阶梯）——
 * 这一点与 {@link SettingsNotices} / 配置页的输入框<b>正好相反</b>，别看错：
 * 遮罩回答的是「<b>这一页此刻能不能用</b>」，而能不能用只取决于进程真正读到的那份路径。
 * 于是「删掉一项、保存、还没重启」→ 页面照常能用、不遮（正确）；
 * 「填上一项、保存、还没重启」→ 仍然遮着，卡片上那句「保存后要重启后端才生效」
 * 就是为这一刻写的（正确）。若反过来判「重启后会生效的值」，就会出现
 * 「配置页说好了、页面照样遮」这种错位。
 */
@Component
@RequiredArgsConstructor
public class PageGates {

    /**
     * 路径项的配置页元数据。{@link SettingsNotices} 那套文案是「缺了会怎样」，
     * 这一套是「缺的是哪一项、去哪儿补」—— 两件事，不重复。
     *
     * <p>{@code label} 与 {@code group} 直接取 {@code Spec} 的，所以卡片上写的
     * 「配置页 → 漫画 · 路径」这个组名与配置页那张卡片的标题<b>逐字一致</b>：
     * {@link SettingsCatalog} 里改了标签，两边一起变，不会各说各话。
     *
     * @param label  这一项在配置页上的名字（{@code 新漫画根}）
     * @param group  它在配置页的哪一组（{@code 漫画 · 路径}）
     * @param reason 为什么算缺，直接给人看 —— {@link PageGates#reasonFor} 那两句话之一
     */
    public record Gap(String label, String group, String reason) {
    }

    private final SettingsCatalog catalog;

    /**
     * 全键 → 缺的那一项。<b>只下发缺了的</b>，不缺的键根本不出现 ——
     * 前端查表查不到就是不遮，省掉一层「缺不缺」的布尔。
     */
    public Map<String, Gap> gaps() {
        Map<String, Gap> out = new LinkedHashMap<>();
        for (SettingsCatalog.Spec spec : catalog.specs()) {
            // 只判目录路径，见类注释。specs() 已滤掉「本次构建里没有的模块」，
            // 所以被裁掉的模块不会在这里冒出来
            if (!SettingsCatalog.PATH_DIR.equals(spec.type())) {
                continue;
            }
            // 判进程此刻的值，见类注释
            String reason = reasonFor(spec.current());
            if (reason != null) {
                out.put(spec.key(), new Gap(spec.label(), spec.group(), reason));
            }
        }
        return out;
    }

    /**
     * 这一项算不算缺。{@code null} ＝ 不缺。
     *
     * <p>「空着」与「填了但磁盘上不在」<b>都算缺</b>，但原因文案不同 ——
     * 要补的地方不一样：前者是「去填一个」，后者是「看看盘挂上没、路径抄错没」。
     *
     * <p>有意与 {@code MangaNewService#requireDir} 同口径（空白 / 不是目录就拦），
     * 只是那里是抛异常、这里是给页面画卡片。
     *
     * <p>包级可见的纯函数，便于单测直接打三个分支。
     */
    static String reasonFor(String path) {
        if (StringUtils.isBlank(path)) {
            return "还没填";
        }
        String trimmed = path.trim();
        try {
            if (Files.isDirectory(Paths.get(trimmed))) {
                return null;
            }
        } catch (InvalidPathException e) {
            // 填进来的串连路径都不是（含 \0 之类）：当作磁盘上没有它，
            // 与「填了但不在」同一句话，不另起一种说法
        }
        return "填的是「" + trimmed + "」，但磁盘上没有这个目录（盘没挂上？）";
    }
}
