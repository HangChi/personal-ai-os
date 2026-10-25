-- ============================================================
-- V9: 长期记忆表（阶段 8）
--
-- 背景：阶段 1 的 chat_memory 只解决「单个会话内」的上下文；换会话即丢失。
-- 本表存放用户**显式要求**长期记住的信息（「记住：周报每周五交」），
-- 在每次会话对话中作为系统提示的一部分注入。
--
-- 设计要点（详见 specs/008-long-term-memory/）：
--   1. 只有内容与两个时间戳 —— 不做分类/标签/来源，因为注入是全量（带预算）、
--      检索是「最近更新优先」，没有任何消费方会读这些字段（research R-02）。
--   2. 没有 vector 列 —— 记忆是「必须始终生效的约束」，相似度召回恰好在
--      「对话没提到它」时漏掉它，与语义相悖（research R-03）。
--   3. 不建索引：预期几十至几百行，ORDER BY updated_at DESC 全表扫描即最优（R-08）。
--   4. 不加长度 CHECK：上限在 Service 层按「去空白后字符数」判定，
--      库里再约束一次会产生第二个口径（与阶段 7 标题校验同一模式）。
--
-- 纪律：已应用的迁移只追加，不改写。V1-V8 零触碰。
-- ============================================================

CREATE TABLE memory (
    id         uuid        PRIMARY KEY,
    content    text        NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

COMMENT ON TABLE memory IS '跨会话长期记忆：用户显式要求记住的信息，每次对话注入系统提示';
COMMENT ON COLUMN memory.content IS '记忆原文，落库前已去除首尾空白，长度不超过 2000';
COMMENT ON COLUMN memory.updated_at IS '最近修改时间，决定注入与列表的排序（倒序）';
