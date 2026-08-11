/**
 * 数据库Schema初始化器
 * 根据数据库类型自动执行对应的SQL脚本
 *
 * @auther WCDK
 * @version 1.0
 */
package com.wcdk.r2dbc.config;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.R2dbcException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 数据库Schema初始化器
 * 根据数据库类型自动执行对应的SQL脚本
 *
 * @auther WCDK
 * @version 1.0
 */
public class DatabaseSchemaInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSchemaInitializer.class);

    private final DatabaseClient databaseClient;

    private final ConnectionFactory connectionFactory;

    private final ResourcePatternResolver resourcePatternResolver;

    private final WcdkR2dbcProperties properties;

    /**
     * 外部配置的数据库类型（来自 database.type 配置项）
     */
    private final String externalDatabaseType;

    /**
     * 数据库类型关键字与类型名称映射
     */
    private static final Map<String, String> DATABASE_TYPE_KEYWORDS = Map.of(
            "dm", "dm",
            "dameng", "dm",
            "oracle", "oracle",
            "postgresql", "postgresql",
            "postgres", "postgresql",
            "mysql", "mysql"
    );

    /**
     * 构造函数注入依赖
     *
     * @param databaseClient         数据库客户端
     * @param connectionFactory      连接工厂
     * @param resourcePatternResolver 资源解析器
     * @param properties             配置属性
     * @param externalDatabaseType   外部配置的数据库类型
     */
    public DatabaseSchemaInitializer(DatabaseClient databaseClient,
                                     ConnectionFactory connectionFactory,
                                     ResourcePatternResolver resourcePatternResolver,
                                     WcdkR2dbcProperties properties,
                                     @Value("${database.type:}") String externalDatabaseType) {
        this.databaseClient = databaseClient;
        this.connectionFactory = connectionFactory;
        this.resourcePatternResolver = resourcePatternResolver;
        this.properties = properties;
        this.externalDatabaseType = externalDatabaseType;
    }

    /**
     * 项目启动时执行数据库初始化
     *
     * @param args 命令行参数
     */
    @Override
    public void run(String... args) {
        WcdkR2dbcProperties.DatabaseInitializer initConfig = properties.getDatabaseInitializer();
        if (initConfig == null || !initConfig.isEnabled()) {
            log.debug("数据库Schema初始化已禁用");
            return;
        }

        String mode = normalizeMode(initConfig.getMode());
        if ("never".equals(mode)) {
            log.info("数据库Schema初始化模式为never，跳过初始化");
            return;
        }

        log.info("开始初始化数据库Schema...");

        String databaseType = detectDatabaseType(initConfig.getDatabaseType());
        if ("embedded".equals(mode) && !isEmbedded(databaseType)) {
            log.info("Schema initialization mode is embedded, but database '{}' is not embedded; skipping", databaseType);
            return;
        }
        log.info("检测到数据库类型: {}", databaseType);

        List<Resource> sqlResources = findSqlResources(databaseType, initConfig.getSqlLocation());
        if (sqlResources.isEmpty()) {
            log.warn("未找到数据库类型 {} 对应的SQL文件，跳过初始化", databaseType);
            return;
        }

        executeSqlResources(sqlResources, initConfig.isIgnoreErrors(), initConfig.isExecuteInTransaction())
                .doOnSuccess(v -> log.info("数据库Schema初始化完成"))
                .doOnError(e -> log.error("数据库Schema初始化失败", e))
                .block();
    }

    /**
     * 检测数据库类型
     * 优先级：配置属性 > 外部配置 > 自动检测
     *
     * @param configuredType 配置指定的数据库类型（可选）
     * @return 数据库类型
     */
    private String detectDatabaseType(String configuredType) {
        // 1. 优先使用配置属性中的数据库类型
        if (configuredType != null && !configuredType.isBlank()) {
            return normalizeDatabaseType(configuredType);
        }

        // 2. 其次使用外部配置的数据库类型
        if (externalDatabaseType != null && !externalDatabaseType.isBlank()) {
            String type = externalDatabaseType.toLowerCase(java.util.Locale.ROOT);
            log.info("使用外部配置的数据库类型: {}", type);
            return normalizeDatabaseType(type);
        }

        // 3. 最后自动检测（通过连接工厂类名判断）
        try {
            String metadataName = connectionFactory.getMetadata().getName();
            if (metadataName != null && !metadataName.isBlank()) {
                return normalizeDatabaseType(metadataName);
            }
        } catch (Exception e) {
            log.warn("自动检测数据库类型失败", e);
        }

        log.warn("无法识别数据库类型，默认使用dm");
        throw new IllegalStateException("Unable to determine database type from configuration or R2DBC metadata");
    }

    /**
     * 查找SQL资源文件
     *
     * @param databaseType 数据库类型
     * @param sqlLocation  SQL文件位置模式
     * @return SQL资源列表
     */
    private List<Resource> findSqlResources(String databaseType, String sqlLocation) {
        List<Resource> resources = new ArrayList<>();

        try {
            // 首先尝试查找特定数据库类型的SQL文件
            String specificPattern = sqlLocation.replace("**", databaseType + "/**");
            Resource[] specificResources = resourcePatternResolver.getResources(specificPattern);
            for (Resource resource : specificResources) {
                if (resource.getFilename() != null && resource.getFilename().endsWith(".sql")) {
                    resources.add(resource);
                }
            }

            // 如果没有找到特定类型的SQL文件，尝试查找通用SQL文件
            if (resources.isEmpty()) {
                Resource[] allResources = resourcePatternResolver.getResources(sqlLocation);
                for (Resource resource : allResources) {
                    String filename = resource.getFilename();
                    if (filename != null && filename.endsWith(".sql")) {
                        // 检查是否是通用SQL文件（不包含数据库类型前缀）
                        boolean isDatabaseSpecific = DATABASE_TYPE_KEYWORDS.keySet().stream()
                                .anyMatch(key -> filename.toLowerCase().contains(key));
                        if (!isDatabaseSpecific) {
                            resources.add(resource);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("查找SQL资源文件失败", e);
        }

        return resources;
    }

    /**
     * 执行SQL资源文件
     *
     * @param resources           SQL资源列表
     * @param ignoreErrors        是否忽略错误
     * @param executeInTransaction 是否在事务中执行
     * @return 执行结果
     */
    private Mono<Void> executeSqlResources(List<Resource> resources, boolean ignoreErrors, boolean executeInTransaction) {
        Flux<String> statements = Flux.fromIterable(resources)
                .concatMap(resource -> Mono.fromCallable(() -> {
                            log.info("Executing SQL resource: {}", resource.getDescription());
                            return readSqlContent(resource);
                        })
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMapIterable(value -> value);
        if (!executeInTransaction) {
            return statements.concatMap(statement -> executeSingleStatement(statement, ignoreErrors))
                    .then();
        }
        return Mono.usingWhen(
                Mono.from(connectionFactory.create()),
                connection -> Mono.from(connection.beginTransaction())
                        .thenMany(statements.concatMap(statement ->
                                executeOnConnection(connection, statement, ignoreErrors)))
                        .then(Mono.defer(() -> Mono.from(connection.commitTransaction()))),
                connection -> Mono.from(connection.close()),
                (connection, error) -> Mono.from(connection.rollbackTransaction())
                        .onErrorResume(rollback -> {
                            error.addSuppressed(rollback);
                            return Mono.empty();
                        })
                        .then(Mono.from(connection.close())),
                connection -> Mono.from(connection.rollbackTransaction())
                        .onErrorResume(ignored -> Mono.empty())
                        .then(Mono.from(connection.close())));
    }

    /**
     * 执行单个SQL资源文件
     *
     * @param resource     SQL资源
     * @param ignoreErrors 是否忽略错误
     * @return 执行结果
     */
    private Mono<Void> executeSingleResource(Resource resource, boolean ignoreErrors) {
        return Mono.fromCallable(() -> {
                    log.info("执行SQL文件: {}", resource.getFilename());
                    return readSqlContent(resource);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(statements -> executeSqlStatements(statements, ignoreErrors));
    }

    /**
     * 读取SQL文件内容
     *
     * @param resource SQL资源
     * @return SQL语句列表
     */
    private List<String> readSqlContent(Resource resource) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder script = new StringBuilder();
            reader.lines().forEach(line -> script.append(line).append('\n'));
            return splitSqlScript(script.toString());
        } catch (Exception e) {
            log.error("读取SQL文件失败: {}", resource.getFilename(), e);
            throw new RuntimeException("读取SQL文件失败: " + resource.getFilename(), e);
        }
    }

    /**
     * 执行SQL语句列表
     *
     * @param statements   SQL语句列表
     * @param ignoreErrors 是否忽略错误
     * @return 执行结果
     */
    private Mono<Void> executeSqlStatements(List<String> statements, boolean ignoreErrors) {
        return Flux.fromIterable(statements)
                .concatMap(statement -> executeSingleStatement(statement, ignoreErrors))
                .then();
    }

    /**
     * 执行单个SQL语句
     *
     * @param sql          SQL语句
     * @param ignoreErrors 是否忽略错误
     * @return 执行结果
     */
    private Mono<Void> executeSingleStatement(String sql, boolean ignoreErrors) {
        return Mono.fromCallable(() -> {
                    log.debug("执行SQL: {}", sql.substring(0, Math.min(sql.length(), 100)));
                    return sql;
                })
                .flatMap(sqlStr -> databaseClient.sql(sqlStr)
                        .then()
                        .doOnSuccess(v -> log.debug("SQL执行成功"))
                        .doOnError(e -> {
                            String errorMsg = e.getMessage();
                            if (ignoreErrors && errorMsg != null) {
                                // 忽略常见的"已存在"错误
                                if (errorMsg.contains("already exists") ||
                                        errorMsg.contains("Duplicate table") ||
                                        errorMsg.contains("ORA-00942") ||
                                        errorMsg.contains("does not exist") ||
                                        errorMsg.contains("Duplicate entry")) {
                                    log.debug("SQL执行忽略（对象已存在）: {}", errorMsg);
                                    return;
                                }
                            }
                            log.warn("SQL执行警告: {}", errorMsg);
                        })
                        .onErrorResume(e -> {
                            if (ignoreErrors && isIgnorable(e)) {
                                return Mono.empty();
                            }
                            return Mono.error(e);
                        })
                );
    }

    private Mono<Void> executeOnConnection(Connection connection, String sql, boolean ignoreErrors) {
        return Flux.from(connection.createStatement(sql).execute())
                .flatMap(result -> result.getRowsUpdated())
                .then()
                .onErrorResume(error -> ignoreErrors && isIgnorable(error) ? Mono.empty() : Mono.error(error));
    }

    static List<String> splitSqlScript(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean singleQuote = false;
        boolean doubleQuote = false;
        boolean lineComment = false;
        boolean blockComment = false;
        boolean plsql = false;
        String dollarTag = null;
        String delimiter = ";";

        for (int i = 0; i < script.length(); i++) {
            char ch = script.charAt(i);
            char next = i + 1 < script.length() ? script.charAt(i + 1) : '\0';
            boolean lineStart = i == 0 || script.charAt(i - 1) == '\n';

            if (!singleQuote && !doubleQuote && dollarTag == null && !blockComment && lineStart) {
                int lineEnd = script.indexOf('\n', i);
                lineEnd = lineEnd < 0 ? script.length() : lineEnd;
                String line = script.substring(i, lineEnd).strip();
                if (current.toString().isBlank() && line.regionMatches(true, 0, "DELIMITER ", 0, 10)) {
                    delimiter = line.substring(10).strip();
                    if (delimiter.isEmpty()) {
                        throw new IllegalArgumentException("SQL DELIMITER must not be empty");
                    }
                    i = lineEnd;
                    continue;
                }
                if (plsql && line.equals("/")) {
                    addStatement(statements, current);
                    plsql = false;
                    i = lineEnd;
                    continue;
                }
            }

            if (lineComment) {
                if (ch == '\n') {
                    lineComment = false;
                    current.append(ch);
                }
                continue;
            }
            if (blockComment) {
                if (ch == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (!singleQuote && !doubleQuote && dollarTag == null && ch == '-' && next == '-') {
                lineComment = true;
                i++;
                continue;
            }
            if (!singleQuote && !doubleQuote && dollarTag == null && ch == '/' && next == '*') {
                blockComment = true;
                i++;
                continue;
            }

            if (!singleQuote && !doubleQuote && dollarTag == null
                    && !delimiter.equals(";") && script.startsWith(delimiter, i)) {
                addStatement(statements, current);
                i += delimiter.length() - 1;
                continue;
            }
            if (!singleQuote && !doubleQuote && ch == '$') {
                int tagEnd = script.indexOf('$', i + 1);
                if (tagEnd >= 0 && script.substring(i + 1, tagEnd).matches("[A-Za-z0-9_]*")) {
                    String tag = script.substring(i, tagEnd + 1);
                    if (dollarTag == null) {
                        dollarTag = tag;
                    } else if (dollarTag.equals(tag)) {
                        dollarTag = null;
                    }
                    current.append(tag);
                    i = tagEnd;
                    continue;
                }
            }
            if (dollarTag == null) {
                if (ch == '\'' && !doubleQuote) {
                    if (singleQuote && next == '\'') {
                        current.append(ch).append(next);
                        i++;
                        continue;
                    }
                    singleQuote = !singleQuote;
                } else if (ch == '"' && !singleQuote) {
                    if (doubleQuote && next == '"') {
                        current.append(ch).append(next);
                        i++;
                        continue;
                    }
                    doubleQuote = !doubleQuote;
                }
            }

            current.append(ch);
            if (!singleQuote && !doubleQuote && dollarTag == null) {
                String prefix = current.toString().stripLeading().toUpperCase(java.util.Locale.ROOT);
                plsql = plsql || prefix.startsWith("DECLARE") || prefix.startsWith("BEGIN")
                        || (prefix.startsWith("CREATE OR REPLACE") && java.util.regex.Pattern.compile(
                                "\\b(PROCEDURE|FUNCTION|TRIGGER|PACKAGE)\\b").matcher(prefix).find());
                if (!plsql && delimiter.equals(";") && ch == ';') {
                    current.setLength(current.length() - 1);
                    addStatement(statements, current);
                }
            }
        }
        if (singleQuote || doubleQuote || dollarTag != null || blockComment) {
            throw new IllegalArgumentException("Unterminated quote or comment in SQL script");
        }
        addStatement(statements, current);
        return statements;
    }

    private static void addStatement(List<String> statements, StringBuilder current) {
        String value = current.toString().strip();
        if (!value.isEmpty()) {
            statements.add(value);
        }
        current.setLength(0);
    }

    private boolean isIgnorable(Throwable error) {
        if (!(error instanceof R2dbcException r2dbcError)) {
            return false;
        }
        String state = r2dbcError.getSqlState();
        int code = r2dbcError.getErrorCode();
        return java.util.Set.of("42P07", "42710", "42S01", "42S02").contains(state)
                || code == 955 || code == 942;
    }

    private String normalizeMode(String mode) {
        String normalized = mode == null ? "always" : mode.strip().toLowerCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("always", "never", "embedded").contains(normalized)) {
            throw new IllegalArgumentException("Invalid wcdk.r2dbc.database-initializer.mode: " + mode);
        }
        return normalized;
    }

    private String normalizeDatabaseType(String value) {
        String normalized = value.strip().toLowerCase(java.util.Locale.ROOT);
        for (Map.Entry<String, String> entry : DATABASE_TYPE_KEYWORDS.entrySet()) {
            if (normalized.equals(entry.getKey()) || normalized.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        for (String embedded : java.util.List.of("h2", "hsql", "derby")) {
            if (normalized.contains(embedded)) {
                return embedded;
            }
        }
        throw new IllegalStateException("Unsupported database type: " + value);
    }

    private boolean isEmbedded(String databaseType) {
        return java.util.Set.of("h2", "hsql", "derby").contains(databaseType);
    }
}
