package com.personalos.task;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 任务标签的归一化（纯函数）。
 *
 * <p>四件事必须一起做，漏任何一步都会出问题：
 * <ul>
 *   <li>去空白：用户从别处粘过来的标签常带尾随空格；</li>
 *   <li>去空：`["工作", ""]` 里的空串若入库，会变成一个看不见、点不掉的标签；</li>
 *   <li>去重：重复标签会撞主键（`task_id + tag_index` 虽不撞，但列表会重复显示）；</li>
 *   <li>校验上限：超长/超数量要在业务层拦住，否则会变成数据库层的长度错误。</li>
 * </ul>
 *
 * <p>集中在这里而不是散在 Service 里：只有纯函数才能把这些边界一条条断言。
 */
public final class TaskTags {

    /** 单个标签最大长度（与迁移里的 VARCHAR(20) 一致） */
    public static final int TAG_MAX_LENGTH = 20;

    /** 单个任务的标签数量上限 */
    public static final int MAX_TAGS = 5;

    private TaskTags() {
    }

    /**
     * 归一化标签列表。
     *
     * @param raw 原始标签，可空
     * @return 去空白、去空、去重（**保留首次出现顺序**）后的不可变列表
     * @throws BusinessException 单个标签超长或数量超限
     */
    public static List<String> normalize(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }

        // LinkedHashSet：去重的同时保留首次出现的顺序（标签顺序在界面上是可见的）
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String item : raw) {
            if (item == null) {
                continue;
            }
            String tag = item.strip();
            if (tag.isEmpty()) {
                continue;
            }
            if (tag.length() > TAG_MAX_LENGTH) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "单个标签不能超过 " + TAG_MAX_LENGTH + " 个字符：" + tag);
            }
            unique.add(tag);
        }

        if (unique.size() > MAX_TAGS) {
            // 刻意报错而不是截断：静默丢掉用户输入的两个标签，比拒绝保存更难发现
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "最多 " + MAX_TAGS + " 个标签，当前 " + unique.size() + " 个");
        }
        return List.copyOf(new ArrayList<>(unique));
    }
}
