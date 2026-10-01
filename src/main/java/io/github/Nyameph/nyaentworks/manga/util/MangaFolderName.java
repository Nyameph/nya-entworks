package io.github.Nyameph.nyaentworks.manga.util;

import org.apache.commons.lang3.StringUtils;

/**
 * 漫画目录名的校验：改名这一类操作要的是**名字**，不是路径。
 * <p>纯静态、不依赖 Spring，与 {@link MangaTextUtil} 同理 —— 这条判断原先在
 * {@code MangaArchiveService} / {@code MangaStoreService} / {@code MangaNewService}
 * 里各抄了一份，三份一字不差；2026-09-24 合并到这里，免得下次只修一处。
 *
 * <p><b>为什么不能拿 {@code contains("..")} 当越界判据</b>（2026-09-24 修的 bug）：
 * 那样一写，名字中间只要出现连续两个点就被拒，而漫画名里很常见的省略号写成 ASCII
 * 就是 {@code ...}（真实样本：「公園でかくれんぼしてただけなのに...」），于是规范化
 * 命名报「目录名里不能有路径分隔符」，而那个名字在文件系统里完全合法。
 * <b>单个名字里出现 {@code ..} 是走不出目录的</b> —— 分隔符已经不在这条路的输入里了
 * （见下），真能表示上级目录的只有整段就是 {@code ..}（或 {@code .}）这一个形态。
 */
public final class MangaFolderName {

    private MangaFolderName() {
    }

    /**
     * 去空白 + 校验：名字里不能有路径分隔符，也不能整段是 {@code .} / {@code ..}。
     * <p>校验的是「最后一段」，所以带分隔符的输入一律拒 —— 那不是改名，是把目录挪到别处。
     *
     * @param folderName 用户填的 / 解析出来的目录名
     * @return 去空白后的名字
     * @throws IllegalArgumentException 空、含 {@code /} 或 {@code \}、或整段是 {@code .} / {@code ..}
     */
    public static String requireSimple(String folderName) {
        String name = StringUtils.trimToNull(folderName);
        if (name == null) {
            throw new IllegalArgumentException("目录名不能为空");
        }
        if (name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("目录名里不能有路径分隔符：" + name);
        }
        // 点只在「整段就是它」时才是路径语义；夹在名字中间的点是普通字符（「...」也算）
        if (name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("目录名不能是 " + name);
        }
        return name;
    }
}
