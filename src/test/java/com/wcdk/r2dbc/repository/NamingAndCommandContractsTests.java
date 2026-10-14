package com.wcdk.r2dbc.repository;

import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.execution.*;
import com.wcdk.r2dbc.execution.lifecycle.SqlExecutionContext;
import com.wcdk.r2dbc.mapping.NamingStrategy;
import com.wcdk.r2dbc.query.LambdaDeleteWrapper;
import com.wcdk.r2dbc.query.xml.RepositoryStatement;
import com.wcdk.r2dbc.repository.metadata.RepositoryMetadata;
import com.wcdk.r2dbc.repository.plan.*;
import io.r2dbc.spi.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.relational.core.mapping.Column;
import reactor.core.publisher.Mono;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NamingAndCommandContractsTests {
    @Test void acronymsAndExplicitColumnsAreConsistentAcrossMetadataAndMapping() throws Exception {
        Map<String, String> names = Map.of("URLValue", "url_value", "userID", "user_id", "CPUTime", "cpu_time",
                "XMLHTTPParser", "xmlhttp_parser", "simpleName", "simple_name");
        names.forEach((input, expected) -> assertThat(NamingStrategy.toSnakeCase(input)).isEqualTo(expected));
        for (boolean quote : List.of(false, true)) {
            var properties = new WcdkR2dbcProperties(); properties.setQuoteIdentifier(quote);
            var metadata = new RepositoryMetadata(URLInfo.class, properties);
            assertThat(metadata.tableName()).isEqualTo(quote ? "\"url_info\"" : "url_info");
            assertThat(metadata.columnByName("URLValue").rawName()).isEqualTo("url_value");
            assertThat(metadata.columnByName("CPUTime").rawName()).isEqualTo("cpu_time");
            assertThat(metadata.columnByName("URL").rawName()).isEqualTo("ExplicitURL");
            var resolver = new CustomMethodResolver(metadata, 1, 0);
            var method = Methods.class.getMethod("findByURLValueAndCPUTime", String.class, Long.class);
            var definition = resolver.compile(method);
            var plan = DerivedQueryPlanModel.compile(method, definition, metadata);
            assertThat(metadata.columnByName(plan.definition().conditions().getFirst().fieldName()).name()).isEqualTo(metadata.columnByName("URLValue").name());
            assertThat(resolver.resolveCompiled(plan, new Object[]{"link", 9L}).sql())
                    .contains(metadata.columnByName("URLValue").name() + " = :p0");
        }
        var row = mock(Row.class); var metadata = mock(RowMetadata.class);
        List<ColumnMetadata> columns = new ArrayList<>();
        for (String name : List.of("url_value", "cpu_time", "ExplicitURL")) {
            var column = mock(ColumnMetadata.class); when(column.getName()).thenReturn(name); columns.add(column);
        }
        when(row.getMetadata()).thenReturn(metadata); doReturn(columns).when(metadata).getColumnMetadatas();
        when(row.get("url_value")).thenReturn("link"); when(row.get("cpu_time")).thenReturn(9L); when(row.get("ExplicitURL")).thenReturn("explicit");
        var mapped = new R2dbcRowMapper().map(row, URLInfo.class);
        assertThat(mapped.URLValue).isEqualTo("link"); assertThat(mapped.CPUTime).isEqualTo(9L); assertThat(mapped.URL).isEqualTo("explicit");
        assertThat(NamingStrategy.tableName(ExplicitTable.class)).isEqualTo("KeepTable");
        assertThat(LambdaDeleteWrapper.of(URLInfo.class).eq(URLInfo::getURL, "value").getConditions().getFirst().column()).isEqualTo("ExplicitURL");
    }

    @Test void commandIdentityIsSharedByPlansStatementsRequestsAndLifecycle() throws Exception {
        assertThat(SqlCommandType.values()).containsExactly(SqlCommandType.INSERT, SqlCommandType.UPDATE,
                SqlCommandType.DELETE, SqlCommandType.SELECT, SqlCommandType.MERGE, SqlCommandType.UNKNOWN);
        var method = Methods.class.getMethod("deleteByURLValue", String.class);
        var properties = new WcdkR2dbcProperties();
        var metadata = new RepositoryMetadata(URLInfo.class, properties);
        var compiler = new DerivedMethodCompiler(Methods.class, metadata, properties);
        var plan = compiler.compile(method).orElseThrow();
        assertThat(plan.commandType()).isEqualTo(SqlCommandType.DELETE);
        assertThat(((DerivedQueryPlanModel) plan.sqlPlan().compiledPlan()).commandType()).isEqualTo(SqlCommandType.DELETE);
        assertThat(new SqlExecutionContext(method, Methods.class, new Object[]{"x"}).getCommandType()).isEqualTo(SqlCommandType.DELETE);
        var statement = new RepositoryStatement(Methods.class.getName(), "custom", SqlCommandType.MERGE, "MERGE INTO t");
        var xmlPlan = new RepositoryMethodPlan(method, RepositoryMethodPlan.Kind.XML, statement, statement.statementId());
        assertThat(xmlPlan.commandType()).isSameAs(statement.commandType());
        assertThat(SqlExecutionRequest.update("UPDATE t SET n=1", Map.of()).commandType()).isEqualTo(SqlCommandType.UPDATE);
        assertThat(SqlCommandType.fromMethodName("mergeRows")).isEqualTo(SqlCommandType.MERGE);
        assertThat(SqlCommandType.fromMethodName("toString")).isEqualTo(SqlCommandType.UNKNOWN);
    }

    interface Methods {
        Mono<URLInfo> findByURLValueAndCPUTime(String URLValue, Long CPUTime);
        Mono<Long> deleteByURLValue(String URLValue);
    }
    static class URLInfo {
        String URLValue; Long CPUTime;
        @Column("ExplicitURL") String URL;
        public String getURL() { return URL; }
    }
    @org.springframework.data.relational.core.mapping.Table("KeepTable") static class ExplicitTable {}
}
