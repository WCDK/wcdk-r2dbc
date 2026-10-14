package com.wcdk.r2dbc.execution;

import io.r2dbc.spi.*;
import com.wcdk.r2dbc.config.*;
import com.wcdk.r2dbc.execution.lifecycle.SqlLifecycleInterceptorChain;
import com.wcdk.r2dbc.execution.log.R2dbcSqlLogger;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.*;
import reactor.test.StepVerifier;
import java.util.*;
import java.util.function.BiFunction;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RowMappingCleanupTests {
    record Entity(Long id, String name) { }
    static final class Unsupported { }

    @Test void unsupportedConversionNamesSourceAndTargetAndKeepsColumnContext() {
        var mapper = new R2dbcRowMapper();
        assertThatThrownBy(()->mapper.convertValue(new Object(), Unsupported.class))
                .hasMessageContaining("java.lang.Object", Unsupported.class.getName());
        assertThatThrownBy(()->mapper.convertValue(42L, java.util.concurrent.atomic.AtomicInteger.class))
                .hasMessageContaining("java.lang.Long", "AtomicInteger");
        var fixture = data(List.of("value"), List.of(new Object()), 1);
        assertThatThrownBy(()->mapper.map(fixture.row, UnsupportedEntity.class))
                .hasMessageContaining("列 'value'", Unsupported.class.getName()).hasStackTraceContaining("java.lang.Object");
    }
    record UnsupportedEntity(Unsupported value) { }

    @Test void conditionRecordIsDeprecatedInFavorOfAst() {
        @SuppressWarnings("removal")
        var annotation = com.wcdk.r2dbc.query.QueryWrapper.Condition.class.getAnnotation(Deprecated.class);
        assertThat(annotation).isNotNull(); assertThat(annotation.forRemoval()).isTrue();
    }

    @Test void conditionUsageProducesRemovalWarning(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var source=directory.resolve("LegacyCondition.java");
        java.nio.file.Files.writeString(source,"class LegacyCondition { com.wcdk.r2dbc.query.QueryWrapper.Condition condition; }");
        var compiler=javax.tools.ToolProvider.getSystemJavaCompiler();
        var diagnostics=new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
        try (var files=compiler.getStandardFileManager(diagnostics,null,null)) {
            assertThat(compiler.getTask(null,files,diagnostics,java.util.List.of("-proc:none","-Xlint:removal",
                    "-classpath",System.getProperty("java.class.path"),"-d",directory.toString()),null,
                    files.getJavaFileObjects(source.toFile())).call()).isTrue();
        }
        assertThat(diagnostics.getDiagnostics()).anySatisfy(diagnostic->{
            assertThat(diagnostic.getKind()).isIn(javax.tools.Diagnostic.Kind.WARNING,javax.tools.Diagnostic.Kind.MANDATORY_WARNING);
            assertThat(diagnostic.getMessage(java.util.Locale.ROOT)).contains("Condition");
        });
    }

    @Test void largeResultBuildsColumnIndexOnceInsteadOfOncePerRow() {
        int rows=10000;
        var data = data(List.of("ID", "NAME"),List.of(7L,"alpha"),rows);
        var mapper = new R2dbcRowMapper();
        for (int i=0;i<rows;i++) assertThat(mapper.map(data.row,Entity.class)).isEqualTo(new Entity(7L,"alpha"));
        verify(data.metadata,times(rows)).getColumnMetadatas();
        for (var column : data.columns) verify(column,times(rows)).getName();
        clearInvocations(data.metadata); data.columns.forEach(column->clearInvocations(column));
        StepVerifier.create(operations(data.result).query("SELECT id,name FROM sample",(row,metadata)->mapper.map(row,Entity.class)))
                .expectNextCount(rows).verifyComplete();
        verify(data.metadata,times(1)).getColumnMetadatas();
        for (var column : data.columns) verify(column,times(1)).getName();
        System.out.printf("Result列索引：行数=%d，元数据遍历从 %d 降为 1%n",rows,rows);
    }

    @Test void resultsAndRepeatedSubscriptionsHaveIndependentColumnIndexes() {
        var first=data(List.of("ID","NAME"),List.of(7L,"alpha"),1);
        var second=data(List.of("name","id"),List.of("beta",9L),1);
        var mapper=new R2dbcRowMapper();
        var publisher=operations(first.result,second.result).query("SELECT id,name FROM sample",(row,metadata)->mapper.map(row,Entity.class));
        for (int i=0;i<2;i++) StepVerifier.create(publisher).expectNext(new Entity(7L,"alpha"),new Entity(9L,"beta")).verifyComplete();
        verify(first.metadata,times(2)).getColumnMetadatas();verify(second.metadata,times(2)).getColumnMetadatas();
    }

    private R2dbcQueryOperations operations(Result... results) {
        ConnectionFactory factory=mock(ConnectionFactory.class);when(factory.getMetadata()).thenReturn(()->"PostgreSQL");
        Connection connection=mock(Connection.class);Statement statement=mock(Statement.class);
        doReturn(Mono.just(connection)).when(factory).create();when(connection.createStatement(anyString())).thenReturn(statement);
        doReturn(Flux.fromArray(results)).when(statement).execute();doReturn(Mono.empty()).when(connection).close();
        var client=DatabaseClient.create(factory);
        return new R2dbcQueryOperations(client,new ParameterBinder(),new SqlLifecycleExecutor(new SqlLifecycleInterceptorChain(List.of(),List.of())),
                new R2dbcSqlLogger(new WcdkR2dbcProperties(),new WcdkSpringR2dbcProperties()));
    }

    private Data data(List<String> names,List<Object> values,int count) {
        RowMetadata metadata=mock(RowMetadata.class);Row row=mock(Row.class);Result result=mock(Result.class);
        List<ColumnMetadata> columns=new ArrayList<>();
        for (int i=0;i<names.size();i++) {
            ColumnMetadata column=mock(ColumnMetadata.class);when(column.getName()).thenReturn(names.get(i));columns.add(column);
            when(row.get(names.get(i))).thenReturn(values.get(i));
        }
        doReturn(columns).when(metadata).getColumnMetadatas();when(row.getMetadata()).thenReturn(metadata);
        when(result.map(any(BiFunction.class))).thenAnswer(invocation->{
            BiFunction<Row,RowMetadata,Object> fn=invocation.getArgument(0);
            return Flux.range(0,count).map(i->fn.apply(row,metadata));
        });
        return new Data(row,metadata,result,columns);
    }
    record Data(Row row,RowMetadata metadata,Result result,List<ColumnMetadata> columns) { }
}
