# Implementation Plan: 安全自动化与资料互通
**Branch**: `go` | **Date**: 2026-11-06 | **Spec**: [spec.md](spec.md)

## Summary
四项作为独立交付切片，Java 稳定版和 Go 候选版共用 SQL/接口，Vue 延续现有卡片、表单和设计变量。先实现日历文件互通，再采集、工作流、维护中心；每片验证后提交。当前工作区干净，保留现有分支，不部署或触碰用户运行数据。

## Technical Context
- Java21/SpringBoot4/SpringAI2、Go 现有模块、Vue3/ElementPlus；PostgreSQL+pgvector。
- 新结构只追加 V44..V47；原 V1..V43 和 033 冻结清单不改。
- ICS 采用受限、严格、可无损子集解析，不引入完整日历引擎。
- 网页抓取明确依赖已存在的 HttpClient5；Go HTML 解析使用 x/net/html，版本从现有模块锁定。记录直接依赖，不升级其他库。
- 工作流首版是固定只读报告策略，零模型/工具调用，消费上限准确表述为每日运行次数、数据项和报告字符数；保存既有 chat_run 身份和回执，不让普通恢复器接管。
- 完整数据库恢复仍使用既有 backup.py 的随机独立库演练；运行时不获 Docker socket、CREATEDB 或任意路径执行权限。

## Constitution Check
设计前后通过：模块化单体、DTO、Service 写路径、构造器注入、追加迁移；网络/模型耗时工作在写事务外。内部/私网网络禁止访问；文件和列表有界。真实命令验证、每阶段及时提交。旧台账的未登记源文件漂移保持开放，不重写冻结基线凑绿。

## Project Structure
- calendar：两端 ICS codec、exchange service/controller、身份映射和冲突查询；CalendarExchange.vue。
- knowledge：两端安全 URL fetcher、capture service、版本/任务映射；KnowledgeCaptures.vue。
- workflow：两端调度/固定报告 service、持久期次/Run、历史 API；WorkflowsView.vue。
- maintenance：两端业务备份纯校验器、真实心跳、状态聚合和校验记录；MaintenanceView.vue。
- specs/035-safe-automation：设计、契约、任务与验证记录。

## Phases and Validation
1. 规格、设计、任务分别提交，扩展 hooks 不存在。
2. 日历：严格 codec 的共同样例、去重/原子性 DB 测试、冲突边界、UI。
3. URL：URL/DNS/连接绑定防护、HTML清洗、同源并发/失败/版本 DB 测试、UI。
4. 工作流：时区/DST、并发期次/预算/重试、无工具和业务零写入、UI。
5. 维护：备份引用校验、心跳状态、失败列表和外部演练报告的准确标识；只读数据前后校验。
6. Java verify、Go race/vet/build、前端 lint/format/types/tests/build；隔离临时库和浏览器关键流程，记录未测范围。

## Complexity Tracking
新增抽象限于安全抓取、严格文件 codec 与独立工作流执行器：通用 Agent 恢复不能保证只读策略，现有 HTTP 客户端不能防 DNS 重绑定，故不能直接复用。无新常驻服务。
