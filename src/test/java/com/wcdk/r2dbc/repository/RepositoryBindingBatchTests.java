package com.wcdk.r2dbc.repository;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class RepositoryBindingBatchTests {
    static class Parent { Long parentId = 9L; }
    static class Child extends Parent { String name = "child"; }
    interface Statements {
        void bean(Child value);
        void scalar(String value);
    }

    @Test void inheritedFieldsBindAsValuesAndMissingBeanPropertiesNeverBindWholeEntity() throws Exception {
        var binder = new RepositoryParameterBinder();
        var method = Statements.class.getMethod("bean", Child.class);
        var arguments = new Object[]{new Child()};
        var source = binder.methodParameters(method, arguments);
        var sql = binder.bindSql("SELECT #{parentId}", method, arguments, source);
        assertThat(sql.parameters()).containsEntry("parentId", 9L);
        assertThatThrownBy(() -> binder.bindSql("SELECT #{missing}", method, arguments, source))
                .hasMessageContaining("missing");
        var scalar = Statements.class.getMethod("scalar", String.class);
        assertThat(binder.bindSql("SELECT #{renamed}", scalar, new Object[]{"value"}, Map.of()).parameters())
                .containsEntry("renamed", "value");
    }

    @Test void dollarPlaceholderIsReplacedLiterallyThenDiagnosedByBinder() throws Exception {
        var binder = new RepositoryParameterBinder();
        var method = Statements.class.getMethod("scalar", String.class);
        var sql = binder.bindSql("SELECT #{user$name}", method, new Object[]{"value"}, Map.of("user$name", "value"));
        assertThat(sql.sql()).isEqualTo("SELECT :user$name");
        assertThatThrownBy(() -> new com.wcdk.r2dbc.execution.ParameterBinder().bind(
                org.mockito.Mockito.mock(org.springframework.r2dbc.core.DatabaseClient.class), sql.sql(), sql.parameters()))
                .hasMessageContaining("user$name").hasMessageContaining("无效的SQL参数名");
    }

    enum Name { ACTIVE; @Override public String toString() { return "not-the-name"; } }
    static class Entity { @org.springframework.data.annotation.Id Long id; String name; }
    interface Derived { reactor.core.publisher.Mono<Entity> findByName(Name name); }

    @Test void derivedEnumConditionsUseNameEvenWhenToStringIsOverridden() throws Exception {
        var resolver = new CustomMethodResolver(new com.wcdk.r2dbc.repository.metadata.RepositoryMetadata(
                Entity.class, new com.wcdk.r2dbc.config.WcdkR2dbcProperties()), 1, 0);
        var parsed = resolver.resolve(Derived.class.getMethod("findByName", Name.class), new Object[]{Name.ACTIVE});
        assertThat(parsed.parameters()).containsEntry("p0", "ACTIVE");
    }

    @Test void booleanLogicalDeleteAcceptsDefaultsAndRejectsUnknownStrings() {
        assertThat(LogicDeleteValueConverter.convert(1, boolean.class)).isEqualTo(true);
        assertThat(LogicDeleteValueConverter.convert(0, Boolean.class)).isEqualTo(false);
        assertThat(LogicDeleteValueConverter.convert(-2, Boolean.class)).isEqualTo(true);
        assertThat(LogicDeleteValueConverter.convert(0.1, Boolean.class)).isEqualTo(true);
        for (String value : new String[]{"1", "true", "TRUE"})
            assertThat(LogicDeleteValueConverter.convert(value, Boolean.class)).isEqualTo(true);
        for (String value : new String[]{"0", "false", "FALSE"})
            assertThat(LogicDeleteValueConverter.convert(value, Boolean.class)).isEqualTo(false);
        assertThatThrownBy(() -> LogicDeleteValueConverter.convert("wrong", Boolean.class))
                .hasMessageContaining("logic-delete-value").hasMessageContaining("wrong");
    }
}
