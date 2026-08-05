package com.wcdk.r2dbc.core;

import com.wcdk.r2dbc.BaseRepository;
import com.wcdk.r2dbc.R2dbcUtil;
import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import com.wcdk.r2dbc.config.WcdkSpringR2dbcProperties;
import com.wcdk.r2dbc.core.metadata.RepositoryMetadata;
import com.wcdk.r2dbc.core.xml.RepositoryXmlRegistry;
import org.springframework.aop.framework.ProxyFactory;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * 仓储代理工厂。
 *
 * @author WCDK
 * @date 2026/7/21
 * @version 1.0
 **/
public class RepositoryProxyFactory {

    private final R2dbcUtil r2dbcUtil;

    private final WcdkR2dbcProperties properties;

    private final RepositoryXmlRegistry repositoryXmlRegistry;

    private final WcdkSpringR2dbcProperties springR2dbcProperties;

    public RepositoryProxyFactory(R2dbcUtil r2dbcUtil, WcdkR2dbcProperties properties, RepositoryXmlRegistry repositoryXmlRegistry, WcdkSpringR2dbcProperties springR2dbcProperties) {
        this.r2dbcUtil = r2dbcUtil;
        this.properties = properties;
        this.repositoryXmlRegistry = repositoryXmlRegistry;
        this.springR2dbcProperties = springR2dbcProperties;
    }

    public Object create(Class<?> repositoryInterface) {
        Class<?> entityClass = resolveEntityClass(repositoryInterface);
        RepositoryMetadata metadata = entityClass != null ? new RepositoryMetadata(entityClass, properties) : null;
        ProxyFactory proxyFactory = new ProxyFactory();
        proxyFactory.setInterfaces(repositoryInterface);
        proxyFactory.addAdvice(new RepositoryProxyMethodInterceptor(r2dbcUtil, properties, metadata, repositoryInterface, repositoryXmlRegistry, springR2dbcProperties));
        return proxyFactory.getProxy(repositoryInterface.getClassLoader());
    }

    private Class<?> resolveEntityClass(Class<?> repositoryInterface) {
        for (Type type : repositoryInterface.getGenericInterfaces()) {
            Class<?> entityClass = resolveEntityClass(type);
            if (entityClass != null) {
                return entityClass;
            }
        }
        return null;
    }

    private Class<?> resolveEntityClass(Type type) {
        if (type instanceof ParameterizedType parameterizedType) {
            Type rawType = parameterizedType.getRawType();
            if (rawType == BaseRepository.class && parameterizedType.getActualTypeArguments()[0] instanceof Class<?> entityClass) {
                return entityClass;
            }
            if (rawType instanceof Class<?> rawClass) {
                for (Type parentType : rawClass.getGenericInterfaces()) {
                    Class<?> entityClass = resolveEntityClass(parentType);
                    if (entityClass != null) {
                        return entityClass;
                    }
                }
            }
        }
        if (type instanceof Class<?> rawClass) {
            for (Type parentType : rawClass.getGenericInterfaces()) {
                Class<?> entityClass = resolveEntityClass(parentType);
                if (entityClass != null) {
                    return entityClass;
                }
            }
        }
        return null;
    }
}
