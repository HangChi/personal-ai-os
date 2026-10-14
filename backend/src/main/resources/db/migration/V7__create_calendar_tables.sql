-- ============================================================
-- V7: 阶段 4 —— 日程与提醒
--
-- 设计说明见 specs/005-calendar-reminders/data-model.md。
--
-- 两处必须与代码严格一致的约定：
--   1) reminder 的唯一约束 (source_type, source_id, occurrence_at) 是**幂等的基础**：
--      扫描用 INSERT ... ON CONFLICT DO NOTHING，靠它保证「同一发生时刻只有一条提醒」。
--      去掉它，重复扫描就会产生重复提醒，而且用户每次重启都会看到一批。
--   2) confirmation.action 的 CHECK 必须包含 DELETE_CALENDAR_EVENT，
--      且 ConfirmationAction 枚举里必须有对应取值并提供处理器 ——
--      ConfirmationRegistry 会在启动期校验「每个取值都有处理器」。
-- ============================================================

CREATE TABLE IF NOT EXISTS calendar_event (
    id                    UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    title                 VARCHAR(200) NOT NULL,
    description           TEXT,
    location              VARCHAR(200),
    all_day               BOOLEAN      NOT NULL DEFAULT false,
    starts_at             TIMESTAMPTZ  NOT NULL,
    ends_at               TIMESTAMPTZ  NOT NULL,
    recurrence_type       VARCHAR(20)  NOT NULL DEFAULT 'NONE',
    recurrence_until      TIMESTAMPTZ,
    reminder_lead_minutes INTEGER      NOT NULL DEFAULT 15,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_calendar_event_range      CHECK (ends_at >= starts_at),
    CONSTRAINT ck_calendar_event_recurrence
        CHECK (recurrence_type IN ('NONE', 'DAILY', 'WEEKLY', 'MONTHLY', 'WEEKDAYS')),
    CONSTRAINT ck_calendar_event_lead       CHECK (reminder_lead_minutes >= 0)
);

-- 支撑「按时间区间查询」这一最常用形态
CREATE INDEX IF NOT EXISTS idx_calendar_event_starts_at ON calendar_event (starts_at);

COMMENT ON TABLE  calendar_event                  IS '日程事件；重复规则由应用侧展开为发生实例';
COMMENT ON COLUMN calendar_event.recurrence_until IS '重复截止（**含**该时刻）；为空表示无限重复';
COMMENT ON COLUMN calendar_event.all_day          IS '全天事件的约定：starts_at 为当天 00:00、ends_at 为次日 00:00';

CREATE TABLE IF NOT EXISTS reminder (
    id            UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    source_type   VARCHAR(20)  NOT NULL,
    source_id     UUID         NOT NULL,
    title         VARCHAR(300) NOT NULL,
    occurrence_at TIMESTAMPTZ  NOT NULL,
    remind_at     TIMESTAMPTZ  NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    read_at       TIMESTAMPTZ,
    -- 幂等的基础：同一来源的同一发生时刻只能有一条提醒。
    -- 有了它，扫描才能用「插入并忽略冲突」的语义，而不是容易出竞态的「先查再插」；
    -- 也让「系统时间回拨」变得安全 —— 判断依据是「这个时刻有没有提醒」而非「现在几点」。
    CONSTRAINT uk_reminder_source_occurrence UNIQUE (source_type, source_id, occurrence_at),
    CONSTRAINT ck_reminder_source CHECK (source_type IN ('CALENDAR_EVENT', 'TASK')),
    CONSTRAINT ck_reminder_status CHECK (status IN ('PENDING', 'READ', 'CANCELLED'))
);

-- 支撑「到点且未读」这一唯一的热查询形态
CREATE INDEX IF NOT EXISTS idx_reminder_status_remind_at ON reminder (status, remind_at);

COMMENT ON TABLE  reminder            IS '提醒（物化）；来源可以是日程或任务';
COMMENT ON COLUMN reminder.title      IS '标题快照：来源被改名或删除后，仍能说明当时提醒的是什么';
COMMENT ON COLUMN reminder.source_id  IS '刻意不加外键：日程删除后提醒需要留存（历史事实）';

-- ------------------------------------------------------------------
-- 扩展待确认动作的取值范围，加入日程删除
-- ------------------------------------------------------------------
ALTER TABLE confirmation DROP CONSTRAINT ck_confirmation_action;
ALTER TABLE confirmation ADD CONSTRAINT ck_confirmation_action
    CHECK (action IN ('DELETE_TASK', 'SCHEDULE_TASK', 'SET_PRIORITY', 'DELETE_CALENDAR_EVENT'));
