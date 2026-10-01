package io.github.Nyameph.nyaentworks.manga.generator;

import com.baomidou.mybatisplus.generator.FastAutoGenerator;
import com.baomidou.mybatisplus.generator.config.rules.DbColumnType;
import com.baomidou.mybatisplus.generator.engine.VelocityTemplateEngine;

import java.nio.file.Paths;
import java.sql.Types;

public class CodeGenerator {
    public static void main(String[] args) {
        FastAutoGenerator.create("jdbc:mysql://localhost:3306/nya_entworks?useSSL=false&serverTimezone=GMT%2B8", "root", "root")
                .globalConfig(builder -> {
                    builder.author("nya-entworks") // 设置作者
                            // Swagger 2（springfox）已停止维护，改用 OpenAPI 3 注解（@Schema）
                            .enableSpringdoc()
                            .outputDir(Paths.get(System.getProperty("user.dir")) + "/src/main/java")
                            .commentDate("yyyy-MM-dd");
                })
                .dataSourceConfig(builder ->
                        builder.typeConvertHandler((globalConfig, typeRegistry, metaInfo) -> {
                            int typeCode = metaInfo.getJdbcType().TYPE_CODE;
                            if (typeCode == Types.SMALLINT) {
                                // 自定义类型转换
                                return DbColumnType.INTEGER;
                            }
                            return typeRegistry.getColumnType(metaInfo);
                        })
                )
                .packageConfig(builder ->
                        builder.parent("io.github.Nyameph.nyaentworks.manga")
                                .entity("entity")
                                .mapper("mapper")
                                .service("service")
                                .serviceImpl("service.impl")
                                .xml("mapper.xml")// 设置mapperXml生成路径
                )
                .strategyConfig(builder ->
                        builder.entityBuilder()
                                .enableLombok()
                )
                .templateEngine(new VelocityTemplateEngine())
                .execute();
    }
}
