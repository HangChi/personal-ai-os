#!/usr/bin/env python3
"""Map individually reviewed remaining Java declarations to concrete tests, never release approval."""
import argparse
import ast
import copy
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
SPEC = ROOT / "specs/033-go-rewrite"
COMMON = ["specs/033-go-rewrite/p8-test-mapping.md", "specs/033-go-rewrite/p8-completion.md",
          "specs/033-go-rewrite/validation.md"]

TESTS = {
    "migration": "TestPostgresOriginalMigrationsIntegration",
    "health": "TestJavaHealthHTTPReportAndMethods",
    "healthOracle": "TestFoundationBusinessHealthRealServletOracle",
    "mcpOff": "TestP7ConfigurationFailureAndDisabledNoSideEffects",
    "personalOff": "TestP7PersonalProtocolReadOnlyCatalogAndIndependentSwitch",
    "errors": "TestJavaExceptionHandler36ResponseFixtures",
    "disconnect": "TestExceptionBoundaryHEADPanicAndDisconnect",
    "response": "TestResponseWireContract",
    "serialization": "TestSerializationFailureIsSafe",
    "page": "TestPaginationBoundaries",
    "sqlPage": "TestSQLResultIsNotPaginatedTwice",
    "auth": "TestHTTPLoginRotationLogoutAndCSRF",
    "authDisabled": "TestHTTPDisabledValidationAndLockout",
    "authOracle": "TestFoundationAuthRealServletOracle",
    "lockout": "TestServiceLockoutSerialContract",
    "actuator": "TestFoundationActuatorRealServletOracle",
    "dotenv": "TestParseDotEnvJavaContract",
    "duplicate": "TestDotEnvJavaWhitespaceAndOrderedDuplicate",
    "dotenvOff": "TestEnvironmentDefaultAndExplicitDisableNeverLoadLocalSecrets",
    "dotenvPriority": "TestEnvironmentNearestFileSystemPriorityAndEmptyOverride",
    "mcpBinding": "TestMCPDotEnvBindingOriginalScenario",
    "transactions": "TestPostgresP8ConfirmationTransactionOutcomes",
    "confirmationCAS": "TestPostgresCASAcrossConnections",
    "confirmationLifecycle": "TestPostgresP8CancelledUnknownAndFilteredConfirmations",
    "extract": "TestP8PlainExtractionFallbackUnsupportedAndBlank",
    "pdf": "TestPDFActualExtractionAndFailure",
    "texts": "TestJavaTextsNullStripRequiredAndUTF16Boundaries",
    "retention": "TestP8RetentionCutoffsAndUncertainTools",
    "monitor": "TestFoundationPostgresManagementAndListeners",
    "metrics": "TestFoundationPostgresActualHTTPAndRuntimeMetrics",
    "bucket": "TestPostgresBucketIntegration",
    "async": "TestConcurrencyHoldsStreamUntilHandlerCompletion",
    "permit": "TestConcurrencyCleanupOnPanicCancellationAndNormalRoutes",
    "release": "TestLocalBoundAndIdempotentRelease",
    "requestID": "TestRequestIDValidationAndJavaTrim",
    "requestChain": "TestConcurrentRequestIDsDoNotLeak",
    "worker": "TestFoundationHTTPWorkerIsolation",
    "routes": "TestFoundationHTTPRoutesAndErrorBoundary",
    "lists": "TestP8SessionAndDocumentListSearchContracts",
    "batchTags": "TestPostgresP8TaskBatchTagsPaginationAndStats",
    "taskAPI": "TestFoundationP2TaskRoutesAndIdempotency",
    "taskHTTP": "TestP8TaskHTTPNoKeyMissingAndTagValidation",
    "taskCRUD": "TestPostgresTaskCRUDAndSearch",
    "taskLifecycle": "TestPostgresP8TaskTagsAndLifecycle",
    "taskOrder": "TestPostgresP8TaskOrderingAndBulkDelete",
    "deferred": "TestPostgresP8TaskConfirmationDefersScheduleAndPriority",
    "taskTags": "TestTagsKeepOrderAndRejectLimits",
    "tagBounds": "TestP8TagEmptyUniqueAndExactBoundaries",
    "emptyTags": "TestPostgresP8EmptyTaskTagList",
    "taskQuery": "TestQueryAndTitleBoundaries",
    "crossHTTP": "TestFoundationP2ConfirmationAndCrossConnectionIdempotency",
    "conflict": "TestP8ConflictingEvidenceBypassesAnswerAndPersistsBothSources",
    "instant": "scripts/migration/test_go_cutover.py:CutoverStoragePrecisionTest.test_original_nanoseconds_and_database_rounding_agree",
    "millisecond": "scripts/migration/test_go_cutover.py:CutoverStoragePrecisionTest.test_one_millisecond_and_null_are_not_hidden",
}

