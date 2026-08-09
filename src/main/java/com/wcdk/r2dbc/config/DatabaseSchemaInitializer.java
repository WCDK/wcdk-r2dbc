/**
 * 数据库Schema初始化器
 * 根据数据库类型自动执行对应的SQL脚本
 *
 * @auther WCDK
 * @version 1.0
 */
package com.wcdk.r2dbc.config;

import io.r2dbc.spi.ConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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

        String mode = initConfig.getMode();
        if ("never".equals(mode)) {
            log.info("数据库Schema初始化模式为never，跳过初始化");
            return;
        }

        log.info("开始初始化数据库Schema...");

        String databaseType = detectDatabaseType(initConfig.getDatabaseType());
        log.info("检测到数据库类型: {}", databaseType);

        List<Resource> sqlResources = findSqlResources(databaseType, initConfig.getSqlLocation());
        if (sqlResources.isEmpty()) {
            log.warn("未找到数据库类型 {} 对应的SQL文件，跳过初始化", databaseType);
            return;
        }

        executeSqlResources(sqlResources, initConfig.isIgnoreErrors(), initConfig.isExecuteInTransaction())
                .doOnSuccess(v -> log.info("数据库Schema初始化完成"))
                .doOnError(e -> log.error("数据库Schema初始化失败", e))
                .subscribe();
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
            String type = configuredType.toLowerCase();
            return DATABASE_TYPE_KEYWORDS.getOrDefault(type, type);
        }

        // 2. 其次使用外部配置的数据库类型
        if (externalDatabaseType != null && !externalDatabaseType.isBlank()) {
            String type = externalDatabaseType.toLowerCase();
            log.info("使用外部配置的数据库类型: {}", type);
            return DATABASE_TYPE_KEYWORDS.getOrDefault(type, type);
        }

        // 3. 最后自动检测（通过连接工厂类名判断）
        try {
            String className = connectionFactory.getClass().getName().toLowerCase();
            
            for (Map.Entry<String, String> entry : DATABASE_TYPE_KEYWORDS.entrySet()) {
                if (className.contains(entry.getKey())) {
                    return entry.getValue();
                }
            }
        } catch (Exception e) {
            log.warn("自动检测数据库类型失败", e);
        }

        log.warn("无法识别数据库类型，默认使用dm");
        return "dm";
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
        return Flux.fromIterable(resources)
                .concatMap(resource -> executeSingleResource(resource, ignoreErrors))
                .then();
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
                .flatMap(statements -> executeSqlStatements(statements, ignoreErrors));
    }

    /**
     * 读取SQL文件内容
     *
     * @param resource SQL资源
     * @return SQL语句列表
     */
    private List<String> readSqlContent(Resource resource) {
        List<String> statements = new ArrayList<>();
        StringBuilder currentStatement = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                String trimmedLine = line.trim();

                // 跳过空行和注释
                if (trimmedLine.isEmpty() || trimmedLine.startsWith("--")) {
                    continue;
                }

                currentStatement.append(line).append("\n");

                // 遇到分号则认为是一个完整的SQL语句
                if (trimmedLine.endsWith(";")) {
                    String sql = currentStatement.toString().trim();
                    if (!sql.isEmpty()) {
                        statements.add(sql);
                    }
                    currentStatement = new StringBuilder();
                }
            }

            // 处理最后一个没有分号的语句
            String lastStatement = currentStatement.toString().trim();
            if (!lastStatement.isEmpty()) {
                statements.add(lastStatement);
            }

        } catch (Exception e) {
            log.error("读取SQL文件失败: {}", resource.getFilename(), e);
            throw new RuntimeException("读取SQL文件失败: " + resource.getFilename(), e);
        }

        return statements;
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
                            if (ignoreErrors) {
                                return Mono.empty();
                            }
                            return Mono.error(e);
                        })
                );
    }
}
