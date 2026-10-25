package com.personalos.calendar;

import com.personalos.calendar.service.CalendarEventService;
import com.personalos.export.ExportContributor;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 日程导出（键 {@code calendarEvents}）。
 *
 * <p>导出的是日程**定义**（含重复规则），不是展开后的发生实例 ——
 * 后者会让一次「每周」日程在文件里变成几百条看不出同源的记录，
 * 而重复规则本身足以重建全部实例。
 *
 * <p>提醒不在导出范围：它由日程与任务派生，导出了反而会产生「改了日程、
 * 旧提醒还留着」的不一致。
 */
@Component
public class CalendarExportContributor implements ExportContributor {

    private final CalendarEventService eventService;

    public CalendarExportContributor(CalendarEventService eventService) {
        this.eventService = eventService;
    }

    @Override
    public String key() {
        return "calendarEvents";
    }

    @Override
    public List<?> snapshot() {
        return eventService.listAll();
    }
}
