package com.personalos.calendar;

import com.personalos.calendar.entity.CalendarEventEntity;
import com.personalos.calendar.repository.CalendarEventRepository;
import com.personalos.common.search.SearchContributor;
import com.personalos.common.search.SearchHit;
import com.personalos.common.search.SearchPatterns;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 日程搜索（阶段 17）。
 *
 * <p>搜标题、描述与地点，按开始时间**倒序**（最近的日程更可能正在找）。
 */
@Component
public class CalendarSearchContributor implements SearchContributor {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final CalendarEventRepository eventRepository;

    public CalendarSearchContributor(CalendarEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @Override
    public String moduleName() {
        return "日程";
    }

    @Override
    public String kind() {
        return "event";
    }

    @Override
    @Transactional(readOnly = true)
    public List<SearchHit> search(String keyword, int limit) {
        String pattern = SearchPatterns.likePattern(keyword);
        String needle = keyword.toLowerCase(Locale.ROOT);
        return eventRepository.findAll(
                (root, query, builder) -> builder.or(
                        builder.like(builder.lower(root.get("title")), pattern, SearchPatterns.ESCAPE),
                        builder.like(builder.lower(builder.coalesce(root.get("description"), "")),
                                pattern, SearchPatterns.ESCAPE),
                        builder.like(builder.lower(builder.coalesce(root.get("location"), "")),
                                pattern, SearchPatterns.ESCAPE)),
                PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "startsAt")))
                .getContent().stream().map(event -> new SearchHit(
                "event",
                event.getId().toString(),
                event.getTitle(),
                DATE_TIME.format(event.getStartsAt()) + " · " + recurrenceLabel(event),
                matchedField(event, needle))).toList();
    }

    private static String matchedField(CalendarEventEntity event, String needle) {
        if (event.getTitle().toLowerCase(Locale.ROOT).contains(needle)) {
            return "标题";
        }
        if (event.getLocation() != null && event.getLocation().toLowerCase(Locale.ROOT).contains(needle)) {
            return "地点";
        }
        return "描述";
    }

    private static String recurrenceLabel(CalendarEventEntity event) {
        return switch (event.getRecurrenceType()) {
            case DAILY -> "每天";
            case WEEKLY -> "每周";
            case MONTHLY -> "每月";
            case WEEKDAYS -> "工作日";
            default -> "不重复";
        };
    }
}
