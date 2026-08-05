package com.wcdk.r2dbc.core;

import com.wcdk.r2dbc.core.metadata.RepositoryMetadata;
import com.wcdk.r2dbc.core.metadata.RepositoryMetadata.FieldColumn;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
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
 * - orderBy[Field]Asc / orderBy[Field]Desc
 *
 * @author WCDK
 * @date 2026/8/5
 * @version 1.0
 **/
public class CustomMethodResolver {

    private static final Pattern METHOD_PATTERN = Pattern.compile(
            "^(find|count|exists|delete|update)(All|One)?(By|OrderBy)(.+)?$"
    );

    private static final Pattern CONDITION_PATTERN = Pattern.compile(
            "([a-zA-Z0-9]+)(And|Or)?"
    );

    private final RepositoryMetadata metadata;

    public CustomMethodResolver(RepositoryMetadata metadata) {
        this.metadata = metadata;
    }

    /**
     * 解析方法名，生成SQL和参数。
     *
     * @param method     方法
     * @param arguments  参数
     * @return 解析结果
     */
    public ParsedMethod resolve(Method method, Object[] arguments) {
        String methodName = method.getName();
        Matcher matcher = METHOD_PATTERN.matcher(methodName);

        if (!matcher.matches()) {
            return null;
        }

        String operation = matcher.group(1); // find, count, exists, delete, update
        String suffix = matcher.group(2);    // All, One, null
        String byClause = matcher.group(3);  // By, OrderBy
        String fieldPart = matcher.group(4); // 字段部分

        if ("All".equals(suffix) || "One".equals(suffix)) {
            // findByAll / findByOne 不支持，应该用 find / findOne
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

    /**
     * 解析find方法。
     */
    private ParsedMethod resolveFind(Method method, String fieldPart, Object[] arguments) {
        if (fieldPart == null || fieldPart.isEmpty()) {
            // findAll
            String sql = "SELECT " + selectColumns() + " FROM " + metadata.tableName()
                    + logicalNotDeleteSql();
            return new ParsedMethod(sql, Map.of(), true);
        }

        // 解析条件
        List<Condition> conditions = parseConditions(fieldPart, method.getParameters(), arguments);
        String whereSql = buildWhereSql(conditions);
        String orderBySql = extractOrderBy(fieldPart);

        String sql = "SELECT " + selectColumns() + " FROM " + metadata.tableName()
                + whereSql + orderBySql;

        Map<String, Object> parameters = buildParameters(conditions);
        return new ParsedMethod(sql, parameters, true);
    }

    /**
     * 解析count方法。
     */
    private ParsedMethod resolveCount(Method method, String fieldPart, Object[] arguments) {
        if (fieldPart == null || fieldPart.isEmpty()) {
            String sql = "SELECT COUNT(1) FROM " + metadata.tableName()
                    + logicalNotDeleteSql();
            return new ParsedMethod(sql, Map.of(), false);
        }

        List<Condition> conditions = parseConditions(fieldPart, method.getParameters(), arguments);
        String whereSql = buildWhereSql(conditions);

        String sql = "SELECT COUNT(1) FROM " + metadata.tableName() + whereSql;
        Map<String, Object> parameters = buildParameters(conditions);
        return new ParsedMethod(sql, parameters, false);
    }

    /**
     * 解析exists方法。
     */
    private ParsedMethod resolveExists(Method method, String fieldPart, Object[] arguments) {
        if (fieldPart == null || fieldPart.isEmpty()) {
            String sql = "SELECT CASE WHEN COUNT(1) > 0 THEN TRUE ELSE FALSE END FROM "
                    + metadata.tableName() + logicalNotDeleteSql();
            return new ParsedMethod(sql, Map.of(), false);
        }

        List<Condition> conditions = parseConditions(fieldPart, method.getParameters(), arguments);
        String whereSql = buildWhereSql(conditions);

        String sql = "SELECT CASE WHEN COUNT(1) > 0 THEN TRUE ELSE FALSE END FROM "
                + metadata.tableName() + whereSql;
        Map<String, Object> parameters = buildParameters(conditions);
        return new ParsedMethod(sql, parameters, false);
    }

    /**
     * 解析delete方法（逻辑删除）。
     */
    private ParsedMethod resolveDelete(Method method, String fieldPart, Object[] arguments) {
        FieldColumn logicDeleteColumn = metadata.logicDeleteColumn();
        if (logicDeleteColumn == null) {
            throw new UnsupportedOperationException("实体未配置逻辑删除字段，不支持delete方法");
        }

        if (fieldPart == null || fieldPart.isEmpty()) {
            throw new UnsupportedOperationException("delete方法必须指定条件");
        }

        List<Condition> conditions = parseConditions(fieldPart, method.getParameters(), arguments);
        String whereSql = buildWhereSql(conditions);

        String sql = "UPDATE " + metadata.tableName()
                + " SET " + logicDeleteColumn.name() + " = :logicDeleteValue"
                + whereSql;

        Map<String, Object> parameters = buildParameters(conditions);
        parameters.put("logicDeleteValue", getLogicDeleteValue());
        return new ParsedMethod(sql, parameters, false);
    }

    /**
     * 解析update方法。
     */
    private ParsedMethod resolveUpdate(Method method, String fieldPart, Object[] arguments) {
        if (fieldPart == null || fieldPart.isEmpty()) {
            throw new UnsupportedOperationException("update方法必须指定更新字段");
        }

        // 解析 update[Field]ById 格式
        if (fieldPart.endsWith("ById")) {
            String fieldName = fieldPart.substring(0, fieldPart.length() - 2); // 去掉 "Id"
            return resolveUpdateById(method, fieldName, arguments);
        }

        // 解析 update[Field1]And[Field2]By[Condition] 格式
        int byIndex = fieldPart.indexOf("By");
        if (byIndex > 0) {
            String setPart = fieldPart.substring(0, byIndex);
            String wherePart = fieldPart.substring(byIndex + 2);
            return resolveUpdateByFields(method, setPart, wherePart, arguments);
        }

        throw new UnsupportedOperationException("不支持的update方法格式：update" + fieldPart);
    }

    /**
     * 解析 update[Field]ById 方法。
     */
    private ParsedMethod resolveUpdateById(Method method, String fieldName, Object[] arguments) {
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
                + " WHERE " + metadata.idColumn().name() + " = :id";

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("newValue", arguments[0]);
        params.put("id", arguments[1]);

        if (metadata.logicDeleteColumn() != null) {
            params.put("logicNotDeleteValue", getLogicNotDeleteValue());
            sql += " AND " + metadata.logicDeleteColumn().name() + " = :logicNotDeleteValue";
        }

        return new ParsedMethod(sql, params, false);
    }

    /**
     * 解析 update[SetFields]By[WhereFields] 方法。
     */
    private ParsedMethod resolveUpdateByFields(Method method, String setPart, String wherePart, Object[] arguments) {
        List<String> setFields = parseFieldNames(setPart);
        List<Condition> conditions = parseConditions(wherePart, method.getParameters(), arguments);

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
            parameters.put(paramName, arguments[i]);
        }

        sql.append(String.join(", ", setClauses));

        String whereSql = buildWhereSql(conditions);
        sql.append(whereSql);

        Map<String, Object> whereParams = buildParameters(conditions);
        parameters.putAll(whereParams);

        return new ParsedMethod(sql.toString(), parameters, false);
    }

    /**
     * 解析条件字段。
     */
    private List<Condition> parseConditions(String fieldPart, Parameter[] methodParams, Object[] arguments) {
        List<Condition> conditions = new ArrayList<>();
        String[] parts = fieldPart.split("(?=And|Or)(?<!^)");

        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            String logicalOperator = "And";

            if (part.startsWith("And")) {
                part = part.substring(3);
                logicalOperator = "And";
            } else if (part.startsWith("Or")) {
                part = part.substring(2);
                logicalOperator = "Or";
            }

            // 检查是否是OrderBy
            if (part.startsWith("OrderBy")) {
                break;
            }

            // 检查是否是特殊操作（Like, In, Between等）
            String operator = "=";
            String fieldName = part;

            if (part.endsWith("Like")) {
                operator = "LIKE";
                fieldName = part.substring(0, part.length() - 4);
            } else if (part.endsWith("In")) {
                operator = "IN";
                fieldName = part.substring(0, part.length() - 2);
            } else if (part.endsWith("Between")) {
                operator = "BETWEEN";
                fieldName = part.substring(0, part.length() - 7);
            } else if (part.endsWith("IsNull")) {
                operator = "IS NULL";
                fieldName = part.substring(0, part.length() - 6);
            } else if (part.endsWith("IsNotNull")) {
                operator = "IS NOT NULL";
                fieldName = part.substring(0, part.length() - 9);
            } else if (part.endsWith("Asc")) {
                // 排序字段，跳过
                continue;
            } else if (part.endsWith("Desc")) {
                // 排序字段，跳过
                continue;
            }

            FieldColumn fieldColumn = findColumn(fieldName);
            if (fieldColumn == null) {
                throw new IllegalArgumentException("实体字段不存在：" + fieldName);
            }

            // 获取参数值
            Object value = null;
            if (!"IS NULL".equals(operator) && !"IS NOT NULL".equals(operator)) {
                if (i < arguments.length) {
                    value = arguments[i];
                }
            }

            conditions.add(new Condition(fieldColumn.name(), operator, value, logicalOperator));
        }

        return conditions;
    }

