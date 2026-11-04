#!/usr/bin/env python3
"""按已经验收的职责组补充 P6 台账，保留其他阶段和发布门禁状态。"""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = ROOT / 'specs/033-go-rewrite'
GROUPS = {
    'storage': {
        'sources': ['KnowledgeDocumentService', 'KnowledgeDocumentEntity', 'KnowledgeDocumentRepository',
                    'KnowledgeMetadata', 'KnowledgeDocumentDto', 'KnowledgeDocumentDeletedDto',
                    'KnowledgeDocumentQuery', 'DocumentWriteLock', 'VectorStoreInspector',
                    'VectorDimensionValidator', 'KnowledgeIndexJobs', 'KnowledgeJobRepository',
                    'DocumentWriteLockTest', 'KnowledgeDocumentUpdateIntegrationTest', 'KnowledgeDocumentPageOverflowIntegrationTest',
                    'KnowledgeIndexJobsIntegrationTest', 'KnowledgeMinLengthIntegrationTest',
                    'EmbeddingDimensionMismatchIntegrationTest', 'VectorStoreIndexMigrationTest'],
        'evidence': ['store.go', 'jobs.go', 'metrics.go', 'store_integration_test.go'],
        'note': 'P6 第二批：真实TLS PostgreSQL/pgvector+42原迁移，文档原子替换/新引用元数据/删除/短文/维度/LIKE分页溢出、文档租约fencing、16路领取竞争、过期重领与旧token提交拒绝、24路队列容量/幂等/原子结果/负载与7日清理通过。HTTP/五角色/真实模型装配待P6后续批次。',
    },
    'readers': {
        'sources': ['DocumentFilenameSupport', 'KnowledgeDocumentReader', 'ReaderSupport',
                    'DocumentReaderFactory', 'PdfDocumentReader', 'DocxDocumentReader',
                    'ImageDocumentReader', 'VisionTextExtractor', 'MarkdownStructureTextSplitter'],
        'evidence': ['reader.go', 'reader_test.go', 'splitter.go', 'splitter_test.go'],
        'note': 'P6 首批：文件名/UTF-8/大小/短文拒收、PDF 500页和超时/空文本/损坏、DOCX段落表格/ZIP/XML边界、图片MIME/空白/无文本与上游失败、Markdown围栏/标题栈/重叠/短块无丢失均经race测试；真实模型装配、向量与完整Java差分仍待后续批次。',
    },
}

