package com.wcdk.r2dbc;

import org.junit.jupiter.api.Test;
import javax.tools.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 为 README 的全部 Java 片段提供必要的周边声明，并交给真实 Java 编译器校验。
 * @author wcdk
 */
class ReadmeExamplesTests {
    private static final String IMPORTS = """
        import com.wcdk.r2dbc.repository.BaseRepository;
        import com.wcdk.r2dbc.query.QueryWrapper;
        import com.wcdk.r2dbc.datasource.R2dbcDataSource;
        import com.wcdk.r2dbc.execution.*;
        import com.wcdk.r2dbc.execution.lifecycle.*;
        import com.wcdk.r2dbc.annotation.Repository;
        import org.springframework.data.annotation.Id;
        import org.springframework.data.annotation.Transient;
        import org.springframework.data.relational.core.mapping.Column;
        import org.springframework.data.relational.core.mapping.Table;
        import org.springframework.data.domain.*;
        import org.springframework.stereotype.Service;
        import org.springframework.context.annotation.Bean;
        import org.springframework.transaction.reactive.TransactionalOperator;
        import io.micrometer.observation.ObservationRegistry;
        import lombok.RequiredArgsConstructor;
        import reactor.core.publisher.*;
        """;
    private static final String USER = "record User(Long id, String userName, String email, Integer status) {}\n";
    private static final String REPOSITORY = """
        interface UserRepository extends BaseRepository<User> {
            Flux<User> findByStatus(Integer status);
            Mono<Long> updateStatusById(Integer status, Long id);
            Mono<Long> updateUserNameAndStatusById(String userName, Integer status, Long id);
            Mono<Long> deleteByStatus(Integer status);
        }
        """;

    @Test void documentedWcdkConfigurationKeysAndDefaultsMatchPublishedMetadata() throws Exception {
        var parser = new org.yaml.snakeyaml.Yaml();
        var metadata = new java.util.HashMap<String, java.util.Map<String,Object>>();
        for (String resource : java.util.List.of("META-INF/spring-configuration-metadata.json", "META-INF/additional-spring-configuration-metadata.json")) {
            try (var input = Files.newInputStream(Path.of("target/classes").resolve(resource))) {
                assertThat(input).isNotNull();
                java.util.Map<String,Object> document=parser.load(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).replace("\\/", "/"));
                for (Object item : (java.util.List<?>)document.get("properties")) {
                    @SuppressWarnings("unchecked") var property=(java.util.Map<String,Object>)item;
                    metadata.put((String)property.get("name"),property);
                }
            }
        }
        var rows=Pattern.compile("(?m)^[|] `([a-z0-9.-]+)` [|] ([^|]+)[|]").matcher(Files.readString(Path.of("README.md")));
        int count=0;
        while (rows.find()) {
            String key=rows.group(1); if (!key.startsWith("wcdk.r2dbc.")) continue;
            assertThat(metadata).as(key).containsKey(key);
            Object value=metadata.get(key).get("defaultValue");
            if (value!=null && !(value instanceof java.util.Collection<?>)) assertThat(rows.group(2).replace("`", "").trim()).as(key).isEqualTo(value.toString());
            count++;
        }
        assertThat(count).isEqualTo(23);
    }

    @Test void everyYamlBlockBindsToDeclaredConfiguration() throws Exception {
        String readme = Files.readString(Path.of("README.md"));
        Matcher matcher = Pattern.compile("```yaml\\R(.*?)```", Pattern.DOTALL).matcher(readme);
        int count = 0;
        while (matcher.find()) {
            var environment = new org.springframework.mock.env.MockEnvironment();
            var loader = new org.springframework.boot.env.YamlPropertySourceLoader();
            var resource = new org.springframework.core.io.ByteArrayResource(
                    matcher.group(1).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for (var source : loader.load("readme", resource)) environment.getPropertySources().addFirst(source);
            var binder = org.springframework.boot.context.properties.bind.Binder.get(environment);
            var handler = new org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler(
                    org.springframework.boot.context.properties.bind.BindHandler.DEFAULT);
            binder.bind("wcdk.r2dbc", org.springframework.boot.context.properties.bind.Bindable.of(
                    com.wcdk.r2dbc.config.WcdkR2dbcProperties.class), handler);
            binder.bind("spring.r2dbc", org.springframework.boot.context.properties.bind.Bindable.of(
                    com.wcdk.r2dbc.config.WcdkSpringR2dbcProperties.class), handler);
            count++;
        }
        assertThat(count).isEqualTo(7);
    }

    @Test void everyJavaBlockCompilesWithItsDocumentedContext() throws Exception {
        String readme = Files.readString(Path.of("README.md"));
        Matcher matcher = Pattern.compile("```java\\R(.*?)```", Pattern.DOTALL).matcher(readme);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).isNotNull();
        Path output = Files.createTempDirectory("readme-java-");
        int count = 0;
        try {
            while (matcher.find()) {
                count++;
                String code = matcher.group(1).replaceAll("(?m)^package .*?;\\s*", "");
                StringBuilder imports = new StringBuilder(IMPORTS);
                Matcher importMatcher = Pattern.compile("(?m)^import .*?;\\s*").matcher(code);
                while (importMatcher.find()) imports.append(importMatcher.group()).append('\n');
                code = importMatcher.replaceAll("");
                boolean hasUser = Pattern.compile("\\b(class|record)\\s+User\\b").matcher(code).find();
                boolean hasRepository = Pattern.compile("\\binterface\\s+UserRepository\\b").matcher(code).find();
                String context = (hasUser ? "" : USER) + (hasRepository ? "" : REPOSITORY);
                // 多个公开类型在文档中分别存文件；这里只移除公开修饰，不改变语句和方法签名。
                code = code.replaceAll("\\bpublic (?=(?:final )?(?:class|interface|record)\\b)", "");
                boolean declaration = Pattern.compile("\\b(class|interface|record)\\s+\\w+").matcher(code).find();
                if (!declaration) {
                    String fields = "UserRepository userRepository; TransactionalOperator transactionalOperator; "
                            + "BaseRepository<UserReport> reportRepository; Long id; User patch; "
                            + "static class UserReport {}\n";
                    boolean method = code.contains("public Mono<User> create") || code.contains("public Flux<UserReport>")
                            || code.contains("SqlExecutionObserver sqlExecutionObserver");
                    boolean interfaceMethod = code.contains("findActiveByEmail");
                    code = interfaceMethod ? "interface Snippet { " + code + " }"
                            : "class Snippet { " + fields + (method ? code : "void example() { " + code + " }") + " }";
                }
                String source = imports + context + code;
                Path file = output.resolve("Snippet" + count + ".java");
                Files.writeString(file, source);
                DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
                try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, null)) {
                    List<String> options = new ArrayList<>(List.of("--release", "21", "-parameters", "-classpath",
                            System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                            "-d", output.toString()));
                    if (code.contains("@RequiredArgsConstructor")) {
                        options.addAll(List.of("-processor", "lombok.launch.AnnotationProcessorHider$AnnotationProcessor"));
                    } else options.add("-proc:none");
                    boolean success = compiler.getTask(null, files, diagnostics, options, null,
                            files.getJavaFileObjects(file)).call();
                    assertThat(success).as("README Java 片段 %s：%s", count, diagnostics.getDiagnostics()).isTrue();
                }
            }
            assertThat(count).isEqualTo(23);
        } finally {
            try (var paths = Files.walk(output)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
