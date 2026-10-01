package io.github.Nyameph.nyaentworks.common.file;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RecycleBin} 里<b>不碰盘的那两半</b>：脚本正文与结果解析。
 *
 * <p><b>这里绝不真删文件</b> —— 真调用会把文件塞进开发者的回收站，而「能直接跑」那批测试
 * 的前提是不碰本机环境。真调用由现场验收覆盖（见
 * {@code docs/已完成/删除进回收站实施计划.md} §3）。
 */
public class RecycleBinTest {

    @Test
    public void script_isPureAsciiAndCarriesTheRecycleApi() {
        String script = RecycleBin.script();
        // 全 ASCII：脚本是经 -EncodedCommand 传的，但正文里出现非 ASCII 就说明有人往里塞了中文文案 ——
        // 那是 启动.cmd 踩过的那个坑（控制台按 936 解码冲垮解析）。中文只许留在 Java 这一侧
        List<Character> nonAscii = new ArrayList<>();
        for (char c : script.toCharArray()) {
            if (c > 127) {
                nonAscii.add(c);
            }
        }
        assertTrue(nonAscii.isEmpty(), "脚本正文里不许有非 ASCII 字符：" + nonAscii);

        assertTrue(script.contains("SendToRecycleBin"),
                "必须走 RecycleOption.SendToRecycleBin —— 送不进回收站就报错，不许静默永久删");
        assertTrue(script.contains("DeleteDirectory") && script.contains("DeleteFile"),
                "文件与目录两条路都要有（漫画目录是整棵树）");
        assertTrue(script.contains("$env:NYA_ENTWORKS_RECYCLE_LIST")
                        && script.contains("$env:NYA_ENTWORKS_RECYCLE_RESULT"),
                "路径清单与结果文件走环境变量传，不上命令行（中文路径经命令行会被控制台代码页弄坏）");
    }

    /**
     * {@code DeleteDirectory} 的第四个参数必须是 {@code UICancelOption}。
     *
     * <p>2026-09-30 第一次真调用时踩到：写成 {@code DeleteDirectoryOption}（那是
     * {@code My.Computer.FileSystem} 的重载才有的）**编译过得去**，跑到那一行才抛
     * 「无法将…转换为类型 UICancelOption」—— 而这条错误当时又被一个编码问题盖住了
     * （见下面那几条 {@code decodeLeniently} 用例），于是现场只剩一句 {@code Input length = 1}。
     * 断言钉住这个重载，免得下次又靠一次真删去发现。
     */
    @Test
    public void script_passesUICancelOptionNotDeleteDirectoryOption() {
        String script = RecycleBin.script();
        assertTrue(script.contains("UICancelOption"),
                "DeleteDirectory 的第四个参数是 UICancelOption::ThrowException");
        assertFalse(script.contains("DeleteDirectoryOption"),
                "DeleteDirectoryOption 只在 My.Computer 的重载上，传进 FileSystem.DeleteDirectory "
                        + "会在运行时抛「无法转换」—— 只有真删过一次才发现，编译不报错");
    }

    /**
     * 两个环境变量必须真的交给子进程。
     *
     * <p>2026-09-30 第一次真调用时踩到：脚本读 {@code $env:NYA_ENTWORKS_RECYCLE_LIST}，
     * 而 Java 侧只把路径写进清单文件、**没往 {@code ProcessBuilder.environment()} 里放**——
     * 子进程拿到空路径，报「路径不是合法的形式」。这条纯单测原先看不见（它不跑进程），
     * 所以现在把拼装那一半抽成 {@code processBuilderFor} 钉住。
     */
    @Test
    public void processBuilder_handsBothEnvVarsToTheChildProcess() {
        java.nio.file.Path work = java.nio.file.Path.of("C:\\tmp\\recycle-work");
        ProcessBuilder builder = RecycleBin.processBuilderFor(
                work.resolve("paths.txt"), work.resolve("result.txt"), work.resolve("ps.log"));

        assertEquals(work.resolve("paths.txt").toString(),
                builder.environment().get("NYA_ENTWORKS_RECYCLE_LIST"),
                "清单文件的位置经环境变量传 —— 脚本读的就是它，空了就整个不工作");
        assertEquals(work.resolve("result.txt").toString(),
                builder.environment().get("NYA_ENTWORKS_RECYCLE_RESULT"),
                "结果文件的位置同理");
    }

