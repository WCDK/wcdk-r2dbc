package com.wcdk.r2dbc.repository;

import com.wcdk.r2dbc.execution.SqlParameter;
import com.wcdk.r2dbc.execution.SqlLexicalScanner;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.data.repository.query.Param;
import org.springframework.util.StringUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * XML SQL 参数绑定器。
 * @author wcdk
 */
final class RepositoryParameterBinder {
    private Field findField(Class<?> type, String name) {
        if (type.getModule() != null && type.getModule().isNamed()) {
            return null;
        }
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

    /**
     * 仅绑定 SQL 代码中的占位符，为属性路径分配不冲突的别名。
     * @author wcdk
     */
    RepositoryParameterBinder.BoundSql bindSql(String sql, Method method, Object[] arguments, Map<String, Object> sourceParameters) {
        Map<String, Object> boundParameters = new LinkedHashMap<>();
        List<String> parameterNames = new ArrayList<>();
        Map<String, String> bindingNames = new LinkedHashMap<>();
        Map<String, String> bindingOwners = new LinkedHashMap<>();
        Matcher matcher = PARAMETER_PATTERN.matcher(sql);
        // 先收集代码中的占位符，避免字符串与注释参与绑定。
        for (int i = 0; i < sql.length();) {
            int end = SqlLexicalScanner.protectedEnd(sql, i);
            if (end > i) { i = end; continue; }
            matcher.region(i, sql.length());
            if (matcher.lookingAt()) {
                parameterNames.add(matcher.group(1));
                i = matcher.end();
            } else i++;
        }
        // 保留普通参数名；嵌套路径发生碰撞时使用独立别名。
        for (String name : parameterNames) {
            if (!name.contains(".")) {
                bindingNames.put(name, name);
                bindingOwners.put(name, name);
            }
        }
        for (String name : parameterNames) {
            if (bindingNames.containsKey(name)) continue;
            String candidate = bindName(name);
            String base = candidate;
            int suffix = 0;
            while (bindingOwners.containsKey(candidate)) candidate = base + "_" + ++suffix;
            bindingNames.put(name, candidate);
            bindingOwners.put(candidate, name);
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < sql.length();) {
            int end = SqlLexicalScanner.protectedEnd(sql, i);
            if (end > i) { builder.append(sql, i, end); i = end; continue; }
            matcher.region(i, sql.length());
            if (matcher.lookingAt()) {
                builder.append(':').append(bindingNames.get(matcher.group(1)));
                i = matcher.end();
            } else builder.append(sql.charAt(i++));
        }
        if ((arguments == null ? 0 : arguments.length) == 1 && parameterNames.size() == 1
                && !parameterNames.get(0).contains(".")
                && !sourceParameters.containsKey(parameterNames.get(0)) && isSimpleValue(arguments[0])) {
            boundParameters.put(bindingNames.get(parameterNames.get(0)), typedNull(arguments[0], method.getParameterTypes()[0]));
            return new BoundSql(builder.toString(), boundParameters);
        }
        for (String name : parameterNames) {
            Object value = parameterValue(sourceParameters, name);
            boundParameters.put(bindingNames.get(name), typedNull(value, parameterJavaType(method, name)));
        }
        return new BoundSql(builder.toString(), boundParameters);
    }

    void validateUnused(Method method, Object[] arguments, java.util.Set<String> usedNames) {
        String[] names = PARAMETER_NAME_DISCOVERER.getParameterNames(method);
        List<String> unused = new ArrayList<>();
        for (int i = 0; i < method.getParameterCount(); i++) {
            Param param = method.getParameters()[i].getAnnotation(Param.class);
            String actual = param != null ? param.value() : names == null ? "arg" + i : names[i];
            java.util.Set<String> aliases = new java.util.HashSet<>(List.of("arg" + i, "param" + (i + 1), actual));
            boolean used = usedNames.stream().map(name -> name.split("\\.", 2)[0]).anyMatch(aliases::contains);
            if (!used && method.getParameterCount() == 1) {
                Object value = arguments[0];
                used = isSimpleValue(value) ? !usedNames.isEmpty()
                        : usedNames.stream().anyMatch(name -> findField(method.getParameterTypes()[0], name) != null);
            }
            if (!used) unused.add(actual);
        }
        if (!unused.isEmpty()) throw new IllegalArgumentException("未使用的 XML SQL 参数：" + unused);
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
                return propertyType(type, name.substring(name.indexOf('.') + 1));
            }
            return type;
        }
        if (method.getParameterCount() == 1 && name.contains(".")) {
            return propertyType(method.getParameterTypes()[0], name);
        }
        if (method.getParameterCount() == 1) {
            Field beanField = findField(method.getParameterTypes()[0], name);
            if (beanField != null) {
                return beanField.getType();
            }
        }
        return Object.class;
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
            if (parameters.containsKey(name.substring(0, dotIndex))) {
                return propertyValue(root, name.substring(dotIndex + 1));
            }
        }
        throw new IllegalArgumentException("R2DBC XML SQL 参数不存在：" + name);
    }

    private String bindName(String name) {
        return name.replace('.', '_');
    }

    /** 沿声明类型逐层解析属性，包括中间对象为 null 的路径。 */
    private Class<?> propertyType(Class<?> type, String path) {
        for (String property : path.split("\\.")) type = field(type, property).getType();
        return type;
    }

    private void putBeanProperties(Map<String, Object> parameters, Object argument) {
        Class<?> type = argument.getClass();
        if (type.getModule() != null && type.getModule().isNamed()) {
            return;
        }
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                if (!parameters.containsKey(field.getName())) {
                    parameters.put(field.getName(), fieldValue(field, argument));
                }
            }
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
        if (type.getModule() != null && type.getModule().isNamed()) {
            throw new IllegalArgumentException("不支持反射访问JDK模块类型字段: " + type.getName() + "." + name);
        }
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
                || value instanceof Enum<?>
                || value instanceof java.time.temporal.TemporalAccessor
                || value instanceof java.time.ZoneId;
    }



    record BoundSql(String sql, Map<String, Object> parameters) {}
}
