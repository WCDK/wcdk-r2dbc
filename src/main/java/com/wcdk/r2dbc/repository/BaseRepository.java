package com.wcdk.r2dbc.repository;

import com.wcdk.r2dbc.query.QueryWrapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/***
 * 基础响应式仓储接口常用操作。
 * @author wcdk
 **/
public interface BaseRepository<T> {

    /***
     * 插入并返回带主键的实体；record 自动生成主键时返回新实例，调用方需使用返回值。
     * @author wcdk
     **/
    Mono<T> insert(T entity);

    Mono<Long> deleteById(Object id);

    /*** 按 ID 更新非 null 字段，兼容历史行为；返回实际影响行数，无字段可更新时返回 0。 @author wcdk ***/
    Mono<Long> updateById(T entity);

    /*** 按 ID 更新非 null 字段，null 表示保留原值；返回实际影响行数。 @author wcdk ***/
    Mono<Long> updateByIdIgnoringNulls(T entity);

    /***
     * 按 ID 更新全部非主键持久化字段，null 表示将对应列置为 NULL。
     * 包括逻辑删除字段，调用方应提供需保留的字段值；无可更新字段时返回 0。
     * @author wcdk
     ***/
    Mono<Long> updateByIdIncludingNulls(T entity);

    Mono<T> selectById(Object id);

    Flux<T> findAll();

    Flux<T> selectList(QueryWrapper<T> queryWrapper);

    Mono<Page<T>> selectPage(Pageable pageable, QueryWrapper<T> queryWrapper);

    default Mono<Page<T>> selectPage(Pageable pageable) {
        return selectPage(pageable, new QueryWrapper<>());
    }

    Mono<T> selectOne(QueryWrapper<T> queryWrapper);

    Mono<Long> selectCount(QueryWrapper<T> queryWrapper);

    Mono<Boolean> exists(QueryWrapper<T> queryWrapper);
}
