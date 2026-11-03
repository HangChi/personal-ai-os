# 自动提取的接口、工具与运行钩子

由清单脚本生成；参数注解与处理器签名保存在 `inventory.json`。

## HTTP 路由

方法 | 路径 | Java 处理器
--- | --- | ---
GET | `/api/v1/agent/tool-invocations` | [ToolInvocationController.list](../../backend/src/main/java/com/personalos/agent/audit/ToolInvocationController.java#L44)
GET | `/api/v1/conversations/{conversationId}/plan` | [AgentPlanController.latest](../../backend/src/main/java/com/personalos/agent/plan/controller/AgentPlanController.java#L32)
POST | `/api/v1/calendar/events` | [CalendarController.create](../../backend/src/main/java/com/personalos/calendar/controller/CalendarController.java#L50)
GET | `/api/v1/calendar/events` | [CalendarController.search](../../backend/src/main/java/com/personalos/calendar/controller/CalendarController.java#L66)
GET | `/api/v1/calendar/events/{id}` | [CalendarController.get](../../backend/src/main/java/com/personalos/calendar/controller/CalendarController.java#L79)
PATCH | `/api/v1/calendar/events/{id}` | [CalendarController.update](../../backend/src/main/java/com/personalos/calendar/controller/CalendarController.java#L87)
DELETE | `/api/v1/calendar/events/{id}` | [CalendarController.delete](../../backend/src/main/java/com/personalos/calendar/controller/CalendarController.java#L96)
GET | `/api/v1/reminders/events` | [ReminderController.events](../../backend/src/main/java/com/personalos/calendar/controller/ReminderController.java#L45)
GET | `/api/v1/reminders` | [ReminderController.list](../../backend/src/main/java/com/personalos/calendar/controller/ReminderController.java#L58)
POST | `/api/v1/reminders/{id}/read` | [ReminderController.markRead](../../backend/src/main/java/com/personalos/calendar/controller/ReminderController.java#L69)
POST | `/api/v1/reminders/scan` | [ReminderController.scan](../../backend/src/main/java/com/personalos/calendar/controller/ReminderController.java#L81)
GET | `/api/v1/reminders/push` | [WebPushController.status](../../backend/src/main/java/com/personalos/calendar/push/WebPushController.java#L21)
POST | `/api/v1/reminders/push/subscriptions` | [WebPushController.subscribe](../../backend/src/main/java/com/personalos/calendar/push/WebPushController.java#L24)
DELETE | `/api/v1/reminders/push/subscriptions` | [WebPushController.unsubscribe](../../backend/src/main/java/com/personalos/calendar/push/WebPushController.java#L29)
GET | `/api/v1/chat/transcribe/status` | [AudioTranscriptionController.status](../../backend/src/main/java/com/personalos/chat/controller/AudioTranscriptionController.java#L50)
POST | `/api/v1/chat/transcribe` | [AudioTranscriptionController.transcribe](../../backend/src/main/java/com/personalos/chat/controller/AudioTranscriptionController.java#L65)
POST | `/api/v1/chat/uploads` | [ChatAttachmentController.upload](../../backend/src/main/java/com/personalos/chat/controller/ChatAttachmentController.java#L43)
GET | `/api/v1/chat/uploads/{id}/content` | [ChatAttachmentController.content](../../backend/src/main/java/com/personalos/chat/controller/ChatAttachmentController.java#L54)
GET | `/api/v1/chat/sessions/{conversationId}/runs/{id}/events` | [ChatRunController.events](../../backend/src/main/java/com/personalos/chat/controller/ChatRunController.java#L19)
POST | `/api/v1/chat/sessions/{conversationId}/runs` | [ChatRunController.start](../../backend/src/main/java/com/personalos/chat/controller/ChatRunController.java#L26)
GET | `/api/v1/chat/sessions/{conversationId}/runs/latest` | [ChatRunController.latest](../../backend/src/main/java/com/personalos/chat/controller/ChatRunController.java#L32)
GET | `/api/v1/chat/sessions/{conversationId}/runs/{id}` | [ChatRunController.get](../../backend/src/main/java/com/personalos/chat/controller/ChatRunController.java#L36)
POST | `/api/v1/chat/sessions/{conversationId}/runs/{id}/resume` | [ChatRunController.resume](../../backend/src/main/java/com/personalos/chat/controller/ChatRunController.java#L41)
POST | `/api/v1/chat/sessions/{conversationId}/runs/{id}/stop` | [ChatRunController.stop](../../backend/src/main/java/com/personalos/chat/controller/ChatRunController.java#L45)
POST | `/api/v1/chat/sessions/{conversationId}/runs/{id}/confirmations/finish` | [ChatRunController.finishConfirmations](../../backend/src/main/java/com/personalos/chat/controller/ChatRunController.java#L50)
POST | `/api/v1/chat/sessions/{conversationId}/runs/{id}/questions/{questionId}/answer` | [ChatRunController.answer](../../backend/src/main/java/com/personalos/chat/controller/ChatRunController.java#L57)
POST | `/api/v1/chat/sessions/{conversationId}/runs/{id}/followups` | [ChatRunController.followup](../../backend/src/main/java/com/personalos/chat/controller/ChatRunController.java#L62)
DELETE | `/api/v1/chat/sessions/{conversationId}/runs/{id}/followups/{inputId}` | [ChatRunController.cancelFollowup](../../backend/src/main/java/com/personalos/chat/controller/ChatRunController.java#L67)
PATCH | `/api/v1/chat/sessions/{conversationId}/runs/{id}/followups/{inputId}` | [ChatRunController.editFollowup](../../backend/src/main/java/com/personalos/chat/controller/ChatRunController.java#L74)
POST | `/api/v1/chat/sessions/{conversationId}/runs/{id}/followups/{inputId}/steer` | [ChatRunController.steerFollowup](../../backend/src/main/java/com/personalos/chat/controller/ChatRunController.java#L79)
POST | `/api/v1/chat/sessions` | [ChatSessionController.createSession](../../backend/src/main/java/com/personalos/chat/controller/ChatSessionController.java#L79)
GET | `/api/v1/chat/sessions` | [ChatSessionController.listSessions](../../backend/src/main/java/com/personalos/chat/controller/ChatSessionController.java#L89)
GET | `/api/v1/chat/sessions/search` | [ChatSessionController.searchSessions](../../backend/src/main/java/com/personalos/chat/controller/ChatSessionController.java#L103)
GET | `/api/v1/chat/sessions/{conversationId}/messages` | [ChatSessionController.listMessages](../../backend/src/main/java/com/personalos/chat/controller/ChatSessionController.java#L118)
POST | `/api/v1/chat/sessions/{conversationId}/stream` | [ChatSessionController.stream](../../backend/src/main/java/com/personalos/chat/controller/ChatSessionController.java#L139)
GET | `/api/v1/chat/sessions/{conversationId}/context` | [ChatSessionController.contextUsage](../../backend/src/main/java/com/personalos/chat/controller/ChatSessionController.java#L159)
GET | `/api/v1/chat/context` | [ChatSessionController.defaultContextUsage](../../backend/src/main/java/com/personalos/chat/controller/ChatSessionController.java#L165)
PUT | `/api/v1/chat/context-budget` | [ChatSessionController.setContextBudget](../../backend/src/main/java/com/personalos/chat/controller/ChatSessionController.java#L192)
PATCH | `/api/v1/chat/sessions/{conversationId}` | [ChatSessionController.renameSession](../../backend/src/main/java/com/personalos/chat/controller/ChatSessionController.java#L222)
PUT | `/api/v1/chat/sessions/{conversationId}/model` | [ChatSessionController.setModel](../../backend/src/main/java/com/personalos/chat/controller/ChatSessionController.java#L240)
DELETE | `/api/v1/chat/sessions/{conversationId}` | [ChatSessionController.deleteSession](../../backend/src/main/java/com/personalos/chat/controller/ChatSessionController.java#L251)
POST | `/api/v1/chat/sessions/{conversationId}/rewind` | [ChatSessionController.rewind](../../backend/src/main/java/com/personalos/chat/controller/ChatSessionController.java#L277)
GET | `/api/v1/chat/sessions/{conversationId}/export` | [ChatSessionController.exportMarkdown](../../backend/src/main/java/com/personalos/chat/controller/ChatSessionController.java#L295)
GET | `/api/v1/usage` | [UsageController.stats](../../backend/src/main/java/com/personalos/chat/controller/UsageController.java#L39)
GET | `/api/v1/settings/model` | [ModelConfigController.get](../../backend/src/main/java/com/personalos/common/ai/config/ModelConfigController.java#L54)
PUT | `/api/v1/settings/model` | [ModelConfigController.save](../../backend/src/main/java/com/personalos/common/ai/config/ModelConfigController.java#L73)
POST | `/api/v1/settings/model/test` | [ModelConfigController.test](../../backend/src/main/java/com/personalos/common/ai/config/ModelConfigController.java#L95)
GET | `/api/v1/settings/model/profiles` | [ModelProfileController.list](../../backend/src/main/java/com/personalos/common/ai/config/ModelProfileController.java#L88)
POST | `/api/v1/settings/model/profiles` | [ModelProfileController.create](../../backend/src/main/java/com/personalos/common/ai/config/ModelProfileController.java#L112)
PUT | `/api/v1/settings/model/profiles/{id}` | [ModelProfileController.update](../../backend/src/main/java/com/personalos/common/ai/config/ModelProfileController.java#L124)
POST | `/api/v1/settings/model/profiles/{id}/activate` | [ModelProfileController.activate](../../backend/src/main/java/com/personalos/common/ai/config/ModelProfileController.java#L142)
DELETE | `/api/v1/settings/model/profiles/{id}` | [ModelProfileController.delete](../../backend/src/main/java/com/personalos/common/ai/config/ModelProfileController.java#L158)
PUT | `/api/v1/settings/model/profiles/order` | [ModelProfileController.reorder](../../backend/src/main/java/com/personalos/common/ai/config/ModelProfileController.java#L169)
POST | `/api/v1/settings/model/profiles/test` | [ModelProfileController.test](../../backend/src/main/java/com/personalos/common/ai/config/ModelProfileController.java#L181)
POST | `/api/v1/settings/model/profiles/probe-models` | [ModelProfileController.probeModels](../../backend/src/main/java/com/personalos/common/ai/config/ModelProfileController.java#L204)
POST | `/api/v1/settings/model/profiles/probe-window` | [ModelProfileController.probeWindow](../../backend/src/main/java/com/personalos/common/ai/config/ModelProfileController.java#L224)
POST | `/api/v1/auth/login` | [AuthController.login](../../backend/src/main/java/com/personalos/common/auth/AuthController.java#L54)
POST | `/api/v1/auth/logout` | [AuthController.logout](../../backend/src/main/java/com/personalos/common/auth/AuthController.java#L86)
GET | `/api/v1/auth/me` | [AuthController.me](../../backend/src/main/java/com/personalos/common/auth/AuthController.java#L102)
GET | `/api/v1/auth/csrf` | [CsrfController.token](../../backend/src/main/java/com/personalos/common/auth/CsrfController.java#L13)
GET | `/api/v1/agent/confirmations` | [ConfirmationController.list](../../backend/src/main/java/com/personalos/common/confirmation/ConfirmationController.java#L34)
POST | `/api/v1/agent/confirmations/{id}/confirm` | [ConfirmationController.confirm](../../backend/src/main/java/com/personalos/common/confirmation/ConfirmationController.java#L44)
POST | `/api/v1/agent/confirmations/{id}/cancel` | [ConfirmationController.cancel](../../backend/src/main/java/com/personalos/common/confirmation/ConfirmationController.java#L53)
ANY | `/${server.error.path:/error}` | [ApiErrorController.handleError](../../backend/src/main/java/com/personalos/common/exception/ApiErrorController.java#L30)
GET | `/api/v1/health` | [HealthController.health](../../backend/src/main/java/com/personalos/common/health/HealthController.java#L26)
GET | `/api/v1/search` | [SearchController.search](../../backend/src/main/java/com/personalos/common/search/SearchController.java#L38)
GET | `/api/v1/export` | [DataExportController.export](../../backend/src/main/java/com/personalos/export/DataExportController.java#L44)
POST | `/api/v1/import` | [DataImportController.importData](../../backend/src/main/java/com/personalos/export/DataImportController.java#L45)
POST | `/api/v1/knowledge/documents` | [KnowledgeController.upload](../../backend/src/main/java/com/personalos/knowledge/controller/KnowledgeController.java#L60)
GET | `/api/v1/knowledge/documents` | [KnowledgeController.listDocuments](../../backend/src/main/java/com/personalos/knowledge/controller/KnowledgeController.java#L78)
PUT | `/api/v1/knowledge/documents/{id}` | [KnowledgeController.update](../../backend/src/main/java/com/personalos/knowledge/controller/KnowledgeController.java#L92)
GET | `/api/v1/knowledge/documents/search` | [KnowledgeController.searchDocuments](../../backend/src/main/java/com/personalos/knowledge/controller/KnowledgeController.java#L112)
DELETE | `/api/v1/knowledge/documents/{id}` | [KnowledgeController.deleteDocument](../../backend/src/main/java/com/personalos/knowledge/controller/KnowledgeController.java#L123)
POST | `/api/v1/knowledge/search` | [KnowledgeController.search](../../backend/src/main/java/com/personalos/knowledge/controller/KnowledgeController.java#L132)
POST | `/api/v1/knowledge/ask` | [KnowledgeController.ask](../../backend/src/main/java/com/personalos/knowledge/controller/KnowledgeController.java#L141)
GET | `/api/v1/knowledge/governance/spaces` | [GovernanceController.spaces](../../backend/src/main/java/com/personalos/knowledge/governance/GovernanceController.java#L9)
POST | `/api/v1/knowledge/governance/spaces` | [GovernanceController.create](../../backend/src/main/java/com/personalos/knowledge/governance/GovernanceController.java#L11)
GET | `/api/v1/knowledge/governance/sources` | [GovernanceController.sources](../../backend/src/main/java/com/personalos/knowledge/governance/GovernanceController.java#L12)
PUT | `/api/v1/knowledge/governance/sources/{id}` | [GovernanceController.update](../../backend/src/main/java/com/personalos/knowledge/governance/GovernanceController.java#L13)
POST | `/api/v1/knowledge/governance/sources/{id}/review` | [GovernanceController.review](../../backend/src/main/java/com/personalos/knowledge/governance/GovernanceController.java#L14)
GET | `/api/v1/knowledge/governance/conflicts` | [GovernanceController.conflicts](../../backend/src/main/java/com/personalos/knowledge/governance/GovernanceController.java#L15)
POST | `/api/v1/knowledge/governance/conflicts/{id}/resolve` | [GovernanceController.resolve](../../backend/src/main/java/com/personalos/knowledge/governance/GovernanceController.java#L16)
POST | `/api/v1/knowledge/jobs` | [KnowledgeJobController.submit](../../backend/src/main/java/com/personalos/knowledge/jobs/KnowledgeJobController.java#L17)
GET | `/api/v1/knowledge/jobs` | [KnowledgeJobController.list](../../backend/src/main/java/com/personalos/knowledge/jobs/KnowledgeJobController.java#L25)
GET | `/api/v1/knowledge/jobs/{id}` | [KnowledgeJobController.get](../../backend/src/main/java/com/personalos/knowledge/jobs/KnowledgeJobController.java#L26)
GET | `/api/v1/knowledge/search-index` | [SearchAdminController.status](../../backend/src/main/java/com/personalos/knowledge/search/SearchAdminController.java#L12)
POST | `/api/v1/knowledge/search-index/reindex` | [SearchAdminController.reindex](../../backend/src/main/java/com/personalos/knowledge/search/SearchAdminController.java#L13)
GET | `/api/v1/mcp/servers` | [McpStatusController.servers](../../backend/src/main/java/com/personalos/mcp/status/McpStatusController.java#L32)
GET | `/api/v1/mcp/server` | [McpStatusController.server](../../backend/src/main/java/com/personalos/mcp/status/McpStatusController.java#L38)
POST | `/api/v1/mcp/servers/{name}/reconnect` | [McpStatusController.reconnect](../../backend/src/main/java/com/personalos/mcp/status/McpStatusController.java#L48)
GET | `/api/v1/memories` | [MemoryController.list](../../backend/src/main/java/com/personalos/memory/controller/MemoryController.java#L41)
POST | `/api/v1/memories` | [MemoryController.create](../../backend/src/main/java/com/personalos/memory/controller/MemoryController.java#L49)
PATCH | `/api/v1/memories/{id}` | [MemoryController.update](../../backend/src/main/java/com/personalos/memory/controller/MemoryController.java#L55)
PATCH | `/api/v1/memories/{id}/pin` | [MemoryController.pin](../../backend/src/main/java/com/personalos/memory/controller/MemoryController.java#L67)
DELETE | `/api/v1/memories/{id}` | [MemoryController.delete](../../backend/src/main/java/com/personalos/memory/controller/MemoryController.java#L77)
POST | `/api/v1/tasks` | [TaskController.create](../../backend/src/main/java/com/personalos/task/controller/TaskController.java#L72)
GET | `/api/v1/tasks` | [TaskController.list](../../backend/src/main/java/com/personalos/task/controller/TaskController.java#L87)
GET | `/api/v1/tasks/tags` | [TaskController.listTags](../../backend/src/main/java/com/personalos/task/controller/TaskController.java#L103)
GET | `/api/v1/tasks/stats` | [TaskController.stats](../../backend/src/main/java/com/personalos/task/controller/TaskController.java#L114)
GET | `/api/v1/tasks/{id}` | [TaskController.get](../../backend/src/main/java/com/personalos/task/controller/TaskController.java#L122)
PATCH | `/api/v1/tasks/{id}` | [TaskController.update](../../backend/src/main/java/com/personalos/task/controller/TaskController.java#L130)
POST | `/api/v1/tasks/{id}/complete` | [TaskController.complete](../../backend/src/main/java/com/personalos/task/controller/TaskController.java#L142)
POST | `/api/v1/tasks/{id}/reopen` | [TaskController.reopen](../../backend/src/main/java/com/personalos/task/controller/TaskController.java#L150)
DELETE | `/api/v1/tasks/{id}` | [TaskController.delete](../../backend/src/main/java/com/personalos/task/controller/TaskController.java#L158)

## 工具

类型 | 名称 | 来源
--- | --- | ---
Tool | `askUser` | [AgentUserTools](../../backend/src/main/java/com/personalos/agent/interaction/tool/AgentUserTools.java#L24)
Tool | `createPlan` | [AgentPlanTools](../../backend/src/main/java/com/personalos/agent/plan/tool/AgentPlanTools.java#L45)
Tool | `startPlanStep` | [AgentPlanTools](../../backend/src/main/java/com/personalos/agent/plan/tool/AgentPlanTools.java#L66)
Tool | `completePlanStep` | [AgentPlanTools](../../backend/src/main/java/com/personalos/agent/plan/tool/AgentPlanTools.java#L81)
Tool | `failPlanStep` | [AgentPlanTools](../../backend/src/main/java/com/personalos/agent/plan/tool/AgentPlanTools.java#L100)
Tool | `skipPlanStep` | [AgentPlanTools](../../backend/src/main/java/com/personalos/agent/plan/tool/AgentPlanTools.java#L118)
Tool | `revisePlan` | [AgentPlanTools](../../backend/src/main/java/com/personalos/agent/plan/tool/AgentPlanTools.java#L135)
Tool | `getPlan` | [AgentPlanTools](../../backend/src/main/java/com/personalos/agent/plan/tool/AgentPlanTools.java#L155)
Tool | `createTask` | [TaskTools](../../backend/src/main/java/com/personalos/agent/tool/TaskTools.java#L52)
Tool | `findTasks` | [TaskTools](../../backend/src/main/java/com/personalos/agent/tool/TaskTools.java#L85)
Tool | `getTask` | [TaskTools](../../backend/src/main/java/com/personalos/agent/tool/TaskTools.java#L113)
Tool | `updateTaskDetails` | [TaskTools](../../backend/src/main/java/com/personalos/agent/tool/TaskTools.java#L129)
Tool | `setTaskCompleted` | [TaskTools](../../backend/src/main/java/com/personalos/agent/tool/TaskTools.java#L158)
Tool | `deleteTask` | [TaskTools](../../backend/src/main/java/com/personalos/agent/tool/TaskTools.java#L183)
Tool | `scheduleTask` | [TaskTools](../../backend/src/main/java/com/personalos/agent/tool/TaskTools.java#L218)
Tool | `setTaskPriority` | [TaskTools](../../backend/src/main/java/com/personalos/agent/tool/TaskTools.java#L244)
Tool | `createCalendarEvent` | [CalendarTools](../../backend/src/main/java/com/personalos/calendar/tool/CalendarTools.java#L54)
Tool | `findCalendarEvents` | [CalendarTools](../../backend/src/main/java/com/personalos/calendar/tool/CalendarTools.java#L107)
Tool | `findDueReminders` | [CalendarTools](../../backend/src/main/java/com/personalos/calendar/tool/CalendarTools.java#L138)
Tool | `markReminderRead` | [CalendarTools](../../backend/src/main/java/com/personalos/calendar/tool/CalendarTools.java#L158)
Tool | `deleteCalendarEvent` | [CalendarTools](../../backend/src/main/java/com/personalos/calendar/tool/CalendarTools.java#L175)
Tool | `searchKnowledge` | [KnowledgeTools](../../backend/src/main/java/com/personalos/knowledge/tool/KnowledgeTools.java#L31)
McpTool | `personal_list_tasks` | [PersonalMcpTools](../../backend/src/main/java/com/personalos/mcp/server/PersonalMcpTools.java#L40)
McpTool | `personal_search_knowledge` | [PersonalMcpTools](../../backend/src/main/java/com/personalos/mcp/server/PersonalMcpTools.java#L59)
McpTool | `personal_list_calendar` | [PersonalMcpTools](../../backend/src/main/java/com/personalos/mcp/server/PersonalMcpTools.java#L70)
McpTool | `personal_list_memories` | [PersonalMcpTools](../../backend/src/main/java/com/personalos/mcp/server/PersonalMcpTools.java#L86)
Tool | `saveMemory` | [MemoryTools](../../backend/src/main/java/com/personalos/memory/tool/MemoryTools.java#L37)

## 运行钩子、装配条件及类型化配置

类型 | 来源 | 声明
--- | --- | ---
ConfigurationProperties | [PlanProperties:15](../../backend/src/main/java/com/personalos/agent/plan/PlanProperties.java#L15) | `@ConfigurationProperties(prefix = "app.plan")`
Bean | [CalendarConfig:23](../../backend/src/main/java/com/personalos/calendar/config/CalendarConfig.java#L23) | `@Bean`
Bean | [CalendarConfig:36](../../backend/src/main/java/com/personalos/calendar/config/CalendarConfig.java#L36) | `@Bean`
ConfigurationProperties | [CalendarProperties:19](../../backend/src/main/java/com/personalos/calendar/config/CalendarProperties.java#L19) | `@ConfigurationProperties(prefix = "app.calendar")`
ConditionalOnProperty | [ReminderBroadcast:24](../../backend/src/main/java/com/personalos/calendar/messaging/ReminderBroadcast.java#L24) | `@ConditionalOnProperty(name="app.rabbitmq.enabled",havingValue="true")`
Bean | [ReminderBroadcast:28](../../backend/src/main/java/com/personalos/calendar/messaging/ReminderBroadcast.java#L28) | `@Bean`
Bean | [ReminderBroadcast:30](../../backend/src/main/java/com/personalos/calendar/messaging/ReminderBroadcast.java#L30) | `@Bean`
ConditionalOnProperty | [ReminderBroadcast:36](../../backend/src/main/java/com/personalos/calendar/messaging/ReminderBroadcast.java#L36) | `@ConditionalOnProperty(name="app.rabbitmq.enabled",havingValue="true")`
ConditionalOnExpression | [ReminderBroadcast:37](../../backend/src/main/java/com/personalos/calendar/messaging/ReminderBroadcast.java#L37) | `@ConditionalOnExpression("'${app.runtime.role:combined}' == 'api' &#124;&#124; '${app.runtime.role:combined}' == 'combined'")`
Bean | [ReminderBroadcast:39](../../backend/src/main/java/com/personalos/calendar/messaging/ReminderBroadcast.java#L39) | `@Bean`
Bean | [ReminderBroadcast:45](../../backend/src/main/java/com/personalos/calendar/messaging/ReminderBroadcast.java#L45) | `@Bean`
Scheduled | [ReminderBroadcast:79](../../backend/src/main/java/com/personalos/calendar/messaging/ReminderBroadcast.java#L79) | `@Scheduled(fixedDelay=1000)`
PreDestroy | [ReminderBroadcast:105](../../backend/src/main/java/com/personalos/calendar/messaging/ReminderBroadcast.java#L105) | `@PreDestroy`
ConditionalOnProperty | [WebPushConfiguration:9](../../backend/src/main/java/com/personalos/calendar/push/WebPushConfiguration.java#L9) | `@ConditionalOnProperty(name = "app.web-push.enabled", havingValue = "true")`
Bean | [WebPushConfiguration:11](../../backend/src/main/java/com/personalos/calendar/push/WebPushConfiguration.java#L11) | `@Bean`
Bean | [WebPushConfiguration:20](../../backend/src/main/java/com/personalos/calendar/push/WebPushConfiguration.java#L20) | `@Bean`
ConditionalOnProperty | [WebPushScheduler:8](../../backend/src/main/java/com/personalos/calendar/push/WebPushScheduler.java#L8) | `@ConditionalOnProperty(name = "app.web-push.enabled", havingValue = "true")`
Scheduled | [WebPushScheduler:14](../../backend/src/main/java/com/personalos/calendar/push/WebPushScheduler.java#L14) | `@Scheduled(fixedDelayString = "${app.web-push.interval-ms:30000}", initialDelay = 15000, scheduler = "webPushTaskScheduler")`
ConditionalOnProperty | [ReminderScheduler:34](../../backend/src/main/java/com/personalos/calendar/scheduler/ReminderScheduler.java#L34) | `@ConditionalOnProperty(name = "app.calendar.scan-enabled", havingValue = "true", matchIfMissing = true)`
Scheduled | [ReminderScheduler:47](../../backend/src/main/java/com/personalos/calendar/scheduler/ReminderScheduler.java#L47) | `@Scheduled(fixedDelayString = "${app.calendar.scan-interval-ms:10000}", initialDelayString = "${app.calendar.scan-initial-delay-ms:10000}")`
Scheduled | [ReminderStreamDelivery:68](../../backend/src/main/java/com/personalos/calendar/service/ReminderStreamDelivery.java#L68) | `@Scheduled(fixedDelayString = "${app.calendar.stream-check-interval-ms:1000}")`
PreDestroy | [ReminderStreamDelivery:115](../../backend/src/main/java/com/personalos/calendar/service/ReminderStreamDelivery.java#L115) | `@PreDestroy`
ConditionalOnProperty | [ReminderWebhookWorker:27](../../backend/src/main/java/com/personalos/calendar/webhook/ReminderWebhookWorker.java#L27) | `@ConditionalOnProperty(name="app.reminders.webhook.enabled",havingValue="true")`
Scheduled | [ReminderWebhookWorker:56](../../backend/src/main/java/com/personalos/calendar/webhook/ReminderWebhookWorker.java#L56) | `@Scheduled(fixedDelayString="${app.reminders.webhook.poll-ms:5000}")`
PreDestroy | [ReminderWebhookWorker:135](../../backend/src/main/java/com/personalos/calendar/webhook/ReminderWebhookWorker.java#L135) | `@PreDestroy`
Scheduled | [ChatAttachmentJanitor:68](../../backend/src/main/java/com/personalos/chat/service/ChatAttachmentJanitor.java#L68) | `@Scheduled(cron = "${app.chat.attachment-janitor-cron:0 30 4 * * *}")`
EventListener | [ChatRunService:128](../../backend/src/main/java/com/personalos/chat/service/ChatRunService.java#L128) | `@EventListener(ApplicationReadyEvent.class)`
Scheduled | [ChatRunService:151](../../backend/src/main/java/com/personalos/chat/service/ChatRunService.java#L151) | `@org.springframework.scheduling.annotation.Scheduled(fixedDelay=1000, initialDelay=1000)`
PreDestroy | [RunEventDelivery:63](../../backend/src/main/java/com/personalos/chat/service/RunEventDelivery.java#L63) | `@PreDestroy`
ConditionalOnProperty | [S3StorageConfig:14](../../backend/src/main/java/com/personalos/chat/storage/S3StorageConfig.java#L14) | `@ConditionalOnProperty(name="app.storage.s3.enabled",havingValue="true")`
Bean | [S3StorageConfig:16](../../backend/src/main/java/com/personalos/chat/storage/S3StorageConfig.java#L16) | `@Bean(destroyMethod="close")`
Bean | [S3StorageConfig:29](../../backend/src/main/java/com/personalos/chat/storage/S3StorageConfig.java#L29) | `@Bean`
ConfigurationProperties | [AgentExecutionProperties:7](../../backend/src/main/java/com/personalos/common/ai/AgentExecutionProperties.java#L7) | `@ConfigurationProperties(prefix = "app.agent")`
ConfigurationProperties | [AiProperties:11](../../backend/src/main/java/com/personalos/common/ai/AiProperties.java#L11) | `@ConfigurationProperties(prefix = "app.ai")`
ConfigurationProperties | [ContextProperties:19](../../backend/src/main/java/com/personalos/common/ai/ContextProperties.java#L19) | `@ConfigurationProperties(prefix = "app.context")`
ConfigurationProperties | [ContextProtectionProperties:6](../../backend/src/main/java/com/personalos/common/ai/ContextProtectionProperties.java#L6) | `@ConfigurationProperties(prefix = "app.context.protection")`
Bean | [EmbeddingCacheConfig:14](../../backend/src/main/java/com/personalos/common/ai/cache/EmbeddingCacheConfig.java#L14) | `@Bean`
ConditionalOnProperty | [RedisCacheConfig:12](../../backend/src/main/java/com/personalos/common/ai/cache/RedisCacheConfig.java#L12) | `@ConditionalOnProperty(name="app.redis.enabled",havingValue="true")`
Bean | [RedisCacheConfig:14](../../backend/src/main/java/com/personalos/common/ai/cache/RedisCacheConfig.java#L14) | `@Bean(destroyMethod="close")`
Bean | [RedisCacheConfig:20](../../backend/src/main/java/com/personalos/common/ai/cache/RedisCacheConfig.java#L20) | `@Bean`
EventListener | [ModelConfigService:464](../../backend/src/main/java/com/personalos/common/ai/config/ModelConfigService.java#L464) | `@org.springframework.context.event.EventListener( org.springframework.boot.context.event.ApplicationReadyEvent.class)`
ConfigurationProperties | [AuthProperties:32](../../backend/src/main/java/com/personalos/common/auth/AuthProperties.java#L32) | `@ConfigurationProperties(prefix = "app.auth")`
Bean | [MonitoringSecurityConfig:14](../../backend/src/main/java/com/personalos/common/auth/MonitoringSecurityConfig.java#L14) | `@Bean`
Bean | [SecurityConfig:78](../../backend/src/main/java/com/personalos/common/auth/SecurityConfig.java#L78) | `@Bean`
Bean | [SecurityConfig:123](../../backend/src/main/java/com/personalos/common/auth/SecurityConfig.java#L123) | `@Bean`
ConditionalOnProperty | [SharedSessionConfig:19](../../backend/src/main/java/com/personalos/common/auth/SharedSessionConfig.java#L19) | `@ConditionalOnProperty(name = "app.shared-session.enabled", havingValue = "true")`
Bean | [SharedSessionConfig:22](../../backend/src/main/java/com/personalos/common/auth/SharedSessionConfig.java#L22) | `@Bean`
Bean | [SharedSessionConfig:48](../../backend/src/main/java/com/personalos/common/auth/SharedSessionConfig.java#L48) | `@Bean`
ConfigurationProperties | [AppProperties:14](../../backend/src/main/java/com/personalos/common/config/AppProperties.java#L14) | `@ConfigurationProperties(prefix = "app")`
Bean | [ContextTaskConfig:34](../../backend/src/main/java/com/personalos/common/config/ContextTaskConfig.java#L34) | `@Bean(name = "contextTaskExecutor", destroyMethod = "shutdown")`
ConfigurationProperties | [RateLimitProperties:22](../../backend/src/main/java/com/personalos/common/config/RateLimitProperties.java#L22) | `@ConfigurationProperties(prefix = "app.rate-limit")`
Scheduled | [DataRetentionService:36](../../backend/src/main/java/com/personalos/common/maintenance/DataRetentionService.java#L36) | `@Scheduled(cron = "${app.retention.cron:0 30 4 * * *}")`
PreDestroy | [DatabaseCoordination:116](../../backend/src/main/java/com/personalos/common/web/DatabaseCoordination.java#L116) | `@PreDestroy`
PostConstruct | [MdcSchedulerHook:35](../../backend/src/main/java/com/personalos/common/web/MdcSchedulerHook.java#L35) | `@PostConstruct`
ConditionalOnProperty | [RedisRateBucket:15](../../backend/src/main/java/com/personalos/common/web/RedisRateBucket.java#L15) | `@ConditionalOnProperty(name = "app.rate-limit.redis.enabled", havingValue = "true")`
PreDestroy | [RedisRateBucket:56](../../backend/src/main/java/com/personalos/common/web/RedisRateBucket.java#L56) | `@PreDestroy`
ConfigurationProperties | [KnowledgeProperties:21](../../backend/src/main/java/com/personalos/knowledge/config/KnowledgeProperties.java#L21) | `@ConfigurationProperties(prefix = "app.knowledge")`
ConditionalOnProperty | [IngestionReviewWorker:18](../../backend/src/main/java/com/personalos/knowledge/governance/IngestionReviewWorker.java#L18) | `@ConditionalOnProperty(name={"app.knowledge.rag.enabled","app.knowledge.rag.ingestion-review-enabled"},havingValue="true",matchIfMissing=true)`
Scheduled | [IngestionReviewWorker:49](../../backend/src/main/java/com/personalos/knowledge/governance/IngestionReviewWorker.java#L49) | `@Scheduled(fixedDelayString="${app.knowledge.rag.review-poll-ms:5000}",initialDelay=10000)`
PreDestroy | [IngestionReviewWorker:102](../../backend/src/main/java/com/personalos/knowledge/governance/IngestionReviewWorker.java#L102) | `@PreDestroy`
Scheduled | [KnowledgeIndexJobs:93](../../backend/src/main/java/com/personalos/knowledge/jobs/KnowledgeIndexJobs.java#L93) | `@Scheduled(fixedDelayString="${app.knowledge.jobs.poll-ms:1000}",initialDelay=1000)`
Scheduled | [KnowledgeIndexJobs:164](../../backend/src/main/java/com/personalos/knowledge/jobs/KnowledgeIndexJobs.java#L164) | `@Scheduled(cron="0 20 4 * * *")`
PreDestroy | [KnowledgeIndexJobs:165](../../backend/src/main/java/com/personalos/knowledge/jobs/KnowledgeIndexJobs.java#L165) | `@PreDestroy`
ConditionalOnProperty | [RabbitJobsConfig:14](../../backend/src/main/java/com/personalos/knowledge/jobs/messaging/RabbitJobsConfig.java#L14) | `@ConditionalOnProperty(name="app.rabbitmq.enabled",havingValue="true")`
Bean | [RabbitJobsConfig:16](../../backend/src/main/java/com/personalos/knowledge/jobs/messaging/RabbitJobsConfig.java#L16) | `@Bean(destroyMethod="destroy")`
Bean | [RabbitJobsConfig:23](../../backend/src/main/java/com/personalos/knowledge/jobs/messaging/RabbitJobsConfig.java#L23) | `@Bean`
Bean | [RabbitJobsConfig:27](../../backend/src/main/java/com/personalos/knowledge/jobs/messaging/RabbitJobsConfig.java#L27) | `@Bean`
Bean | [RabbitJobsConfig:28](../../backend/src/main/java/com/personalos/knowledge/jobs/messaging/RabbitJobsConfig.java#L28) | `@Bean`
Bean | [RabbitJobsConfig:31](../../backend/src/main/java/com/personalos/knowledge/jobs/messaging/RabbitJobsConfig.java#L31) | `@Bean`
Bean | [RabbitJobsConfig:34](../../backend/src/main/java/com/personalos/knowledge/jobs/messaging/RabbitJobsConfig.java#L34) | `@Bean`
ConditionalOnProperty | [WorkSignalRelay:19](../../backend/src/main/java/com/personalos/knowledge/jobs/messaging/WorkSignalRelay.java#L19) | `@ConditionalOnProperty(name="app.rabbitmq.enabled",havingValue="true")`
Scheduled | [WorkSignalRelay:35](../../backend/src/main/java/com/personalos/knowledge/jobs/messaging/WorkSignalRelay.java#L35) | `@Scheduled(fixedDelay=1000)`
PreDestroy | [WorkSignalRelay:65](../../backend/src/main/java/com/personalos/knowledge/jobs/messaging/WorkSignalRelay.java#L65) | `@PreDestroy`
ConditionalOnProperty | [WorkerWakeupsConfig:18](../../backend/src/main/java/com/personalos/knowledge/jobs/messaging/WorkerWakeupsConfig.java#L18) | `@ConditionalOnProperty(name="app.rabbitmq.enabled",havingValue="true")`
Bean | [WorkerWakeupsConfig:20](../../backend/src/main/java/com/personalos/knowledge/jobs/messaging/WorkerWakeupsConfig.java#L20) | `@Bean`
Bean | [WorkerWakeupsConfig:24](../../backend/src/main/java/com/personalos/knowledge/jobs/messaging/WorkerWakeupsConfig.java#L24) | `@Bean`
Bean | [WorkerWakeupsConfig:29](../../backend/src/main/java/com/personalos/knowledge/jobs/messaging/WorkerWakeupsConfig.java#L29) | `@Bean`
ConditionalOnProperty | [CrossEncoderReranker:15](../../backend/src/main/java/com/personalos/knowledge/rag/CrossEncoderReranker.java#L15) | `@ConditionalOnProperty(name="app.knowledge.rag.reranker.enabled",havingValue="true")`
PreDestroy | [RagModelGateway:43](../../backend/src/main/java/com/personalos/knowledge/rag/RagModelGateway.java#L43) | `@PreDestroy`
ConditionalOnProperty | [RagRetrievalPipeline:18](../../backend/src/main/java/com/personalos/knowledge/rag/RagRetrievalPipeline.java#L18) | `@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="app.knowledge.rag.enabled",havingValue="true",matchIfMissing=true)`
ConditionalOnProperty | [SearchAdminController:8](../../backend/src/main/java/com/personalos/knowledge/search/SearchAdminController.java#L8) | `@ConditionalOnProperty(name="app.knowledge.search.enabled",havingValue="true")`
ConditionalOnProperty | [SearchConfig:13](../../backend/src/main/java/com/personalos/knowledge/search/SearchConfig.java#L13) | `@ConditionalOnProperty(name="app.knowledge.search.enabled",havingValue="true")`
Bean | [SearchConfig:15](../../backend/src/main/java/com/personalos/knowledge/search/SearchConfig.java#L15) | `@Bean`
Bean | [SearchConfig:21](../../backend/src/main/java/com/personalos/knowledge/search/SearchConfig.java#L21) | `@Bean`
Scheduled | [SearchProjection:38](../../backend/src/main/java/com/personalos/knowledge/search/SearchProjection.java#L38) | `@Scheduled(fixedDelayString="${app.knowledge.search.poll-ms:2000}")`
PreDestroy | [SearchProjection:82](../../backend/src/main/java/com/personalos/knowledge/search/SearchProjection.java#L82) | `@PreDestroy`
ConditionalOnExpression | [RedisCandidateCache:20](../../backend/src/main/java/com/personalos/knowledge/service/RedisCandidateCache.java#L20) | `@ConditionalOnExpression("${app.redis.enabled:false} && ${app.knowledge.candidate-cache.enabled:false}")`
ConditionalOnProperty | [McpClientRegistry:43](../../backend/src/main/java/com/personalos/mcp/client/McpClientRegistry.java#L43) | `@ConditionalOnProperty(name = "mcp.enabled", havingValue = "true")`
PostConstruct | [McpClientRegistry:80](../../backend/src/main/java/com/personalos/mcp/client/McpClientRegistry.java#L80) | `@PostConstruct`
ConfigurationProperties | [McpProperties:34](../../backend/src/main/java/com/personalos/mcp/config/McpProperties.java#L34) | `@ConfigurationProperties(prefix = "mcp")`
ConditionalOnProperty | [McpToolConfig:20](../../backend/src/main/java/com/personalos/mcp/config/McpToolConfig.java#L20) | `@ConditionalOnProperty(name = "mcp.enabled", havingValue = "true")`
Bean | [McpToolConfig:23](../../backend/src/main/java/com/personalos/mcp/config/McpToolConfig.java#L23) | `@Bean`
ConditionalOnProperty | [McpToolConflictValidator:31](../../backend/src/main/java/com/personalos/mcp/config/McpToolConflictValidator.java#L31) | `@ConditionalOnProperty(name = "mcp.enabled", havingValue = "true")`
PostConstruct | [McpToolConflictValidator:50](../../backend/src/main/java/com/personalos/mcp/config/McpToolConflictValidator.java#L50) | `@PostConstruct`
ConfigurationProperties | [MemoryProperties:21](../../backend/src/main/java/com/personalos/memory/MemoryProperties.java#L21) | `@ConfigurationProperties(prefix = "app.memory")`

## 环境变量引用

变量 | 引用次数
--- | ---
`ACME_EMAIL` | 1
`ACTUATOR_HEALTH_DETAILS` | 1
`AI_AGENT_MAX_CONSECUTIVE_FAILURES` | 1
`AI_AGENT_MAX_MODEL_CALLS` | 1
`AI_CALENDAR_DEFAULT_LEAD` | 1
`AI_CALENDAR_DEFAULT_QUERY_LIMIT` | 1
`AI_CALENDAR_MAX_QUERY_LIMIT` | 1
`AI_CALENDAR_MAX_RANGE_DAYS` | 1
`AI_CALENDAR_SCAN_ENABLED` | 1
`AI_CALENDAR_SCAN_INITIAL_DELAY_MS` | 1
`AI_CALENDAR_SCAN_INTERVAL_MS` | 1
`AI_CALENDAR_SCAN_LOOKAHEAD` | 1
`AI_CALENDAR_SCAN_LOOKBACK` | 1
`AI_CALENDAR_STREAM_CHECK_INTERVAL_MS` | 1
`AI_CHAT_MAX_ATTACHMENT_BYTES` | 1
`AI_CHAT_PROVIDER` | 1
`AI_CHAT_STREAM_TIMEOUT` | 1
`AI_CHAT_UPLOAD_DIR` | 1
`AI_CONTEXT_AUTO_MEMORY_ENABLED` | 1
`AI_CONTEXT_AUTO_MEMORY_MAX_ITEMS` | 1
`AI_CONTEXT_SUMMARY_ENABLED` | 1
`AI_CONTEXT_SUMMARY_MAX_CHARS` | 1
`AI_CONTEXT_SUMMARY_MAX_EXCERPT` | 1
`AI_CONTEXT_SUMMARY_MIN_MESSAGES` | 1
`AI_EMBEDDING_PROVIDER` | 1
`AI_KNOWLEDGE_ALLOWED_EXTENSIONS` | 1
`AI_KNOWLEDGE_CHUNK_OVERLAP_CHARS` | 1
`AI_KNOWLEDGE_CHUNK_SIZE_CHARS` | 1
`AI_KNOWLEDGE_EMBEDDING_DIMENSIONS` | 2
`AI_KNOWLEDGE_HYBRID_ENABLED` | 1
`AI_KNOWLEDGE_KEYWORD_CHANNEL_WEIGHT` | 1
`AI_KNOWLEDGE_MAX_FILE_SIZE` | 1
`AI_KNOWLEDGE_MIN_CHUNK_SIZE_CHARS` | 1
`AI_KNOWLEDGE_RRF_K` | 1
`AI_KNOWLEDGE_SIMILARITY_THRESHOLD` | 1
`AI_KNOWLEDGE_TOP_K` | 1
`AI_MEMORY_MAX_CONTEXT_CHARS` | 1
`AI_MEMORY_MAX_MESSAGES` | 1
`AI_MODEL_MAX_CONCURRENT_REQUESTS` | 1
`AI_RETRY_MAX_ATTEMPTS` | 1
`AI_SYSTEM_PROMPT` | 1
`AI_TOOL_MAX_CALLS_PER_TOOL` | 1
`AI_TOOL_MAX_TOTAL_CALLS` | 1
`AI_TOOL_ON_LIMIT_EXCEEDED` | 1
`APP_MEMORY_BACKFILL_BATCH` | 1
`APP_MEMORY_BACKFILL_ENABLED` | 1
`APP_MEMORY_HALF_LIFE_DAYS` | 1
`APP_MEMORY_PINNED_MAX` | 1
`APP_MEMORY_RANKING_ENABLED` | 1
`APP_MEMORY_RANKING_TIMEOUT` | 1
`APP_MEMORY_SIMILAR_THRESHOLD` | 1
`APP_PLAN_ENFORCE` | 1
`APP_PLAN_MAX_INJECT_CHARS` | 1
`APP_RATE_LIMIT_CAPACITY` | 1
`APP_RATE_LIMIT_ENABLED` | 1
`APP_RATE_LIMIT_PERIOD` | 1
`APP_SECURITY_CSRF_ENABLED` | 1
`APP_SECURITY_PRODUCTION_MODE` | 1
`ASYNC_REQUEST_TIMEOUT` | 1
`AUTH_ENABLED` | 1
`AUTH_PASSWORD` | 1
`AUTH_USERNAME` | 1
`AWS_ACCESS_KEY_ID` | 1
`AWS_SECRET_ACCESS_KEY` | 1
`CONTEXT_IMAGE_RESERVE_TOKENS` | 1
`CONTEXT_MAX_RETRIES` | 1
`CONTEXT_OUTPUT_RESERVE_TOKENS` | 1
`CONTEXT_SAFETY_MARGIN_TOKENS` | 1
`DOMAIN` | 1
`EMBEDDING_CACHE_CAPACITY` | 1
`EMBEDDING_CACHE_ENABLED` | 1
`EMBEDDING_CACHE_TTL` | 1
`GITHUB_TOKEN` | 1
`GRAFANA_ADMIN_PASSWORD` | 2
`GRAFANA_ADMIN_USER` | 1
`LOCAL_RABBITMQ_HOSTNAME` | 1
`MODEL_KEY_ENCRYPTION_KEY` | 1
`OLLAMA_BASE_URL` | 1
`OLLAMA_CHAT_MODEL` | 1
`OLLAMA_EMBEDDING_MODEL` | 1
`OPENAI_API_KEY` | 1
`OPENAI_BASE_URL` | 1
`OPENAI_CHAT_MODEL` | 1
`OPENAI_EMBEDDING_MODEL` | 1
`OPENAI_ENABLE_THINKING` | 1
`OPENSEARCH_ENABLED` | 1
`OPENSEARCH_ENDPOINT` | 1
`OPENSEARCH_INDEX` | 1
`OPENSEARCH_PASSWORD` | 1
`OPENSEARCH_USERNAME` | 1
`OPERATION_RECORD_RETENTION_DAYS` | 1
`PERSONAL_MCP_SERVER_ENABLED` | 1
`PERSONAL_MCP_SERVER_ENDPOINT` | 1
`PERSONAL_MCP_SERVER_INSTRUCTIONS` | 1
`PERSONAL_MCP_SERVER_NAME` | 1
`PERSONAL_MCP_SERVER_PROTOCOL` | 1
`PERSONAL_MCP_SERVER_VERSION` | 1
`POSTGRES_DB` | 7
`POSTGRES_HOST` | 1
`POSTGRES_PASSWORD` | 5
`POSTGRES_PORT` | 2
`POSTGRES_USER` | 7
`RABBITMQ_DEFAULT_PASS` | 1
`RABBITMQ_ENABLED` | 1
`RABBITMQ_PASSWORD` | 1
`RABBITMQ_QUEUE` | 1
`RABBITMQ_URI` | 1
`RAG_BUDGET_MS` | 1
`RAG_CANDIDATE_CACHE_ENABLED` | 1
`RAG_ENABLED` | 1
`RAG_EVIDENCE_TIMEOUT_MS` | 1
`RAG_RERANKER_API_KEY` | 1
`RAG_RERANKER_ENABLED` | 1
`RAG_RERANKER_ENDPOINT` | 1
`RAG_RERANKER_MODEL` | 1
`RAG_RERANKER_PROTOCOL` | 1
`REDIS_ENABLED` | 1
`REDIS_NAMESPACE` | 1
`REDIS_PASSWORD` | 2
`REDIS_RATE_LIMIT_ENABLED` | 1
`REDIS_RATE_LIMIT_NAMESPACE` | 1
`REDIS_RATE_LIMIT_URI` | 1
`REDIS_SESSION_ENABLED` | 1
`REDIS_SESSION_NAMESPACE` | 1
`REDIS_SESSION_URI` | 1
`REDIS_TIMEOUT_MS` | 1
`REDIS_URI` | 1
`REMINDER_WEBHOOK_ENABLED` | 1
`REMINDER_WEBHOOK_SECRET` | 1
`REMINDER_WEBHOOK_URL` | 1
`S3_ACCESS_KEY` | 2
`S3_BUCKET` | 1
`S3_ENABLED` | 1
`S3_ENDPOINT` | 1
`S3_NAMESPACE` | 1
`S3_ORPHAN_CLEANUP_ENABLED` | 1
`S3_REGION` | 1
`S3_SECRET_KEY` | 2
`SERVER_PORT` | 1
`SESSION_COOKIE_SECURE` | 1
`TOKEN_USAGE_RETENTION_DAYS` | 1
`WEB_PUSH_ENABLED` | 1
`WEB_PUSH_PRIVATE_KEY` | 1
`WEB_PUSH_PUBLIC_KEY` | 1
`WEB_PUSH_SUBJECT` | 1

配置属性的完整默认值与 profile 以清单中的 application*.yml、@Value、@ConfigurationProperties 和 .env.example 为准。
Spring relaxed binding、动态 MCP 配置和程序读取环境变量不一定使用 ${...}，不能只迁移本表。
