# Feature Specification: 阶段 10 — 富文档解析

**Feature Branch**: `010-rich-document-parsing`

**Created**: 2026-10-25

**Status**: Draft

**Input**: 用户需求：「按照建议的几个缺口，帮我逐个实现」的第四项 —— 知识库只能吃 UTF-8 纯文本，
`.pdf` / `.docx` / 图片一律被拒（阶段 3 的 `40003`）。

## 背景

阶段 3 的 `KnowledgeDocumentReader` 只做文本解码：严格 UTF-8 校验 + `TextReader`。
真实的知识库内容大多不在 `.md` 里 —— 课件是 PDF，教案是 Word，手机拍的板书是图片。
本阶段补上这三类内容的**文本提取**，其余链路（分块、向量化、检索、引用、删除）完全复用阶段 3。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 上传 PDF / Word 并检索（Priority: P1）

作为用户，我希望把课件 PDF 和教案 Word 直接传进知识库，并像 Markdown 一样被检索与引用。

**Why this priority**：这是本阶段的主要价值。PDF/Word 是真实的文档载体，纯文本只是少数派。

**Independent Test**：上传一个含已知句子的 PDF 与一个 docx，文档列表显示字符数与片段数均大于 0，
用该句子检索能命中并给出引用。

**Acceptance Scenarios**：

1. **Given** 一个含文本层的 PDF，**When** 上传，**Then** 200，`charCount > 0`、`chunkCount > 0`，检索可命中。
2. **Given** 一个 `.docx`，**When** 上传，**Then** 同上，且段落按原有顺序拼接。
3. **Given** 一个**扫描版 PDF**（无文本层），**When** 上传，**Then** 明确报错「未提取到文本（可能是扫描件）」，**不写入**文档记录与向量。
4. **Given** 一个加密/损坏的 PDF，**When** 上传，**Then** 明确报错，不泄漏堆栈。
5. **Given** 超大文件，**When** 上传，**Then** 仍由既有大小上限拦截（行为不变）。

---

### User Story 2 - 上传图片并检索（Priority: P2）

作为用户，我希望把拍下来的板书、截图丢进知识库，之后能按内容检索到。

**Why this priority**：图片是补充入口；PDF/Word 覆盖绝大多数场景，即使没有它 US-1 也完整。
但它需要视觉模型，属于**有条件能力**，必须诚实告知不可用的原因。

**Independent Test**：配置支持视觉的模型后上传一张含文字的图片，返回的文本进入索引；
未配置时上传，得到「当前模型不支持图片」的明确错误，而不是 500。

**Acceptance Scenarios**：

1. **Given** 已配置可用模型，**When** 上传 `.png` / `.jpg`，**Then** 模型返回的描述文本进入索引，`charCount > 0`。
2. **Given** 未配置任何模型（`spring.ai.model.chat=none`），**When** 上传图片，**Then** 返回 `40003`「当前未配置模型，无法解析图片」，且**不写入**任何数据。
3. **Given** 模型调用失败（超时/额度），**When** 上传图片，**Then** 返回明确的「图片解析失败」而不是内部错误。
4. **Given** 模型返回空文本，**When** 上传，**Then** 与扫描版 PDF 同样处理：报「未提取到文本」。

## Edge Cases

| 输入 | 期望行为 |
|---|---|
| `.pdf` 无文本层（扫描件） | `40003`「未提取到文本（如果是扫描件，需要 OCR，当前未支持）」 |
| `.pdf` 加密 | `40003`，提示「PDF 已加密」 |
| `.docx` 只有图片/表格 | 提取表格单元格文本；若整体为空 → 与扫描件同样报错 |
| `.doc`（旧格式）/ `.pptx` / `.xlsx` | 不支持，`40003`（白名单拒绝，理由见 research R-03） |
| 图片格式 `webp`/`gif` | 支持（视觉模型接受主流图片 MIME） |
| 图片超大（> 5MB） | 由既有大小上限拦截 |
| UTF-8 非法的 `.txt` | 行为不变：`40003`「不是有效的 UTF-8」 |
| 扩展名与实际内容不符（`.pdf` 实为文本） | 按 PDF 解析失败 → `40003`「不是有效的 PDF」 |

## Requirements *(mandatory)*

- **FR-001**：MUST 支持 `pdf`（文本层）、`docx`、`png/jpg/jpeg/webp/gif` 的白名单扩展名。
- **FR-002**：PDF 提取 MUST 使用文本层；MUST NOT 尝试 OCR（明确不含）。
- **FR-003**：`docx` 提取 MUST 覆盖段落与表格单元格文本，并保持文档顺序。
- **FR-004**：图片 MUST 经视觉模型转成文本后再进入既有分块与向量化流程。
- **FR-005**：任一路径提取结果为空时 MUST 报「未提取到文本」，且 MUST NOT 留下文档记录或向量。
- **FR-006**：解析失败 MUST 归一为 `40003` 与可读中文文案，MUST NOT 泄漏堆栈或类名。
- **FR-007**：既有纯文本路径的校验（严格 UTF-8、去 BOM）MUST 保持不变。
- **FR-008**：新增依赖 MUST 在 `research.md` 记录 Decision 与 Alternatives（宪法原则 I）。
- **FR-009**：图片路径在「无可用模型」时 MUST 快速失败并给出可操作提示，MUST NOT 在解析中途抛原始异常。

## Success Criteria

- **SC-001**：含文本层的 PDF 与 docx 上传后 `charCount > 0`、`chunkCount > 0`，检索能命中其内容。
- **SC-002**：扫描版 PDF → `40003` 且文档表与向量表均**零写入**。
- **SC-003**：无模型时上传图片 → `40003`，同样零写入。
- **SC-004**：阶段 3 既有测试（28 条）零回归；非法 UTF-8、空文件、超限行为不变。

## Assumptions

- **不做 OCR**：扫描版 PDF 与纯图片文字的离线识别需要 Tesseract + 语言包（系统级依赖），
  与「零冗余依赖、自托管单机」的基调冲突；图片走视觉模型是已选的折中。
- **`.doc`（旧二进制格式）不做**：需要 POI 的 HWPF 分支，与 docx 的 XWPF 是两套解析器；
  真实用户极少上传旧格式，收益不足（research R-03）。
- **`.pptx` / `.xlsx` 不做**：本阶段聚焦「文档」，表格与幻灯片的解析规则不同（合并单元格、备注页），
  应各自评估。
- **图片解析依赖模型能力**：视觉模型对图片内容的提取质量不由本系统保证；系统只保证链路与错误语义。