GROUPS.update({
    'retrieval': {
        'sources': ['KeywordExtractor', 'KnowledgeRetrievalService', 'KnowledgeSearchPort',
                    'RedisCandidateCache', 'KnowledgeHitDto', 'HybridSearchIntegrationTest',
                    'KeywordExtractorTest', 'VectorStoreInspectorPatternTest'],
        'evidence': ['retrieval.go', 'cache.go', 'retrieval_integration_test.go',
                     'cache_integration_test.go', 'oracle_integration_test.go', 'oracle_test.go'],
        'note': 'T603/T607/T609：真实TLS PostgreSQL/Redis验证阈值、RRF/IDF、中文补召回、LIKE字面量、权威回表、20秒TTL/256KiB/generation/事务绕过/关闭/故障；固定Java 152查询及18组受控召回和引用逐项相等。受控向量不表示真实语义质量。',
    },
    'background': {
        'sources': ['KnowledgeJobNotifier', 'RabbitJobNotifier', 'RabbitJobsConfig',
                    'WorkSignalRelay', 'WorkerWakeupsConfig', 'OpenSearchGateway',
                    'SearchConfig', 'SearchProjection', 'RabbitJobNotifierIntegrationTest',
                    'OpenSearchGatewayIntegrationTest'],
        'evidence': ['signals.go', 'search.go', 'module.go', 'search_integration_test.go',
                     'facilities_integration_test.go', 'process_integration_test.go'],
        'note': 'T604/T608：真实RabbitMQ publisher confirm、durable唤醒/重连/重复与非法通知；真实OpenSearch映射/外部版本/删除。数据库Outbox revision/token拒绝陈旧确认；双API+index/review/delivery与combined故障轮询；真实索引子进程SIGKILL并新进程恢复（测试前移租约期限）。',
    },
    'rag': {
        'sources': ['CrossEncoderReranker', 'EvidenceReview', 'RagModelGateway', 'RagResult',
                    'RagRetrievalPipeline', 'KnowledgeQaService', 'CitationDto',
                    'KnowledgeAskRequest', 'KnowledgeAskResponse', 'KnowledgeSearchRequest',
                    'KnowledgeSearchResponse', 'KnowledgeRagIntegrationTest',
                    'AdvancedRagIntegrationTest', 'CrossEncoderRerankerTest',
                    'RagModelGatewayTest', 'RagRetrievalPipelineTest'],
        'evidence': ['rag.go', 'qa.go', 'reranker.go', 'rag_test.go', 'gateway_test.go',
                     'governance_integration_test.go', 'oracle_test.go'],
        'note': 'T605：查询改写/融合/多样性/两种重排协议/证据/总预算；READY、CONFLICT、CLARIFY、UNVERIFIED及空库拒答，topK=1保留双方，实际编号引用；治理不可用保留证据并拒绝确定回答。无工具辅助模型的大小/队列/取消/关闭与视觉协议有回归。',
    },
    'governance': {
        'sources': ['KnowledgeGovernance', 'SourceInfo', 'IngestionReviewWorker',
                    'KnowledgeGovernanceIntegrationTest'],
        'evidence': ['governance.go', 'review.go', 'governance_integration_test.go'],
        'note': 'T606：空间/生效期/元数据/revision/生命周期、原文连续摘录复核、人工决议/审计/版本失效；review逐片段、16路领取、租约与revision最终fencing、失败三次暂停和人工重排。全量导出导入及备份切换仍按P7/P8验收。',
    },
    'assembly': {
        'sources': ['KnowledgeController', 'KnowledgeJobController', 'GovernanceController',
                    'SearchAdminController', 'KnowledgeProperties', 'KnowledgeToolContributor',
                    'KnowledgeTools', 'ChatClientVisionTextExtractor', 'package-info',
                    'RichDocumentIngestIntegrationTest', 'DocumentFilenameSupportTest',
                    'MarkdownStructureTextSplitterTest', 'DocxDocumentReaderTest',
                    'ImageDocumentReaderTest', 'PdfDocumentReaderTest'],
        'evidence': ['http.go', 'module.go', 'reader.go', 'reader_test.go', 'splitter_test.go',
                     'gateway_test.go', 'oracle_test.go'],
        'note': 'T600–T608：--foundation --p6显式装配全部知识HTTP/认证/CSRF/限流、只读工具、聊天共享端口与保守引用落库，独立候选Redis；五角色隔离/退出、索引enabled开关、可选设施关闭和消息代理失联轮询已验证。',
    },
})

GROUPS['storage']['evidence'] += ['retrieval.go', 'schema_integration_test.go', 'process_integration_test.go']
GROUPS['storage']['note'] = 'T601/T602：原42迁移与TLS PostgreSQL/pgvector验证JSON/维度/HNSW/表达式索引EXPLAIN及去索引反证、文档租约/原子替换/删除/引用、队列容量/幂等/回执/续租/最终fencing/负载清理；索引真实子进程SIGKILL后新进程完整恢复。'
GROUPS['readers']['evidence'] += ['oracle_test.go', 'gateway_test.go']
GROUPS['readers']['note'] = 'T600/T601：文件名/UTF16/UTF8/扩展名/大小/阈值、真实PDF/空白/损坏/500页与超时、DOCX段落表格/ZIP/XML、图片无工具视觉转录及错误、Markdown结构/围栏/重叠/无丢失通过；固定Java文件名和15组切分逐项相等。依赖与许可见THIRD_PARTY_NOTICES。'

