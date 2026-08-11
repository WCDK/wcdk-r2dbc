package com.wcdk.r2dbc.core.xml;

import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RepositoryXmlRegistryTests {

    @Test
    void rejectsDoctypeAndExternalEntities() throws Exception {
        String xml = """
                <!DOCTYPE repository [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <repository namespace="%s">
                  <select id="find">&xxe;</select>
                </repository>
                """.formatted(TestRepository.class.getName());

        assertThatThrownBy(() -> registry(xml))
                .isInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("DOCTYPE");
    }

    @Test
    void validatesMissingAndCircularResultMapsAtStartup() throws Exception {
        String missing = """
                <repository namespace="%s">
                  <select id="find" resultMap="missing">SELECT 1</select>
                </repository>
                """.formatted(TestRepository.class.getName());
        assertThatThrownBy(() -> registry(missing))
                .hasStackTraceContaining("resultMap");

        String circular = """
                <repository namespace="%s">
                  <resultMap id="first" type="java.lang.Object">
                    <discriminator column="kind"><case value="1" resultMap="second"/></discriminator>
                  </resultMap>
                  <resultMap id="second" type="java.lang.Object">
                    <discriminator column="kind"><case value="2" resultMap="first"/></discriminator>
                  </resultMap>
                </repository>
                """.formatted(TestRepository.class.getName());
        assertThatThrownBy(() -> registry(circular))
                .hasStackTraceContaining("循环引用");
    }

    @Test
    void rejectsUnknownElementsAndStatementsWithoutIdsWithContext() {
        String unknown = """
                <repository namespace="%s"><unknown>SELECT 1</unknown></repository>
                """.formatted(TestRepository.class.getName());
        assertThatThrownBy(() -> registry(unknown))
                .hasStackTraceContaining("Unknown R2DBC XML element")
                .hasStackTraceContaining(TestRepository.class.getName())
                .hasStackTraceContaining("test-mapper.xml");

        String missingId = """
                <repository namespace="%s"><select>SELECT 1</select></repository>
                """.formatted(TestRepository.class.getName());
        assertThatThrownBy(() -> registry(missingId))
                .hasStackTraceContaining("missing id")
                .hasStackTraceContaining(TestRepository.class.getName());
    }

    private RepositoryXmlRegistry registry(String xml) throws Exception {
        ResourcePatternResolver resolver = mock(ResourcePatternResolver.class);
        Resource resource = new ByteArrayResource(xml.getBytes(StandardCharsets.UTF_8), "test-mapper.xml");
        when(resolver.getResources("memory:test")).thenReturn(new Resource[]{resource});
        WcdkR2dbcProperties properties = new WcdkR2dbcProperties();
        properties.setMapperLocations("memory:test");
        return new RepositoryXmlRegistry(resolver, properties);
    }

    interface TestRepository {
    }
}
