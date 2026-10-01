package io.github.Nyameph.nyaentworks.common.lyric;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 歌词文件的编码嗅探与解码（实现说明 5.8）。
 *
 * <p>放在 {@code common} 而不是 {@code song} 下：填词歌曲与喊麦两个模块都要读歌词，
 * 而「BOM → 严格 UTF-8 → GBK」这套判断只能有一份（判错的症状是整篇乱码且不报错）。
 *
 * <p>磁盘上的歌词编码是混的（实测 741 个 utf-8、74 个 GBK 系、12 个 utf-16），
 * 没有统一转码的打算，所以读的时候要猜。顺序是：
 * <ol>
 *   <li>看 BOM（UTF-8 / UTF-16LE / UTF-16BE）—— 有 BOM 就不用猜
 *   <li><b>严格</b> UTF-8 解码
 *   <li>回落 GBK
 * </ol>
 *
 * <p><b>第 2 步不能用宽松模式</b>：宽松解码会把 GBK 字节替换成 {@code �} 然后
 * 「成功」返回，于是歌词全是乱码而没有任何报错 —— 这类问题看现象查不出原因，
 * 只有严格模式抛 {@link CharacterCodingException} 才能触发回落。
 */
public final class TextDecoder {

    private TextDecoder() {
    }

    /** GBK 在 JDK 里一定有（中文 Windows 的默认代码页），不必判存在 */
    private static final Charset GBK = Charset.forName("GBK");

    /**
     * 读文件并解码。
     *
     * @return 文本，行分隔符保持原样（解析侧会先归一化成 {@code \n} 再切）
     */
    public static String read(Path path) throws IOException {
        return decode(Files.readAllBytes(path));
    }

    /** 解码字节。BOM 会被去掉，不然 BOM 会留在第一行开头影响 {@code [00:00.00]} 的匹配 */
    public static String decode(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        String utf8 = strictDecode(bytes, StandardCharsets.UTF_8);
        if (utf8 != null) {
            return utf8;
        }
        // GBK 也用严格模式试一次，两个都不成才宽松兜底 —— 至少让人看到部分内容，
        // 而不是抛异常让整首歌的歌词都打不开
        String gbk = strictDecode(bytes, GBK);
        return gbk != null ? gbk : new String(bytes, GBK);
    }

    /** @return 解码结果；有非法字节时返回 null（交给调用方回落） */
    private static String strictDecode(byte[] bytes, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer buffer = decoder.decode(ByteBuffer.wrap(bytes));
            return buffer.toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
