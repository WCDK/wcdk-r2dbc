package com.wcdk.r2dbc.config;

import com.wcdk.r2dbc.repository.RepositoryProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.FactoryBean;

/**
 * 仓储代理 Bean 工厂。
 *
 * @author WCDK
 * @date 2026/7/21
 * @version 1.0
 **/
public class WcdkR2dbcRepositoryFactoryBean<T> implements FactoryBean<T> {

    private final Class<T> repositoryInterface;

    private final ObjectProvider<RepositoryProxyFactory> repositoryProxyFactory;

    private volatile T repository;

    public WcdkR2dbcRepositoryFactoryBean(Class<T> repositoryInterface, ObjectProvider<RepositoryProxyFactory> repositoryProxyFactory) {
        this.repositoryInterface = repositoryInterface;
        this.repositoryProxyFactory = repositoryProxyFactory;
    }

    @Override
    public T getObject() {
        T current = repository;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (repository == null) {
                repository = repositoryInterface.cast(repositoryProxyFactory.getObject().create(repositoryInterface));
            }
            return repository;
        }
    }

    @Override
    public Class<?> getObjectType() {
        return repositoryInterface;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }
}
