package com.wcdk.r2dbc.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import static org.assertj.core.api.Assertions.*;

class RepositoryScanningBatchTests {
    @Test void sameSimpleNamesFailStartupWithBothQualifiedNames() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(Collisions.class);
            assertThatThrownBy(context::refresh).hasMessageContaining("仓储 Bean 名冲突")
                    .hasMessageContaining("com.wcdk.r2dbc.fixtures.scanone.SameRepository")
                    .hasMessageContaining("com.wcdk.r2dbc.fixtures.scanother.SameRepository");
        }
    }
    @Test void explicitPackageAndClassScopesExcludeAutomaticPackages() {
        for (Class<?> config : new Class<?>[]{NarrowPackage.class, NarrowClass.class}) {
            try (var context = new AnnotationConfigApplicationContext()) {
                AutoConfigurationPackages.register(context, "com.wcdk.r2dbc.fixtures.scope.wide");
                context.register(config); context.refresh();
                assertThat(context.containsBeanDefinition("narrowRepository")).isTrue();
                assertThat(context.containsBeanDefinition("wideRepository")).isFalse();
            }
        }
    }
    @Test void explicitPropertyScopeAlsoExcludesAutomaticPackages() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("scope",
                    java.util.Map.of("wcdk.r2dbc.base-packages", "com.wcdk.r2dbc.fixtures.scope.narrow")));
            AutoConfigurationPackages.register(context, "com.wcdk.r2dbc.fixtures.scope.wide");
            context.register(DefaultScope.class); context.refresh();
            assertThat(context.containsBeanDefinition("narrowRepository")).isTrue();
            assertThat(context.containsBeanDefinition("wideRepository")).isFalse();
        }
    }
    @Test void overlappingScopesRegisterTheSameRepositoryOnce() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(Overlapping.class); context.refresh();
            assertThat(context.containsBeanDefinition("narrowRepository")).isTrue();
        }
    }
    @Configuration(proxyBeanMethods=false)
    @EnableWcdkR2dbcRepositories(basePackages={"com.wcdk.r2dbc.fixtures.scanone","com.wcdk.r2dbc.fixtures.scanother"})
    static class Collisions { }
    @Configuration(proxyBeanMethods=false)
    @EnableWcdkR2dbcRepositories(basePackages="com.wcdk.r2dbc.fixtures.scope.narrow")
    static class NarrowPackage { }
    @Configuration(proxyBeanMethods=false)
    @EnableWcdkR2dbcRepositories(basePackageClasses=com.wcdk.r2dbc.fixtures.scope.narrow.NarrowRepository.class)
    static class NarrowClass { }
    @Configuration(proxyBeanMethods=false)
    @EnableWcdkR2dbcRepositories
    static class DefaultScope { }
    @Configuration(proxyBeanMethods=false)
    @EnableWcdkR2dbcRepositories(basePackages={"com.wcdk.r2dbc.fixtures.scope","com.wcdk.r2dbc.fixtures.scope.narrow"})
    static class Overlapping { }
}
