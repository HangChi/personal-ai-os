-- 改名、调整额度或重复保存相同配置，不应跳过已经到期的报告。
CREATE OR REPLACE FUNCTION prepare_workflow() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT'
    OR ROW(NEW.kind,NEW.timezone,NEW.local_time,NEW.weekday)
       IS DISTINCT FROM ROW(OLD.kind,OLD.timezone,OLD.local_time,OLD.weekday)
    OR (NEW.enabled AND NOT OLD.enabled) THEN
  NEW.next_run_at:=workflow_next_at(NEW.timezone,NEW.local_time,NEW.weekday,NEW.kind,clock_timestamp());
 END IF;
 NEW.updated_at:=now();
 RETURN NEW;
END $$;
