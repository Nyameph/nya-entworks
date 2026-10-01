package io.github.Nyameph.nyaentworks.manga.consts;

/**
 * 归档别名是怎么来的。
 * <p>迁库之初别名全部由目录名派生，{@code sync} 每次整组重建即可。归档编辑表单
 * 允许「增加关联作者但不改目录名」之后，出现了目录名里没有的别名 —— 若仍整组重建，
 * 这些别名会在下一次同步时被静默删掉，且没有任何地方能把它们找回来。
 * <p>所以别名分两种来源：{@link #FOLDER} 的归目录名管、同步时整组替换；
 * {@link #MANUAL} 的只归库管、同步时保留。这是<b>「文件系统是权威」的唯一例外</b>，
 * 因为「这个作者的作品也放这个目录」这件事目录名表达不了。
 */
public enum MangaArchiveNameSource {

    /** 从目录名的 {@code [社团 (作者)]} 块解析而来，随目录名变化 */
    FOLDER,

    /** 人工加的额外关联，目录名里没有，{@code sync} 不动它 */
    MANUAL,
}
