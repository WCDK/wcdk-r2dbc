package com.wcdk.r2dbc.core;

import com.wcdk.r2dbc.core.metadata.RepositoryMetadata;
import com.wcdk.r2dbc.core.metadata.RepositoryMetadata.FieldColumn;
import com.wcdk.r2dbc.core.executor.SqlParameter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 自定义仓储方法解析器，支持通过方法名约定自动生成SQL。
 *
 * 支持的方法名格式：
 * - findBy[Field] / findBy[Field1]And[Field2]
 * - countBy[Field] / countBy[Field1]And[Field2]
 * - existsBy[Field] / existsBy[Field1]And[Field2]
 * - deleteBy[Field] (逻辑删除)
 * - update[Field]ById
 * - update[Field1]And[Field2]By[Condition]
 * - orderBy[Field]Asc / orderBy[Field]Desc
 *
 * @author WCDK
 * @date 2026/8/5
 * @version 2.0
 **/
public class CustomMethodResolver {

    private static final Pattern METHOD_PATTERN = Pattern.compile(
            "^(find|count|exists|delete|update)(All|One)?(By|OrderBy)(.+)?$"
    );

    private final RepositoryMetadata metadata;

    private final Object logicDeleteValue;

    private final Object logicNotDeleteValue;

    public CustomMethodResolver(RepositoryMetadata metadata, Object logicDeleteValue, Object logicNotDeleteValue) {
        this.metadata = metadata;
        this.logicDeleteValue = logicDeleteValue;
        this.logicNotDeleteValue = logicNotDeleteValue;
    }

    /**
     * 解析方法名，生成SQL和参数。
     *
     * @param method     方法
     * @param arguments  参数
     * @return 解析结果，null 表示不支持的方法格式
     */
    public ParsedMethod resolve(Method method, Object[] arguments) {
        String methodName = method.getName();
        Matcher matcher = METHOD_PATTERN.matcher(methodName);

        if (!matcher.matches()) {
            return null;
        }

        String operation = matcher.group(1);
        String suffix = matcher.group(2);
        String fieldPart = matcher.group(4);

        if ("All".equals(suffix) || "One".equals(suffix)) {
            return null;
        }

        return switch (operation) {
            case "find" -> resolveFind(method, fieldPart, arguments);
            case "count" -> resolveCount(method, fieldPart, arguments);
            case "exists" -> resolveExists(method, fieldPart, arguments);
            case "delete" -> resolveDelete(method, fieldPart, arguments);
            case "update" -> resolveUpdate(method, fieldPart, arguments);
            default -> null;
        };
    }

    public static boolean supports(Method method) {
        Matcher matcher = METHOD_PATTERN.matcher(method.getName());
        return matcher.matches() && matcher.group(2) == null;
    }

    // ==================== find ====================

    private ParsedMethod resolveFind(Method method, String fieldPart, Object[] arguments) {
        if (fieldPart == null || fieldPart.isEmpty()) {
            String sql = "SELECT " + selectColumns() + " FROM " + metadata.tableName()
                    + logicalNotDeleteSql();
            return new ParsedMethod(sql, Map.of(), SqlCommandType.SELECT);
        }

        String conditionPart = conditionPart(fieldPart);
        List<Condition> conditions = parseConditions(conditionPart, method.getParameters(), arguments, 0, method.getName());
        String orderBySql = extractOrderBy(fieldPart);
        String whereSql = buildWhereSql(conditions, true);

        String sql = "SELECT " + selectColumns() + " FROM " + metadata.tableName()
                + whereSql + orderBySql;

        Map<String, Object> parameters = buildParameters(conditions);
        return new ParsedMethod(sql, parameters, SqlCommandType.SELECT);
    }

    // ==================== count ====================

    private ParsedMethod resolveCount(Method method, String fieldPart, Object[] arguments) {
        if (fieldPart == null || fieldPart.isEmpty()) {
            String sql = "SELECT COUNT(1) FROM " + metadata.tableName()
                    + logicalNotDeleteSql();
            return new ParsedMethod(sql, Map.of(), SqlCommandType.SELECT);
        }

        List<Condition> conditions = parseConditions(fieldPart, method.getParameters(), arguments, 0, method.getName());
        String whereSql = buildWhereSql(conditions, true);

        String sql = "SELECT COUNT(1) FROM " + metadata.tableName() + whereSql;
        Map<String, Object> parameters = buildParameters(conditions);
        return new ParsedMethod(sql, parameters, SqlCommandType.SELECT);
    }

