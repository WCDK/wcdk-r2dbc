package com.wcdk.r2dbc.repository;

import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.repository.metadata.RepositoryMetadata;
import com.wcdk.r2dbc.repository.plan.DerivedQueryPlanModel;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class DerivedQueryContractsTests {
    private final WcdkR2dbcProperties properties = new WcdkR2dbcProperties();
    private final RepositoryMetadata metadata = new RepositoryMetadata(User.class, properties);
    private final CustomMethodResolver resolver = new CustomMethodResolver(metadata, 1, 0);

    @Test void everySupportedSuffixSharesCompilationValidationAndBinding() {
        Map<String, Integer> expected = Map.ofEntries(
                Map.entry("", 1),
                Map.entry("IsNull", 0),
                Map.entry("IsNotNull", 0),
                Map.entry("GreaterThanEqual", 1),
                Map.entry("LessThanEqual", 1),
                Map.entry("GreaterThan", 1),
                Map.entry("LessThan", 1),
                Map.entry("NotIn", 1),
                Map.entry("In", 1),
                Map.entry("Like", 1),
                Map.entry("Between", 2),
                Map.entry("Not", 1));
        Map<String, String> sql = Map.ofEntries(Map.entry("", "="), Map.entry("IsNull", "IS NULL"),
                Map.entry("IsNotNull", "IS NOT NULL"), Map.entry("GreaterThanEqual", ">="), Map.entry("LessThanEqual", "<="),
                Map.entry("GreaterThan", ">"), Map.entry("LessThan", "<"), Map.entry("NotIn", "NOT IN"),
                Map.entry("In", "IN"), Map.entry("Like", "LIKE"), Map.entry("Between", "BETWEEN"), Map.entry("Not", "<>"));
        assertThat(DerivedOperator.values()).hasSize(expected.size());
        for (var method : Valid.class.getDeclaredMethods()) {
            String suffix = method.getName().substring("findByAge".length());
            var definition = resolver.compile(method);
            resolver.validateMethod(method);
            var condition = definition.conditions().getFirst();
            assertThat(condition.fieldName()).isEqualTo("age");
            assertThat(condition.operator()).isEqualTo(sql.get(suffix));
            assertThat(condition.argumentCount()).isEqualTo(expected.get(suffix));
            Object[] arguments = new Object[expected.get(suffix)]; Arrays.fill(arguments, 18);
            assertThat(resolver.resolve(definition, method, arguments).sql()).contains(condition.operator());
            assertThatThrownBy(() -> resolver.resolve(definition, method, new Object[arguments.length + 1]))
                    .hasMessageContaining("参数数量");
            if (arguments.length > 0) assertThatThrownBy(() -> resolver.resolve(definition, method, new Object[arguments.length - 1]))
                    .hasMessageContaining("参数数量");
        }
        var compiler = new DerivedMethodCompiler(InvalidCount.class, metadata, properties);
        for (var method : InvalidCount.class.getDeclaredMethods())
            assertThatThrownBy(() -> compiler.compile(method)).hasStackTraceContaining("参数数量");
        for (var method : UnknownField.class.getDeclaredMethods())
            assertThatThrownBy(() -> resolver.compile(method)).hasMessageContaining("实体字段不存在");
    }

    @Test void longestFieldMatchProtectsInternalAndOrAndUsesSameModel() throws Exception {
        for (String name : List.of("findByBrandAndCoordinator", "findByBrandAndModelAndCoordinator", "findByAndersonOrOrderCode", "findByCoOrdinatorAndBrand")) {
            var method = Arrays.stream(Combined.class.getDeclaredMethods()).filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
            var definition = resolver.compile(method);
            var model = DerivedQueryPlanModel.compile(method, definition, metadata);
            assertThat(model.conditions()).hasSize(2);
            assertThat(model.conditions().stream().map(c -> c.fieldName()).toList())
                    .isEqualTo(definition.conditions().stream().map(c -> c.fieldName()).toList());
            assertThat(resolver.resolveCompiled(model, new Object[]{"a", "b"}).parameters())
                    .containsKeys("p0", "p1");
        }
        var method = Combined.class.getMethod("findByNameOrBrand", String.class);
        var definition = resolver.compile(method);
        assertThat(definition.conditions()).hasSize(1);
        assertThat(definition.conditions().getFirst().fieldName()).isEqualTo("nameOrBrand");
        var ordered = resolver.compile(Combined.class.getMethod("findByBrandOrderByCoordinatorAscBrandAndModelDesc", String.class));
        var model = DerivedQueryPlanModel.compile(method, ordered, metadata);
        assertThat(model.orders()).hasSize(2);
        assertThat(resolver.resolve(ordered, method, new Object[]{"a"}).sql())
                .contains("ORDER BY \"coordinator\" ASC, \"brand_and_model\" DESC");
    }

    interface Combined {
        Mono<User> findByBrandAndCoordinator(String brand, String coordinator);
        Mono<User> findByBrandAndModelAndCoordinator(String brandAndModel, String coordinator);
        Mono<User> findByAndersonOrOrderCode(String anderson, String orderCode);
        Mono<User> findByCoOrdinatorAndBrand(String coOrdinator, String brand);
        Mono<User> findByNameOrBrand(String nameOrBrand);
        Mono<User> findByBrandOrderByCoordinatorAscBrandAndModelDesc(String brand);
    }
    interface Valid {
        Mono<User> findByAge(Integer p0);
        Mono<User> findByAgeIsNull();
        Mono<User> findByAgeIsNotNull();
        Mono<User> findByAgeGreaterThanEqual(Integer p0);
        Mono<User> findByAgeLessThanEqual(Integer p0);
        Mono<User> findByAgeGreaterThan(Integer p0);
        Mono<User> findByAgeLessThan(Integer p0);
        Mono<User> findByAgeNotIn(Integer p0);
        Mono<User> findByAgeIn(Integer p0);
        Mono<User> findByAgeLike(Integer p0);
        Mono<User> findByAgeBetween(Integer p0, Integer p1);
        Mono<User> findByAgeNot(Integer p0);
    }
    interface InvalidCount {
        Mono<User> findByAge(Integer p0, Integer p1);
        Mono<User> findByAgeIsNull(Integer p0);
        Mono<User> findByAgeIsNotNull(Integer p0);
        Mono<User> findByAgeGreaterThanEqual(Integer p0, Integer p1);
        Mono<User> findByAgeLessThanEqual(Integer p0, Integer p1);
        Mono<User> findByAgeGreaterThan(Integer p0, Integer p1);
        Mono<User> findByAgeLessThan(Integer p0, Integer p1);
        Mono<User> findByAgeNotIn(Integer p0, Integer p1);
        Mono<User> findByAgeIn(Integer p0, Integer p1);
        Mono<User> findByAgeLike(Integer p0, Integer p1);
        Mono<User> findByAgeBetween(Integer p0, Integer p1, Integer p2);
        Mono<User> findByAgeNot(Integer p0, Integer p1);
    }
    interface UnknownField {
        Mono<User> findByMissing(Integer p0);
        Mono<User> findByMissingIsNull();
        Mono<User> findByMissingIsNotNull();
        Mono<User> findByMissingGreaterThanEqual(Integer p0);
        Mono<User> findByMissingLessThanEqual(Integer p0);
        Mono<User> findByMissingGreaterThan(Integer p0);
        Mono<User> findByMissingLessThan(Integer p0);
        Mono<User> findByMissingNotIn(Integer p0);
        Mono<User> findByMissingIn(Integer p0);
        Mono<User> findByMissingLike(Integer p0);
        Mono<User> findByMissingBetween(Integer p0, Integer p1);
        Mono<User> findByMissingNot(Integer p0);
    }
    static class User {
        Long id; Integer age; String brand; String coordinator; String brandAndModel;
        String anderson; String orderCode; String coOrdinator; String name; String nameOrBrand;
    }
}
