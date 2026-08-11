package com.wcdk.r2dbc.core.xml;

import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.Expression;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compiles and renders the supported dynamic XML SQL nodes. */
public final class DynamicSqlSource {

    private static final Pattern PARAMETER_PATTERN = Pattern.compile("#\\{\\s*([a-zA-Z0-9_.$]+)\\s*}");
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
        return new RenderedSql(sql.toString().strip(), context.additionalParameters());
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
            SqlNode contents = parseChildren(element);
            nodes.add(switch (element.getTagName()) {
                case "if" -> new IfSqlNode(requiredAttribute(element, "test"), contents);
                case "where" -> new WhereSqlNode(contents);
                case "foreach" -> new ForeachSqlNode(
                        requiredAttribute(element, "collection"),
                        attribute(element, "item", "item"),
                        attribute(element, "index", "index"),
                        element.getAttribute("open"),
                        element.getAttribute("separator"),
                        element.getAttribute("close"),
                        contents);
                default -> throw new IllegalArgumentException("Unsupported dynamic SQL element: " + element.getTagName());
            });
        }
        return new MixedSqlNode(nodes);
    }

    private static String requiredAttribute(Element element, String name) {
        String value = element.getAttribute(name);
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Dynamic SQL element <" + element.getTagName() + "> requires attribute " + name);
        }
        return value;
    }

    private static String attribute(Element element, String name, String defaultValue) {
        String value = element.getAttribute(name);
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    public record RenderedSql(String sql, Map<String, Object> additionalParameters) {
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
                throw new IllegalArgumentException("Dynamic SQL does not allow literal ${} substitution; use #{} binding");
            }
        }

        @Override
        public void apply(RenderContext context, StringBuilder sql) {
            Matcher matcher = PARAMETER_PATTERN.matcher(text);
            StringBuilder rendered = new StringBuilder();
            while (matcher.find()) {
                String path = matcher.group(1);
                String rootName = path.split("\\.", 2)[0];
                if (!context.localNames().contains(rootName)) {
                    matcher.appendReplacement(rendered, Matcher.quoteReplacement(matcher.group()));
                    continue;
                }
                String parameterName = context.addLocalParameter(context.value(path));
                matcher.appendReplacement(rendered, Matcher.quoteReplacement("#{" + parameterName + "}"));
            }
            matcher.appendTail(rendered);
            sql.append(rendered);
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
            List<Iteration> iterations = iterations(context.value(collectionExpression));
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
                throw new IllegalArgumentException("Dynamic SQL foreach collection is not iterable: " + source.getClass().getName());
            }
            return result;
        }
    }

    private record Iteration(Object index, Object value) {
    }

    private record RenderContext(Map<String, Object> bindings, Set<String> localNames,
                                 Map<String, Object> additionalParameters, AtomicInteger sequence) {

        static RenderContext root(Map<String, Object> parameters) {
            Map<String, Object> bindings = parameters == null ? Map.of() : new LinkedHashMap<>(parameters);
            return new RenderContext(bindings, Set.of(), new LinkedHashMap<>(), new AtomicInteger());
        }

        RenderContext child(String itemName, Object itemValue, String indexName, Object indexValue) {
            Map<String, Object> childBindings = new LinkedHashMap<>(bindings);
            childBindings.put(itemName, itemValue);
            childBindings.put(indexName, indexValue);
            Set<String> childLocalNames = new LinkedHashSet<>(localNames);
            childLocalNames.add(itemName);
            childLocalNames.add(indexName);
            return new RenderContext(childBindings, childLocalNames, additionalParameters, sequence);
        }

        StandardEvaluationContext evaluationContext() {
            StandardEvaluationContext context = new StandardEvaluationContext(bindings);
            context.addPropertyAccessor(new MapAccessor());
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
    }
}
