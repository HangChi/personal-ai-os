# Implementation Plan: 阶段 10 — 富文档解析

**Branch**: `010-rich-document-parsing` | **Date**: 2026-10-25 | **Spec**: [spec.md](./spec.md)

## Summary

给知识库加三种内容来源：PDF（文本层，PDFBox）、docx（POI）、图片（视觉模型转文字）。
解析器按扩展名分派，其余链路（分块 / 向量化 / 检索 / 引用 / 删除）完全复用阶段 3，零改动。

## Technical Context

- **新依赖**：`org.apache.pdfbox:pdfbox:3.0.5`、`org.apache.poi:poi-ooxml:5.5.1`（均实测可拉取）
- **数据库**：**无迁移**（文档表已有 `content_type` / `char_count` / `chunk_count`）
- **前端**：上传组件的 accept 与提示文案（支持格式列表）
- **规模**：4 个新类（3 个 reader + 1 个工厂）+ Service 一处替换 + yml 白名单

## Constitution Check

| 原则 | 判定 | 依据 |
|---|---|---|
| I 简单优先 | ✅ | 只加两个库，且各自有被否决方案的论证（research R-01/R-02）；不做 OCR、不做 .doc/pptx/xlsx |
| II 边界即安全 | ✅ | 解析在服务端内存完成；文件名清洗与大小上限沿用阶段 3；错误不泄漏堆栈 |
| III 分层与写路径收敛 | ✅ | 解析器只负责「字节 → 文本」，入库仍由 `KnowledgeDocumentService` 编排；不向前端暴露实体 |
| IV 数据可追溯 | ✅ | 无结构变更 ⇒ 无迁移（沿用 V4 的表） |
| V 可验证的交付 | ✅ | 三种 reader 各有单测（真实字节构造）；集成测试验证 `charCount/chunkCount` 与检索命中；图片路径用桩模型断言「Media 真的被送出」 |

**结论**：无违例。Complexity Tracking 登记「为何用工厂而不是分支」。

## Project Structure

```text
backend/pom.xml                                   # 改：pdfbox 3.0.5、poi-ooxml 5.5.1
backend/src/main/resources/application.yml        # 改：allowed-extensions 增加 pdf,docx,png,jpg,jpeg,webp,gif
backend/src/main/java/com/personalos/knowledge/
├── reader/PdfDocumentReader.java                 # 新增
├── reader/DocxDocumentReader.java                # 新增
├── reader/ImageDocumentReader.java               # 新增（视觉模型）
├── reader/DocumentReaderFactory.java             # 新增（按扩展名分派）
├── reader/KnowledgeDocumentReader.java           # 不改：仍是文本解码器
└── service/KnowledgeDocumentService.java         # 改：用工厂；contentType 映射扩展
backend/src/test/java/com/personalos/knowledge/
├── reader/PdfDocumentReaderTest.java
├── reader/DocxDocumentReaderTest.java
├── reader/ImageDocumentReaderTest.java
└── RichDocumentIngestIntegrationTest.java        # 上传 pdf/docx 走完整链路（含向量库）
```

## Complexity Tracking

| 取舍 | 为什么需要 | 更简单的替代方案为何被否决 |
|---|---|---|
| 新增工厂类 | 三种格式的失败语义完全不同（加密 PDF / 损坏 docx / 模型不可用） | 把分支塞进 Service 或塞进 `KnowledgeDocumentReader`：前者让编排层变成分支迷宫，后者让"文本解码器"承担三种技术栈 |
