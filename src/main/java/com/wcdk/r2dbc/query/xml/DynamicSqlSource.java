package com.wcdk.r2dbc.query.xml;

import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.Expression;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.util.StringUtils;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * 动态 SQL 源。
 * @author wcdk
 */
public final class DynamicSqlSource {

    private static final Pattern SQL_FUNCTION_PATTERN = Pattern.compile(
            "(?i)[a-z_][a-z0-9_$.]*\\s*\\((?:[^'\"()]|\\([^()]*\\))*\\)");
    private static final Pattern PROPERTY_PATH_PATTERN = Pattern.compile("[a-zA-Z0-9_.$]+");
    private static final SpelExpressionParser EXPRESSION_PARSER = new SpelExpressionParser();

    private final SqlNode root;

    private DynamicSqlSource(SqlNode root) {
        this.root = root;
    }

    public static DynamicSqlSource parse(Element statementElement) {
        return new DynamicSqlSource(parseChildren(statementElement));
    }

    public static DynamicSqlSource staticSql(String sql) {
        return new DynamicSqlSource(new TextSqlNode(sql));
    }

    public RenderedSql render(Map<String, Object> parameters) {
        RenderContext context = RenderContext.root(parameters);
        StringBuilder sql = new StringBuilder();
        root.apply(context, sql);
        return new RenderedSql(sql.toString().strip(), context.additionalParameters(), Set.copyOf(context.usedParameters()));
    }

