package com.wcdk.r2dbc.core.executor;

import io.r2dbc.spi.ColumnMetadata;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

class R2dbcRowMapperTests {

    private final R2dbcRowMapper mapper = new R2dbcRowMapper();

    @Test
    void mapsRecordEnumTimeAndSnakeCaseColumns() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 9, 12, 30);
        Row row = row(List.of("id", "user_name", "status", "created_at"),
                List.of(7L, "alice", "ACTIVE", createdAt));

        UserRecord result = mapper.map(row, UserRecord.class);

        assertThat(result).isEqualTo(new UserRecord(7L, "alice", Status.ACTIVE, createdAt));
    }

    @Test
    void mapsInheritedFieldsAndSingleConstructor() {
        Row inheritedRow = row(List.of("id", "display_name"), List.of(9L, "admin"));
        ChildEntity inherited = mapper.map(inheritedRow, ChildEntity.class);
        assertThat(inherited.id).isEqualTo(9L);
        assertThat(inherited.displayName).isEqualTo("admin");

        Row constructorRow = row(List.of("user_name", "age"), List.of("bob", 20L));
        ConstructorEntity constructor = mapper.map(constructorRow, ConstructorEntity.class);
        assertThat(constructor.userName).isEqualTo("bob");
        assertThat(constructor.age).isEqualTo(20);
    }

    private Row row(List<String> names, List<Object> values) {
        Row row = mock(Row.class);
        RowMetadata metadata = mock(RowMetadata.class);
        List<ColumnMetadata> columns = names.stream().map(name -> {
            ColumnMetadata column = mock(ColumnMetadata.class);
            when(column.getName()).thenReturn(name);
            return column;
        }).toList();
        doReturn(columns).when(metadata).getColumnMetadatas();
        when(row.getMetadata()).thenReturn(metadata);
        for (int i = 0; i < names.size(); i++) {
            when(row.get(names.get(i))).thenReturn(values.get(i));
        }
        return row;
    }

    private enum Status {
        ACTIVE
    }

    private record UserRecord(Long id, String userName, Status status, LocalDateTime createdAt) {
    }

    private static class ParentEntity {
        protected Long id;
    }

    private static class ChildEntity extends ParentEntity {
        private String displayName;
    }

    private static class ConstructorEntity {
        private final String userName;
        private final int age;

        private ConstructorEntity(String userName, int age) {
            this.userName = userName;
            this.age = age;
        }
    }
}
