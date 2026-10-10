package com.wcdk.r2dbc.execution;

import io.r2dbc.spi.ColumnMetadata;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;

import java.time.Instant;
import java.time.LocalDateTime;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

class R2dbcRowMapperTests {

    private final R2dbcRowMapper mapper = new R2dbcRowMapper();

    @Test
    void constructorParameterColumnOverridesFieldAndParameterName() {
        var result = mapper.map(row(List.of("explicit_name"), List.of("alice")), ParameterColumnEntity.class);
        assertThat(result.name).isEqualTo("alice");
        assertThat(mapper.map(row(List.of("explicit_name"), List.of("bob")), ParameterColumnRecord.class))
                .isEqualTo(new ParameterColumnRecord("bob"));
    }

    @Test
    void constructorsWithoutParameterMetadataRequireColumnAnnotations(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var source = directory.resolve("NoNames.java");
        java.nio.file.Files.writeString(source, """
            public class NoNames {
                public final String name;
                public NoNames(String name) { this.name = name; }
                public static class Annotated {
                    public final String name;
                    public Annotated(@com.wcdk.r2dbc.annotation.Column("explicit_name") String name) { this.name = name; }
                }
            }
            """);
        assertThat(javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-proc:none", "-classpath", System.getProperty("java.class.path"), "-d", directory.toString(), source.toString())).isZero();
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{directory.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> missing = loader.loadClass("NoNames");
            assertThat(missing.getConstructors()[0].getParameters()[0].isNamePresent()).isFalse();
            assertThatThrownBy(() -> mapper.map(row(List.of("name"), List.of("alice")), missing))
                    .hasMessageContaining("-parameters").hasMessageContaining("@Column");
            Class<?> annotated = loader.loadClass("NoNames$Annotated");
            Object entity = mapper.map(row(List.of("explicit_name"), List.of("alice")), annotated);
            assertThat(annotated.getField("name").get(entity)).isEqualTo("alice");
        }
    }

    static class ParameterColumnEntity {
        @org.springframework.data.relational.core.mapping.Column("field_name") final String name;
        ParameterColumnEntity(@com.wcdk.r2dbc.annotation.Column("explicit_name") String name) { this.name = name; }
    }
    record ParameterColumnRecord(String name) {
        ParameterColumnRecord(@com.wcdk.r2dbc.annotation.Column("explicit_name") String name) { this.name = name; }
    }


    @Test
    void mapsRecordEnumTimeAndSnakeCaseColumns() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 9, 12, 30);
        Row row = row(List.of("id", "user_name", "status", "created_at"),
                List.of(7L, "alice", "ACTIVE", createdAt));

        UserRecord result = mapper.map(row, UserRecord.class);
        mapper.map(row, UserRecord.class);

        assertThat(result).isEqualTo(new UserRecord(7L, "alice", Status.ACTIVE, createdAt));
        assertThat(mapper.cacheStats()).isEqualTo(new R2dbcRowMapper.CacheStats(1, 1, 1));
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

    @Test
    void rejectsMissingOrNullPrimitiveColumns() {
        assertThatThrownBy(() -> mapper.map(row(List.of("name"), List.of("alice")), PrimitiveEntity.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("基本类型")
                .hasMessageContaining("age");

        assertThatThrownBy(() -> mapper.map(row(List.of("age"), Arrays.asList((Object) null)), PrimitiveEntity.class))
                .isInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("SQL NULL")
                .hasStackTraceContaining("age");
    }

    @Test
    void usesPersistenceCreatorAndCustomConverter() {
        R2dbcValueConverter converter = new R2dbcValueConverter() {
            @Override
            public boolean supports(Class<?> sourceType, Class<?> targetType) {
                return sourceType == String.class && targetType == Token.class;
            }

            @Override
            public Object convert(Object source, Class<?> targetType) {
                return new Token(source.toString());
            }
        };
        CreatorEntity entity = new R2dbcRowMapper(List.of(converter))
                .map(row(List.of("token"), List.of("abc")), CreatorEntity.class);

        assertThat(entity.token.value()).isEqualTo("abc");
        assertThat(entity.createdBy).isEqualTo("persistence-creator");
    }

    @Test
    void mapsTransientFieldsReturnedByQueryWithoutRequiringThem() {
        Row row = row(List.of("id", "display_name", "display_label"),
                List.of(3L, "alice", "Alice (active)"));

        QueryEntity entity = mapper.map(row, QueryEntity.class);

        assertThat(entity.displayLabel).isEqualTo("Alice (active)");
    }

    @Test
    void mapsTransientFieldsAfterConstructorInvocation() {
        Row row = row(List.of("name", "display_label"), List.of("alice", "Alice (active)"));

        ConstructorQueryEntity entity = mapper.map(row, ConstructorQueryEntity.class);

        assertThat(entity.displayLabel).isEqualTo("Alice (active)");
    }

    @Test
    void convertsInstantToLegacyDateAndJavaTimeTypes() {
        Instant instant = Instant.parse("2026-08-11T04:05:06Z");

        assertThat(mapper.convertValue(instant, Date.class)).isEqualTo(Date.from(instant));
        assertThat(mapper.convertValue(instant, LocalDateTime.class))
                .isEqualTo(LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault()));
    }

    @Test
    void mapsByteBufferToByteArrayWithoutChangingBufferPosition() {
        ByteBuffer content = ByteBuffer.wrap(new byte[]{0, 1, 2, 3});
        content.position(1);
        content.limit(3);

        BinaryEntity entity = mapper.map(
                row(List.of("content_bytes"), List.of(content)), BinaryEntity.class);

        assertThat(entity.contentBytes).containsExactly(1, 2);
        assertThat(content.position()).isEqualTo(1);
        assertThat(content.limit()).isEqualTo(3);
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

    private static class PrimitiveEntity {
        private int age;
    }

    private record Token(String value) {
    }

    private static class CreatorEntity {
        private Token token;
        private String createdBy;

        private CreatorEntity() {
            this.createdBy = "default";
        }

        @PersistenceCreator
        private CreatorEntity(Token token) {
            this.token = token;
            this.createdBy = "persistence-creator";
        }
    }

    private static class QueryEntity {
        private Long id;
        private String displayName;
        @Transient
        private String displayLabel;
    }

    private static class ConstructorQueryEntity {
        private final String name;
        @Transient
        private String displayLabel;

        private ConstructorQueryEntity(String name) {
            this.name = name;
        }
    }

    private static class BinaryEntity {
        private byte[] contentBytes;
    }
}
