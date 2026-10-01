package io.github.Nyameph.nyaentworks.shout.task;

/**
 * 喊麦模块的任务队列名。
 * <p>同一个 {@code module} 字符串 = 同一条单线程队列。喊麦单独一条而不是搭歌曲那条：
 * 两个模块的批量操作互不相干，共用一条的话喊麦的删除会排在歌曲的同步后面空等。
 * <p>队列本身是懒创建的（第一次提交任务时才建），所以这里加一条不需要任何注册。
 */
public final class ShoutTaskModule {

    public static final String SHOUT = "shout";

    private ShoutTaskModule() {
    }
}
