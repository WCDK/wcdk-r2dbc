package com.wcdk.r2dbc.repository;

import com.wcdk.r2dbc.execution.SqlParameter;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.data.repository.query.Param;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/***
 * XML SQL 参数绑定器。
 * @author wcdk
 */
final class RepositoryParameterBinder {
    private Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private Object fieldValue(Field field, Object target) {
        try {
            field.setAccessible(true);
            return field.get(target);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("无法读取参数字段: " + field, error);
        }
    }
    private static final Pattern PARAMETER_PATTERN = Pattern.compile("#\\{\\s*([a-zA-Z0-9_.$]+)\\s*}");
    private static final ParameterNameDiscoverer PARAMETER_NAME_DISCOVERER = new DefaultParameterNameDiscoverer();

    RepositoryParameterBinder.BoundSql bindSql(String sql, Method method, Object[] arguments, Map<String, Object> sourceParameters) {
        Map<String, Object> boundParameters = new LinkedHashMap<>();
        List<String> parameterNames = new ArrayList<>();
        Matcher matcher = PARAMETER_PATTERN.matcher(sql);
        StringBuilder builder = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            parameterNames.add(name);
            matcher.appendReplacement(builder, ":" + bindName(name));
        }
        matcher.appendTail(builder);
        if ((arguments == null ? 0 : arguments.length) == 1 && parameterNames.size() == 1
                && !sourceParameters.containsKey(parameterNames.get(0))) {
            boundParameters.put(bindName(parameterNames.get(0)), typedNull(arguments[0], method.getParameterTypes()[0]));
            return new BoundSql(builder.toString(), boundParameters);
        }
        for (String name : parameterNames) {
            Object value = parameterValue(sourceParameters, name);
            boundParameters.put(bindName(name), typedNull(value, parameterJavaType(method, name)));
        }
        return new BoundSql(builder.toString(), boundParameters);
    }

    private Object typedNull(Object value, Class<?> javaType) {
        return value == null ? SqlParameter.nullOf(javaType == null ? Object.class : javaType) : value;
    }

    private Class<?> parameterJavaType(Method method, String name) {
        String rootName = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        String[] discoveredNames = PARAMETER_NAME_DISCOVERER.getParameterNames(method);
        for (int i = 0; i < method.getParameterCount(); i++) {
            MethodParameter parameter = new MethodParameter(method, i);
            Param annotation = parameter.getParameterAnnotation(Param.class);
            boolean matches = rootName.equals("arg" + i) || rootName.equals("param" + (i + 1))
                    || annotation != null && rootName.equals(annotation.value())
                    || discoveredNames != null && rootName.equals(discoveredNames[i]);
            if (!matches) {
                continue;
            }
            Class<?> type = method.getParameterTypes()[i];
            if (name.contains(".")) {
                return field(type, name.substring(name.indexOf('.') + 1)).getType();
            }
            return type;
        }
        if (method.getParameterCount() == 1 && name.contains(".")) {
            return field(method.getParameterTypes()[0], name.substring(name.indexOf('.') + 1)).getType();
        }
        if (method.getParameterCount() == 1) {
            Field beanField = findField(method.getParameterTypes()[0], name);
            if (beanField != null) {
                return beanField.getType();
            }
        }
        return Object.class;
    }

    static Object terminatedPublisher(Method method) {
        return method.getReturnType() == Flux.class ? Flux.empty() : Mono.empty();
    }

    Map<String, Object> methodParameters(Method method, Object[] arguments) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (arguments == null) {
            return parameters;
        }
        String[] parameterNames = PARAMETER_NAME_DISCOVERER.getParameterNames(method);
        for (int i = 0; i < arguments.length; i++) {
            Object argument = arguments[i];
            parameters.put("arg" + i, argument);
            parameters.put("param" + (i + 1), argument);
            MethodParameter methodParameter = new MethodParameter(method, i);
            Param param = methodParameter.getParameterAnnotation(Param.class);
            if (param != null) {
                parameters.put(param.value(), argument);
            }
            if (parameterNames != null && StringUtils.hasText(parameterNames[i])) {
                parameters.put(parameterNames[i], argument);
            }
            if (arguments.length == 1 && !isSimpleValue(argument)) {
                putBeanProperties(parameters, argument);
            }
        }
        return parameters;
    }

    private Object parameterValue(Map<String, Object> parameters, String name) {
        if (parameters.containsKey(name)) {
            return parameters.get(name);
        }
        int dotIndex = name.indexOf('.');
        if (dotIndex > 0) {
            Object root = parameters.get(name.substring(0, dotIndex));
            if (root != null) {
                return propertyValue(root, name.substring(dotIndex + 1));
            }
        }
        throw new IllegalArgumentException("R2DBC XML SQL 参数不存在：" + name);
    }

    private String bindName(String name) {
        return name.replace('.', '_');
    }

    private void putBeanProperties(Map<String, Object> parameters, Object argument) {
        for (Field field : argument.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            parameters.putIfAbsent(field.getName(), fieldValue(field, argument));
        }
    }

    private Object propertyValue(Object root, String propertyPath) {
        Object value = root;
        for (String name : propertyPath.split("\\.")) {
            if (value == null) {
                return null;
            }
            value = fieldValue(field(value.getClass(), name), value);
        }
        return value;
    }

    private Field field(Class<?> type, String name) {
        Class<?> searchType = type;
        while (searchType != null && searchType != Object.class) {
            try {
                Field field = searchType.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                searchType = searchType.getSuperclass();
            }
        }
        throw new IllegalArgumentException("R2DBC XML SQL 参数字段不存在：" + type.getName() + "." + name);
    }

    private boolean isSimpleValue(Object value) {
        return value == null
                || value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Enum<?>;
    }



    record BoundSql(String sql, Map<String, Object> parameters) {}
}