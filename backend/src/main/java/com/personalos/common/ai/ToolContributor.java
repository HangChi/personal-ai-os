package com.personalos.common.ai;

import java.util.List;

/**
 * 工具贡献者 SPI。
 *
 * <p>业务模块实现本接口来向对话暴露自己的工具（如阶段 2 的任务工具、阶段 3 的知识库工具）。
 *
 * <h2>为什么需要这一层间接</h2>
 * 对话模块（{@code chat}）需要把所有工具挂到 {@code ChatClient} 上，但它**不应该**依赖
 * 具体业务模块 —— 否则会出现「业务模块反向依赖对话模块」的方向颠倒，而且每新增一类工具
 * 都要改动对话模块。有了本接口，{@code chat} 只依赖 {@code common}，新增工具只需在业务模块
 * 里实现本接口并注册为 Bean。
 *
 * <p>工具对象需为普通 Java 对象，其方法以 Spring AI 的 {@code @Tool} 注解标记。
 */
public interface ToolContributor {

    /**
     * @return 供对话使用的工具对象列表；无工具时返回空列表而非 {@code null}
     */
    List<Object> toolObjects();
}
