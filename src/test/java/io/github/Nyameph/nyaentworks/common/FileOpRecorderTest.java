package io.github.Nyameph.nyaentworks.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import io.github.Nyameph.nyaentworks.common.file.GroupFileOps.FileMove;
import io.github.Nyameph.nyaentworks.common.file.GroupFileOps.GroupPlan;
import io.github.Nyameph.nyaentworks.common.fileop.FileOpRecorder;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpLevel;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpModule;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpResult;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpSource;
import io.github.Nyameph.nyaentworks.common.fileop.consts.FileOpType;
import io.github.Nyameph.nyaentworks.common.fileop.service.FileOpLogService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link FileOpRecorder} 的口径测试：批次嵌套、记录粒度、映射表、UNKNOWN 兜底、
 * 「记录是附属品」。纯 Mockito 单测，不连库、不碰磁盘。
 *
 * <p>每个用例自建 mock 并 {@code bindForTest}，断言全部用本用例内的 captor ——
 * ArgumentCaptor 的 {@code getAllValues} 会跨 verify 累积，共用一个 captor
 * 会让下标含义漂移，所以不抽公共的抓参工具。
 */
public class FileOpRecorderTest {

    private FileOpLogService service;

    @BeforeEach
    public void setUp() {
        service = mock(FileOpLogService.class);
        FileOpRecorder.bindForTest(service);
    }

    @AfterEach
    public void tearDown() {
        // 静态引用清掉，别泄漏给同 JVM 里的其它测试类
        FileOpRecorder.bindForTest(null);
    }

    @Test
    public void sameBatchForTwoRecordPaths() {
        FileOpRecorder.batch(FileOpModule.SONG, FileOpSource.PAGE, null, () -> {
            FileOpRecorder.recordPath(FileOpModule.SONG, FileOpType.MOVE, FileOpLevel.GROUP,
                    "改名", "F:\\a\\歌.mp4", "F:\\b\\歌.mp4");
            FileOpRecorder.recordPath(FileOpModule.SONG, FileOpType.MOVE, FileOpLevel.GROUP,
                    "改名", "F:\\a\\歌.lrc", "F:\\b\\歌.lrc");
            return null;
        });
        ArgumentCaptor<String> batch = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<FileOpSource> source = ArgumentCaptor.forClass(FileOpSource.class);
        ArgumentCaptor<Long> taskId = ArgumentCaptor.forClass(Long.class);
        verify(service, times(2)).insert(eq(FileOpModule.SONG), batch.capture(), any(), any(),
                any(), source.capture(), taskId.capture(), any(), any(), any(), any());
        assertEquals(batch.getAllValues().get(0), batch.getAllValues().get(1),
                "同一批次里两次 recordPath 应共用一个 batchId");
        assertEquals(FileOpSource.PAGE, source.getAllValues().get(0));
        assertEquals(FileOpSource.PAGE, source.getAllValues().get(1));
        assertEquals(null, taskId.getAllValues().get(0));
    }

    @Test
    public void nestedBatchOuterWins() {
        FileOpRecorder.batch(FileOpModule.SONG, FileOpSource.TASK, 42L, () ->
                FileOpRecorder.batch(FileOpModule.SONG, FileOpSource.PAGE, null, () -> {
                    FileOpRecorder.recordPath(FileOpModule.SONG, FileOpType.DELETE,
                            FileOpLevel.GROUP, "删除", "F:\\a\\歌.mp4", null);
                    return null;
                }));
        // 内层 batch 不新开：source、taskId 全是外层的
        ArgumentCaptor<String> batch = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<FileOpSource> source = ArgumentCaptor.forClass(FileOpSource.class);
        ArgumentCaptor<Long> taskId = ArgumentCaptor.forClass(Long.class);
        verify(service, times(1)).insert(eq(FileOpModule.SONG), batch.capture(), any(), any(),
                any(), source.capture(), taskId.capture(), any(), any(), any(), any());
        assertEquals(FileOpSource.TASK, source.getValue());
        assertEquals(42L, taskId.getValue());
        String innerBatch = batch.getValue();

        // 批次结束后 ThreadLocal 已清：下一次 recordPath 是新批次、UNKNOWN 来源
        FileOpRecorder.recordPath(FileOpModule.SONG, FileOpType.DELETE, FileOpLevel.GROUP,
                "删除", "F:\\a\\x.mp4", null);
        ArgumentCaptor<String> batch2 = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<FileOpSource> source2 = ArgumentCaptor.forClass(FileOpSource.class);
        verify(service, times(2)).insert(eq(FileOpModule.SONG), batch2.capture(), any(), any(),
                any(), source2.capture(), any(), any(), any(), any(), any());
        assertNotEquals(innerBatch, batch2.getAllValues().get(1), "批次结束后的 recordPath 应是新批次");
        assertEquals(FileOpSource.UNKNOWN, source2.getAllValues().get(1));
    }

