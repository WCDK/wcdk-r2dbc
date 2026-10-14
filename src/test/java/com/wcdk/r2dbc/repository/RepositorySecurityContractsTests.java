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
    @Test void logGateAndAuditPreserveOriginalParameters(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
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
        assertThat(output.getAll()).contains("SQL_AUDIT",context.getExecutionId(),"completeSql=", "private-password", "13800138000")
                .doesNotContain("[REDACTED]");
        properties.setSqlLogCompleteEnabled(true);
        logger.logExecution(context.getExecutionId(),context.getSql(),context.getParameters(),1L);
        assertThat(output.getAll()).contains("completeSql=", "private-password", "13800138000");
    }

    @Test void configuredNamesAndPrivacyPatternsDoNotChangeValues() {
        var properties=new WcdkR2dbcProperties(); properties.getSensitiveColumns().add("customerEmail");
        var logger=new com.wcdk.r2dbc.execution.log.R2dbcSqlLogger(properties,new com.wcdk.r2dbc.config.WcdkSpringR2dbcProperties());
        var values=java.util.Map.of("customer_email","private@example.com","contact",13800138000L,
                "payload",java.util.Map.of("password","nested-private","normal","11010519900101123X"),
                "wrapped",com.wcdk.r2dbc.execution.SqlParameter.of("13800138000"));
        assertThat(logger.logParameters(values).toString()).contains("private@example.com","13800138000","11010519900101123X","nested-private");
        assertThat(logger.completeSql("SELECT :customer_email, :contact, :wrapped",values))
                .contains("private@example.com","13800138000").doesNotContain("[REDACTED]");
        assertThat(logger.completeSql("SELECT 'a\\' :inside', :outside # :inside",java.util.Map.of("inside","secret","outside",7)))
                .contains(":inside", "7 # :inside").doesNotContain("secret");
    }

    @Test void rawCredentialsAndErrorMessagesRemainUnmodified() {
        var logger=new com.wcdk.r2dbc.execution.log.R2dbcSqlLogger(new WcdkR2dbcProperties(),new com.wcdk.r2dbc.config.WcdkSpringR2dbcProperties());
        assertThat(logger.sanitizeText("password=:password AND secret=$1",java.util.Map.of())).isEqualTo("password=:password AND secret=$1");
        assertThat(logger.sanitizeText("\"password\"='quoted private value'",java.util.Map.of())).contains("quoted private value");
        assertThat(logger.sanitizeText("password='a secret with spaces' token=private-token",java.util.Map.of()))
                .contains("a secret", "private-token");
        assertThat(logger.sanitizeText("Duplicate entry 'nested-private'", java.util.Map.of("payload",java.util.Map.of("password","nested-private"))))
                .contains("nested-private");
        assertThat(logger.completeSql("SELECT :password",java.util.Map.of("password","sec'ret"))).isEqualTo("SELECT 'sec''ret'");
    }

    @Test void generatedAliasesAndIndexedBindingsKeepOriginalValues(org.springframework.boot.test.system.CapturedOutput output) {
        var properties=new WcdkR2dbcProperties();properties.setSqlLogEnabled(true);properties.setSqlLogCompleteEnabled(true);
        var logger=new com.wcdk.r2dbc.execution.log.R2dbcSqlLogger(properties,new com.wcdk.r2dbc.config.WcdkSpringR2dbcProperties());
        String sql="SELECT * FROM users WHERE \"password\" IN (:p0,:p1)";
        var values=java.util.Map.of("p0","alias-private-a","p1","alias-private-b");
        logger.logExecution("alias-context",sql,values,new IllegalStateException("Duplicate alias-private-a"));
        logger.logExecution("alias-insert","INSERT INTO t(name,password) VALUES (:p0,:p1)",java.util.Map.of("p0","public","p1","insert-private"),1);
        assertThat(logger.sanitizeParameters("UPDATE t SET name=?,password=?",java.util.Map.of(0,"public",1,"indexed-private")))
                .containsEntry("0","public").containsEntry("1","indexed-private");
        properties.getSensitiveColumns().add("密码");
        assertThat(logger.sanitizeParameters("SELECT * FROM t WHERE \"密码\"=:p0",java.util.Map.of("p0","unicode-private")))
                .containsEntry("p0","unicode-private");
        assertThat(output.getAll()).contains("alias-private-a","alias-private-b","insert-private");
    }

    @Test void loggingFailureIsFailClosedAndCannotChangeACompletedBusinessResult(org.springframework.boot.test.system.CapturedOutput output) {
        var properties=new WcdkR2dbcProperties();properties.setSqlLogEnabled(true);
        var logger=new com.wcdk.r2dbc.execution.log.R2dbcSqlLogger(properties,new com.wcdk.r2dbc.config.WcdkSpringR2dbcProperties());
        Object broken=new Object(){public String toString(){throw new IllegalStateException("sensitive-log-failure");}};
        reactor.test.StepVerifier.create(reactor.core.publisher.Mono.just(1).doOnNext(value->
                logger.logExecution("safe-execution","SELECT :payload",java.util.Map.of("payload",broken),value)))
                .expectNext(1).verifyComplete();
        assertThat(output.getAll()).contains("safe-execution").doesNotContain("sensitive-log-failure");
    }

    enum LogStatus { ACTIVE; public String toString(){return "wrong-status";} }
    @Test void completeSqlKeepsLongStatementStructureAndUsesBoundEnumNames(){
        var logger=new com.wcdk.r2dbc.execution.log.R2dbcSqlLogger(new WcdkR2dbcProperties(),new com.wcdk.r2dbc.config.WcdkSpringR2dbcProperties());
        String sql="SELECT "+"column_name,".repeat(40)+"id FROM sample WHERE status=:status";
        assertThat(logger.completeSql(sql,java.util.Map.of("status",LogStatus.ACTIVE)))
                .isEqualTo(sql.replace(":status","'ACTIVE'")).doesNotContain("wrong-status");
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
