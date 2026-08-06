package com.wcdk.r2dbc.id;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SnowflakeIdGeneratorTests {

    @Test
    void defaultGeneratorProducesUniqueIds() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator();
        Set<Long> ids = new HashSet<>();

        for (int i = 0; i < 10_000; i++) {
            assertThat(ids.add(generator.nextId())).isTrue();
        }
    }

    @Test
    void workerIdParticipatesInGeneratedId() {
        long first = new SnowflakeIdGenerator(1).nextId();
        long second = new SnowflakeIdGenerator(2).nextId();

        assertThat(first).isNotEqualTo(second);
    }
}
