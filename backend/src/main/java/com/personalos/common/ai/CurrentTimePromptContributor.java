package com.personalos.common.ai;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 每轮从服务端时钟刷新时间，避免模型用训练日期、旧对话或文件时间解释「明天」。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CurrentTimePromptContributor implements PromptContributor {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd EEEE", Locale.SIMPLIFIED_CHINESE);
    private final Clock clock;
    private final ZoneId zone;

    public CurrentTimePromptContributor(Clock clock, ZoneId zone) {
        this.clock = clock;
        this.zone = zone;
    }

    @Override
    public String contribute() {
        ZonedDateTime now = ZonedDateTime.ofInstant(clock.instant(), zone);
        return "[系统时间]\n"
                + "当前时间：" + now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + "\n"
                + "当前日期：" + now.format(DATE) + "\n"
                + "日历时区：" + zone.getId() + "\n"
                + "明天日期：" + now.toLocalDate().plusDays(1) + "\n"
                + "本段由系统时钟实时提供。用户未指定其他时区时，以此解释今天、明天、下周等相对时间；"
                + "不要询问用户今天的日期，不要联网校时，也不要读取文件时间猜测日期。"
                + "历史消息里的日期不代表今天。连续安排日程时，用户只补充时段、事项或地点而省略日期，"
                + "应承接前文用户明确指定的同一安排日期（例如先说明天的组会，再说晚上19–21点的大课，仍指明天）；"
                + "不要仅因今天该时段已过而自行顺延日期，也不要把承接前文说成猜测。指代不明确时先询问。"
                + "调用日程工具时使用带时区偏移的 ISO-8601 时间。";
    }
}
