package io.github.Nyameph.nyaentworks.shout.consts;

/**
 * 喊麦组（{@code shout_file}）下单个文件的类型。
 * <p>落 {@code name()}：MyBatis-Plus 默认枚举处理器按枚举名映射，DDL 的
 * {@code VARCHAR(20)} 存的就是 {@code VIDEO/AUDIO/LYRIC}。不加 {@code @EnumValue}。
 * <p>分类口径来自 {@code MediaExtensions} 的 {@code isVideo/isAudio}，与歌曲侧同一套。
 */
public enum ShoutFileType {

    /** 视频（mp4）。一组至多一个 */
    VIDEO,

    /** 音频（mp3/m4a/...）。一组至多一个 */
    AUDIO,

    /** 歌词（lrc/srt/txt/ass）。一组可多个 */
    LYRIC,
}