# 固定基线的每个测试类都必须显式出现，不能用目录通配代替职责审查。
TESTS = {
    'EmbeddingDimensionMismatchIntegrationTest': ('TestP6DimensionAndDocumentLeaseFencing', '维度不符零写入'),
    'HybridSearchIntegrationTest': ('TestP6HybridThresholdIDFChineseAndLiteralPattern, TestP6KeywordWindowCountsBeforeLimitAndRRF, TestP6LiveCorpusControlledJavaRecallAndCitationParity', '全量统计先于limit、稀有词、空score、双通道、中文与去重；matchedBy经序列化和HTTP保留'),
    'KnowledgeDocumentPageOverflowIntegrationTest': ('TestP6DocumentAtomicReplaceMetadataDeleteAndPagination, TestFoundationP6DocumentsAPIAndRolePolling', '巨大页号、带keyword与正常分页'),
    'KnowledgeDocumentUpdateIntegrationTest': ('TestP6DocumentAtomicReplaceMetadataDeleteAndPagination, TestP6DimensionAndDocumentLeaseFencing', '同ID/创建时间、失败保留旧代、新引用元数据、写锁及完整删除'),
    'KnowledgeIndexJobsIntegrationTest': ('TestP6IndexJobIdempotencyAtomicResultAndPayloadCleanup, TestP6IndexJobClaimCompetitionExpiryAndFencing, TestP6QueueCapacityAcrossAPIReplicas, TestP6IndexSIGKILLAndProcessRestart, TestFoundationP6DocumentsAPIAndRolePolling', '202先返回、回执原子提交、替换失败、失效重领、旧Worker拒写、三次上限及跨API容量'),
    'KnowledgeMinLengthIntegrationTest': ('TestFilenameAndValidation, TestP6DimensionAndDocumentLeaseFencing', '短文拒收及等于最小阈值接收'),
    'KnowledgeRagIntegrationTest': ('TestFoundationP6DocumentsAPIAndRolePolling, TestFoundationP6SecurityGovernanceAndGuardedChat, TestP6LiveCorpusControlledJavaRecallAndCitationParity, TestQAActualCitationNumbersGuardAndEmptyNoAnswerCall', '上传/检索/问答、引用过滤、空库不生成、删除/400/404、聊天共享检索与重复上传独立'),
    'RabbitJobNotifierIntegrationTest': ('TestP6RealRabbitDurableConfirmedWakeupsAndReconnect, TestP6SignalConfirmedRevisionFailureAndBusinessIsolation', 'durable/confirm、重复唤醒不消费业务任务、重连及轮询兜底'),
    'RichDocumentIngestIntegrationTest': ('TestPDFTextEmptyBrokenAndPageLimit, TestDOCXParagraphsTablesAndSafety, TestP6DocumentAtomicReplaceMetadataDeleteAndPagination', 'PDF文本/扫描拒收、DOCX表格及不支持格式零写入；标准库解析替换PDFBox/POI'),
    'VectorStoreIndexMigrationTest': ('TestP6IndependentUploadsAndExpressionIndexPlan, TestP6HybridThresholdIDFChineseAndLiteralPattern, TestP6DimensionAndDocumentLeaseFencing', '原42迁移保留JSON/document_id表达式索引与HNSW；真实EXPLAIN采用索引，事务内去索引后Seq Scan反证并回滚恢复'),
    'KnowledgeGovernanceIntegrationTest': ('TestP6GovernanceSourceMetadataConflictAuditAndInvalidation, TestP6GovernanceRejectsForgedQuotesAndKeepBoth, TestP6ReviewIncrementalFailureRetryAndSourceRevision, TestP6ProjectionRevisionRecoveryDeleteAndReindex', '范围/日期/审计、两方原文/人工决议/失效、已存冲突补齐、增量review与投影失败；其中backupRestores的跨模块导入导出职责明确归P7/T704，完整备份恢复归P8/T804'),
    'AdvancedRagIntegrationTest': ('TestFoundationP6SecurityGovernanceAndGuardedChat, TestFoundationP6RealFacilitiesRolesAndCacheFailure', '启用实际管线、双源证据和确定作答保护'),
    'CrossEncoderRerankerTest': ('TestCrossEncoderCohereDashscopeAndInvalidRanks', 'Cohere/DashScope协议、非法/重复/越界排序拒收'),
    'RagModelGatewayTest': ('TestGatewayFencesOversizedJSONBudgetAndVisionPlainClient, TestGatewayBoundedQueueDeadlineCancellationAndClose', '围栏JSON、64K上限、预算、超时取消及关闭'),
    'RagRetrievalPipelineTest': ('TestPipelineReadyConflictTopOneFallbackAndNoContent, TestPipelineRewriteRecoveryAndFailedStages, TestEvidenceReviewCoverageGroundingPeriodsAndClarification, TestP6GovernanceOutageRetainsEvidenceAndGuardsAnswer', '七声明：冲突top1、非法重排回退、伪造摘录、改写救回、空库、全部阶段不可用及不同条件澄清'),
    'DocxDocumentReaderTest': ('TestDOCXParagraphsTablesAndSafety', '段落/表格顺序、空白与损坏'),
    'ImageDocumentReaderTest': ('TestVisionNormalizationMIMEAndErrors, TestGatewayFencesOversizedJSONBudgetAndVisionPlainClient', 'MIME、无文本/失败、无工具多模态协议'),
    'PdfDocumentReaderTest': ('TestPDFTextEmptyBrokenAndPageLimit', '真实文本PDF、空白/损坏和完整500页限制'),
    'OpenSearchGatewayIntegrationTest': ('TestP6RealOpenSearchProjectionHydrationVersionAndDelete', '中文混合索引、版本拒旧及删除'),
    'DocumentFilenameSupportTest': ('TestFilenameAndValidation, TestJavaP6FilenameKeywordSplitterAndCitationOracle', '10声明：空名/路径/300 UTF16/扩展名/标题/图片及文档MIME'),
    'DocumentWriteLockTest': ('TestP6DimensionAndDocumentLeaseFencing, TestP6IndexJobClaimCompetitionExpiryAndFencing', '多连接数据库租约替代本地锁、独立文档及幂等释放；共享协调包另验续租和事务完成释放'),
    'KeywordExtractorTest': ('TestKeywordsIdentifierStopWordsOrderAndLimit, TestJavaP6FilenameKeywordSplitterAndCitationOracle', '9声明与152条原有查询逐项对照'),
    'VectorStoreInspectorPatternTest': ('TestP6HybridThresholdIDFChineseAndLiteralPattern, TestP6KeywordWindowCountsBeforeLimitAndRRF', '原wordStartPattern仅为未被生产查询调用的辅助函数；生产searchByKeywords明确使用ILIKE子串，本迁移保留实际SQL与转义，不添加未使用正则API'),
    'MarkdownStructureTextSplitterTest': ('TestMarkdownStructureAndNoLoss, TestMarkdownFenceOverlapMinimumAndLongPrefix, TestJavaP6FilenameKeywordSplitterAndCitationOracle', '14声明的标题栈/同级/段落/前缀/重叠/空白/无丢失/围栏；15组固定Java切分逐项相等'),
}

