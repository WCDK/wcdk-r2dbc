package com.wcdk.r2dbc.repository;
import com.wcdk.r2dbc.execution.SqlCommandType;

import com.wcdk.r2dbc.repository.metadata.RepositoryMetadata;
import com.wcdk.r2dbc.repository.metadata.RepositoryMetadata.FieldColumn;
import com.wcdk.r2dbc.execution.SqlParameter;
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
 * - 查询：findBy[Field] / findBy[Field1]And[Field2]
 * - 计数：countBy[Field] / countBy[Field1]And[Field2]
 * - 判断是否存在：existsBy[Field] / existsBy[Field1]And[Field2]
 * - 删除：deleteBy[Field] (逻辑删除)
 * - 按主键更新：updateBy[Field]ById
 * - 按条件更新：updateBy[Field1]And[Field2]By[Condition]
 * - 排序：orderBy[Field]Asc / orderBy[Field]Desc
 *
 * @author WCDK
 *
 * @version 2.0
 */
public class CustomMethodResolver {

    private static final Pattern METHOD_PATTERN = Pattern.compile(
            "^(find|count|exists|delete|update)(All|One)?(By|OrderBy)(.+)?$"
    );

    private final RepositoryMetadata metadata;
    private final List<FieldToken> fieldTokens;

    private final Object logicDeleteValue;

    private final Object logicNotDeleteValue;

    public CustomMethodResolver(RepositoryMetadata metadata, Object logicDeleteValue, Object logicNotDeleteValue) {
        this.metadata = metadata;
        this.fieldTokens = buildTokens();
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
        return resolve(compile(method), method, arguments);
    }

    public static boolean supports(Method method) {
        Matcher matcher = METHOD_PATTERN.matcher(method.getName());
        return matcher.matches() && matcher.group(2) == null;
    }

    public DerivedQueryDefinition compile(Method method) {
        Matcher matcher = METHOD_PATTERN.matcher(method.getName());
        if (!matcher.matches() || matcher.group(2) != null) {
            throw new IllegalArgumentException("不支持的派生仓库方法：" + method.toGenericString() + "；请使用 findBy/countBy/existsBy/deleteBy 或 updateBy<更新字段>By<条件>，不支持 findAllBy/findOneBy/updateXxxById");
        }
        validateMethodNameLength(method.getName());
        String operation = matcher.group(1);
        String fieldPart = matcher.group(4);
        if ("OrderBy".equals(matcher.group(3))) fieldPart = "OrderBy" + (fieldPart == null ? "" : fieldPart);
        String conditionPart = fieldPart;
        List<String> setFields = List.of();
        if ("update".equals(operation)) {
            int byIndex = -1;
            for (int position = fieldPart == null ? -1 : fieldPart.lastIndexOf("By"); position > 0;
                 position = fieldPart.lastIndexOf("By", position - 1)) {
                try {
                    setFields = parseFieldNames(fieldPart.substring(0, position));
                    byIndex = position;
                    break;
                } catch (IllegalArgumentException error) {
                    if (error.getMessage().contains("歧义")) throw error;
                    // 下一候选切分继续按字段集合校验。
                }
            }
            if (byIndex <= 0) throw new IllegalArgumentException("update方法必须使用 updateBy<更新字段>By<条件>：" + method.getName());
            conditionPart = fieldPart.substring(byIndex + 2);
            if ("Id".equals(conditionPart)) conditionPart = metadata.requireIdColumn().field().getName();
        }
        if ("delete".equals(operation) && (conditionPart == null || conditionPart.isEmpty()))
            throw new IllegalArgumentException("delete方法必须指定条件：" + method.getName());
        List<DerivedQueryDefinition.ConditionDefinition> conditions = compileConditions(conditionPart);
        List<DerivedQueryDefinition.OrderDefinition> orders = compileOrders(fieldPart);
        int count = setFields.size() + conditions.stream().mapToInt(DerivedQueryDefinition.ConditionDefinition::argumentCount).sum();
        if (count != method.getParameterCount()) throw new IllegalArgumentException("方法 " + method.getName()
                + " 参数数量不匹配：需要 " + count + "，声明 " + method.getParameterCount());
        validateReturnType(method, operation);
        return new DerivedQueryDefinition(operation, fieldPart, conditions, setFields, orders);
    }

