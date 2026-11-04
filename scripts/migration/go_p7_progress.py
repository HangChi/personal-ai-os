#!/usr/bin/env python3
"""Record P7 responsibilities and every original test without closing release gates."""
import argparse
from functools import lru_cache
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = ROOT / 'specs/033-go-rewrite'
CONTRIBUTORS = {f'{module}{kind}Contributor' for module in ['Task', 'Chat', 'Calendar', 'Memory', 'Knowledge'] for kind in ['Search', 'Export']}
BACKUP = 'backupRestoresKnowledgeSpaceAndEffectiveScope'
COMMON = ['specs/033-go-rewrite/p7-completion.md', 'specs/033-go-rewrite/p7-test-mapping.md',
          'specs/033-go-rewrite/validation.md']
TESTS = {
    'McpToolNamingTest': ('TestP7NamesPolicyAndSchema, TestJavaP7NamingPolicySummarySearchPersonalAndImportOracle', '前缀/清洗/空名/100字符/两种冲突，包含Locale.ROOT Unicode差分'),
    'McpToolPolicyTest': ('TestP7NamesPolicyAndSchema, TestJavaP7NamingPolicySummarySearchPersonalAndImportOracle', '仅原始名称精确命中只读清单为AUTO，其余CONFIRM'),
    'McpToolCallbackProviderTest': ('TestP7NamesPolicyAndSchema, TestJavaP7NamingPolicySummarySearchPersonalAndImportOracle', '运行时对齐Spring adapter归一化后的object/properties；原hasUsableSchema是对归一化结果的防御检查，不把其原始JSON输入与SDK转换前输入混为同一层'),
    'McpConfirmationSummaryTest': ('TestP7SummaryAndDisabledConfirmation, TestJavaP7NamingPolicySummarySearchPersonalAndImportOracle', '服务端依据payload生成，键排序前三个、换行/80 UTF16/省略统计'),
    'McpStatusServiceTest': ('TestP7HTTPHeadersAndRedactedFailureStatus, TestP7PersonalProtocolReadOnlyCatalogAndIndependentSwitch', '状态/禁用/故障只显示受控文案，不泄露环境/命令/请求头/端点凭据'),
    'PersonalMcpExposureTest': ('TestP7PersonalProtocolReadOnlyCatalogAndIndependentSwitch, TestP7CustomMCPExemptionKeepsAuthenticationAndOtherCSRF, TestP7MCPAndFiveModuleSearchPostgres', '真实HTTP协商与四个工具调用；独立开关、只读注解、禁递归/写工具、认证不豁免'),
    'McpClientRegistryIntegrationTest': ('TestP7RealStdioLifecycleAndFailureIsolation, TestP7StdioHandshakeTimeoutReapsChild, TestP7HTTPTimeoutBusinessErrorAndReconnect, TestP7ConfigurationFailureAndDisabledNoSideEffects', '真实子进程和Streamable HTTP；initialize/list/call、超时回收、关闭/重连/旧会话fencing、单服务器故障降级与配置拒绝'),
    'McpConfirmationIntegrationTest': ('TestP7MCPAuditBudgetConfirmationAndReconnect, TestP7MCPChatHTTPAndPersistentConfirmation, TestP7SummaryAndDisabledConfirmation, TestP7NamesPolicyAndSchema', '真实MCP协议接入共同Agent预算/审计/幂等；重复申请去重，真实数据库12路HTTP确认只调用一次，旧callback重连后解析新客户端'),
    'McpDegradedStartupIntegrationTest': ('TestP7RealStdioLifecycleAndFailureIsolation, TestP7ConfigurationFailureAndDisabledNoSideEffects, TestP7MCPAndFiveModuleSearchPostgres', '外部失败不阻止注册表建立；外部MCP开关与个人只读服务/模型独立，非法配置仍拒绝'),
    'McpChatToolCallingIntegrationTest': ('TestP7MCPChatHTTPAndPersistentConfirmation, TestP7MCPAuditBudgetConfirmationAndReconnect', '真实HTTP对话流与MCP协议：工具实际出现在模型请求，AUTO结果与CONFIRM状态回到下一轮模型上下文；确认前零写入、真实库并发CAS/审计；预算耗尽不调用/不申请'),
    'SearchPatternsTest': ('TestP7GlobalSearchContractIsolationAndLimits, TestJavaP7NamingPolicySummarySearchPersonalAndImportOracle', 'strip/Locale.ROOT小写/反斜线及%_字面量，不把单独通配符当全库搜索'),
    'GlobalSearchIntegrationTest': ('TestP7MCPAndFiveModuleSearchPostgres, TestP7GlobalSearchContractIsolationAndLimits, TestJavaP7NamingPolicySummarySearchPersonalAndImportOracle', '五模块、标题优先/内容补充/会话去重、matchedField、字面量、分组排序/限额/无命中及单模块SQL故障隔离'),
    'DataExportIntegrationTest': ('TestP7StreamingExportImportRoundTripPostgres, TestP7ExportSnapshotAcrossBatchesAndImportConstraintRollback, TestP7EmptyExportPendingJobsAndSanitizedImport, TestP7ExportHEADAndNegotiation, TestP7BoundedStreamingArrayAndWriterFailures', '空数组/同源counts/附件头/无信封与embedding；200条批次含405消息/205块长文，单快照并发写入隔离及写失败传播'),
    'DataImportIntegrationTest': ('TestP7StreamingExportImportRoundTripPostgres, TestP7ImportAtomicityConcurrencyAndEmbeddingBoundary, TestP7ExportSnapshotAcrossBatchesAndImportConstraintRollback, TestP7EmptyExportPendingJobsAndSanitizedImport, TestP7ImportHTTPRejectsInvalidInputBeforeDatabase, TestP7ImportFormatAndSizeValidation, TestJavaP7NamingPolicySummarySearchPersonalAndImportOracle', '五模块/记忆替代链与状态、非空/待索引拒绝、版本/20MiB、脏行回落、嵌入无业务锁、失败整笔回滚及并发二次空库检查'),
    'KnowledgeGovernanceIntegrationTest': ('TestP7StreamingExportImportRoundTripPostgres', '本阶段只补backupRestoresKnowledgeSpaceAndEffectiveScope；来源按spaceName恢复、scope/version/有效期跟随事务，其他五声明保留P6证据'),
}