    // ==================== exists ====================

    private ParsedMethod resolveExists(Method method, String fieldPart, Object[] arguments) {
        if (fieldPart == null || fieldPart.isEmpty()) {
            String sql = "SELECT CASE WHEN COUNT(1) > 0 THEN TRUE ELSE FALSE END FROM "
                    + metadata.tableName() + logicalNotDeleteSql();
            return new ParsedMethod(sql, Map.of(), SqlCommandType.SELECT);
        }

        List<Condition> conditions = parseConditions(fieldPart, method.getParameters(), arguments, 0, method.getName());
        String whereSql = buildWhereSql(conditions, true);

        String sql = "SELECT CASE WHEN COUNT(1) > 0 THEN TRUE ELSE FALSE END FROM "
                + metadata.tableName() + whereSql;
        Map<String, Object> parameters = buildParameters(conditions);
        return new ParsedMethod(sql, parameters, SqlCommandType.SELECT);
    }

    // ==================== delete ====================

    private ParsedMethod resolveDelete(Method method, String fieldPart, Object[] arguments) {
        FieldColumn logicDeleteColumn = metadata.logicDeleteColumn();
        if (logicDeleteColumn == null) {
            throw new UnsupportedOperationException("实体未配置逻辑删除字段，不支持delete方法");
        }

        if (fieldPart == null || fieldPart.isEmpty()) {
            throw new UnsupportedOperationException("delete方法必须指定条件");
        }

        List<Condition> conditions = parseConditions(fieldPart, method.getParameters(), arguments, 0, method.getName());
        String whereSql = buildWhereSql(conditions, false);

        String sql = "UPDATE " + metadata.tableName()
                + " SET " + logicDeleteColumn.name() + " = :logicDeleteValue"
                + whereSql;

        Map<String, Object> parameters = buildParameters(conditions);
        parameters.put("logicDeleteValue", typed(logicDeleteColumn, logicDeleteValue));
        return new ParsedMethod(sql, parameters, SqlCommandType.UPDATE);
    }

    // ==================== update ====================

    private ParsedMethod resolveUpdate(Method method, String fieldPart, Object[] arguments) {
        if (fieldPart == null || fieldPart.isEmpty()) {
            throw new UnsupportedOperationException("update方法必须指定更新字段");
        }

        if (fieldPart.endsWith("ById")) {
            String fieldName = fieldPart.substring(0, fieldPart.length() - 3);
            return resolveUpdateById(method, fieldName, arguments);
        }

        int byIndex = fieldPart.indexOf("By");
        if (byIndex > 0) {
            String setPart = fieldPart.substring(0, byIndex);
            String wherePart = fieldPart.substring(byIndex + 2);
            return resolveUpdateByFields(method, setPart, wherePart, arguments);
        }

        throw new UnsupportedOperationException("不支持的update方法格式：update" + fieldPart);
    }

    private ParsedMethod resolveUpdateById(Method method, String fieldName, Object[] arguments) {
        FieldColumn idColumn = metadata.requireIdColumn();
        FieldColumn fieldColumn = findColumn(fieldName);
        if (fieldColumn == null) {
            throw new IllegalArgumentException("实体字段不存在：" + fieldName);
        }

        Parameter[] parameters = method.getParameters();
        if (parameters.length < 2) {
            throw new UnsupportedOperationException("update方法需要两个参数：newValue和id");
        }

        String sql = "UPDATE " + metadata.tableName()
                + " SET " + fieldColumn.name() + " = :newValue"
                + " WHERE " + idColumn.name() + " = :id";

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("newValue", typed(fieldColumn, arguments[0]));
        params.put("id", typed(idColumn, arguments[1]));

        if (metadata.logicDeleteColumn() != null) {
            params.put("logicNotDeleteValue", typed(metadata.logicDeleteColumn(), logicNotDeleteValue));
            sql += " AND " + metadata.logicDeleteColumn().name() + " = :logicNotDeleteValue";
        }

        return new ParsedMethod(sql, params, SqlCommandType.UPDATE);
    }