    @Test
    public void recordPlanOneRowPerPath() {
        GroupPlan plan = new GroupPlan("SCORE", "歌", "#9超赞", "歌", List.of(
                new FileMove("歌.mp4", "F:\\旧\\歌.mp4", "歌.mp4", "F:\\新\\歌.mp4", null),
                new FileMove("歌.mp3", "F:\\旧\\歌.mp3", "歌.mp3", "F:\\新\\歌.mp3", null),
                new FileMove("歌.lrc", "F:\\旧\\歌.lrc", "歌.lrc", "F:\\新\\歌.lrc", null)), null);
        FileOpRecorder.batch(FileOpModule.SONG, FileOpSource.PAGE, null, () -> {
            FileOpRecorder.recordPlan(FileOpModule.SONG, plan, FileOpLevel.GROUP);
            return null;
        });
        verify(service, times(3)).insert(eq(FileOpModule.SONG), any(), eq(FileOpType.MOVE),
                eq(FileOpLevel.GROUP), eq("改评分（换分区）"), any(), any(),
                any(), any(), any(), any());
        verify(service, times(1)).insert(any(), any(), any(), any(), any(), any(), any(),
                eq("F:\\旧\\歌.mp4"), eq("F:\\新\\歌.mp4"), any(), any());
        verify(service, times(1)).insert(any(), any(), any(), any(), any(), any(), any(),
                eq("F:\\旧\\歌.mp3"), eq("F:\\新\\歌.mp3"), any(), any());
        verify(service, times(1)).insert(any(), any(), any(), any(), any(), any(), any(),
                eq("F:\\旧\\歌.lrc"), eq("F:\\新\\歌.lrc"), any(), any());
    }

    @Test
    public void recordPlanSkipsBlocked() {
        GroupPlan plan = new GroupPlan("RENAME", "歌", null, "新歌", List.of(
                new FileMove("歌.mp4", "F:\\a\\歌.mp4", "新歌.mp4", "F:\\a\\新歌.mp4", null),
                new FileMove("歌.mp3", "F:\\a\\歌.mp3", null, null, "源文件不在了"),
                new FileMove("歌.lrc", "F:\\a\\歌.lrc", "新歌.lrc", "F:\\a\\新歌.lrc", null)), null);
        FileOpRecorder.recordPlan(FileOpModule.SONG, plan, FileOpLevel.GROUP);
        // blockedReason 非空的那一条根本没执行，不许记
        verify(service, times(2)).insert(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any());
        verify(service, never()).insert(any(), any(), any(), any(), any(), any(), any(),
                eq("F:\\a\\歌.mp3"), any(), any(), any());
    }

    @Test
    public void recordPlanDeleteAction() {
        GroupPlan plan = new GroupPlan("DELETE", "歌", null, null, List.of(
                new FileMove("歌.mp4", "F:\\a\\歌.mp4", null, null, null),
                new FileMove("歌.lrc", "F:\\a\\歌.lrc", null, null, null)), null);
        FileOpRecorder.recordPlan(FileOpModule.SHOUT, plan, FileOpLevel.GROUP);
        verify(service, times(2)).insert(eq(FileOpModule.SHOUT), any(), eq(FileOpType.DELETE),
                eq(FileOpLevel.GROUP), eq("删除（进回收站）"), any(), any(),
                any(), isNull(), eq(FileOpResult.OK), any());
    }

