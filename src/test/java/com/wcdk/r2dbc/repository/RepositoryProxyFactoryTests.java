package com.wcdk.r2dbc.repository;

import com.wcdk.r2dbc.execution.RepositoryOperations;
import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.id.SnowflakeIdGenerator;
import com.wcdk.r2dbc.query.xml.RepositoryXmlRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/***
 * Repository 泛型解析测试。
 * @author wcdk
 */
class RepositoryProxyFactoryTests {

    @Test
    void shouldResolveEntityFromIndirectGenericRepository() throws Exception {
        RepositoryProxyFactory factory = new RepositoryProxyFactory(
                mock(RepositoryOperations.class), properties(), mock(RepositoryXmlRegistry.class),
                mock(SnowflakeIdGenerator.class));
        Method resolver = RepositoryProxyFactory.class.getDeclaredMethod("resolveEntityClass", Class.class);
        resolver.setAccessible(true);

        assertThat(resolver.invoke(factory, UserRepository.class)).isEqualTo(User.class);
    }

    @Test
    void shouldReuseProvidedSnowflakeIdGeneratorWhenEnabled() throws Exception {
        WcdkR2dbcProperties properties = properties();
        org.mockito.Mockito.when(properties.isSnowflakeId()).thenReturn(true);
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1);
        RepositoryProxyFactory factory = new RepositoryProxyFactory(
                mock(RepositoryOperations.class), properties, mock(RepositoryXmlRegistry.class), generator);

        Field field = RepositoryProxyFactory.class.getDeclaredField("snowflakeIdGenerator");
        field.setAccessible(true);

        assertThat(field.get(factory)).isSameAs(generator);
    }

    private WcdkR2dbcProperties properties() {
        WcdkR2dbcProperties properties = mock(WcdkR2dbcProperties.class);
        org.mockito.Mockito.when(properties.isSnowflakeId()).thenReturn(false);
        return properties;
    }

    interface CommonRepository<T> extends BaseRepository<T> {
    }

    interface UserRepository extends CommonRepository<User> {
    }

    static final class User {
    }
}
