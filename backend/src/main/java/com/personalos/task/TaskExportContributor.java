package com.personalos.task;

import com.personalos.export.ExportContributor;
import com.personalos.export.ExportWriter;
import tools.jackson.core.JsonGenerator;
import com.personalos.task.service.TaskService;
import org.springframework.stereotype.Component;

/**
 * 任务导出（键 {@code tasks}）。
 *
 * <p>逐批读取全部记录，不受界面查询页数限制。
 */
@Component
public class TaskExportContributor implements ExportContributor {

    private final ExportWriter writer;

    private final TaskService taskService;

    public TaskExportContributor(TaskService taskService, ExportWriter writer) {
        this.writer = writer;
        this.taskService = taskService;
    }

    @Override
    public String key() {
        return "tasks";
    }

    @Override
    public long writeTo(JsonGenerator json) {
        return writer.array(json, page -> taskService.exportBatch(page,
                ExportWriter.BATCH_SIZE));
    }
}