def update(groups):
    path = SPEC / 'progress.json'
    progress = json.loads(path.read_text())
    inventory = json.loads((SPEC / 'inventory.json').read_text())
    entries = {entry['id']: entry for values in inventory.values() if isinstance(values, list)
               for entry in values if isinstance(entry, dict) and 'id' in entry}
    changed = set()
    for name in groups:
        group = GROUPS[name]
        evidence = ['backend-go/internal/business/knowledge/' + f for f in group['evidence']]
        evidence.append('specs/033-go-rewrite/validation.md')
        evidence += ['backend-go/internal/app/foundation_p6_integration_test.go',
                     'specs/033-go-rewrite/p6-completion.md',
                     'specs/033-go-rewrite/p6-test-mapping.md']
        if name == 'assembly':
            evidence += ['backend-go/internal/business/agent/knowledge.go',
                         'backend-go/internal/business/agent/knowledge_test.go']
        for entry in evidence:
            if not (ROOT / entry).is_file():
                raise ValueError('证据不存在: ' + entry)
        for key, entry in sorted(entries.items()):
            source_path = entry.get('path', '')
            if '/knowledge/' not in source_path:
                continue
            if not any('/' + source + '.java' in source_path or '/' + source + 'Test.java' in source_path
                       for source in group['sources']):
                continue
            item = progress['items'].setdefault(key, {'status': 'not_started', 'evidence': []})
            if item['status'] != 'verified':
                item['status'] = 'implemented'
            item['evidence'] = list(dict.fromkeys(item.get('evidence', []) + evidence))
            item['note'] = group['note']
            changed.add(key)
    if set(groups) == set(GROUPS):
        test_files = {str(p.relative_to(ROOT)): p.read_text()
                      for p in (ROOT / 'backend-go').rglob('*_test.go')}
        for entry in inventory['java_test_cases']:
            if '/knowledge/' not in entry['path']:
                continue
            names, note = TESTS[Path(entry['path']).stem]
            evidence = []
            for name in names.split(', '):
                matches = [path for path, text in test_files.items()
                           if 'func ' + name + '(' in text]
                if not matches:
                    raise ValueError('Go 断言不存在: ' + name)
                evidence += matches
            item = progress['items'][entry['id']]
            item['evidence'] = list(dict.fromkeys(item['evidence'] + evidence))
            item['note'] = ('原断言 ' + entry['handler']['name'] + '；Go: ' + names +
                            '。' + note + '。逐方法清单见p6-test-mapping.md。')
        for name, task in [('KnowledgeExportContributor', 'P7/T704'),
                           ('KnowledgeSearchContributor', 'P7/T703')]:
            key = 'file:backend/src/main/java/com/personalos/knowledge/' + name + '.java'
            item = progress['items'].setdefault(key, {'status': 'not_started', 'evidence': []})
            item['note'] = '跨模块贡献器归' + task + '，P6未实现也未验收该职责。'
    path.write_text(json.dumps(progress, ensure_ascii=False, indent=2) + '\n')
    print('更新 P6 条目:', len(changed))


