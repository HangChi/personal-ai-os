package com.personalos.support;

import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 测试用的 {@link ObjectProvider} 实现：按给定顺序返回固定的几个 Bean。
 *
 * <p>为什么不用 mock：这些 SPI 消费方用的是 {@code orderedStream()}，
 * 用 mock 时要为每个方法写 when，还容易漏掉顺序语义 —— 而「顺序」恰恰是
 * 系统提示拼装和轮末钩子的被测属性之一。
 *
 * <p>空列表是合法状态，对应生产环境「该模块未安装」的情况，
 * 因此 {@link #getIfAvailable()} 返回 {@code null} 而不是抛异常。
 */
public final class StaticObjectProvider<T> implements ObjectProvider<T> {

    private final List<T> items;

    public StaticObjectProvider(List<T> items) {
        this.items = List.copyOf(items);
    }

    @Override
    public T getObject(Object... args) {
        return getObject();
    }

    @Override
    public T getObject() {
        if (items.isEmpty()) {
            throw new IllegalStateException("测试替身中没有可用的 Bean");
        }
        return items.get(0);
    }

    @Override
    public T getIfAvailable() {
        return items.isEmpty() ? null : items.get(0);
    }

    @Override
    public T getIfUnique() {
        return items.size() == 1 ? items.get(0) : null;
    }

    @Override
    public Stream<T> orderedStream() {
        return items.stream();
    }

    @Override
    public Iterator<T> iterator() {
        return items.iterator();
    }
}
