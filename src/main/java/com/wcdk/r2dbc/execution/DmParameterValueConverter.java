package com.wcdk.r2dbc.execution;

import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Date;

/***
 * 达梦 R2DBC 驱动参数兼容转换器。
 * @author wcdk
 **/
public class DmParameterValueConverter extends DefaultParameterValueConverter {

    /***
     * 仅转换达梦驱动不兼容的时间类型。
     * @author wcdk
     * @param value 原始参数值
     * @return 达梦驱动参数值
     */
    @Override
    public Object convert(Object value) {
        if (value instanceof Instant instant) return Date.from(instant);
        if (value instanceof LocalDateTime dateTime) return Timestamp.valueOf(dateTime);
        if (value instanceof LocalDate date) return java.sql.Date.valueOf(date);
        if (value instanceof LocalTime time) return Time.valueOf(time);
        return value;
    }
}