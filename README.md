# WCDK-R2DBC

> 基于 Spring Boot 的响应式数据库访问框架，提供多数据源支持、动态路由、SQL生命周期拦截等特性。

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21+-green.svg)](https://www.oracle.com/java/)
[![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5+-brightgreen.svg)](https://spring.io/projects/spring-boot)

## 目录

- [特性](#特性)
- [架构设计](#架构设计)
- [快速开始](#快速开始)
- [配置说明](#配置说明)
- [多数据源配置](#多数据源配置)
- [SQL生命周期拦截器](#sql生命周期拦截器)
- [数据库方言支持](#数据库方言支持)
- [XML映射配置](#xml映射配置)
- [API文档](#api文档)
- [示例代码](#示例代码)

## 特性

### 核心特性

| 特性 | 说明 |
|------|------|
| **多数据源支持** | 支持配置多个数据源，动态路由切换 |
| **响应式编程** | 基于 Project Reactor 的完全非阻塞响应式模型 |
| **多数据库兼容** | 支持达梦、PostgreSQL、MySQL、Oracle |
| **连接池管理** | 内置 r2dbc-pool 连接池，支持精细化配置 |
| **事务管理** | 支持响应式事务，保证数据一致性 |
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

## 架构设计

```
┌─────────────────────────────────────────────────────────────────┐
│                         应用层 (Application)                     │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐ │
│  │  Repository接口  │  │   XML映射文件   │  │  R2dbcUtil工具  │ │
│  └────────┬────────┘  └────────┬────────┘  └────────┬────────┘ │
└───────────┼────────────────────┼────────────────────┼───────────┘
            │                    │                    │
            ▼                    ▼                    ▼
┌─────────────────────────────────────────────────────────────────┐
│                       核心层 (Core)                              │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │           RepositoryProxyMethodInterceptor              │   │
│  │           (SQL生成 + 生命周期拦截)                        │   │
│  └────────────────────────┬────────────────────────────────┘   │
│                           │                                     │
│  ┌────────────────────────▼────────────────────────────────┐   │
│  │           SqlLifecycleInterceptorChain                   │   │
│  │           (SQL生命周期拦截器链)                            │   │
│  └────────────────────────┬────────────────────────────────┘   │
└───────────────────────────┼─────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                     数据访问层 (Data Access)                     │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐ │
│  │    R2dbcUtil    │  │ EntityTemplate  │  │ DatabaseClient  │ │
│  └────────┬────────┘  └────────┬────────┘  └────────┬────────┘ │
└───────────┼────────────────────┼────────────────────┼───────────┘
            │                    │                    │
            ▼                    ▼                    ▼
┌─────────────────────────────────────────────────────────────────┐
│                      数据源层 (DataSource)                       │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │        DynamicRoutingConnectionFactory                  │   │
│  │        (动态路由连接工厂)                                 │   │
│  └────────────────────────┬────────────────────────────────┘   │
│                           │                                     │
│  ┌───────────┐ ┌───────────┐ ┌───────────┐ ┌───────────┐     │
│  │   达梦    │ │ PostgreSQL│ │   MySQL   │ │  Oracle   │     │
│  └───────────┘ └───────────┘ └───────────┘ └───────────┘     │
└─────────────────────────────────────────────────────────────────┘
```

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
| `wcdk.r2dbc.sql-log-enabled` | boolean | false | 启用SQL日志 |
| `wcdk.r2dbc.logic-delete-value` | int | 1 | 逻辑删除值 |
| `wcdk.r2dbc.logic-not-delete-value` | int | 0 | 未删除值 |
| `wcdk.r2dbc.snowflake-id` | boolean | false | 启用雪花ID |

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

### 实现自定义拦截器

```java
@Component
public class MySqlInterceptor implements SqlLifecycleInterceptor {
    
    private static final Logger log = LoggerFactory.getLogger(MySqlInterceptor.class);
    
    @Override
    public void beforeCompile(SqlExecutionContext context) {
        // SQL编译前：可以进行参数预处理、权限校验等
        log.debug("准备编译SQL - 方法: {}", context.getMethod().getName());
    }
    
    @Override
    public void afterCompile(SqlExecutionContext context) {
        // SQL编译后：可以进行SQL审计、日志记录、SQL修改等
        log.debug("SQL编译完成: {}", context.getSql());
        
        // 可以修改SQL
        // context.setSql(context.getSql() + " /* audit */");
        
        // 可以跳过执行
        // context.setSkipped(true);
    }
    
    @Override
    public void beforeExecute(SqlExecutionContext context) {
        // SQL执行前：可以进行最终校验、性能计时开始等
        log.debug("准备执行SQL");
    }
    
    @Override
    public void afterExecute(SqlExecutionContext context) {
        // SQL执行后：可以进行性能计时结束、结果处理、异常处理等
        long durationMs = context.getDuration() / 1_000_000;
        
        if (context.hasError()) {
            log.error("SQL执行失败 ({}ms): {}", durationMs, context.getSql(), context.getError());
        } else {
            log.info("SQL执行成功 ({}ms): {}", durationMs, context.getSql());
        }
    }
    
    @Override
    public int getOrder() {
        return 0; // 数值越小越先执行
    }
}
```

### 性能监控拦截器

```java
@Component
public class PerformanceInterceptor implements SqlLifecycleInterceptor {
    
    private static final long SLOW_SQL_THRESHOLD_MS = 1000;
    
    @Override
    public void afterExecute(SqlExecutionContext context) {
        long durationMs = context.getDuration() / 1_000_000;
        
        if (durationMs > SLOW_SQL_THRESHOLD_MS) {
            // 记录慢SQL告警
            AlertService.alert("慢SQL检测", context.getSql(), durationMs);
        }
    }
    
    @Override
    public int getOrder() {
        return Integer.MAX_VALUE;
    }
}
```

### SQL审计拦截器

```java
@Component
public class AuditInterceptor implements SqlLifecycleInterceptor {
    
    @Autowired
    private AuditLogService auditLogService;
    
    @Override
    public void beforeCompile(SqlExecutionContext context) {
        // 记录SQL操作开始
        AuditLog log = new AuditLog();
        log.setMethod(context.getMethod().getName());
        log.setRepository(context.getRepositoryInterface().getSimpleName());
        log.setStartTime(LocalDateTime.now());
        auditLogService.save(log);
    }
    
    @Override
    public void afterExecute(SqlExecutionContext context) {
        // 记录SQL操作完成
        AuditLog log = auditLogService.findByMethod(context.getMethod().getName());
        log.setSql(context.getSql());
        log.setDuration(context.getDuration());
        log.setSuccess(!context.hasError());
        auditLogService.update(log);
    }
    
    @Override
    public int getOrder() {
        return -100;
    }
}
```

### 内置拦截器

| 拦截器 | 说明 | 执行顺序 |
|--------|------|----------|
| `SqlAuditInterceptor` | SQL审计日志 | -100 |
| `SqlPerformanceInterceptor` | 性能监控 | MAX_VALUE |

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

## XML映射配置

### 配置说明

XML映射文件用于定义复杂的SQL语句，支持 `resultType` 和 `resultMap` 两种结果映射方式。

### 文件位置

在 `application.yml` 中配置XML文件位置：

```yaml
wcdk:
  r2dbc:
    enabled: true
    mapper-locations: classpath*:mapper/**/*.xml
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

#### 事务操作

```java
@Service
public class TransactionService {
    
    private final R2dbcUtil r2dbcUtil;
    
    // 声明式事务（使用TransactionalOperator）
    public <T> Flux<T> transaction(Function<DatabaseClient, Publisher<T>> action) {
        return r2dbcUtil.transaction(action);
    }
    
    // 指定数据源的事务
    public <T> Flux<T> transaction(String dataSource, Function<DatabaseClient, Publisher<T>> action) {
        return r2dbcUtil.transaction(dataSource, action);
    }
    
    // 创建手动事务
    public Mono<ManualTransaction> createManualTransaction() {
        return r2dbcUtil.createManualTransaction();
    }
    
    // 创建带名称的手动事务
    public Mono<ManualTransaction> createManualTransaction(String name) {
        return r2dbcUtil.createManualTransaction(name);
    }
    
    // 使用事务模板执行（自动提交/回滚）
    public <T> Mono<T> executeInTransaction(Function<Connection, Publisher<T>> action) {
        return r2dbcUtil.executeInTransaction(action);
    }
    
    // 使用事务模板执行（带事务名称）
    public <T> Mono<T> executeInTransaction(String name, Function<Connection, Publisher<T>> action) {
        return r2dbcUtil.executeInTransaction(name, action);
    }
    
    // 使用只读事务模板执行
    public <T> Mono<T> executeInReadOnlyTransaction(Function<Connection, Publisher<T>> action) {
        return r2dbcUtil.executeInReadOnlyTransaction(action);
    }
    
    // 获取事务模板
    public TransactionTemplate getTransactionTemplate() {
        return r2dbcUtil.getTransactionTemplate();
    }
    
    // 获取事务管理器
    public TransactionManager getTransactionManager() {
        return r2dbcUtil.getTransactionManager();
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

#### 其他操作

```java
@Service
public class OtherService {
    
    private final R2dbcUtil r2dbcUtil;
    
    // 获取DatabaseClient
    public DatabaseClient getDatabaseClient() {
        return r2dbcUtil.databaseClient();
    }
    
    // 获取EntityTemplate
    public R2dbcEntityTemplate getEntityTemplate() {
        return r2dbcUtil.entityTemplate();
    }
    
    // 实体映射
    public <T> T map(Row row, Class<T> entityClass) {
        return r2dbcUtil.map(row, entityClass);
    }
    
    // 值转换
    public Object convertValue(Object value, Class<?> targetType) {
        return r2dbcUtil.convertValue(value, targetType);
    }
}
```

## 示例代码

### 完整CRUD示例

```java
@Repository
public interface UserRepository extends BaseRepository<User> {
    
    @Select("SELECT * FROM user WHERE email = #{email}")
    Mono<User> findByEmail(@Param("email") String email);
    
    @Insert("INSERT INTO user (name, email) VALUES (#{name}, #{email})")
    Mono<Void> insertUser(@Param("name") String name, @Param("email") String email);
}

@Service
@RequiredArgsConstructor
public class UserService {
    
    private final UserRepository userRepository;
    
    // 新增
    public Mono<User> createUser(User user) {
        user.setCreateTime(LocalDateTime.now());
        return userRepository.insert(user);
    }
    
    // 修改
    public Mono<Long> updateUser(User user) {
        return userRepository.updateById(user);
    }
    
    // 删除
    public Mono<Long> deleteUser(Long id) {
        return userRepository.deleteById(id);
    }
    
    // 查询单个
    public Mono<User> getUser(Long id) {
        return userRepository.selectById(id);
    }
    
    // 条件查询
    public Flux<User> getUsersByStatus(Integer status) {
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.eq("status", status);
        return userRepository.selectList(wrapper);
    }
    
    // 分页查询
    public Mono<Page<User>> getUserPage(int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return userRepository.selectPage(pageable);
    }
    
    // 复杂查询
    public Flux<User> complexQuery(String name, Integer status) {
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        if (name != null) {
            wrapper.like("name", name);
        }
        if (status != null) {
            wrapper.eq("status", status);
        }
        wrapper.orderByDesc("create_time");
        return userRepository.selectList(wrapper);
    }
}
```

### XML映射示例

```xml
<!-- UserMapper.xml -->
<repository namespace="com.example.repository.UserRepository">
    
    <!-- 使用 resultType 指定返回类型 -->
    <select id="findByName" resultType="com.example.entity.User">
        SELECT * FROM user WHERE name = #{name}
    </select>
    
    <!-- 使用 resultMap 自定义列映射 -->
    <resultMap id="userResultMap" type="com.example.entity.User">
        <id column="user_id" property="id"/>
        <result column="user_name" property="name"/>
        <result column="user_email" property="email"/>
    </resultMap>
    
    <select id="findById" resultMap="userResultMap">
        SELECT * FROM user WHERE id = #{id}
    </select>
    
    <!-- 带鉴别器的 resultMap -->
    <resultMap id="vehicleResultMap" type="com.example.entity.Vehicle">
        <id column="vehicle_id" property="id"/>
        <result column="vehicle_type" property="type"/>
        <discriminator column="vehicle_type">
            <case value="car" resultMap="carResultMap"/>
            <case value="truck" resultMap="truckResultMap"/>
        </discriminator>
    </resultMap>
    
    <resultMap id="carResultMap" type="com.example.entity.Car">
        <id column="vehicle_id" property="id"/>
        <result column="seat_count" property="seatCount"/>
    </resultMap>
    
    <resultMap id="truckResultMap" type="com.example.entity.Truck">
        <id column="vehicle_id" property="id"/>
        <result column="payload_capacity" property="payloadCapacity"/>
    </resultMap>
    
    <select id="findVehicle" resultMap="vehicleResultMap">
        SELECT * FROM vehicle WHERE id = #{id}
    </select>
    
    <!-- 基础CRUD操作 -->
    <insert id="insertUser">
        INSERT INTO user (name, email) VALUES (#{name}, #{email})
    </insert>
    
    <update id="updateStatus">
        UPDATE user SET status = #{status} WHERE id = #{id}
    </update>
    
    <delete id="deleteByIds">
        DELETE FROM user WHERE id IN 
        <foreach collection="ids" item="id" open="(" separator="," close=")">
            #{id}
        </foreach>
    </delete>
    
</repository>
```

### XML映射详解

#### resultType

`resultType` 用于指定查询结果的返回类型。支持以下类型：

- **实体类**：自动映射列名到字段名（支持驼峰命名转换）
- **基本类型**：`String`、`Integer`、`Long`、`Boolean` 等
- **`Map`**：返回 `Map<String, Object>` 格式

```xml
<!-- 实体类映射 -->
<select id="findById" resultType="com.example.entity.User">
    SELECT * FROM user WHERE id = #{id}
</select>

<!-- 基本类型映射 -->
<select id="countByStatus" resultType="java.lang.Long">
    SELECT COUNT(*) FROM user WHERE status = #{status}
</select>
```

#### resultMap

`resultMap` 用于自定义列名到属性名的映射关系，适用于列名与字段名不一致的场景。

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

#### discriminator（鉴别器）

`discriminator` 用于根据列值选择不同的 `resultMap`，实现多态映射。

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

#### 使用示例

```java
@Repository
public interface UserRepository extends BaseRepository<User> {
    
    // XML中定义的方法
    Mono<User> findByName(@Param("name") String name);
    
    Mono<User> findById(@Param("id") Long id);
}

@Service
@RequiredArgsConstructor
public class UserService {
    
    private final UserRepository userRepository;
    
    public Mono<User> findUserByName(String name) {
        return userRepository.findByName(name);
    }
    
    public Mono<User> findUserById(Long id) {
        return userRepository.findById(id);
    }
}
```

### 事务示例

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
    
    /**
     * 转账操作（声明式事务）
     */
    public Mono<Void> transfer(Long fromId, Long toId, BigDecimal amount) {
        return r2dbcUtil.transaction(client -> {
            return accountRepository.decrease(fromId, amount)
                    .then(accountRepository.increase(toId, amount))
                    .then();
        }).then();
    }
}
```

## 手动事务管理

### 事务控制方式对比

| 方式 | 说明 | 适用场景 | 复杂度 |
|------|------|----------|--------|
| **声明式事务** | `@Transactional` 注解 | 简单事务，自动提交/回滚 | 低 |
| **模板事务** | `TransactionTemplate` | 需要自动控制的事务 | 中 |
| **手动事务** | `ManualTransaction` | 需要精细控制的复杂事务 | 高 |

### ManualTransaction API 详解

#### 核心方法

| 方法 | 返回类型 | 说明 |
|------|----------|------|
| `commit()` | `Mono<Boolean>` | 提交事务，返回是否成功 |
| `rollback()` | `Mono<Boolean>` | 回滚事务，返回是否成功 |
| `getStatus()` | `TransactionStatus` | 获取当前事务状态 |
| `isActive()` | `boolean` | 判断事务是否活跃 |
| `isCompleted()` | `boolean` | 判断事务是否已完成 |
| `isReadOnly()` | `boolean` | 判断是否只读事务 |
| `setReadOnly(boolean)` | `void` | 设置只读模式 |
| `setTimeout(int)` | `void` | 设置超时时间（秒） |
| `getName()` | `string` | 获取事务名称 |
| `setName(String)` | `void` | 设置事务名称 |

#### 保存点方法

| 方法 | 返回类型 | 说明 |
|------|----------|------|
| `createSavepoint(String)` | `Mono<Savepoint>` | 创建保存点 |
| `rollbackToSavepoint(Savepoint)` | `Mono<Boolean>` | 回滚到保存点 |
| `releaseSavepoint(Savepoint)` | `Mono<Boolean>` | 释放保存点 |

#### 事务状态枚举

| 状态 | 说明 |
|------|------|
| `NEW` | 新建事务 |
| `ACTIVE` | 事务已激活 |
| `COMMITTED` | 事务已提交 |
| `ROLLED_BACK` | 事务已回滚 |
| `COMPLETED` | 事务已完成 |
| `READ_ONLY` | 事务标记为仅读取 |
| `TIMEOUT` | 事务超时 |
| `FAILED` | 事务异常 |

### 使用示例

#### 1. 基础手动事务

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
     * 手动事务示例 - 批量插入
     */
    public Mono<List<User>> batchInsert(List<User> users) {
        return r2dbcUtil.createManualTransaction("batch-insert")
                .flatMap(transaction -> {
                    Connection connection = ((ManualTransactionImpl) transaction).getConnection();
                    
                    return Flux.fromIterable(users)
                            .concatMap(user -> userRepository.insert(connection, user))
                            .collectList()
                            .flatMap(result -> transaction.commit().thenReturn(result))
                            .onErrorResume(error -> 
                                transaction.rollback().then(Mono.error(error)));
                });
    }
}
```

#### 2. 带保存点的事务

```java
@Service
@RequiredArgsConstructor
public class SavepointService {
    
    private final R2dbcUtil r2dbcUtil;
    private final OrderRepository orderRepository;
    private final InventoryRepository inventoryRepository;
    
    /**
     * 创建订单（使用保存点）
     * 扣减库存成功后，如果创建订单失败，只回滚订单操作，保留库存扣减
     */
    public Mono<Order> createOrderWithSavepoint(Order order) {
        return r2dbcUtil.createManualTransaction("create-order")
                .flatMap(transaction -> {
                    Connection connection = ((ManualTransactionImpl) transaction).getConnection();
                    
                    return inventoryRepository.decrease(connection, order.getProductId(), order.getQuantity())
                            // 创建保存点
                            .then(transaction.createSavepoint("after-inventory"))
                            .flatMap(savepoint -> 
                                orderRepository.insert(connection, order)
                                    .onErrorResume(error -> 
                                        // 回滚到保存点，只回滚订单操作
                                        transaction.rollbackToSavepoint(savepoint)
                                            .then(Mono.error(error)))
                            )
                            .then(transaction.commit())
                            .onErrorResume(error -> 
                                transaction.rollback().then(Mono.error(error)));
                });
    }
    
    /**
     * 复杂业务流程（多个保存点）
     */
    public Mono<Void> complexBusinessProcess() {
        return r2dbcUtil.createManualTransaction("complex-process")
                .flatMap(transaction -> {
                    Connection connection = ((ManualTransactionImpl) transaction).getConnection();
                    
                    return step1(connection)
                        .then(transaction.createSavepoint("step1-done"))
                        .flatMap(sp1 -> step2(connection)
                            .onErrorResume(e -> transaction.rollbackToSavepoint(sp1).then(Mono.error(e)))
                        )
                        .then(transaction.createSavepoint("step2-done"))
                        .flatMap(sp2 -> step3(connection)
                            .onErrorResume(e -> transaction.rollbackToSavepoint(sp2).then(Mono.error(e)))
                        )
                        .then(transaction.commit())
                        .onErrorResume(e -> transaction.rollback().then(Mono.error(e)));
                })
                .then();
    }
}
```

#### 3. 事务模板使用

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
     * 带事务名称的事务
     */
    public Mono<Void> transferWithTemplate(Long fromId, Long toId, BigDecimal amount) {
        return r2dbcUtil.executeInTransaction("transfer-operation", connection -> {
            return accountRepository.decrease(connection, fromId, amount)
                    .then(Mono.from(accountRepository.increase(connection, toId, amount)))
                    .then();
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
    
    /**
     * 执行多个操作的事务
     */
    public Mono<List<Object>> executeMultipleOperations() {
        return r2dbcUtil.getTransactionTemplate()
            .executeInTransaction(
                connection -> userRepository.findAll(connection).collectList(),
                connection -> orderRepository.findAll(connection).collectList()
            )
            .collectList();
    }
}
```

#### 4. 条件事务

```java
@Service
@RequiredArgsConstructor
public class ConditionalTransactionService {
    
    private final R2dbcUtil r2dbcUtil;
    
    /**
     * 根据条件决定是否提交事务
     */
    public Mono<Boolean> conditionalCommit(boolean shouldCommit) {
        return r2dbcUtil.createManualTransaction("conditional")
                .flatMap(transaction -> {
                    Connection connection = ((ManualTransactionImpl) transaction).getConnection();
                    
                    return someOperation(connection)
                            .then(Mono.defer(() -> {
                                if (shouldCommit) {
                                    return transaction.commit();
                                } else {
                                    return transaction.rollback();
                                }
                            }));
                });
    }
    
    /**
     * 部分提交事务
     */
    public Mono<Void> partialCommit() {
        return r2dbcUtil.createManualTransaction("partial")
                .flatMap(transaction -> {
                    Connection connection = ((ManualTransactionImpl) transaction).getConnection();
                    
                    return operation1(connection)
                            .then(transaction.commit())  // 提交操作1
                            .then(r2dbcUtil.createManualTransaction("partial-2"))
                            .flatMap(t -> {
                                Connection conn = ((ManualTransactionImpl) t).getConnection();
                                return operation2(conn)
                                    .then(t.commit());
                            });
                })
                .then();
    }
}
```

### 事务管理器 API

```java
@Service
@RequiredArgsConstructor
public class TransactionManagerService {
    
    private final TransactionManager transactionManager;
    
    /**
     * 创建事务
     */
    public Mono<ManualTransaction> createTransaction() {
        return transactionManager.createTransaction("my-transaction");
    }
    
    /**
     * 创建只读事务
     */
    public Mono<ManualTransaction> createReadOnlyTransaction() {
        return transactionManager.createReadOnlyTransaction();
    }
    
    /**
     * 创建带超时的事务
     */
    public Mono<ManualTransaction> createTimedTransaction(int timeoutSeconds) {
        return transactionManager.createTransaction(timeoutSeconds);
    }
    
    /**
     * 使用事务模板
     */
    public Mono<Result> executeWithTemplate() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        return template.execute(connection -> {
            // 数据库操作
            return Mono.just(result);
        });
    }
}
```

### 事务配置

```yaml
wcdk:
  r2dbc:
    enabled: true
    # 事务相关配置
    transaction:
      enabled: true
      default-timeout: 30        # 默认超时时间（秒）
      default-read-only: false   # 默认是否只读
      max-retry: 3              # 最大重试次数
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

### 保存点生命周期图

```
ACTIVE 事务
    │
    ▼
createSavepoint("savepoint1")
    │
    ▼
┌─────────────────┐
│  操作1          │
└────────┬────────┘
         │
         ▼
createSavepoint("savepoint2")
    │
    ▼
┌─────────────────┐
│  操作2          │
└────────┬────────┘
         │
    ┌────┴────────────┐
    │                 │
    ▼                 ▼
┌────────────┐ ┌────────────────┐
│ commit     │ │rollbackTo      │
│ (所有操作) │ │Savepoint("s1") │
└────────────┘ └────────────────┘
                  │
                  ▼
            ┌─────────────────┐
            │ 操作1保留       │
            │ 操作2回滚       │
            └─────────────────┘
```

### 常见问题

#### Q1: 手动事务与声明式事务的区别？

| 特性 | 手动事务 | 声明式事务 |
|------|----------|------------|
| 控制粒度 | 精细控制 | 粗粒度控制 |
| 提交/回滚 | 手动调用 | 自动 |
| 保存点支持 | 支持 | 不支持 |
| 事务传播 | 需要手动管理 | 自动管理 |
| 适用场景 | 复杂业务流程 | 简单CRUD操作 |

#### Q2: 何时使用保存点？

- 执行多个步骤的业务流程
- 需要部分回滚的场景
- 需要保留前面操作结果的场景

#### Q3: 如何处理嵌套事务？

```java
// 使用保存点实现嵌套事务效果
r2dbcUtil.createManualTransaction("outer")
    .flatMap(outer -> {
        return operation1(outer.getConnection())
            .then(outer.createSavepoint("nested"))
            .flatMap(sp -> 
                r2dbcUtil.createManualTransaction("inner")
                    .flatMap(inner -> 
                        operation2(inner.getConnection())
                            .then(inner.commit())
                            .onErrorResume(e -> inner.rollback().then(Mono.error(e)))
                    )
                    .onErrorResume(e -> 
                        outer.rollbackToSavepoint(sp).then(Mono.error(e)))
            )
            .then(outer.commit())
            .onErrorResume(e -> outer.rollback().then(Mono.error(e)));
    });
```

#### Q4: 事务超时如何处理？

```java
r2dbcUtil.createManualTransaction("timeout-example")
    .doOnSuccess(transaction -> transaction.setTimeout(30)) // 30秒超时
    .flatMap(transaction -> {
        return longRunningOperation(transaction.getConnection())
            .then(transaction.commit())
            .onErrorResume(e -> transaction.rollback().then(Mono.error(e)));
    });
```

#### Q5: 如何调试事务问题？

1. 开启SQL日志：`wcdk.r2dbc.sql-log-enabled=true`
2. 使用事务名称标识：`createTransaction("my-transaction")`
3. 监控事务状态：`transaction.getStatus()`
4. 使用拦截器记录事务操作

## 许可证

[MIT License](LICENSE)

## 贡献

欢迎提交 Issue 和 Pull Request！

## 联系方式

- 作者：WCDK
- 邮箱：wcdk1024@gmail.com
