package com.wcdk.r2dbc.core.xml;

import com.wcdk.r2dbc.config.WcdkR2dbcProperties;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.util.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * XML 仓储语句注册表。
 *
 * @author WCDK
 * @date 2026/7/21
 * @version 1.0
 **/
public class RepositoryXmlRegistry {

    private final Map<String, RepositoryStatement> statements = new LinkedHashMap<>();

    public RepositoryXmlRegistry(ResourcePatternResolver resourcePatternResolver, WcdkR2dbcProperties properties) {
        load(resourcePatternResolver, properties);
    }

    public Optional<RepositoryStatement> find(Class<?> repositoryInterface, String methodName) {
        return Optional.ofNullable(statements.get(repositoryInterface.getName() + "." + methodName));
    }

    private void load(ResourcePatternResolver resourcePatternResolver, WcdkR2dbcProperties properties) {
        String mapperLocations = properties.getMapperLocations();
        if (!StringUtils.hasText(mapperLocations)) {
            return;
        }
        try {
            for (String location : StringUtils.commaDelimitedListToStringArray(mapperLocations)) {
                for (Resource resource : resourcePatternResolver.getResources(location.trim())) {
                    if (resource.exists() && resource.isReadable()) {
                        load(resource);
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("加载 R2DBC XML 仓储语句失败", e);
        }
    }

    private void load(Resource resource) {
        try (InputStream inputStream = resource.getInputStream()) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder().parse(new InputSource(inputStream));
            Element root = document.getDocumentElement();
            if (root == null || !"repository".equals(root.getTagName())) {
                return;
            }
            String namespace = root.getAttribute("namespace");
            if (!StringUtils.hasText(namespace)) {
                throw new IllegalStateException("R2DBC XML 仓储缺少 namespace：" + resource.getDescription());
            }
            NodeList children = root.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (node instanceof Element element) {
                    register(namespace, element, resource);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("解析 R2DBC XML 仓储失败：" + resource.getDescription(), e);
        }
    }

    private void register(String namespace, Element element, Resource resource) {
        SqlCommandType commandType = commandType(element.getTagName());
        if (commandType == null) {
            return;
        }
        String id = element.getAttribute("id");
        if (!StringUtils.hasText(id)) {
            id = element.getTagName();
        }
        String sql = element.getTextContent();
        if (!StringUtils.hasText(sql)) {
            throw new IllegalStateException("R2DBC XML SQL 不能为空：" + namespace + "." + id);
        }
        RepositoryStatement statement = new RepositoryStatement(namespace, id, commandType, sql.strip());
        RepositoryStatement previous = statements.putIfAbsent(statement.statementId(), statement);
        if (previous != null) {
            throw new IllegalStateException("重复的 R2DBC XML SQL：" + statement.statementId() + "，资源：" + resource.getDescription());
        }
    }

    private SqlCommandType commandType(String tagName) {
        try {
            return SqlCommandType.valueOf(tagName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