# Every row names one original method; sharing a test requires a distinct assertion explanation.
CASES = {
    "PersonalOsApplicationTests": """
flywayMigrationAppliesVectorExtension|migration|原42迁移在空库与合成升级库执行，vector扩展存在。
pgvectorIsFunctional|migration|真实PostgreSQL中向量写入与距离运算；非mock连接。
healthEndpointReturnsUnifiedEnvelope|health+healthOracle|公开业务health统一信封及数据库/模型依赖组合；真实Servlet对照。
mcpRegistryAbsentWhenNotConfigured|mcpOff+personalOff|Go无Spring Bean；未配置不启动外部连接，个人只读开关独立，空目录无副作用承担原职责。
unknownPathReturnsUnifiedErrorEnvelope|errors+routes|未知路径404统一错误信封，默认与真实Servlet错误快照相同。
""",
    "ChatKnowledgeIntegrationTest": """
conflictingEvidenceBypassesAnswerModelAndPersistsBothSources|conflict|原样替换检索端口；SSE含rag/delta/done、不同说法/适用日期；回答零调用、两消息及两条有序引用落库。
""",
    "TokenUsageIntegrationTest": """
bulkRetentionPreservesExactBoundaryAndNewerRecords|retention|真实库严格早于UTC截止才删，精确边界/较新保留，二次零删除。
operationRetentionUsesBulkDeleteAndStrictBoundary|retention|审计严格截止；R-053有意保留未知TOOL IN_PROGRESS，与Java删除全部的安全差异显式记录。
""",
    "ApiResponseTest": """
okShouldWrapData|response|code=0、OK、原data及时间戳/四字段序列化。
okShouldAllowNullData|response|成功空值保留data:null，不能省略。
errorShouldCarryErrorCodeAndCustomMessage|response|失败状态与自定义文案、null data。
errorShouldFallbackToDefaultMessage|response+serialization|Failure使用错误码默认消息；明确检查50000/默认服务器内部错误，不额外增加空字符串回退语义。
""",
    "PageDtoTest": """
shouldSlicePages|page|五元素第一页/第二页/末页准确切片与总数。
shouldNotOverflowOnHugePage|page+sqlPage|最大int与Java int偏移边界不溢出，返回空页保留总数。
shouldReturnEmptyPageBeyondEnd|page|越界页为非nil空数组且总数不丢失。
shouldNormalizeIllegalArguments|page|负page归零、非正size归一，保留原辅助PageDto规则。
shouldHandleEmptySource|page|nil源序列化items:[]，不输出null。
""",
    "AuthDisabledIntegrationTest": """
shouldAllowEverythingWhenDisabled|authDisabled|认证/CSRF关闭后写请求直达下游。
meShouldReportDisabled|authDisabled|me返回authEnabled=false、username=null。
loginShouldNotFakeSessionWhenDisabled|authDisabled|关闭时login不创建Cookie或伪登录身份。
""",
    "AuthIntegrationTest": """
shouldRejectAnonymous|auth|任务/资源/MCP匿名请求401并保留null data和requestId。
healthShouldStayPublic|auth+healthOracle|公开health不创建会话，实际业务health真实Servlet差分。
reminderStreamRequiresAuthentication|auth|匿名GET /api/v1/reminders/events返回401。
healthDetailsShouldBeHiddenWhenAnonymous|actuator|真实Servlet与Go对照匿名健康响应，详情可见性依认证状态。
meShouldReportAnonymous|auth|me匿名成功、authEnabled=true、username=null。
shouldRejectWrongPassword|authDisabled+authOracle|错误密码统一401不可枚举；实际认证协议Java对照。
loginThenLogout|auth|登录旋转会话、旧Cookie失效、注销删除Cookie和服务端记录。
writeEndpointsShouldWorkAfterLogin|auth|登录后有效新CSRF写成功，旧token/缺token拒绝。
""",
    "AuthLockoutIntegrationTest": """
locksAfterConsecutiveFailures|authDisabled+lockout|连续失败达到阈值锁定，正确凭据在锁期内仍429。
unlocksAfterLockoutExpires|lockout|可控时钟越过锁期，正确登录恢复并重置失败计数。
""",
    "DotEnvParserTest": """
bindsMcpEnvironmentVariablesFromDotEnv|mcpBinding|原命名环境键映射MCP开关/列表/命令，临时合成文件无现有秘密。
parsesSimplePairs|dotenv|注释/空行/首个等号及键值strip。
keepsEqualsAndColonInValue|dotenv|值中等号与冒号完整保留。
doesNotStripInlineComment|dotenv|行内井号是值的一部分。
unquotesOnlyPairedQuotes|dotenv|仅剥离配对首尾单/双引号，错配保留。
stripsExportPrefix|dotenv|export前缀行为与Java表格样例一致。
skipsMalformedLines|dotenv|无等号/空键行忽略。
laterDuplicateWins|duplicate|重复键后值覆盖并维持Java顺序。
keepsEmptyValue|dotenv|空值保留，非缺失键。
testEnvironmentMustNotReadLocalSecrets|dotenvOff|测试profile和显式禁用不读取本地文件。
localStartupKeepsFallbackAndEnvironmentPriority|dotenvPriority|最近临时文件回落，显式环境及空覆盖优先。
""",
    "ConfirmationTransactionTest": """
businessExceptionDoesNotLeaveConfirmedStatus|transactions|business子例：业务失败独立提交FAILED。
runtimeExceptionIsRecordedAsFailed|transactions|runtime子例：普通错误独立提交FAILED。
resourceNotFoundIsCancelledNotFailed|transactions|missing子例：目标不存在为CANCELLED且零影响。
successRecordsConfirmed|transactions|success子例：CONFIRMED、影响3条、已执行摘要。
failedConfirmationIsStillSingleUse|transactions|失败后原token重放拒绝，处理器仅一次。
claimCommitsBeforeHandlerRuns|transactions|处理器内由第二连接池读到已提交CONFIRMED和resolved_at。
failureDoesNotRollbackClaim|transactions|处理器错误后第二池观察终态及解决时间，非业务事务回滚。
expiredMarkerSurvivesTheException|transactions|真实SQL设置过去期限，异常返回后EXPIRED/时间与过滤列表仍存在。
""",
    "PlainTextExtractorTest": """
readsPlainText|extract|text/plain中文原文提取。
fallsBackToExtension|extract|octet-stream的md、空媒体json按扩展名提取。
readsPdfTextLayer|pdf|实际Poppler从带文本层PDF提取，非手填字符串。
unsupportedTypeYieldsEmpty|extract|zip/image不作为普通文本提取。
truncatesOverlongText|extract|附件上下文恰好8000 UTF-16且带截断提示。
blankOrBrokenYieldsEmpty|extract|空字节、空白和坏PDF均无文本。
""",
    "ErrorEnvelopeIntegrationTest": """
malformedJsonIsClientError|errors|真实Java malformed JSON错误快照对照400/40000及安全消息。
methodNotAllowedIsClientError|errors|405/40500、Allow与WARN分类对照。
hugePageNumberReturnsEmptyPage|batchTags|真实任务溢出页为空，总数不变。
hugePageNumberOnSessionSearchReturnsEmptyPage|lists|真实会话2147483647页为空且total=25。
unacceptableResponseTypeIsNotServerError|errors|不接受响应媒体为406而非500，实际MVC fixture对照。
""",
    "GlobalExceptionHandlerLogTest": """
methodNotSupportedLogsRequestUri|errors|405日志包含URI和requestId，WARN级别。
methodNotSupportedLogsPathWithoutQueryString|errors|日志仅路径不包含查询字符串/秘密。
clientDisconnectIsNotLoggedAsError|disconnect|断连分类不输出ERROR，已提交流不追加JSON。
""",
    "MonitoringIntegrationTest": """
internalScrapeExportsMetricsButDoesNotExposeApplicationOrEnvironment|monitor+metrics|独立管理监听真实health/指标、业务/环境路径拒绝、应用端不暴露指标；真实HTTP计数与Go运行时样本。
""",
    "TextsTest": """
trimToNullShouldCollapseBlankToNull|texts|nil/空串/Java空白归nil，NBSP等不误删。
trimToNullShouldStripEndsOnly|texts|只裁首尾，内部空白保留。
requireNotBlankShouldRejectBlank|texts|空值抛ValidationFailed且精确标签错误文案。
requireNotBlankShouldReturnStripped|texts|非空返回Java strip后的值。
requireNotBlankShouldAcceptExactLimit|texts|裁剪后200字符/100非BMP字符等于200 UTF-16通过。
requireNotBlankShouldRejectOverLimit|texts|201单元/101非BMP拒绝并给出精确长度文案。
""",
    "DatabaseCoordinationIntegrationTest": """
rateBudgetIsSharedAndRefillsUsingDatabaseClock|bucket|真实双池共享容量，Java shared capacity and database clock refill子例用数据库时钟复充。
""",
    "ModelConcurrencyFilterTest": """
asyncPermitIsHeldUntilCompletionAndReleasedOnlyOnce|async+release|真实流处理完成前持有permit，Gate并发/重复release不超额。
failuresAndSyncCompletionReleasePermit|permit|panic/取消/同步完成均释放，普通路径不获取模型许可。
""",
    "RequestIdFilterTest": """
passesThroughSafeInboundId|requestID+requestChain|安全字符与Java trim保留入站ID，context/响应头一致且并发不串线。
rejectsLogInjection|requestID|换行/非法字符拒绝并重新生成。
rejectsOverlongInboundId|requestID|64字符边界允许，65字符拒绝并生成12位hex。
generatesWhenHeaderMissing|requestID|缺失ID生成12位hex；context传播另由并发链测试覆盖。
""",
    "WorkerIsolationFilterTest": """
workerRejectsBusinessRequestsBeforeCallingDownstream|worker|三个Worker在认证/限流/业务依赖前空404且无Cookie。
workerHealthAndApiRequestsRemainAccessibleOnTheirRespectiveRoles|worker+routes+monitor|业务监听Worker隔离，health/指标走原独立管理监听；API/combined业务链仍可达。
""",
    "ListSearchIntegrationTest": """
taskTagsAreBatchLoaded|batchTags|真实驱动计数：20条任务页恰好三查询、25ID批取两查询，顺序不变。
shouldPageSessions|lists|25会话20+5分页、total与无重复遗漏。
shouldSearchSessions|lists|会话标题唯一命中、缺失词零命中。
shouldClampSessionParams|lists|size1000→100、0→20、负页→0、越界与int最大页空。
shouldKeepMessageCountsAcrossPages|lists|活跃会话第一页消息数2，其余会话参与分页。
shouldKeepFullListEndpoint|lists|原完整会话端点仍含本次25条，不被默认分页截断。
shouldPageDocuments|lists|25真实文档/向量20+5页、total与不重漏。
shouldSearchDocuments|lists|文档标题与文件名分别唯一命中、缺失词零命中。
shouldClampDocumentParams|lists|文档size/负页/溢出与会话同边界。
shouldKeepFullDocumentEndpoint|lists|原完整文档端点仍含本次25条。
""",
    "InstantsTest": """
nanosecondValueEqualsItsMicrosecondRoundedForm|instant|原职责是测试辅助比较；迁移Python比较器按PG微秒舍入，含跨秒与五位小数，不新增生产API。
oneMillisecondApartIsDifferent|millisecond|一毫秒差异仍检测，未引入宽容时间窗。
nullHandling|millisecond|null仅等于null，日期与null有差异。
""",
    "TaskApiIntegrationTest": """
createTaskInsertsRow|taskHTTP|真实HTTP201后的ID由仓储读回，标题持久。
blankTitleIsRejected|taskAPI|空白标题HTTP400。
listSupportsFiltering|taskAPI+taskHTTP|真实HTTP tag过滤及status/priority/keyword组合仅命中目标ID。
invalidEnumQueryParamIsClientError|taskAPI|非法status参数400，不能进入服务查询。
unknownTaskReturns404|taskHTTP|不存在UUID真实HTTP404/40004。
patchOnlyUpdatesPresentFields|taskLifecycle|仅改title保留description/due/tags，PATCH省略与清空分离。
completeAndReopenMaintainInvariant|taskLifecycle|完成设置时间/previousStatus，重开清时间和previousStatus。
deleteIsImmediateForHumans|taskHTTP|DELETE200 deleted=true，随即GET404，无确认等待。
sameIdempotencyKeyCreatesOnce|taskAPI+crossHTTP|同key返回同ID，双API24路并发最终仅一行。
sameKeyWithDifferentBodyConflicts|taskAPI|同key不同digest为409/40001。
invalidIdempotencyKeyIsRejected|taskAPI|过短幂等key在写前400。
withoutIdempotencyKeyNoDeduplication|taskHTTP|两次同内容无key创建不同ID，搜索确有两行。
deleteIsIdempotentWithKey|taskAPI|相同key重复DELETE均200。
bulkDeleteRespectsLimit|taskOrder|51ID拒绝，无批量写入。
bulkDeleteReturnsAffectedCount|taskOrder|重复ID/缺失ID不虚增，实际删除两行。
queryPagingParamsAreNormalized|taskQuery+batchTags|负页/size上限/空tag归一及真实SQL溢出页。
listIsPaginatedWithTotal|batchTags|25真实任务第一页20条、总数25。
searchOrdersUnfinishedFirstThenByDueDate|taskOrder|未完成优先、截止升序、null在后、完成任务最后。
paginationCoversEveryTaskExactlyOnce|batchTags|每页4条遍历25任务、无重漏且保持排序。
missingTaskRaisesNotFound|taskLifecycle|删除后service.Get返回ErrNotFound。
statsAreAggregatedNotPaginated|batchTags|全局待办统计不受页大小影响，完成后减一。
reopenRestoresDoingStatus|taskLifecycle|DOING完成后重开恢复DOING。
reopenTodoStaysTodo|taskLifecycle|TODO完成后重开保持TODO。
repeatedCompleteDoesNotOverwritePreviousStatus|taskLifecycle|连续两次完成仍保留首次previousStatus。
previousStatusIsRecomputedAfterReopen|taskLifecycle|同任务重开后改DOING，新一轮完成记录新previousStatus。
""",
    "TaskTagsIntegrationTest": """
shouldKeepTagOrderOnCreate|taskLifecycle|真实create/get标签顺序一致。
shouldReplaceTags|taskLifecycle|PATCH替换按新顺序持久。
shouldClearTagsWithEmptyArray|taskLifecycle|显式空数组清空，读回非nil空数组。
shouldKeepTagsWhenOmitted|taskLifecycle|仅改标题不改标签。
shouldRejectTooManyTags|taskHTTP|六个唯一标签HTTP400，无部分任务写入。
shouldRejectTooLongTag|taskHTTP|21UTF-16标签HTTP400，无部分任务写入。
shouldFilterByTag|taskLifecycle|按本次唯一标签精确筛到一条。
shouldMatchTagExactly|taskLifecycle|截短标签不能匹配，不用LIKE模糊过滤。
shouldListDistinctTags|taskLifecycle|去重列表本次标签仅一次。
shouldReturnEmptyTagList|emptyTags|空独立真实数据库ListTags返回非nil空列表。
shouldCascadeTagsOnDelete|taskLifecycle|删除后直接SQL确认task_tag零行。
""",
    "TaskTagsTest": """
shouldKeepOrder|taskTags|工作/学习标签保持输入顺序。
shouldNormalizeDuplicatesAndBlanks|taskTags|首尾strip、空白忽略、首次重复保留。
shouldReturnEmptyForNull|tagBounds|nil/空/全空白均非nil空列表。
shouldRejectTooLongTag|taskTags|11非BMP字符超过20UTF-16拒绝。
shouldRejectTooManyTags|taskTags|六唯一标签拒绝。
shouldAllowBoundaryValues|tagBounds|恰好20UTF-16、五标签、10非BMP均通过。
shouldCountUniqueTags|tagBounds|先去重再限量，六输入三唯一通过。
""",
    "ConfirmationIntegrationTest": """
wrongTokenIsRejected|transactions|错误token不消费占位、不调用处理器；正确token随后仍可执行。
confirmationIsSingleUse|transactions+crossHTTP|终态token重放拒绝、HTTP真实链只一次写入。
expiredConfirmationIsRejected|transactions|过去期限拒绝并提交EXPIRED。
cancelledConfirmationCannotBeConfirmed|confirmationLifecycle|真实取消后确认拒绝、处理器零调用。
missingTargetIsHandledGracefully|transactions|目标已不存在转CANCELLED、Executed=false/count=0，无错误副作用。
scheduleAndPriorityApplyOnlyAfterConfirmation|deferred|真实任务确认前原截止/优先级不变，两确认后准确写入。
listFiltersByConversationAndStatus|confirmationLifecycle|同会话Pending/Cancelled及默认Pending精确筛选，排除另一会话。
unknownConfirmationRaisesNotFound|confirmationLifecycle|不存在UUID为ResourceNotFound、处理器零调用。
onlyOneConcurrentConfirmWins|confirmationCAS|双池24并发只一成功、处理器仅一次，终态从另一池可见。
""",
}


