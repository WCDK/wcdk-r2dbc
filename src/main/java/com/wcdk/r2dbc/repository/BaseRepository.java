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

    Mono<T> insert(T entity);

    Mono<Long> deleteById(Object id);

    Mono<Long> updateById(T entity);

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
