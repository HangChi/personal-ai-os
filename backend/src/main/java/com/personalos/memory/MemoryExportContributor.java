package com.personalos.memory;

import com.personalos.export.ExportContributor;
import com.personalos.memory.service.MemoryService;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 长期记忆导出（键 {@code memories}）。
 *
 * <p>记忆是「会注入之后每一次对话」的内容，也是最容易在换机器时丢掉的部分 ——
 * 它没有可见的原始来源，用户往往只在助手「突然不记得了」时才发现丢了。
 */
@Component
public class MemoryExportContributor implements ExportContributor {

    private final MemoryService memoryService;

    public MemoryExportContributor(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Override
    public String key() {
        return "memories";
    }

    @Override
    public List<?> snapshot() {
        return memoryService.list();
    }
}
