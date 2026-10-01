package io.github.Nyameph.nyaentworks.song.fill.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §7.4 两道闸（在线误配 / 本地槽位误填在线地址）。纯函数，可离线跑。 */
public class FillAiOnlineGuardTest {

    private static final String LOCAL = "http://localhost:11434/v1";

    /** 本地模式 + 本机 Ollama：放行。 */
    @Test
    public void guard_localOk() {
        assertNull(FillAiService.onlineGuard(false, LOCAL, false, ""));
        assertNull(FillAiService.onlineGuard(false, "http://127.0.0.1:11434/v1", false, ""));
        assertNull(FillAiService.onlineGuard(false, "http://[::1]:11434/v1", true, "https://x"));
        assertNull(FillAiService.onlineGuard(false, "http://0.0.0.0:11434/v1", false, ""));
    }

    /** 闸二：use-online=true 但 common.online-ai.enabled=false（或 base-url 空）→ 拒绝，指出缺哪个。 */
    @Test
    public void guard_onlineRequiresEnabledAndBaseUrl() {
        String denied = FillAiService.onlineGuard(true, LOCAL, false, "");
        assertTrue(denied.contains("enabled"), denied);
        String denied2 = FillAiService.onlineGuard(true, LOCAL, true, "");
        assertTrue(denied2 != null && denied2.contains("base-url"), String.valueOf(denied2));
        // enabled=false 时的报错只说 enabled、不说 base-url（§7.4：说清缺哪个）
        String denied1 = FillAiService.onlineGuard(true, LOCAL, false, "");
        assertTrue(!denied1.contains("base-url 为空"), denied1);
        assertNull(FillAiService.onlineGuard(true, LOCAL, true, "https://api.deepseek.com/v1"));
    }

    /** 闸一：use-online=false 但 local.base-url 指向非本机 → 拒绝，指出该 host（防误外发）。 */
    @Test
    public void guard_localSlotWithRemoteHostDenied() {
        String denied = FillAiService.onlineGuard(false, "https://api.example.com/v1", false, "");
        assertTrue(denied.contains("api.example.com"), denied);
        assertTrue(denied.contains("common.online-ai"), denied);
        // 在线端点即便配好了，use-online=false + 远程 local 槽位照样拒（两道闸互不替代）
        String denied2 = FillAiService.onlineGuard(false, "https://api.example.com/v1",
                true, "https://api.deepseek.com/v1");
        assertTrue(denied2.contains("api.example.com"), denied2);
    }

    @Test
    public void guard_hostParsing() {
        assertEquals("api.example.com", FillAiService.hostOf("https://api.example.com/v1"));
        assertTrue(FillAiService.isLocalHost("LOCALHOST"));
        assertFalse(FillAiService.isLocalHost("api.deepseek.com"));
        assertFalse(FillAiService.isLocalHost(null));
    }
}
