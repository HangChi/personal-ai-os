-- 今日工作台的任务组织；共享不变量覆盖 Java/Go、Agent 与人工写路径。
CREATE TABLE project (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name varchar(100) NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 100),
    description text CHECK (length(description) <= 2000),
    archived boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE task
    ADD COLUMN project_id uuid REFERENCES project(id),
    ADD COLUMN parent_task_id uuid REFERENCES task(id) ON DELETE RESTRICT,
    ADD COLUMN recurrence_type varchar(16) NOT NULL DEFAULT 'NONE'
        CHECK (recurrence_type IN ('NONE','DAILY','WEEKDAYS','WEEKLY','MONTHLY')),
    ADD COLUMN recurrence_timezone varchar(100) NOT NULL DEFAULT 'UTC',
    ADD COLUMN recurrence_anchor_day integer CHECK (recurrence_anchor_day BETWEEN 1 AND 31),
    ADD COLUMN recurrence_generated boolean NOT NULL DEFAULT false;
CREATE INDEX idx_task_project ON task(project_id);
CREATE INDEX idx_task_parent ON task(parent_task_id);
CREATE INDEX idx_task_open_due ON task(due_at, id) WHERE status <> 'DONE';

CREATE FUNCTION validate_task_workspace() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    parent task%ROWTYPE;
    archived_project boolean;
BEGIN
    -- 先锁父任务，使创建子任务与父任务完成/移动互斥；一级层级也排除了循环。
    IF NEW.parent_task_id IS NOT NULL THEN
        IF NEW.parent_task_id = NEW.id THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='workspace:任务不能成为自己的子任务';
        END IF;
        SELECT * INTO parent FROM task WHERE id=NEW.parent_task_id FOR UPDATE;
        IF NOT FOUND OR parent.parent_task_id IS NOT NULL OR parent.recurrence_type <> 'NONE' THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='workspace:父任务必须是存在的、非周期的根任务';
        END IF;
        IF parent.project_id IS DISTINCT FROM NEW.project_id THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='workspace:父任务与子任务必须属于同一项目';
        END IF;
        IF parent.status='DONE' AND NEW.status<>'DONE' THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='workspace:请先恢复父任务，再添加或恢复未完成子任务';
        END IF;
    END IF;
    IF EXISTS (SELECT 1 FROM task WHERE parent_task_id=NEW.id) THEN
        IF NEW.parent_task_id IS NOT NULL OR NEW.recurrence_type<>'NONE' THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='workspace:有子任务的任务不能成为子任务或周期任务';
        END IF;
        IF TG_OP='UPDATE' AND NEW.project_id IS DISTINCT FROM OLD.project_id THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='workspace:请先解除子任务关联，再移动父任务的项目';
        END IF;
        IF NEW.status='DONE' AND EXISTS(SELECT 1 FROM task WHERE parent_task_id=NEW.id AND status<>'DONE') THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='workspace:请先完成所有子任务，再完成父任务';
        END IF;
    END IF;
    IF NEW.project_id IS NOT NULL AND (TG_OP='INSERT' OR NEW.project_id IS DISTINCT FROM OLD.project_id) THEN
        SELECT archived INTO archived_project FROM project WHERE id=NEW.project_id FOR SHARE;
        IF NOT FOUND OR archived_project THEN
            -- 自动生成的下一期保持原项目（包括已归档项目），不创建新的人工关联。
            IF pg_trigger_depth() < 2 THEN
                RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='workspace:项目不存在或已归档，请选择有效项目';
            END IF;
        END IF;
    END IF;
    IF NOT EXISTS(SELECT 1 FROM pg_timezone_names WHERE name=NEW.recurrence_timezone) THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='workspace:请选择有效的周期任务时区';
    END IF;
    IF NEW.recurrence_type<>'NONE' AND (NEW.due_at IS NULL OR NEW.parent_task_id IS NOT NULL) THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='workspace:周期任务必须设置截止时间，且不能同时是子任务';
    END IF;
    IF TG_OP='UPDATE' THEN
        -- 标记只增不减，重新打开/再次完成也不能重复生成。
        NEW.recurrence_generated := OLD.recurrence_generated OR
            (NEW.status='DONE' AND OLD.status<>'DONE' AND NEW.recurrence_type<>'NONE');
    END IF;
    IF NEW.recurrence_type<>'NONE' AND
        (NEW.recurrence_anchor_day IS NULL OR (TG_OP='UPDATE' AND (NEW.due_at IS DISTINCT FROM OLD.due_at OR NEW.recurrence_timezone IS DISTINCT FROM OLD.recurrence_timezone))) THEN
        NEW.recurrence_anchor_day := extract(day FROM NEW.due_at AT TIME ZONE NEW.recurrence_timezone);
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER task_workspace_validate BEFORE INSERT OR UPDATE ON task
    FOR EACH ROW EXECUTE FUNCTION validate_task_workspace();

-- 本地时间推进，与原任务完成同事务，回滚时两者一起回滚。
CREATE FUNCTION generate_next_task_occurrence() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    local_due timestamp;
    next_due timestamp;
    month_start timestamp;
    next_id uuid;
BEGIN
    IF NEW.status<>'DONE' OR OLD.status='DONE' OR OLD.recurrence_generated OR NEW.recurrence_type='NONE' THEN
        RETURN NEW;
    END IF;
    local_due := NEW.due_at AT TIME ZONE NEW.recurrence_timezone;
    CASE NEW.recurrence_type
        WHEN 'DAILY' THEN next_due := local_due + interval '1 day';
        WHEN 'WEEKLY' THEN next_due := local_due + interval '7 days';
        WHEN 'WEEKDAYS' THEN
            next_due := local_due + interval '1 day';
            WHILE extract(isodow FROM next_due)>5 LOOP next_due := next_due + interval '1 day'; END LOOP;
        WHEN 'MONTHLY' THEN
            month_start := date_trunc('month',local_due) + interval '1 month';
            next_due := month_start + (least(NEW.recurrence_anchor_day,
                extract(day FROM month_start + interval '1 month - 1 day')::integer)-1)*interval '1 day'
                + local_due::time;
    END CASE;
    INSERT INTO task(title,description,status,priority,due_at,created_at,updated_at,project_id,
                     recurrence_type,recurrence_timezone,recurrence_anchor_day)
    VALUES(NEW.title,NEW.description,'TODO',NEW.priority,next_due AT TIME ZONE NEW.recurrence_timezone,
           now(),now(),NEW.project_id,NEW.recurrence_type,NEW.recurrence_timezone,NEW.recurrence_anchor_day)
    RETURNING id INTO next_id;
    INSERT INTO task_tag(task_id,tag_index,tag)
        SELECT next_id,tag_index,tag FROM task_tag WHERE task_id=NEW.id;
    RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER task_recurrence_generate AFTER UPDATE ON task
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION generate_next_task_occurrence();

CREATE FUNCTION protect_task_children() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS(SELECT 1 FROM task WHERE parent_task_id=OLD.id) THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='workspace:请先删除或解除所有子任务，再删除父任务';
    END IF;
    RETURN OLD;
END $$;
CREATE TRIGGER task_children_delete BEFORE DELETE ON task
    FOR EACH ROW EXECUTE FUNCTION protect_task_children();