    private ParsedMethod resolveUpdateByFields(Method method, String setPart, String wherePart, Object[] arguments) {
        List<String> setFields = parseFieldNames(setPart);

        if (setFields.isEmpty()) {
            throw new UnsupportedOperationException("update方法必须指定更新字段");
        }

        StringBuilder sql = new StringBuilder();
        sql.append("UPDATE ").append(metadata.tableName()).append(" SET ");

        Map<String, Object> parameters = new LinkedHashMap<>();
        List<String> setClauses = new ArrayList<>();

        for (int i = 0; i < setFields.size(); i++) {
            FieldColumn fieldColumn = findColumn(setFields.get(i));
            if (fieldColumn == null) {
                throw new IllegalArgumentException("实体字段不存在：" + setFields.get(i));
            }
            String paramName = "set" + i;
            setClauses.add(fieldColumn.name() + " = :" + paramName);
            parameters.put(paramName, typed(fieldColumn, arguments[i]));
        }

        sql.append(String.join(", ", setClauses));

        int argOffset = setFields.size();
        List<Condition> conditions = parseConditions(wherePart, method.getParameters(), arguments, argOffset, method.getName());
        String whereSql = buildWhereSql(conditions, false);
        sql.append(whereSql);

        Map<String, Object> whereParams = buildParameters(conditions);
        parameters.putAll(whereParams);

        return new ParsedMethod(sql.toString(), parameters, SqlCommandType.UPDATE);
    }

    // ==================== 条件解析 ====================

    private List<Condition> parseConditions(String fieldPart, Parameter[] methodParams,
                                             Object[] arguments, int argOffset, String methodName) {
        List<Condition> conditions = new ArrayList<>();
        String[] parts = fieldPart.split("(?=And|Or)(?<!^)");

        int argIndex = argOffset;

        for (String part : parts) {
            String logicalOperator = "And";

            if (part.startsWith("And")) {
                part = part.substring(3);
                logicalOperator = "And";
            } else if (part.startsWith("Or")) {
                part = part.substring(2);
                logicalOperator = "Or";
            }

            String operator = "=";
            String fieldName = part;

            if (part.endsWith("IsNotNull")) {
                operator = "IS NOT NULL";
                fieldName = part.substring(0, part.length() - 9);
            } else if (part.endsWith("IsNull")) {
                operator = "IS NULL";
                fieldName = part.substring(0, part.length() - 6);
            } else if (part.endsWith("GreaterThanEqual")) {
                operator = ">=";
                fieldName = part.substring(0, part.length() - 16);
            } else if (part.endsWith("LessThanEqual")) {
                operator = "<=";
                fieldName = part.substring(0, part.length() - 13);
            } else if (part.endsWith("GreaterThan")) {
                operator = ">";
                fieldName = part.substring(0, part.length() - 11);
            } else if (part.endsWith("LessThan")) {
                operator = "<";
                fieldName = part.substring(0, part.length() - 8);
            } else if (part.endsWith("NotIn")) {
                operator = "NOT IN";
                fieldName = part.substring(0, part.length() - 5);
            } else if (part.endsWith("Like")) {
                operator = "LIKE";
                fieldName = part.substring(0, part.length() - 4);
            } else if (part.endsWith("In")) {
                operator = "IN";
                fieldName = part.substring(0, part.length() - 2);
            } else if (part.endsWith("Between")) {
                operator = "BETWEEN";
                fieldName = part.substring(0, part.length() - 7);
            } else if (part.endsWith("Not")) {
                operator = "<>";
                fieldName = part.substring(0, part.length() - 3);
            } else if (part.endsWith("Asc") || part.endsWith("Desc")) {
                continue;
            }

            FieldColumn fieldColumn = findColumn(fieldName);
            if (fieldColumn == null) {
                throw new IllegalArgumentException("实体字段不存在：" + fieldName + "（方法：" + methodName + "）");
            }

            Object value = null;
            Object secondValue = null;

            if ("IS NULL".equals(operator) || "IS NOT NULL".equals(operator)) {
                // 不消费参数
            } else if ("BETWEEN".equals(operator)) {
                if (argIndex + 1 >= arguments.length) {
                    throw new IllegalArgumentException(
                            "方法 " + methodName + " 参数数量不匹配：BETWEEN 需要2个参数，但只剩 "
                                    + (arguments.length - argIndex) + " 个");
                }
                value = arguments[argIndex++];
                secondValue = arguments[argIndex++];
            } else {
                if (argIndex >= arguments.length) {
                    throw new IllegalArgumentException(
                            "方法 " + methodName + " 参数数量不匹配：需要更多参数，但已用完");
                }
                value = arguments[argIndex++];
            }

            conditions.add(new Condition(fieldColumn.name(), operator, value, secondValue, logicalOperator));
        }

        if (argIndex < arguments.length) {
            throw new IllegalArgumentException(
                    "方法 " + methodName + " 参数数量不匹配：消耗了 " + (argIndex - argOffset)
                            + " 个参数，但传入了 " + (arguments.length - argOffset) + " 个");
        }

        return conditions;
    }