    /**
     * 解析字段名列表。
     */
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

    /**
     * 构建WHERE子句。
     */
    private String buildWhereSql(List<Condition> conditions) {
        if (conditions.isEmpty()) {
            return logicalNotDeleteSql();
        }

        StringBuilder where = new StringBuilder();
        where.append(" WHERE ");

        for (int i = 0; i < conditions.size(); i++) {
            Condition condition = conditions.get(i);
            if (i > 0) {
                where.append(" ").append(condition.logicalOperator()).append(" ");
            }

            if ("IS NULL".equals(condition.operator()) || "IS NOT NULL".equals(condition.operator())) {
                where.append(condition.column()).append(" ").append(condition.operator());
            } else if ("IN".equals(condition.operator())) {
                where.append(condition.column()).append(" IN (").append(":").append(condition.column()).append(")");
            } else if ("BETWEEN".equals(condition.operator())) {
                where.append(condition.column()).append(" BETWEEN :").append(condition.column())
                        .append("Start AND :").append(condition.column()).append("End");
            } else {
                where.append(condition.column()).append(" ").append(condition.operator())
                        .append(" :").append(condition.column());
            }
        }

        // 添加逻辑删除条件
        if (metadata.logicDeleteColumn() != null) {
            where.append(" AND ").append(metadata.logicDeleteColumn().name()).append(" = :logicNotDeleteValue");
        }

        return where.toString();
    }

