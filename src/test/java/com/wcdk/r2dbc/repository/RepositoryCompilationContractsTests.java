package com.wcdk.r2dbc.repository;

import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.datasource.DynamicRoutingConnectionFactory;
import com.wcdk.r2dbc.execution.RepositoryOperations;
import com.wcdk.r2dbc.query.xml.RepositoryXmlRegistry;
import com.wcdk.r2dbc.repository.metadata.RepositoryMetadata;
import com.wcdk.r2dbc.repository.plan.RepositoryMethodPlan;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import java.lang.reflect.Method;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RepositoryCompilationContractsTests {
    private static final List<Class<?>> REPOSITORIES = List.of(First.class, Second.class, Third.class);
    @Test void compilesEachRepositoryOncePerDatasourceIncludingPrimary() {
        var single = factory(false); REPOSITORIES.forEach(single::create);
        assertThat(single.counts).containsExactlyEntriesOf(Map.of("POSTGRESQL", 3));
        var routed = factory(true); REPOSITORIES.forEach(routed::create);
        assertThat(routed.counts).containsExactlyInAnyOrderEntriesOf(Map.of("POSTGRESQL", 3, "MYSQL", 3, "DM", 3));
    }
    @Test void recordsMultipleRepositoryStartupBenchmark() {
        for (boolean routed : List.of(false, true)) {
            for (int warmup = 0; warmup < 5; warmup++) REPOSITORIES.forEach(factory(routed)::create);
            long[] samples = new long[10];
            for (int i = 0; i < samples.length; i++) {
                var factory = factory(routed); long start = System.nanoTime();
                REPOSITORIES.forEach(factory::create); samples[i] = System.nanoTime() - start;
            }
            Arrays.sort(samples);
            System.out.printf(java.util.Locale.ROOT, "Repository startup benchmark: repositories=3, datasources=%d, samples=10, median_ms=%.3f, max_ms=%.3f%n",
                    routed ? 3 : 1, samples[5] / 1_000_000.0, samples[9] / 1_000_000.0);
        }
    }
    private CountingFactory factory(boolean routed) {
        ConnectionFactory primary = connectionFactory("PostgreSQL");
        ConnectionFactory selected = routed ? new DynamicRoutingConnectionFactory("primary", new LinkedHashMap<>(Map.of(
                "primary", primary, "report", connectionFactory("MySQL"), "dm", connectionFactory("DM")))) : primary;
        var client = mock(DatabaseClient.class); when(client.getConnectionFactory()).thenReturn(selected);
        var operations = mock(RepositoryOperations.class); when(operations.databaseClient()).thenReturn(client);
        return new CountingFactory(operations, new WcdkR2dbcProperties(), mock(RepositoryXmlRegistry.class));
    }
    private ConnectionFactory connectionFactory(String name) {
        var factory = mock(ConnectionFactory.class); when(factory.getMetadata()).thenReturn(() -> name); return factory;
    }
    static class CountingFactory extends RepositoryProxyFactory {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        CountingFactory(RepositoryOperations operations, WcdkR2dbcProperties properties, RepositoryXmlRegistry registry) { super(operations, properties, registry); }
        @Override Map<Method, RepositoryMethodPlan> compilePlans(Class<?> repository, RepositoryMetadata metadata) {
            counts.merge(metadata.dialect().databaseType().name(), 1, Integer::sum);
            return super.compilePlans(repository, metadata);
        }
    }
    interface First extends BaseRepository<User> { Mono<User> findByName(String name); }
    interface Second extends BaseRepository<User> { Mono<User> findByName(String name); }
    interface Third extends BaseRepository<User> { Mono<User> findByName(String name); }
    static class User { Long id; String name; }
}