    private List<String> parseFieldNames(String fieldPart) {
        List<String> fields = new ArrayList<>();
        String[] parts = fieldPart.split("(?=And)(?<!^)");

        for (String part : parts) {
            if (part.startsWith("And")) {
                part = part.substring(3);
            }
            fields.add(part);
        }

        return fields;
    }

    // ==================== SQL 构建 ====================

    private String buildWhereSql(List<Condition> conditions, boolean addLogicDelete) {
        if (conditions.isEmpty()) {
            return addLogicDelete ? logicalNotDeleteSql() : "";
        }

        StringBuilder where = new StringBuilder(" WHERE (");
        for (int i = 0; i < conditions.size(); i++) {
            Condition condition = conditions.get(i);
            String parameterName = "p" + i;
            if (i > 0) {
                where.append(" ").append(condition.logicalOperator()).append(" ");
            }

            if (("=".equals(condition.operator()) || "<>".equals(condition.operator()))
                    && condition.value() == null) {
                where.append(condition.column()).append(" ")
                        .append("=".equals(condition.operator()) ? "IS NULL" : "IS NOT NULL");
            } else if ("IS NULL".equals(condition.operator()) || "IS NOT NULL".equals(condition.operator())) {
                where.append(condition.column()).append(" ").append(condition.operator());
            } else if ("IN".equals(condition.operator()) || "NOT IN".equals(condition.operator())) {
                appendInClause(where, condition, parameterName);
            } else if ("BETWEEN".equals(condition.operator())) {
                where.append(condition.column()).append(" BETWEEN :").append(parameterName)
                        .append("Start AND :").append(parameterName).append("End");
            } else {
                where.append(condition.column()).append(" ").append(condition.operator())
                        .append(" :").append(parameterName);
            }
        }

        where.append(")");

        if (addLogicDelete && metadata.logicDeleteColumn() != null
                && conditions.stream().noneMatch(condition ->
                condition.column().equals(metadata.logicDeleteColumn().name()))) {
            where.append(" AND ").append(metadata.logicDeleteColumn().name()).append(" = :logicNotDeleteValue");
        }

        return where.toString();
    }

    private void appendInClause(StringBuilder where, Condition condition, String parameterName) {
        Object value = condition.value();
        boolean negated = "NOT IN".equals(condition.operator());
        if (value instanceof Collection<?> collection) {
            if (collection.isEmpty()) {
                where.append(negated ? "1 = 1" : "1 = 0");
            } else {
                StringBuilder placeholders = new StringBuilder();
                int idx = 0;
                for (Object item : collection) {
                    if (idx > 0) placeholders.append(", ");
                    placeholders.append(":").append(parameterName).append("_in_").append(idx++);
                }
                where.append(condition.column()).append(negated ? " NOT IN (" : " IN (")
                        .append(placeholders).append(")");
            }
        } else if (value != null && value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            if (length == 0) {
                where.append(negated ? "1 = 1" : "1 = 0");
            } else {
                StringBuilder placeholders = new StringBuilder();
                for (int i = 0; i < length; i++) {
                    if (i > 0) placeholders.append(", ");
                    placeholders.append(":").append(parameterName).append("_in_").append(i);
                }
                where.append(condition.column()).append(negated ? " NOT IN (" : " IN (")
                        .append(placeholders).append(")");
            }
        } else {
            where.append(condition.column()).append(negated ? " NOT IN (:" : " IN (:")
                    .append(parameterName).append(")");
        }
    }

