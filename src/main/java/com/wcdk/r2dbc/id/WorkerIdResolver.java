package com.wcdk.r2dbc.id;

/**
 * 解析雪花节点编号；注册中心实现应先取得独占租约，并在生成器使用期间维持租约。
 * @author wcdk
 */
@FunctionalInterface
public interface WorkerIdResolver {
    long resolveWorkerId();
}
