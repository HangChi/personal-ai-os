package com.personalos.chat.service;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** 只负责唤醒本实例的读取者；事件与游标仍以数据库为准，无连接时不保留数据。 */
@Component
public class RunEventHub {
    private final ConcurrentHashMap<String, Signal> signals = new ConcurrentHashMap<>();
    public static final class Signal {
        private long version;
        private int readers;
        public synchronized long version() { return version; }
        private synchronized void wake() { version++; notifyAll(); }
        public synchronized void await(long previous) throws InterruptedException {
            if (version == previous) wait(2000);
        }
    }
    public Signal subscribe(String id) {
        return signals.compute(id, (key, value) -> {
            if (value == null) value = new Signal();
            value.readers++;
            return value;
        });
    }
    public void unsubscribe(String id, Signal signal) {
        signals.computeIfPresent(id, (key, value) -> --value.readers == 0 ? null : value);
    }
    public void signal(String id) {
        Signal signal = signals.get(id);
        if (signal != null) signal.wake();
    }
}