    @Test
    public void decodeLeniently_readsUtf8AsIs() {
        assertEquals("OK|C:\\a.mp4\n", RecycleBin.decodeLeniently("OK|C:\\a.mp4\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void decodeLeniently_fallsBackToTheConsoleCodePageInsteadOfThrowing() {
        // powershell 重定向出来的字节走控制台代码页（本机 936）：严格按 UTF-8 读会抛
        // MalformedInputException，消息只有一句 "Input length = 1" —— 真正的报错就被它盖掉了
        byte[] gbk = "无法将参数 onUserCancel 转换".getBytes(Charset.forName("GBK"));
        assertEquals("无法将参数 onUserCancel 转换", RecycleBin.decodeLeniently(gbk));
    }

    @Test
    public void decodeLeniently_neverThrowsOnGarbage() {
        byte[] junk = {(byte) 0x81, (byte) 0xFF, (byte) 0xFE, 0x41, (byte) 0x80};
        assertNotNull(RecycleBin.decodeLeniently(junk), "坏字节只许换成 ?，不许把整条错误信息换成「读不出来」");
    }

    @Test
    public void failuresOf_okAndMissingBothCountAsSuccess() {
        Map<String, String> failures = RecycleBin.failuresOf(
                List.of("C:\\a.mp4", "C:\\b.lrc", "C:\\c.mp4"),
                List.of("OK|C:\\a.mp4", "MISSING|C:\\b.lrc", "FAIL|C:\\c.mp4|Access to the path is denied"));

        assertEquals(1, failures.size(), "只有 FAIL 那条算失败：" + failures);
        assertEquals("Access to the path is denied", failures.get("C:\\c.mp4"));
    }

    @Test
    public void failuresOf_pathWithoutAnyResultIsAFailure() {
        Map<String, String> failures = RecycleBin.failuresOf(
                List.of("C:\\a.mp4", "C:\\b.mp4"),
                List.of("OK|C:\\a.mp4"));

        // 少一条回报 = 静默少删一个：宁可报出来
        assertEquals(1, failures.size());
        assertEquals("powershell 没有回报这条的结果", failures.get("C:\\b.mp4"));
    }

    @Test
    public void failuresOf_keepsTheRestOfTheLineAsTheReason() {
        Map<String, String> failures = RecycleBin.failuresOf(
                List.of("C:\\a.mp4"),
                List.of("FAIL|C:\\a.mp4|The file is in use | close it first"));

        assertEquals("The file is in use | close it first", failures.get("C:\\a.mp4"),
                "原因在第二个分隔符之后原样带回（路径里不可能有 |，Windows 文件名不许）");
    }

    @Test
    public void failuresOf_emptyReasonFallsBackToPlainWords() {
        Map<String, String> failures = RecycleBin.failuresOf(
                List.of("C:\\a.mp4"), List.of("FAIL|C:\\a.mp4|"));
        assertEquals("没能移入回收站", failures.get("C:\\a.mp4"));
    }

    @Test
    public void failuresOf_ignoresGarbageAndStrayLines() {
        Map<String, String> failures = RecycleBin.failuresOf(
                List.of("C:\\a.mp4"),
                List.of("", "no separator here", "OK|C:\\别的.mp4", "OK|C:\\a.mp4"));

        assertTrue(failures.isEmpty(), "没头没尾的行与不相干的路径都不该冒出失败：" + failures);
    }

    @Test
    public void emptyInputNeverTouchesTheDisk() {
        assertTrue(RecycleBin.recycleAll(List.of()).isEmpty());
        assertTrue(RecycleBin.recycleAll(null).isEmpty());
    }

    @Test
    public void nullPathIsRejectedBeforeSpawningAnything() {
        assertThrows(IllegalArgumentException.class, () -> RecycleBin.recycle((String) null));
    }

    @Test
    public void joinFailures_readsAsOneLine() {
        String text = RecycleBin.joinFailures(
                new java.util.LinkedHashMap<>(Map.of("C:\\a.mp4", "被占用")));
        assertEquals("C:\\a.mp4 —— 被占用", text);
    }
}
