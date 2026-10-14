package com.wcdk.r2dbc.execution;

import java.util.*;
import java.lang.ref.WeakReference;

/** 为接口兼容默认方法复用映射计划，弱键与弱值不持有外部操作器；转换委托其扩展点。 */
final class RepositoryMappingCache {
    private static final Map<RepositoryOperations,WeakReference<R2dbcRowMapper>> CACHE = new WeakHashMap<>();
    static synchronized R2dbcRowMapper mapper(RepositoryOperations operations) {
        var reference=CACHE.get(operations);var mapper=reference==null?null:reference.get();
        if(mapper==null){
            mapper=new R2dbcRowMapper(List.of(new R2dbcValueConverter(){
                public boolean supports(Class<?> source,Class<?> target){return true;}
                public Object convert(Object value,Class<?> target){return operations.convertValue(value,target);}
            }));
            CACHE.put(operations,new WeakReference<>(mapper));
        }
        return mapper;
    }
}
