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
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
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
    @Test void logGatesAndAuditUseTheSameSanitization(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        var properties=new WcdkR2dbcProperties();
        var logger=new com.wcdk.r2dbc.execution.log.R2dbcSqlLogger(properties,new com.wcdk.r2dbc.config.WcdkSpringR2dbcProperties());
        var audit=new com.wcdk.r2dbc.execution.lifecycle.SqlAuditInterceptor(logger);
        var context=new com.wcdk.r2dbc.execution.lifecycle.SqlExecutionContext(
                BaseRepository.class.getMethod("selectById",Object.class),BaseRepository.class,new Object[]{1L});
        context.setSql("SELECT * FROM users WHERE password=:password AND contact=:contact");
        context.setParameters(java.util.Map.of("password","private-password","contact","13800138000"));
        context.setError(new IllegalStateException("Duplicate entry '13800138000', password=private-password"));
        logger.logExecution(context.getExecutionId(),context.getSql(),context.getParameters(),context.getError());
        audit.afterExecute(context);
        assertThat(output.getAll()).doesNotContain("SQL_AUDIT","completeSql=","private-password","13800138000");
        properties.setSqlLogEnabled(true);
        logger.logExecution(context.getExecutionId(),context.getSql(),context.getParameters(),context.getError());
        audit.afterExecute(context);
        assertThat(output.getAll()).contains("SQL_AUDIT",context.getExecutionId(),"[REDACTED]")
                .doesNotContain("completeSql=","private-password","13800138000");
        properties.setSqlLogCompleteEnabled(true);
        logger.logExecution(context.getExecutionId(),context.getSql(),context.getParameters(),1L);
        assertThat(output.getAll()).contains("completeSql=").doesNotContain("private-password","13800138000");
    }

    @Test void configuredNamesTypedNullsNestedValuesAndPrivacyPatternsAreRedacted() {
        var properties=new WcdkR2dbcProperties(); properties.getSensitiveColumns().add("customerEmail");
        var logger=new com.wcdk.r2dbc.execution.log.R2dbcSqlLogger(properties,new com.wcdk.r2dbc.config.WcdkSpringR2dbcProperties());
        var values=java.util.Map.of("customer_email","private@example.com","contact",13800138000L,
                "payload",java.util.Map.of("password","nested-private","normal","11010519900101123X"),
                "wrapped",com.wcdk.r2dbc.execution.SqlParameter.of("13800138000"));
        assertThat(logger.sanitizeParameters(values).toString()).doesNotContain("private@example.com","13800138000","11010519900101123X","nested-private");
        assertThat(logger.completeSql("SELECT :customer_email, :contact, :wrapped",values))
                .doesNotContain("private@example.com","13800138000").contains("'[REDACTED]'");
        assertThat(logger.completeSql("SELECT 'a\\' :inside', :outside # :inside",java.util.Map.of("inside","secret","outside",7)))
                .contains(":inside", "7 # :inside").doesNotContain("secret");
    }

    @Test void rawCredentialsNestedErrorValuesAndSqlPlaceholdersAreHandledSafely() {
        var logger=new com.wcdk.r2dbc.execution.log.R2dbcSqlLogger(new WcdkR2dbcProperties(),new com.wcdk.r2dbc.config.WcdkSpringR2dbcProperties());
        assertThat(logger.sanitizeText("password=:password AND secret=$1",java.util.Map.of())).isEqualTo("password=:password AND secret=$1");
        assertThat(logger.sanitizeText("password='a secret with spaces' token=private-token",java.util.Map.of()))
                .doesNotContain("a secret", "private-token");
        assertThat(logger.sanitizeText("Duplicate entry 'nested-private'", java.util.Map.of("payload",java.util.Map.of("password","nested-private"))))
                .doesNotContain("nested-private");
        assertThat(logger.completeSql("SELECT :password",java.util.Map.of("password","sec'ret"))).isEqualTo("SELECT '[REDACTED]'");
    }

    @Test void xmlExternalEntitiesRemainRejectedWithoutReadingTheSecret(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var secret=directory.resolve("secret.txt");java.nio.file.Files.writeString(secret,"xxe-must-not-escape");
        var xml=directory.resolve("mapper.xml");java.nio.file.Files.writeString(xml,
                "<!DOCTYPE mapper [<!ENTITY leaked SYSTEM '"+secret.toUri()+"'>]><mapper namespace='test'><select id='read'>SELECT '&leaked;'</select></mapper>");
        var properties=new WcdkR2dbcProperties();properties.setMapperLocations(xml.toUri().toString());
        assertThatThrownBy(()->new com.wcdk.r2dbc.query.xml.RepositoryXmlRegistry(
                new org.springframework.core.io.support.PathMatchingResourcePatternResolver(),properties))
                .hasMessageContaining("XML").hasStackTraceContaining("DOCTYPE");
    }

    static class User { @Id Long id; String name; }
}
