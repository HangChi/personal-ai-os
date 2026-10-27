package com.personalos.common.web;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

/**
 * 把 MDC 带进 Reactor 的调度线程。
 *
 * <h2>为什么单靠过滤器不够</h2>
 * {@link RequestIdFilter} 只覆盖 servlet 线程。而流式对话的执行体被
 * {@code subscribeOn(Schedulers.boundedElastic())} 切到了别的线程：
 * 模型调用、知识库检索、出错日志全都发生在那里，MDC 是**线程本地**的，
 * 切线程后就空了 —— 于是最需要 requestId 的那批日志恰好没有它。
 *
 * <h2>为什么用调度钩子而不是手写 try/finally</h2>
 * 钩子只注册一次，此后所有切到调度器的任务都自动继承。若改在业务代码里逐处
 * {@code MDC.put}，等于把「可观测性」的负担压给每个写业务的人，早晚会漏。
 *
 * <p>捕获发生在**任务提交时**（也就是订阅发生的线程，即请求线程），
 * 所以取到的是正确的 requestId，而不是执行线程自己的。
 */
@Component
public class MdcSchedulerHook {

    /** 钩子名：同名注册会替换而不是叠加，避免测试上下文反复注册后重复包装 */
    private static final String HOOK_NAME = "personalos-mdc";

    private static final Logger log = LoggerFactory.getLogger(MdcSchedulerHook.class);

    @PostConstruct
    void register() {
        Schedulers.onScheduleHook(HOOK_NAME, runnable -> {
            Map<String, String> snapshot = MDC.getCopyOfContextMap();
            return () -> {
                if (snapshot != null) {
                    MDC.setContextMap(snapshot);
                }
                try {
                    runnable.run();
                } finally {
                    // 调度线程是池化的：不清理会让下一个任务读到上一个请求的 requestId
                    MDC.clear();
                }
            };
        });
        // 启动日志不是噪音：这个钩子一旦静默失效，症状只是「异步日志没有 requestId」，
        // 排查时看到的第一手证据就是「这行在不在」
        log.info("已注册 MDC 调度钩子：流式对话的异步日志将携带 requestId");
    }
}
