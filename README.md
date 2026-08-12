# WCDK R2DBC

WCDK R2DBC 是一个基于 Spring Boot、Spring Data R2DBC 和 Project Reactor 的响应式数据库访问框架，面向 WebFlux 微服务和响应式业务场景。

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21+-green.svg)](https://www.oracle.com/java/)
[![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5+-brightgreen.svg)](https://spring.io/projects/spring-boot)

## 目录

- [特性](#特性)
- [环境要求](#环境要求)
- [快速开始](#快速开始)
- [配置](#配置)
- [Repository API](#repository-api)
- [派生查询方法](#派生查询方法)
- [QueryWrapper](#querywrapper)
- [分页](#分页)
- [逻辑删除](#逻辑删除)
- [多数据源](#多数据源)
- [事务](#事务)
- [XML SQL](#xml-sql)
- [SQL 生命周期与观测](#sql-生命周期与观测)
- [数据库支持](#数据库支持)
- [响应式使用约束](#响应式使用约束)
- [项目结构](#项目结构)

## 特性

- **响应式 API**：仓储查询统一返回 `Mono` / `Flux`，适配 Spring WebFlux。
- **Repository 动态代理**：启动时扫描接口并生成仓储代理，业务代码只需定义接口。
- **标准 CRUD**：提供新增、按 ID 查询、更新、删除、列表、单条、统计和存在性查询。
- **派生方法**：支持 `findBy`、`countBy`、`existsBy`、`deleteBy`、`update...By...` 等方法名约定。
- **查询构造器**：支持等值、范围、模糊、集合、空值、嵌套 `AND` / `OR`、排序、分页。
- **逻辑删除**：查询、统计、更新和派生删除默认过滤已删除数据。
- **多数据源路由**：支持通过 `@R2dbcDataSource` 和 Reactor Context 切换数据源。
- **响应式事务**：提供 `TransactionalOperator`、模板事务和手动事务能力。
- **XML SQL**：支持 XML 语句、动态 SQL、结果类型和结果映射。
- **SQL 生命周期**：支持 SQL 执行前后拦截、审计、性能统计和 Micrometer 观测。
- **数据库方言**：支持达梦、PostgreSQL、MySQL 和 Oracle 的方言适配。
- **连接池**：基于 `r2dbc-pool` 管理连接池参数。
- **类型映射**：支持实体、记录类、枚举、Java 时间类型以及自定义值转换器。

## 环境要求

- Java 21+
- Spring Boot 3.5+
- Maven 3.9+
- 对应数据库的 R2DBC 驱动

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.wcdk.r2dbc</groupId>
    <artifactId>wcdk-r2dbc</artifactId>
    <version>3.5.16</version>
</dependency>

<!-- 按实际数据库选择一个驱动 -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>r2dbc-postgresql</artifactId>
    <version>1.0.7.RELEASE</version>
    <scope>runtime</scope>
</dependency>
```

项目已经依赖 `spring-boot-starter-data-r2dbc` 时无需重复声明；达梦、MySQL、Oracle 驱动版本可参考项目 `pom.xml` 的 profile 配置。

### 2. 配置数据库

```yaml
spring:
  r2dbc:
    url: r2dbc:postgresql://localhost:5432/demo
    username: postgres
    password: postgres

wcdk:
  r2dbc:
    enabled: true
    sql-log-enabled: true
```

### 3. 启用仓储扫描

```java
package com.example;

import com.wcdk.r2dbc.config.EnableWcdkR2dbcRepositories;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@EnableWcdkR2dbcRepositories(basePackages = "com.example.repository")
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### 4. 定义实体和仓储

```java
package com.example.repository;

import com.wcdk.r2dbc.annotation.Repository;
import com.wcdk.r2dbc.repository.BaseRepository;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import reactor.core.publisher.Flux;

@Table("sys_user")
public record User(
        @Id Long id,
        @Column("user_name") String userName,
        String email,
        Integer status
) {
}

@Repository
public interface UserRepository extends BaseRepository<User> {
    Flux<User> findByStatus(Integer status);
}
```

> 如果项目仍使用旧版本包路径，也可以继续使用 `com.wcdk.r2dbc.Repository` 和 `com.wcdk.r2dbc.BaseRepository`；这两个接口保留了兼容支持。

### 5. 在 Service 中使用

```java
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    public Mono<User> findById(Long id) {
        return userRepository.selectById(id);
    }

    public Flux<User> findEnabledUsers() {
        return userRepository.findByStatus(1);
    }

    public Mono<User> create(User user) {
        return userRepository.insert(user);
    }
}
```

Controller、Service 和 Repository 之间应保持 `Mono` / `Flux` 链路，不要调用 `block()` 或在业务方法中手动 `subscribe()`。

## 配置

完整示例见 [`application-example.yml`](src/main/resources/application-example.yml)。

### WCDK 配置项

| 配置项 | 默认值 | 说明 |
|---|---:|---|
| `wcdk.r2dbc.enabled` | `false` | 是否启用 WCDK R2DBC |
| `wcdk.r2dbc.sql-log-enabled` | `true` | 是否输出 SQL 日志 |
| `wcdk.r2dbc.observability-enabled` | `false` | 是否启用 Micrometer 观测 |
| `wcdk.r2dbc.snowflake-id` | `false` | 是否启用雪花 ID 生成 |
| `wcdk.r2dbc.quote-identifier` | `true` | 是否引用数据库标识符 |
| `wcdk.r2dbc.mapper-locations` | `classpath*:repository/**/*.xml` | XML Mapper 扫描位置 |
| `wcdk.r2dbc.logic-delete-field` | `delFlg` | 逻辑删除字段 |
| `wcdk.r2dbc.logic-not-delete-value` | `0` | 未删除值 |
| `wcdk.r2dbc.logic-delete-value` | `1` | 已删除值 |

数据库初始化配置位于 `wcdk.r2dbc.database-initializer`，支持 `enabled`、`sql-location`、`database-type`、`mode`、`ignore-errors` 和 `execute-in-transaction`。

## Repository API

推荐导入：

```java
import com.wcdk.r2dbc.annotation.Repository;
import com.wcdk.r2dbc.repository.BaseRepository;
```

| 方法 | 返回值 | 说明 |
|---|---|---|
| `insert(entity)` | `Mono<T>` | 插入并返回实体 |
| `deleteById(id)` | `Mono<Long>` | 按 ID 删除，返回影响行数 |
| `updateById(entity)` | `Mono<Long>` | 按 ID 更新，返回影响行数 |
| `selectById(id)` | `Mono<T>` | 按 ID 查询 |
| `findAll()` | `Flux<T>` | 查询全部数据 |
| `selectList(wrapper)` | `Flux<T>` | 条件查询列表 |
| `selectOne(wrapper)` | `Mono<T>` | 查询单条数据 |
| `selectCount(wrapper)` | `Mono<Long>` | 条件统计 |
| `exists(wrapper)` | `Mono<Boolean>` | 判断数据是否存在 |
| `selectPage(pageable, wrapper)` | `Mono<Page<T>>` | 分页查询 |

更新和删除方法支持 `Mono<Long>`、`Mono<Integer>`、`Mono<Boolean>`、`Mono<Void>` 等兼容返回形式，具体以方法声明为准。

## 派生查询方法

框架在仓储代理创建阶段解析方法名，并生成对应的执行计划。常用形式如下：

```java
public interface UserRepository extends BaseRepository<User> {
    Flux<User> findByStatus(Integer status);
    Flux<User> findByStatusAndEmail(Integer status, String email);
    Flux<User> findByStatusOrEmail(Integer status, String email);
    Mono<Long> countByStatus(Integer status);
    Mono<Boolean> existsByEmail(String email);
    Mono<Long> deleteByStatus(Integer status);
    Mono<Integer> updateUserNameById(String userName, Long id);
}
```

支持的常见操作包括：

- `findBy`、`countBy`、`existsBy`、`deleteBy`
- `updateXxxById`、`updateXxxByField`
- `And`、`Or`、`OrderBy`
- `Equals`、`Not`、`Like`、`StartingWith`、`EndingWith`
- `GreaterThan`、`GreaterThanEqual`、`LessThan`、`LessThanEqual`
- `In`、`NotIn`、`IsNull`、`IsNotNull`

方法名中的字段必须能映射到实体属性；方法参数数量和返回类型不符合约定时，应在应用启动阶段修正，而不是在请求链路中兜底。

## QueryWrapper

当前 QueryWrapper 位于 `com.wcdk.r2dbc.core.query`，推荐使用条件表达式 API：

```java
import com.wcdk.r2dbc.core.query.QueryWrapper;

QueryWrapper<User> wrapper = new QueryWrapper<>();
wrapper.eq("status", 1)
       .and(nested -> nested
               .like("email", "%@example.com")
               .or(or -> or.isNull("user_name")
                          .eq("user_name", "管理员")))
       .orderByDesc("id")
       .limit(20)
       .offset(0);

Flux<User> users = userRepository.selectList(wrapper);
```

常用方法：

`eq`、`ne`、`gt`、`ge`、`lt`、`le`、`like`、`in`、`inArray`、`notIn`、`notInArray`、`isNull`、`isNotNull`、`and`、`or`、`orderByAsc`、`orderByDesc`、`limit`、`offset`、`page`。

`conditions()` 仅为历史兼容 API，新的执行链以 `expression()` 生成的条件表达式为准。

## 分页

```java
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

Mono<Page<User>> result = userRepository.selectPage(
        PageRequest.of(0, 20),
        new QueryWrapper<User>().eq("status", 1)
);
```

`PageRequest` 的页码从 `0` 开始。QueryWrapper 的 `page(pageNo, pageSize)` 使用从 `1` 开始的业务页码，并自动设置 `limit` 和 `offset`。

## 逻辑删除

默认逻辑删除配置为：

```yaml
wcdk:
  r2dbc:
    logic-delete-field: delFlg
    logic-not-delete-value: 0
    logic-delete-value: 1
```

实体包含对应字段时，查询、统计、存在性判断、更新和派生删除会自动过滤已删除数据。物理删除和逻辑删除的具体方法行为以实体元数据和仓储方法类型为准。

## 多数据源

配置多个数据源时，将 `spring.r2dbc` 改为 `spring.r2dbc.data-sources`，并指定主数据源：

```yaml
spring:
  r2dbc:
    primary: master
    data-sources:
      master:
        url: r2dbc:postgresql://localhost:5432/master
        username: postgres
        password: postgres
      report:
        url: r2dbc:postgresql://localhost:5432/report
        username: postgres
        password: postgres
```

在 Service 或方法上指定数据源：

```java
@R2dbcDataSource("report")
public Flux<UserReport> queryReport() {
    return reportRepository.findAll();
}
```

数据源标识通过 Reactor Context 传递；不要使用普通 `ThreadLocal` 假设数据源上下文一定存在。

## 事务

Spring 环境下优先使用响应式事务：

```java
@Service
@RequiredArgsConstructor
public class UserService {

    private final TransactionalOperator transactionalOperator;
    private final UserRepository userRepository;

    public Mono<User> create(User user) {
        return transactionalOperator.transactional(
                userRepository.insert(user)
        );
    }
}
```

事务中应保持数据库操作链的响应式特性，不要在事务范围内调用阻塞 JDBC、阻塞 HTTP 或长时间外部服务。

## XML SQL

默认扫描路径为 `classpath*:repository/**/*.xml`。例如 `src/main/resources/repository/UserRepository.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<repository namespace="com.example.repository.UserRepository">
    <select id="findActiveByEmail" resultType="com.example.User">
        SELECT id, user_name, email, status
        FROM sys_user
        WHERE email = #{email}
          AND status = 1
    </select>
</repository>
```

仓储接口声明对应方法：

```java
Mono<User> findActiveByEmail(String email);
```

XML 语句支持动态 SQL、参数绑定、`resultType`、`resultMap`、`discriminator` 和 `<foreach>`。XML 文件中的 namespace、语句 ID、参数和返回类型必须与仓储接口一致。

## SQL 生命周期与观测

可以通过 `SqlLifecycleInterceptor`、`ReactiveSqlLifecycleInterceptor` 和 `SqlExecutionObserver` 扩展 SQL 执行生命周期：

```java
@Bean
SqlExecutionObserver sqlExecutionObserver(ObservationRegistry registry) {
    return new MicrometerSqlExecutionObserver(registry);
}
```

可观测信息包括 SQL 执行阶段、终止状态、结果数量、耗时和异常。不要把密码、Token、完整身份证号或原始敏感参数写入日志和观测标签。

## 数据库支持

| 数据库 | R2DBC 驱动 | Maven profile |
|---|---|---|
| 达梦 | `com.dameng:dm-r2dbc` | `dm` |
| PostgreSQL | `org.postgresql:r2dbc-postgresql` | `postgres` |
| MySQL | `io.asyncer:r2dbc-mysql` | `mysql` |
| Oracle | `com.oracle.database.r2dbc:oracle-r2dbc` | `oracle` |

本地构建全部数据库驱动可以使用：

```bash
mvn -Pall test
```

## 响应式使用约束

- Controller、Service、Repository 链路统一返回 `Mono` 或 `Flux`。
- 业务代码禁止调用 `block()`、`blockFirst()`、`blockLast()`。
- 业务组件禁止手动 `subscribe()`。
- 阻塞 SDK 必须使用 `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` 隔离，并注明阻塞来源。
- 远程调用应设置超时；重试必须有次数上限、条件和幂等性依据。
- 大量元素使用 `flatMap` 时应设置合理并发上限。
- 异常使用 `switchIfEmpty`、`onErrorMap`、`onErrorResume` 和 `doOnError` 按语义处理，不能无条件吞异常。

## 项目结构

```text
com.wcdk.r2dbc
├── annotation       # 公共注解
├── config           # 自动配置、仓储扫描、属性配置
├── datasource       # 动态数据源与 Reactor Context
├── dialect          # 数据库方言
├── repository       # 仓储公共接口
├── execution        # 仓储执行接口
├── core.query       # QueryWrapper 与查询表达式
├── core.plan        # Repository 方法计划
├── core.executor    # CRUD、派生方法和 XML 执行器
├── core.sql         # SQL 表达式渲染
└── database         # 各数据库驱动适配
```

## 开发与测试

```bash
mvn test
mvn -DskipTests package
```

响应式单元测试推荐使用 `StepVerifier`，WebFlux 接口测试推荐使用 `WebTestClient`。

## 许可证

本项目基于 [MIT License](LICENSE) 发布。