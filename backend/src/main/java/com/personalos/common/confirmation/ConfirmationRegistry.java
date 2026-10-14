package com.personalos.common.confirmation;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 待确认动作的路由表。
 *
 * <h2>启动期即校验「每个动作都有处理器」</h2>
 * {@link ConfirmationAction} 的取值与数据库 CHECK 约束、与处理器三方必须对齐。
 * 若只在确认时才去查处理器，那么「新增了一个动作但忘了写处理器」这个错误会一直潜伏到
 * **用户点了确认** 才暴露 —— 而那一刻用户已经做出了不可逆的决定，体验最差。
 * 放到启动期校验，则问题在部署时就被拦住，且错误信息直接点明缺哪个动作。
 */
@Component
public class ConfirmationRegistry {

    private static final Logger log = LoggerFactory.getLogger(ConfirmationRegistry.class);

    private final Map<ConfirmationAction, ConfirmationActionHandler> handlers;

    public ConfirmationRegistry(List<ConfirmationActionHandler> discoveredHandlers) {
        Map<ConfirmationAction, ConfirmationActionHandler> mapping = new EnumMap<>(ConfirmationAction.class);

        for (ConfirmationActionHandler handler : discoveredHandlers) {
            for (ConfirmationAction action : handler.supportedActions()) {
                ConfirmationActionHandler previous = mapping.put(action, handler);
                if (previous != null) {
                    // 重复注册意味着「确认之后到底干什么」是不确定的，必须当场失败
                    throw new IllegalStateException(String.format(
                            "待确认动作 %s 被多个处理器声明：%s 与 %s",
                            action, previous.getClass().getName(), handler.getClass().getName()));
                }
            }
        }

        List<ConfirmationAction> missing = Arrays.stream(ConfirmationAction.values())
                .filter(action -> !mapping.containsKey(action))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(String.format(
                    "以下待确认动作缺少处理器：%s。"
                            + "请为它们实现 ConfirmationActionHandler，"
                            + "或从 ConfirmationAction 中移除（移除需配套迁移调整 CHECK 约束）。",
                    missing));
        }

        this.handlers = Collections.unmodifiableMap(mapping);
        log.info("待确认动作处理器注册完成：{}", this.handlers.keySet());
    }

    /**
     * @throws IllegalStateException 动作没有对应处理器（理论上被启动期校验挡住）
     */
    public ConfirmationActionHandler require(ConfirmationAction action) {
        ConfirmationActionHandler handler = handlers.get(action);
        if (handler == null) {
            throw new IllegalStateException("待确认动作没有处理器：" + action);
        }
        return handler;
    }
}
