package io.github.Nyameph.nyaentworks.common.lyric;

/**
 * 一行歌词。三种格式（lrc / srt / txt）统一成这个形态（实现说明 5.8）。
 *
 * <p>与 {@link LyricParser} 一起放在 {@code common}：填词歌曲与喊麦两个模块都要读歌词。
 *
 * @param start 开始秒数。<b>txt 没有时间轴时为 null</b>，前端据此降级成纯文本
 * @param end   结束秒数。lrc 用下一行的开始时间补；srt 自带；最后一行为 null
 * @param text  歌词文本
 */
public record LyricLine(Double start, Double end, String text) {
}
