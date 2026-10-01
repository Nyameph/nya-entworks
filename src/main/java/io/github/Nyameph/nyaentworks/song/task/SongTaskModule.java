package io.github.Nyameph.nyaentworks.song.task;

/**
 * 歌曲模块的任务队列名。
 * <p>同一个 {@code module} 字符串 = 同一条单线程队列，所以歌曲的任务彼此串行 ——
 * 与漫画模块同理：批量改名 / 删除都在动磁盘，并行只会互相拖慢，且模块内串行
 * 避免「同时改同一分区下两个组」的竞争。
 */
public final class SongTaskModule {

    public static final String SONG = "song";

    /**
     * AI 填词专用的队列键（填词助手设计 §6.5）。队列按 module 字符串懒建单线程池、
     * 同 module 串行：本地 7b 模型生成 20 句要分钟级，若挂在 {@link #SONG} 上会把
     * 歌曲同步 / 模板扫描 / 编辑任务全堵在后面，所以必须新开键
     * （async_task.module 是 VARCHAR(20)，12 字符放得下）。
     */
    public static final String SONG_FILL_AI = "song-fill-ai";

    private SongTaskModule() {
    }
}