    /**
     * 使用启动阶段生成的 派生查询 模型绑定运行时参数。
     *
     * @param plan 派生查询 编译模型
     * @param arguments 方法参数
     * @return 参数绑定后的 SQL 执行定义
     * @author wcdk
     */
    public ParsedMethod resolveCompiled(com.wcdk.r2dbc.repository.plan.DerivedQueryPlanModel plan,
                                        Object[] arguments) {
        return resolve(plan.definition(), plan.method(), arguments);
    }
    public ParsedMethod resolve(DerivedQueryDefinition plan, Method method, Object[] arguments) {
        int expected = plan.setFields().size() + plan.conditions().stream()
                .mapToInt(DerivedQueryDefinition.ConditionDefinition::argumentCount).sum();
        if (arguments == null || expected != arguments.length)
            throw new IllegalArgumentException("方法 " + method.getName() + " 参数数量不匹配：需要 " + expected);
        return switch (plan.operation()) {
            case "find" -> resolveFind(method, plan.fieldPart(), arguments, plan.conditions(), plan.orders());
            case "count" -> resolveCount(method, plan.fieldPart(), arguments, plan.conditions());
            case "exists" -> resolveExists(method, plan.fieldPart(), arguments, plan.conditions());
            case "delete" -> resolveDelete(method, plan.fieldPart(), arguments, plan.conditions());
            case "update" -> resolveUpdate(method, arguments, plan);
            default -> throw new UnsupportedOperationException("不支持的派生操作：" + plan.operation());
        };
    }
    // ==================== 查询 ====================

    private ParsedMethod resolveFind(Method method, String fieldPart, Object[] arguments, List<DerivedQueryDefinition.ConditionDefinition> compiledConditions, List<DerivedQueryDefinition.OrderDefinition> orders) {
        if (fieldPart == null || fieldPart.isEmpty()) {
            String sql = "SELECT " + selectColumns() + " FROM " + metadata.tableName()
                    + logicalNotDeleteSql();
            return new ParsedMethod(sql, buildParameters(List.of()), SqlCommandType.SELECT);
        }

        List<Condition> conditions = bindConditions(compiledConditions, arguments, 0, method.getName());
        String orderBySql = renderOrders(orders);
        String whereSql = buildWhereSql(conditions, true);

        String sql = "SELECT " + selectColumns() + " FROM " + metadata.tableName()
                + whereSql + orderBySql;

        Map<String, Object> parameters = buildParameters(conditions);
        return new ParsedMethod(sql, parameters, SqlCommandType.SELECT);
    }

    // ==================== 计数 ====================

    private ParsedMethod resolveCount(Method method, String fieldPart, Object[] arguments, List<DerivedQueryDefinition.ConditionDefinition> compiledConditions) {
        if (fieldPart == null || fieldPart.isEmpty()) {
            String sql = "SELECT COUNT(1) FROM " + metadata.tableName()
                    + logicalNotDeleteSql();
            return new ParsedMethod(sql, buildParameters(List.of()), SqlCommandType.SELECT);
        }

        List<Condition> conditions = bindConditions(compiledConditions, arguments, 0, method.getName());
        String whereSql = buildWhereSql(conditions, true);

        String sql = "SELECT COUNT(1) FROM " + metadata.tableName() + whereSql;
        Map<String, Object> parameters = buildParameters(conditions);
        return new ParsedMethod(sql, parameters, SqlCommandType.SELECT);
    }

    // ==================== 判断是否存在 ====================

    private ParsedMethod resolveExists(Method method, String fieldPart, Object[] arguments, List<DerivedQueryDefinition.ConditionDefinition> compiledConditions) {
        List<Condition> conditions = bindConditions(compiledConditions, arguments, 0, method.getName());
        String whereSql = buildWhereSql(conditions, true);
        String query = "SELECT 1 FROM " + metadata.tableName() + whereSql
                + DialectPagination.render(metadata.dialect(), 1, null);
        String sql = metadata.dialect().existsResult(query);
        Map<String, Object> parameters = buildParameters(conditions);
        return new ParsedMethod(sql, parameters, SqlCommandType.SELECT);
    }

    // ==================== 删除 ====================

    private ParsedMethod resolveDelete(Method method, String fieldPart, Object[] arguments, List<DerivedQueryDefinition.ConditionDefinition> compiledConditions) {
        if (fieldPart == null || fieldPart.isEmpty()) {
            throw new UnsupportedOperationException("delete方法必须指定条件");
        }

        List<Condition> conditions = bindConditions(compiledConditions, arguments, 0, method.getName());
        String whereSql = buildWhereSql(conditions, true);
        Map<String, Object> parameters = buildParameters(conditions);
        FieldColumn logicDeleteColumn = metadata.logicDeleteColumn();
        if (logicDeleteColumn == null) {
            return new ParsedMethod("DELETE FROM " + metadata.tableName() + whereSql,
                    parameters, SqlCommandType.DELETE);
        }

        String sql = "UPDATE " + metadata.tableName()
                + " SET " + logicDeleteColumn.name() + " = :logicDeleteValue"
                + whereSql;
        parameters.put("logicDeleteValue", typed(logicDeleteColumn, logicDeleteValue));
        return new ParsedMethod(sql, parameters, SqlCommandType.UPDATE);
    }
    // ==================== 更新 ====================

