# 实施顺序

1. 测试隔离与保留策略。
2. 完整备份、校验与隔离恢复。
3. 有界导出与模型指标。
4. Web Push 后端、Service Worker 与显式订阅界面。
5. 后端 verify、前端 lint/format/type/logic/component/build、恢复演练。

保持模块化单体；数据库仅新增 Flyway 迁移；网络发送在数据库事务之外。
