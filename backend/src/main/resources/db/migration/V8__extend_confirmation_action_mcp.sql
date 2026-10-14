-- ============================================================
-- V8: 扩展待确认动作取值范围，加入 MCP 工具调用
--
-- 背景：阶段 6 把外部 MCP server 的变更类工具纳入人工确认。MCP 工具名是运行期
-- 动态的（取决于接入了哪些 server），无法为每个工具生成静态枚举值，
-- 因此用**一个通用动作** MCP_TOOL_CALL 承载，具体 server/工具/参数在
-- payload_json 里（见 specs/006-mcp-client/research.md R-05）。
--
-- 纪律（阶段 4 教训）：已应用的迁移只追加，不改写。V1-V7 零触碰。
-- 约束名沿用 V5 确立、V7 扩展过的 ck_confirmation_action。
-- ============================================================

ALTER TABLE confirmation DROP CONSTRAINT ck_confirmation_action;
ALTER TABLE confirmation ADD CONSTRAINT ck_confirmation_action
    CHECK (action IN ('DELETE_TASK', 'SCHEDULE_TASK', 'SET_PRIORITY',
                      'DELETE_CALENDAR_EVENT', 'MCP_TOOL_CALL'));
