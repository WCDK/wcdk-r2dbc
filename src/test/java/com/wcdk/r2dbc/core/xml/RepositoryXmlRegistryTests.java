package com.wcdk.r2dbc.core.xml;

import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RepositoryXmlRegistryTests {

    @Test
    void allowsBundledDtdAndDoesNotExpandExternalEntities() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE repository SYSTEM "wcdk-r2dbc-repository.dtd">
                <repository namespace="%s">
                  <select id="find">SELECT 1</select>
                </repository>
                """.formatted(TestRepository.class.getName());

        assertThat(registry(xml).find(TestRepository.class, "find")).isPresent();

        String externalEntity = """
                <!DOCTYPE repository [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <repository namespace="%s">
                  <select id="find">&xxe;</select>
                </repository>
                """.formatted(TestRepository.class.getName());

        assertThatThrownBy(() -> registry(externalEntity))
                .isInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("SQL 不能为空");
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
                .hasStackTraceContaining("未知的R2DBC XML元素")
                .hasStackTraceContaining(TestRepository.class.getName())
                .hasStackTraceContaining("test-mapper.xml");

        String missingId = """
                <repository namespace="%s"><select>SELECT 1</select></repository>
                """.formatted(TestRepository.class.getName());
        assertThatThrownBy(() -> registry(missingId))
                .hasStackTraceContaining("缺少 id")
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