    private Map<String, Object> buildParameters(List<Condition> conditions) {
        Map<String, Object> parameters = new LinkedHashMap<>();

        for (int i = 0; i < conditions.size(); i++) {
            Condition condition = conditions.get(i);
            String parameterName = "p" + i;
            if ("IS NULL".equals(condition.operator()) || "IS NOT NULL".equals(condition.operator())) {
                continue;
            }
            if (condition.value() == null
                    && ("=".equals(condition.operator()) || "<>".equals(condition.operator()))) {
                continue;
            }
            if ("IN".equals(condition.operator()) || "NOT IN".equals(condition.operator())) {
                putInParameters(parameters, condition, parameterName);
            } else if ("BETWEEN".equals(condition.operator())) {
                parameters.put(parameterName + "Start", typed(condition.column(), condition.value()));
                parameters.put(parameterName + "End", typed(condition.column(), condition.secondValue()));
            } else {
                parameters.put(parameterName, typed(condition.column(), condition.value()));
            }
        }

        if (metadata.logicDeleteColumn() != null
                && conditions.stream().noneMatch(condition ->
                condition.column().equals(metadata.logicDeleteColumn().name()))) {
            parameters.put("logicNotDeleteValue", typed(metadata.logicDeleteColumn(), logicNotDeleteValue));
        }

        return parameters;
    }

