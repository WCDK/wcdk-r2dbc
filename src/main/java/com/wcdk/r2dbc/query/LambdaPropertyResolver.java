package com.wcdk.r2dbc.query;

import com.wcdk.r2dbc.repository.metadata.RepositoryMetadata;

import java.io.Serializable;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;

/***
 * Lambda 属性解析器，统一通过实体元数据获取数据库列。
 * @author wcdk
***/
@FunctionalInterface
public interface LambdaPropertyResolver {

    /***
     * 解析 Lambda 对应的实体字段，并返回统一元数据中的列定义。
     * @author wcdk
     * @param getter Lambda Getter
     * @param metadata 实体元数据
     * @return 字段列定义
     ***/
    RepositoryMetadata.FieldColumn resolve(Serializable getter, RepositoryMetadata metadata);

    /***
     * 返回默认 Lambda 属性解析器。
     * @author wcdk
     * @return 默认解析器
     ***/
    static LambdaPropertyResolver defaultResolver() {
        return Default.INSTANCE;
    }

    /***
     * 默认 Lambda 属性解析实现。
     * @author wcdk
     ***/
    final class Default implements LambdaPropertyResolver {
        private static final Default INSTANCE = new Default();

        @Override
        public RepositoryMetadata.FieldColumn resolve(Serializable getter, RepositoryMetadata metadata) {
            if (getter == null) {
                throw new IllegalArgumentException("Lambda 字段不能为空");
            }
            if (metadata == null) {
                throw new IllegalArgumentException("实体元数据不能为空");
            }
            try {
                Method writeReplace = getter.getClass().getDeclaredMethod("writeReplace");
                writeReplace.setAccessible(true);
                SerializedLambda lambda = (SerializedLambda) writeReplace.invoke(getter);
                return metadata.columnByName(propertyName(lambda.getImplMethodName()));
            } catch (ReflectiveOperationException | ClassCastException exception) {
                throw new IllegalArgumentException("无法解析 Lambda 字段，请使用实体 Getter 方法引用", exception);
            }
        }

        private String propertyName(String methodName) {
            String suffix;
            if (methodName.startsWith("get") && methodName.length() > 3) {
                suffix = methodName.substring(3);
            } else if (methodName.startsWith("is") && methodName.length() > 2) {
                suffix = methodName.substring(2);
            } else if (methodName.startsWith("set") && methodName.length() > 3) {
                suffix = methodName.substring(3);
            } else {
                throw new IllegalArgumentException("Lambda 必须引用实体 Getter 方法：" + methodName);
            }
            return suffix.length() > 1 && Character.isUpperCase(suffix.charAt(1))
                    ? suffix
                    : Character.toLowerCase(suffix.charAt(0)) + suffix.substring(1);
        }
    }
}
