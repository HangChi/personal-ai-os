package com.personalos.common.confirmation;

import java.util.Set;

/**
 * 待确认动作的执行处理器（SPI）。
 *
 * <p>业务模块实现本接口来声明「自己能处理哪些待确认动作」。{@link ConfirmationService}
 * 只负责与业务无关的机制（令牌、有效期、单次性、原子占位），
 * 真正「确认之后干什么」由各模块自己的处理器决定。
 *
 * <h2>为什么需要这一层</h2>
 * 在只有任务一个使用方时，把所有动作写在通用服务的一个 {@code switch} 里是够用的。
 * 但每新增一种高风险操作（日程删除、知识库批量删除……）都要改这个通用服务 ——
 * 通用服务被迫知道所有业务细节，而且改它就要重新验证整条确认链路。
 * 有了 SPI，新增动作只需在自己的模块里加一个处理器。
 */
public interface ConfirmationActionHandler {

    /**
     * @return 本处理器负责的动作集合；一个处理器可以覆盖同一模块的多个动作
     */
    Set<ConfirmationAction> supportedActions();

    /**
     * 执行动作。
     *
     * @param action      具体动作（处理器可能覆盖多个）
     * @param payloadJson 创建待确认事项时存入的参数（JSON 文本），此处原样回放
     * @return 实际影响的行数
     * @throws com.personalos.common.exception.ResourceNotFoundException 目标已不存在时抛出，
     *         由 {@link ConfirmationService} 转换为「目标已不存在」的优雅结果
     */
    int execute(ConfirmationAction action, String payloadJson);
}
