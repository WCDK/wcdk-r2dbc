package com.wcdk.r2dbc.repository;

import java.lang.reflect.Method;
import java.util.Objects;

/***
 * Repository 方法未找到对应编译计划异常。
 * @author wcdk
 **/
final class UnsupportedRepositoryMethodException extends IllegalStateException {

    /***
     * 创建 Repository 方法未找到计划异常。
     *
     * @param method 未找到计划的方法
     * @author wcdk
     **/
    UnsupportedRepositoryMethodException(Method method) {
        super("不支持的 Repository 方法：" + Objects.requireNonNull(method, "method"));
    }
}