def test_entry(entry):
    path = entry.get('path', '')
    if path.endswith('/KnowledgeGovernanceIntegrationTest.java'):
        return entry.get('handler', {}).get('name') == BACKUP
    return any(prefix in path for prefix in ['/mcp/', '/common/search/', '/export/'])


def selected(entry):
    path = entry.get('path', '')
    if not path.endswith('.java'):
        return False
    return test_entry(entry) or Path(path).stem in CONTRIBUTORS


@lru_cache(maxsize=1)
def test_sources():
    return [(str(p.relative_to(ROOT)), p.read_text()) for p in (ROOT / 'backend-go').rglob('*_test.go')]


def evidence(entry):
    path = entry.get('path', '')
    if '/src/test/' in path and Path(path).stem in TESTS:
        names, note = TESTS[Path(path).stem]
        matches = []
        for name in names.split(', '):
            found = [path for path, text in test_sources() if 'func ' + name + '(' in text]
            if not found:
                raise ValueError('Go assertion not found: ' + name)
            matches += found
        return list(dict.fromkeys(matches + COMMON)), names + '。' + note
    if '/mcp/' in path:
        files = ['backend-go/internal/business/mcp/' + p for p in ['registry.go', 'naming.go', 'confirmation.go', 'personal.go', 'http.go', 'registry_test.go', 'oracle_test.go']]
        files += ['backend-go/internal/business/agent/mcp.go', 'backend-go/internal/business/agent/mcp_test.go', 'backend-go/internal/platform/config/mcp_runtime.go', 'backend-go/internal/app/foundation.go']
        note = 'T700–T702：真实stdio/HTTP与受控超时/关闭/重连/降级，默认CONFIRM及共同预算/审计/幂等，独立个人只读四工具/认证CSRF；原协议版本2025-06-18。'
    elif '/common/search/' in path or Path(path).stem.endswith('SearchContributor'):
        files = ['backend-go/internal/business/search/' + p for p in ['search.go', 'contributors.go', 'search_test.go']]
        files += ['backend-go/internal/app/foundation_p7_integration_test.go', 'backend-go/internal/business/mcp/oracle_test.go']
        note = 'T703：五模块真实SQL、LIKE字面量/标题优先/补充去重、独立事务故障隔离、分组排序/UTF16/限额，固定Java契约对照。'
    else:
        files = ['backend-go/internal/business/dataexport/export.go', 'backend-go/internal/business/dataimport/import.go', 'backend-go/internal/business/dataimport/binding.go', 'backend-go/internal/business/knowledge/import.go', 'backend-go/internal/business/exchange/http.go', 'backend-go/internal/app/foundation_p7_integration_test.go']
        if Path(path).stem in CONTRIBUTORS and Path(path).stem.endswith('ExportContributor'):
            module = Path(path).stem.removesuffix('ExportContributor').lower()
            files += [f'backend-go/internal/business/{module}/export.go']
        note = 'T704：同一REPEATABLE READ快照/200条流式导出，20MiB空库导入/格式绑定，嵌入在事务外，表锁二次校验/全部业务向量来源原子写入；引用/附件/摘要/提醒按原边界不恢复。'
    return files + COMMON, note


