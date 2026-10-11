package com.wcdk.r2dbc.repository;

import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.dialect.*;
import com.wcdk.r2dbc.query.QueryWrapper;
import com.wcdk.r2dbc.repository.metadata.RepositoryMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.Id;
import static org.assertj.core.api.Assertions.*;

/**
 * 验证恶意值保持参数绑定，条件和排序字段不能带入 SQL 片段。
 * @author wcdk
 */
class RepositorySecurityContractsTests {
    @Test void valuesStayBoundAndIdentifiersStayWhitelistedAcrossDialects() {
        for (DatabaseDialect dialect : new DatabaseDialect[]{PostgreSqlDatabaseDialect.INSTANCE,
                MySqlDatabaseDialect.INSTANCE, OracleDatabaseDialect.INSTANCE, DmDatabaseDialect.INSTANCE}) {
            for (boolean quoted : new boolean[]{true, false}) {
                var properties = new WcdkR2dbcProperties();
                properties.setQuoteIdentifier(quoted);
                var metadata = new RepositoryMetadata(User.class, properties, dialect);
                var builder = new RepositoryQuerySqlBuilder(properties, metadata, dialect, null);
                String payload = "' OR 1=1; DROP TABLE users; --";
                var where = builder.buildWhere(new QueryWrapper<>().eq("name", payload));
                assertThat(where.sql()).doesNotContain(payload).contains(":p0");
                assertThat(where.parameters()).containsEntry("p0", payload);
                for (String field : new String[]{"name; DROP TABLE users", "name OR 1=1", "u.name"}) {
                    assertThatThrownBy(() -> builder.buildWhere(new QueryWrapper<>().eq(field, "value")))
                            .isInstanceOf(IllegalArgumentException.class);
                    assertThatThrownBy(() -> builder.orderBySql(new QueryWrapper<>().orderByAsc(field)))
                            .isInstanceOf(IllegalArgumentException.class);
                }
            }
        }
    }
    static class User { @Id Long id; String name; }
}