    private static SqlNode parseChildren(Element parent) {
        List<SqlNode> nodes = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                if (!child.getTextContent().isEmpty()) {
                    nodes.add(new TextSqlNode(child.getTextContent()));
                }
                continue;
            }
            if (!(child instanceof Element element)) {
                continue;
            }
            nodes.add(switch (element.getTagName()) {
                case "if" -> new IfSqlNode(requiredAttribute(element, "test"), parseChildren(element));
                case "where" -> new WhereSqlNode(parseChildren(element));
                case "choose" -> parseChoose(element);
                case "foreach" -> new ForeachSqlNode(
                        requiredAttribute(element, "collection"),
                        attribute(element, "item", "item"),
                        attribute(element, "index", "index"),
                        element.getAttribute("open"),
                        element.getAttribute("separator"),
                        element.getAttribute("close"),
                        parseChildren(element));
                case "when", "otherwise" -> throw new IllegalArgumentException(
                        "<" + element.getTagName() + "> 必须位于 <choose> 内部");
                default -> throw new IllegalArgumentException("不支持的动态SQL元素: " + element.getTagName());
            });
        }
        return new MixedSqlNode(nodes);
    }

    /**
     * 解析  choose 分支节点。
     * @author wcdk
     */
    private static SqlNode parseChoose(Element chooseElement) {
        List<ChooseBranch> branches = new ArrayList<>();
        boolean hasOtherwise = false;
        NodeList children = chooseElement.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                if (StringUtils.hasText(child.getTextContent())) {
                    throw new IllegalArgumentException("<choose> 只允许包含 <when> 或 <otherwise>");
                }
                continue;
            }
            if (!(child instanceof Element branchElement)) {
                continue;
            }
            switch (branchElement.getTagName()) {
                case "when" -> branches.add(new ChooseBranch(
                        new IfSqlNode(requiredAttribute(branchElement, "test"), parseChildren(branchElement)), false));
                case "otherwise" -> {
                    if (hasOtherwise) {
                        throw new IllegalArgumentException("<choose> 最多只能包含一个 <otherwise>");
                    }
                    hasOtherwise = true;
                    branches.add(new ChooseBranch(new AlwaysSqlNode(parseChildren(branchElement)), true));
                }
                default -> throw new IllegalArgumentException("<choose> 只允许包含 <when> 或 <otherwise>");
            }
        }
        if (branches.isEmpty()) {
            throw new IllegalArgumentException("<choose> 至少需要包含一个 <when> 或 <otherwise>");
        }
        return new ChooseSqlNode(List.copyOf(branches));
    }
    private static String requiredAttribute(Element element, String name) {
        String value = element.getAttribute(name);
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("动态SQL元素 <" + element.getTagName() + "> 需要属性 " + name);
        }
        return value;
    }

    private static String attribute(Element element, String name, String defaultValue) {
        String value = element.getAttribute(name);
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    public record RenderedSql(String sql, Map<String, Object> additionalParameters, Set<String> usedParameters) {
    }

    private interface SqlNode {
        void apply(RenderContext context, StringBuilder sql);
    }

    private record MixedSqlNode(List<SqlNode> nodes) implements SqlNode {
        @Override
        public void apply(RenderContext context, StringBuilder sql) {
            nodes.forEach(node -> node.apply(context, sql));
        }
    }

    private record TextSqlNode(String text) implements SqlNode {
        private TextSqlNode {
            if (text.contains("${")) {
                throw new IllegalArgumentException("动态SQL不允许使用字面量 ${} 替换；请使用 #{} 绑定");
            }
        }

        @Override
        public void apply(RenderContext context, StringBuilder sql) {
            sql.append(context.renderText(text));
        }
    }

    private record IfSqlNode(Expression test, SqlNode contents) implements SqlNode {
        private IfSqlNode(String test, SqlNode contents) {
            this(EXPRESSION_PARSER.parseExpression(test), contents);
        }

        @Override
        public void apply(RenderContext context, StringBuilder sql) {
            if (Boolean.TRUE.equals(test.getValue(context.evaluationContext(), Boolean.class))) {
                contents.apply(context, sql);
            }
        }
    }

    private record AlwaysSqlNode(SqlNode contents) implements SqlNode {
        @Override
        public void apply(RenderContext context, StringBuilder sql) {
            contents.apply(context, sql);
        }
    }

    private record ChooseBranch(SqlNode node, boolean otherwise) {
    }

    private record ChooseSqlNode(List<ChooseBranch> branches) implements SqlNode {
        @Override
        public void apply(RenderContext context, StringBuilder sql) {
            for (ChooseBranch branch : branches) {
                if (branch.otherwise() || matches(branch.node(), context)) {
                    branch.node().apply(context, sql);
                    return;
                }
            }
        }

        private boolean matches(SqlNode node, RenderContext context) {
            return node instanceof IfSqlNode ifNode
                    && Boolean.TRUE.equals(ifNode.test().getValue(context.evaluationContext(), Boolean.class));
        }
    }
    private record WhereSqlNode(SqlNode contents) implements SqlNode {
        @Override
        public void apply(RenderContext context, StringBuilder sql) {
            StringBuilder body = new StringBuilder();
            contents.apply(context, body);
            String value = body.toString().strip().replaceFirst("(?i)^(AND|OR)\\b\\s*", "");
            if (StringUtils.hasText(value)) {
                sql.append(" WHERE ").append(value).append(' ');
            }
        }
    }

    private record ForeachSqlNode(String collectionExpression, String item, String index,
                                  String open, String separator, String close, SqlNode contents) implements SqlNode {
        @Override
        public void apply(RenderContext context, StringBuilder sql) {
            Object source = context.value(collectionExpression);
            if (source == null) throw new IllegalArgumentException("foreach 集合参数为 null：" + collectionExpression);
            List<Iteration> iterations = iterations(source);
            if (iterations.isEmpty()) {
                return;
            }
            sql.append(open);
            for (int i = 0; i < iterations.size(); i++) {
                if (i > 0) {
                    sql.append(separator);
                }
                Iteration iteration = iterations.get(i);
                contents.apply(context.child(item, iteration.value(), index, iteration.index()), sql);
            }
            sql.append(close);
        }

        private static List<Iteration> iterations(Object source) {
            if (source == null) {
                return List.of();
            }
            List<Iteration> result = new ArrayList<>();
            if (source instanceof Map<?, ?> map) {
                map.forEach((key, value) -> result.add(new Iteration(key, value)));
            } else if (source instanceof Iterable<?> iterable) {
                int index = 0;
                for (Object value : iterable) {
                    result.add(new Iteration(index++, value));
                }
            } else if (source.getClass().isArray()) {
                for (int i = 0; i < Array.getLength(source); i++) {
                    result.add(new Iteration(i, Array.get(source, i)));
                }
            } else {
                throw new IllegalArgumentException("动态SQL foreach集合不可迭代: " + source.getClass().getName());
            }
            return result;
        }
    }

    private record Iteration(Object index, Object value) {
    }

    private record RenderContext(Map<String, Object> bindings, Set<String> localNames,
                                 Map<String, Object> additionalParameters, AtomicInteger sequence, Set<String> usedParameters) {

        static RenderContext root(Map<String, Object> parameters) {
            Map<String, Object> bindings = parameters == null ? Map.of() : new LinkedHashMap<>(parameters);
            return new RenderContext(bindings, Set.of(), new LinkedHashMap<>(), new AtomicInteger(), new LinkedHashSet<>());
        }

        RenderContext child(String itemName, Object itemValue, String indexName, Object indexValue) {
            Map<String, Object> childBindings = new LinkedHashMap<>(bindings);
            childBindings.put(itemName, itemValue);
            childBindings.put(indexName, indexValue);
            Set<String> childLocalNames = new LinkedHashSet<>(localNames);
            childLocalNames.add(itemName);
            childLocalNames.add(indexName);
            return new RenderContext(childBindings, childLocalNames, additionalParameters, sequence, usedParameters);
        }

        StandardEvaluationContext evaluationContext() {
            StandardEvaluationContext context = new StandardEvaluationContext(bindings);
            context.addPropertyAccessor(new MapAccessor() {
                @Override
                public org.springframework.expression.TypedValue read(org.springframework.expression.EvaluationContext context,
                        Object target, String name) throws org.springframework.expression.AccessException {
                    if (target == bindings && !localNames.contains(name)) usedParameters.add(name);
                    return super.read(context, target, name);
                }
            });
            context.setMethodResolvers(List.of());
            context.setBeanResolver(null);
            context.setTypeLocator(typeName -> {
                throw new EvaluationException("Dynamic SQL expressions cannot access types: " + typeName);
            });
            return context;
        }

        Object value(String expression) {
            return EXPRESSION_PARSER.parseExpression(expression).getValue(evaluationContext());
        }

        String addLocalParameter(Object value) {
            String name = "__foreach_" + sequence.getAndIncrement();
            additionalParameters.put(name, value);
            return name;
        }

        String renderText(String text) {
            StringBuilder rendered = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                int protectedEnd = com.wcdk.r2dbc.execution.SqlLexicalScanner.protectedEnd(text, i);
                if (protectedEnd > i) {
                    rendered.append(text, i, protectedEnd);
                    i = protectedEnd - 1;
                    continue;
                }
                char ch = text.charAt(i);
                char next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
                if (ch == '#' && next == '{') {
                    int end = placeholderEnd(text, i + 2);
                    if (end < 0) {
                        throw new IllegalArgumentException("SQL 参数表达式缺少结束符 '}': " + text.substring(i));
                    }
                    String expression = text.substring(i + 2, end).trim();
                    if (expression.isEmpty()) {
                        throw new IllegalArgumentException("SQL 参数表达式不能为空");
                    }
                    if (PROPERTY_PATH_PATTERN.matcher(expression).matches()
                            && !localNames.contains(expression.split("\\.", 2)[0])) {
                        usedParameters.add(expression.split("\\.", 2)[0]);
                        rendered.append(text, i, end + 1);
                        i = end;
                        continue;
                    }
                    rendered.append(renderExpression(expression));
                    i = end;
                    continue;
                }
                if (ch == ':' && isParameterStart(next)
                        && (i == 0 || text.charAt(i - 1) != ':') && next != '=') {
                    int end = i + 2;
                    while (end < text.length() && isParameterPart(text.charAt(end))) end++;
                    usedParameters.add(text.substring(i + 1, end));
                    rendered.append("#{").append(text, i + 1, end).append('}');
                    i = end - 1;
                    continue;
                }
                rendered.append(ch);
            }
            return rendered.toString();
        }

        private int placeholderEnd(String text, int start) {
            char quote = 0;
            for (int i = start; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (quote != 0) {
                    if (ch == quote) {
                        if (i + 1 < text.length() && text.charAt(i + 1) == quote) i++;
                        else quote = 0;
                    }
                } else if (ch == '\'' || ch == '"') quote = ch;
                else if (ch == '}') return i;
            }
            return -1;
        }

        private String renderExpression(String expression) {
            int question = topLevelCharacter(expression, '?');
            if (question >= 0) {
                int colon = topLevelCharacter(expression, ':', question + 1);
                if (colon < 0) {
                    throw new IllegalArgumentException("SQL 参数三元表达式缺少 ':'：" + expression);
                }
                String condition = expression.substring(0, question).trim();
                String whenTrue = expression.substring(question + 1, colon).trim();
                String whenFalse = expression.substring(colon + 1).trim();
                if (whenTrue.isEmpty() || whenFalse.isEmpty()) {
                    throw new IllegalArgumentException("SQL 参数三元表达式分支不能为空：" + expression);
                }
                boolean matches = Boolean.TRUE.equals(EXPRESSION_PARSER.parseExpression(condition)
                        .getValue(evaluationContext(), Boolean.class));
                return renderValue(matches ? whenTrue : whenFalse);
            }
            return renderValue(expression);
        }

        private String renderValue(String expression) {
            if (SQL_FUNCTION_PATTERN.matcher(expression).matches()) {
                for (String local : localNames) {
                    if (Pattern.compile("(?<![a-zA-Z0-9_])" + Pattern.quote(local) + "(?![a-zA-Z0-9_])")
                            .matcher(expression).find()) throw new IllegalArgumentException("foreach 局部参数不允许 SQL 函数内联：" + local);
                }
                return expression;
            }
            Object value = value(expression);
            return "#{" + addLocalParameter(value) + "}";
        }

        private int topLevelCharacter(String expression, char target) {
            return topLevelCharacter(expression, target, 0);
        }

        private int topLevelCharacter(String expression, char target, int start) {
            int depth = 0;
            char quote = 0;
            for (int i = start; i < expression.length(); i++) {
                char ch = expression.charAt(i);
                if (quote != 0) {
                    if (ch == quote) {
                        if (i + 1 < expression.length() && expression.charAt(i + 1) == quote) i++;
                        else quote = 0;
                    }
                    continue;
                }
                if (ch == '\'' || ch == '"') { quote = ch; continue; }
                if (ch == '(' || ch == '[') depth++;
                else if (ch == ')' || ch == ']') depth--;
                else if (ch == target && depth == 0) return i;
            }
            return -1;
        }

        private boolean isParameterStart(char ch) {
            return Character.isLetter(ch) || ch == '_';
        }

        private boolean isParameterPart(char ch) {
            return Character.isLetterOrDigit(ch) || ch == '_';
        }
    }

}
