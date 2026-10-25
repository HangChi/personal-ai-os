package com.personalos.memory;

import com.personalos.common.ai.PromptContributor;
import com.personalos.memory.service.MemoryService;
import org.springframework.stereotype.Component;

/**
 * 把长期记忆渲染进系统提示。
 *
 * <p>本类刻意极薄：渲染逻辑（排序、预算、不截断）全部在 {@link MemoryService#renderForPrompt()}，
 * 这里只做「SPI → 业务方法」的适配。这样渲染规则可以被单元测试直接覆盖，
 * 不需要启动 Spring 上下文。
 */
@Component
public class MemoryPromptContributor implements PromptContributor {

    private final MemoryService memoryService;

    public MemoryPromptContributor(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    /**
     * @return 记忆块；没有任何记忆时返回空串（调用方据此完全不追加内容）
     */
    @Override
    public String contribute() {
        return memoryService.renderForPrompt();
    }
}
