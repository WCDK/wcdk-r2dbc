package com.wcdk.r2dbc.repository;

import com.wcdk.r2dbc.execution.ParameterBinder;
import com.wcdk.r2dbc.execution.SqlParameter;
import com.wcdk.r2dbc.query.xml.DynamicSqlSource;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Mono;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

/**
 * 验证 XML 占位符词法边界、路径别名及嵌套空值类型。
 * @author wcdk
 */
class XmlParameterRegressionTests {
    static class Address { String city = "Shanghai"; }
    static class User { Long id = 7L; Address address = new Address(); }
    interface Statements {
        Mono<String> scalar(@Param("value") String value);
        Mono<String> nested(@Param("user") User user);
        Mono<String> bean(User entity);
        Mono<Long> collision(@Param("user") User user, @Param("user_id") Long id);
    }

    @Test void literalsIdentifiersAndCommentsRemainUnchangedAcrossRenderingAndBinding() throws Exception {
        var binder = new RepositoryParameterBinder();
        var method = Statements.class.getMethod("scalar", String.class);
        Object[] arguments = {"real"};
        var source = binder.methodParameters(method, arguments);
        String sql = "SELECT '#{value}', \"#{value}\", `#{value}`, $$#{value} :value$$, "
                + "q'[#{value} :value]', #{value}\n-- #{missing}\n/* #{missing} */\n# #{missing}\n";
        var rendered = DynamicSqlSource.staticSql(sql).render(source);
        var bound = binder.bindSql(rendered.sql(), method, arguments, source);
        assertThat(bound.sql()).isEqualTo(sql.strip().replace(", #{value}\n", ", :value\n"));
        assertThat(bound.parameters()).containsOnlyKeys("value").containsEntry("value", "real");
        binder.validateUnused(method, arguments, rendered.usedParameters());
    }

    @Test void placeholdersOnlyInsideSyntaxDoNotCreateBindings() throws Exception {
        var binder = new RepositoryParameterBinder();
        var method = Statements.class.getMethod("scalar", String.class);
        String sql = "SELECT '#{missing}' /* #{missing} */ -- #{missing}";
        var bound = binder.bindSql(sql, method, new Object[]{"unused"}, Map.of());
        assertThat(bound.sql()).isEqualTo(sql);
        assertThat(bound.parameters()).isEmpty();
    }

    @Test void collidingPathsKeepIndependentValuesAndRepeatTheSameBinding() throws Exception {
        var binder = new RepositoryParameterBinder();
        var method = Statements.class.getMethod("collision", User.class, Long.class);
        Object[] arguments = {new User(), 99L};
        var bound = binder.bindSql("SELECT #{user.id}, #{user_id}, #{user.id}", method,
                arguments, binder.methodParameters(method, arguments));
        assertThat(bound.sql()).isEqualTo("SELECT :user_id_1, :user_id, :user_id_1");
        assertThat(bound.parameters()).containsExactlyInAnyOrderEntriesOf(Map.of("user_id_1", 7L, "user_id", 99L));
        var client = org.mockito.Mockito.mock(org.springframework.r2dbc.core.DatabaseClient.class,
                org.mockito.Mockito.RETURNS_DEEP_STUBS);
        assertThatCode(() -> new ParameterBinder().bind(client, bound.sql(), bound.parameters())).doesNotThrowAnyException();
    }

    @Test void multiLevelPathsResolveLeafTypeWhenIntermediateOrRootValuesAreNull() throws Exception {
        var binder = new RepositoryParameterBinder();
        var method = Statements.class.getMethod("nested", User.class);
        var user = new User();
        Object[] arguments = {user};
        var bound = binder.bindSql("SELECT #{user.address.city}", method, arguments,
                binder.methodParameters(method, arguments));
        assertThat(bound.parameters()).containsEntry("user_address_city", "Shanghai");
        user.address = null;
        for (Object root : new Object[]{user, null}) {
            Object[] nullable = {root};
            var result = binder.bindSql("SELECT #{user.address.city}", method, nullable,
                    binder.methodParameters(method, nullable));
            SqlParameter parameter = (SqlParameter) result.parameters().get("user_address_city");
            assertThat(parameter.value()).isNull();
            assertThat(parameter.javaType()).isEqualTo(String.class);
        }
        var bean = Statements.class.getMethod("bean", User.class);
        var result = binder.bindSql("SELECT #{address.city}", bean, arguments,
                binder.methodParameters(bean, arguments));
        assertThat(((SqlParameter) result.parameters().get("address_city")).javaType()).isEqualTo(String.class);
    }
}
