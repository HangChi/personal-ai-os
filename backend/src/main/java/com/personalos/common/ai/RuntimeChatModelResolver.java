package com.personalos.common.ai;

import org.springframework.ai.chat.model.ChatModel;

/**
 * 按模型名在运行期造出一个 {@code ChatModel}。
 *
 * <h2>为什么用接口而不是让 {@code AiChatClientProvider} 直接依赖 ModelConfigService</h2>
 * 两者是双向依赖：配置服务在「保存 / 启用配置」时要调 {@code provider.replaceModel}
 * 把新模型装上；而按会话换模型时，provider 又要反过来请配置服务按模型名造一个实例。
 * 直接互注会形成构造期循环，Spring 只能靠字段注入或 {@code @Lazy} 打破，
 * 那两种写法都会把「循环依赖」这个事实藏起来，将来重构时容易踩空。
 *
 * <p>抽一个只含一件事的窄接口放在 {@code common.ai}，provider 用
 * {@code ObjectProvider} 懒取实现：编译期依赖方向单一（config → ai 的接口），
 * 运行期也只在真的要换模型时才去取实现。
 *
 * <h2>为什么不是「返回 ChatClient」</h2>
 * 客户端要装配系统提示、记忆 Advisor 与工具，这些是 provider 的职责
 * （见 {@code AiChatClientProvider#buildBundle}）。让配置服务造客户端
 * 会把同一套装配逻辑写两遍，且两份迟早不一致。这里只造「裸模型」。
 */
public interface RuntimeChatModelResolver {

    /**
     * 按模型名造一个模型实例。
     *
     * <p>每次调用都返回**新实例**：这里不做缓存，缓存在调用方
     * （{@link AiChatClientProvider}），因为只有它知道什么时候该失效
     * —— 配置一改，缓存里所有按模型名造的实例都要跟着作废。
     *
     * @param modelName 模型名，如 {@code qwen-max}
     * @return 新建的模型实例
     * @throws com.personalos.common.exception.BusinessException 模型名无效、
     *         或当前配置没有可用密钥时（此时无法构造 HTTP 客户端）
     */
    ChatModel resolve(String modelName);

    /**
     * 当前配置的默认对话模型名。
     *
     * <p>会话没选模型时用它；也让「界面上显示正在用哪个模型」有确切答案，
     * 不必由前端再拼一次三层回落的逻辑。
     *
     * @return 默认模型名；永不为 null（最差是内置默认值）
     */
    String defaultChatModel();
}