    /**
     * 构建参数Map。
     */
    private Map<String, Object> buildParameters(List<Condition> conditions) {
        Map<String, Object> parameters = new LinkedHashMap<>();

        for (Condition condition : conditions) {
            if ("IS NULL".equals(condition.operator()) || "IS NOT NULL".equals(condition.operator())) {
                continue;
            }
            if ("IN".equals(condition.operator())) {
                parameters.put(condition.column(), condition.value());
            } else if ("BETWEEN".equals(condition.operator())) {
                // 需要处理BETWEEN的两个参数
                if (condition.value() instanceof Object[] array && array.length == 2) {
                    parameters.put(condition.column() + "Start", array[0]);
                    parameters.put(condition.column() + "End", array[1]);
                }
            } else {
                parameters.put(condition.column(), condition.value());
            }
        }

        if (metadata.logicDeleteColumn() != null) {
            parameters.put("logicNotDeleteValue", getLogicNotDeleteValue());
        }

        return parameters;
    }

    /**
     * 提取OrderBy部分。
     */
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
        String[] parts = orderByPart.split("(?=Asc|Desc)(?<!^)");

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
            }
        }

        if (orderClauses.isEmpty()) {
            return "";
        }

        return " ORDER BY " + String.join(", ", orderClauses);
    }

    /**
     * 查找字段列。
     */
    private FieldColumn findColumn(String fieldName) {
        return metadata.columns().stream()
                .filter(col -> col.field().getName().equalsIgnoreCase(fieldName)
                        || col.name().equalsIgnoreCase(fieldName)
                        || col.name().equalsIgnoreCase(camelToUnderline(fieldName)))
                .findFirst()
                .orElse(null);
    }

    /**
     * 生成SELECT列。
     */
    private String selectColumns() {
        return metadata.columns().stream()
                .map(FieldColumn::name)
                .collect(Collectors.joining(", "));
    }

    /**
     * 生成逻辑删除条件。
     */
    private String logicalNotDeleteSql() {
        if (metadata.logicDeleteColumn() == null) {
            return "";
        }
        return " WHERE " + metadata.logicDeleteColumn().name() + " = :logicNotDeleteValue";
    }

    /**
     * 获取逻辑删除值。
     */
    private Object getLogicDeleteValue() {
        // 默认值，可以从配置中获取
        return 1;
    }

    /**
     * 获取逻辑未删除值。
     */
    private Object getLogicNotDeleteValue() {
        // 默认值，可以从配置中获取
        return 0;
    }

    /**
     * 驼峰转下划线。
     */
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

    /**
     * 解析结果。
     */
    public record ParsedMethod(String sql, Map<String, Object> parameters, boolean isQuery) {
    }

    /**
     * 条件定义。
     */
    private record Condition(String column, String operator, Object value, String logicalOperator) {
    }
}
