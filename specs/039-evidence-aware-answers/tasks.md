# 实施任务

## 基础

- [x] T001 完成 specs/039-evidence-aware-answers/spec.md 与设计；保存 /tmp/personalos-039-freeze.json 基线快照并验证原源码门禁。
- [x] T002 [US1] 在 contracts/answer-cases.json 与双端 EvidenceAnswer 测试中定义部分冲突、建议、引文造假和核对失败契约。

## US1 · 逐项回答

- [x] T003 [US1] 修改双端 knowledge/rag 的证据保留与确定性分歧渲染。
- [x] T004 [US1] 实现 EvidenceAnswer.java 与 evidence_answer.go，严格校验生成和独立核对，受统一预算限制。
- [x] T005 [US1] 接入双端检索管线与 knowledge 工具；验证问答/对话复用、内部缓存不序列化、工具编号重映射。

## US2 · 历史冲突范围

- [x] T006 [US2] 在 EvidenceReview.java 与 evidence_scope.go 中复核恢复冲突与当前问题的关系；核对失败保护，不写回原记录。
- [x] T007 [US2] 在双端 governance/pipeline 单元与隔离集成测试中覆盖同片段无关事实、真冲突保留及非法范围核对。

## US3 · 来源体验

- [x] T008 [US3] 实现 RagDisagreements.vue，调整 ChatSources.vue 与 RagTrace.vue 的信息层级、数量及去重。
- [x] T009 [US3] 补充 frontend/test/component/chat-sources.test.ts 与 scripts/ui/browser-checks.mjs，验证键盘/恢复/窄屏/深浅主题。

## 验证与登记

- [x] T010 执行 Java verify、Go 单测/race/受影响隔离集成、前端全套检查和浏览器视觉复核，记录 validation.md。
- [ ] T011 按职责提交，更新 specs/033-go-rewrite/scope-changes.json 候选与责任锚点；运行迁移派生、185项脚本与冻结检查。

## 依赖与执行

T001→T002→T003/T004→T005；T006→T007；T008→T009；全部完成后 T010→T011。独立测试可以并行执行，源码修改按文件串行；先完成 US1，再收束 US2/US3。没有待澄清项或扩展 hooks。
