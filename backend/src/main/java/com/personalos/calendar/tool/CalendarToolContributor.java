package com.personalos.calendar.tool;

import com.personalos.common.ai.ToolContributor;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 把日程与提醒工具贡献给对话。
 *
 * <p>第三个使用 {@link ToolContributor} SPI 的模块。到这一步可以看到当初这个 SPI 的价值：
 * {@code chat} 模块从阶段 1 起**一行未改**，对话能力却从「能聊天」长到了
 * 「能管任务、能查知识库、能安排日程」。
 */
@Component
public class CalendarToolContributor implements ToolContributor {

    private final CalendarTools calendarTools;

    public CalendarToolContributor(CalendarTools calendarTools) {
        this.calendarTools = calendarTools;
    }

    @Override
    public List<Object> toolObjects() {
        return List.of(calendarTools);
    }
}
