package com.personalos.chat.service;

import java.util.function.BiConsumer;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 后台执行的事件出口，不绑定浏览器连接；停止必须显式请求。 */
public final class RunEmitter extends SseEmitter {
    private final BiConsumer<String, Object> publisher;
    private volatile Runnable cancel;
    private Runnable completion = () -> { };
    public void whenComplete(Runnable action) { completion = action; }
    private volatile boolean stopped;
    private Runnable control = () -> { };
    public void control(Runnable action) { control = action; }
    public void checkpoint() { control.run(); }
    private String recoveryContext = "";
    public void recoveryContext(String context) { recoveryContext = context; }
    public String recoveryContext() { return recoveryContext; }

    public RunEmitter(BiConsumer<String, Object> publisher) {
        super(0L);
        this.publisher = publisher;
    }

    public void publish(String name, Object payload) { publisher.accept(name, payload); }
    public synchronized void onCancel(Runnable action) {
        cancel = action;
        if (stopped) action.run();
    }
    public boolean stopped() { return stopped; }
    public synchronized void cancel() {
        stopped = true;
        if (cancel != null) cancel.run();
    }
    // 后台出口不需要网络心跳，也不积压 Spring MVC 的 earlySendAttempts。
    @Override public void send(SseEventBuilder builder) { }
    @Override public void complete() { completion.run(); }
}
