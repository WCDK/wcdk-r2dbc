package com.wcdk.r2dbc.execution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证统一命名参数 词法分析状态。
 * @author wcdk
 */
class ParameterParserTests {

    /**
     * 验证字符串、注释、PostgreSQL 美元符号引号 和 Oracle 替代引号 均不会产生参数。
     * @author wcdk
     */
    @Test
    void ignoresAllSupportedQuotedAndCommentStates() {
        String sql = "SELECT ':text', \" :double \" , `:backtick`, $$:dollar$$, q'[ :oracle ]' "
                + "-- :line\n/* :block */ WHERE id = :id";

        assertThat(NamedParameterParser.parse(sql)).containsExactly("id");
    }
}