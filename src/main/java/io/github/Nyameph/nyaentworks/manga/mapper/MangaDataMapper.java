package io.github.Nyameph.nyaentworks.manga.mapper;

import io.github.Nyameph.nyaentworks.manga.entity.MangaData;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 漫画资源表 Mapper 接口
 * </p>
 *
 * @author Nyameph
 * @since 2024-11-23
 */
public interface MangaDataMapper extends BaseMapper<MangaData> {

    /**
     * 每个归档目录下、按「多少本漫画带它」聚合的内容标签频次。
     * <p>口径 = 只统计每本漫画自己的独立标签（{@code manga_tag_ref} 的 MANGA_DATA 关联），
     * 不把继承的目录标签算进去 —— 归档作者页「高频标签」列的展示依据。
     * <p>{@code manga_tag.count_ignore = 1} 的标签不计入统计（归档作者页明确排除）。
     * <p>按 {@code tag_name} 分组：同名不同 namespace 的词典标签在展示上合并成一条。
     * <p>结果已按 {@code archive_unit_id, cnt DESC, tag_name} 排序，每个 unit 的前 3 行
     * 即出现本数最多的前 3 个标签。
     *
     * @param unitIds 归档目录 id 集合。调用方必须守空集合 —— 空集合会渲染成 {@code IN ()}
     *                语法报错（速查索引 §7.27）
     * @return 行形如 {unit_id, tag_name, cnt}
     */
    @Select("<script>"
            + "SELECT d.archive_unit_id AS unit_id, t.tag_name AS tag_name, COUNT(*) AS cnt "
            + "FROM manga_data d "
            + "JOIN manga_tag_ref r ON r.target_type = 'MANGA_DATA' AND r.target_id = d.id "
            + "JOIN manga_tag t ON t.id = r.tag_id "
            + "AND (t.count_ignore IS NULL OR t.count_ignore = 0) "
            + "WHERE d.archive_unit_id IN "
            + "<foreach collection='unitIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "GROUP BY d.archive_unit_id, t.tag_name "
            + "ORDER BY d.archive_unit_id, cnt DESC, t.tag_name ASC"
            + "</script>")
    List<Map<String, Object>> countTagsByUnit(@Param("unitIds") Collection<Long> unitIds);
}
