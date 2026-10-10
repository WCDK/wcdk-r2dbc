package com.wcdk.r2dbc.query.xml;

import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.Map;

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
                .hasStackTraceContaining("internal subsets are forbidden");
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
    void acceptsUnescapedSqlComparisonOperators() throws Exception {
        String xml = """
                <repository namespace="%s">
                  <select id="find">SELECT 1 WHERE A &lt;= 2 AND B &lt;&gt; 3</select>
                  <select id="findPlain">SELECT 1 WHERE A <= 2 AND B <> 3</select>
                </repository>
                """.formatted(TestRepository.class.getName());

        assertThat(registry(xml).find(TestRepository.class, "findPlain")).isPresent();
    }

    @Test
    void preservesComparisonOperatorsInsideCdata() throws Exception {
        String xml = """
                <repository namespace="%s">
                  <update id="acquire"><![CDATA[
                    UPDATE lock_table SET lock_until=:until
                    WHERE lock_until < :now
                  ]]></update>
                </repository>
                """.formatted(TestRepository.class.getName());

        String sql = registry(xml).find(TestRepository.class, "acquire")
                .orElseThrow().render(Map.of()).sql();
        assertThat(sql).contains("lock_until < #{now}").doesNotContain("&lt;");
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


    @Test
    void rejectsExternalAndInvalidDtdsWithoutLoadingThem() {
        for (String uri : java.util.List.of("file:///etc/wcdk-r2dbc-repository.dtd",
                "http://127.0.0.1:9/wcdk-r2dbc-repository.dtd", "unknown.dtd")) {
            String xml = "<!DOCTYPE repository SYSTEM \"%s\"><repository namespace=\"%s\"><select id=\"find\">SELECT 1</select></repository>"
                    .formatted(uri, TestRepository.class.getName());
            assertThatThrownBy(() -> registry(xml)).hasStackTraceContaining("DTD");
        }
        String invalid = "<!DOCTYPE repository SYSTEM \"wcdk-r2dbc-repository.dtd\"><repository namespace=\"%s\"><select>SELECT 1</select></repository>"
                .formatted(TestRepository.class.getName());
        assertThatThrownBy(() -> registry(invalid)).hasStackTraceContaining("id");
    }

    @Test
    void failsClosedWhenParserDoesNotSupportSecurityFeatures() throws Exception {
        var factory = mock(javax.xml.parsers.DocumentBuilderFactory.class);
        var cause = new javax.xml.parsers.ParserConfigurationException("unsupported feature");
        org.mockito.Mockito.doThrow(cause).when(factory)
                .setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
        assertThatThrownBy(() -> RepositoryXmlRegistry.configureSecureFactory(factory, false))
                .hasMessageContaining("security features").hasCause(cause);
    }


    @Test
    void acceptsExplicitClasspathDtdAndNestedReferences() throws Exception {
        String xml = """
                <!DOCTYPE repository SYSTEM "classpath:/dtd/wcdk-r2dbc-repository.dtd">
                <repository namespace="%s">
                  <resultMap id="outer" type="java.lang.Object"><association property="address" resultMap="inner"/></resultMap>
                  <resultMap id="inner" type="java.lang.Object"><result column="city" property="city"/></resultMap>
                  <select id="find" resultMap="outer">SELECT 1</select>
                </repository>
                """.formatted(TestRepository.class.getName());
        String namespace = TestRepository.class.getName();
        assertThat(registry(xml).findResultMap(namespace + ".outer").orElseThrow().associationMappings())
                .containsEntry("address", namespace + ".inner");
        assertThatThrownBy(() -> registry(xml.replace("resultMap=\"inner\"", "resultMap=\"outer\"")))
                .hasStackTraceContaining("循环引用");
    }

    @Test
    void rejectsFileAndNetworkEntitiesInInternalSubsets() {
        for (String uri : java.util.List.of("file:///etc/passwd", "http://127.0.0.1:9/secret")) {
            String xml = """
                    <!DOCTYPE repository [<!ENTITY xxe SYSTEM "%s">]>
                    <repository namespace="%s"><select id="find">SELECT 1 &xxe;</select></repository>
                    """.formatted(uri, TestRepository.class.getName());
            assertThatThrownBy(() -> registry(xml)).hasStackTraceContaining("internal subsets are forbidden");
        }
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