def mapping():
    inventory = json.loads((SPEC / 'inventory.json').read_text())
    tests = [e for e in inventory['java_test_cases'] if '/knowledge/' in e['path']]
    classes = {Path(e['path']).stem for e in tests}
    if classes != set(TESTS):
        raise ValueError('P6 测试声明映射不完整: ' + str(classes ^ set(TESTS)))
    go = '\n'.join(p.read_text() for p in (ROOT / 'backend-go').rglob('*_test.go'))
    for names, _ in TESTS.values():
        for name in names.split(', '):
            if 'func ' + name + '(' not in go:
                raise ValueError('Go 断言不存在: ' + name)
    lines = ['# P6 原测试职责映射', '',
             '固定 Java 基线：`' + inventory['baseline_commit'] + '`。' +
             f'共 {len(classes)} 类、{len(tests)} 个测试声明。下表逐条保留原方法名和 Go 断言；Spring/Mockito 实现细节以 Go 的事务/生命周期验证替代。', '',
             '复验：`python3 scripts/migration/go_p6_progress.py --mapping`。实现和实测边界见 [P6 验收](p6-completion.md)。跨模块导入/导出与发布恢复仍归 P7/P8；不改变五个发布门禁。', '']
    for name in sorted(classes):
        funcs, note = TESTS[name]
        lines += ['## ' + name, '', note + '。', '',
                  '对应 Go：' + '、'.join('`' + f + '`' for f in funcs.split(', ')) + '。', '',
                  '| 原方法 | 固定来源行 |', '| --- | ---: |']
        lines += [f'| `{e["handler"]["name"]}` | {e["line"]} |'
                  for e in tests if Path(e['path']).stem == name]
        lines.append('')
    (SPEC / 'p6-test-mapping.md').write_text('\n'.join(lines))
    print('P6 测试映射:', len(classes), '类,', len(tests), '声明')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('groups', nargs='*')
    parser.add_argument('--mapping', action='store_true')
    args = parser.parse_args()
    if set(args.groups) - {*GROUPS, 'all'}:
        parser.error('未知职责组: ' + ', '.join(set(args.groups) - {*GROUPS, 'all'}))
    if args.mapping or args.groups:
        mapping()
    if args.groups:
        update(list(GROUPS) if 'all' in args.groups else args.groups)
