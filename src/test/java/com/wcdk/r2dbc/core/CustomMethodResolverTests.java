package com.wcdk.r2dbc.core;

import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.core.metadata.RepositoryMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.Id;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomMethodResolverTests {

    private CustomMethodResolver resolver;

    @BeforeEach
    void setUp() {
        WcdkR2dbcProperties properties = new WcdkR2dbcProperties();
        resolver = new CustomMethodResolver(new RepositoryMetadata(User.class, properties), 1, 0);
    }

    @Test
    void rendersComparisonNotInOrderingAndStableSafeParameters() throws Exception {
        Method method = Methods.class.getMethod("findByAgeGreaterThanEqualAndNameNotInOrderByIdDesc",
                Integer.class, List.class);

        CustomMethodResolver.ParsedMethod parsed = resolver.resolve(method,
                new Object[]{18, List.of("blocked", "deleted")});

        assertThat(parsed.sql())
                .contains((char) 34 + "age" + (char) 34 + " >= :p0")
                .contains((char) 34 + "name" + (char) 34 + " NOT IN (:p1_in_0, :p1_in_1)")
                .contains("ORDER BY " + (char) 34 + "id" + (char) 34 + " DESC")
                .contains((char) 34 + "del_flg" + (char) 34 + " = :logicNotDeleteValue");
        assertThat(parsed.parameters()).containsExactly(
                java.util.Map.entry("p0", 18),
                java.util.Map.entry("p1_in_0", "blocked"),
                java.util.Map.entry("p1_in_1", "deleted"),
                java.util.Map.entry("logicNotDeleteValue", 0));
    }

    @Test
    void convertsNullEqualityAndEmptyCollectionsToExplicitPredicates() throws Exception {
        CustomMethodResolver.ParsedMethod nullParsed = resolver.resolve(
                Methods.class.getMethod("findByName", String.class), new Object[]{null});
        CustomMethodResolver.ParsedMethod emptyParsed = resolver.resolve(
                Methods.class.getMethod("findByNameNotIn", List.class), new Object[]{List.of()});

        assertThat(nullParsed.sql()).contains((char) 34 + "name" + (char) 34 + " IS NULL")
                .doesNotContain(":p0");
        assertThat(emptyParsed.sql()).contains("1 = 1");
    }

    @Test
    void rejectsNonReactiveDerivedReturnTypeAtStartup() throws Exception {
        assertThatThrownBy(() -> resolver.validateMethod(
                Methods.class.getMethod("countByName", String.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的返回类型");
    }

    interface Methods {
        Flux<User> findByAgeGreaterThanEqualAndNameNotInOrderByIdDesc(Integer age, List<String> names);

        Mono<User> findByName(String name);

        Mono<User> findByNameNotIn(List<String> names);

        long countByName(String name);
    }

    static class User {
        @Id
        private Long id;
        private Integer age;
        private String name;
        private Integer delFlg;
    }
}
