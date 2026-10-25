package com.personalos.task;

import com.personalos.export.ExportContributor;
import com.personalos.task.service.TaskService;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 任务导出（键 {@code tasks}）。
 *
 * <p>用 {@link TaskService#listAll()} 而不是分页查询：分页上限 100 会让任务多时静默少导。
 */
@Component
public class TaskExportContributor implements ExportContributor {

    private final TaskService taskService;

    public TaskExportContributor(TaskService taskService) {
        this.taskService = taskService;
    }

    @Override
    public String key() {
        return "tasks";
    }

    @Override
    public List<?> snapshot() {
        return taskService.listAll();
    }
}
