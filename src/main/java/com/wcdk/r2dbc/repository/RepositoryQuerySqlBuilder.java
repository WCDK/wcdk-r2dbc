package com.wcdk.r2dbc.repository;

import com.wcdk.r2dbc.R2dbcUtil;
import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.execution.lifecycle.SqlExecutionContext;
import com.wcdk.r2dbc.execution.lifecycle.SqlLifecycleInterceptorChain;
import com.wcdk.r2dbc.execution.SqlLifecycleExecutor;
import com.wcdk.r2dbc.repository.metadata.RepositoryMetadata;
import com.wcdk.r2dbc.id.SnowflakeIdGenerator;
import com.wcdk.r2dbc.repository.metadata.RepositoryMetadata.FieldColumn;
import com.wcdk.r2dbc.query.QueryWrapper;
import com.wcdk.r2dbc.query.sql.RenderedPredicate;
import com.wcdk.r2dbc.query.sql.SqlExpressionRenderer;
import com.wcdk.r2dbc.query.xml.DynamicSqlSource;
import com.wcdk.r2dbc.query.xml.ResultMapDefinition;
import com.wcdk.r2dbc.query.xml.RepositoryStatement;
import com.wcdk.r2dbc.query.xml.RepositoryXmlRegistry;
import io.r2dbc.spi.Row;

import org.aopalliance.intercept.MethodInvocation;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.ResolvableType;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import com.wcdk.r2dbc.dialect.DatabaseDialects;
import com.wcdk.r2dbc.dialect.DatabaseDialect;
import org.springframework.data.repository.query.Param;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;
import com.wcdk.r2dbc.datasource.DynamicRoutingConnectionFactory;
import com.wcdk.r2dbc.datasource.R2dbcDataSourceContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/***
 * Repository 查询 SQL 构建器。
 * @author wcdk
 */
final class RepositoryQuerySqlBuilder {
    final WcdkR2dbcProperties properties;
    final RepositoryMetadata metadata;
    final DatabaseDialect dialect;
    final SqlExecutionEngine sqlExecutionEngine;
    RepositoryQuerySqlBuilder(WcdkR2dbcProperties properties, RepositoryMetadata metadata, DatabaseDialect dialect, SqlExecutionEngine sqlExecutionEngine) {
        this.properties = properties;
        this.metadata = metadata;
        this.dialect = dialect;
        this.sqlExecutionEngine = sqlExecutionEngine;
    }
    SqlWhere buildWhere(QueryWrapper<?> queryWrapper) {
        QueryWrapper<?> wrapper = queryWrapper == null ? new QueryWrapper<>() : queryWrapper;
        RenderedPredicate rendered = new SqlExpressionRenderer(metadata).render(wrapper.expression());
        String sql = rendered.sql();
        Map<String, Object> parameters = new LinkedHashMap<>(rendered.bindings());
        if (metadata.logicDeleteColumn() != null && !sql.contains(metadata.logicDeleteColumn().name())) {
            sql = sql.isBlank() ? " WHERE " : sql + " AND ";
            sql += metadata.logicDeleteColumn().name() + " = :logicNotDeleteValue";
            parameters.put("logicNotDeleteValue", LogicDeleteValueConverter.convert(
                    properties.getLogicNotDeleteValue(), metadata.logicDeleteColumn().field().getType()));
        }
        return new SqlWhere(sql, parameters);
    }

    List<?> iterableValues(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof Iterable<?> iterable)) {
            throw new IllegalArgumentException("IN 条件值必须是集合");
        }
        List<Object> values = new ArrayList<>();
        iterable.forEach(values::add);
        return values;
    }

    String orderBySql(QueryWrapper<?> queryWrapper) {
        if (queryWrapper == null || queryWrapper.orderByList().isEmpty()) {
            return "";
        }
        return queryWrapper.orderByList().stream()
                .map(orderBy -> metadata.columnByName(orderBy.column()).name() + (orderBy.asc() ? " ASC" : " DESC"))
                .collect(Collectors.joining(", ", " ORDER BY ", ""));
    }

    String limitSql(QueryWrapper<?> queryWrapper, ContextView dialectContext) {
        if (queryWrapper == null || queryWrapper.limit() == null) {
            return "";
        }
        Long offset = queryWrapper.offset() == null ? null : queryWrapper.offset().longValue();
        return paginationSql(queryWrapper.limit(), offset, dialectContext);
    }

    String pageSql(Pageable pageable, ContextView dialectContext) {
        if (pageable.isUnpaged()) {
            return "";
        }
        return paginationSql(pageable.getPageSize(), pageable.getOffset(), dialectContext);
    }

    String paginationSql(long limit, Long offset, ContextView context) {
        return DialectPagination.render(resolveDialect(context), limit, offset);
    }

    DatabaseDialect resolveDialect(ContextView context) {
        String dataSource = R2dbcDataSourceContext.get(context);
        if (sqlExecutionEngine.connectionFactory() instanceof DynamicRoutingConnectionFactory routing) {
            return DatabaseDialects.get(routing.getConnectionFactory(dataSource));
        }
        return dialect;
    }

    String logicNotDeleteSql(String prefix) {
        FieldColumn logicDeleteColumn = metadata.logicDeleteColumn();
        return logicDeleteColumn == null ? "" : prefix + logicDeleteColumn.name() + " = :logicNotDeleteValue";
    }

    QueryWrapper<?> queryWrapper(Object[] arguments) {
        return arguments == null || arguments.length == 0 ? new QueryWrapper<>() : (QueryWrapper<?>) arguments[0];
    }

    QueryWrapper<?> queryWrapper(Object[] arguments, int index) {
        return arguments == null || arguments.length <= index ? new QueryWrapper<>() : (QueryWrapper<?>) arguments[index];
    }

    Pageable pageable(Object[] arguments) {
        return arguments == null || arguments.length == 0 ? Pageable.unpaged() : (Pageable) arguments[0];
    }

    String selectColumns() {
        return metadata.columns().stream().map(FieldColumn::name).collect(Collectors.joining(", "));
    }


    record SqlWhere(String sql, Map<String, Object> parameters) {}

}