def update():
    inventory = json.loads((SPEC / 'inventory.json').read_text())
    path = SPEC / 'progress.json'
    progress = json.loads(path.read_text())
    changed = set()
    for entries in inventory.values():
        if not isinstance(entries, list):
            continue
        for entry in entries:
            if not isinstance(entry, dict) or 'id' not in entry or not selected(entry):
                continue
            files, note = evidence(entry)
            for file in files:
                if not (ROOT / file).is_file():
                    raise ValueError('Evidence not found: ' + file)
            item = progress['items'].setdefault(entry['id'], {'status': 'not_started', 'evidence': []})
            if item['status'] != 'verified':
                item['status'] = 'implemented'
            item['evidence'] = list(dict.fromkeys(item.get('evidence', []) + files))
            if entry.get('handler'):
                note = '原声明 ' + entry['handler']['name'] + '；' + note
            item['note'] = note
            changed.add(entry['id'])
    path.write_text(json.dumps(progress, ensure_ascii=False, indent=2) + '\n')
    print('P7 progress:', len(changed), 'items; verified/release gates preserved')


def mapping():
    inventory = json.loads((SPEC / 'inventory.json').read_text())
    tests = [entry for entry in inventory['java_test_cases'] if test_entry(entry)]
    classes = {Path(entry['path']).stem for entry in tests}
    if classes != set(TESTS):
        raise ValueError('P7 unmapped test classes: ' + str(classes ^ set(TESTS)))
    lines = ['# P7 原 Java 测试声明逐项映射', '', f'固定基线 `{inventory["baseline_commit"]}`；{len(classes)} 类、{len(tests)} 个原测试声明。', '',
             'Java 定向运行 77 个测试（包括治理类的全部6个）；本表只列 P7 的71个声明和从P6移交的1个备份恢复声明。Go 用例聚合多个原断言，原文件/方法仍逐条列出。状态只记 implemented，发布门禁留给P8。', '',
             '| 原文件与方法 | Go 断言 | 职责与边界 |', '| --- | --- | --- |']
    for entry in tests:
        files, note = evidence(entry)
        names, detail = TESTS[Path(entry['path']).stem]
        lines.append(f'| `{entry["path"]}:{entry["line"]}` `{entry["handler"]["name"]}` | {names} | {detail} |')
    lines += ['', '协议测试采用官方Go SDK启动真实stdio子进程/Streamable HTTP模拟器；不访问用户配置的外部MCP服务。SQL测试使用runner独立创建并销毁的TLS PostgreSQL/pgvector。Java纯契约由固定源码重新编译生成，不将mock SQL结果冒充数据库往返。', '']
    (SPEC / 'p7-test-mapping.md').write_text('\n'.join(lines))
    print('P7 mapping:', len(classes), 'classes,', len(tests), 'declarations')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true')
    args = parser.parse_args()
    mapping()
    if args.write:
        update()
