# Requirements Checklist: 阶段 3 — 个人知识库与 RAG

**Purpose**: 验证 `spec.md` 中需求的质量（完整性、清晰度、可测试性）。
**Created**: 2026-10-14
**Feature**: [spec.md](../spec.md)

**Marker Semantics**: `[x]` = 该需求质量准则已经评审并满足；不代表实现已完成。

## 需求完整性

- [x] CHK001 每个用户故事都有可独立验证的测试方式（5 个故事均含 Independent Test）
- [x] CHK002 每条功能需求（FR-001 ~ FR-021）均可追溯到验收场景或明确设计约定
- [x] CHK003 每条成功标准（SC-001 ~ SC-009）均为可度量表述，且多数以**数据库行数**为口径
- [x] CHK004 「明确不含」已列出（不含 PDF/Word/HTML、混合检索、重排序、集合隔离、原文存储、增量同步、流式问答）
- [x] CHK005 边界情况已覆盖：维度不一致、表与配置不一致、中英混排、BOM、超长单行、重复上传、超大文件、删除部分失败

## 需求清晰度

- [x] CHK006 无 `[NEEDS CLARIFICATION]` 遗留；未定项进入 Assumptions 并说明理由
- [x] CHK007 术语一致（文档 / 片段 / 命中 / 引用 / 维度）
- [x] CHK008 验收场景均为 Given/When/Then，无模糊动词
- [x] CHK009 spec 未预设实现细节（类名、表名、starter 坐标只出现在 plan/contracts/research）

## 一致性与可测试性

- [x] CHK010 FR-013（表与配置必须一致）与 FR-016（无模型也能启动）不矛盾 —— 前者只比对**本地**的两处配置，不调用模型；已在 research R-03 说明为何不用 `EmbeddingModel.dimensions()`
- [x] CHK011 FR-009（无命中不得编造）与 FR-007（必须返回回答）不矛盾 —— 用 `answered=false` 表达，已在契约中钉死
- [x] CHK012 FR-005/006（删除必须清理向量）与「不保存原文」不矛盾 —— 片段元数据里存了 `document_id`，清理有依据
- [x] CHK013 每条需求都能用命令或可观察现象验证（引用 quickstart 场景 A–E）

## 关键风险已显式记录

- [x] CHK014 **维度不一致**的三种来源与症状已列表记录（data-model.md），并给出三重校验
- [x] CHK015 **索引 opclass 与距离类型不匹配**不会报错、只会变慢，已记录风险与对策（research R-05）
- [x] CHK016 **删除顺序**（先删向量再删记录）的可恢复性理由已写明（data-model.md）
- [x] CHK017 **测试替身的边界**已声明：词袋嵌入只能验证链路正确，**不能**验证检索效果；效果验证被明确列为人工项（research R-07 + quickstart 场景 D）
- [x] CHK018 **框架不会替我们校验表结构**（`schema-validation` 默认 false）已记录，故迁移脚本必须逐列对齐（research R-02）

## 依赖与假设

- [x] CHK019 外部依赖已列明（Ollama + bge-m3 可选；自动化测试不需要任何外部模型）
- [x] CHK020 Assumptions 每条都给出影响（上限 5 MiB、阈值 0.3、topK 4、不存原文、引用由服务端产出）
- [x] CHK021 无隐藏前置：维度写死在迁移里，故「换模型」需要新迁移 —— 这一点已写进 FR-013 与 Assumptions
