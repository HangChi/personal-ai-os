# Research: 阶段 10 — 富文档解析

**Date**: 2026-10-25 | **Spec**: [spec.md](./spec.md)

---

## R-01 PDF 提取：Apache PDFBox 3.0.5

**Decision**：`org.apache.pdfbox:pdfbox:3.0.5`，用 `Loader.loadPDF(byte[])` + `PDFTextStripper`。

**依据（已实测）**：`./mvnw dependency:get -Dartifact=org.apache.pdfbox:pdfbox:3.0.5` 成功，
本地仓库已落 `~/.m2/.../pdfbox/3.0.5`。3.x 的入口是 `Loader.loadPDF`（2.x 的 `PDDocument.load`
在 3.x 已移除），本地缓存里还有 2.0.30，但**不用 2.x**：新代码没有理由从旧大版本开始。

**被否决的方案**：
- **iText 7**：AGPL，商用需授权；本项目 Apache-2.0，许可证不兼容。
- **PDFBox 2.0.30**：本地已有、能离线构建，但 2.x 已进入维护模式，且 `PDDocument.load` 系列 API 在 3.x 被
  `Loader` 取代 —— 新写代码用旧大版本只会给自己留迁移债。
- **调用外部 `pdftotext`（poppler）**：需要系统级二进制，违反自托管单机「开箱即用」的前提，测试环境也不可控。

---

## R-02 Word 提取：Apache POI 5.5.1（仅 `poi-ooxml`）

**Decision**：`org.apache.poi:poi-ooxml:5.5.1`，用 `XWPFDocument` 遍历 `getBodyElements()`。

**依据（已实测）**：本地仓库已有 `~/.m2/.../poi-ooxml/5.5.1`，即该版本真实存在且曾被解析。

**为什么遍历 `getBodyElements()` 而不是只取 `getParagraphs()`**：Word 文档里的内容经常一半在段落、
一半在表格里（教案的课时表、课件的对照表）。只取段落会**静默丢掉整段表格**，
而用户不知道知识库里少了什么 —— 这类"看起来成功"的失败比报错更糟。

**被否决的方案**：
- **只用 `getParagraphs()`**：丢表格（见上）。
- **`.doc` 支持（HWPF）**：另一套解析器 + 另一组依赖；真实场景极少（research 已在 spec Assumptions 说明）。
- **docx4j**：功能更全但依赖树重得多（JAXB 等），为一个「取文本」的需求不值。

---

## R-03 图片：视觉模型，不加本地 OCR

**Decision**：把图片字节作为 `Media` 附到 `UserMessage`，调用既有 `plainChatClient`，把返回文本当作解析结果。

**依据**：
- 用户已选择「图片走视觉模型」。
- 本地 OCR（Tesseract）需要系统级安装与中文语言包，测试与部署都变重，且识别质量对板书/截图并不好。
- 复用既有模型装配点，不新增 AI 依赖：`UserMessage.builder().text(提示词).media(Media.builder()...build())`
  已核实存在于 Spring AI 2.0.1（`org.springframework.ai.content.Media` 在 `spring-ai-commons`）。

**被否决的方案**：
- **Tesseract OCR**：见上（系统依赖）。
- **只用视觉模型、不做任何降级**：会让"没配模型"变成 500。本设计要求无模型时**快速失败**
  并给出「当前未配置模型，无法解析图片」（FR-009）。

---

## R-04 解析器以「工厂」分派，不把分支塞进 Service

**Decision**：新增 `DocumentReaderFactory`，按扩展名返回对应的 `DocumentReader`；
`KnowledgeDocumentService` 只调工厂。

**依据**：Service 现在已经有校验、落库、分块、向量化、失败翻译五段职责。再塞进
「if pdf … else if docx … else if 图片」会让它变成分支迷宫，且每种格式的异常语义不同
（加密 PDF / 损坏 docx / 模型不可用），这些细节不该出现在编排层。

**被否决的方案**：
- **在 `KnowledgeDocumentReader` 内部按扩展名分支**：那个类的职责是「文本解码」，
  让它同时负责 PDF/Word/视觉调用，等于把三种完全不同的技术栈塞进一个类。

---

## R-05 「提取结果为空」必须报错，不能静默入库

**Decision**：任一路径提取出的文本为空（或只有空白）时，抛 `UNSUPPORTED_DOCUMENT`：
「未提取到文本（如果是扫描件，需要 OCR，当前未支持）」。

**依据**：阶段 3 已经有这条检查（`解析后没有可索引的内容`）。本阶段的新路径更容易命中它：
扫描版 PDF 与"模型说图片里没字"都会落到这里。**必须保留并给出可操作的解释** ——
否则用户只会看到"上传失败"，不知道是扫描件的问题。

**被否决的方案**：
- **空内容也入库（字符数 0）**：文档列表里会出现一条永远检索不到的记录，属于脏数据。

---

## R-06 依赖只加这两个，且都要在 `mvn verify` 下真跑

**Decision**：`pom.xml` 只新增 `pdfbox` 与 `poi-ooxml`，版本显式写死（不交给 BOM —— Spring AI BOM 不管这两个）。

**依据**：宪法原则 I 要求新增运行时依赖记录 Decision 与 Alternatives（本文档即记录）；
显式版本避免将来 BOM 变化导致隐式升级。

**影响**：`poi-ooxml` 会带进 `commons-compress` / `xmlbeans` / `log4j-api` 等传递依赖。
其中 `log4j-api` 只是 API（不是实现），与项目现有日志栈（Logback）共存无冲突 —— 构建后已由
`mvn verify` 全绿验证。
