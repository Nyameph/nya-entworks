package io.github.Nyameph.nyaentworks.common;

import io.github.Nyameph.nyaentworks.common.settings.SettingsCatalog;
import io.github.Nyameph.nyaentworks.manga.config.MangaProperties;
import io.github.Nyameph.nyaentworks.shout.config.ShoutProperties;
import io.github.Nyameph.nyaentworks.song.config.SongProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModuleController} 的自检：它下发的模块 id 必须与配置页认的
 * {@link SettingsCatalog#MODULES} <b>同一套</b>。
 *
 * <p>漂了的后果是<b>全静默</b>：配置页多出一个模块开关、而这里没有对应的键时，
 * 那个模块关掉后左侧导航照旧挂着入口，点进去每个请求 404 —— 看起来像坏了而不是像关了。
 * 反过来少一个模块也不报错，只是导航项永远画不出来。
 *
 * <p>纯单测：三个 Properties 直接 {@code new}，不起 Spring。
 */
public class ModuleControllerTest {

    private ModuleController controller;

    @BeforeEach
    public void setUp() {
        controller = new ModuleController(new MangaProperties(), new SongProperties(), new ShoutProperties());
    }

    @Test
    public void enabled_coversExactlyTheModulesTheSettingsPageKnows() {
        Map<String, Boolean> flags = controller.enabled().data();
        assertEquals(new HashSet<>(SettingsCatalog.MODULES), flags.keySet(),
                "下发的模块 id 与配置页的 MODULES 对不上，多出来的模块关掉后导航不会隐藏");
    }

    /** 缺省是关：没在配置里写 enabled=true 的模块，导航不该显示入口 */
    @Test
    public void enabled_defaultsToFalse() {
        Map<String, Boolean> flags = controller.enabled().data();
        for (String module : SettingsCatalog.MODULES) {
            assertNotNull(flags.get(module), "没有这个模块的开关：" + module);
            assertFalse(flags.get(module), "缺省应当是关的：" + module);
        }
    }

    /** 开了就要如实上报 —— 三个字段各接各的 Properties，接串了会把别人的导航项藏起来 */
    @Test
    public void enabled_reportsEachModuleFromItsOwnProperties() {
        MangaProperties manga = new MangaProperties();
        SongProperties song = new SongProperties();
        ShoutProperties shout = new ShoutProperties();
        song.setEnabled(true);
        Map<String, Boolean> flags = new ModuleController(manga, song, shout).enabled().data();
        assertTrue(flags.get("song"), "song.enabled=true 没上报成开");
        assertFalse(flags.get("manga"), "manga 没开却上报成开");
        assertFalse(flags.get("shout"), "shout 没开却上报成开");
    }
}