def mappings():
    result = {}
    for owner, rows in CASES.items():
        for row in rows.strip().splitlines():
            name, aliases, note = row.split("|", 2)
            key = owner + "." + name
            if key in result:
                raise ValueError("Duplicate Java declaration: " + key)
            result[key] = ([TESTS[alias] for alias in aliases.split("+")], note)
    return result


def resolve_tests():
    declarations = {}
    for path in (ROOT / "backend-go").rglob("*_test.go"):
        for name in re.findall(r"^func (Test\w+)\(", path.read_text(), re.M):
            declarations.setdefault(name, []).append(str(path.relative_to(ROOT)))
    resolved = {}
    for test in TESTS.values():
        if ":" in test:
            path, symbol = test.split(":", 1)
            owner, method = symbol.split(".")
            source = ast.parse((ROOT / path).read_text(), filename=path)
            classes = [node for node in source.body if isinstance(node, ast.ClassDef) and node.name == owner]
            if len(classes) != 1 or not any(isinstance(node, ast.FunctionDef) and node.name == method for node in classes[0].body):
                raise ValueError("Missing Python evidence: " + test)
            resolved[test] = path
        else:
            matches = declarations.get(test, [])
            if len(matches) != 1:
                raise ValueError("Expected unique Go test: " + test + ": " + str(matches))
            resolved[test] = matches[0]
    return resolved


