# Data Model: 阶段 10 — 富文档解析

**Date**: 2026-10-25 | **Spec**: [spec.md](./spec.md)

## 结构变更：无

不新增表、不新增列、不新增迁移。复用阶段 3 的 `knowledge_document` 与 `vector_store`。

| 字段 | 本阶段的语义 |
|---|---|
| `content_type` | 扩展：`application/pdf`、docx 的官方 MIME、`image/png|jpeg|webp|gif` |
| `char_count` | **语义修正**：改为「解析出的文本总字符数（分块前）」 |
| `chunk_count` | 不变：实际写入向量库的片段数 |
| `size_bytes` | 不变：原始上传字节数 |
| `filename` / `title` | 不变（标题仍取文件名去扩展名，可覆盖） |

## 顺带修复的既有缺陷：`char_count` 永远是 0

**现象**：阶段 3 起，`knowledge_document.char_count` 在创建时被写成 `0`，之后只调用过
`updateChunkCount(chunkCount, now)` —— 字符数**从未被更新**。文档列表里的「字符数」一直是 0。

**为什么直到现在才发现**：阶段 3 的测试断言的是「文档数、片段数、检索命中」，
没有任何用例断言 `charCount > 0`；前端也没展示这一列。直到本阶段写
「PDF 入库后 `charCount > 0`」的验收标准，它才作为**无法满足的断言**暴露出来。

**修法**：实体方法 `updateChunkCount(int, Instant)` → `updateStats(int charCount, int chunkCount, Instant now)`，
入库时用 `parsed` 的文本长度求和作为 `charCount`。只有一处调用点，无兼容成本。

> 这类缺陷正是「用产物校准规格」的价值：它不是代码写错了某一行，而是**两处各写一半**，
> 单看任何一处都"看起来对"。

## 明确不做的变更

| 不做的变更 | 理由 |
|---|---|
| `source_format` 列 | `content_type` 已能区分格式 |
| `parse_warnings` 列（如"本 PDF 有 3 页是图片"） | 需要逐页分析，本阶段只做整体提取；无消费方 |
| OCR 相关的缓存表 | 不做 OCR |
| `vector_store` 表结构 | 与阶段 3 完全一致，维度守卫仍有效 |
