# 内部数据

- AnswerPart：topic 1—80 Unicode字符；status 为 SUPPORTED/SUGGESTION/UNRESOLVED；text 最多1200；evidence 最多4条，每条为命中index和4—800字符连续原文。SUPPORTED/SUGGESTION 必须有正文和证据，UNRESOLVED 必须空正文/空证据。最多8项，总正文6000。
- AnswerVerification：complete=true、conflictsRespected=true，parts 按index完整覆盖所有回答项且supported=true。严格类型，不接收字符串布尔。
- StoredConflictScope：完整覆盖本次恢复的历史冲突，每项index、relevant布尔和非空reason，不得遗漏、重复或越界。
- RagResult.preparedAnswer：仅内部使用，Java JSON忽略、Go json:"-"；无新增公开字段或持久化字段。失败为空，使用确定性保护回答。
