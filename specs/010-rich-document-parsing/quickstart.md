# Quickstart / 验收清单: 阶段 10 — 富文档解析

**Date**: 2026-10-25 | **Spec**: [spec.md](./spec.md) | **Contract**: [contracts/document-ingest.md](./contracts/document-ingest.md)

> ✅ 已实际执行并验证 ｜ ⏳ 未执行（须写原因） ｜ ❌ 失败

## 前置

```bash
cd /Volumes/WD770/Project/personal-ai-os/backend && ./mvnw -B verify
cd ../frontend && npm run type-check && npm run build && npm run test:logic
```

**基线**：后端 **216 tests / 0 failures**（阶段 9 的 203 → 216，新增 9 单测 + 4 集成）；
前端 124 断言全通过、`type-check` 0 退出、构建成功。

## 自动化验收

| 项 | 动作 | 关联 | 结果 |
|---|---|---|---|
| A-PDF 提取 | `PdfDocumentReaderTest`：用 PDFBox 现场生成 PDF，断言文本与元数据 | D-1 | ✅ |
| A-扫描版 PDF | 空白页 PDF → 「未提取到文本」 | D-2 | ✅ |
| A-损坏 PDF | `not a pdf` → 文案含「PDF」，不泄漏异常类名 | D-4 | ✅ |
| A-docx 段落 | 生成含两段的 docx，断言文本与顺序 | D-5 | ✅ |
| A-docx 表格 | **表格单元格「可重复读」也进入文本** | D-5 | ✅ |
| A-空 docx | → 「未提取到文本」 | D-6 | ✅ |
| A-图片文本 | 假 extractor 返回文本 → 进入 Document，MIME 透传正确 | D-7 | ✅ |
| A-图片空结果 | 返回 `""` 与 `"  \n\t "` → 均报「未提取到文本」 | D-9 | ✅ |
| I-PDF 全链路 | `RichDocumentIngestIntegrationTest`：真实 pgvector，`charCount>0`、`chunkCount>0`，检索命中 | D-1 / SC-001 | ✅ |
| I-docx 全链路 | 同上，检索「可重复读」命中表格内容 | D-5 / SC-001 | ✅ |
| I-扫描版零写入 | 40003 且 `knowledge_document` 与 `vector_store` 均 0 行 | D-2 / SC-002 | ✅ |
| I-白名单 | `.pptx` → 40003 且 message 列出支持格式 | D-10 | ✅ |
| A-回归 | 阶段 3 的 28 条测试 + 全量 216 条零失败 | SC-004 | ✅ |

## 真机验收（`java -jar` + postgres:55432 + curl -F）

| 项 | 动作 | 结果 |
|---|---|---|
| M-白名单 | 上传 `.pptx` → `400/40003`，message 列出 `md / markdown / txt / pdf / docx / png / jpg / jpeg / webp / gif` | ✅ |
| M-损坏 PDF | 上传 `not a real pdf` 命名为 `.pdf` → `400/40003`「PDF 解析失败」 | ✅ |
| M-扫描版 PDF | 上传空白 A4 PDF → `400/40003`「未提取到文本（如果是扫描件或纯图片，需要 OCR，当前未支持）」 | ✅ |
| M-正常 PDF | 上传浏览器打印的 19KB PDF → 解析**通过**（未报 40003），停在 `502/50200`「无法连接嵌入模型服务」 | ✅ 解析层正确 |
| M-正常 docx | 上传 `textutil` 生成的 docx → 同上 | ✅ 解析层正确 |
| M-失败零残留 | 上面五次上传后 `GET /documents` 返回 **0 条** | ✅ 事务回滚正确 |
| M-成功入库并可检索 | 接入 DashScope 后重跑：PDF `charCount=98/chunks=1`、图片 `charCount=29/chunks=1`、docx `charCount=87/chunks=1`；检索 score 0.803；RAG 问答带引用 | ✅ 2026-10-25 补验 |

## 未验证项与补验动作

**无**（2026-10-25 补齐）。

原「M-成功入库并可检索」曾因**本机没有可用嵌入模型**而挂起（上传停在 `50200 无法连接嵌入模型服务`）。
解析层当时就已经通过（否则会先报 40003）。配置 DashScope 后（`qwen3.7-text-embedding-flash`，1024 维）
重跑，全链路通过：

- PDF（浏览器打印生成，19 KB）→ `charCount=98`、`chunkCount=1`
- 图片（含中英文文本的 PNG）→ 视觉模型转录 → `charCount=29`、`chunkCount=1`
- docx（`textutil` 生成）→ `charCount=87`、`chunkCount=1`
- 「事务隔离级别 可重复读」检索 → 三条命中（0.803 / 0.698 / 0.661）
- RAG 问答 → 「图片里提到了『可重复读』和『读已提交』[1]」，引用 3 条带来源标题

> 另外发现：前端文档表格**原本没有展示 `charCount` 列** —— 这正是 `char_count` 恒为 0 多年没被
> 发现的原因。本阶段顺手把该列加到表格里，让这类回归能被肉眼看见。

> 链路正确性已由 `RichDocumentIngestIntegrationTest` 覆盖（真实 pgvector + 词袋嵌入桩，
> 与阶段 3 同一套做法）；真机缺的只是「真实嵌入模型」这一环，与阶段 3 的 `G-浏览器联调` 同因。

## 实现偏差与意外发现

| 项 | 说明 |
|---|---|
| **发现并修复既有缺陷**：`char_count` 恒为 0 | 阶段 3 起创建时写 0、之后只用 `updateChunkCount` 更新片段数，字符数**从未被写入**。本阶段的断言 `charCount > 0` 把它逼了出来。修法：`updateStats(charCount, chunkCount, now)`，`charCount` 取解析后文本总长 |
| 加密 PDF 用例未自动化 | PDFBox 生成加密 PDF 需要额外 API（`StandardProtectionPolicy`），收益低于成本；错误分支已由 `InvalidPasswordException` 的显式捕获覆盖，真机可手工验证 |
| 集成测试检索断言最初写成「字符数 > 0」失败 | 失败原因不是解析，而是上面那个 `charCount` 缺陷 —— 断言写得越具体，越能逼出真问题 |