def apply(inventory, before):
    import go_inventory
    import go_scope_changes
    _, amended = go_scope_changes.load(inventory, ROOT,
            lambda revision, path: go_inventory.git("show", revision + ":" + path))
    expected = mappings()
    resolved = resolve_tests()
    result = copy.deepcopy(before)
    entries = {}
    for entry in inventory["java_test_cases"]:
        key = Path(entry["path"]).stem + "." + entry["handler"]["name"]
        if key in expected:
            if key in entries:
                raise ValueError("Ambiguous original declaration: " + key)
            entries[key] = entry
    if set(entries) != set(expected) or len(expected) != 144:
        raise ValueError("Fixed P8 declaration scope changed; review required")
    for key, entry in entries.items():
        tests, note = expected[key]
        old = result["items"].get(entry["id"], {})
        result["items"][entry["id"]] = dict(old,
            status="verified" if old.get("status") == "verified" else "implemented",
            evidence=list(dict.fromkeys(old.get("evidence", []) + [resolved[test] for test in tests] + COMMON)),
            note="P8逐声明：" + key + " → " + ", ".join(tests) + "；" + note)
    # File-level evidence is complete only after all its original declarations have a disposition.
    selected_paths = {entry["path"] for entry in entries.values()}
    for entry in inventory["files"]:
        if entry["path"] not in selected_paths:
            continue
        # This mapping covers original declarations only. Candidate file status
        # belongs to the scope ledger and cannot inherit historical completion.
        if entry["path"] in amended:
            continue
        cases = [case for case in inventory["java_test_cases"] if case["path"] == entry["path"]]
        if any(result["items"].get(case["id"], {}).get("status") not in {"implemented", "verified"} for case in cases):
            raise ValueError("Unmapped declarations remain in " + entry["path"])
        old = result["items"].get(entry["id"], {})
        evidence = list(dict.fromkeys(old.get("evidence", []) + [path for case in cases
            for path in result["items"][case["id"]].get("evidence", [])]))
        result["items"][entry["id"]] = dict(old,
            status="verified" if old.get("status") == "verified" else "implemented",
            evidence=evidence, note="P8逐声明审读；本文件全部原声明已有具体测试映射，含前阶段证据。发布核验另行处理。")
    lines = ["# P8 剩余原Java测试逐声明映射", "",
        "固定基线cb5d694d62fa93742b5789b97273e76d931ce335；144个声明逐一审读，复用前阶段测试或补齐缺口。",
        "仅记implemented，不升级verified或发布门禁；测试存在性检查不等于执行证明，实测命令/失败历史见p8-completion.md及validation.md。",
        "原Instants承担迁移测试辅助职责；Spring容器缺席由Go禁用状态无连接/无目录副作用对应。R-053审计保留差异显式列明。", "",
        "| 原Java声明 | 具体测试及证据文件 | 对应断言与边界 |", "| --- | --- | --- |"]
    for key, entry in sorted(entries.items()):
        tests, note = expected[key]
        evidence = "<br>".join("`" + resolved[test] + "` `" + test.split(":")[-1] + "`" for test in tests)
        lines.append("| `" + entry["path"] + ":" + str(entry["handler"]["line"]) + "` `" + key + "` | " + evidence + " | " + note + " |")
    return result, "\n".join(lines) + "\n"


def update(write=False):
    inventory = json.loads((SPEC / "inventory.json").read_text())
    before = json.loads((SPEC / "progress.json").read_text())
    after, markdown = apply(inventory, before)
    output = json.dumps(after, ensure_ascii=False, indent=2) + "\n"
    target = SPEC / "p8-test-mapping.md"
    if write:
        (SPEC / "progress.json").write_text(output)
        target.write_text(markdown)
    elif (SPEC / "progress.json").read_text() != output or not target.is_file() or target.read_text() != markdown:
        raise ValueError("P8 test mapping differs; review then run --write")
    print("P8: 144 explicit declarations mapped; prior verified states and all release gates preserved")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    update(parser.parse_args().write)
