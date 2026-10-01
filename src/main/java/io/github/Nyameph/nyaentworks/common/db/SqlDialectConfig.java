package io.github.Nyameph.nyaentworks.common.db;

import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Properties;

/**
 * 把方言探测结果同时喂给两处：{@link SqlDialect}（给手写的 SQL 片段用）与
 * MyBatis 的 {@code databaseId}（给整句不同的 mapper 语句用，见
 * {@code mapper/RhymeEntryMapper.xml}）。
 *
 * <p>探测放在这个 {@code @Bean} 方法里而不是另一个 {@code @PostConstruct} 里：它本来就
 * 是 {@code databaseId} 的来源，两者<b>同一次</b>发生，不存在「探了但没人看」或
 * 「看了但还没探」的窗口。MyBatis-Plus 在构造 {@code SqlSessionFactory} 时取这个 Bean，
 * 那时业务代码还一行没跑。
 *
 * <p><b>刻意不返回 MyBatis 自带的 {@code VendorDatabaseIdProvider}</b>：那个是靠产品名
 * 做子串匹配，匹不上就返回 {@code null}，而 {@code databaseId} 为 null 时 XML 里
 * 带 {@code databaseId} 的语句<b>一条都不会被加载</b>（症状是运行期
 * {@code Invalid bound statement (not found)}）。这里直接回读 {@link SqlDialect#kind()}
 * 的结果，恒为 {@code mysql} / {@code sqlite} 之一。
 */
@Configuration
public class SqlDialectConfig {

    @Bean
    public DatabaseIdProvider databaseIdProvider(DataSource dataSource) {
        SqlDialect.detect(dataSource);
        return new DatabaseIdProvider() {
            @Override
            public String getDatabaseId(DataSource ds) throws SQLException {
                // 用探测好的那份，不再连一次库 —— 两个入口必须同一个答案
                return SqlDialect.kind().databaseId();
            }

            @Override
            public void setProperties(Properties p) {
                // MyBatis 从 XML 配置装配时才调它，这里用不上
            }
        };
    }
}
