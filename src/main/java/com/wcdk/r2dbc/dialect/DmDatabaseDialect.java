package com.wcdk.r2dbc.dialect;

import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Date;

/***
 * 达梦数据库方言。
 * @author wcdk
 */
public final class DmDatabaseDialect extends OracleDatabaseDialect {
    public static final DmDatabaseDialect INSTANCE = new DmDatabaseDialect();

    private DmDatabaseDialect() {
    }

    @Override
    public Object normalizeParameterValue(Object value) {
        if (value instanceof Instant instant) return Date.from(instant);
        if (value instanceof LocalDateTime dateTime) return Timestamp.valueOf(dateTime);
        if (value instanceof LocalDate date) return java.sql.Date.valueOf(date);
        if (value instanceof LocalTime time) return Time.valueOf(time);
        return value;
    }
}