    private ParsedMethod resolveUpdate(Method method, Object[] arguments, DerivedQueryDefinition plan) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        List<String> clauses = new ArrayList<>();
        for (int i = 0; i < plan.setFields().size(); i++) {
            FieldColumn column = metadata.columnByName(plan.setFields().get(i));
            clauses.add(column.name() + " = :set" + i);
            parameters.put("set" + i, typed(column, arguments[i]));
        }
        List<Condition> conditions = bindConditions(plan.conditions(), arguments, plan.setFields().size(), method.getName());
        parameters.putAll(buildParameters(conditions));
        return new ParsedMethod("UPDATE " + metadata.tableName() + " SET " + String.join(", ", clauses)
                + buildWhereSql(conditions, true), parameters, SqlCommandType.UPDATE);
    }

    private List<DerivedQueryDefinition.ConditionDefinition> compileConditions(String fieldPart) {
        if (fieldPart == null || fieldPart.isEmpty()) return List.of();
        String text = conditionPart(fieldPart);
        List<DerivedQueryDefinition.ConditionDefinition> result = new ArrayList<>();
        int offset = 0;
        String logical = "And";
        while (offset < text.length()) {
            boolean matched = false;
            for (FieldToken candidate : matchingTokens(text, offset)) {
                FieldColumn column = candidate.column();
                String token = candidate.token();
                if (!text.regionMatches(true, offset, token, 0, token.length())) continue;
                int start = offset + token.length();
                for (DerivedOperator operator : DerivedOperator.values()) {
                    if (!text.startsWith(operator.suffix(), start)) continue;
                    int end = start + operator.suffix().length();
                    if (end != text.length() && !text.startsWith("And", end) && !text.startsWith("Or", end)) continue;
                    result.add(new DerivedQueryDefinition.ConditionDefinition(column.field().getName(), operator.sql(), logical, operator.argumentCount()));
                    offset = end;
                    matched = true;
                    break;
                }
                if (matched) break;
            }
            if (!matched) {
                String remaining = text.substring(offset);
                String known = longestTokens().stream().map(FieldToken::token)
                        .filter(token -> remaining.regionMatches(true, 0, token, 0, token.length())).findFirst().orElse(null);
                if (known != null) throw new IllegalArgumentException("算子不支持：" + remaining.substring(known.length())
                        + "；支持算子：" + java.util.Arrays.stream(DerivedOperator.values())
                        .map(operator -> operator.suffix().isEmpty() ? "(等值)" : operator.suffix()).toList());
                throw new IllegalArgumentException("实体字段不存在：" + remaining + "；候选字段："
                        + metadata.columns().stream().map(c -> c.field().getName()).toList());
            }
            if (offset < text.length()) {
                logical = text.startsWith("And", offset) ? "And" : "Or";
                offset += logical.length();
                if (offset == text.length()) throw new IllegalArgumentException("方法名中存在空条件字段：" + fieldPart);
            }
        }
        return List.copyOf(result);
    }

    private record FieldToken(FieldColumn column, String token) {}

    private List<FieldToken> longestTokens() { return fieldTokens; }

    private List<FieldToken> buildTokens() {
        return metadata.columns().stream().flatMap(column ->
                java.util.stream.Stream.of(column.field().getName(), capitalize(column.field().getName()), column.rawName(), column.rawName().replace("_", "")).distinct()
                        .map(token -> new FieldToken(column, token)))
                .sorted(java.util.Comparator.comparingInt((FieldToken token) -> token.token().length()).reversed())
                .toList();
    }

    private static String capitalize(String name) {
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private List<FieldToken> matchingTokens(String text, int offset) {
        List<FieldToken> matches = longestTokens().stream()
                .filter(t -> text.regionMatches(true, offset, t.token(), 0, t.token().length())).toList();
        if (matches.isEmpty()) return matches;
        int length = matches.getFirst().token().length();
        List<FieldToken> longest = matches.stream().filter(t -> t.token().length() == length).toList();
        List<FieldToken> exact = longest.stream()
                .filter(t -> text.startsWith(t.column().field().getName(), offset)
                        || text.startsWith(capitalize(t.column().field().getName()), offset)).toList();
        List<FieldToken> preferred = exact.isEmpty() ? longest : exact;
        List<String> fields = preferred.stream().map(t -> t.column().field().getName()).distinct().toList();
        if (fields.size() > 1) throw new IllegalArgumentException("字段令牌有歧义：" + text.substring(offset)
                + "；候选字段：" + fields);
        List<FieldToken> result = new ArrayList<>(preferred);
        matches.stream().filter(t -> t.token().length() < length).forEach(result::add);
        return result;
    }

    private List<Condition> bindConditions(List<DerivedQueryDefinition.ConditionDefinition> definitions,
                                           Object[] arguments, int argOffset, String methodName) {
        int expected = argOffset + definitions.stream().mapToInt(DerivedQueryDefinition.ConditionDefinition::argumentCount).sum();
        if (arguments == null || expected != arguments.length)
            throw new IllegalArgumentException("方法 " + methodName + " 参数数量不匹配：需要 " + expected);
        List<Condition> conditions = new ArrayList<>();
        int index = argOffset;
        for (DerivedQueryDefinition.ConditionDefinition definition : definitions) {
            Object value = definition.argumentCount() == 0 ? null : arguments[index++];
            Object second = definition.argumentCount() == 2 ? arguments[index++] : null;
            FieldColumn column = metadata.columnByName(definition.fieldName());
            conditions.add(new Condition(column.name(), definition.operator(), value, second, definition.logicalOperator()));
        }
        if (index != arguments.length) throw new IllegalArgumentException("方法 " + methodName + " 参数数量不匹配");
        return conditions;
    }
    private List<String> parseFieldNames(String text) {
        List<DerivedQueryDefinition.ConditionDefinition> fields = compileConditions(text);
        if (fields.isEmpty() || fields.stream().anyMatch(field -> !"=".equals(field.operator()) || !"And".equals(field.logicalOperator())))
            throw new IllegalArgumentException("更新字段只能用 And 连接：" + text);
        return fields.stream().map(DerivedQueryDefinition.ConditionDefinition::fieldName).toList();
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
        return value == null
                ? SqlParameter.nullOf(column.field().getType())
                : LogicDeleteValueConverter.convert(value, column.field().getType());
    }

    // ==================== 排序 ====================

    private List<DerivedQueryDefinition.OrderDefinition> compileOrders(String fieldPart) {
        if (fieldPart == null || !fieldPart.contains("OrderBy")) return List.of();
        String text = fieldPart.substring(fieldPart.indexOf("OrderBy") + 7);
        if (text.isEmpty()) throw new IllegalArgumentException("排序字段不能为空");
        List<DerivedQueryDefinition.OrderDefinition> result = new ArrayList<>();
        int offset = 0;
        while (offset < text.length()) {
            boolean matched = false;
            for (FieldToken candidate : matchingTokens(text, offset)) {
                FieldColumn column = candidate.column();
                String token = candidate.token();
                if (!text.regionMatches(true, offset, token, 0, token.length())) continue;
                int end = offset + token.length();
                boolean ascending = true;
                if (text.startsWith("Asc", end)) end += 3;
                else if (text.startsWith("Desc", end)) { end += 4; ascending = false; }
                else if (end != text.length()) continue;
                result.add(new DerivedQueryDefinition.OrderDefinition(column.field().getName(), ascending));
                offset = end;
                if (text.startsWith("And", offset)) {
                    offset += 3;
                    if (offset == text.length()) throw new IllegalArgumentException("排序字段不能为空");
                }
                matched = true;
                break;
            }
            if (!matched) throw new IllegalArgumentException("排序字段不存在：" + text.substring(offset));
        }
        return List.copyOf(result);
    }

    private String renderOrders(List<DerivedQueryDefinition.OrderDefinition> orders) {
        return orders.isEmpty() ? "" : " ORDER BY " + orders.stream()
                .map(order -> metadata.columnByName(order.fieldName()).name() + (order.ascending() ? " ASC" : " DESC"))
                .collect(Collectors.joining(", "));
    }

    private String conditionPart(String fieldPart) {
        int orderByIndex = fieldPart.indexOf("OrderBy");
        return orderByIndex < 0 ? fieldPart : fieldPart.substring(0, orderByIndex);
    }

    // ==================== 工具方法 ====================

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

    // ==================== 启动期校验 ====================

    /**
     * 校验方法是否支持，不支持时在启动期快速失败。
     *
     * @param method 方法
     */
    public void validateMethod(Method method) {
        if (supports(method)) compile(method);
    }

    private void validateMethodNameLength(String methodName) {
        if (methodName.length() > 128) {
            throw new IllegalArgumentException("方法名过长（超过128字符）：" + methodName);
        }
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

    // ==================== 类型定义 ====================

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
