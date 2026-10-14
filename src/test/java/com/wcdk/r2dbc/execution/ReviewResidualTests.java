package com.wcdk.r2dbc.execution;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class ReviewResidualTests {
    record Value(Integer count) { }
    @Test void interfaceDefaultMappingReusesPlansAndDelegatesConverters(){
        var operations=mock(RepositoryOperations.class,CALLS_REAL_METHODS);
        doReturn(7).when(operations).convertValue("external",Integer.class);
        var mapper=RepositoryMappingCache.mapper(operations);
        assertThat(operations.mapProperties(Map.of("count","external"),Value.class,"test")).isEqualTo(new Value(7));
        assertThat(operations.mapProperties(Map.of("count","external"),Value.class,"test")).isEqualTo(new Value(7));
        assertThat(RepositoryMappingCache.mapper(operations)).isSameAs(mapper);
        assertThat(mapper.cacheStats().misses()).isEqualTo(1);
        verify(operations,times(2)).convertValue("external",Integer.class);
    }
    @Test void returningClauseHandlesTerminatorAndTrailingCommentsWithoutChangingLiterals(){
        assertThat(NamedParameterParser.appendClause("INSERT INTO t VALUES ('--;'); -- tail", " RETURNING id"))
                .isEqualTo("INSERT INTO t VALUES ('--;') -- tail\nRETURNING id");
        assertThat(NamedParameterParser.appendClause("INSERT INTO t VALUES ($$x;--$$); /* tail */", "RETURNING id"))
                .isEqualTo("INSERT INTO t VALUES ($$x;--$$) /* tail */ RETURNING id");
        assertThat(NamedParameterParser.appendClause("INSERT INTO t VALUES ('v')", " RETURNING id"))
                .isEqualTo("INSERT INTO t VALUES ('v') RETURNING id");
    }
}