    @Test
    public void recordPlanActionWording() {
        for (String[] pair : new String[][]{
                {"SCORE", "改评分（换分区）"}, {"RENAME", "改名"}, {"EDIT", "改评分 + 改名"}}) {
            FileOpLogService one = mock(FileOpLogService.class);
            FileOpRecorder.bindForTest(one);
            GroupPlan plan = new GroupPlan(pair[0], "歌", null, "歌", List.of(
                    new FileMove("歌.mp4", "F:\\a\\歌.mp4", "歌.mp4", "F:\\b\\歌.mp4", null)), null);
            FileOpRecorder.recordPlan(FileOpModule.SONG, plan, FileOpLevel.GROUP);
            verify(one).insert(eq(FileOpModule.SONG), any(), eq(FileOpType.MOVE),
                    eq(FileOpLevel.GROUP), eq(pair[1]), any(), any(), any(), any(), any(), any());
        }
    }

    @Test
    public void unknownActionDisplayedAsIs() {
        GroupPlan plan = new GroupPlan("SOMETHING_NEW", "歌", null, "歌", List.of(
                new FileMove("歌.mp4", "F:\\a\\歌.mp4", "歌.mp4", "F:\\b\\歌.mp4", null)), null);
        // 不许抛；action 原样落库；op 按「有 toPath = MOVE」判
        FileOpRecorder.recordPlan(FileOpModule.SONG, plan, FileOpLevel.GROUP);
        verify(service).insert(eq(FileOpModule.SONG), any(), eq(FileOpType.MOVE),
                eq(FileOpLevel.GROUP), eq("SOMETHING_NEW"), any(), any(), any(), any(), any(), any());
    }

    @Test
    public void recordWithoutBatchFallsBackToUnknown() {
        FileOpRecorder.recordPath(FileOpModule.MANGA, FileOpType.MOVE, FileOpLevel.OTHER,
                "模板目录改名", "F:\\旧", "F:\\新");
        // 仍然 insert，source = UNKNOWN、batchId 非空 —— 忘包 batch 必须看得见，不能静默
        ArgumentCaptor<String> batch = ArgumentCaptor.forClass(String.class);
        verify(service).insert(eq(FileOpModule.MANGA), batch.capture(), any(), any(), any(),
                eq(FileOpSource.UNKNOWN), isNull(), any(), any(), any(), any());
        assertNotNull(batch.getValue());
        assertTrue(!batch.getValue().isEmpty());
    }

    @Test
    public void throwingServiceDoesNotPropagate() {
        doThrow(new RuntimeException("MySQL 挂了")).when(service)
                .insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        // 本类最重要的一条：连写入侧自己都炸了，调用方也不能跟着炸
        FileOpRecorder.batch(FileOpModule.SONG, FileOpSource.PAGE, null, () -> {
            FileOpRecorder.recordPath(FileOpModule.SONG, FileOpType.MOVE, FileOpLevel.GROUP,
                    "改名", "F:\\a\\歌.mp4", "F:\\b\\歌.mp4");
            return "ok";
        });
        verify(service).insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    public void batchBodyExceptionPropagatesAndContextCleared() {
        assertThrows(IllegalStateException.class, () ->
                FileOpRecorder.batch(FileOpModule.SONG, FileOpSource.PAGE, null, () -> {
                    throw new IllegalStateException("业务异常不能被吞");
                }));
        // ThreadLocal 已被清掉：紧接着在外面调 recordPath ⇒ UNKNOWN
        FileOpRecorder.recordPath(FileOpModule.SONG, FileOpType.MOVE, FileOpLevel.GROUP,
                "改名", "F:\\a\\歌.mp4", "F:\\b\\歌.mp4");
        verify(service, times(1)).insert(any(), any(), any(), any(), any(),
                eq(FileOpSource.UNKNOWN), isNull(), any(), any(), any(), any());
    }

    @Test
    public void recordFailureWritesFailedRow() {
        FileOpRecorder.batch(FileOpModule.SONG, FileOpSource.PAGE, null, () -> {
            FileOpRecorder.recordFailure(FileOpModule.SONG, FileOpType.DELETE, FileOpLevel.GROUP,
                    "F:\\a\\歌.mp4", null, "文件正被播放器占用");
            return null;
        });
        verify(service).insert(eq(FileOpModule.SONG), any(), eq(FileOpType.DELETE),
                eq(FileOpLevel.GROUP), isNull(), eq(FileOpSource.PAGE), isNull(),
                eq("F:\\a\\歌.mp4"), isNull(), eq(FileOpResult.FAILED),
                eq("文件正被播放器占用"));
    }
}
