---
description: "Task list for 阶段 10 — 富文档解析"
---

# Tasks: 阶段 10 — 富文档解析

**Input**: Design documents from `/specs/010-rich-document-parsing/`

> 状态：**已实现并验证**（2026-10-25）。证据见 [quickstart.md](./quickstart.md)。

## Phase 1: 依赖与解析器 ✅

- [x] T001 `pom.xml` 加 `pdfbox:3.0.5`、`poi-ooxml:5.5.1`（版本显式，不走 BOM）
- [x] T002 `application.yml` 白名单加 `pdf,docx,png,jpg,jpeg,webp,gif`（并支持 `AI_KNOWLEDGE_ALLOWED_EXTENSIONS` 覆盖）
- [x] T003 [P] `ReaderSupport`：归一（去 BOM / trim）+ 空文本统一报错
- [x] T004 [P] `PdfDocumentReader`：PDFBox 3 `Loader.loadPDF` + `PDFTextStripper`；加密与损坏分别翻译
- [x] T005 [P] `DocxDocumentReader`：遍历 `getBodyElements()`，**覆盖表格单元格**
- [x] T006 [P] `VisionTextExtractor` + `ChatClientVisionTextExtractor`：图片 → `UserMessage` + `Media` → plainChatClient
- [x] T007 [P] `ImageDocumentReader`：把 extractor 结果走同一归一逻辑
- [x] T008 [P] `DocumentReaderFactory`：按扩展名分派
- [x] T009 `KnowledgeDocumentService`：注入工厂替换 `new KnowledgeDocumentReader`；`contentTypeOf` 映射扩展

## Phase 2: 测试 ✅

- [x] T010 [P] `PdfDocumentReaderTest`（3）：提取 / 扫描版 / 损坏
- [x] T011 [P] `DocxDocumentReaderTest`（3）：段落+表格 / 空文档 / 损坏
- [x] T012 [P] `ImageDocumentReaderTest`（3）：文本 / 空串 / 空白
- [x] T013 `RichDocumentIngestIntegrationTest`（4）：PDF 全链路、扫描版零写入、docx 表格可检索、白名单
- [x] T014 `./mvnw -B verify`：**216 tests / 0 failures**

## Phase 3: 前端与真机 ✅

- [x] T015 `KnowledgeView`：`accept` 与提示文案补齐 PDF/docx/图片
- [x] T016 真机五条路径（白名单 / 损坏 / 扫描版 / 正常 PDF / 正常 docx）+ 失败零残留
- [x] T017 回填 quickstart；README 与 architecture 路线图补阶段 10
- [x] T018 **提交**

---

## 完成记录

| 内容 | 结果 |
|---|---|
| 后端基线 | 203 → **216** 测试（+9 单测 +4 集成），BUILD SUCCESS |
| 依赖 | pdfbox 3.0.5、poi-ooxml 5.5.1，均实测可拉取；无迁移 |
| 真机 | 5 条路径全部符合契约；失败入库**零残留**（事务回滚正确） |
| **顺带修复** | `knowledge_document.char_count` 自阶段 3 起恒为 0（两处各写一半，谁都看不出来）；改为 `updateStats(charCount, chunkCount, now)` |
| 未验证 | 真机「成功入库 + 检索」：缺可用嵌入模型（与阶段 3 的 G 项同因），附两条补验路径 |

## Notes

- **空文本必须报错**：扫描版 PDF、纯图片、损坏 docx 都会「解析成功但文本为空」，
  静默入库会产生一条永远检索不到的记录。
- **docx 必须遍历 body 元素**：只取 `getParagraphs()` 会静默丢掉整张表格。
- 图片的 MIME 要真的传对：模型收到「text/plain 的图片」时不会报错，只是识别不出内容。
- 解析失败必须在**向量化之前**抛出，否则会留下半条文档记录（本阶段的真机验证专门断言了"零残留"）。
