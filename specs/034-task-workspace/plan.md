# Implementation Plan: 今日工作台与项目任务
**Branch**: `go` | **Date**: 2026-11-06 | **Spec**: [spec.md](spec.md)

## Summary
扩展现有任务模型与任务页，首页增加行动区。Java/Go 共用追加的 Flyway 迁移，数据库约束保证层级与周期生成在所有写入口一致。

## Technical Context
- Language/Version: Java 21、Go 1.27、TypeScript/Vue 3。
- Primary Dependencies: 复用 Spring/JPA/JDBC、database/sql、Element Plus，不新增运行时依赖。
- Storage: PostgreSQL；V43 追加项目和任务关联、周期字段及事务触发器。
- Testing: 现有 Maven、Go test、前端逻辑/组件测试；独立临时 PostgreSQL 验证迁移与并发。
- Target Platform: 自托管 Web 单体；Scale: 个人工作数据。
- Performance Goals: 今日每组最多 20 项并显示总数，SQL 筛选分页，避免全表客户端扫描。
- Constraints: 不改历史迁移或 033 冻结快照；不部署、不自动提交、不触碰现有脏文件。用户后续已明确要求分拆提交，本次提交仅包含 034 的实现和验证记录。

## Constitution Check
设计前后均通过：沿用模块化单体与 Service 写路径、DTO、构造器注入；新增结构仅 Flyway；无新增服务、无外部副作用、无真实密钥。沿用现有 Agent 工具和确认通道；首版不新增 Agent 项目操作。按 specify→plan→tasks→implement 串行执行，用户授权继续实现且不自动创建提交。

## Project Structure
- backend/src/main/resources/db/migration/V43__task_workspace.sql
- backend/src/main/java/com/personalos/task/{dto,entity,repository,service,controller}
- backend/src/main/java/com/personalos/export
- backend-go/internal/business/{task,dataimport}
- frontend/src/{api,lib,components/task,components/home,views,stores}
- specs/034-task-workspace/{spec,plan,research,data-model,quickstart,tasks,validation}.md

## Complexity Tracking
数据库触发器只负责跨后端共享不变量与原子生成；避免两套 Service 对并发完成、Agent 写入和导入有不同语义。所有用户写入仍经过 Service。033 迁移基线会检测到有意的新增源文件差异，保持旧快照不变并单独记录，而非伪造验收通过。
