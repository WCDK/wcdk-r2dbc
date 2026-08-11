package com.wcdk.r2dbc.core.xml;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * resultMap 定义。
 *
 * @author WCDK
 * @date 2026/8/5
 * @version 1.0
 **/
public record ResultMapDefinition(String id, String type, Map<String, String> idMappings,
                                  String discriminatorColumn, Map<String, String> discriminatorMappings) {

    public static class Builder {
        private final String id;
        private final String type;
        private final Map<String, String> idMappings = new LinkedHashMap<>();
        private String discriminatorColumn;
        private final Map<String, String> discriminatorMappings = new LinkedHashMap<>();

        public Builder(String id, String type) {
            this.id = id;
            this.type = type;
        }

        public Builder addIdMapping(String column, String property) {
            idMappings.put(column, property);
            return this;
        }

        public Builder discriminatorColumn(String discriminatorColumn) {
            this.discriminatorColumn = discriminatorColumn;
            return this;
        }

        public Builder addDiscriminatorMapping(String value, String resultMapId) {
            discriminatorMappings.put(value, resultMapId);
            return this;
        }

        public ResultMapDefinition build() {
            return new ResultMapDefinition(id, type, Map.copyOf(idMappings),
                    discriminatorColumn, Map.copyOf(discriminatorMappings));
        }
    }
}
