# WCDK-R2DBC

> 基于 Spring Boot 的响应式数据库访问框架，提供多数据源支持、动态路由、SQL生命周期拦截等特性。

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21+-green.svg)](https://www.oracle.com/java/)
[![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5+-brightgreen.svg)](https://spring.io/projects/spring-boot)

## 目录

- [特性](#特性)
- [快速开始](#快速开始)
- [配置说明](#配置说明)
- [多数据源配置](#多数据源配置)
- [XML映射配置](#xml映射配置)
- [SQL生命周期拦截器](#sql生命周期拦截器)
- [自定义仓储方法](#自定义仓储方法)
- [事务管理](#事务管理)
- [数据库方言支持](#数据库方言支持)
- [API文档](#api文档)
- [架构设计](#架构设计)
- [常见问题](#常见问题)

## 特性

### 核心特性

| 特性 | 说明 |
|------|------|
| **多数据源支持** | 支持配置多个数据源，动态路由切换 |
| **响应式编程** | 基于 Project Reactor 的完全非阻塞响应式模型 |
| **多数据库兼容** | 支持达梦、PostgreSQL、MySQL、Oracle |
| **连接池管理** | 内置 r2dbc-pool 连接池，支持精细化配置 |
| **事务管理** | 支持声明式事务、模板事务、手动事务 |
| **动态数据源切换** | 通过 AOP + 注解实现数据源动态切换 |
| **SQL生命周期拦截** | 支持在SQL编译前/后、执行前/后自定义操作 |

### 开发特性

| 特性 | 说明 |
|------|------|
| **简化API** | 提供 BaseRepository 接口，封装常用CRUD操作 |
| **查询构造器** | QueryWrapper 提供链式编程构建查询条件 |
| **XML映射** | 支持类似 MyBatis 的 XML SQL 映射文件 |
| **resultType** | 支持指定查询结果的返回类型 |
| **resultMap** | 支持自定义列名到属性名的映射关系 |
| **discriminator** | 支持根据列值选择不同的 resultMap，实现多态映射 |
| **自动ID生成** | 内置雪花算法，自动生成分布式唯一ID |
| **分页支持** | 内置分页查询功能，简化分页逻辑 |
| **逻辑删除** | 支持逻辑删除，数据可恢复 |
| **SQL日志** | 可配置的SQL日志输出，便于调试 |
| **自定义仓储方法** | 支持通过方法名约定自动生成SQL（findBy*、countBy*等） |
| **响应式拦截器** | 支持异步拦截器，不阻塞响应式流 |

## 快速开始

### 环境要求

- Java 21+
- Spring Boot 3.5+
- Maven 3.8+

### 添加依赖

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-r2dbc</artifactId>
</dependency>
<dependency>
    <groupId>com.wcdk</groupId>
    <artifactId>wcdk-r2dbc</artifactId>
    <version>1.0.0</version>
</dependency>

<!-- 数据库驱动 (按需选择) -->
<dependency>
    <groupId>com.dameng</groupId>
    <artifactId>dm-r2dbc</artifactId>
    <version>1.0.0</version>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>r2dbc-postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.asyncer</groupId>
    <artifactId>r2dbc-mysql</artifactId>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>com.oracle.database.r2dbc</groupId>
    <artifactId>oracle-r2dbc</artifactId>
    <scope>runtime</scope>
</dependency>
```

### 基础配置

```yaml
# application.yml
spring:
  r2dbc:
    url: r2dbc:dm://localhost:5236
    username: SYSDBA
    password: SYSDBA

wcdk:
  r2dbc:
    enabled: true
    sql-log-enabled: true
```

### 启用注解

在启动类上添加 `@EnableWcdkR2dbcRepositories` 注解，并指定 Repository 扫描路径：

```java
@EnableWcdkR2dbcRepositories(basePackages = {
        "com.example.repository"
})
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### 定义实体

```java
@Data
@ToString
@Table("sys_user")
public class SysUser {
    
    @Id
    private Long id;
    
    @Column("user_name")
    private String userName;
    
    @Column("email")
    private String email;
    
    @Column("status")
    private Integer status;
    
    @Column("create_time")
    private LocalDateTime createTime;
}
```

### 定义仓储接口

```java
@Repository
public interface SysUserRepository extends BaseRepository<SysUser> {
    
    /**
     * 自定义查询方法
     */
    @Select("SELECT * FROM sys_user WHERE user_name = #{userName}")
    Flux<SysUser> findByUserName(@Param("userName") String userName);
}
```

### 使用仓储

```java
@Service
@RequiredArgsConstructor
public class SysUserService {
    
    private final SysUserRepository userRepository;
    
    /**
     * 根据ID查询
     */
    public Mono<SysUser> findById(Long id) {
        return userRepository.selectById(id);
    }
    
    /**
     * 条件查询
     */
    public Flux<SysUser> findByCondition() {
        QueryWrapper<SysUser> wrapper = new QueryWrapper<>();
        wrapper.eq("status", 1)
               .orderByDesc("create_time")
               .limit(10);
        return userRepository.selectList(wrapper);
    }
    
    /**
     * 新增用户
     */
    public Mono<SysUser> save(SysUser user) {
        return userRepository.insert(user);
    }
    
    /**
     * 批量查询（分页）
     */
    public Mono<Page<SysUser>> findPage(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo - 1, pageSize);
        return userRepository.selectPage(pageable);
    }
}
```

## 配置说明

### 基础配置

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `wcdk.r2dbc.enabled` | boolean | false | 启用WCDK R2DBC |
| `wcdk.r2dbc.sql-log-enabled` | boolean | true | 启用SQL日志 |
| `wcdk.r2dbc.quote-identifier` | boolean | true | 标识符加引号 |
| `wcdk.r2dbc.mapper-locations` | String | `classpath*:repository/**/*.xml` | XML映射文件位置 |
| `wcdk.r2dbc.logic-delete-field` | String | `delFlg` | 逻辑删除字段 |
| `wcdk.r2dbc.logic-delete-value` | Object | 1 | 逻辑删除值 |
| `wcdk.r2dbc.logic-not-delete-value` | Object | 0 | 未删除值 |
| `wcdk.r2dbc.base-packages` | String[] | - | Repository扫描路径（可选，优先使用注解配置） |

### 连接池配置

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `spring.r2dbc.pool.enabled` | boolean | true | 启用连接池 |
| `spring.r2dbc.pool.max-size` | int | 20 | 最大连接数 |
| `spring.r2dbc.pool.max-idle-time` | Duration | 30m | 最大空闲时间 |
| `spring.r2dbc.pool.max-life-time` | Duration | 30m | 最大生命周期 |
| `spring.r2dbc.pool.max-acquire-time` | Duration | 10s | 最大获取时间 |
| `spring.r2dbc.pool.acquire-retry` | int | 1 | 获取重试次数 |
| `spring.r2dbc.pool.initial-size` | int | 10 | 初始连接数 |

## 多数据源配置

### 配置示例

```yaml
spring:
  r2dbc:
    primary: master  # 主数据源名称
    
    data-sources:
      master:
        url: r2dbc:dm://localhost:5236
        username: SYSDBA
        password: SYSDBA
        pool:
          enabled: true
          max-size: 20
      
      postgres:
        url: r2dbc:postgresql://localhost:5432/testdb
        username: postgres
        password: postgres
        pool:
          enabled: true
          max-size: 20
      
      mysql:
        url: r2dbc:mysql://localhost:3306/testdb
        username: root
        password: root
        pool:
          enabled: true
          max-size: 20
      
      oracle:
        url: r2dbc:oracle://localhost:1521/testdb
        username: system
        password: oracle
        pool:
          enabled: true
          max-size: 20
```

### 动态数据源切换

```java
@Service
@RequiredArgsConstructor
public class MultiDataSourceService {
    
    private final R2dbcUtil r2dbcUtil;
    
    /**
     * 使用指定数据源执行查询
     */
    public Mono<Map<String, Object>> queryFromMaster(String sql) {
        return r2dbcUtil.dataSource("master", r2dbcUtil.queryOne(sql));
    }
    
    /**
     * 使用从库执行查询
     */
    public Mono<Map<String, Object>> queryFromSlave(String sql) {
        return r2dbcUtil.dataSource("slave", r2dbcUtil.queryOne(sql));
    }
    
    /**
     * 事务操作
     */
    public Mono<Void> transactionalOperation() {
        return r2dbcUtil.transaction("master", client -> {
            // 事务操作
            return Mono.empty();
        }).then();
    }
}
```

### AOP注解切换

```java
@Service
public class OrderService {
    
    /**
     * 使用AOP注解切换数据源
     */
    @R2dbcDataSource("slave")
    public Mono<Order> findOrderFromSlave(Long id) {
        // 自动切换到slave数据源
        return orderRepository.selectById(id);
    }
}
```

## XML映射配置

### 配置说明

XML映射文件用于定义复杂的SQL语句，支持 `resultType` 和 `resultMap` 两种结果映射方式。

### 文件位置

在 `application.yml` 中配置XML文件位置：

```yaml
wcdk:
  r2dbc:
    enabled: true
    mapper-locations: classpath*:repository/**/*.xml
```

### 基础结构

```xml
<?xml version="1.0" encoding="UTF-8"?>
<repository namespace="com.example.repository.UserRepository">
    
    <!-- SQL语句定义 -->
    <select id="findByName" resultType="com.example.entity.User">
        SELECT * FROM user WHERE name = #{name}
    </select>
    
</repository>
```

### 参数绑定

支持以下参数绑定方式：

```xml
<!-- 使用 #{} 绑定参数 -->
<select id="findUser" resultType="com.example.entity.User">
    SELECT * FROM user WHERE name = #{name} AND status = #{status}
</select>

<!-- 支持嵌套属性 -->
<select id="findOrder" resultType="com.example.entity.Order">
    SELECT * FROM order WHERE user_id = #{user.id}
</select>

<!-- 支持集合参数 -->
<delete id="deleteByIds">
    DELETE FROM user WHERE id IN 
    <foreach collection="ids" item="id" open="(" separator="," close=")">
        #{id}
    </foreach>
</delete>
```

### resultType 详解

`resultType` 用于指定查询结果的返回类型：

```xml
<!-- 实体类映射 -->
<select id="findById" resultType="com.example.entity.User">
    SELECT * FROM user WHERE id = #{id}
</select>

<!-- 基本类型映射 -->
<select id="countByStatus" resultType="java.lang.Long">
    SELECT COUNT(*) FROM user WHERE status = #{status}
</select>

<!-- Map类型映射 -->
<select id="findMap" resultType="java.util.Map">
    SELECT * FROM user WHERE id = #{id}
</select>
```

### resultMap 详解

`resultMap` 用于自定义列名到属性名的映射关系：

```xml
<resultMap id="userResultMap" type="com.example.entity.User">
    <!-- 主键映射 -->
    <id column="user_id" property="id"/>
    
    <!-- 普通字段映射 -->
    <result column="user_name" property="name"/>
    <result column="user_email" property="email"/>
    <result column="create_time" property="createTime"/>
</resultMap>

<select id="findById" resultMap="userResultMap">
    SELECT user_id, user_name, user_email, create_time 
    FROM user WHERE user_id = #{id}
</select>
```

### discriminator 鉴别器

`discriminator` 用于根据列值选择不同的 `resultMap`，实现多态映射：

```xml
<resultMap id="vehicleResultMap" type="com.example.entity.Vehicle">
    <id column="vehicle_id" property="id"/>
    <result column="vehicle_type" property="type"/>
    
    <!-- 根据 vehicle_type 列的值选择不同的 resultMap -->
    <discriminator column="vehicle_type">
        <case value="car" resultMap="carResultMap"/>
        <case value="truck" resultMap="truckResultMap"/>
        <case value="motorcycle" resultMap="motorcycleResultMap"/>
    </discriminator>
</resultMap>

<resultMap id="carResultMap" type="com.example.entity.Car">
    <id column="vehicle_id" property="id"/>
    <result column="seat_count" property="seatCount"/>
    <result column="fuel_type" property="fuelType"/>
</resultMap>

<resultMap id="truckResultMap" type="com.example.entity.Truck">
    <id column="vehicle_id" property="id"/>
    <result column="payload_capacity" property="payloadCapacity"/>
    <result column="axle_count" property="axleCount"/>
</resultMap>

<select id="findVehicle" resultMap="vehicleResultMap">
    SELECT * FROM vehicle WHERE id = #{id}
</select>
```

### 完整示例

```xml
<?xml version="1.0" encoding="UTF-8"?>
<repository namespace="com.example.repository.UserRepository">
    
    <!-- 定义 resultMap -->
    <resultMap id="userResultMap" type="com.example.entity.User">
        <id column="user_id" property="id"/>
        <result column="user_name" property="name"/>
        <result column="user_email" property="email"/>
        <result column="user_status" property="status"/>
        <result column="create_time" property="createTime"/>
        <result column="update_time" property="updateTime"/>
    </resultMap>
    
    <!-- 使用 resultType -->
    <select id="findByName" resultType="com.example.entity.User">
        SELECT * FROM user WHERE user_name = #{name}
    </select>
    
    <!-- 使用 resultMap -->
    <select id="findById" resultMap="userResultMap">
        SELECT user_id, user_name, user_email, user_status, create_time, update_time
        FROM user WHERE user_id = #{id}
    </select>
    
    <!-- 带条件的查询 -->
    <select id="findByCondition" resultMap="userResultMap">
        SELECT user_id, user_name, user_email, user_status, create_time, update_time
        FROM user
        <where>
            <if test="name != null">
                AND user_name LIKE CONCAT('%', #{name}, '%')
            </if>
            <if test="status != null">
                AND user_status = #{status}
            </if>
        </where>
        ORDER BY create_time DESC
    </select>
    
    <!-- 批量插入 -->
    <insert id="batchInsert">
        INSERT INTO user (user_name, user_email, user_status)
        VALUES
        <foreach collection="users" item="user" separator=",">
            (#{user.name}, #{user.email}, #{user.status})
        </foreach>
    </insert>
    
    <!-- 更新操作 -->
    <update id="updateStatus">
        UPDATE user SET user_status = #{status}, update_time = NOW()
        WHERE user_id = #{id}
    </update>
    
    <!-- 删除操作 -->
    <delete id="deleteByIds">
        DELETE FROM user WHERE user_id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">
            #{id}
        </foreach>
    </delete>
    
</repository>
```

## SQL生命周期拦截器

### 生命周期阶段

```
Repository方法调用
    │
    ▼
┌─────────────────┐
│  beforeCompile  │  ← SQL编译前：参数预处理、权限校验
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│    SQL编译      │  ← 生成SQL语句
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│   afterCompile  │  ← SQL编译后：SQL审计、日志、SQL修改
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  beforeExecute  │  ← SQL执行前：最终校验、计时开始
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│    SQL执行      │  ← 执行数据库操作
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  afterExecute   │  ← SQL执行后：计时结束、结果处理、异常处理
└─────────────────┘
```

### 执行状态

拦截器通过 `SqlExecutionStatus` 枚举提供清晰的执行状态语义：

| 状态 | 描述 | 是否终态 | 使用场景 |
|------|------|----------|----------|
| `CONTINUE` | 继续执行 | 否 | 默认状态，继续执行后续拦截器和SQL |
| `COMPLETED` | 正常完成 | 是 | SQL正常执行完成（可能无结果） |
| `DENIED_BY_PERMISSION` | 权限阻止 | 是 | 被权限拦截器阻止执行 |
| `SKIPPED_BY_AUDIT` | 审计跳过 | 是 | 被审计策略跳过 |
| `TERMINATED_AT_COMPILE` | 编译终止 | 是 | SQL编译阶段主动终止 |
| `TERMINATED_AT_EXECUTE` | 执行终止 | 是 | SQL执行阶段主动终止 |
| `CACHE_HIT` | 缓存命中 | 是 | 从缓存返回结果，无需执行SQL |
| `DEGRADED` | 降级执行 | 是 | 降级策略触发，使用备用逻辑 |

### 实现自定义拦截器

```java
@Component
public class PermissionInterceptor implements SqlLifecycleInterceptor {
    
    private static final Logger log = LoggerFactory.getLogger(PermissionInterceptor.class);
    
    @Override
    public void beforeExecute(SqlExecutionContext context) {
        // 权限校验
        if (!hasPermission(getCurrentUser(), context.getSql())) {
            // 明确标识：权限阻止
            context.denyByPermission("用户 " + getCurrentUser() + " 无权执行此SQL");
        }
    }
    
    @Override
    public int getOrder() {
        return -200; // 权限拦截器优先执行
    }
}

@Component
public class AuditInterceptor implements SqlLifecycleInterceptor {
    
    private static final Logger log = LoggerFactory.getLogger(AuditInterceptor.class);
    
    @Override
    public void afterCompile(SqlExecutionContext context) {
        // 审计检查
        if (isReadOnlyAuditMode() && isWriteOperation(context.getSql())) {
            // 明确标识：审计跳过
            context.skipByAudit("只读审计模式，跳过写操作");
        }
    }
    
    @Override
    public int getOrder() {
        return -100; // 审计拦截器
    }
}

@Component
public class CacheInterceptor implements SqlLifecycleInterceptor {
    
    @Override
    public void beforeExecute(SqlExecutionContext context) {
        Object cachedResult = cache.get(context.getSql());
        if (cachedResult != null) {
            // 明确标识：缓存命中
            context.cacheHit(cachedResult);
        }
    }
    
    @Override
    public int getOrder() {
        return -300; // 缓存拦截器最先执行
    }
}
```

### SqlExecutionContext 快捷方法

```java
// 控制执行流程
context.denyByPermission("权限不足");           // 权限阻止
context.skipByAudit("审计跳过");               // 审计跳过
context.terminateAtCompile("参数校验失败");     // 编译终止
context.terminateAtExecute("熔断器打开");       // 执行终止
context.cacheHit(cachedResult);                // 缓存命中
context.degrade("数据库超时，使用缓存");        // 降级执行

// 状态查询
context.getStatus();           // 获取状态枚举
context.getStatusReason();     // 获取状态原因
context.shouldContinue();      // 判断是否继续执行
context.isTerminated();        // 判断是否已终止
```

### 同步与异步拦截器对比

| 特性 | `SqlLifecycleInterceptor` | `ReactiveSqlLifecycleInterceptor` |
|------|---------------------------|-----------------------------------|
| 方法签名 | `void beforeCompile(context)` | `Mono<Void> beforeCompileReactive(context)` |
| 执行模式 | 同步 | 异步 |
| 适用场景 | 简单逻辑（日志、计时） | 需要异步操作（远程调用、数据库查询等） |
| 阻塞风险 | 可能阻塞响应式流 | 不阻塞 |
| 执行顺序 | 在异步拦截器之后执行 | 在同步拦截器之前执行 |

### 响应式拦截器示例

```java
@Component
public class RemotePermissionInterceptor implements ReactiveSqlLifecycleInterceptor {
    
    private final PermissionService permissionService;
    
    @Override
    public Mono<Void> beforeCompileReactive(SqlExecutionContext context) {
        // 异步远程调用验证权限
        return permissionService.checkPermission(context.getSql())
                .flatMap(hasPermission -> {
                    if (!hasPermission) {
                        context.denyByPermission("权限不足");
                    }
                    return Mono.empty();
                });
    }
    
    @Override
    public int getOrder() {
        return -300; // 缓存拦截器最先执行
    }
}

@Component
public class CacheInterceptor implements ReactiveSqlLifecycleInterceptor {
    
    private final CacheService cacheService;
    
    @Override
    public Mono<Void> beforeExecuteReactive(SqlExecutionContext context) {
        return cacheService.get(context.getSql())
                .flatMap(cachedResult -> {
                    if (cachedResult != null) {
                        context.cacheHit(cachedResult);
                    }
                    return Mono.empty();
                });
    }
    
    @Override
    public int getOrder() {
        return -200;
    }
}
```

### 拦截器初始化

```java
@Configuration
public class InterceptorConfig {
    
    @Bean
    public SqlLifecycleInterceptorChain interceptorChain(
            List<SqlLifecycleInterceptor> syncInterceptors,
            List<ReactiveSqlLifecycleInterceptor> reactiveInterceptors) {
        
        SqlLifecycleInterceptorHolder.init(syncInterceptors, reactiveInterceptors);
        return SqlLifecycleInterceptorHolder.getChain();
    }
}
```

### 内置拦截器

| 拦截器 | 说明 | 执行顺序 |
|--------|------|----------|
| `SqlAuditInterceptor` | SQL审计日志 | -100 |
| `SqlPerformanceInterceptor` | 性能监控 | MAX_VALUE |

## 自定义仓储方法

### 方法名约定

支持通过方法名约定自动生成SQL，无需编写XML或注解。

### 支持的方法格式

| 方法格式 | 示例 | 生成SQL |
|----------|------|---------|
| `findAll` | `findAll()` | `SELECT * FROM table` |
| `findBy[Field]` | `findByUserName(String name)` | `SELECT * FROM table WHERE user_name = ?` |
| `findBy[Field1]And[Field2]` | `findByNameAndStatus(String name, Integer status)` | `SELECT * FROM table WHERE name = ? AND status = ?` |
| `findBy[Field]Or[Field]` | `findByNameOrEmail(String name, String email)` | `SELECT * FROM table WHERE name = ? OR email = ?` |
| `countBy[Field]` | `countByStatus(Integer status)` | `SELECT COUNT(1) FROM table WHERE status = ?` |
| `existsBy[Field]` | `existsByEmail(String email)` | `SELECT CASE WHEN COUNT(1) > 0 THEN TRUE ELSE FALSE END FROM table WHERE email = ?` |
| `deleteBy[Field]` | `deleteByStatus(Integer status)` | `UPDATE table SET del_flg = 1 WHERE status = ?`（逻辑删除） |
| `update[Field]ById` | `updateStatusById(Long id, Integer status)` | `UPDATE table SET status = ? WHERE id = ?` |
| `findBy[Field]OrderBy[Field]Asc/Desc` | `findByNameOrderByCreateTimeDesc()` | `SELECT * FROM table ORDER BY create_time DESC` |

### 支持的条件操作

| 操作 | 方法后缀 | 示例 | 生成条件 |
|------|----------|------|----------|
| 等值查询 | (默认) | `findByStatus` | `WHERE status = ?` |
| 模糊查询 | `Like` | `findByNameLike` | `WHERE name LIKE ?` |
| 包含查询 | `In` | `findByIdIn` | `WHERE id IN (?)` |
| 范围查询 | `Between` | `findByAgeBetween` | `WHERE age BETWEEN ? AND ?` |
| 空值查询 | `IsNull` / `IsNotNull` | `findByEmailIsNull` | `WHERE email IS NULL` |
| 排序 | `OrderBy[Field]Asc/Desc` | `findByNameOrderByAgeDesc` | `ORDER BY age DESC` |

### 使用示例

```java
@Repository
public interface UserRepository extends BaseRepository<User> {
    
    // 查询所有用户
    Flux<User> findAll();
    
    // 根据用户名查询
    Mono<User> findByUserName(String userName);
    
    // 根据状态和邮箱查询
    Flux<User> findByStatusAndEmail(Integer status, String email);
    
    // 统计指定状态的用户数
    Mono<Long> countByStatus(Integer status);
    
    // 检查邮箱是否存在
    Mono<Boolean> existsByEmail(String email);
    
    // 逻辑删除指定状态的用户
    Mono<Long> deleteByStatus(Integer status);
    
    // 更新用户状态
    Mono<Long> updateStatusById(Long id, Integer status);
    
    // 根据用户名排序查询
    Flux<User> findByStatusOrderByCreateTimeDesc(Integer status);
}
```

### 执行顺序

1. 先尝试从XML注册表查找方法
2. 如果找不到，尝试通过方法名约定解析
3. 如果都找不到，抛出异常

## 事务管理

### 事务控制方式对比

| 方式 | 说明 | 适用场景 | 复杂度 |
|------|------|----------|--------|
| **声明式事务** | `@Transactional` 注解 | 简单事务，自动提交/回滚 | 低 |
| **模板事务** | `TransactionTemplate` | 需要自动控制的事务 | 中 |
| **手动事务** | `ManualTransaction` | 需要精细控制的复杂事务 | 高 |

### 声明式事务

```java
@Service
@RequiredArgsConstructor
public class OrderService {
    
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final R2dbcUtil r2dbcUtil;
    
    /**
     * 创建订单（声明式事务）
     */
    @Transactional
    public Mono<Order> createOrder(Order order) {
        return r2dbcUtil.transaction(client -> {
            // 1. 扣减库存
            return productRepository.decreaseStock(order.getProductId(), order.getQuantity())
                    // 2. 创建订单
                    .then(orderRepository.insert(order));
        }).next();
    }
}
```

### 模板事务

```java
@Service
@RequiredArgsConstructor
public class TransactionTemplateService {
    
    private final R2dbcUtil r2dbcUtil;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    
    /**
     * 自动提交/回滚事务
     */
    public Mono<Order> createOrderWithTemplate(Order order) {
        return r2dbcUtil.executeInTransaction(connection -> {
            return inventoryRepository.decrease(connection, order.getProductId(), order.getQuantity())
                    .then(Mono.from(orderRepository.insert(connection, order)));
        });
    }
    
    /**
     * 只读事务
     */
    public Mono<List<User>> findUsersReadOnly() {
        return r2dbcUtil.executeInReadOnlyTransaction(connection -> {
            return userRepository.findAll(connection).collectList();
        });
    }
}
```

### 手动事务

#### ManualTransaction API

| 方法 | 返回类型 | 说明 |
|------|----------|------|
| `commit()` | `Mono<Boolean>` | 提交事务 |
| `rollback()` | `Mono<Boolean>` | 回滚事务 |
| `createSavepoint(String)` | `Mono<Savepoint>` | 创建保存点 |
| `rollbackToSavepoint(Savepoint)` | `Mono<Boolean>` | 回滚到保存点 |
| `releaseSavepoint(Savepoint)` | `Mono<Boolean>` | 释放保存点 |
| `getStatus()` | `TransactionStatus` | 获取事务状态 |
| `isActive()` | `boolean` | 判断事务是否活跃 |
| `isReadOnly()` | `boolean` | 判断是否只读事务 |
| `setReadOnly(boolean)` | `void` | 设置只读模式 |
| `setTimeout(int)` | `void` | 设置超时时间（秒） |

#### 使用示例

```java
@Service
@RequiredArgsConstructor
public class ManualTransactionService {
    
    private final R2dbcUtil r2dbcUtil;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    
    /**
     * 手动事务示例 - 转账操作
     */
    public Mono<Void> transfer(Long fromId, Long toId, BigDecimal amount) {
        return r2dbcUtil.createManualTransaction("transfer")
                .flatMap(transaction -> {
                    Connection connection = ((ManualTransactionImpl) transaction).getConnection();
                    
                    return userRepository.decreaseBalance(connection, fromId, amount)
                            .then(userRepository.increaseBalance(connection, toId, amount))
                            .then(transaction.commit())
                            .onErrorResume(error -> {
                                log.error("转账失败，回滚事务", error);
                                return transaction.rollback().then(Mono.error(error));
                            });
                })
                .then();
    }
    
    /**
     * 使用保存点的事务
     */
    public Mono<Order> createOrderWithSavepoint(Order order) {
        return r2dbcUtil.createManualTransaction("create-order")
                .flatMap(transaction -> {
                    Connection connection = ((ManualTransactionImpl) transaction).getConnection();
                    
                    return inventoryRepository.decrease(connection, order.getProductId(), order.getQuantity())
                            .then(transaction.createSavepoint("after-inventory"))
                            .flatMap(savepoint -> 
                                orderRepository.insert(connection, order)
                                    .onErrorResume(error -> 
                                        transaction.rollbackToSavepoint(savepoint)
                                            .then(Mono.error(error)))
                            )
                            .then(transaction.commit())
                            .onErrorResume(error -> 
                                transaction.rollback().then(Mono.error(error)));
                });
    }
}
```

### 事务生命周期图

```
createTransaction()
    │
    ▼
┌─────────────────┐
│   NEW 状态      │  ← 事务创建
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  ACTIVE 状态    │  ← 事务激活
└────────┬────────┘
         │
    ┌────┴────┐
    │         │
    ▼         ▼
┌────────┐ ┌────────┐
│ commit │ │rollback│
└────┬───┘ └────┬───┘
     │          │
     ▼          ▼
┌─────────────────┐
│ COMMITTED /     │  ← 事务完成
│ ROLLED_BACK     │
└─────────────────┘
```

## 数据库方言支持

### 支持的数据库

| 数据库 | 方言类 | 驱动 | 连接URL格式 |
|--------|--------|------|-------------|
| 达梦 | `DmR2dbcDialect` | dm-r2dbc | `r2dbc:dm://host:port` |
| PostgreSQL | `PostgresDialect` | r2dbc-postgresql | `r2dbc:postgresql://host:port/db` |
| MySQL | `MySqlDialect` | r2dbc-mysql | `r2dbc:mysql://host:port/db` |
| Oracle | `OracleDialect` | oracle-r2dbc | `r2dbc:oracle://host:port/db` |

### 方言特性对比

| 特性 | 达梦 | PostgreSQL | MySQL | Oracle |
|------|------|------------|-------|--------|
| 绑定参数风格 | `?` | `$1,$2` | `?` | `?` |
| LIMIT/OFFSET | ✓ | ✓ | ✓ | ✓ |
| RETURNING | ✓ | ✓ | ✗ | ✓ |
| JSON类型 | ✓ | ✓ | ✓ | ✓ |
| 数组类型 | ✗ | ✓ | ✗ | ✓ |

## API文档

### BaseRepository 接口

```java
public interface BaseRepository<T> {
    
    // 插入
    Mono<T> insert(T entity);
    
    // 删除
    Mono<Long> deleteById(Object id);
    
    // 更新
    Mono<Long> updateById(T entity);
    
    // 查询单个
    Mono<T> selectById(Object id);
    
    // 查询列表
    Flux<T> selectList(QueryWrapper<T> queryWrapper);
    
    // 分页查询
    Mono<Page<T>> selectPage(Pageable pageable, QueryWrapper<T> queryWrapper);
    
    // 查询单个
    Mono<T> selectOne(QueryWrapper<T> queryWrapper);
    
    // 查询总数
    Mono<Long> selectCount(QueryWrapper<T> queryWrapper);
    
    // 是否存在
    Mono<Boolean> exists(QueryWrapper<T> queryWrapper);
}
```

### QueryWrapper 查询构造器

```java
QueryWrapper<SysUser> wrapper = new QueryWrapper<>();

// 等值查询
wrapper.eq("status", 1);

// 不等查询
wrapper.ne("status", 0);

// 大于查询
wrapper.gt("age", 18);

// 大于等于
wrapper.ge("age", 18);

// 小于查询
wrapper.lt("age", 60);

// 小于等于
wrapper.le("age", 60);

// 模糊查询
wrapper.like("name", "张");

// 排序
wrapper.orderByAsc("create_time");
wrapper.orderByDesc("id");

// 分页
wrapper.limit(10);
wrapper.offset(20);
wrapper.page(1, 10); // 第1页，每页10条
```

### R2dbcUtil 工具类

#### 架构说明

R2dbcUtil 采用门面模式，内部组合了多个专业组件：

```
R2dbcUtil (门面)
├── ParameterBinder (参数绑定)
├── SqlLifecycleExecutor (生命周期)
├── R2dbcRowMapper (行映射)
├── R2dbcSqlLogger (日志)
├── R2dbcDataSourceRouter (数据源)
├── R2dbcQueryOperations (查询)
├── R2dbcUpdateOperations (更新)
└── R2dbcTransactionOperations (事务)
```

#### 组件说明

| 组件 | 职责 |
|------|------|
| `ParameterBinder` | SQL参数绑定 |
| `SqlLifecycleExecutor` | SQL生命周期管理（拦截器调用） |
| `R2dbcRowMapper` | 数据库行到实体的映射 |
| `R2dbcSqlLogger` | SQL日志记录 |
| `R2dbcDataSourceRouter` | 多数据源动态路由 |
| `R2dbcQueryOperations` | 查询操作（query、queryOne） |
| `R2dbcUpdateOperations` | 更新操作（update、batch） |
| `R2dbcTransactionOperations` | 事务管理 |

#### 基础操作

```java
@Service
public class MyService {
    
    private final R2dbcUtil r2dbcUtil;
    
    // 原生SQL查询
    public Flux<Map<String, Object>> query(String sql) {
        return r2dbcUtil.query(sql);
    }
    
    // 带参数查询
    public Flux<Map<String, Object>> query(String sql, Map<String, Object> params) {
        return r2dbcUtil.query(sql, params);
    }
    
    // 自定义映射查询
    public <T> Flux<T> query(String sql, BiFunction<Row, RowMetadata, T> mapper) {
        return r2dbcUtil.query(sql, mapper);
    }
    
    // 更新操作
    public Mono<Long> update(String sql, Map<String, Object> params) {
        return r2dbcUtil.update(sql, params);
    }
    
    // 批量执行
    public Mono<Long> batch(List<String> sqlList) {
        return r2dbcUtil.batch(sqlList);
    }
    
    // 实体操作
    public <T> Mono<T> insert(T entity) {
        return r2dbcUtil.insert(entity);
    }
    
    public <T> Mono<T> save(T entity) {
        return r2dbcUtil.save(entity);
    }
    
    public <T> Mono<T> delete(T entity) {
        return r2dbcUtil.delete(entity);
    }
}
```

#### 数据源操作

```java
@Service
public class DataSourceService {
    
    private final R2dbcUtil r2dbcUtil;
    
    // 指定数据源执行Mono操作
    public <T> Mono<T> dataSource(String dataSource, Mono<T> publisher) {
        return r2dbcUtil.dataSource(dataSource, publisher);
    }
    
    // 指定数据源执行Flux操作
    public <T> Flux<T> dataSource(String dataSource, Flux<T> publisher) {
        return r2dbcUtil.dataSource(dataSource, publisher);
    }
}
```

## 架构设计

### 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        Application Layer                        │
├─────────────────────────────────────────────────────────────────┤
│  BaseRepository  │  @Repository  │  XML Mapping  │  QueryWrapper │
├─────────────────────────────────────────────────────────────────┤
│                     Repository Proxy Layer                      │
│  RepositoryProxyMethodInterceptor  │  CustomMethodResolver      │
├─────────────────────────────────────────────────────────────────┤
│                       Core Layer (R2dbcUtil)                    │
├─────────────┬─────────────┬─────────────┬───────────────────────┤
│   Query     │   Update    │ Transaction │      DataSource       │
│ Operations  │ Operations  │ Operations  │        Router         │
├─────────────┴─────────────┴─────────────┴───────────────────────┤
│                    Infrastructure Layer                          │
├─────────────┬─────────────┬─────────────┬───────────────────────┤
│  Parameter  │    SQL      │    Row      │      SQL              │
│   Binder    │  Lifecycle  │   Mapper    │      Logger           │
│             │  Executor   │             │                       │
├─────────────┴─────────────┴─────────────┴───────────────────────┤
│                    Interceptor Layer                             │
├─────────────────────────────┬───────────────────────────────────┤
│  SqlLifecycleInterceptor    │ ReactiveSqlLifecycleInterceptor   │
│       (Sync)                │         (Async)                   │
├─────────────────────────────┴───────────────────────────────────┤
│                    Database Layer (R2DBC SPI)                    │
├─────────────┬─────────────┬─────────────┬───────────────────────┤
│   达梦      │ PostgreSQL  │    MySQL    │      Oracle           │
└─────────────┴─────────────┴─────────────┴───────────────────────┘
```

### 组件职责

| 层级 | 组件 | 职责 |
|------|------|------|
| **应用层** | BaseRepository | 基础CRUD接口 |
| | @Repository | 仓储接口标识 |
| | XML Mapping | SQL映射配置 |
| | QueryWrapper | 查询条件构造 |
| **代理层** | RepositoryProxyMethodInterceptor | 方法拦截与分发 |
| | CustomMethodResolver | 方法名约定解析 |
| **核心层** | R2dbcUtil | 统一API入口（门面模式） |
| | R2dbcQueryOperations | 查询操作 |
| | R2dbcUpdateOperations | 更新操作 |
| | R2dbcTransactionOperations | 事务管理 |
| | R2dbcDataSourceRouter | 数据源路由 |
| **基础设施层** | ParameterBinder | SQL参数绑定 |
| | SqlLifecycleExecutor | 生命周期管理 |
| | R2dbcRowMapper | 行映射与转换 |
| | R2dbcSqlLogger | SQL日志记录 |
| **拦截器层** | SqlLifecycleInterceptor | 同步拦截器 |
| | ReactiveSqlLifecycleInterceptor | 异步拦截器 |
| **数据库层** | R2DBC SPI | 数据库驱动接口 |

## 常见问题

### Q1: 如何启用WCDK R2DBC？

在 `application.yml` 中配置：

```yaml
wcdk:
  r2dbc:
    enabled: true
```

并在启动类上添加注解：

```java
@EnableWcdkR2dbcRepositories(basePackages = "com.example.repository")
```

### Q2: 如何配置多数据源？

参考 [多数据源配置](#多数据源配置) 章节。

### Q3: XML映射文件放在哪里？

默认位置为 `classpath*:repository/**/*.xml`，可通过 `wcdk.r2dbc.mapper-locations` 配置。

### Q4: 如何使用 resultMap？

参考 [XML映射配置](#xml映射配置) 章节中的 `resultMap` 部分。

### Q5: 手动事务与声明式事务的区别？

| 特性 | 手动事务 | 声明式事务 |
|------|----------|------------|
| 控制粒度 | 精细控制 | 粗粒度控制 |
| 提交/回滚 | 手动调用 | 自动 |
| 保存点支持 | 支持 | 不支持 |
| 适用场景 | 复杂业务流程 | 简单CRUD操作 |

### Q6: 如何调试SQL问题？

1. 开启SQL日志：`wcdk.r2dbc.sql-log-enabled=true`
2. 使用SQL拦截器记录执行详情
3. 监控慢SQL：实现 `SqlLifecycleInterceptor` 接口

## 许可证

[MIT License](LICENSE)

## 贡献

欢迎提交 Issue 和 Pull Request！

## 联系方式

- 作者：WCDK
- 邮箱：wcdk1024@gmail.com
