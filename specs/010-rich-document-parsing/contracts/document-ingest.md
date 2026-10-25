# Contract: 富文档上传与解析

**Date**: 2026-10-25 | **Spec**: [spec.md](../spec.md)

## 一、扩展名 → 解析器 / MIME 映射（顺序即契约）

```
POST /api/v1/knowledge/documents   (multipart/form-data, part = "file")
```

| 扩展名 | 解析器 | 存储的 `content_type` | 依赖 |
|---|---|---|---|
| `md` / `markdown` | `KnowledgeDocumentReader`（文本） | `text/markdown` | — |
| `txt` / 其它已放行 | `KnowledgeDocumentReader`（文本） | `text/plain` | — |
| `pdf` | `PdfDocumentReader` | `application/pdf` | PDFBox 3.0.5 |
| `docx` | `DocxDocumentReader` | `application/vnd.openxmlformats-officedocument.wordprocessingml.document` | POI 5.5.1 |
| `png` | `ImageDocumentReader` | `image/png` | 视觉模型 |
| `jpg` / `jpeg` | 同上 | `image/jpeg` | 视觉模型 |
| `webp` / `gif` | 同上 | `image/webp` / `image/gif` | 视觉模型 |

白名单（`app.knowledge.allowed-extensions`）：
`md,markdown,txt,pdf,docx,png,jpg,jpeg,webp,gif`。

## 二、错误码映射

| HTTP | code | message | 条件 |
|---|---|---|---|
| 400 | 40003 | 不支持的文件类型「x」，当前仅支持：… | 扩展名不在白名单 |
| 400 | 40003 | 文件内容为空 | 0 字节 |
| 400 | 40003 | 文件过大（…） | 超过 5 MiB |
| 400 | 40003 | PDF 已加密，无法提取文本… | 加密 PDF |
| 400 | 40003 | PDF 解析失败：文件可能已损坏… | 损坏 / 非 PDF |
| 400 | 40003 | Word 解析失败：文件可能已损坏…（旧版 .doc 暂不支持） | 损坏 / 非 docx |
| 400 | 40003 | 未提取到文本（如果是扫描件或纯图片，需要 OCR，当前未支持） | 任何解析器返回空文本 |
| 400 | 40003 | 当前未配置 AI 模型，无法解析图片（请先在 .env 中配置…） | 图片 + 无模型（由 `AI_NOT_CONFIGURED` 转换） |
| 400 | 40003 | 不是有效的 UTF-8 编码… | `.txt` / `.md` 编码非法（阶段 3 行为不变） |
| 502 | 50200 | 无法连接嵌入模型服务… | **解析成功后**向量化阶段模型不可达 |
| 502 | 50200 | AI 服务调用失败… | 图片的视觉模型调用失败（上游问题，不伪装成文件问题） |

**顺序**：白名单 → 空文件 → 大小 → 解析 → 分块 → 向量化。
解析失败时**文档记录已被回滚**（整个 `ingest` 在一个事务里），数据库与向量库都不留残留。

## 三、行为用例表

| # | 输入 | 期望 |
|---|---|---|
| D-1 | 含文本层的 PDF | 200；`charCount > 0`、`chunkCount > 0`；检索命中正文 |
| D-2 | 扫描版 PDF（空白页） | 40003「未提取到文本」；`knowledge_document` 与 `vector_store` 均 0 行 |
| D-3 | 加密 PDF | 40003「PDF 已加密」 |
| D-4 | `not a pdf` 冒充 `.pdf` | 40003「PDF 解析失败」 |
| D-5 | docx（段落 + 表格） | 200；表格里的词也能被检索命中 |
| D-6 | 空 docx | 40003「未提取到文本」 |
| D-7 | 图片 + 已配置模型 | 200；模型转录文本进入索引 |
| D-8 | 图片 + 无模型 | 40003「当前未配置 AI 模型…」；零写入 |
| D-9 | 图片 + 模型返回「无文本」 | 40003「未提取到文本」（不写入空文档） |
| D-10 | `.pptx` | 40003 白名单，message 列出当前支持的格式 |
| D-11 | 合法 `.md` | 行为与阶段 3 完全一致（回归） |
| D-12 | `charCount` | **不为 0**（修复前恒为 0） |

## 四、前端契约

| 关注点 | 契约 |
|---|---|
| `accept` | `.md,.markdown,.txt,.pdf,.docx,.png,.jpg,.jpeg,.webp,.gif` |
| 提示文案 | 列出全部支持格式，并说明「PDF 需文本层」「图片需配置视觉模型」「单文件 ≤ 5 MiB」 |
| 错误展示 | 直接展示后端 `message`（这些文案已经是给人看的） |
| 进度 | 沿用既有 uploading 状态；不新增进度条（后端是同步请求） |