    private void putInParameters(Map<String, Object> parameters, Condition condition, String parameterName) {
        Object value = condition.value();
        if (value instanceof Collection<?> collection) {
            int idx = 0;
            for (Object item : collection) {
                parameters.put(parameterName + "_in_" + idx++, typed(condition.column(), item));
            }
        } else if (value != null && value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                parameters.put(parameterName + "_in_" + i,
                        typed(condition.column(), java.lang.reflect.Array.get(value, i)));
            }
        } else {
            parameters.put(parameterName, typed(condition.column(), value));
        }
    }

    private Object typed(String column, Object value) {
        return typed(metadata.columnByName(column), value);
    }

    private Object typed(FieldColumn column, Object value) {
        return value == null ? SqlParameter.nullOf(column.field().getType()) : value;
    }

    // ==================== OrderBy ====================

    private String extractOrderBy(String fieldPart) {
        if (fieldPart == null) {
            return "";
        }

        int orderByIndex = fieldPart.indexOf("OrderBy");
        if (orderByIndex < 0) {
            return "";
        }

        String orderByPart = fieldPart.substring(orderByIndex + 7);
        if (orderByPart.isEmpty()) {
            return "";
        }

        List<String> orderClauses = new ArrayList<>();
        String[] parts = orderByPart.split("(?<=Asc)(?=[A-Z])|(?<=Desc)(?=[A-Z])");

        for (String part : parts) {
            if (part.isEmpty()) continue;

            boolean asc = true;
            String fieldName = part;

            if (part.endsWith("Asc")) {
                fieldName = part.substring(0, part.length() - 3);
                asc = true;
            } else if (part.endsWith("Desc")) {
                fieldName = part.substring(0, part.length() - 4);
                asc = false;
            }

            FieldColumn fieldColumn = findColumn(fieldName);
            if (fieldColumn != null) {
                orderClauses.add(fieldColumn.name() + (asc ? " ASC" : " DESC"));
            } else {
                throw new IllegalArgumentException("排序字段不存在：" + fieldName);
            }
        }

        if (orderClauses.isEmpty()) {
            return "";
        }

        return " ORDER BY " + String.join(", ", orderClauses);
    }

    private String conditionPart(String fieldPart) {
        int orderByIndex = fieldPart.indexOf("OrderBy");
        return orderByIndex < 0 ? fieldPart : fieldPart.substring(0, orderByIndex);
    }

    // ==================== 工具方法 ====================

    private FieldColumn findColumn(String fieldName) {
        return metadata.columns().stream()
                .filter(col -> col.field().getName().equalsIgnoreCase(fieldName)
                        || col.name().equalsIgnoreCase(fieldName)
                        || col.name().equalsIgnoreCase(camelToUnderline(fieldName)))
                .findFirst()
                .orElse(null);
    }

    private String selectColumns() {
        return metadata.columns().stream()
                .map(FieldColumn::name)
                .collect(Collectors.joining(", "));
    }

    private String logicalNotDeleteSql() {
        if (metadata.logicDeleteColumn() == null) {
            return "";
        }
        return " WHERE " + metadata.logicDeleteColumn().name() + " = :logicNotDeleteValue";
    }

    private String camelToUnderline(String value) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isUpperCase(ch) && i > 0) {
                builder.append('_');
            }
            builder.append(Character.toLowerCase(ch));
        }
        return builder.toString();
    }

    // ==================== 启动期校验 ====================

    /**
     * 校验方法是否支持，不支持时在启动期快速失败。
     *
     * @param method 方法
     */
    public void validateMethod(Method method) {
        String methodName = method.getName();
        Matcher matcher = METHOD_PATTERN.matcher(methodName);

        if (!matcher.matches()) {
            return;
        }

        String operation = matcher.group(1);
        String fieldPart = matcher.group(4);

        validateMethodNameLength(methodName);
        if ("delete".equals(operation) && metadata.logicDeleteColumn() == null) {
            throw new IllegalArgumentException("实体未配置逻辑删除字段，不支持派生 delete 方法：" + methodName);
        }
        validateFieldPart(fieldPart == null ? null : conditionPart(fieldPart), methodName);
        if ("find".equals(operation)) {
            extractOrderBy(fieldPart);
        }
        validateReturnType(method, operation);
    }

    private void validateMethodNameLength(String methodName) {
        if (methodName.length() > 128) {
            throw new IllegalArgumentException("方法名过长（超过128字符）：" + methodName);
        }
    }

    private void validateFieldPart(String fieldPart, String methodName) {
        if (fieldPart == null || fieldPart.isEmpty()) {
            return;
        }

        if (fieldPart.length() > 256) {
            throw new IllegalArgumentException("方法名字段部分过长（超过256字符）：" + methodName);
        }

        String[] parts = fieldPart.split("(?=And|Or)(?<!^)");
        for (String part : parts) {
            if (part.startsWith("And")) {
                part = part.substring(3);
            } else if (part.startsWith("Or")) {
                part = part.substring(2);
            }

            if (part.startsWith("OrderBy")) {
                break;
            }

            String fieldName = extractFieldNameForValidation(part);
            if (fieldName.isEmpty()) {
                throw new IllegalArgumentException("方法名中存在空条件字段：" + methodName);
            }

            FieldColumn fieldColumn = findColumn(fieldName);
            if (fieldColumn == null && !part.endsWith("Asc") && !part.endsWith("Desc")) {
                throw new IllegalArgumentException("方法名引用了不存在的实体字段：" + fieldName + "（方法：" + methodName + "）");
            }
        }
    }

    private String extractFieldNameForValidation(String part) {
        if (part.endsWith("IsNotNull")) {
            return part.substring(0, part.length() - 9);
        } else if (part.endsWith("IsNull")) {
            return part.substring(0, part.length() - 6);
        } else if (part.endsWith("GreaterThanEqual")) {
            return part.substring(0, part.length() - 16);
        } else if (part.endsWith("LessThanEqual")) {
            return part.substring(0, part.length() - 13);
        } else if (part.endsWith("GreaterThan")) {
            return part.substring(0, part.length() - 11);
        } else if (part.endsWith("LessThan")) {
            return part.substring(0, part.length() - 8);
        } else if (part.endsWith("NotIn")) {
            return part.substring(0, part.length() - 5);
        } else if (part.endsWith("Like")) {
            return part.substring(0, part.length() - 4);
        } else if (part.endsWith("In")) {
            return part.substring(0, part.length() - 2);
        } else if (part.endsWith("Between")) {
            return part.substring(0, part.length() - 7);
        } else if (part.endsWith("Not")) {
            return part.substring(0, part.length() - 3);
        } else if (part.endsWith("Asc")) {
            return part.substring(0, part.length() - 3);
        } else if (part.endsWith("Desc")) {
            return part.substring(0, part.length() - 4);
        }
        return part;
    }

    private void validateReturnType(Method method, String operation) {
        Class<?> returnType = method.getReturnType();
        Class<?> genericType = getGenericType(method);

        switch (operation) {
            case "find" -> validateFindReturnType(returnType, genericType, method.getName());
            case "count" -> validateCountReturnType(returnType, genericType, method.getName());
            case "exists" -> validateExistsReturnType(returnType, genericType, method.getName());
            case "delete", "update" -> validateUpdateReturnType(returnType, genericType, method.getName());
        }
    }

    private void validateFindReturnType(Class<?> returnType, Class<?> genericType, String methodName) {
        if ((returnType == Flux.class || returnType == Mono.class)
                && genericType != null && metadata.entityClass().isAssignableFrom(genericType)) {
            return;
        }
        throw new IllegalArgumentException("find方法必须返回 Mono<" + metadata.entityClass().getSimpleName()
                + "> 或 Flux<" + metadata.entityClass().getSimpleName() + ">：" + methodName);
    }

    private void validateCountReturnType(Class<?> returnType, Class<?> genericType, String methodName) {
        if (returnType == Mono.class) {
            if (genericType == Long.class || genericType == long.class
                    || genericType == Integer.class || genericType == int.class) {
                return;
            }
            throw new IllegalArgumentException("count方法的Mono泛型必须是Long或Integer：" + methodName);
        }
        throw new IllegalArgumentException("count方法不支持的返回类型：" + returnType.getName() + "（方法：" + methodName + "）");
    }

    private void validateExistsReturnType(Class<?> returnType, Class<?> genericType, String methodName) {
        if (returnType == Mono.class) {
            if (genericType == Boolean.class || genericType == boolean.class) {
                return;
            }
            throw new IllegalArgumentException("exists方法的Mono泛型必须是Boolean：" + methodName);
        }
        throw new IllegalArgumentException("exists方法不支持的返回类型：" + returnType.getName() + "（方法：" + methodName + "）");
    }

    private void validateUpdateReturnType(Class<?> returnType, Class<?> genericType, String methodName) {
        if (returnType == Mono.class) {
            if (genericType == Long.class || genericType == long.class
                    || genericType == Integer.class || genericType == int.class
                    || genericType == Boolean.class || genericType == boolean.class
                    || genericType == Void.class || genericType == void.class) {
                return;
            }
            throw new IllegalArgumentException("update/delete方法的Mono泛型必须是Long、Integer、Boolean或Void：" + methodName);
        }
        throw new IllegalArgumentException("update/delete方法不支持的返回类型：" + returnType.getName() + "（方法：" + methodName + "）");
    }

    private Class<?> getGenericType(Method method) {
        Type genericReturnType = method.getGenericReturnType();
        if (genericReturnType instanceof java.lang.reflect.ParameterizedType parameterizedType) {
            Type[] typeArgs = parameterizedType.getActualTypeArguments();
            if (typeArgs.length > 0 && typeArgs[0] instanceof Class<?> clazz) {
                return clazz;
            }
        }
        return null;
    }

    private boolean isSimpleType(Class<?> type) {
        return type == String.class
                || type == Long.class || type == long.class
                || type == Integer.class || type == int.class
                || type == Boolean.class || type == boolean.class
                || type == Double.class || type == double.class
                || type == Float.class || type == float.class
                || type == Short.class || type == short.class
                || type == Byte.class || type == byte.class
                || type == Character.class || type == char.class
                || Number.class.isAssignableFrom(type)
                || type.isEnum();
    }

    // ==================== 类型定义 ====================

    /**
     * SQL 命令类型。
     */
    public enum SqlCommandType {
        SELECT, INSERT, UPDATE, DELETE
    }

    /**
     * 解析结果。
     */
    public record ParsedMethod(String sql, Map<String, Object> parameters, SqlCommandType commandType) {
    }

    /**
     * 条件定义。
     */
    private record Condition(String column, String operator, Object value, Object secondValue, String logicalOperator) {
    }
}
