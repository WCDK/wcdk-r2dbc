package com.wcdk.r2dbc.query.xml;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class XmlBoundaryBatchTests {
    @Test void expressionDelimiterIgnoresBraceInsideStringLiteral() {
        var rendered = DynamicSqlSource.staticSql("SELECT #{'}'}").render(Map.of());
        assertThat(rendered.additionalParameters()).containsValue("}");
        assertThat(rendered.sql()).isEqualTo("SELECT #{__foreach_0}");
    }
    @Test void nullForeachCollectionHasParameterNameAndEmptyCollectionStillSkips() throws Exception {
        var source = source("<select>SELECT id FROM t <foreach collection='ids' item='item'>#{item}</foreach></select>");
        var parameters = new HashMap<String,Object>(); parameters.put("ids", null);
        assertThatThrownBy(() -> source.render(parameters)).hasMessageContaining("ids").hasMessageContaining("null");
        assertThat(source.render(Map.of("ids", List.of())).sql()).isEqualTo("SELECT id FROM t");
    }
    @Test void localVariablesCannotBeInlinedAsSqlFunctions() throws Exception {
        var source = source("<select><foreach collection='ids' item='item'>#{upper(item)}</foreach></select>");
        assertThatThrownBy(() -> source.render(Map.of("ids", List.of("x"))))
                .hasMessageContaining("foreach").hasMessageContaining("item");
    }
    @Test void dynamicReadsAreTrackedForAliasAwareUnusedValidation() throws Exception {
        var source = source("<select><if test='flag != null'>SELECT #{flag}</if><foreach collection='ids' item='item'>#{item}</foreach></select>");
        assertThat(source.render(Map.of("flag",1,"ids",List.of(2))).usedParameters()).containsExactlyInAnyOrder("flag","ids");
    }
    private DynamicSqlSource source(String xml) throws Exception {
        var builder = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder();
        return DynamicSqlSource.parse(builder.parse(new org.xml.sax.InputSource(new java.io.StringReader(xml))).getDocumentElement());
    }
}
