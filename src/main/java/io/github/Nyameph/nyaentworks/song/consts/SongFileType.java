package io.github.Nyameph.nyaentworks.song.consts;

/**
 * 合并条目（song_file）下单个文件的类型。
 * <p>落 {@code name()}：MyBatis-Plus 默认枚举处理器按枚举名映射，DDL 的
 * {@code VARCHAR(20)} 存的就是 {@code VIDEO/AUDIO/LYRIC}。
 * 不加 {@code @EnumValue} —— 加了会改变存量列的写入值。
 */
public enum SongFileType {

    /** 视频（mp4）。一个 variant 至多一个 */
    VIDEO,

    /** 音频（mp3/m4a/...）。一个 variant 至多一个 */
    AUDIO,

    /** 歌词（lrc/srt/txt/ass）。一个 variant 可有多个 */
    LYRIC,
}
