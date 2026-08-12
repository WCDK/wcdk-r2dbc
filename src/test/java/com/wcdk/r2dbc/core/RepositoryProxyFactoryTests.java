package com.wcdk.r2dbc.core;

import com.wcdk.r2dbc.BaseRepository;
import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.core.xml.RepositoryXmlRegistry;
import org.junit.jupiter.api.Test;

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
                mock(RepositoryOperations.class), properties(), mock(RepositoryXmlRegistry.class));
        Method resolver = RepositoryProxyFactory.class.getDeclaredMethod("resolveEntityClass", Class.class);
        resolver.setAccessible(true);

        assertThat(resolver.invoke(factory, UserRepository.class)).isEqualTo(User.class);
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
