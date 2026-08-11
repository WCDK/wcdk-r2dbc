package com.wcdk.r2dbc.core.xml;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicSqlSourceTests {

    @Test
    void rendersIfAndWhereFromParameters() throws Exception {
        DynamicSqlSource source = parse("""
                <select>
                  SELECT * FROM sys_user
                  <where>
                    <if test="name != null"> AND user_name = #{name}</if>
                    <if test="status != null"> AND status = #{status}</if>
                  </where>
                </select>
                """);

        DynamicSqlSource.RenderedSql rendered = source.render(mapWithNull("name", null, "status", 1));

        assertThat(rendered.sql()).contains("WHERE status = #{status}");
        assertThat(rendered.sql()).doesNotContain("user_name");
        assertThat(rendered.additionalParameters()).isEmpty();
    }

    @Test
    void omitsWhereWhenNoConditionMatches() throws Exception {
        DynamicSqlSource source = parse("""
                <select>SELECT * FROM sys_user<where><if test="name != null">AND name = #{name}</if></where></select>
                """);

        assertThat(source.render(mapWithNull("name", null)).sql()).isEqualTo("SELECT * FROM sys_user");
    }

    @Test
    void expandsForeachWithUniqueParameters() throws Exception {
        DynamicSqlSource source = parse("""
                <delete>
                  DELETE FROM sys_user WHERE id IN
                  <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
                </delete>
                """);

        DynamicSqlSource.RenderedSql rendered = source.render(Map.of("ids", List.of(10L, 20L, 30L)));

        assertThat(rendered.sql()).contains("(#{__foreach_0},#{__foreach_1},#{__foreach_2})");
        assertThat(rendered.additionalParameters()).containsExactly(
                Map.entry("__foreach_0", 10L),
                Map.entry("__foreach_1", 20L),
                Map.entry("__foreach_2", 30L));
    }

    @Test
    void expandsNestedForeachProperties() throws Exception {
        DynamicSqlSource source = parse("""
                <insert>
                  INSERT INTO sys_user(id, name) VALUES
                  <foreach collection="users" item="user" separator=",">(#{user.id}, #{user.name})</foreach>
                </insert>
                """);

        DynamicSqlSource.RenderedSql rendered = source.render(Map.of(
                "users", List.of(new User(1L, "A"), new User(2L, "B"))));

        assertThat(rendered.additionalParameters().values()).containsExactly(1L, "A", 2L, "B");
    }

    @Test
    void rejectsLiteralSubstitutionByDefault() {
        assertThatThrownBy(() -> parse("<select>SELECT * FROM ${table}</select>"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not allow literal");
    }

    private static DynamicSqlSource parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        return DynamicSqlSource.parse(document.getDocumentElement());
    }

    private static Map<String, Object> mapWithNull(Object... entries) {
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            result.put((String) entries[i], entries[i + 1]);
        }
        return result;
    }

    private record User(Long id, String name) {
    }
}
