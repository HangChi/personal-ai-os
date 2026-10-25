package com.personalos.task;

import com.personalos.common.search.SearchContributor;
import com.personalos.common.search.SearchHit;
import com.personalos.common.search.SearchPatterns;
import com.personalos.task.entity.TaskEntity;
import com.personalos.task.entity.TaskPriority;
import com.personalos.task.entity.TaskStatus;
import com.personalos.task.repository.TaskRepository;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 任务搜索（阶段 17）。
 *
 * <p>搜标题与描述，按更新时间倒序取前 {@code limit} 条。
 */
@Component
public class TaskSearchContributor implements SearchContributor {

    private final TaskRepository taskRepository;

    public TaskSearchContributor(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    @Override
    public String moduleName() {
        return "任务";
    }

    @Override
    public String kind() {
        return "task";
    }

    @Override
    @Transactional(readOnly = true)
    public List<SearchHit> search(String keyword, int limit) {
        String pattern = SearchPatterns.likePattern(keyword);
        String needle = keyword.toLowerCase(Locale.ROOT);
        return taskRepository.findAll(
                (root, query, builder) -> builder.or(
                        builder.like(builder.lower(root.get("title")), pattern, SearchPatterns.ESCAPE),
                        builder.like(builder.lower(builder.coalesce(root.get("description"), "")),
                                pattern, SearchPatterns.ESCAPE)),
                PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "updatedAt")))
                .getContent().stream().map(task -> new SearchHit(
                "task",
                task.getId().toString(),
                task.getTitle(),
                subtitle(task),
                // 标题与描述同时命中时标注标题：标题命中通常意味着「这条就是关于这个的」
                task.getTitle().toLowerCase(Locale.ROOT).contains(needle) ? "标题" : "描述")).toList();
    }

    private static String subtitle(TaskEntity task) {
        return statusLabel(task.getStatus()) + " · " + priorityLabel(task.getPriority());
    }

    private static String statusLabel(TaskStatus status) {
        return switch (status) {
            case DONE -> "已完成";
            case DOING -> "进行中";
            default -> "待办";
        };
    }

    private static String priorityLabel(TaskPriority priority) {
        return switch (priority) {
            case HIGH -> "高";
            case LOW -> "低";
            default -> "中";
        };
    }
}
