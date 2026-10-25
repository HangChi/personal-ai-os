-- ============================================================
-- 阶段 14：任务标签
--
-- 设计要点：
--   1) 独立表而不是「task.tags 逗号分隔列」：后者无法索引筛选
--      （LIKE '%工作%' 会误命中「工作台」）、无法保证唯一、
--      标签里一旦出现分隔符就崩。
--   2) tag_index 用于保序：标签在列表、编辑回填、导出三处都会被看到，
--      顺序不稳定会让用户觉得「同样的数据刷新后换了位置」。
--      因此主键是 (task_id, tag_index) 而不是 (task_id, tag)。
--   3) 外键 ON DELETE CASCADE：删任务必须带走标签，
--      否则筛选下拉里会残留一个永远点不出结果的标签。
-- ============================================================

CREATE TABLE IF NOT EXISTS task_tag (
    task_id   UUID        NOT NULL,
    tag_index INTEGER     NOT NULL,
    tag       VARCHAR(20) NOT NULL,
    CONSTRAINT pk_task_tag PRIMARY KEY (task_id, tag_index),
    CONSTRAINT fk_task_tag_task FOREIGN KEY (task_id) REFERENCES task (id) ON DELETE CASCADE
);

COMMENT ON TABLE  task_tag           IS '任务标签（0–5 个/任务，自由文本，无标签库）';
COMMENT ON COLUMN task_tag.tag_index IS '同一任务内的顺序，从 0 开始；用于稳定展示顺序';

-- 按标签筛选是主要用途
CREATE INDEX IF NOT EXISTS idx_task_tag_tag ON task_tag (tag);
