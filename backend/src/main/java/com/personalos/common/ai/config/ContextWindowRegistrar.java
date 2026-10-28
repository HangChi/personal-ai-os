package com.personalos.common.ai.config;

import com.personalos.common.ai.ContextBudgetService;
import com.personalos.common.ai.ModelContextWindows;
import java.util.UUID;
import org.springframework.context.annotation.Configuration;

/**
 * 把「按模型名查窗口」接到 {@link ContextBudgetService} 上（V25）。
 *
 * <h2>为什么要有这个中转，而不是让预算服务直接注入配置服务</h2>
 * 依赖方向会成环：{@code ModelConfigService} → 可能需要预算服务，
 * 而预算服务要查的模型清单又住在 {@code ModelProfileService} 里。
 * 这里在**构造完成后**用 setter 注册一个查询函数，把环掰成单向：
 * 预算服务只认识一个 {@code Function<String, Integer>}，不认识配置服务。
 *
 * <h2>为什么查询要包一层 try/catch 到「只返回 null」</h2>
 * 这个查询会在**每次**取窗口时执行（包括窗口裁剪与仪表盘两条路径）。
 * 一次数据库抖动不该让聊天失败 —— 窗口估保守一点（回落到内置表/全局默认）
 * 是可接受的降级，而抛出去就是「用户发不出消息」。
 */
@Configuration
public class ContextWindowRegistrar {

    public ContextWindowRegistrar(ContextBudgetService contextBudget,
                                  ModelProfileService profileService) {
        contextBudget.registerWindowLookup(modelName -> lookup(profileService, modelName));
    }

    /**
     * 在**当前生效配置**的对话模型清单里找该模型的窗口。
     *
     * <p>只查生效配置：会话用的是「生效配置下的某个模型」，非生效配置里的
     * 同名模型即使配了窗口也与本会话无关 —— 换配置后窗口本来就该跟着变。
     *
     * <p>找不到就返回 null 交给下一级回落（内置表 → 全局默认）。
     * 特别注意这里**不**抛异常也不返回 0：调用方用「null = 不知道」来区分
     * 「窗口是 0」这种不可能的值。
     */
    private static Integer lookup(ModelProfileService profileService, String modelName) {
        try {
            UUID activeId = profileService.current()
                    .map(entity -> entity.getId())
                    .orElse(null);
            if (activeId == null) {
                return null;
            }
            return profileService.modelsOf(activeId).stream()
                    .filter(model -> model.kindEnum() == AiModelProfileModelEntity.Kind.CHAT)
                    .filter(model -> modelName.equals(model.getModelName()))
                    .map(AiModelProfileModelEntity::getContextWindow)
                    .filter(window -> window != null && window >= 1024)
                    .findFirst()
                    // 配置里没填窗口时，别急着回落全局默认：还要让内置表有机会先说话。
                    // 返回 null 就是这个意思，顺序由 ContextBudgetService 维护。
                    .orElse(null);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
