package io.github.Nyameph.nyaentworks.manga.task;

/**
 * 漫画模块的任务队列名。
 * <p>同一个 {@code module} 字符串 = 同一条单线程队列，所以漫画的任务彼此串行 ——
 * 压缩本来就在吃磁盘 IO，并行只会互相拖慢，而且「同时归档两本到同一个目录」
 * 这类竞争也就不存在了。
 */
public final class MangaTaskModule {

    public static final String MANGA = "manga";

    private MangaTaskModule() {
    }
}
