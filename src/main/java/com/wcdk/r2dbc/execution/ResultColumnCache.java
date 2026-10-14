package com.wcdk.r2dbc.execution;

import io.r2dbc.spi.*;
import io.r2dbc.spi.Readable;
import org.reactivestreams.Publisher;
import java.util.*;
import java.util.function.*;

/** 将列名索引限定在一个 Result 内；不同结果及订阅不共享列结构。 */
final class ResultColumnCache implements Result {
    private final Result delegate;
    private volatile Columns columns;

    ResultColumnCache(Result delegate) { this.delegate = delegate; }

    private Columns columns(RowMetadata metadata) {
        Columns cached = columns;
        if (cached == null) {
            synchronized (this) {
                cached = columns;
                if (cached == null) columns = cached = new Columns(metadata);
            }
        }
        return cached;
    }

    @Override public Publisher<Long> getRowsUpdated() { return delegate.getRowsUpdated(); }

    @Override public <T> Publisher<T> map(BiFunction<Row, RowMetadata, ? extends T> mapper) {
        return delegate.map((row, metadata) -> {
            Columns cached = columns(metadata);
            return mapper.apply(indexed(row, cached), cached);
        });
    }

    @Override public <T> Publisher<T> map(Function<? super Readable, ? extends T> mapper) {
        return delegate.map(readable -> mapper.apply(readable instanceof Row row
                ? indexed(row, columns(row.getMetadata())) : readable));
    }

    @Override public Result filter(Predicate<Segment> filter) { return new ResultColumnCache(delegate.filter(filter)); }

    @Override public <T> Publisher<T> flatMap(Function<Segment, ? extends Publisher<? extends T>> mapper) {
        return delegate.flatMap(mapper);
    }

    private static Row indexed(Row row, Columns metadata) {
        return new Row() {
            @Override public <T> T get(int index, Class<T> type) { return row.get(index, type); }
            @Override public <T> T get(String name, Class<T> type) { return row.get(name, type); }
            @Override public Object get(int index) { return row.get(index); }
            @Override public Object get(String name) { return row.get(name); }
            @Override public RowMetadata getMetadata() { return metadata; }
        };
    }

    /** 构建一次的精确名称及大小写归一索引，查找不扫描列。 */
    static final class Columns implements RowMetadata {
        private static final Columns EMPTY = new Columns(List.of());
        private final List<? extends ColumnMetadata> metadata;
        private final Map<String, ColumnMetadata> exact = new LinkedHashMap<>();
        private final Map<String, String> folded = new HashMap<>();
        private final Set<String> names;

        Columns(RowMetadata metadata) { this(metadata.getColumnMetadatas()); }
        private Columns(List<? extends ColumnMetadata> metadata) {
            this.metadata = List.copyOf(metadata);
            for (ColumnMetadata column : metadata) {
                String name = column.getName();
                exact.put(name, column);
                folded.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
            }
            names = Collections.unmodifiableSet(exact.keySet());
        }
        static Columns empty() { return EMPTY; }
        static Columns of(RowMetadata metadata) { return metadata instanceof Columns cached ? cached : new Columns(metadata); }
        Set<String> names() { return names; }
        String actualName(String name) {
            return exact.containsKey(name) ? name : folded.get(name.toLowerCase(Locale.ROOT));
        }
        @Override public ColumnMetadata getColumnMetadata(int index) { return metadata.get(index); }
        @Override public ColumnMetadata getColumnMetadata(String name) {
            ColumnMetadata column = exact.get(actualName(name));
            if (column == null) throw new NoSuchElementException("结果不包含列：" + name);
            return column;
        }
        @Override public List<? extends ColumnMetadata> getColumnMetadatas() { return metadata; }
        @Override public boolean contains(String name) { return actualName(name) != null; }
    }
}
