package com.wcdk.r2dbc.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WcdkSpringR2dbcPropertiesTests {

    @Test
    void acceptsValidPoolConfiguration() {
        WcdkSpringR2dbcProperties.Pool pool = new WcdkSpringR2dbcProperties.Pool();
        pool.setInitialSize(2);
        pool.setMaxSize(4);

        assertThatCode(() -> pool.validate("master")).doesNotThrowAnyException();
    }

    @Test
    void rejectsInitialSizeGreaterThanMaxSize() {
        WcdkSpringR2dbcProperties.Pool pool = new WcdkSpringR2dbcProperties.Pool();
        pool.setInitialSize(5);
        pool.setMaxSize(4);

        assertThatThrownBy(() -> pool.validate("master"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("master")
                .hasMessageContaining("initial-size");
    }

    @Test
    void rejectsNegativeDurationsAndRetryCounts() {
        WcdkSpringR2dbcProperties.Pool pool = new WcdkSpringR2dbcProperties.Pool();
        pool.setMaxAcquireTime(Duration.ofSeconds(-1));

        assertThatThrownBy(() -> pool.validate("reporting"))
                .hasMessageContaining("max-acquire-time");

        pool.setMaxAcquireTime(Duration.ZERO);
        pool.setAcquireRetry(-1);
        assertThatThrownBy(() -> pool.validate("reporting"))
                .hasMessageContaining("acquire-retry");
    }
}
