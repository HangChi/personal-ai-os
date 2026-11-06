-- ICS 来源身份只去重，不以外部文件覆盖用户编辑。
CREATE TABLE calendar_ics_identity (
    uid varchar(512) PRIMARY KEY CHECK(length(uid)>0),
    event_id uuid NOT NULL UNIQUE REFERENCES calendar_event(id) ON DELETE CASCADE,
    imported_at timestamptz NOT NULL DEFAULT now()
);
