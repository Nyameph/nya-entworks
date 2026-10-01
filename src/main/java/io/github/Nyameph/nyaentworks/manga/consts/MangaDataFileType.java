package io.github.Nyameph.nyaentworks.manga.consts;

import org.apache.commons.lang3.StringUtils;

/**
 * 单本漫画在磁盘上的文件形态。
 * <p>「漫画单元」本可以是目录（散图），也可以是 {@code .cbz}（图片打包成的单个文件），
 * 两者都能被扫描/阅读/评分（见 {@code MangaCbzUtil}）。落库时把形态标在这一列，
 * 免得每次都 {@code Files.isDirectory} 现看磁盘。
 * <p><b>后续可扩展</b>：pdf、epub 等新形态加枚举值即可，判定口径以 {@link #fromFolderPath} 为准。
 */
public enum MangaDataFileType {

    /** 图片散放在目录里 */
    FOLDER,

    /** 打包成 {@code .cbz} 的单个文件 */
    CBZ;

    /**
     * 从 {@code folder_path} 后缀推断形态，不碰磁盘。
     * <p>与 {@code MangaCbzUtil.isCbz} 同口径（忽略大小写的 {@code .cbz} 结尾），
     * 纯看路径字符串 —— 所以磁盘上已经不存在的 MISSING 行也能标对。
     *
     * @return {@code null} 表示路径为空、推断不出；否则 {@link #CBZ} 或 {@link #FOLDER}
     */
    public static MangaDataFileType fromFolderPath(String folderPath) {
        if (StringUtils.isBlank(folderPath)) {
            return null;
        }
        return StringUtils.endsWithIgnoreCase(folderPath, ".cbz") ? CBZ : FOLDER;
    }
}
