package com.personalos.memory.tool;

import com.personalos.common.ai.ToolContributor;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 把长期记忆工具贡献给对话模块（SPI，见 {@link ToolContributor}）。
 */
@Component
public class MemoryToolContributor implements ToolContributor {

    private final MemoryTools memoryTools;

    public MemoryToolContributor(MemoryTools memoryTools) {
        this.memoryTools = memoryTools;
    }

    @Override
    public List<Object> toolObjects() {
        return List.of(memoryTools);
    }
}
